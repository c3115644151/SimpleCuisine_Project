package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CookingPot {
    private final SimpleCuisine plugin;
    private final Location location;
    private Inventory inventory;
    
    private Recipe currentRecipe;
    private int progress;
    private TextDisplay progressDisplay;
    private final Set<UUID> visibleToPlayers = new HashSet<>();
    
    // Slot Constants
    public static final int[] INPUT_SLOTS = {1, 2, 3, 10, 11, 12};
    public static final int SLOT_BUFFER = 7;
    public static final int SLOT_HEAT_INDICATOR = 20;
    public static final int SLOT_CONTAINER_INPUT = 23;
    public static final int SLOT_OUTPUT = 25;

    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    private boolean cachedHeated = false;
    private boolean checkHeatNextTick = true;

    public CookingPot(SimpleCuisine plugin, Location location, Inventory inventory) {
        this.plugin = plugin;
        this.location = location;
        this.inventory = inventory;
        this.progress = 0;
        this.checkHeatNextTick = true; // Initial check
        
        // Clean up any lingering displays at this location from previous sessions
        removeProgressDisplay();
    }

    public boolean isHeated() {
        return cachedHeated;
    }
    
    public void forceHeatCheck() {
        this.checkHeatNextTick = true;
    }

    public void tick() {
        // [Performance Fix] Heat check only on critical moments or assume persistence
        // For now, we still check heat but could optimize to check less frequently.
        // User requested: "check when entering GUI" -> implying lazy check.
        // But for active cooking, we need to know if it stopped.
        // Let's implement a "Lazy Heat" check: Check every 5 seconds (100 ticks) OR if interaction happened.
        // But to follow user instruction strictly: "Heat detection... triggered when player interacts".
        // This implies if no one touches it, we assume state persists.
        // However, fire going out should stop cooking eventually?
        // User said: "totally doesn't need active polling".
        // So we will TRUST the cached state until interaction updates it.
        // Wait, we need an initial check.
        // Let's just use the cached `isHeated` state which we will update via `updateHeatState()`.
        
        // boolean isHeated = isHeated(); // REMOVED POLLING
        // We rely on `cachedHeated` which is updated by `updateHeatState()` called on interaction/start.
        
        // Actually, we need to implement `cachedHeated`.
        // For now, let's just NOT call isHeated() here.
        // But we need a way to start cooking.
        // Logic:
        // 1. tick() checks `cachedHeated`.
        // 2. `cachedHeated` is updated when:
        //    a. GUI Opened (updateHeatState)
        //    b. Recipe matched (to verify start conditions)
        
        if (checkHeatNextTick) {
            cachedHeated = hasHeatSource();
            checkHeatNextTick = false;
        }

        updateHeatIndicator(cachedHeated);
        updateGuiElements();

        // [Performance Fix] Update display visibility only if necessary
        // updateDisplayVisibility(); // Moved inside logic flow to avoid constant checking
        
        // 1. Handle Buffer (Slot 7) -> Output (Slot 25) logic
        ItemStack bufferItem = inventory.getItem(SLOT_BUFFER);
        if (bufferItem != null && !bufferItem.getType().isAir()) {
            tryMoveBufferToOutput(bufferItem);
        }

        // 2. If Buffer is empty and no heat, go idle
        if (!cachedHeated) {
            if (currentRecipe != null || progress > 0) {
                currentRecipe = null;
                progress = 0;
                removeProgressDisplay();
            }
            return; // Idle
        }
        
        // Update visibility only if active/heated
        updateDisplayVisibility();

        List<ItemStack> inputs = new ArrayList<>();
        for (int slot : INPUT_SLOTS) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                inputs.add(item);
            }
        }

        // Debug logging for inputs if something is present but not cooking
        if (!inputs.isEmpty() && currentRecipe == null) {
            // Only log occasionally to avoid spam, or log only when players are viewing?
            // For now, let's just rely on the fact that if inputs are present, we try to match.
        }

        Recipe match = plugin.getCookingManager().matchRecipe(inputs);
        
        // Enforce Grid Structure (1 item per slot limit logic for detection)
        if (match != null && !checkGridStructure(match, inputs)) {
            match = null;
        }

        // Fix: Check if output can be merged into buffer before starting cooking
        if (match != null) {
            ItemStack expectedResult = match.createResult();
            if (!canMergeIntoBuffer(expectedResult)) {
                match = null;
            }
        }
        
        if (match != null) {
            if (currentRecipe == null || !currentRecipe.getId().equals(match.getId())) {
                currentRecipe = match;
                progress = 0;
            }

            progress += 2; // Incremented by 2 because this method runs every 2 ticks

            // updateViewersActionBar(); // Replaced by TextDisplay
            updateProgressDisplay();

            if (progress >= currentRecipe.getTime()) {
                ItemStack result = match.createResult();
                int totalXp = (int) match.getExperience();
                
                // Add XP to result PDC
                if (totalXp > 0) {
                    ItemMeta meta = result.getItemMeta();
                    if (meta != null) {
                        org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");
                        meta.getPersistentDataContainer().set(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, totalXp);
                        result.setItemMeta(meta);
                    }
                }

                boolean needsContainer = (match.getContainers() != null && !match.getContainers().isEmpty());
                boolean handled = false;

                // 1. Attempt Direct Output (if container satisfied or not needed)
                if (needsContainer) {
                    ItemStack containerInput = inventory.getItem(SLOT_CONTAINER_INPUT);
                    if (containerInput != null && match.isContainer(containerInput)) {
                         int maxWeCanMove = containerInput.getAmount();
                         int fitting = getFittingAmount(result);
                         int actualMove = Math.min(result.getAmount(), Math.min(maxWeCanMove, fitting));
                         
                         if (actualMove >= result.getAmount()) {
                             addToOutput(result); 
                             containerInput.setAmount(containerInput.getAmount() - actualMove);
                             inventory.setItem(SLOT_CONTAINER_INPUT, containerInput);
                             handled = true;
                         }
                    }
                } else {
                    int fitting = getFittingAmount(result);
                    if (fitting >= result.getAmount()) {
                         addToOutput(result);
                         handled = true;
                    }
                }

                // 2. Fallback to Buffer
                if (!handled) {
                     // Add lore if needs container
                    if (needsContainer) {
                        ItemMeta meta = result.getItemMeta();
                        List<Component> lore = meta.lore();
                        if (lore == null) lore = new ArrayList<>();
                        
                        Ingredient firstContainer = match.getContainers().get(0);
                        Component containerName;
                        if (firstContainer.getCraftEngineId() != null) {
                            ItemStack item = com.example.simplecuisine.util.CraftEngineHook.getItem(firstContainer.getCraftEngineId());
                            if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                                containerName = item.getItemMeta().displayName();
                            } else {
                                containerName = Component.text(firstContainer.getCraftEngineId());
                            }
                        } else {
                            containerName = Component.translatable(firstContainer.getMaterial().translationKey());
                        }
                        
                        lore.add(ConfigManager.getGuiText("cooking.container_needed").append(containerName));
                        if (match.getContainers().size() > 1) {
                             lore.add(ConfigManager.getGuiText("cooking.container_hint"));
                        }
                        
                        meta.lore(lore);
                        result.setItemMeta(meta);
                    }
                    
                    ItemStack buffer = inventory.getItem(SLOT_BUFFER);
                    if (buffer == null || buffer.getType().isAir()) {
                        inventory.setItem(SLOT_BUFFER, result);
                        handled = true;
                    } else {
                        // Try to stack in buffer
                        if (isSimilarIgnoringXP(buffer, result)) { 
                            // Check max stack
                            int max = buffer.getMaxStackSize();
                            if (buffer.getAmount() + result.getAmount() <= max) {
                                // Stack them
                                buffer.setAmount(buffer.getAmount() + result.getAmount());
                                // Merge XP
                                if (totalXp > 0) {
                                     ItemMeta bMeta = buffer.getItemMeta();
                                     org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");
                                     int currentXp = bMeta.getPersistentDataContainer().getOrDefault(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, 0);
                                     bMeta.getPersistentDataContainer().set(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, currentXp + totalXp);
                                     buffer.setItemMeta(bMeta);
                                }
                                inventory.setItem(SLOT_BUFFER, buffer);
                                handled = true;
                            }
                        }
                    }
                }

                if (handled) {
                    consumeIngredients(match);
                    playSound(needsContainer ? org.bukkit.Sound.BLOCK_BREWING_STAND_BREW : org.bukkit.Sound.BLOCK_FIRE_EXTINGUISH);
                    progress = 0;
                    removeProgressDisplay();
                } else {
                    // Blocked
                    updateProgressDisplay();
                }
            }
        } else {
            currentRecipe = null;
            progress = 0;
            removeProgressDisplay();
        }
    }

    private void updateDisplayVisibility() {
        if (progressDisplay == null || !progressDisplay.isValid()) return;

        // [Performance Fix] Optimize using Vector Dot Product instead of RayTrace
        // RayTrace is expensive (world collision). Dot Product is cheap (math).
        // Only checking players within 10 blocks.
        
        Collection<org.bukkit.entity.Entity> nearby = location.getWorld().getNearbyEntities(location, 10, 10, 10);
        Set<UUID> nearbyUUIDs = new HashSet<>();
        
        Vector3f potPos = location.toVector().toVector3f();

        for (org.bukkit.entity.Entity e : nearby) {
            if (e instanceof Player) {
                Player p = (Player) e;
                nearbyUUIDs.add(p.getUniqueId());
                
                // Calculate direction from player eye to pot
                Location eyeLoc = p.getEyeLocation();
                Vector3f eyePos = eyeLoc.toVector().toVector3f();
                Vector3f toPot = new Vector3f(potPos).sub(eyePos).normalize();
                Vector3f lookDir = eyeLoc.getDirection().toVector3f();
                
                // Dot product: 1.0 = looking directly at it. 
                // Threshold 0.9 roughly corresponds to being within ~25 degrees cone.
                float dot = lookDir.dot(toPot);
                boolean isLooking = dot > 0.95; // Narrow cone for "looking at"

                if (isLooking) {
                    if (!visibleToPlayers.contains(p.getUniqueId())) {
                        p.showEntity(plugin, progressDisplay);
                        visibleToPlayers.add(p.getUniqueId());
                    }
                } else {
                    if (visibleToPlayers.contains(p.getUniqueId())) {
                        p.hideEntity(plugin, progressDisplay);
                        visibleToPlayers.remove(p.getUniqueId());
                    }
                }
            }
        }
        
        // Cleanup tracking for players who left range
        visibleToPlayers.removeIf(uuid -> !nearbyUUIDs.contains(uuid));
    }

    private void updateProgressDisplay() {
        if (currentRecipe == null) return;
        
        if (progressDisplay == null || !progressDisplay.isValid()) {
            spawnProgressDisplay();
        }
        
        if (progressDisplay != null) {
            int percentage = Math.min(100, (int) ((double) progress / currentRecipe.getTime() * 100));
            String recipeName = MiniMessage.miniMessage().serialize(currentRecipe.getDisplayName());
            Component text = ConfigManager.getGuiText("cooking.progress_display", 
                "<recipe>", recipeName, 
                "<percent>", String.valueOf(percentage));
            progressDisplay.text(text);
        }
    }

    private void spawnProgressDisplay() {
        // Look for existing display first to avoid duplicates
        Collection<org.bukkit.entity.Entity> nearby = location.getWorld().getNearbyEntities(location.clone().add(0.5, 1.5, 0.5), 0.5, 0.5, 0.5);
        for (org.bukkit.entity.Entity e : nearby) {
            if (e instanceof TextDisplay && e.getScoreboardTags().contains("sc_cooking_progress")) {
                progressDisplay = (TextDisplay) e;
                // Ensure correct visibility setting for existing entities
                progressDisplay.setVisibleByDefault(false);
                return;
            }
        }

        progressDisplay = location.getWorld().spawn(location.clone().add(0.5, 1.2, 0.5), TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.CENTER);
            entity.addScoreboardTag("sc_cooking_progress");
            entity.setVisibleByDefault(false); // Hide by default, show only when looking
            entity.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(0.5f, 0.5f, 0.5f), // Scale down a bit
                new AxisAngle4f(0, 0, 0, 1)
            ));
            entity.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0)); // Transparent
            entity.setShadowed(true);
        });
    }

    private void removeProgressDisplay() {
        if (progressDisplay != null) {
            progressDisplay.remove();
            progressDisplay = null;
        } else {
            // Double check world for orphans
            if (location.getWorld() != null) {
                Collection<org.bukkit.entity.Entity> nearby = location.getWorld().getNearbyEntities(location.clone().add(0.5, 1.5, 0.5), 0.5, 0.5, 0.5);
                for (org.bukkit.entity.Entity e : nearby) {
                    if (e instanceof TextDisplay && e.getScoreboardTags().contains("sc_cooking_progress")) {
                        e.remove();
                    }
                }
            }
        }
        visibleToPlayers.clear(); // Reset visibility cache so new display is shown to players
    }



    private void playSound(org.bukkit.Sound sound) {
        location.getWorld().playSound(location, sound, 1f, 1f);
    }

    private boolean hasHeatSource() {
        Block down = location.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        return plugin.getCookingManager().isHeatSource(down);
    }

    private void updateHeatIndicator(boolean hasHeat) {
        CookingManager.GuiConfig config = plugin.getCookingManager().getGuiConfig();
        
        if (config.heatIndicatorEnabled) {
            ItemStack icon = null;

            // Use getItemWithFallback to handle cross-plugin items and avoid warnings
            if (hasHeat && config.heatIndicatorItemOn != null) {
                icon = plugin.getCookingManager().getItemWithFallback(config.heatIndicatorItemOn);
            } else if (!hasHeat && config.heatIndicatorItemOff != null) {
                icon = plugin.getCookingManager().getItemWithFallback(config.heatIndicatorItemOff);
            }

            // Fallback to manual config
            if (icon == null) {
                icon = new ItemStack(config.heatIndicatorMaterial);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    if (hasHeat) {
                        meta.displayName(ConfigManager.getGuiText("pot.heat_on"));
                        if (config.heatIndicatorModelOn != 0) meta.setCustomModelData(config.heatIndicatorModelOn);
                    } else {
                        meta.displayName(ConfigManager.getGuiText("pot.heat_off"));
                        if (config.heatIndicatorModelOff != 0) meta.setCustomModelData(config.heatIndicatorModelOff);
                    }
                    icon.setItemMeta(meta);
                }
            } else {
                // Ensure display name is set if using IA/CE item (optional, maybe item already has name)
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.displayName(hasHeat ? ConfigManager.getGuiText("pot.heat_on") : ConfigManager.getGuiText("pot.heat_off"));
                    icon.setItemMeta(meta);
                }
            }
            
            inventory.setItem(config.heatIndicatorSlot, icon);
        } else {
            // Fallback default behavior
            ItemStack icon;
            if (hasHeat) {
                icon = new ItemStack(Material.FIRE_CHARGE);
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(ConfigManager.getGuiText("pot.heat_on"));
                icon.setItemMeta(meta);
            } else {
                icon = new ItemStack(Material.GUNPOWDER);
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(ConfigManager.getGuiText("pot.heat_off"));
                icon.setItemMeta(meta);
            }
            inventory.setItem(SLOT_HEAT_INDICATOR, icon);
        }
    }

    private void updateGuiElements() {
        CookingManager.GuiConfig config = plugin.getCookingManager().getGuiConfig();
        
        if (config.progressBarEnabled) {
            if (currentRecipe != null && progress > 0) {
                int maxTime = currentRecipe.getTime();
                double pct = (double) progress / maxTime;
                if (pct > 1.0) pct = 1.0;
                if (pct < 0) pct = 0;
                
                ItemStack bar = null;
                
                // Try ItemsAdder or CraftEngine IDs first
                List<String> itemIds = config.progressBarItems;
                if (itemIds != null && !itemIds.isEmpty()) {
                     int index = (int) (pct * (itemIds.size() - 1));
                     if (index < 0) index = 0;
                     if (index >= itemIds.size()) index = itemIds.size() - 1;
                     
                     String id = itemIds.get(index);
                     
                     bar = plugin.getCookingManager().getItemWithFallback(id);
                }

                // Fallback to manual models
                if (bar == null) {
                    List<Integer> models = config.progressBarModels;
                    if (models != null && !models.isEmpty()) {
                        int index = (int) (pct * (models.size() - 1));
                        if (index < 0) index = 0;
                        if (index >= models.size()) index = models.size() - 1;
                        
                        int modelData = models.get(index);
                        
                        bar = new ItemStack(config.progressBarMaterial);
                        ItemMeta meta = bar.getItemMeta();
                        if (meta != null) {
                            meta.setCustomModelData(modelData);
                            meta.displayName(ConfigManager.getGuiText("cooking.progress_bar_name", "<percent>", String.valueOf((int)(pct * 100))));
                            bar.setItemMeta(meta);
                        }
                    }
                }
                
                // Fallback default
                if (bar == null) {
                    bar = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                    ItemMeta meta = bar.getItemMeta();
                    meta.displayName(ConfigManager.getGuiText("cooking.progress_bar_name", "<percent>", String.valueOf((int)(pct * 100))));
                    bar.setItemMeta(meta);
                }
                
                inventory.setItem(config.progressBarSlot, bar);
            } else {
                // Clear progress bar if not cooking
                ItemStack empty = new ItemStack(Material.AIR);
                // Or maybe a background item? Configurable?
                // For now, let's check if we want an "empty" state item
                inventory.setItem(config.progressBarSlot, empty);
            }
        }
    }

    private boolean tryMoveBufferToOutput(ItemStack bufferItem) {
        Recipe sourceRecipe = plugin.getCookingManager().getRecipeByResult(bufferItem);
        boolean needsContainer = (sourceRecipe != null && sourceRecipe.getContainers() != null && !sourceRecipe.getContainers().isEmpty());

        // Prepare the item to move (Strip Lore)
        ItemStack toMove = bufferItem.clone();
        ItemMeta meta = toMove.getItemMeta();
        if (meta != null && meta.hasLore()) {
            List<Component> lore = meta.lore();
            if (lore != null) {
                lore.removeIf(c -> {
                    String plain = PlainTextComponentSerializer.plainText().serialize(c);
                    return plain.contains("需盛装于") || plain.contains("(或其它可用容器)");
                });
                meta.lore(lore);
                toMove.setItemMeta(meta);
            }
        }

        if (needsContainer) {
            ItemStack containerInput = inventory.getItem(SLOT_CONTAINER_INPUT);
            if (sourceRecipe != null && containerInput != null && sourceRecipe.isContainer(containerInput)) {
                
                int maxWeCanMove = containerInput.getAmount(); // 1 container per 1 food
                int fitting = getFittingAmount(toMove); // Check fitting for CLEAN item
                int actualMove = Math.min(bufferItem.getAmount(), Math.min(maxWeCanMove, fitting));
                
                if (actualMove > 0) {
                    ItemStack movingStack = toMove.clone(); // Use clean item
                    movingStack.setAmount(actualMove);
                    addToOutput(movingStack);
                    
                    containerInput.setAmount(containerInput.getAmount() - actualMove);
                    inventory.setItem(SLOT_CONTAINER_INPUT, containerInput);
                    
                    if (bufferItem.getAmount() > actualMove) {
                        bufferItem.setAmount(bufferItem.getAmount() - actualMove);
                        inventory.setItem(SLOT_BUFFER, bufferItem);
                        return false; 
                    } else {
                        inventory.setItem(SLOT_BUFFER, null);
                        return true; 
                    }
                }
            }
            // If needs container but none found, we just stay in buffer.
        } else {
            // No container needed, just output
            int fitting = getFittingAmount(toMove); // Check fitting for CLEAN item
            int actualMove = Math.min(bufferItem.getAmount(), fitting);
            
            if (actualMove > 0) {
                ItemStack movingStack = toMove.clone(); // Use clean item
                movingStack.setAmount(actualMove);
                addToOutput(movingStack);
                
                if (bufferItem.getAmount() > actualMove) {
                    bufferItem.setAmount(bufferItem.getAmount() - actualMove);
                    inventory.setItem(SLOT_BUFFER, bufferItem);
                    return false;
                } else {
                    inventory.setItem(SLOT_BUFFER, null);
                    return true;
                }
            }
        }
        return false;
    }

    private int getFittingAmount(ItemStack item) {
        ItemStack current = inventory.getItem(SLOT_OUTPUT);
        if (current == null || current.getType().isAir()) {
            return item.getMaxStackSize();
        }
        if (current.getType() == item.getType()) {
            // Check similarity (ignoring amount) if needed, but type check is basic
            // Ideally we check isSimilar, but we stripped lore from 'item', while 'current' might strictly match result.
            // 'current' in output should already be clean.
            // So isSimilar should work.
            if (isSimilarIgnoringXP(current, item)) {
                return Math.max(0, current.getMaxStackSize() - current.getAmount());
            }
        }
        return 0;
    }

    public boolean isSimilarIgnoringXP(ItemStack stack1, ItemStack stack2) {
        if (stack1 == null || stack2 == null) return false;
        if (stack1.getType() != stack2.getType()) return false;
        if (stack1.hasItemMeta() != stack2.hasItemMeta()) return false;
        if (!stack1.hasItemMeta()) return true;

        // Special check for CraftEngine/ItemsAdder items
        // If they have the same custom ID, we consider them stackable regardless of other NBT
        String id1 = com.example.simplecuisine.util.CraftEngineHook.getCustomItemId(stack1);
        String id2 = com.example.simplecuisine.util.CraftEngineHook.getCustomItemId(stack2);
        
        if (id1 != null && id2 != null) {
            return id1.equals(id2);
        }

        ItemMeta meta1 = stack1.getItemMeta().clone();
        ItemMeta meta2 = stack2.getItemMeta().clone();
        
        org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");
        org.bukkit.NamespacedKey ceIdKey = new org.bukkit.NamespacedKey(plugin, "ce_id");
        
        meta1.getPersistentDataContainer().remove(xpKey);
        meta2.getPersistentDataContainer().remove(xpKey);
        
        // Remove CE ID for stacking check (since we already compared IDs above)
        meta1.getPersistentDataContainer().remove(ceIdKey);
        meta2.getPersistentDataContainer().remove(ceIdKey);
        
        // Remove Lore for stricter "Same Food" check (ignore flavor text/instructions)
        meta1.lore(null);
        meta2.lore(null);
        
        // Remove Display Name to allow stacking across different locales
        // e.g. "Beef" and "牛肉" should stack if they are the same item ID
        meta1.displayName(null);
        meta2.displayName(null);
        
        ItemStack s1 = stack1.clone();
        s1.setItemMeta(meta1);
        
        ItemStack s2 = stack2.clone();
        s2.setItemMeta(meta2);
        
        return s1.isSimilar(s2);
    }

    private void addToOutput(ItemStack item, int experience) {
        ItemStack current = inventory.getItem(SLOT_OUTPUT);
        
        // Prepare PDC key
        org.bukkit.NamespacedKey xpKey = new org.bukkit.NamespacedKey(plugin, "stored_exp");

        // Check if incoming item has stored XP in PDC (e.g. from buffer)
        int itemStoredXp = 0;
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta != null && itemMeta.getPersistentDataContainer().has(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER)) {
            itemStoredXp = itemMeta.getPersistentDataContainer().get(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER);
            // We should remove the tag from the item being added so it doesn't get double counted if we were to inspect it later?
            // But here we are just merging.
        }
        
        int totalXpToAdd = experience + itemStoredXp;
        
        if (current == null || current.getType().isAir()) {
            // New item, set XP
            if (totalXpToAdd > 0) {
                ItemMeta meta = item.getItemMeta(); // item is what we are setting
                if (meta != null) {
                    meta.getPersistentDataContainer().set(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, totalXpToAdd);
                    item.setItemMeta(meta);
                }
            }
            inventory.setItem(SLOT_OUTPUT, item);
        } else {
            // Existing stack, add amounts and merge XP
            current.setAmount(current.getAmount() + item.getAmount());
            
            if (totalXpToAdd > 0) {
                ItemMeta meta = current.getItemMeta();
                if (meta != null) {
                    int existingXp = meta.getPersistentDataContainer().getOrDefault(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, 0);
                    meta.getPersistentDataContainer().set(xpKey, org.bukkit.persistence.PersistentDataType.INTEGER, existingXp + totalXpToAdd);
                    current.setItemMeta(meta);
                }
            }
            inventory.setItem(SLOT_OUTPUT, current);
        }
    }

    // Overload for backward compatibility or cases without XP
    private void addToOutput(ItemStack item) {
        addToOutput(item, 0);
    }

    private void consumeIngredients(Recipe recipe) {
        // Track containers to return after consumption
        List<ItemStack> containersToReturn = new ArrayList<>();

        for (java.util.Map.Entry<Ingredient, Integer> entry : recipe.getIngredients().entrySet()) {
            Ingredient ingredient = entry.getKey();
            int amountNeeded = entry.getValue();

            // Find all matching slots
            List<Integer> matchingSlots = new ArrayList<>();
            for (int slot : INPUT_SLOTS) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && !item.getType().isAir() && ingredient.matches(item)) {
                    matchingSlots.add(slot);
                }
            }
            
            // Consume from slots
            for (int slot : matchingSlots) {
                if (amountNeeded <= 0) break;

                ItemStack item = inventory.getItem(slot);
                int available = item.getAmount();
                int toTake = Math.min(available, amountNeeded);
                
                // Prepare container if applicable
                ItemStack containerTemplate = getContainerFor(item);
                if (containerTemplate != null) {
                    // For each item consumed, we get a container
                    for (int i = 0; i < toTake; i++) {
                        containersToReturn.add(containerTemplate.clone());
                    }
                }
                
                // Update item amount
                item.setAmount(available - toTake);
                if (item.getAmount() <= 0) {
                    inventory.setItem(slot, null);
                } else {
                    inventory.setItem(slot, item);
                }
                
                amountNeeded -= toTake;
            }
        }
        
        // Return containers to inventory or drop them
        for (ItemStack container : containersToReturn) {
            dropOrStoreContainer(container);
        }
    }

    private void dropOrStoreContainer(ItemStack container) {
        boolean placed = false;
        for (int inputSlot : INPUT_SLOTS) {
            ItemStack slotItem = inventory.getItem(inputSlot);
            if (slotItem == null || slotItem.getType().isAir()) {
                inventory.setItem(inputSlot, container);
                placed = true;
                break;
            } else if (slotItem.isSimilar(container) && slotItem.getAmount() < slotItem.getMaxStackSize()) {
                if (slotItem.getAmount() + container.getAmount() <= slotItem.getMaxStackSize()) {
                    slotItem.setAmount(slotItem.getAmount() + container.getAmount());
                    inventory.setItem(inputSlot, slotItem);
                    placed = true;
                    break;
                }
            }
        }
        
        if (!placed) {
            location.getWorld().dropItem(location.clone().add(0.5, 1.0, 0.5), container.clone());
        }
    }


    private boolean checkGridStructure(Recipe recipe, List<ItemStack> inputs) {
        // Create a copy of inputs to simulate consumption
        // We want to ensure that we can satisfy the recipe requirements
        // Allowing multiple items from the same slot (Stacking)
        
        List<ItemStack> workingInputs = new ArrayList<>();
        for (ItemStack is : inputs) {
            if (is != null) {
                workingInputs.add(is.clone());
            } else {
                workingInputs.add(null);
            }
        }

        for (java.util.Map.Entry<Ingredient, Integer> entry : recipe.getIngredients().entrySet()) {
            Ingredient ingredient = entry.getKey();
            int amountNeeded = entry.getValue();
            
            int satisfied = 0;
            
            for (ItemStack item : workingInputs) {
                if (satisfied >= amountNeeded) break;
                
                if (item != null && item.getAmount() > 0 && ingredient.matches(item)) {
                    // Consume as many as needed/available from this slot
                    int available = item.getAmount();
                    int needed = amountNeeded - satisfied;
                    int toTake = Math.min(available, needed);
                    
                    item.setAmount(available - toTake);
                    satisfied += toTake;
                }
            }
            
            if (satisfied < amountNeeded) {
                return false;
            }
        }
        return true;
    }

    private boolean canMergeIntoBuffer(ItemStack result) {
        ItemStack buffer = inventory.getItem(SLOT_BUFFER);
        if (buffer == null || buffer.getType().isAir()) return true;
        
        if (!isSimilarIgnoringXP(buffer, result)) return false;
        
        return buffer.getAmount() + result.getAmount() <= buffer.getMaxStackSize();
    }

    private ItemStack getContainerFor(ItemStack item) {
        if (item == null) return null;
        Material type = item.getType();
        
        if (type.name().endsWith("BUCKET") && type != Material.BUCKET) {
            return new ItemStack(Material.BUCKET);
        }
        if (type == Material.POTION || type == Material.HONEY_BOTTLE || type == Material.DRAGON_BREATH) {
            return new ItemStack(Material.GLASS_BOTTLE);
        }
        if (type == Material.MUSHROOM_STEW || type == Material.RABBIT_STEW || type == Material.BEETROOT_SOUP || type == Material.SUSPICIOUS_STEW) {
            return new ItemStack(Material.BOWL);
        }
        return null;
    }
    
    public Location getLocation() {
        return location;
    }
}
