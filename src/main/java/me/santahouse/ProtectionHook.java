package me.santahouse;

import java.lang.reflect.Method;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Проверка чужих приватов через рефлексию (WorldGuard и GriefPrevention),
 * чтобы плагин собирался и работал без этих плагинов.
 */
public final class ProtectionHook {

    private final Logger log;
    private boolean wg;
    private boolean gp;

    private ProtectionHook(Logger log, boolean wg, boolean gp) {
        this.log = log;
        this.wg = wg;
        this.gp = gp;
    }

    public static ProtectionHook create(Logger log) {
        return new ProtectionHook(log, isOn("WorldGuard"), isOn("GriefPrevention"));
    }

    private static boolean isOn(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    public boolean any() {
        return wg || gp;
    }

    public String names() {
        return (wg ? "WorldGuard " : "") + (gp ? "GriefPrevention" : "");
    }

    /** true, если область пересекается с чьим-то приватом. */
    public boolean isClaimed(World w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (wg) {
            try {
                if (wgClaimed(w, minX, minY, minZ, maxX, maxY, maxZ)) {
                    return true;
                }
            } catch (Throwable t) {
                wg = false;
                log.warning("Не удалось проверить приваты WorldGuard (" + t + "). Проверка отключена.");
            }
        }
        if (gp) {
            try {
                if (gpClaimed(w, minX, minZ, maxX, maxZ)) {
                    return true;
                }
            } catch (Throwable t) {
                gp = false;
                log.warning("Не удалось проверить приваты GriefPrevention (" + t + "). Проверка отключена.");
            }
        }
        return false;
    }

    private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private boolean wgClaimed(World w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
            throws Exception {
        ClassLoader cl = Bukkit.getPluginManager().getPlugin("WorldGuard").getClass().getClassLoader();
        Class<?> wgC = cls(cl, "com.sk89q.worldguard.WorldGuard");
        Object wgi = wgC.getMethod("getInstance").invoke(null);
        Object platform = wgC.getMethod("getPlatform").invoke(wgi);
        Class<?> platC = cls(cl, "com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
        Object container = platC.getMethod("getRegionContainer").invoke(platform);
        Class<?> contC = cls(cl, "com.sk89q.worldguard.protection.regions.RegionContainer");
        Class<?> adapterC = cls(cl, "com.sk89q.worldedit.bukkit.BukkitAdapter");
        Object weWorld = adapterC.getMethod("adapt", World.class).invoke(null, w);
        Class<?> weWorldC = cls(cl, "com.sk89q.worldedit.world.World");
        Object manager = contC.getMethod("get", weWorldC).invoke(container, weWorld);
        if (manager == null) {
            return false;
        }
        Class<?> bv3 = cls(cl, "com.sk89q.worldedit.math.BlockVector3");
        Method at = bv3.getMethod("at", int.class, int.class, int.class);
        Object min = at.invoke(null, minX, minY, minZ);
        Object max = at.invoke(null, maxX, maxY, maxZ);
        Class<?> pcr = cls(cl, "com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion");
        Class<?> prC = cls(cl, "com.sk89q.worldguard.protection.regions.ProtectedRegion");
        Object region = pcr.getConstructor(String.class, bv3, bv3).newInstance("darkcarnival_check", min, max);
        Class<?> rmC = cls(cl, "com.sk89q.worldguard.protection.managers.RegionManager");
        Object set = rmC.getMethod("getApplicableRegions", prC).invoke(manager, region);
        Class<?> setC = cls(cl, "com.sk89q.worldguard.protection.ApplicableRegionSet");
        Object size = setC.getMethod("size").invoke(set);
        return size instanceof Number && ((Number) size).intValue() > 0;
    }

    private boolean gpClaimed(World w, int minX, int minZ, int maxX, int maxZ) throws Exception {
        ClassLoader cl = Bukkit.getPluginManager().getPlugin("GriefPrevention").getClass().getClassLoader();
        Class<?> gpC = cls(cl, "me.ryanhamshire.griefprevention.GriefPrevention");
        Object inst = gpC.getField("instance").get(null);
        Object ds = gpC.getField("dataStore").get(inst);
        Class<?> claimC = cls(cl, "me.ryanhamshire.griefprevention.Claim");
        Method m = null;
        for (Method mm : ds.getClass().getMethods()) {
            Class<?>[] pt = mm.getParameterTypes();
            if (mm.getName().equals("getClaimAt") && pt.length == 3 && pt[0] == Location.class
                    && pt[1] == boolean.class && pt[2] == claimC) {
                m = mm;
                break;
            }
        }
        if (m == null) {
            throw new NoSuchMethodException("getClaimAt");
        }
        for (int x = minX; x <= maxX + 3; x += 4) {
            for (int z = minZ; z <= maxZ + 3; z += 4) {
                int px = Math.min(x, maxX);
                int pz = Math.min(z, maxZ);
                if (m.invoke(ds, new Location(w, px, 64, pz), true, null) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
