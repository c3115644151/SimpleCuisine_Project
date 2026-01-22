package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

public class CookingPotListener implements Listener {

    private final SimpleCuisine plugin;
    // Use plugin.getPotManager().getGuiTitle() instead of local constant to ensure sync
    // private final Component GUI_TITLE = Component.text("烹饪锅");
    
    // Slot Constants - Now using CookingPot public constants
    // private final int[] INPUT_SLOTS = {1, 2, 3, 10, 11, 12};
    // private final int SLOT_BUFFER = 7;
    // private final int SLOT_CONTAINER_INPUT = 23;
    // private final int SLOT_OUTPUT = 25;

    public CookingPotListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    // Cache to prevent double-trigger (Placement -> Immediate Interaction)
    private final java.util.Map<org.bukkit.Location, Long> justPlaced = new java.util.HashMap<>();

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        plugin.getPotManager().tryLoadPending(event.getWorld());
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        Block block = event.getBlock();
        
        // Only handle CraftEngine/SimpleCuisine standard blocks here.
        // ItemsAdder Furniture/Blocks are handled by ItemsAdderListener if IA is present.
        
        boolean isPot = false;

        // 1. Check Item (Standard SC/CE check)
        if (plugin.getItemManager().isCookingPot(item)) {
            // Check if it's NOT an ItemsAdder item (handled by IA events)
            boolean isIA = false;
            if (com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                if (com.example.simplecuisine.util.ItemsAdderHook.getCustomStackId(item) != null) {
                    isIA = true;
                }
            }
            
            if (!isIA) {
                isPot = true;
            }
        } 

