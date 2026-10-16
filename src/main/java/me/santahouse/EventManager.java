package me.santahouse;

import java.io.File;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

public final class EventManager {

    private record Saved(int x, int y, int z, BlockData data) {
    }

    private record LootEntry(Material material, int min, int max, String name, int weight) {
    }

    private record BonusCmd(double chance, String command) {
    }

    private final SantaHouse plugin;

    // настройки
    private String prefix = "";
    private String worldName = "grief-1";
    private String worldDisplay = "Обычный мир";
    private ZoneId zone = ZoneId.of("Europe/Moscow");
    private String zoneLabel = "МСК";
    private final List<LocalTime> times = new ArrayList<>();
    private List<Integer> announceMinutes = new ArrayList<>();
    private int durationMin = 20;
    private int centerX;
    private int centerZ;
    private int minR = 300;
    private int maxR = 3000;
    private int clearance = 6;
    private int maxSlope = 4;
    private boolean avoidClaims = true;
    private int claimMargin = 10;
    private ProtectionHook protection;
    private boolean removeAfter = true;
    private SchematicLoader.Schem schem;
    private int yOffset;
    private boolean beaconEnabled = true;
    private boolean noPlace = true;
    private int noPlaceRadius = 20;
    private final Set<Material> allowedPlace = new HashSet<>();
    private int lootMin = 2;
    private int lootMax = 5;
    private final List<LootEntry> loot = new ArrayList<>();
    private final List<BonusCmd> bonus = new ArrayList<>();

    // состояние
    private boolean active;
    private long endMillis;
    private World world;
    private int cx;
    private int cy;
    private int cz;
    private int bx;
    private int bz;
    private int by;
    private boolean beaconBuilt;
    /** Все блоки дома и маяка: их нельзя ломать. */
    private final Set<Long> protectedBlocks = new HashSet<>();
    private final Set<Long> containers = new HashSet<>();
    private final Set<Long> opened = new HashSet<>();
    private final Set<Long> savedKeys = new HashSet<>();
    private final List<Saved> saved = new ArrayList<>();
    private int foundCount;
    private BossBar bar;
    private BukkitTask task;
    private String lastStartKey = "";
    private final Set<String> announced = new HashSet<>();

    public EventManager(SantaHouse plugin) {
        this.plugin = plugin;
    }

    // ===================== Настройки =====================

    private static int intOf(Object o, int def) {
        return (o instanceof Number) ? ((Number) o).intValue() : def;
    }

