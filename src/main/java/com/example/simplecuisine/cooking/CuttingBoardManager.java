package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CuttingBoardManager {
    private final SimpleCuisine plugin;
    private final Map<Location, CuttingBoard> cuttingBoards = new ConcurrentHashMap<>();
    private final Map<String, Set<Location>> chunkMap = new ConcurrentHashMap<>();
    private final List<CuttingRecipe> recipes = new ArrayList<>();
    private final Set<String> allowedKnives = new LinkedHashSet<>();
    
    // Data structure to hold boards for worlds that are not yet loaded
    private static class PendingBoard {
        String worldName;
        int x, y, z;
        ItemStack item;
        
        PendingBoard(String worldName, int x, int y, int z, ItemStack item) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.item = item;
        }
    }
    private final List<PendingBoard> pendingBoards = Collections.synchronizedList(new ArrayList<>());

    public List<CuttingRecipe> getRecipes() {
        return recipes;
    }

    public Set<String> getAllowedKnives() {
        return allowedKnives;
    }

    public static class CuttingBoard {
        private final Location location;
        private ItemStack item;

        public CuttingBoard(Location location) {
            this.location = location;
        }

        public Location getLocation() { return location; }
        public ItemStack getItem() { return item; }
        public void setItem(ItemStack item) {
            this.item = item;
        }
        public boolean isEmpty() { return item == null || item.getType().isAir(); }
    }

    public class CuttingRecipe {
        private final Ingredient input;
        private final String toolType;
        private final List<ItemStack> results;
        private final String sound;

        public CuttingRecipe(Ingredient input, String toolType, List<ItemStack> results, String sound) {
            this.input = input;
            this.toolType = toolType;
            this.results = results;
            this.sound = sound;
        }

        public boolean matches(ItemStack item, ItemStack tool) {
            if (item == null || tool == null) return false;
            
            // Check Input
            if (!input.matches(item)) return false;

            // Check Tool
            if ("knife".equalsIgnoreCase(toolType)) {
                String id = getCustomId(tool);
                return id != null && allowedKnives.contains(id);
            }
            
            return false;
        }
        
        private String getCustomId(ItemStack item) {
            if (item == null) return null;
            if (com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                String id = com.example.simplecuisine.util.ItemsAdderHook.getCustomItemId(item);
                if (id != null) return id;
            }
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                String id = com.example.simplecuisine.util.CraftEngineHook.getCustomItemId(item);
                if (id != null) return id;
            }
            return null;
        }

        public List<ItemStack> getResults() {
            List<ItemStack> list = new ArrayList<>();
            for (ItemStack stack : results) {
                list.add(stack.clone());
            }
            return list;
        }
        
        public Ingredient getInput() { return input; }
        public String getToolType() { return toolType; }
        
        public String getSound() { return sound; }
    }

    public CuttingBoardManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        loadConfigValues();
        loadBoards();
    }

    private void loadConfigValues() {
        allowedKnives.clear();
        List<String> knives = plugin.getConfig().getStringList("cutting_board.allowed_knives");
        if (knives != null && !knives.isEmpty()) {
            allowedKnives.addAll(knives);
        } else {
            // Default fallback
            allowedKnives.add("farmersdelight:flint_knife");
            allowedKnives.add("farmersdelight:iron_knife");
            allowedKnives.add("farmersdelight:golden_knife");
            allowedKnives.add("farmersdelight:diamond_knife");
            allowedKnives.add("farmersdelight:netherite_knife");
        }
    }

    public void loadRecipes() {
        recipes.clear();
        File file = new File(plugin.getDataFolder(), "cutting_board_recipes.yml");
        if (!file.exists()) {
            plugin.saveResource("cutting_board_recipes.yml", false);
        }
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("recipes");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String inputStr = section.getString(key + ".input");
            String tool = section.getString(key + ".tool");
            String sound = section.getString(key + ".sound", "block.wood.hit");
            
            // Parse Input using CookingManager (supports Tags, Material, CE/IA ID)
            Ingredient input = plugin.getCookingManager().parseIngredient(key, inputStr);
            if (input == null) {
                 plugin.getLogger().warning("Invalid input in cutting board recipe: " + key + " (" + inputStr + ")");
                 continue;
            }

            List<ItemStack> resultList = new ArrayList<>();
            
            // Support single result (legacy)
            if (section.contains(key + ".result")) {
                String resultStr = section.getString(key + ".result");
                int amount = section.getInt(key + ".amount", 1);
                ItemStack item = resolveItem(resultStr, amount);
                if (item != null) resultList.add(item);
            }
            
            // Support multiple results
            if (section.contains(key + ".results")) {
                List<String> results = section.getStringList(key + ".results");
                for (String entry : results) {
                    // Format: "namespace:id:amount" or "namespace:id" (default 1)
                    String[] parts = entry.split(":");
                    int amount = 1;
                    String id = entry;
                    
                    // Try to detect amount at end
                    if (parts.length >= 2) {
                        try {
                            int parsedAmount = Integer.parseInt(parts[parts.length - 1]);
                            // If successful, the ID is everything before
                            amount = parsedAmount;
                            id = entry.substring(0, entry.lastIndexOf(":"));
                        } catch (NumberFormatException e) {
                            // Last part is not number, assume part of ID
                        }
                    }
                    
                    ItemStack item = resolveItem(id, amount);
                    if (item != null) resultList.add(item);
                }
            }
            
            if (!resultList.isEmpty()) {
                recipes.add(new CuttingRecipe(input, tool, resultList, sound));
            } else {
                plugin.getLogger().warning("Cutting board recipe " + key + " has no valid results.");
            }
        }
        plugin.getLogger().info("Loaded " + recipes.size() + " cutting board recipes.");
    }
    
    private ItemStack resolveItem(String id, int amount) {
        // 1. Try ItemsAdder
        if (ItemsAdderHook.isItemsAdderLoaded()) {
            ItemStack iaItem = ItemsAdderHook.getItem(id);
            if (iaItem != null) {
                iaItem.setAmount(amount);
                return iaItem;
            }
        }
        // 2. Try CraftEngine
        if (CraftEngineHook.isEnabled()) {
            ItemStack ceItem = CraftEngineHook.getItem(id);
            if (ceItem != null) {
                ceItem.setAmount(amount);
                return ceItem;
            }
        }
        // 3. Try Vanilla
        Material mat = Material.matchMaterial(id);
        if (mat != null) {
            return new ItemStack(mat, amount);
        }
        return null;
    }
    
    public CuttingRecipe getRecipe(ItemStack input, ItemStack tool) {
        for (CuttingRecipe recipe : recipes) {
            if (recipe.matches(input, tool)) return recipe;
        }
        return null;
    }

    // Chunk Management (Copied & Adapted from SkilletManager)
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

    // Board Management
    public boolean isCuttingBoard(Location loc) {
        return cuttingBoards.containsKey(loc);
    }
    
    public void addBoard(Location loc) {
        if (!cuttingBoards.containsKey(loc)) {
            // Check pending data first
            synchronized (pendingBoards) {
                Iterator<PendingBoard> it = pendingBoards.iterator();
                while (it.hasNext()) {
                    PendingBoard pb = it.next();
                    if (pb.worldName.equals(loc.getWorld().getName()) && 
                        pb.x == loc.getBlockX() && 
                        pb.y == loc.getBlockY() && 
                        pb.z == loc.getBlockZ()) {
                        
                        // Restore data
                        CuttingBoard board = new CuttingBoard(loc);
                        if (pb.item != null) {
                            board.setItem(pb.item);
                        }
                        cuttingBoards.put(loc, board);
                        addToChunkMap(loc);
                        if (loc.isChunkLoaded()) {
                            restoreVisuals(loc);
                        }
                        it.remove();
                        plugin.getLogger().info("Lazy restored cutting board data at " + loc);
                        return; // Done
                    }
                }
            }

            // No pending data found, create new empty
            cuttingBoards.put(loc, new CuttingBoard(loc));
            addToChunkMap(loc);
        }
    }
    
    public ItemStack removeBoard(Location loc) {
        CuttingBoard board = cuttingBoards.remove(loc);
        removeFromChunkMap(loc);
        cleanupVisualsAt(loc);
        return board != null ? board.getItem() : null;
    }

    // Visuals
    public void cleanupVisualsAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        Location center = loc.clone().add(0.5, 0.5, 0.5); 
        for (Entity e : center.getWorld().getNearbyEntities(center, 0.8, 0.8, 0.8)) {
            if (e.getScoreboardTags().contains("simplecuisine_cutting_visual")) {
                e.remove();
            }
        }
    }
    
    public void restoreVisuals(Location loc) {
        CuttingBoard board = cuttingBoards.get(loc);
        if (board == null) return;
        
        cleanupVisualsAt(loc);
        if (!board.isEmpty()) {
            createVisual(board);
        }
    }
    
    private void createVisual(CuttingBoard board) {
        Location loc = board.getLocation();
        if (!loc.isChunkLoaded()) return;
        
        ItemStack itemStack = board.getItem();
        if (itemStack == null || itemStack.getType().isAir()) return;

        // Configurable Visuals
        double offsetY = plugin.getConfig().getDouble("cutting_board.visual.offset_y", 0.07);
        double scaleVal = plugin.getConfig().getDouble("cutting_board.visual.scale", 0.5);
        double blockScaleVal = plugin.getConfig().getDouble("cutting_board.visual.block_scale", 0.25);
        double rotX = plugin.getConfig().getDouble("cutting_board.visual.rotation_x", 90.0);

        Location displayLoc = loc.clone().add(0.5, offsetY, 0.5);
        
        ItemDisplay display = loc.getWorld().spawn(displayLoc, ItemDisplay.class);
        display.setItemStack(itemStack);
        display.addScoreboardTag("simplecuisine_cutting_visual");
        
        Transformation transformation = display.getTransformation();
        
        double finalScale = scaleVal;
        if (itemStack.getType().isBlock()) {
            finalScale = blockScaleVal;
        }
        
        transformation.getScale().set((float)finalScale, (float)finalScale, (float)finalScale);
        transformation.getLeftRotation().set(new AxisAngle4f((float) Math.toRadians(rotX), 1, 0, 0));
        
        display.setTransformation(transformation);
        
        display.setPersistent(true); 
    }
    
    private void updateVisual(CuttingBoard board) {
        cleanupVisualsAt(board.getLocation());
        if (!board.isEmpty()) {
            createVisual(board);
        }
    }

    // Interaction
    public boolean handleInteract(Player player, Block block, ItemStack itemInHand, EquipmentSlot hand) {
        Location loc = block.getLocation();
        CuttingBoard board = cuttingBoards.get(loc);
        if (board == null) {
            addBoard(loc);
            board = cuttingBoards.get(loc);
        }
        
        boolean isHandEmpty = (itemInHand == null || itemInHand.getType().isAir());
        
        if (board.isEmpty()) {
            // Logic: Place Item
            
            // 1. If Main Hand is a Tool, we should NOT place it.
            // AND we should return FALSE so the Offhand event can fire (to place the food).
            if (!isHandEmpty && isTool(itemInHand)) {
                return false; // Yield to Offhand
            }

            // 2. If Main Hand is empty or valid item
            if (!isHandEmpty && itemInHand != null) {
                // Prevent placing Cutting Board on Cutting Board (Issue 1)
                if (plugin.getItemManager().isCuttingBoard(itemInHand)) {
                    return false;
                }
            
                // Place 1 item
                ItemStack toPlace = itemInHand.clone();
                toPlace.setAmount(1);
                
                board.setItem(toPlace);
                itemInHand.setAmount(itemInHand.getAmount() - 1);
                
                updateVisual(board);
                loc.getWorld().playSound(loc, Sound.BLOCK_WOOD_PLACE, 1.0f, 1.0f);
                return true;
            }
            return false;
        } else {
            // Board has item
            // Check if tool
            if (!isHandEmpty) {
                CuttingRecipe recipe = getRecipe(board.getItem(), itemInHand);
                if (recipe != null) {
                    // Cut!
                    processCutting(player, board, recipe, itemInHand);
                    return true;
                } else {
                    // Not a valid tool/recipe
                    if (isTool(itemInHand)) {
                         player.sendActionBar(net.kyori.adventure.text.Component.text("这东西可不能切啊...", net.kyori.adventure.text.format.NamedTextColor.RED));
                    }
                    // If sneaking and empty hand (not possible here since !isHandEmpty), retrieve
                    return false;
                }
            } else {
                // Empty hand -> Retrieve
                if (hand == EquipmentSlot.HAND) {
                    retrieveItem(player, board);
                    return true;
                }
                return false;
            }
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

    private void processCutting(Player player, CuttingBoard board, CuttingRecipe recipe, ItemStack tool) {
        Location loc = board.getLocation();
        
        // Effects
        loc.getWorld().playSound(loc, recipe.getSound(), 1.0f, 1.0f);
        loc.getWorld().spawnParticle(org.bukkit.Particle.ITEM, loc.clone().add(0.5, 0.2, 0.5), 5, 0.1, 0.1, 0.1, 0.05, board.getItem());
        
        // Result
        List<ItemStack> results = recipe.getResults();
        if (results != null && !results.isEmpty()) {
            for (ItemStack result : results) {
                // Drop result naturally
                loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), result);
            }
            
            // Clear board
            board.setItem(null);
            updateVisual(board);
            
            // Damage Tool
            // Issue 1: Durability Logic with Unbreaking Support
            if (tool.getType().getMaxDurability() > 0) {
                int level = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
                // Chance to ignore damage: 100/(level+1) % -> Chance to take damage: 1/(level+1)
                if (java.util.concurrent.ThreadLocalRandom.current().nextInt(level + 1) == 0) {
                    org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) tool.getItemMeta();
                    if (meta != null) {
                        int newDamage = meta.getDamage() + 1;
                        meta.setDamage(newDamage);
                        tool.setItemMeta(meta);
                        
                        if (newDamage >= tool.getType().getMaxDurability()) {
                            tool.setAmount(0);
                            loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                        }
                    }
                }
            }
        }
    }
    
    private void retrieveItem(Player player, CuttingBoard board) {
        Location loc = board.getLocation();
        ItemStack item = board.getItem();
        if (item == null) return;
        
        board.setItem(null);
        updateVisual(board);
        
        loc.getWorld().dropItem(loc.clone().add(0.5, 0.5, 0.5), item);
        loc.getWorld().playSound(loc, Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    // Persistence
    public void saveBoards() {
        File file = new File(plugin.getDataFolder(), "cutting_boards.yml");
        YamlConfiguration config = new YamlConfiguration();
        
        int count = 0;
        
        // Save active boards
        for (CuttingBoard board : cuttingBoards.values()) {
            // CRITICAL: Do NOT skip empty boards! 
            // if (board.isEmpty()) continue; 
            
            String key = "board_" + count++;
            Location loc = board.getLocation();
            String locStr = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            
            config.set(key + ".location", locStr);
            config.set(key + ".item", board.getItem());
        }
        
        // Save pending boards (worlds not loaded)
        synchronized (pendingBoards) {
            for (PendingBoard pb : pendingBoards) {
                String key = "board_" + count++;
                String locStr = pb.worldName + "," + pb.x + "," + pb.y + "," + pb.z;
                
                config.set(key + ".location", locStr);
                config.set(key + ".item", pb.item);
            }
        }
        
        try {
            config.save(file);
            plugin.getLogger().info("Saved " + count + " cutting boards (Active: " + cuttingBoards.size() + ", Pending: " + pendingBoards.size() + ").");
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save cutting_boards.yml: " + e.getMessage());
        }
    }

    public void loadBoards() {
        File file = new File(plugin.getDataFolder(), "cutting_boards.yml");
        if (!file.exists()) return;
        
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        cuttingBoards.clear();
        pendingBoards.clear();
        
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
            
            org.bukkit.World world = Bukkit.getWorld(worldName);
            
            if (world == null) {
                // World not loaded, save to pending
                pendingBoards.add(new PendingBoard(worldName, x, y, z, item));
                continue;
            }
            
            Location loc = new Location(world, x, y, z);
            CuttingBoard board = new CuttingBoard(loc);
            
            board.setItem(item);
            
            cuttingBoards.put(loc, board);
            addToChunkMap(loc);
            
            if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                restoreVisuals(loc);
            }
        }
        plugin.getLogger().info("Loaded " + cuttingBoards.size() + " cutting boards (and " + pendingBoards.size() + " pending).");
    }

    public void tryLoadPending(org.bukkit.World world) {
        if (world == null) return;
        String name = world.getName();
        int loadedCount = 0;
        
        synchronized (pendingBoards) {
            Iterator<PendingBoard> it = pendingBoards.iterator();
            while (it.hasNext()) {
                PendingBoard pb = it.next();
                if (pb.worldName.equals(name)) {
                    Location loc = new Location(world, pb.x, pb.y, pb.z);
                    CuttingBoard board = new CuttingBoard(loc);
                    board.setItem(pb.item);
                    
                    cuttingBoards.put(loc, board);
                    addToChunkMap(loc);
                    
                    // If chunk loaded, visuals will be handled by chunk load event or manual check
                    if (loc.isChunkLoaded()) {
                        restoreVisuals(loc);
                    }
                    
                    it.remove();
                    loadedCount++;
                }
            }
        }
        
        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending cutting boards for world: " + name);
        }
    }
    
    public void removeAllVisuals() {
        for (Location loc : cuttingBoards.keySet()) {
            cleanupVisualsAt(loc);
        }
    }
}