package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import org.bukkit.event.world.WorldLoadEvent;

public class CuttingBoardListener implements Listener {
    private final SimpleCuisine plugin;
    
    public CuttingBoardListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getCuttingBoardManager().tryLoadPending(event.getWorld());
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getChunk().isLoaded()) {
                plugin.getCuttingBoardManager().restoreVisualsInChunk(event.getChunk());
            }
        }, 1L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Shift-Click bypass
        if (event.getPlayer().isSneaking()) return;
        
        if (event.getClickedBlock() == null) return;

        // Lazy Registration (Self-Healing)
        if (!plugin.getCuttingBoardManager().isCuttingBoard(event.getClickedBlock().getLocation())) {
             String id = null;
             if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                 id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(event.getClickedBlock());
             }
             if (id == null && com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                 id = com.example.simplecuisine.util.ItemsAdderHook.getCustomId(event.getClickedBlock());
             }
             
             if (id != null && id.contains("cutting_board")) {
                 plugin.getCuttingBoardManager().addBoard(event.getClickedBlock().getLocation());
                 plugin.debug("Lazy registered cutting board at " + event.getClickedBlock().getLocation());
             }
        }

        // Check if interacting with an existing cutting board
        if (plugin.getCuttingBoardManager().isCuttingBoard(event.getClickedBlock().getLocation())) {
            
            // Debounce check
            if (!com.example.simplecuisine.util.InteractionDebouncer.canInteract(event.getPlayer().getUniqueId())) {
                event.setCancelled(true); // Prevent vanilla double-action
                return;
            }

            boolean handled = plugin.getCuttingBoardManager().handleInteract(event.getPlayer(), event.getClickedBlock(), event.getItem(), event.getHand());
            
            if (handled) {
                event.setCancelled(true);
                event.getPlayer().updateInventory();
            } else {
                // If not handled (yielded), reset debouncer to allow offhand or subsequent events
                com.example.simplecuisine.util.InteractionDebouncer.reset(event.getPlayer().getUniqueId());
            }
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getCuttingBoardManager().isCuttingBoard(event.getBlock().getLocation())) {
            org.bukkit.inventory.ItemStack item = plugin.getCuttingBoardManager().removeBoard(event.getBlock().getLocation());
            if (item != null && !item.getType().isAir()) {
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), item);
            }
        }
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        
        // Prevent placement if interacting with existing board (Safety check, though onInteract should handle it)
        // Note: BlockPlaceEvent fires AFTER Interact. If Interact wasn't cancelled, Place fires.
        
        // Check CraftEngine
        if (CraftEngineHook.isEnabled()) {
            String id = CraftEngineHook.getCustomBlockId(event.getBlockPlaced());
            if ("farmersdelight:cutting_board".equals(id)) {
                 plugin.getCuttingBoardManager().addBoard(event.getBlockPlaced().getLocation());
                 com.example.simplecuisine.util.InteractionDebouncer.blockInteraction(event.getPlayer().getUniqueId(), 200);
            }
        }
    }
}
