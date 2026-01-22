package com.example.simplecuisine.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public class ItemManager {

    private final JavaPlugin plugin;
    public static final NamespacedKey POT_KEY = new NamespacedKey("farmersdelight", "cooking_pot");
    public static final NamespacedKey CUTTING_BOARD_KEY = new NamespacedKey("farmersdelight", "cutting_board");
    public static final NamespacedKey SKILLET_KEY = new NamespacedKey("farmersdelight", "skillet");

    public ItemManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        // Currently no cached values to clear, but good for future proofing
    }

    public ItemStack getCookingPot() {
        ItemStack item = null;
        String mode = plugin.getConfig().getString("mode", "itemsadder").toLowerCase();

        // 1. Try ItemsAdder (If mode is itemsadder or default/fallback)
        if (mode.equals("itemsadder") || (!mode.equals("vanilla") && !mode.equals("craftengine"))) {
            if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                try {
                    String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
                    String id = plugin.getConfig().getString("itemsadder.cooking_pot_id", "cooking_pot");
                    item = com.example.simplecuisine.util.ItemsAdderHook.getItem(namespace + ":" + id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 2. Try CraftEngine (If mode is craftengine, or if IA failed/skipped and mode is not vanilla)
        if (item == null && (mode.equals("craftengine") || !mode.equals("vanilla"))) {
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                try {
                    String id = plugin.getConfig().getString("craftengine.cooking_pot_id", "farmersdelight:cooking_pot");
                    item = com.example.simplecuisine.util.CraftEngineHook.getItem(id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 3. Fallback to Vanilla / Config defined
        if (item == null) {
            String matName = plugin.getConfig().getString("cooking_pot.item.material", "CAULDRON");
            Material mat = Material.getMaterial(matName);
            if (mat == null) mat = Material.CAULDRON;
            item = new ItemStack(mat);
            
            ItemMeta meta = item.getItemMeta();
            
            String name = plugin.getConfig().getString("cooking_pot.item.name", "§e厨锅");
            meta.displayName(Component.text(name.replace("&", "§")));
            
            java.util.List<String> loreList = plugin.getConfig().getStringList("cooking_pot.item.lore");
            if (loreList.isEmpty()) {
                loreList = java.util.List.of("§7放置在热源上方以开始烹饪！");
            }
            
            java.util.List<Component> loreComponents = new java.util.ArrayList<>();
            for (String line : loreList) {
                loreComponents.add(Component.text(line.replace("&", "§")));
            }
            meta.lore(loreComponents);
            
            int modelData = plugin.getConfig().getInt("cooking_pot.item.custom_model_data", 0);
            if (modelData != 0) {
                meta.setCustomModelData(modelData);
            }
            item.setItemMeta(meta);
        }
        
        // Ensure the NBT tag is always present for identification
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(POT_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCookingPot(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        
        // Check 1: SimpleCuisine NBT (Priority)
        if (item.hasItemMeta()) {
            org.bukkit.persistence.PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            
            // Check Byte (Standard)
            if (pdc.has(POT_KEY, PersistentDataType.BYTE)) {
                return true;
            }
            
            // Check String (Compatibility for CraftEngine/Other Generators)
            if (pdc.has(POT_KEY, PersistentDataType.STRING)) {
                String val = pdc.get(POT_KEY, PersistentDataType.STRING);
                if (val != null && (val.equals("1b") || val.equals("1") || val.equalsIgnoreCase("true"))) {
                    return true;
                }
            }
            
            // Check Integer (Just in case)
            if (pdc.has(POT_KEY, PersistentDataType.INTEGER)) {
                Integer val = pdc.get(POT_KEY, PersistentDataType.INTEGER);
                if (val != null && val == 1) {
                    return true;
                }
            }
        }

        // Check 2: CraftEngine (If enabled)
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String id = com.example.simplecuisine.util.CraftEngineHook.getItemId(item);
            if ("farmersdelight:cooking_pot".equals(id)) {
                return true;
            }
        }

        // Check 3: ItemsAdder CustomStack ID (For items retrieved from Creative Menu etc)
        if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
            String id = plugin.getConfig().getString("itemsadder.cooking_pot_id", "cooking_pot");
            String fullId = namespace + ":" + id;
            
            String itemId = com.example.simplecuisine.util.ItemsAdderHook.getCustomStackId(item);
            if (fullId.equals(itemId)) {
                return true;
            }
        }
        
        return false;
    }

    public ItemStack getCuttingBoard() {
        ItemStack item = null;
        String mode = plugin.getConfig().getString("mode", "itemsadder").toLowerCase();

        // 1. Try ItemsAdder
        if (mode.equals("itemsadder") || (!mode.equals("vanilla") && !mode.equals("craftengine"))) {
            if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                try {
                    String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
                    String id = plugin.getConfig().getString("itemsadder.cutting_board_id", "cutting_board");
                    item = com.example.simplecuisine.util.ItemsAdderHook.getItem(namespace + ":" + id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 2. Try CraftEngine
        if (item == null && (mode.equals("craftengine") || !mode.equals("vanilla"))) {
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                try {
                    String id = plugin.getConfig().getString("craftengine.cutting_board_id", "farmersdelight:cutting_board");
                    item = com.example.simplecuisine.util.CraftEngineHook.getItem(id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 3. Fallback to Vanilla / Config defined
        if (item == null) {
            String matName = plugin.getConfig().getString("cutting_board.item.material", "OAK_PRESSURE_PLATE");
            Material mat = Material.getMaterial(matName);
            if (mat == null) mat = Material.OAK_PRESSURE_PLATE;
            item = new ItemStack(mat);
            
            ItemMeta meta = item.getItemMeta();
            
            String name = plugin.getConfig().getString("cutting_board.item.name", "§e砧板");
            meta.displayName(Component.text(name.replace("&", "§")));
            
            java.util.List<String> loreList = plugin.getConfig().getStringList("cutting_board.item.lore");
            if (loreList.isEmpty()) {
                loreList = java.util.List.of("§7用于处理食材");
            }
            
            java.util.List<Component> loreComponents = new java.util.ArrayList<>();
            for (String line : loreList) {
                loreComponents.add(Component.text(line.replace("&", "§")));
            }
            meta.lore(loreComponents);
            
            int modelData = plugin.getConfig().getInt("cutting_board.item.custom_model_data", 0);
            if (modelData != 0) {
                meta.setCustomModelData(modelData);
            }
            item.setItemMeta(meta);
        }
        
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(CUTTING_BOARD_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCuttingBoard(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(CUTTING_BOARD_KEY, PersistentDataType.BYTE)) {
            return true;
        }

        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String id = com.example.simplecuisine.util.CraftEngineHook.getItemId(item);
            if ("simplecuisine:cutting_board".equals(id)) {
                return true;
            }
        }

        if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
            String id = plugin.getConfig().getString("itemsadder.cutting_board_id", "cutting_board");
            String fullId = namespace + ":" + id;
            
            String itemId = com.example.simplecuisine.util.ItemsAdderHook.getCustomStackId(item);
            if (fullId.equals(itemId)) {
                return true;
            }
        }
        
        return false;
    }

    public ItemStack getSkillet() {
        ItemStack item = null;
        String mode = plugin.getConfig().getString("mode", "itemsadder").toLowerCase();

        // 1. Try ItemsAdder
        if (mode.equals("itemsadder") || (!mode.equals("vanilla") && !mode.equals("craftengine"))) {
            if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
                try {
                    String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
                    String id = plugin.getConfig().getString("itemsadder.skillet_id", "skillet");
                    item = com.example.simplecuisine.util.ItemsAdderHook.getItem(namespace + ":" + id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 2. Try CraftEngine
        if (item == null && (mode.equals("craftengine") || !mode.equals("vanilla"))) {
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                try {
                    String id = plugin.getConfig().getString("craftengine.skillet_id", "simplecuisine:skillet");
                    item = com.example.simplecuisine.util.CraftEngineHook.getItem(id);
                } catch (Throwable ignored) {
                }
            }
        }
        
        // 3. Fallback to Vanilla / Config defined
        if (item == null) {
            String matName = plugin.getConfig().getString("skillet.item.material", "IRON_TRAPDOOR");
            Material mat = Material.getMaterial(matName);
            if (mat == null) mat = Material.IRON_TRAPDOOR;
            item = new ItemStack(mat);
            
            ItemMeta meta = item.getItemMeta();
            
            String name = plugin.getConfig().getString("skillet.item.name", "§e煎锅");
            meta.displayName(Component.text(name.replace("&", "§")));
            
            java.util.List<String> loreList = plugin.getConfig().getStringList("skillet.item.lore");
            if (loreList.isEmpty()) {
                loreList = java.util.List.of("§7放置在热源上方以煎烤食物！");
            }
            
            java.util.List<Component> loreComponents = new java.util.ArrayList<>();
            for (String line : loreList) {
                loreComponents.add(Component.text(line.replace("&", "§")));
            }
            meta.lore(loreComponents);
            
            int modelData = plugin.getConfig().getInt("skillet.item.custom_model_data", 0);
            if (modelData != 0) {
                meta.setCustomModelData(modelData);
            }
            item.setItemMeta(meta);
        }
        
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(SKILLET_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSkillet(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(SKILLET_KEY, PersistentDataType.BYTE)) {
            return true;
        }

        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String id = com.example.simplecuisine.util.CraftEngineHook.getItemId(item);
            if ("farmersdelight:skillet".equals(id)) {
                return true;
            }
        }

        if (plugin.getServer().getPluginManager().getPlugin("ItemsAdder") != null) {
            String namespace = plugin.getConfig().getString("itemsadder.namespace", "simplecuisine");
            String id = plugin.getConfig().getString("itemsadder.skillet_id", "skillet");
            String fullId = namespace + ":" + id;
            
            String itemId = com.example.simplecuisine.util.ItemsAdderHook.getCustomStackId(item);
            if (fullId.equals(itemId)) {
                return true;
            }
        }
        
        return false;
    }
}
