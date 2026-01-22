package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import org.bukkit.event.world.WorldLoadEvent;

public class StoveListener implements Listener {
    private final SimpleCuisine plugin;
    
    public StoveListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getStoveManager().tryLoadPending(event.getWorld());
    }
    
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // When a chunk loads, check if we have any stoves there and restore their visuals
        org.bukkit.Chunk chunk = event.getChunk();
        // Since we don't have a chunk->stove map, we iterate (inefficient for many stoves, but okay for now)
        // Optimization: StoveManager could keep a map of ChunkKey -> List<Location>
        
        // Actually, iterating all stoves is fine if count is low (<1000). 
        // If high, we need spatial index. Let's assume low for now.
        for (org.bukkit.Location loc : plugin.getStoveManager().getAllStoveLocations()) {
             if (loc.getWorld().equals(chunk.getWorld()) && 
                 loc.getBlockX() >> 4 == chunk.getX() && 
                 loc.getBlockZ() >> 4 == chunk.getZ()) {
                 
                 plugin.getStoveManager().restoreVisuals(loc);
             }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        
        // Allow usage of Flint & Steel, Bucket, Water Bucket, Shovel (State changing items)
        // Do this BEFORE checking for stove to allow interaction to pass through to IA/Vanilla
        if (event.getItem() != null) {
            org.bukkit.Material type = event.getItem().getType();
            if (type == org.bukkit.Material.FLINT_AND_STEEL || 
                type == org.bukkit.Material.WATER_BUCKET || 
                type == org.bukkit.Material.BUCKET ||
                type == org.bukkit.Material.POTION ||
                type.name().endsWith("_SHOVEL")) {
                // Return immediately to let CraftEngine/Vanilla handle the state change.
                // Do NOT cancel the event.
                return;
            }
        }

        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        
        Block block = event.getClickedBlock();
        if (block == null) return;
        
        // Lazy Registration (Self-Healing)
        if (!plugin.getStoveManager().isStove(block.getLocation())) {
             String id = null;
             if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                 id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(block);
             }
             
             boolean validStove = (id != null && id.contains("stove"));
             
             if (!validStove && com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                 String iaId = com.example.simplecuisine.util.ItemsAdderHook.getCustomId(block);
                 if (iaId != null) {
                     id = iaId;
                 }
             }
             
             if (id != null && id.contains("stove")) {
                 plugin.getStoveManager().addStove(block.getLocation());
                 plugin.debug("Lazy registered stove at " + block.getLocation());
             }
        }
        
        String id = null;

        // 1. Try CraftEngine
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(block);
        }
        
        // 2. Try ItemsAdder if ID is still null OR if CraftEngine returned an ID that isn't a stove
        // This fixes the issue where CraftEngine might return a non-null ID (or "minecraft:air" etc)
        // preventing ItemsAdder from being checked.
        boolean validStove = (id != null && id.contains("stove"));
        
        if (!validStove && com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
            String iaId = com.example.simplecuisine.util.ItemsAdderHook.getCustomId(block);
            if (iaId != null) {
                id = iaId;
            }
        }

        if (id != null && id.contains("stove")) { // Relaxed check for stove/stove_on/stove_active
            
            // Manual State Switching Logic
            if (event.getItem() != null) {
                org.bukkit.Material type = event.getItem().getType();
                boolean isLit = id.contains("stove_on") || id.contains("active") || id.contains("lit");
                
                if (plugin.getConfig().getBoolean("debug")) {
                    plugin.getLogger().info("[Debug] Interact with stove. ID=" + id + ", Lit=" + isLit + ", Item=" + type);
                }
                
                // Ignite
                if (type == org.bukkit.Material.FLINT_AND_STEEL && !isLit) {
                    plugin.getLogger().info("[Debug] Igniting stove at " + block.getLocation());
                    plugin.getStoveManager().setSwitchingState(block.getLocation(), true);
                    boolean success = com.example.simplecuisine.util.ItemsAdderHook.placeCustomBlock("farmersdelight:stove_on", block.getLocation());
                    if (!success) {
                        String cmd = "iazip:place farmersdelight:stove_on " + block.getX() + " " + block.getY() + " " + block.getZ() + " " + block.getWorld().getName();
                        plugin.getLogger().info("Fallback placing stove_on via command: " + cmd);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                    plugin.getStoveManager().setSwitchingState(block.getLocation(), false);
                    block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.ITEM_FLINTANDSTEEL_USE, 1.0f, 1.0f);
                    return; // Done
                }
                
                // Extinguish
                if ((type == org.bukkit.Material.WATER_BUCKET || type == org.bukkit.Material.BUCKET || type.name().endsWith("_SHOVEL")) && isLit) {
                    plugin.getLogger().info("[Debug] Extinguishing stove at " + block.getLocation());
                    // Switch to stove
                    plugin.getStoveManager().setSwitchingState(block.getLocation(), true);
                    boolean success = com.example.simplecuisine.util.ItemsAdderHook.placeCustomBlock("farmersdelight:stove", block.getLocation());
                    if (!success) {
                        // Fallback
                        String cmd = "iazip:place farmersdelight:stove " + block.getX() + " " + block.getY() + " " + block.getZ() + " " + block.getWorld().getName();
                        plugin.getLogger().info("Fallback placing stove via command: " + cmd);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                    plugin.getStoveManager().setSwitchingState(block.getLocation(), false);
                    block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 1.0f);
                    return; // Done
                }
            }

            // 3. Interaction Logic
            // Only handle interaction if it's a valid cooking operation (Place Ingredient or Retrieve)
            // Otherwise, let the event pass (e.g. for placing a Cooking Pot on top)
            
            boolean isIngredient = false;
            if (event.getItem() != null && !event.getItem().getType().isAir()) {
                if (plugin.getStoveManager().getRecipe(event.getItem()) != null) {
                    isIngredient = true;
                }
            }
            
            boolean isHandEmpty = (event.getItem() == null || event.getItem().getType().isAir());
            
            if (isIngredient || isHandEmpty) {
                event.setCancelled(true); // Prevent default interaction (e.g. NoteBlock tuning)
                plugin.getStoveManager().handleInteract(event.getPlayer(), block, event.getItem());
            } else {
                // Not an ingredient, and hand not empty.
                // Allow interaction to pass through.
                // This enables placing blocks (like Cooking Pot) on the stove.
                if (plugin.getConfig().getBoolean("debug")) {
                     plugin.getLogger().info("[Debug] Passing interaction for non-ingredient item: " + event.getItem().getType());
                }
            }
        }
    }
    
    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (plugin.getStoveManager().isStove(event.getBlock().getLocation())) {
            // Check if we are just switching state
            if (plugin.getStoveManager().isSwitchingState(event.getBlock().getLocation())) {
                // Do NOT remove stove data
                return;
            }
            plugin.getStoveManager().removeStove(event.getBlock().getLocation());
        }
    }
}