    private static double doubleOf(Object o, double def) {
        return (o instanceof Number) ? ((Number) o).doubleValue() : def;
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        prefix = color(c.getString("prefix", "&b&l❄ &4&lДом Санты &8&l» &7"));
        worldName = c.getString("world", "grief-1");
        worldDisplay = c.getString("world-display-name", "Обычный мир");
        clearance = Math.max(0, c.getInt("location.clearance", 6));
        maxSlope = Math.max(0, c.getInt("location.max-slope", 4));
        avoidClaims = c.getBoolean("protection.avoid-claims", true);
        claimMargin = Math.max(0, c.getInt("protection.margin", 10));
        protection = ProtectionHook.create(plugin.getLogger());
        if (avoidClaims) {
            if (protection.any()) {
                plugin.getLogger().info("Обход приватов включён: " + protection.names());
            } else {
                plugin.getLogger().warning("WorldGuard/GriefPrevention не найдены: приваты не проверяются!");
            }
        }
        try {
            zone = ZoneId.of(c.getString("timezone", "Europe/Moscow"));
        } catch (Exception ex) {
            zone = ZoneId.of("Europe/Moscow");
        }
        zoneLabel = c.getString("timezone-label", "МСК");
        times.clear();
        for (String s : c.getStringList("start-times")) {
            try {
                times.add(LocalTime.parse(s.trim()));
            } catch (Exception ex) {
                plugin.getLogger().warning("Не понял время запуска '" + s + "' (нужен формат 08:00)");
            }
        }
        Collections.sort(times);
        announceMinutes = c.getIntegerList("announce-before-minutes");
        durationMin = Math.max(1, c.getInt("duration-minutes", 20));
        centerX = c.getInt("location.center-x", 0);
        centerZ = c.getInt("location.center-z", 0);
        minR = Math.max(0, c.getInt("location.min-radius", 300));
        maxR = Math.max(minR + 1, c.getInt("location.max-radius", 3000));

        yOffset = c.getInt("house.y-offset", 0);
        beaconEnabled = c.getBoolean("house.beacon", true);
        noPlace = c.getBoolean("event-area.block-placing-denied", true);
        noPlaceRadius = Math.max(5, c.getInt("event-area.radius", 20));
        allowedPlace.clear();
        for (String n : c.getStringList("event-area.allowed-blocks")) {
            Material am = Material.matchMaterial(n.trim());
            if (am != null) {
                allowedPlace.add(am);
            }
        }

        lootMin = Math.max(0, c.getInt("loot.items-per-container.min", 2));
        lootMax = Math.max(lootMin, c.getInt("loot.items-per-container.max", 5));
        loot.clear();
        for (Map<?, ?> m : c.getMapList("loot.items")) {
            Object mo = m.get("material");
            Material mat = (mo == null) ? null : Material.matchMaterial(String.valueOf(mo));
            if (mat == null) {
                plugin.getLogger().warning("Неизвестный материал в loot.items: " + mo);
                continue;
            }
            int mn = Math.max(1, intOf(m.get("min"), 1));
            int mx = Math.max(mn, intOf(m.get("max"), mn));
            Object no = m.get("name");
            String nm = (no == null) ? null : color(String.valueOf(no));
            int wt = Math.max(1, intOf(m.get("weight"), 10));
            loot.add(new LootEntry(mat, mn, mx, nm, wt));
        }
        if (loot.isEmpty()) {
            plugin.getLogger().warning("loot.items пуст — контейнеры будут пустыми!");
        }
        bonus.clear();
        for (Map<?, ?> m : c.getMapList("loot.bonus-commands")) {
            Object co = m.get("command");
            if (co != null) {
                bonus.add(new BonusCmd(doubleOf(m.get("chance"), 1.0), String.valueOf(co)));
            }
        }

        schem = null;
        File dir = new File(plugin.getDataFolder(), "schematics");
        dir.mkdirs();
        File f = new File(dir, c.getString("house.schematic-file", "domsanta.schem"));
        if (!f.isFile()) {
            plugin.getLogger().warning("Файл схематики не найден: " + f.getPath() + ". Ивент запускаться не будет!");
        } else {
            try {
                schem = SchematicLoader.load(f);
                plugin.getLogger().info("Схематика загружена: " + f.getName() + " (" + schem.width + "x"
                        + schem.height + "x" + schem.length + ")");
            } catch (Exception ex) {
                plugin.getLogger().warning("Не удалось прочитать схематику " + f.getName() + ": " + ex);
            }
        }

        removeAfter = c.getBoolean("remove-house-after-end", true);
    }

    public String prefix() {
        return prefix;
    }

    public boolean isActive() {
        return active;
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ===================== Оформление (тема: зимний дом Санты) =====================

    private static String header() {
        return "§8§m        §r §b❄ §4§lДОМ §f§lСАНТЫ §b❄ §8§m        §r";
    }

    private static String footer() {
        return "§8§m                                        §r";
    }

    // ===================== Расписание =====================

    public void startScheduler() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        if (active) {
            cleanup();
        }
    }

    private ZonedDateTime occurrence(LocalTime t, ZonedDateTime now, int dayOffset) {
        return now.toLocalDate().plusDays(dayOffset).atTime(t).atZone(now.getZone());
    }

    private ZonedDateTime nextStart(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (LocalTime t : times) {
            for (int off = 0; off <= 1; off++) {
                ZonedDateTime z = occurrence(t, now, off);
                if (z.isAfter(now) && (best == null || z.isBefore(best))) {
                    best = z;
                }
            }
        }
        return best;
    }

    private ZonedDateTime lastStart(ZonedDateTime now) {
        ZonedDateTime best = null;
        for (LocalTime t : times) {
            for (int off = -1; off <= 0; off++) {
                ZonedDateTime z = occurrence(t, now, off);
                if (!z.isAfter(now) && (best == null || z.isAfter(best))) {
                    best = z;
                }
            }
        }
        return best;
    }

