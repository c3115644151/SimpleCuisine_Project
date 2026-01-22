package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StoveManager {
    private final SimpleCuisine plugin;
    private final Map<Location, Stove> stoves = new HashMap<>();
    private final List<StoveRecipe> recipes = new ArrayList<>();

    // Pending Data for unloaded worlds
    private static class PendingStove {
        String worldName;
        int x, y, z;
        Map<Integer, ItemStack> items = new HashMap<>();
        Map<Integer, Integer> times = new HashMap<>();
        Map<Integer, Integer> maxTimes = new HashMap<>();
        
        PendingStove(String worldName, int x, int y, int z) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
    private final List<PendingStove> pendingStoves = Collections.synchronizedList(new ArrayList<>());
    
    // 3x2 Grid Offsets (Approximate for a full block surface)
    // Row 1 (Back): -0.2, -0.2 | 0.0, -0.2 | 0.2, -0.2 (relative to center)
    // Row 2 (Front): -0.2, 0.2 | 0.0, 0.2 | 0.2, 0.2
    // Actually FD is 3x2. Let's try to fit them nicely.
    // 0 1 2
    // 3 4 5
    private final double[][] SLOT_OFFSETS = {
        {-0.25, 1.02, -0.2}, {0.0, 1.02, -0.2}, {0.25, 1.02, -0.2},
        {-0.25, 1.02, 0.2},  {0.0, 1.02, 0.2},  {0.25, 1.02, 0.2}
    };
    
    // Inner class for recipe data
    public static class StoveRecipe {
        private final Ingredient input;
        private final ItemStack result;
        private final int time;
        private final float experience;
        
        public StoveRecipe(Ingredient input, ItemStack result, int time, float experience) {
            this.input = input;
            this.result = result;
            this.time = time;
            this.experience = experience;
        }
        
        public boolean matches(ItemStack item) {
            return input.matches(item);
        }

        public ItemStack getResult() { return result.clone(); }
        public int getTime() { return time; }
        public int getCookingTime() { return time; } // Alias
        public float getExperience() { return experience; }
    }

    // State switching flag to prevent data loss during block replace
    private final Set<Location> switchingState = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public StoveManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        loadRecipes();
        loadStoves();
        startTask();
    }
    public void setSwitchingState(Location loc, boolean switching) {
        if (switching) switchingState.add(loc);
        else switchingState.remove(loc);
    }
    
    public boolean isSwitchingState(Location loc) {
        return switchingState.contains(loc);
    }
    
    public boolean isStove(Location loc) {
        return stoves.containsKey(loc);
    }
    
    public void addStove(Location loc) {
        if (!stoves.containsKey(loc)) {
            // Check pending data first
            synchronized (pendingStoves) {
                Iterator<PendingStove> it = pendingStoves.iterator();
                while (it.hasNext()) {
                    PendingStove ps = it.next();
                    if (ps.worldName.equals(loc.getWorld().getName()) && 
                        ps.x == loc.getBlockX() && 
                        ps.y == loc.getBlockY() && 
                        ps.z == loc.getBlockZ()) {
                        
                        // Restore data
                        Stove stove = new Stove(loc);
                        for (Map.Entry<Integer, ItemStack> entry : ps.items.entrySet()) {
                            int slot = entry.getKey();
                            stove.setItem(slot, entry.getValue(), ps.maxTimes.getOrDefault(slot, 600));
                            // Restore cooking progress if needed (Stove class needs method or direct field access)
                            // Assuming setItem resets time, we might need to manually set it back if API allows
                            // For now, let's just restore item and maxTime. Cooking progress might reset but item is saved.
                        }
                        stoves.put(loc, stove);
                        // restoreVisuals(loc); // Stove listener handles visual restoration on chunk load or update
                        it.remove();
                        plugin.getLogger().info("Lazy restored stove data at " + loc);
                        return; // Done
                    }
                }
            }

            // No pending data found, create new empty
            stoves.put(loc, new Stove(loc));
        }
    }

    public Set<Location> getAllStoveLocations() {
        return stoves.keySet();
    }

    public void saveStoves() {
        File file = new File(plugin.getDataFolder(), "stoves.yml");
        YamlConfiguration config = new YamlConfiguration();
        
        int count = 0;
        
        // Save active stoves
        for (Map.Entry<Location, Stove> entry : stoves.entrySet()) {
            Location loc = entry.getKey();
            Stove stove = entry.getValue();
            
            // Key: world,x,y,z
            String key = "stove_" + count++;
            String locStr = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            
            config.set(key + ".location", locStr);
            
            // Save Items
            for (int i = 0; i < stove.getSlotCount(); i++) {
                if (!stove.isSlotEmpty(i)) {
                    config.set(key + ".items." + i, stove.getItem(i));
                    config.set(key + ".times." + i, stove.getCookingTime(i));
                    config.set(key + ".maxTimes." + i, stove.getMaxTime(i));
                }
            }
        }
        
        // Save pending stoves
        synchronized (pendingStoves) {
            for (PendingStove ps : pendingStoves) {
                String key = "stove_" + count++;
                String locStr = ps.worldName + "," + ps.x + "," + ps.y + "," + ps.z;
                
                config.set(key + ".location", locStr);
                
                for (Map.Entry<Integer, ItemStack> entry : ps.items.entrySet()) {
                    int slot = entry.getKey();
                    config.set(key + ".items." + slot, entry.getValue());
                    config.set(key + ".times." + slot, ps.times.getOrDefault(slot, 0));
                    config.set(key + ".maxTimes." + slot, ps.maxTimes.getOrDefault(slot, 600));
                }
            }
        }
        
        try {
            config.save(file);
            plugin.getLogger().info("Saved " + count + " stoves (Active: " + stoves.size() + ", Pending: " + pendingStoves.size() + ").");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save stoves.yml: " + e.getMessage());
        }
    }
    
    public void loadStoves() {
        File file = new File(plugin.getDataFolder(), "stoves.yml");
        if (!file.exists()) return;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        stoves.clear();
        pendingStoves.clear();
        
        for (String key : config.getKeys(false)) {
            String locStr = config.getString(key + ".location");
            if (locStr == null) continue;
            
            String[] parts = locStr.split(",");
            if (parts.length != 4) continue;
            
            String worldName = parts[0];
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            org.bukkit.World world = Bukkit.getWorld(worldName);
            
            // Handle unloaded world
            if (world == null) {
                PendingStove ps = new PendingStove(worldName, x, y, z);
                if (config.contains(key + ".items")) {
                    ConfigurationSection itemSec = config.getConfigurationSection(key + ".items");
                    for (String slotStr : itemSec.getKeys(false)) {
                        int slot = Integer.parseInt(slotStr);
                        ps.items.put(slot, itemSec.getItemStack(slotStr));
                        ps.times.put(slot, config.getInt(key + ".times." + slot, 0));
                        ps.maxTimes.put(slot, config.getInt(key + ".maxTimes." + slot, 600));
                    }
                }
                pendingStoves.add(ps);
                continue;
            }
            
            Location loc = new Location(world, x, y, z);
            
            Stove stove = new Stove(loc);
            
            // Load Items
            if (config.contains(key + ".items")) {
                ConfigurationSection itemSec = config.getConfigurationSection(key + ".items");
                for (String slotStr : itemSec.getKeys(false)) {
                    int slot = Integer.parseInt(slotStr);
                    ItemStack item = itemSec.getItemStack(slotStr);
                    int time = config.getInt(key + ".times." + slot, 0);
                    int maxTime = config.getInt(key + ".maxTimes." + slot, 600);
                    
                    stove.setItem(slot, item, maxTime);
                    // Manually set current time as setItem resets it
                    for(int t=0; t<time; t++) stove.incrementCookingTime(slot);
                }
            }
            
            stoves.put(loc, stove);
            
            // If chunk is already loaded (e.g. reload), restore visuals immediately
            if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                cleanupAllVisualsAt(loc);
                for (int i = 0; i < stove.getSlotCount(); i++) {
                    if (!stove.isSlotEmpty(i)) {
                        createVisual(stove, i);
                    }
                }
            }
        }
        plugin.getLogger().info("Loaded " + stoves.size() + " stoves (and " + pendingStoves.size() + " pending).");
    }
    
    public void tryLoadPending(org.bukkit.World world) {
        if (world == null) return;
        String name = world.getName();
        int loadedCount = 0;
        
        synchronized (pendingStoves) {
            Iterator<PendingStove> it = pendingStoves.iterator();
            while (it.hasNext()) {
                PendingStove ps = it.next();
                if (ps.worldName.equals(name)) {
                    Location loc = new Location(world, ps.x, ps.y, ps.z);
                    Stove stove = new Stove(loc);
                    
                    for (Map.Entry<Integer, ItemStack> entry : ps.items.entrySet()) {
                        int slot = entry.getKey();
                        stove.setItem(slot, entry.getValue(), ps.maxTimes.getOrDefault(slot, 600));
                        int time = ps.times.getOrDefault(slot, 0);
                        for(int t=0; t<time; t++) stove.incrementCookingTime(slot);
                    }
                    
                    stoves.put(loc, stove);
                    
                    if (loc.isChunkLoaded()) {
                        cleanupAllVisualsAt(loc);
                        for (int i = 0; i < stove.getSlotCount(); i++) {
                            if (!stove.isSlotEmpty(i)) {
                                createVisual(stove, i);
                            }
                        }
                    }
                    
                    it.remove();
                    loadedCount++;
                }
            }
        }
        
        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending stoves for world: " + name);
        }
    }
    
    // Restore visuals for a stove (called on chunk load)
    public void restoreVisuals(Location loc) {
        Stove stove = stoves.get(loc);
        if (stove == null) return;
        
        cleanupAllVisualsAt(loc);
        for (int i = 0; i < stove.getSlotCount(); i++) {
            if (!stove.isSlotEmpty(i)) {
                createVisual(stove, i);
            }
        }
    }

    public void loadRecipes() {
        recipes.clear();
        
        // Load from stove_recipes.yml
        java.io.File file = new java.io.File(plugin.getDataFolder(), "stove_recipes.yml");
        if (!file.exists()) {
            plugin.saveResource("stove_recipes.yml", false);
        }
        
        org.bukkit.configuration.file.YamlConfiguration config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        if (!config.contains("recipes")) return;
        
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("recipes");
        if (section == null) return;
        
        for (String key : section.getKeys(false)) {
            String inputStr = section.getString(key + ".input");
            String resultStr = section.getString(key + ".result");
            int time = section.getInt(key + ".time", 600);
            double exp = section.getDouble(key + ".experience", 0.0);
            
            if (inputStr == null || resultStr == null) {
                plugin.getLogger().warning("Invalid stove recipe: " + key);
                continue;
            }
            
            // Parse Input using CookingManager (supports Tags, Material, CE/IA ID)
            Ingredient input = plugin.getCookingManager().parseIngredient(key, inputStr);
            if (input == null) {
                 plugin.getLogger().warning("Invalid input in stove recipe: " + key + " (" + inputStr + ")");
                 continue;
            }
            
            // Resolve Result Item
            ItemStack resultItem = resolveItem(resultStr);
            if (resultItem == null) {
                plugin.getLogger().warning("Invalid result item in stove recipe: " + key + " (" + resultStr + ")");
                continue;
            }
            
            recipes.add(new StoveRecipe(input, resultItem, time, (float)exp));
        }
        
        plugin.getLogger().info("Loaded " + recipes.size() + " stove recipes.");
    }
    
    public StoveRecipe getRecipe(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        for (StoveRecipe recipe : recipes) {
            if (recipe.matches(item)) return recipe;
        }
        return null;
    }
    
    private ItemStack resolveItem(String str) {
        // 1. Try Material
        Material mat = Material.matchMaterial(str);
        if (mat != null) return new ItemStack(mat);
        
        // 2. Try Custom Item (ItemsAdder/CraftEngine)
        if (str.contains(":")) {
             // Try ItemsAdder
             if (com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                 ItemStack iaItem = com.example.simplecuisine.util.ItemsAdderHook.getCustomStack(str);
                 if (iaItem != null) return iaItem;
             }
             // Try CraftEngine
             if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                 ItemStack ceItem = com.example.simplecuisine.util.CraftEngineHook.getItem(str);
                 if (ceItem != null) return ceItem;
             }
        }
        return null;
    }

    public void reload() {
        loadRecipes();
    }

    private void startTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 1L, 1L); // Tick every tick for smooth timing
    }

    // Cleanup all visuals at a stove location (based on spatial scan, not UUID)
    public void cleanupAllVisualsAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        
        Location center = loc.clone().add(0.5, 1.2, 0.5); // Approx center of visuals
        // Scan radius 0.8 to cover all slots but avoid neighbors
        for (Entity e : center.getWorld().getNearbyEntities(center, 0.8, 0.5, 0.8)) {
            if (e.getScoreboardTags().contains("simplecuisine_visual")) {
                e.remove();
            }
        }
    }

    private void tick() {
        if (stoves.isEmpty()) return;
        
        // Debug tick rate (once every 100 ticks = 5 seconds)
        if (plugin.getConfig().getBoolean("debug") && Bukkit.getCurrentTick() % 100 == 0) {
            plugin.getLogger().info("[Debug] Ticking " + stoves.size() + " stoves...");
        }

        Iterator<Map.Entry<Location, Stove>> it = stoves.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Location, Stove> entry = it.next();
            Stove stove = entry.getValue();
            Location loc = stove.getLocation();

            // Check if chunk is loaded
            if (!loc.isChunkLoaded()) continue;
            
            // Check if block is still a stove (and lit?)
            Block block = loc.getBlock();
            String id = null;
            
            // 1. Try CraftEngine
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(block);
            }
            
            // 2. Try ItemsAdder
            if (id == null && com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                id = com.example.simplecuisine.util.ItemsAdderHook.getCustomBlockId(block);
            }

            if (id == null || !id.contains("stove")) {
                // Safety: Only remove if block is AIR or clearly not a stove material
                // If ID is null (API fail), we keep it for now to prevent flickering/data loss
                if (block.getType().isAir()) {
                    if (plugin.getConfig().getBoolean("debug")) {
                         plugin.getLogger().info("[Debug] Removing stove at " + loc + " because block is AIR");
                    }
                    removeStove(loc);
                } else if (id != null) {
                    // ID is present but NOT a stove (e.g. replaced by another custom block)
                    if (plugin.getConfig().getBoolean("debug")) {
                         plugin.getLogger().info("[Debug] Stove ID mismatch at " + loc + ". Found: " + id + ". Keeping stove data for safety.");
                    }
                }
                // If id is null but block exists, SKIP ticking this turn, but DO NOT remove.
                continue;
            }
            
            // Check fire property
            boolean isLit = false;
            
            // Method A: Property "fire" (CraftEngine / some IA impls)
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                String fireProp = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, "fire");
                if ("true".equalsIgnoreCase(fireProp)) isLit = true;
            }
            
            // Method B: ID suffix (ItemsAdder often uses _on)
            if (!isLit && (id.contains("stove_on") || id.contains("active") || id.contains("lit"))) {
                isLit = true;
            }
            
            // Method C: Light Level Fallback (If emitting light, it's likely lit)
            if (!isLit && block.getLightFromBlocks() > 0) {
                isLit = true;
            }

            // Tick each slot
            boolean anyCooking = false;
            for (int i = 0; i < stove.getSlotCount(); i++) {
                if (!stove.isSlotEmpty(i)) {
                    // Check if already cooked
                    if (stove.getCookingTime(i) >= stove.getMaxTime(i)) {
                        // Already cooked, just waiting for pickup
                        if (Math.random() < 0.005) { // Reduced frequency to 0.5% (approx every 10s)
                            spawnDoneParticles(loc, i);
                        }
                        continue;
                    }

                    // Cooking (Only if lit)
                    if (isLit) {
                        // Check if stove is covered (occluded)
                        if (!loc.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                            continue; // Skip cooking if covered
                        }

                        stove.incrementCookingTime(i);
                        anyCooking = true;
                        
                        // Particles (Smoke/Sizzle)
                        if (Math.random() < 0.2) {
                            spawnCookingParticles(loc, i);
                        }
                        
                        // Sound (Campfire Crackle) - approx every 3 seconds (5% chance per tick)
                        if (Math.random() < 0.05) {
                            loc.getWorld().playSound(loc, Sound.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 1.0f);
                        }
    
                        // Check if done
                        if (stove.getCookingTime(i) >= stove.getMaxTime(i)) {
                            finishCooking(stove, i);
                        }
                    }
                }
            }
            
            if (!anyCooking && isEmpty(stove)) {
                // Remove stove from memory if completely empty to save resources
                // But we need to cleanup visuals first!
                // Actually, let's keep it simple: cleanup logic handled separately or when block broken.
            }
        }
    }
    
    private boolean isEmpty(Stove stove) {
        for (int i=0; i<stove.getSlotCount(); i++) {
            if (!stove.isSlotEmpty(i)) return false;
        }
        return true;
    }

    private void spawnCookingParticles(Location loc, int slot) {
        double[] offset = SLOT_OFFSETS[slot];
        // FIX: Add 0.5 to center the particles on the block, then apply offset
        Location pLoc = loc.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        loc.getWorld().spawnParticle(Particle.SMOKE, pLoc, 1, 0, 0, 0, 0.02);
    }
    
    private void spawnDoneParticles(Location loc, int slot) {
        double[] offset = SLOT_OFFSETS[slot];
        // FIX: Add 0.5 to center the particles on the block, then apply offset
        Location pLoc = loc.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]);
        loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, pLoc, 1, 0.1, 0.1, 0.1, 0.02);
    }

    private void finishCooking(Stove stove, int slot) {
        ItemStack input = stove.getItem(slot);
        StoveRecipe recipe = getRecipe(input);
        if (recipe != null) {
            ItemStack result = recipe.getResult();
            stove.setItem(slot, result, stove.getMaxTime(slot)); // Keep result in slot
            // Update visual
            updateVisual(stove, slot);
            // Sound
            stove.getLocation().getWorld().playSound(stove.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.0f); // Sizzle sound
        }
    }



    public void handleInteract(Player player, Block block, ItemStack itemInHand) {
        Location loc = block.getLocation();
        
        // Check occlusion
        if (!loc.clone().add(0, 1, 0).getBlock().getType().isAir()) {
             // If covered, prevent interaction (and cooking)
             // Maybe send a message? Or just silent fail like vanilla chest?
             // User said "cannot use".
             return;
        }

        if (plugin.getConfig().getBoolean("debug")) {
            plugin.getLogger().info("[Debug] Handling interact at " + loc + " with " + (itemInHand == null ? "AIR" : itemInHand.getType()));
        }
        Stove stove = stoves.get(loc);
        if (stove == null) {
             if (plugin.getConfig().getBoolean("debug")) plugin.getLogger().info("[Debug] Creating NEW stove instance at " + loc);
             stove = new Stove(loc);
             stoves.put(loc, stove);
        } else {
             if (plugin.getConfig().getBoolean("debug")) plugin.getLogger().info("[Debug] Using EXISTING stove instance at " + loc + " (Slots: " + stove.getSlotCount() + ")");
        }

        // 1. Try to remove item (Sneak + Click or just Click empty slot? or Click occupied slot?)
        // Standard interaction: 
        // - Click with empty hand on occupied slot -> Retrieve
        // - Click with cookable item on empty slot -> Place
        
        // Problem: We interact with the BLOCK, not the specific slot.
        // We need to raytrace or use heuristics to determine which slot.
        // Or simpler: First empty slot for placement. First occupied slot (LIFO) for retrieval?
        // FD Logic: Right click places on next available slot. Right click with empty hand (or valid tool) retrieves ALL? Or retrieves specific?
        // FD usually retrieves the one you look at. Raytracing is hard on server side without precise hit position.
        // Let's implement: 
        // - If holding cookable: Add to next empty slot.
        // - If hand empty: Remove ALL items (or just one?). Campfire pops all. FD pops specific.
        // Let's start with: Remove LAST added item (LIFO) or just remove the first found item.
        // Better: Remove the cooked ones first.
        
        if (itemInHand == null || itemInHand.getType().isAir()) {
            // Retrieve
            // Prioritize cooked items
            for (int i = 0; i < stove.getSlotCount(); i++) {
                if (!stove.isSlotEmpty(i) && stove.getCookingTime(i) >= stove.getMaxTime(i)) {
                    retrieveItem(player, stove, i);
                    return;
                }
            }
            // If no cooked items, retrieve uncooked (LIFO - starting from last slot)
            for (int i = stove.getSlotCount() - 1; i >= 0; i--) {
                if (!stove.isSlotEmpty(i)) {
                    retrieveItem(player, stove, i);
                    return;
                }
            }
        } else {
            // Place Logic
            StoveRecipe recipe = getRecipe(itemInHand);
            if (recipe != null) {
                // Find empty slot
                for (int i = 0; i < stove.getSlotCount(); i++) {
                    if (stove.isSlotEmpty(i)) {
                        // Place it
                        ItemStack toCook = itemInHand.clone();
                        toCook.setAmount(1);
                        stove.setItem(i, toCook, recipe.getTime());
                        
                        // Decrement hand
                        itemInHand.setAmount(itemInHand.getAmount() - 1);
                        player.getInventory().setItemInMainHand(itemInHand);
                        
                        // Visual
                        createVisual(stove, i);
                        
                        // Sound
                        player.playSound(loc, Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
                        return;
                    }
                }
                player.sendActionBar(Component.text("§c灶炉已满！"));
            }
        }
    }
    
    private void retrieveItem(Player player, Stove stove, int slot) {
        if (plugin.getConfig().getBoolean("debug")) {
            plugin.getLogger().info("[Debug] Retrieving item from slot " + slot + " at " + stove.getLocation());
        }
        ItemStack item = stove.getItem(slot);
        if (item != null) {
            // Drop item or give to player
            // FIXED: Use clone() to prevent mutating the stored location!
            stove.getLocation().getWorld().dropItem(stove.getLocation().clone().add(0.5, 1.2, 0.5), item);
            stove.removeItem(slot);
            removeVisual(stove, slot);
            player.playSound(stove.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.0f);
            
            // Debug: Print remaining items to ensure they are not gone
            if (plugin.getConfig().getBoolean("debug")) {
                 int count = 0;
                 for(int i=0; i<stove.getSlotCount(); i++) if(!stove.isSlotEmpty(i)) count++;
                 plugin.getLogger().info("[Debug] Remaining items on stove: " + count);
            }
        }
    }

    private void createVisual(Stove stove, int slot) {
        // Remove existing if any (safety)
        removeVisual(stove, slot);

        Location loc = stove.getLocation();
        double[] offset = SLOT_OFFSETS[slot];
        Location displayLoc = loc.clone().add(0.5 + offset[0], offset[1], 0.5 + offset[2]); // Center + Offset

        // Cleanup duplicate/ghost visuals at this location before spawning
        // Use small radius to avoid hitting neighbors (dist ~0.25)
        for (Entity e : displayLoc.getWorld().getNearbyEntities(displayLoc, 0.15, 0.15, 0.15)) {
            if (e instanceof ItemDisplay && e.getScoreboardTags().contains("simplecuisine_visual")) {
                e.remove();
            }
        }

        // Spawn ItemDisplay
        ItemDisplay display = loc.getWorld().spawn(displayLoc, ItemDisplay.class);
        display.setItemStack(stove.getItem(slot));
        display.addScoreboardTag("simplecuisine_visual"); // Tag for identification
        // Rotate flat on the stove
        display.setTransformation(new Transformation(
            new Vector3f(0, 0, 0),
            new AxisAngle4f((float) (Math.PI / 2), 1, 0, 0), // Rotate 90 deg around X (lie flat)
            new Vector3f(0.25f, 0.25f, 0.25f), // Scale down
            new AxisAngle4f(0, 0, 0, 0)
        ));
        
        stove.setDisplayUuid(slot, display.getUniqueId());
    }
    
    private void updateVisual(Stove stove, int slot) {
        UUID uuid = stove.getDisplayUuid(slot);
        if (uuid != null) {
            Entity e = Bukkit.getEntity(uuid);
            if (e instanceof ItemDisplay) {
                ((ItemDisplay) e).setItemStack(stove.getItem(slot));
            } else {
                // Entity lost? Recreate
                createVisual(stove, slot);
            }
        }
    }

    private void removeVisual(Stove stove, int slot) {
        UUID uuid = stove.getDisplayUuid(slot);
        if (uuid != null) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) {
                e.remove();
            }
            stove.setDisplayUuid(slot, null);
        }
    }
    
    public void printDebugInfo(Location loc, Player player) {
        Stove stove = stoves.get(loc);
        if (stove == null) {
            player.sendMessage(Component.text("§cNo stove data found at this location."));
            return;
        }
        
        player.sendMessage(Component.text("§e--- Stove Data ---"));
        for (int i = 0; i < stove.getSlotCount(); i++) {
            if (stove.isSlotEmpty(i)) {
                player.sendMessage(Component.text("§7Slot " + i + ": Empty"));
            } else {
                ItemStack item = stove.getItem(i);
                StoveRecipe recipe = getRecipe(item);
                
                player.sendMessage(Component.text("§7Slot " + i + ": " + item.getType() + " x" + item.getAmount()));
                player.sendMessage(Component.text("§7  - Time: " + stove.getCookingTime(i) + "/" + stove.getMaxTime(i)));
                player.sendMessage(Component.text("§7  - Has Recipe: " + (recipe != null ? "§aYes" : "§cNo")));
                if (recipe != null) {
                    player.sendMessage(Component.text("§7  - Result: " + recipe.getResult().getType()));
                }
                
                UUID uuid = stove.getDisplayUuid(i);
                player.sendMessage(Component.text("§7  - DisplayUUID: " + (uuid != null ? uuid.toString() : "None")));
                if (uuid != null) {
                    Entity e = Bukkit.getEntity(uuid);
                    player.sendMessage(Component.text("§7    -> Entity Valid: " + (e != null)));
                }
            }
        }
    }

    public void removeStove(Location loc) {
        if (plugin.getConfig().getBoolean("debug")) {
            plugin.getLogger().info("[Debug] removeStove called for " + loc);
            // Stack trace to identify caller
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                if (ste.getClassName().contains("SimpleCuisine")) {
                    plugin.getLogger().info("  at " + ste);
                }
            }
        }
        Stove stove = stoves.remove(loc);
        if (stove != null) {
            // Drop items
            Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);
            for (int i = 0; i < stove.getSlotCount(); i++) {
                if (!stove.isSlotEmpty(i)) {
                    loc.getWorld().dropItem(dropLoc, stove.getItem(i));
                }
                removeVisual(stove, i);
            }
        }
    }

    public void removeAllVisuals() {
        for (Location loc : stoves.keySet()) {
            cleanupAllVisualsAt(loc);
        }
    }
}
