package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public class SkilletManager {
    private final SimpleCuisine plugin;
    private final Map<Location, Skillet> placedSkillets = new ConcurrentHashMap<>();
    private final Map<String, Set<Location>> chunkMap = new ConcurrentHashMap<>();
    
    private String getChunkKey(Location loc) {
        return loc.getWorld().getName() + "_" + (loc.getBlockX() >> 4) + "_" + (loc.getBlockZ() >> 4);
    }
    
    private void addToChunkMap(Location loc) {
        chunkMap.computeIfAbsent(getChunkKey(loc), k -> ConcurrentHashMap.newKeySet()).add(loc);
    }
    
    private void removeFromChunkMap(Location loc) {
        String key = getChunkKey(loc);
        if (chunkMap.containsKey(key)) {
            chunkMap.get(key).remove(loc);
            if (chunkMap.get(key).isEmpty()) chunkMap.remove(key);
        }
    }
    
    public void restoreVisualsInChunk(org.bukkit.Chunk chunk) {
        String key = chunk.getWorld().getName() + "_" + chunk.getX() + "_" + chunk.getZ();
        Set<Location> locs = chunkMap.get(key);
        if (locs != null) {
            for (Location loc : locs) {
                restoreVisuals(loc);
            }
        }
    }
    
    private final NamespacedKey KEY_CONTENT;
    private final NamespacedKey KEY_COOK_TIME;
    private final NamespacedKey KEY_MAX_TIME;
    private final NamespacedKey KEY_IS_COOKED;
    
    // Data class for a placed skillet
    public static class Skillet {
        private final Location location;
        private ItemStack item; // Can be a stack
        private ItemStack sourceItem; // The skillet item itself (with durability)
        private int cookingTime;
        private int maxTime;
        private boolean isCooking = false;
        
        public Skillet(Location location) {
            this.location = location;
        }
        
        public Location getLocation() { return location; }
        public ItemStack getItem() { return item; }
        public void setItem(ItemStack item, int maxTime) {
            this.item = item;
            this.cookingTime = 0;
            this.maxTime = maxTime;
            this.isCooking = false; // Default off
        }
        public ItemStack getSourceItem() { return sourceItem; }
        public void setSourceItem(ItemStack sourceItem) { this.sourceItem = sourceItem; }
        
        public boolean isEmpty() { return item == null || item.getType().isAir(); }
        public int getCookingTime() { return cookingTime; }
        public void setCookingTime(int time) { this.cookingTime = time; }
        public void incrementCookingTime() { cookingTime++; }
        public int getMaxTime() { return maxTime; }
        public boolean isCooking() { return isCooking; }
        public void setCooking(boolean cooking) { this.isCooking = cooking; }
    }

    public SkilletManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        this.KEY_CONTENT = new NamespacedKey(plugin, "skillet_content");
        this.KEY_COOK_TIME = new NamespacedKey(plugin, "skillet_cook_time");
        this.KEY_MAX_TIME = new NamespacedKey(plugin, "skillet_max_time");
        this.KEY_IS_COOKED = new NamespacedKey(plugin, "skillet_is_cooked");
        loadSkillets();
        startTask();
    }
    
    public boolean isSkillet(Location loc) {
        return placedSkillets.containsKey(loc);
    }
    
    public Skillet getSkillet(Location loc) {
        return placedSkillets.get(loc);
    }
    
    public void addSkillet(Location loc) {
        addSkillet(loc, null);
    }

    public void addSkillet(Location loc, ItemStack sourceItem) {
        if (!placedSkillets.containsKey(loc)) {
            // Check pending data first
            synchronized (pendingSkillets) {
                Iterator<PendingSkillet> it = pendingSkillets.iterator();
                while (it.hasNext()) {
                    PendingSkillet ps = it.next();
                    if (ps.worldName.equals(loc.getWorld().getName()) && 
                        ps.x == loc.getBlockX() && 
                        ps.y == loc.getBlockY() && 
                        ps.z == loc.getBlockZ()) {
                        
                        // Restore data
                        Skillet skillet = new Skillet(loc);
                        if (sourceItem != null) {
                            skillet.setSourceItem(sourceItem.clone());
                        }
                        if (ps.item != null) {
                            skillet.setItem(ps.item, ps.maxTime);
                            skillet.setCookingTime(ps.cookingTime);
                            skillet.setCooking(ps.isCooking);
                        }
                        placedSkillets.put(loc, skillet);
                        addToChunkMap(loc);
                        if (loc.isChunkLoaded()) {
                            restoreVisuals(loc);
                        }
                        it.remove();
                        plugin.getLogger().info("Lazy restored skillet data at " + loc);
                        return; // Done
                    }
                }
            }
            
            // No pending data found, create new empty
            Skillet skillet = new Skillet(loc);
            if (sourceItem != null) {
                skillet.setSourceItem(sourceItem.clone());
            }
            placedSkillets.put(loc, skillet);
            addToChunkMap(loc);
        }
    }
    
    public void removeSkillet(Location loc) {
        // Drop content
        Skillet skillet = placedSkillets.get(loc);
        if (skillet != null) {
            if (!skillet.isEmpty()) {
                ItemStack item = skillet.getItem();
                if (item != null) {
                    loc.getWorld().dropItem(loc.clone().add(0.5, 0.5, 0.5), item);
                }
            }
            // Drop the skillet itself (custom item with durability)
            if (skillet.getSourceItem() != null) {
                ItemStack drop = skillet.getSourceItem().clone();
                drop.setAmount(1);
                loc.getWorld().dropItemNaturally(loc, drop);
            } else {
                // Fallback if no source item saved (e.g. legacy placed), let the block break event handle it?
                // Or drop a fresh one? 
                // If we rely on block break event for fallback, we must coordinate with listener.
                // Listener will setDropItems(false). So we MUST drop something here.
                // Drop default skillet
                if (CraftEngineHook.isEnabled()) {
                    ItemStack fresh = CraftEngineHook.createItem("farmersdelight:skillet", null);
                    if (fresh != null) {
                        loc.getWorld().dropItemNaturally(loc, fresh);
                    }
                }
            }
        }
        
        cleanupVisualsAt(loc);
        placedSkillets.remove(loc);
        removeFromChunkMap(loc);
    }

    public void cleanupAllVisualsAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        Location center = loc.clone().add(0.5, 0.5, 0.5); 
        for (Entity e : center.getWorld().getNearbyEntities(center, 0.8, 0.8, 0.8)) {
            if (e.getScoreboardTags().contains("simplecuisine_skillet_visual")) {
                e.remove();
            }
        }
    }
    
    public void restoreVisuals(Location loc) {
        Skillet skillet = placedSkillets.get(loc);
        if (skillet == null) return;
        
        cleanupVisualsAt(loc);
        if (!skillet.isEmpty()) {
            createVisual(skillet);
        }
    }
    
    private void createVisual(Skillet skillet) {
        Location loc = skillet.getLocation();
        if (!loc.isChunkLoaded()) return; // Lazy init
        
        ItemStack itemStack = skillet.getItem();
        if (itemStack == null || itemStack.getType().isAir()) return;

        int count = itemStack.getAmount();
        int displayCount = Math.min(count, 4); // Max 4 visuals
        
        // Seeded random for consistent "messy" look
        Random random = new Random(loc.hashCode());
        
        for (int i = 0; i < displayCount; i++) {
            // Messy offsets: +/- 0.15 X/Z
            double offsetX = (random.nextDouble() - 0.5) * 0.3; 
            double offsetZ = (random.nextDouble() - 0.5) * 0.3;
            // Stacked Y: 0.1 + (i * 0.03)
            double offsetY = 0.1 + (i * 0.03);
            
            Location displayLoc = loc.clone().add(0.5 + offsetX, offsetY, 0.5 + offsetZ);
            
            ItemDisplay display = loc.getWorld().spawn(displayLoc, ItemDisplay.class);
            ItemStack visualItem = itemStack.clone();
            visualItem.setAmount(1);
            display.setItemStack(visualItem);
            display.addScoreboardTag("simplecuisine_skillet_visual");
            
            // Random Rotation Y
            float rotY = random.nextFloat() * 360;
            display.setRotation(rotY, 0);
            
            // Flat on the pan
            Transformation transformation = display.getTransformation();
            transformation.getScale().set(0.5f, 0.5f, 0.5f);
            transformation.getLeftRotation().set(new AxisAngle4f((float) Math.toRadians(90), 1, 0, 0));
            display.setTransformation(transformation);
            
            display.setPersistent(true); // VITAL: Prevents ghost entities on restart
        }
    }
    
    private void updateVisual(Skillet skillet) {
        cleanupVisualsAt(skillet.getLocation());
        if (!skillet.isEmpty()) {
            createVisual(skillet);
        }
    }
    
    private void cleanupVisualsAt(Location loc) {
        cleanupAllVisualsAt(loc);
    }

    // Interaction
    public boolean handleInteract(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        Location loc = block.getLocation();
        Skillet skillet = placedSkillets.get(loc);
        if (skillet == null) {
            addSkillet(loc);
            skillet = placedSkillets.get(loc);
        }
        
        if (itemInHand == null || itemInHand.getType().isAir()) {
            // Retrieve
            if (!skillet.isEmpty()) {
                ItemStack item = skillet.getItem().clone();
                skillet.setItem(null, 0);
                updateVisual(skillet);
                
                // Drop item
                loc.getWorld().dropItem(loc.clone().add(0.5, 1.0, 0.5), item);
                loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
                return true;
            }
            return false;
        } else {
            // Place
            if (skillet.isEmpty()) {
                // Yield if tool (for offhand support)
                if (isTool(itemInHand)) {
                    return false;
                }

                // Prevent placing Skillet on Skillet (Issue 1)
                if (isSkilletItem(itemInHand)) {
                    return false;
                }
            
                // Check recipe
                StoveManager.StoveRecipe recipe = plugin.getStoveManager().getRecipe(itemInHand);
                if (recipe != null) {
                    // [Performance Fix] Check heat only on interaction
                    if (hasHeatSource(loc)) {
                        // Place entire stack (or 1 depending on intent, but user asked for stack support)
                        ItemStack toPlace = itemInHand.clone();
                        // toPlace.setAmount(1); // REMOVED: Allow stacking
                        
                        skillet.setItem(toPlace, recipe.getCookingTime());
                        skillet.setCooking(true); // Fix: Explicitly set cooking to true
                        itemInHand.setAmount(0); // Consume all
                        
                        updateVisual(skillet);
                        loc.getWorld().playSound(loc, Sound.BLOCK_LANTERN_PLACE, 1.0f, 1.0f);
                        return true;
                    } else {
                        // Optional: Feedback for no heat?
                        // For now, allow placement but it won't cook? 
                        // User said: "trigger detection... putting food in".
                        // If we allow placement without heat, tick needs to know not to cook.
                        // But current tick implementation iterates all skillets.
                        // If we want to avoid checking heat in tick, we must set a state here.
                        // Let's allow placement, but set cookingTime to a special state?
                        // Or just set it normally, and rely on tick NOT checking heat?
                        // If tick doesn't check heat, and we allow placement on cold block, it will cook on cold block!
                        // So we MUST REJECT placement if cold? Or allow placement but don't start?
                        // If we reject, player knows "needs fire".
                        // If we accept but don't cook, player is confused.
                        // Let's REJECT placement if no heat, or at least warn.
                        // Actually, vanilla campfire allows placement even if unlit.
                        // But for performance, user said: "check ... putting food in".
                        // I will allow placement, but if cold, I set maxTime to -1 or similar to pause?
                        // No, let's just check heat here. If cold, tell player "Need heat source".
                        // But wait, user said "trigger detection".
                        // Let's implement: Allow placement. Set 'isCooking' flag on Skillet.
                        // If hasHeat -> isCooking = true.
                        // If noHeat -> isCooking = false.
                        // Tick only processes if isCooking = true.
                        
                        boolean isHot = hasHeatSource(loc);
                        
                        ItemStack toPlace = itemInHand.clone();
                        skillet.setItem(toPlace, recipe.getCookingTime());
                        skillet.setCooking(isHot); // Needs new method
                        
                        itemInHand.setAmount(0); 
                        updateVisual(skillet);
                        loc.getWorld().playSound(loc, Sound.BLOCK_LANTERN_PLACE, 1.0f, 1.0f);
                        
                        if (!isHot) {
                            // Visual feedback that it's not cooking?
                            // Maybe particles or sound?
                        }
                        return true;
                    }
                }
                
                return false;
            }
            
            return false;
        }
    }
    
    private boolean isTool(ItemStack item) {
        String name = item.getType().name();
        return name.endsWith("_SWORD") || 
               name.endsWith("_AXE") || 
               name.endsWith("_HOE") || 
               name.endsWith("_SHOVEL") || 
               name.endsWith("_PICKAXE") || 
               name.contains("KNIFE") || // Support Farmer's Delight Knives
               name.equals("SHEARS") || 
               name.equals("FLINT_AND_STEEL");
    }

    private void startTask() {
        // [Performance Fix] Reduced tick rate from 1L to 20L.
        new BukkitRunnable() {
            @Override
            public void run() {
                tickPlaced();
                tickHandheld();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
    
    private void tickPlaced() {
        try {
            for (Skillet skillet : placedSkillets.values()) {
                Location loc = skillet.getLocation();
                if (!loc.isChunkLoaded()) continue;
                
                if (skillet.isEmpty()) continue;
                
                // If not cookable, skip
                if (skillet.getMaxTime() <= 0) continue;
                
                // [Performance Fix] Check heat persistence
                if (!skillet.isCooking()) continue; // Skip if not flagged as cooking
                
                // If already cooked, just wait for pickup (or burn if we implemented burning)
                // For now, if cooked, skip heat check to save performance
                if (skillet.getCookingTime() >= skillet.getMaxTime()) {
                     // Check if it needs to finish (convert item)
                     // If item is already the result, finishCooking won't do anything harm but logic wise we should check
                     // finishCooking does: getRecipe(input). If input is already cooked (e.g. Steak), getRecipe(Steak) returns null?
                     // Yes, unless Steak can be cooked further.
                     // So we should try finishCooking.
                     finishCooking(skillet);
                     continue;
                }
                
                // Check Heat Source (Campfire, Stove, Fire, Lava, Furnace)
                // [Performance Fix] Removed polling. Rely on isCooking state.
                // if (!hasHeatSource(loc)) continue;
                
                // Increment Time (Adjusted for slower tick rate)
                // Since we now tick every 20 ticks (1s), we add 20 to cooking time (assuming cookingTime is in ticks)
                // OR if cookingTime is meant to be ticks, we add 20.
                skillet.setCookingTime(skillet.getCookingTime() + 20);
                
                // Particles
                if (Math.random() < 0.1) {
                    loc.getWorld().spawnParticle(Particle.SMOKE, loc.clone().add(0.5, 0.5, 0.5), 1, 0.1, 0.1, 0.1, 0.02);
                }
                
                // Check Done
                if (skillet.getCookingTime() >= skillet.getMaxTime()) {
                    finishCooking(skillet);
                }
            }
        } catch (Exception e) {
            // Prevent task from dying
            // plugin.getLogger().warning("Error in SkilletManager tick: " + e.getMessage());
        }
    }
    
    private boolean hasHeatSource(Location loc) {
        // [Performance Fix] Optimized search: Check ONLY block directly below
        Block below = loc.clone().add(0, -1, 0).getBlock();
        return isHeatSource(below);
    }
    
    private boolean isHeatSource(Block block) {
        return plugin.getCookingManager().isHeatSource(block);
    }
    
    private void finishCooking(Skillet skillet) {
        ItemStack input = skillet.getItem();
        if (input == null) return;
        
        StoveManager.StoveRecipe recipe = plugin.getStoveManager().getRecipe(input); // Use recipe from input type
        if (recipe != null) {
            ItemStack result = recipe.getResult();
            result.setAmount(input.getAmount()); // Preserve stack size!
            
            // Check if the RESULT can be cooked further
            StoveManager.StoveRecipe nextRecipe = plugin.getStoveManager().getRecipe(result);
            int nextTime = (nextRecipe != null) ? nextRecipe.getTime() : -1;
            
            skillet.setItem(result, nextTime);
            updateVisual(skillet);
            skillet.getLocation().getWorld().playSound(skillet.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
            
            // Particles
            skillet.getLocation().getWorld().spawnParticle(Particle.HAPPY_VILLAGER, skillet.getLocation().clone().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2);
        } else {
            // No recipe found. Stop cooking.
            skillet.setItem(input, -1);
        }
    }

    // Persistence
    private final List<PendingSkillet> pendingSkillets = Collections.synchronizedList(new ArrayList<>());

    private static class PendingSkillet {
        final String worldName;
        final int x, y, z;
        final ItemStack item;
        final ItemStack sourceItem; // Added
        final int cookingTime;
        final int maxTime;
        final boolean isCooking;

        PendingSkillet(String worldName, int x, int y, int z, ItemStack item, ItemStack sourceItem, int cookingTime, int maxTime, boolean isCooking) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.item = item;
            this.sourceItem = sourceItem;
            this.cookingTime = cookingTime;
            this.maxTime = maxTime;
            this.isCooking = isCooking;
        }
    }

    public void saveSkillets() {
        // Save placed skillets to skillets.yml
        File file = new File(plugin.getDataFolder(), "skillets.yml");
        YamlConfiguration config = new YamlConfiguration();
        
        int count = 0;
        
        // Save active skillets
        for (Skillet skillet : placedSkillets.values()) {
            String key = "skillet_" + count++;
            Location loc = skillet.getLocation();
            String locStr = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            
            config.set(key + ".location", locStr);
            if (skillet.getSourceItem() != null) {
                config.set(key + ".sourceItem", skillet.getSourceItem());
            }
            if (!skillet.isEmpty()) {
                config.set(key + ".item", skillet.getItem());
                config.set(key + ".time", skillet.getCookingTime());
                config.set(key + ".maxTime", skillet.getMaxTime());
                config.set(key + ".isCooking", skillet.isCooking());
            }
        }
        
        // Save pending skillets
        synchronized (pendingSkillets) {
            for (PendingSkillet ps : pendingSkillets) {
                String key = "skillet_" + count++;
                String locStr = ps.worldName + "," + ps.x + "," + ps.y + "," + ps.z;
                
                config.set(key + ".location", locStr);
                if (ps.sourceItem != null) {
                    config.set(key + ".sourceItem", ps.sourceItem);
                }
                config.set(key + ".item", ps.item);
                config.set(key + ".time", ps.cookingTime);
                config.set(key + ".maxTime", ps.maxTime);
                config.set(key + ".isCooking", ps.isCooking);
            }
        }
        
        try {
            config.save(file);
            plugin.getLogger().info("Saved " + count + " placed skillets (Active: " + placedSkillets.size() + ", Pending: " + pendingSkillets.size() + ").");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save skillets.yml: " + e.getMessage());
        }
    }
    
    public void loadSkillets() {
        File file = new File(plugin.getDataFolder(), "skillets.yml");
        if (!file.exists()) return;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        placedSkillets.clear();
        pendingSkillets.clear();
        
        for (String key : config.getKeys(false)) {
            String locStr = config.getString(key + ".location");
            if (locStr == null) continue;
            
            String[] parts = locStr.split(",");
            if (parts.length != 4) continue;
            
            String worldName = parts[0];
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            ItemStack item = config.getItemStack(key + ".item");
            ItemStack sourceItem = config.getItemStack(key + ".sourceItem");
            int time = config.getInt(key + ".time", 0);
            int maxTime = config.getInt(key + ".maxTime", 0);
            boolean isCooking = config.getBoolean(key + ".isCooking", false);
            
            org.bukkit.World world = Bukkit.getWorld(worldName);
            
            // Handle unloaded world
            if (world == null) {
                pendingSkillets.add(new PendingSkillet(worldName, x, y, z, item, sourceItem, time, maxTime, isCooking));
                continue;
            }
            
            Location loc = new Location(world, x, y, z);
            
            Skillet skillet = new Skillet(loc);
            if (sourceItem != null) {
                skillet.setSourceItem(sourceItem);
            }
            if (item != null) {
                skillet.setItem(item, maxTime);
                skillet.setCookingTime(time);
                skillet.setCooking(isCooking);
            }
            
            placedSkillets.put(loc, skillet);
            addToChunkMap(loc);
            
            if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                restoreVisuals(loc);
            }
        }
        plugin.getLogger().info("Loaded " + placedSkillets.size() + " placed skillets (and " + pendingSkillets.size() + " pending).");
    }
    
    public void tryLoadPending(org.bukkit.World world) {
        if (world == null) return;
        String name = world.getName();
        int loadedCount = 0;
        
        synchronized (pendingSkillets) {
            Iterator<PendingSkillet> it = pendingSkillets.iterator();
            while (it.hasNext()) {
                PendingSkillet ps = it.next();
                if (ps.worldName.equals(name)) {
                    Location loc = new Location(world, ps.x, ps.y, ps.z);
                    Skillet skillet = new Skillet(loc);
                    
                    if (ps.sourceItem != null) {
                        skillet.setSourceItem(ps.sourceItem);
                    }
                    if (ps.item != null) {
                        skillet.setItem(ps.item, ps.maxTime);
                        skillet.setCookingTime(ps.cookingTime);
                        skillet.setCooking(ps.isCooking);
                    }
                    
                    placedSkillets.put(loc, skillet);
                    addToChunkMap(loc);
                    
                    if (loc.isChunkLoaded()) {
                        restoreVisuals(loc);
                    }
                    
                    it.remove();
                    loadedCount++;
                }
            }
        }
        
        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending skillets for world: " + name);
        }
    }
    
    public void removeAllVisuals() {
        for (Location loc : placedSkillets.keySet()) {
            cleanupVisualsAt(loc);
        }
    }
    
    public Set<Location> getAllSkilletLocations() {
        return placedSkillets.keySet();
    }
    
    // Handheld Logic & Helpers
    
    private byte[] serializeItem(ItemStack item) {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream();
             BukkitObjectOutputStream bos = new BukkitObjectOutputStream(os)) {
            bos.writeObject(item);
            return os.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(data);
             BukkitObjectInputStream bis = new BukkitObjectInputStream(is)) {
            return (ItemStack) bis.readObject();
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    public boolean isSkilletItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (CraftEngineHook.isEnabled()) {
            String id = CraftEngineHook.getCustomItemId(item);
            if ("farmersdelight:skillet".equals(id)) return true;
        }
        if (ItemsAdderHook.isItemsAdderLoaded()) {
            String id = ItemsAdderHook.getCustomItemId(item);
            if ("farmersdelight:skillet".equals(id)) return true;
        }
        return false;
    }
    
    public boolean tryStartHandheldCooking(Player player, ItemStack skillet, ItemStack food) {
        ItemMeta meta = skillet.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Must be empty
        if (pdc.has(KEY_CONTENT, PersistentDataType.BYTE_ARRAY)) return false;
        
        // Check recipe
        StoveManager.StoveRecipe recipe = plugin.getStoveManager().getRecipe(food);
        if (recipe == null) return false;
        
        // Take WHOLE stack
        ItemStack toCook = food.clone();
        food.setAmount(0); // Consume all
        
        // Setup Skillet
        pdc.set(KEY_CONTENT, PersistentDataType.BYTE_ARRAY, serializeItem(toCook));
        pdc.set(KEY_COOK_TIME, PersistentDataType.INTEGER, 0);
        pdc.set(KEY_MAX_TIME, PersistentDataType.INTEGER, recipe.getTime());
        
        // No Lore - Removed to preserve original item lore
        // meta.lore(null);
        
        skillet.setItemMeta(meta);
        
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1.0f, 1.0f);
        player.sendActionBar(Component.text("§e开始烹饪: " + getDisplayName(toCook)));
        return true;
    }
    
    public boolean tryRetrieveHandheldCooking(Player player, ItemStack skillet) {
        ItemMeta meta = skillet.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // Retrieve content (Allow retrieval at ANY state, cooked or raw)
        byte[] data = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
        if (data == null) return false; // Nothing to retrieve
        
        ItemStack content = deserializeItem(data);
        if (content != null) {
            // Give to player
            HashMap<Integer, ItemStack> left = player.getInventory().addItem(content);
            if (!left.isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), left.get(0));
            }
            
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
            player.sendActionBar(Component.text("§a取出: " + getDisplayName(content)));
        }
        
        // Clear Skillet
        pdc.remove(KEY_CONTENT);
        pdc.remove(KEY_COOK_TIME);
        pdc.remove(KEY_MAX_TIME);
        pdc.remove(KEY_IS_COOKED);
        // meta.lore(null); // Removed to preserve original item lore
        
        skillet.setItemMeta(meta);
        return true;
    }
    
    public void openHandheldMenu(Player player, ItemStack skilletItem) {
        Inventory inv = Bukkit.createInventory(player, 9, Component.text("煎锅"));
        
        ItemMeta meta = skilletItem.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (pdc.has(KEY_CONTENT, PersistentDataType.BYTE_ARRAY)) {
                byte[] data = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
                ItemStack content = deserializeItem(data);
                if (content != null) {
                    inv.setItem(4, content);
                }
            }
        }
        
        player.openInventory(inv);
    }
    
    public void saveHandheldMenu(Player player, Inventory inv) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!isSkilletItem(held)) {
             held = player.getInventory().getItemInOffHand();
             if (!isSkilletItem(held)) return;
        }
        
        ItemStack content = inv.getItem(4);
        ItemMeta meta = held.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        if (content != null && !content.getType().isAir()) {
            StoveManager.StoveRecipe recipe = plugin.getStoveManager().getRecipe(content);
            int maxTime = (recipe != null) ? recipe.getTime() : 100;
            
            // Check if item changed
            byte[] oldData = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
            boolean sameItem = false;
            if (oldData != null) {
                ItemStack old = deserializeItem(oldData);
                if (old != null && old.isSimilar(content)) {
                    sameItem = true;
                }
            }
            
            pdc.set(KEY_CONTENT, PersistentDataType.BYTE_ARRAY, serializeItem(content));
            if (!sameItem) {
                pdc.set(KEY_COOK_TIME, PersistentDataType.INTEGER, 0);
            }
            if (!pdc.has(KEY_COOK_TIME, PersistentDataType.INTEGER)) {
                 pdc.set(KEY_COOK_TIME, PersistentDataType.INTEGER, 0);
            }
            pdc.set(KEY_MAX_TIME, PersistentDataType.INTEGER, maxTime);
            
            // List<Component> lore = new ArrayList<>();
            // lore.add(Component.text("§7正在烹饪: " + content.getAmount() + "x " + getDisplayName(content)));
            // meta.lore(lore);
            
        } else {
            pdc.remove(KEY_CONTENT);
            pdc.remove(KEY_COOK_TIME);
            pdc.remove(KEY_MAX_TIME);
            // meta.lore(null);
        }
        
        held.setItemMeta(meta);
    }
    
    private String getDisplayName(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            // Best effort to get a string representation
            Component displayName = item.getItemMeta().displayName();
            if (displayName != null) {
                // Basic serializer
                 return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName);
            }
        }
        return item.getType().name().toLowerCase().replace("_", " ");
    }
    
    private final Map<UUID, HandheldSession> sessions = new ConcurrentHashMap<>();

    private static class HandheldSession {
        int mainHandTime = -1;
        int offHandTime = -1;
    }
    
    private void tickHandheld() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayerHand(player, EquipmentSlot.HAND);
            tickPlayerHand(player, EquipmentSlot.OFF_HAND);
        }
    }
    
    private void tickPlayerHand(Player player, EquipmentSlot slot) {
        ItemStack item = (slot == EquipmentSlot.HAND) ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        
        HandheldSession session = sessions.computeIfAbsent(player.getUniqueId(), k -> new HandheldSession());
        
        if (!isSkilletItem(item)) {
            if (slot == EquipmentSlot.HAND) session.mainHandTime = -1;
            else session.offHandTime = -1;
            return;
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        
        // [Performance Fix] Strict Check: Only tick if skillet has content (food)
        if (!pdc.has(KEY_CONTENT, PersistentDataType.BYTE_ARRAY)) {
             if (slot == EquipmentSlot.HAND) session.mainHandTime = -1;
            else session.offHandTime = -1;
            return;
        }
        
        // Don't cook if already cooked
        if (pdc.has(KEY_IS_COOKED, PersistentDataType.BYTE)) {
             if (slot == EquipmentSlot.HAND) session.mainHandTime = -1;
            else session.offHandTime = -1;
            return;
        }
        
        // Check Heat
        boolean hasHeat = hasHeatSource(player.getLocation());
        // Also check if standing IN fire/lava
        Material footBlock = player.getLocation().getBlock().getType();
        if (footBlock == Material.FIRE || footBlock == Material.SOUL_FIRE || footBlock == Material.LAVA) {
            hasHeat = true;
        }
        
        if (!hasHeat) {
            // Need Heat Warning
            byte[] data = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
            ItemStack content = deserializeItem(data);
            String name = (content != null) ? getDisplayName(content) : "未知";
            player.sendActionBar(Component.text("§7" + name + " §c(需靠近热源)"));
            return;
        }
        
        // Get Time
        int time;
        int storedTime = pdc.getOrDefault(KEY_COOK_TIME, PersistentDataType.INTEGER, 0);
        int cachedTime = (slot == EquipmentSlot.HAND) ? session.mainHandTime : session.offHandTime;
        
        if (cachedTime == -1) {
            time = storedTime;
        } else {
            time = cachedTime;
        }
        
        int maxTime = pdc.getOrDefault(KEY_MAX_TIME, PersistentDataType.INTEGER, 100);
        
        time++;
        
        // Update Session
        if (slot == EquipmentSlot.HAND) session.mainHandTime = time;
        else session.offHandTime = time;
        
        // Visuals (Particles)
        if (time % 10 == 0) {
             player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 1, 0.2, 0.2, 0.2, 0.01);
        }
        
        // Action Bar & Save (Every 20 ticks or Finish)
        if (time % 20 == 0 || time >= maxTime) {
            
            // Finish?
            if (time >= maxTime) {
                byte[] data = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
                ItemStack content = deserializeItem(data);
                if (content != null) {
                    StoveManager.StoveRecipe recipe = plugin.getStoveManager().getRecipe(content);
                    if (recipe != null) {
                        ItemStack result = recipe.getResult();
                        result.setAmount(content.getAmount());
                        
                        // Auto-Collect
                        HashMap<Integer, ItemStack> left = player.getInventory().addItem(result);
                        if (!left.isEmpty()) {
                            player.getWorld().dropItem(player.getLocation(), left.get(0));
                        }
                        
                        // Clear PDC
                        pdc.remove(KEY_CONTENT);
                        pdc.remove(KEY_COOK_TIME);
                        pdc.remove(KEY_MAX_TIME);
                        pdc.remove(KEY_IS_COOKED);
                        
                        // No Lore update
                        // meta.lore(null);
                        
                        item.setItemMeta(meta);
                        // Force update inventory to ensure persistence
                        if (slot == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(item);
                        else player.getInventory().setItemInOffHand(item);
                        
                        player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f);
                        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5);
                        player.sendActionBar(Component.text("§a烹饪完成! 已放入背包"));
                        
                        // Reset session
                         if (slot == EquipmentSlot.HAND) session.mainHandTime = -1;
                         else session.offHandTime = -1;
                         
                        return;
                    }
                }
            } else {
                // Checkpoint Save
                pdc.set(KEY_COOK_TIME, PersistentDataType.INTEGER, time);
                item.setItemMeta(meta);
                
                // Action Bar
                byte[] data = pdc.get(KEY_CONTENT, PersistentDataType.BYTE_ARRAY);
                ItemStack content = deserializeItem(data);
                if (content != null) {
                    int percent = (int) ((double) time / maxTime * 100);
                    player.sendActionBar(Component.text("§6正在烹饪: " + getDisplayName(content) + " (" + percent + "%)"));
                }
            }
        }
    }
}
