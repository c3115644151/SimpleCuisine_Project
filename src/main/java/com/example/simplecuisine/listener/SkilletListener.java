package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class SkilletListener implements Listener {
    private final SimpleCuisine plugin;
    
    public SkilletListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        plugin.getSkilletManager().tryLoadPending(event.getWorld());
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // Delay 1 tick to ensure entities are loaded for cleanup
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getChunk().isLoaded()) {
                plugin.getSkilletManager().restoreVisualsInChunk(event.getChunk());
            }
        }, 1L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 1. Block Interactions (require block)
        if (event.getClickedBlock() != null) {
            // Lazy Registration (Self-Healing)
            // If the block is a skillet but not in memory (e.g. placed while plugin unloaded, or event missed), register it now.
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && 
                !plugin.getSkilletManager().isSkillet(event.getClickedBlock().getLocation())) {
                
                String id = null;
                // 1. Try CraftEngine
                if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                    id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(event.getClickedBlock());
                }
                
                boolean validSkillet = (id != null && id.contains("skillet"));
                
                // 2. Try ItemsAdder
                if (!validSkillet && com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                    String iaId = com.example.simplecuisine.util.ItemsAdderHook.getCustomId(event.getClickedBlock());
                    if (iaId != null) {
                        id = iaId;
                    }
                }
                
                if (id != null && id.contains("skillet")) {
                    plugin.getSkilletManager().addSkillet(event.getClickedBlock().getLocation());
                    plugin.debug("Lazy registered skillet at " + event.getClickedBlock().getLocation());
                }
            }

            // Check placed skillet interaction first
            // If clicking a block, and that block is a skillet
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                if (plugin.getSkilletManager().isSkillet(event.getClickedBlock().getLocation())) {
                    
                    // Debounce check
                    if (!com.example.simplecuisine.util.InteractionDebouncer.canInteract(event.getPlayer().getUniqueId())) {
                        event.setCancelled(true);
                        return;
                    }

                    boolean handled = plugin.getSkilletManager().handleInteract(event.getPlayer(), event.getClickedBlock(), event.getItem(), event.getHand());
                    if (handled) {
                        event.setCancelled(true); // Stop vanilla interaction
                        event.getPlayer().updateInventory();
                    } else {
                        // If not handled (yielded), reset debouncer to allow offhand
                        com.example.simplecuisine.util.InteractionDebouncer.reset(event.getPlayer().getUniqueId());
                    }
                    // If not handled, allow vanilla/offhand
                    return; // Important: Don't process handheld logic if we interacted with a placed skillet
                }
            }
        }
        
        // 2. Handheld Skillet Logic
        // Check if Main Hand is Skillet (regardless of which hand triggered the event)
        // This ensures we catch OffHand events (eating) when MainHand is a skillet
        ItemStack mainHand = event.getPlayer().getInventory().getItemInMainHand();
        if (mainHand != null && plugin.getSkilletManager().isSkilletItem(mainHand)) {
            // Check if we are interacting with a block that IS NOT a skillet (already handled above)
            // If we are looking at a block, we need to be careful not to trigger if we just placed it or if it's a container?
            // But here we are focusing on "Cooking in Hand".
            
            // Right Click (Air or Block)
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || 
                event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                
                ItemStack offHand = event.getPlayer().getInventory().getItemInOffHand();
                boolean handled = false;
                
                // 1. Try Retrieve (ALWAYS try retrieval first if skillet has content)
                if (plugin.getSkilletManager().tryRetrieveHandheldCooking(event.getPlayer(), mainHand)) {
                    handled = true;
                }
                
                // 2. Try Start Cooking (if not retrieved/handled and offhand has food)
                if (!handled && offHand != null && !offHand.getType().isAir()) {
                    if (plugin.getSkilletManager().tryStartHandheldCooking(event.getPlayer(), mainHand, offHand)) {
                        handled = true;
                    }
                }
                
                // If handled, cancel event and DENY usage to prevent eating
                if (handled) {
                    event.setCancelled(true);
                    event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                    event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                    event.getPlayer().updateInventory(); // Prevent ghost items
                    return;
                }
            }
            
            // 3. Prevent Stripping Logs (Axe-like behavior restriction)
            if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
                if (org.bukkit.Tag.LOGS.isTagged(event.getClickedBlock().getType())) {
                    // Prevent stripping but allow placement (if handled by other plugins)
                    event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                }
            }
        }
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getSkilletManager().isSkillet(event.getBlock().getLocation())) {
            event.setDropItems(false); // Prevent native block drops (double drop fix)
            plugin.getSkilletManager().removeSkillet(event.getBlock().getLocation());
        }
    }
    
    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Player) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) event.getDamager();
            ItemStack item = player.getInventory().getItemInMainHand();
            if (plugin.getSkilletManager().isSkilletItem(item)) {
                // Play a metallic bonk sound
                player.getWorld().playSound(event.getEntity().getLocation(), org.bukkit.Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.5f);
            }
        }
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;
        // Check CraftEngine
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(event.getBlockPlaced());
            if ("farmersdelight:skillet".equals(id)) {
                 // Save the item state (durability)
                 plugin.getSkilletManager().addSkillet(event.getBlockPlaced().getLocation(), event.getItemInHand());
                 com.example.simplecuisine.util.InteractionDebouncer.blockInteraction(event.getPlayer().getUniqueId(), 200);
            }
        }
    }
}
