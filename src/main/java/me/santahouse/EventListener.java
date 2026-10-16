package me.santahouse;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.InventoryHolder;

public class EventListener implements Listener {

    private final EventManager manager;

    public EventListener(EventManager manager) {
        this.manager = manager;
    }

    /** Дом и маяк сломать нельзя. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (manager.isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
            manager.deny(e.getPlayer(), "§b❄ §cДом Санты ломать нельзя");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(manager::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(manager::isProtectedBlock);
    }

    /** Мобы (эндермены, визер и т.д.) не могут ломать или забирать блоки дома. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) {
            return;
        }
        if (manager.isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (manager.isProtectedBlock(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Дом из ели горит: огонь, лава и молнии его не трогают. */
    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (manager.isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (manager.isNoBuildZone(e.getBlock(), Material.FIRE)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) {
        if (manager.isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (manager.isProtectedBlock(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** Маяк нельзя открыть и настроить эффект. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType() == Material.BEACON
                && manager.isProtectedBlock(b)) {
            e.setCancelled(true);
        }
    }

    /** На ивенте нельзя ставить блоки (и закрывать луч маяка). */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (manager.isNoBuildZone(e.getBlock(), e.getBlockPlaced().getType())) {
            e.setCancelled(true);
            manager.deny(e.getPlayer(), "§b❄ §cНа ивенте ставить блоки нельзя");
        } else if (manager.isBeamColumn(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    /** Нельзя лить воду и лаву в зоне ивента. */
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (manager.isNoBuildZone(e.getBlock(), Material.WATER)) {
            e.setCancelled(true);
            manager.deny(e.getPlayer(), "§b❄ §cНа ивенте жидкости лить нельзя");
        }
    }

    /** Открытие сундука или бочки: первый открывший получает очки. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) {
            return;
        }
        InventoryHolder h = e.getInventory().getHolder();
        List<Block> blocks = new ArrayList<>();
        if (h instanceof DoubleChest) {
            DoubleChest dc = (DoubleChest) h;
            addBlock(blocks, dc.getLeftSide());
            addBlock(blocks, dc.getRightSide());
        } else {
            addBlock(blocks, h);
        }
        if (!blocks.isEmpty()) {
            manager.onContainerOpen((Player) e.getPlayer(), blocks);
        }
    }

    private static void addBlock(List<Block> list, InventoryHolder h) {
        if (h instanceof BlockState) {
            list.add(((BlockState) h).getBlock());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        manager.showBar(e.getPlayer());
    }
}