        // Debug log
        if (isPot) {
            plugin.getLogger().info("BlockPlaceEvent (Standard): " + item.getType() + " IsPot: " + isPot);
            plugin.getPotManager().createPot(block.getLocation());
            justPlaced.put(block.getLocation(), System.currentTimeMillis());
            
            // Clean up cache after 1 second
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                justPlaced.remove(block.getLocation());
            }, 20L);

            if (plugin.isDebug()) {
                event.getPlayer().sendMessage(Component.text("§a[DEBUG] 成功放置厨锅！"));
            }
            plugin.getLogger().info("Cooking Pot placed at " + block.getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (plugin.getPotManager().isPot(block.getLocation())) {
            // Drop contents
            Inventory inv = plugin.getPotManager().getPotInventory(block.getLocation());
            if (inv != null) {
                // Drop inputs
                for (int slot : CookingPot.INPUT_SLOTS) dropItem(block, inv.getItem(slot));
                // Drop buffer (Disabled by user request)
                // dropItem(block, inv.getItem(CookingPot.SLOT_BUFFER));
                // Drop container input
                dropItem(block, inv.getItem(CookingPot.SLOT_CONTAINER_INPUT));
                // Drop output with XP check
                ItemStack outputItem = inv.getItem(CookingPot.SLOT_OUTPUT);
                if (outputItem != null && !outputItem.getType().isAir()) {
                    if (outputItem.hasItemMeta()) {
                        org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");
                        ItemMeta meta = outputItem.getItemMeta();
                        if (meta.getPersistentDataContainer().has(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                            int xp = meta.getPersistentDataContainer().get(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER);
                            if (xp > 0) {
                                block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), org.bukkit.entity.ExperienceOrb.class).setExperience(xp);
                            }
                        }
                    }
                    dropItem(block, outputItem);
                }
            }

            // Drop the pot item itself
            block.getWorld().dropItemNaturally(block.getLocation(), plugin.getItemManager().getCookingPot());
            
            // Remove from manager
            plugin.getPotManager().removePot(block.getLocation());
            
            // Prevent vanilla cauldron drop (since we dropped custom item)
            event.setDropItems(false);
        }
    }

    private void dropItem(Block block, ItemStack item) {
        if (item != null && !item.getType().isAir()) {
            block.getWorld().dropItemNaturally(block.getLocation(), item);
        }
    }

    @EventHandler
    public void onCauldronLevelChange(org.bukkit.event.block.CauldronLevelChangeEvent event) {
        if (plugin.getPotManager().isPot(event.getBlock().getLocation())) {
            // Prevent any level change (water/lava/snow filling or emptying)
            // except maybe when we want to empty it programmatically?
            // For now, strict protection to preserve the custom model.
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // Ignore off-hand to prevent double firing
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            // Shift-Click bypass (allow placing blocks against pot)
            if (event.getPlayer().isSneaking()) return;

            Block block = event.getClickedBlock();
            if (block != null) {
                // Prevent immediate interaction after placement (double-fire fix)
                if (justPlaced.containsKey(block.getLocation())) {
                    long placedTime = justPlaced.get(block.getLocation());
                    if (System.currentTimeMillis() - placedTime < 1000) { // Increased to 1s
                        return; 
                    }
                }

                // Check if it is a SC pot (Persistent Data)
                if (plugin.getPotManager().isPot(block.getLocation())) {
                    plugin.getPotManager().openGui(event.getPlayer(), block.getLocation());
                    event.setCancelled(true);
                    return;
                }

                // Lazy registration: If not known as a pot, check if it is an ItemsAdder pot
                if (plugin.getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
                    plugin.debug("Checking ItemsAdder for block at " + block.getLocation() + " Material: " + block.getType());
                     
                    // Check Block (Primary method for REAL_NOTE blocks)
                    String id = com.example.simplecuisine.util.ItemsAdderHook.getCustomBlockId(block);
                    plugin.debug("IA Block ID: " + id);
                    
                    if ("simplecuisine:cooking_pot".equals(id)) {
                         plugin.getPotManager().createPot(block.getLocation());
                         plugin.debug("Lazy registered pot at " + block.getLocation());
                         plugin.getPotManager().openGui(event.getPlayer(), block.getLocation()); // Open GUI immediately
                         event.setCancelled(true);
                    }
                }
            }
        }
    }

    // Prevent Note Block sound if it's our pot
    @EventHandler
    public void onNotePlay(org.bukkit.event.block.NotePlayEvent event) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
            if (com.example.simplecuisine.util.ItemsAdderHook.isCustomBlock(event.getBlock())) {
                 String id = com.example.simplecuisine.util.ItemsAdderHook.getCustomBlockId(event.getBlock());
                 if ("simplecuisine:cooking_pot".equals(id)) {
                     event.setCancelled(true);
                 }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(plugin.getPotManager().getGuiTitle())) {
            Inventory top = event.getView().getTopInventory();
            Inventory clicked = event.getClickedInventory();
            CookingPot pot = plugin.getPotManager().getPot(top);
            if (pot == null) return; // Should not happen

            // 1. Handle Shift-Click from Bottom Inventory (Player)
            if (event.isShiftClick() && clicked != null && clicked != top) {
                // Smart Shift-Click Logic: Only allow items into INPUT_SLOTS and SLOT_CONTAINER_INPUT
                event.setCancelled(true);
                
                ItemStack item = event.getCurrentItem();
                if (item == null || item.getType().isAir()) return;
                
                int leftoverAmount = item.getAmount();
                
                // A. Try Input Slots (1, 2, 3, 10, 11, 12)
                for (int slot : CookingPot.INPUT_SLOTS) {
                    if (leftoverAmount <= 0) break;
                    ItemStack target = top.getItem(slot);
                    
                    // Stack with existing
                    if (target != null && target.isSimilar(item)) {
                        int space = target.getMaxStackSize() - target.getAmount();
                        if (space > 0) {
                            int toAdd = Math.min(space, leftoverAmount);
                            target.setAmount(target.getAmount() + toAdd);
                            top.setItem(slot, target);
                            leftoverAmount -= toAdd;
                        }
                    }
                }
                
                // If still has items, fill empty input slots
                if (leftoverAmount > 0) {
                     for (int slot : CookingPot.INPUT_SLOTS) {
                        if (leftoverAmount <= 0) break;
                        ItemStack target = top.getItem(slot);
                        if (target == null || target.getType().isAir()) {
                            ItemStack newItem = item.clone();
                            newItem.setAmount(leftoverAmount);
                            top.setItem(slot, newItem);
                            leftoverAmount = 0;
                        }
                    }
                }

                // B. Try Container Input (Slot 23)
                if (leftoverAmount > 0) {
                    int slot = CookingPot.SLOT_CONTAINER_INPUT;
                    ItemStack target = top.getItem(slot);
                    
                    if (target != null && target.isSimilar(item)) {
                        int space = target.getMaxStackSize() - target.getAmount();
                        if (space > 0) {
                            int toAdd = Math.min(space, leftoverAmount);
                            target.setAmount(target.getAmount() + toAdd);
                            top.setItem(slot, target);
                            leftoverAmount -= toAdd;
                        }
                    } else if (target == null || target.getType().isAir()) {
                         ItemStack newItem = item.clone();
                         newItem.setAmount(leftoverAmount);
                         top.setItem(slot, newItem);
                         leftoverAmount = 0;
                    }
                }
                
                // C. Try Buffer Slot (Slot 41 -> No wait, Buffer is 31? No, 23 is Container. Buffer is 31? Let's check CookingPot.SLOT_BUFFER)
                // Actually, let's use CookingPot.SLOT_BUFFER directly.
                if (leftoverAmount > 0) {
                    int slot = CookingPot.SLOT_BUFFER;
                    ItemStack target = top.getItem(slot);
                    
                    if (target != null && pot.isSimilarIgnoringXP(target, item)) {
                        // Custom Stacking Logic
                        int space = target.getMaxStackSize() - target.getAmount();
                        if (space > 0) {
                            int toAdd = Math.min(space, leftoverAmount);
                            target.setAmount(target.getAmount() + toAdd);
                            top.setItem(slot, target);
                            leftoverAmount -= toAdd;
                        }
                    } else if (target == null || target.getType().isAir()) {
                         // Only allow if no recipe conflict? User said "if recipe mismatch, block".
                         // But if buffer is empty, we don't know if it mismatches yet.
                         // So allow if empty.
                         ItemStack newItem = item.clone();
                         newItem.setAmount(leftoverAmount);
                         top.setItem(slot, newItem);
                         leftoverAmount = 0;
                    }
                }
                
                // Update source item
                if (leftoverAmount != item.getAmount()) {
                    if (leftoverAmount <= 0) {
                        event.setCurrentItem(null);
                    } else {
                        item.setAmount(leftoverAmount);
                        event.setCurrentItem(item);
                    }
                }
                return;
            }

            // 2. Handle Clicks in Top Inventory (Pot)
            if (clicked == top) {
                int slot = event.getSlot();

                // Recipe Menu (Slot 9)
                if (slot == 9) {
                     event.setCancelled(true);
                     if (event.getWhoClicked() instanceof Player) {
                         plugin.getRecipeMenuManager().openPotRecipeMenu((Player) event.getWhoClicked(), 0);
                     }
                     return;
                }
                
                boolean isInput = false;
                for (int i : CookingPot.INPUT_SLOTS) if (i == slot) isInput = true;
                
                // Allow interaction ONLY in Input, Container Input, Output, and Buffer
                if (!isInput && slot != CookingPot.SLOT_CONTAINER_INPUT && slot != CookingPot.SLOT_OUTPUT && slot != CookingPot.SLOT_BUFFER) {
                    event.setCancelled(true);
                    return;
                }
                
                // Special Handling for Buffer Slot to allow Stacking of "Same Food" (ignoring XP/Lore)
                if (slot == CookingPot.SLOT_BUFFER) {
                    ItemStack cursor = event.getCursor();
                    ItemStack current = event.getCurrentItem();
                    
                    // Case: Placing item into buffer
                    if (cursor != null && !cursor.getType().isAir() && (event.getAction() == InventoryAction.PLACE_ALL || event.getAction() == InventoryAction.PLACE_ONE || event.getAction() == InventoryAction.PLACE_SOME)) {
                        if (current != null && !current.getType().isAir()) {
                            // Custom Stacking Check
                            if (pot.isSimilarIgnoringXP(current, cursor)) {
                                event.setCancelled(true); // Handle manually
                                
                                int space = current.getMaxStackSize() - current.getAmount();
                                if (space > 0) {
                                    int toAdd = Math.min(space, cursor.getAmount());
                                    if (event.getAction() == InventoryAction.PLACE_ONE) toAdd = Math.min(space, 1);
                                    
                                    current.setAmount(current.getAmount() + toAdd);
                                    cursor.setAmount(cursor.getAmount() - toAdd);
                                    
                                    top.setItem(slot, current);
                                    event.getView().setCursor(cursor.getAmount() > 0 ? cursor : null);
                                }
                                return;
                            } else {
                                // Mismatch -> Block
                                event.setCancelled(true);
                                return;
                            }
                        }
                    }
                }

                // Special handling for Output Slot
                if (slot == CookingPot.SLOT_OUTPUT) {
                    // Prevent placing items INTO output
                    switch (event.getAction()) {
                        case PLACE_ALL:
                        case PLACE_ONE:
                        case PLACE_SOME:
                        case SWAP_WITH_CURSOR:
                        case HOTBAR_SWAP:
                            event.setCancelled(true);
                            return;
                        default:
                            break;
                    }

                    // Handle Experience Claim (if taking item)
                    ItemStack item = event.getCurrentItem();
                    if (item != null && item.hasItemMeta()) {
                        org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");
                        ItemMeta meta = item.getItemMeta();
                        if (meta.getPersistentDataContainer().has(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
                            int xp = meta.getPersistentDataContainer().get(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER);
                            if (xp > 0) {
                                if (event.getWhoClicked() instanceof Player) {
                                    Player p = (Player) event.getWhoClicked();
                                    p.giveExp(xp);
                                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                                }
                                
                                // Remove XP tag so it's not claimed again
                                meta.getPersistentDataContainer().remove(xpKey);
                                item.setItemMeta(meta);
                                event.setCurrentItem(item); // Update the item in the slot before it's picked up
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().title().equals(plugin.getPotManager().getGuiTitle())) {
            for (int slot : event.getRawSlots()) {
                if (slot < event.getView().getTopInventory().getSize()) {
                     boolean isInput = false;
                    for (int i : CookingPot.INPUT_SLOTS) if (i == slot) isInput = true;
                    
                    if (!isInput && slot != CookingPot.SLOT_CONTAINER_INPUT && slot != CookingPot.SLOT_BUFFER) {
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // Just save. Items are already in the inventory object managed by CookingPotManager.
        // We can optionally trigger a save to disk here, but doing it onDisable is more efficient usually.
        // However, for crash safety, maybe save periodically?
        // For now, onDisable is fine as per standard practice, or we can add an autosave task.
    }
}