    private void tick() {
        try {
            if (active) {
                updateBar();
                if (System.currentTimeMillis() >= endMillis) {
                    finish();
                }
                return;
            }
            if (times.isEmpty()) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime last = lastStart(now);
            if (last != null && Duration.between(last, now).getSeconds() < 5
                    && !last.toString().equals(lastStartKey)) {
                lastStartKey = last.toString();
                start();
                return;
            }
            ZonedDateTime next = nextStart(now);
            if (next != null) {
                long sec = Duration.between(now, next).getSeconds();
                for (int m : announceMinutes) {
                    String key = next + ":" + m;
                    if (sec <= m * 60L && sec > m * 60L - 10 && announced.add(key)) {
                        Bukkit.broadcastMessage(prefix + "§b❄ §fДом Санты §7появится через §c§l" + m
                                + " мин§7! Внутри полно подарков. Подробнее: §b/santa");
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Ошибка в тике ивента: " + ex);
        }
    }

    // ===================== Запуск и окончание =====================

    public boolean start() {
        if (active) {
            return false;
        }
        if (schem == null) {
            plugin.getLogger().warning("Схематика дома не загружена — ивент не запущен.");
            return false;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            plugin.getLogger().warning("Мир '" + worldName + "' не найден!");
            return false;
        }
        int[] loc = findLocation(w);
        if (loc == null) {
            plugin.getLogger().warning("Не удалось найти подходящее место для дома.");
            return false;
        }
        world = w;
        cx = loc[0];
        cy = loc[1];
        cz = loc[2];
        announced.clear();
        foundCount = 0;
        build();

        active = true;
        endMillis = System.currentTimeMillis() + durationMin * 60_000L;
        bar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SEGMENTED_20);
        updateBar();

        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(header());
        Bukkit.broadcastMessage("§b❄ §c§lДОМ САНТЫ ПОЯВИЛСЯ! §b❄");
        Bukkit.broadcastMessage("§7Санта оставил дом, полный §bподарков§7. Открывай сундуки и бочки!");
        Bukkit.broadcastMessage("§8➤ §7Мир: §f" + worldDisplay);
        Bukkit.broadcastMessage("§8➤ §7Координаты: §fX §b" + cx + " §fY §b" + (cy + 1) + " §fZ §b" + cz
                + " §8(§7ищи красный луч маяка§8)");
        Bukkit.broadcastMessage("§8➤ §7Сундуков и бочек с подарками: §f" + containers.size()
                + "§7. Открывай и забирай всё, что внутри!");
        Bukkit.broadcastMessage("§8➤ §7Время: §c§l" + durationMin + " мин§7. Подарков хватит не всем! §b❄");
        Bukkit.broadcastMessage(footer());
        Bukkit.broadcastMessage("");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle("§b❄ §4§lДОМ §f§lСАНТЫ §b❄", "§7Найди дом и забери подарки!", 10, 80, 20);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }
        return true;
    }

    /** Остановка без наград (командой админа). */
    public void stop() {
        if (!active) {
            return;
        }
        Bukkit.broadcastMessage(prefix + "§cДом Санты убран администратором.");
        cleanup();
    }

    private void finish() {
        Bukkit.broadcastMessage("");
        Bukkit.broadcastMessage(header());
        Bukkit.broadcastMessage("§b❄ §c§lДОМ САНТЫ ЗАКРЫЛСЯ §b❄");
        Bukkit.broadcastMessage("§8➤ §7Открыто контейнеров: §f" + foundCount + "§7/§f" + containers.size());
        Bukkit.broadcastMessage("§7Дом исчезает в метели. До следующего раза! §b❄");
        Bukkit.broadcastMessage(footer());
        Bukkit.broadcastMessage("");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        cleanup();
    }

    private void cleanup() {
        if (world != null) {
            // закрываем открытые контейнеры, чтобы никто не держал инвентарь удаляемого дома
            for (Player p : world.getPlayers()) {
                if (Math.abs(p.getLocation().getBlockX() - cx) <= noPlaceRadius + 10
                        && Math.abs(p.getLocation().getBlockZ() - cz) <= noPlaceRadius + 10) {
                    p.closeInventory();
                }
            }
            if (removeAfter) {
                for (Saved s : saved) {
                    world.getBlockAt(s.x(), s.y(), s.z()).setBlockData(s.data(), false);
                }
            }
        }
        saved.clear();
        savedKeys.clear();
        protectedBlocks.clear();
        containers.clear();
        opened.clear();
        beaconBuilt = false;
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
        active = false;
        world = null;
    }

    // ===================== Место и дом =====================

    private static boolean isNaturalGround(Material m) {
        switch (m) {
            case GRASS_BLOCK: case DIRT: case COARSE_DIRT: case ROOTED_DIRT: case PODZOL: case MYCELIUM:
            case MUD: case CLAY: case SAND: case RED_SAND: case GRAVEL: case STONE: case GRANITE:
            case DIORITE: case ANDESITE: case SANDSTONE: case RED_SANDSTONE: case TERRACOTTA:
            case SNOW_BLOCK: case SNOW: case MOSS_BLOCK: case CALCITE: case TUFF: case DEEPSLATE:
            case DRIPSTONE_BLOCK: case NETHERRACK:
                return true;
            default:
                return m.name().endsWith("_TERRACOTTA");
        }
    }

    private int half() {
        return Math.max(schem.width, schem.length) / 2 + 1;
    }

    /**
     * Ищет место: сначала строгое (ровная природная земля без деревьев, построек и воды,
     * вне чужих приватов), затем запасное (только не вода и не чужой приват).
     * Возвращает {x центра, y верхней поверхности (самой высокой точки участка), z центра}.
     */
    private int[] findLocation(World w) {
        Random r = ThreadLocalRandom.current();
        int reach = half() + clearance;
        for (int pass = 0; pass < 2; pass++) {
            boolean strict = pass == 0;
            for (int i = 0; i < (strict ? 80 : 30); i++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double dist = minR + r.nextDouble() * (maxR - minR);
                int x = centerX + (int) (Math.cos(ang) * dist);
                int z = centerZ + (int) (Math.sin(ang) * dist);
                if (!w.getWorldBorder().isInside(new Location(w, x - reach, 64, z - reach))
                        || !w.getWorldBorder().isInside(new Location(w, x + reach, 64, z + reach))) {
                    continue;
                }
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                boolean bad = false;
                int step = strict ? 3 : Math.max(1, reach);
                for (int dx = -reach; dx <= reach && !bad; dx += step) {
                    for (int dz = -reach; dz <= reach; dz += step) {
                        int px = x + dx;
                        int pz = z + dz;
                        int yy = w.getHighestBlockYAt(px, pz, HeightMap.MOTION_BLOCKING);
                        Material m = w.getBlockAt(px, yy, pz).getType();
                        if (strict ? !isNaturalGround(m)
                                : (m == Material.WATER || m == Material.LAVA || m == Material.ICE
                                        || m == Material.BLUE_ICE || m == Material.PACKED_ICE
                                        || m == Material.BEDROCK)) {
                            bad = true;
                            break;
                        }
                        minY = Math.min(minY, yy);
                        maxY = Math.max(maxY, yy);
                    }
                }
                if (bad || (strict && maxY - minY > maxSlope)) {
                    continue;
                }
                int centerY = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
                int base = strict ? maxY : centerY;
                if (avoidClaims && protection != null && protection.any()) {
                    int m = claimMargin;
                    if (protection.isClaimed(w, x - reach - m, base - 15, z - reach - m,
                            x + reach + m, base + 35, z + reach + m)) {
                        continue;
                    }
                }
                return new int[]{x, base, z};
            }
        }
        return null;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }

    private static long key(Block b) {
        return key(b.getX(), b.getY(), b.getZ());
    }

    /** Запоминает исходный блок (один раз) для отката. */
    private void save(Block b) {
        if (savedKeys.add(key(b))) {
            saved.add(new Saved(b.getX(), b.getY(), b.getZ(), b.getBlockData()));
        }
    }

    private boolean inWorld(int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    /**
     * Вставляет дом так, чтобы нижний слой схематики (трава и пол) лёг на уровень земли.
     * Выше пола воздух тоже вставляется: так внутри дома не остаётся травы и снега.
     */
    private void build() {
        saved.clear();
        savedKeys.clear();
        protectedBlocks.clear();
        containers.clear();
        opened.clear();
        beaconBuilt = false;

        int w = schem.width;
        int h = schem.height;
        int l = schem.length;
        int ox = cx - w / 2;
        int oz = cz - l / 2;
        int oy = cy + yOffset;
        List<int[]> containerPos = new ArrayList<>();

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < l; z++) {
                for (int x = 0; x < w; x++) {
                    BlockData d = schem.data[x + z * w + y * w * l];
                    if (d == null) {
                        continue;
                    }
                    boolean air = d.getMaterial().isAir();
                    if (air && y == 0) {
                        continue; // нижний слой: воздух не трогаем, чтобы не вырезать землю
                    }
                    int wx = ox + x;
                    int wy = oy + y;
                    int wz = oz + z;
                    if (!inWorld(wy)) {
                        continue;
                    }
                    Block b = world.getBlockAt(wx, wy, wz);
                    save(b);
                    b.setBlockData(d, false);
                    if (!air) {
                        protectedBlocks.add(key(wx, wy, wz));
                        Material m = d.getMaterial();
                        if (m == Material.CHEST || m == Material.BARREL) {
                            containerPos.add(new int[]{wx, wy, wz});
                        }
                    }
                }
            }
        }

        // фундамент: под нижним слоем засыпаем пустоты землёй, чтобы дом не висел на склоне
        for (int z = 0; z < l; z++) {
            for (int x = 0; x < w; x++) {
                BlockData d = schem.data[x + z * w];
                if (d == null || d.getMaterial().isAir()) {
                    continue;
                }
                for (int yy = oy - 1; yy >= oy - 12 && inWorld(yy); yy--) {
                    Block b = world.getBlockAt(ox + x, yy, oz + z);
                    if (b.getType().isSolid()) {
                        break;
                    }
                    save(b);
                    b.setType(Material.DIRT, false);
                    protectedBlocks.add(key(b));
                }
            }
        }

        // наполняем сундуки и бочки подарками
        Random rnd = ThreadLocalRandom.current();
        for (int[] p : containerPos) {
            Block b = world.getBlockAt(p[0], p[1], p[2]);
            BlockState st = b.getState();
            if (st instanceof Container) {
                Container ct = (Container) st;
                Inventory inv = (ct instanceof Chest) ? ((Chest) ct).getBlockInventory() : ct.getInventory();
                inv.clear();
                fillLoot(inv, rnd);
                long k = key(b);
                containers.add(k);
            }
        }
        if (containers.isEmpty()) {
            plugin.getLogger().warning("В схематике не найдено ни одного сундука или бочки — очки начисляться не будут!");
        }

        // красный маяк рядом с домом (виден издалека)
        if (beaconEnabled) {
            bx = ox + w + 3;
            bz = cz;
            by = world.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    put(bx + dx, by, bz + dz, "minecraft:iron_block");
                }
            }
            put(bx, by + 1, bz, "minecraft:beacon");
            put(bx, by + 2, bz, "minecraft:red_stained_glass");
            beaconBuilt = true;
        }
    }

    /** Ставит неломаемый блок со своим сохранением для отката. */
    private void put(int x, int y, int z, String data) {
        if (!inWorld(y)) {
            return;
        }
        Block b = world.getBlockAt(x, y, z);
        save(b);
        b.setBlockData(Bukkit.createBlockData(data), false);
        protectedBlocks.add(key(x, y, z));
    }

    private void fillLoot(Inventory inv, Random rnd) {
        if (loot.isEmpty() || lootMax <= 0) {
            return;
        }
        int total = 0;
        for (LootEntry e : loot) {
            total += e.weight();
        }
        int n = lootMin + rnd.nextInt(lootMax - lootMin + 1);
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, rnd);
        for (int i = 0; i < n && i < slots.size(); i++) {
            int roll = rnd.nextInt(total);
            LootEntry pick = loot.get(0);
            for (LootEntry e : loot) {
                roll -= e.weight();
                if (roll < 0) {
                    pick = e;
                    break;
                }
            }
            int amount = pick.min() + rnd.nextInt(pick.max() - pick.min() + 1);
            amount = Math.max(1, Math.min(amount, pick.material().getMaxStackSize()));
            ItemStack it = new ItemStack(pick.material(), amount);
            if (pick.name() != null) {
                ItemMeta im = it.getItemMeta();
                if (im != null) {
                    im.setDisplayName(pick.name());
                    it.setItemMeta(im);
                }
            }
            inv.setItem(slots.get(i), it);
        }
    }

    // ===================== Игровая логика =====================

    /** Блок дома или маяка: ломать нельзя. */
    public boolean isProtectedBlock(Block b) {
        return active && world != null && b.getWorld().equals(world) && protectedBlocks.contains(key(b));
    }

    /** Нельзя ставить блоки / лить жидкости в зоне ивента. */
    public boolean isNoBuildZone(Block b, Material placed) {
        return noPlace && active && world != null && b.getWorld().equals(world)
                && Math.abs(b.getX() - cx) <= noPlaceRadius && Math.abs(b.getZ() - cz) <= noPlaceRadius
                && !allowedPlace.contains(placed);
    }

    /** Блок над красным стеклом маяка: сюда нельзя ставить блоки (чтобы не закрыть луч). */
    public boolean isBeamColumn(Block b) {
        return active && beaconBuilt && world != null && b.getWorld().equals(world)
                && b.getX() == bx && b.getZ() == bz && b.getY() > by + 2;
    }

    public void deny(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    /** Игрок открыл контейнер (одну или обе половины двойного сундука). */
    public void onContainerOpen(Player p, List<Block> blocks) {
        if (!active || world == null || blocks.isEmpty() || !blocks.get(0).getWorld().equals(world)) {
            return;
        }
        boolean mine = false;
        int fresh = 0;
        for (Block b : blocks) {
            long k = key(b);
            if (containers.contains(k)) {
                mine = true;
                if (!opened.contains(k)) {
                    fresh++;
                }
            }
        }
        if (!mine) {
            return;
        }
        for (Block b : blocks) {
            opened.add(key(b));
        }
        if (fresh == 0) {
            return; // уже открывали раньше
        }
        foundCount += fresh;
        updateBar();

        Location at = blocks.get(0).getLocation().add(0.5, 1.0, 0.5);
        world.spawnParticle(Particle.SNOWFLAKE, at, 25, 0.4, 0.4, 0.4, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§b❄ §fПодарок найден §8| §7Открыто: §f"
                + foundCount + "§7/§f" + containers.size()));

        for (BonusCmd bc : bonus) {
            if (ThreadLocalRandom.current().nextDouble() < bc.chance()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), bc.command().replace("{player}", p.getName()));
            }
        }
    }

    // ===================== Боссбар =====================

    private void updateBar() {
        if (bar == null) {
            return;
        }
        long left = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
        double total = durationMin * 60.0;
        bar.setTitle("§b❄ §4§lДОМ §f§lСАНТЫ §b❄ §8§l» §fОсталось: §c§l" + mmss(left)
                + " §8§l» §bОткрыто: §f§l" + foundCount + "§7/§f§l" + containers.size() + " §b❄");
        bar.setProgress(Math.max(0.0, Math.min(1.0, left / total)));
        // последняя минута — полоса мигает красным и белым
        bar.setColor(left <= 60 ? (left % 2 == 0 ? BarColor.WHITE : BarColor.RED) : BarColor.BLUE);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bar.getPlayers().contains(p)) {
                bar.addPlayer(p);
            }
        }
    }

    public void showBar(Player p) {
        if (active && bar != null) {
            bar.addPlayer(p);
        }
    }

    // ===================== Тексты для /santa =====================

    private static String mmss(long s) {
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static String human(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        if (h > 0) {
            return h + " ч " + m + " мин";
        }
        if (m > 0) {
            return m + " мин";
        }
        return totalSeconds + " сек";
    }

    private String scheduleText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) {
                sb.append("§7, ");
            }
            sb.append("§e").append(times.get(i).toString());
        }
        return sb + " §7(" + zoneLabel + ")";
    }

    public List<String> infoLines() {
        List<String> out = new ArrayList<>();
        out.add(header());
        if (active) {
            long left = Math.max(0, (endMillis - System.currentTimeMillis()) / 1000);
            out.add("§b§lДом Санты стоит прямо сейчас! §7Осталось: §c§l" + mmss(left));
            out.add("§8➤ §7Мир: §f" + worldDisplay + "§7, X §b" + cx + " §7Y §b" + (cy + 1) + " §7Z §b" + cz
                    + " §8(§7ищи красный луч маяка§8)");
            out.add("§8➤ §7Открыто контейнеров: §f" + foundCount + "§7/§f" + containers.size());
        } else {
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime next = nextStart(now);
            if (next == null) {
                out.add("§7Время запуска не настроено.");
            } else {
                String day = next.toLocalDate().equals(now.toLocalDate()) ? "сегодня" : "завтра";
                long sec = Duration.between(now, next).getSeconds();
                out.add("§8➤ §7Следующий ивент: §b§l" + day + " в " + next.toLocalTime().toString() + " §7("
                        + zoneLabel + "), через §f" + human(sec));
            }
            out.add("§8➤ §7Расписание: " + scheduleText());
        }
        out.add("§8➤ §7Открывай сундуки и бочки в доме и забирай всё, что внутри.");
        out.add(footer());
        return out;
    }
}
