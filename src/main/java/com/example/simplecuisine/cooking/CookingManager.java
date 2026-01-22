package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CookingManager {

    private final SimpleCuisine plugin;
    private final List<Recipe> recipes = new ArrayList<>();
    private final Map<String, Ingredient> itemMappings = new HashMap<>();
    private File recipesFile;
    private FileConfiguration recipesConfig;
    private GuiConfig guiConfig;

    public CookingManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        createRecipesConfig();
        loadGuiConfig();
        loadMappings();
    }
    
    public void reload() {
        plugin.reloadConfig();
        createRecipesConfig();
        loadGuiConfig();
        loadMappings();
        loadRecipes();
    }

    private void createRecipesConfig() {
        recipesFile = new File(plugin.getDataFolder(), "pot_recipes.yml");
        if (!recipesFile.exists()) {
            recipesFile.getParentFile().mkdirs();
            plugin.saveResource("pot_recipes.yml", false);
        }
        recipesConfig = YamlConfiguration.loadConfiguration(recipesFile);
    }

    private void loadMappings() {
        itemMappings.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("mappings");
        if (section == null) return;
        
        for (String key : section.getKeys(false)) {
            String matName = section.getString(key + ".material");
            int cmd = section.getInt(key + ".custom_model_data", 0);
            String ceId = section.getString(key + ".craft_engine_id");
            
            Material mat = null;
            if (matName != null) {
                mat = Material.getMaterial(matName);
            }
            
            if (mat != null || ceId != null) {
                if (mat == null) mat = Material.AIR; // Fallback to AIR if only CE ID is provided
                itemMappings.put(key, new Ingredient(key, mat, cmd, ceId));
            } else {
                plugin.getLogger().warning("Invalid mapping " + key + ": must have at least material or craft_engine_id");
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void loadRecipes() {
        recipes.clear();
        plugin.debug("Starting recipe loading...");
        
        ConfigurationSection section = recipesConfig.getConfigurationSection("recipes");
        if (section == null) {
            plugin.getLogger().warning("No 'recipes' section found in recipes.yml!");
            return;
        }

        for (String key : section.getKeys(false)) {
            plugin.debug("Loading recipe: " + key);
            ConfigurationSection recipeSection = section.getConfigurationSection(key);
            if (recipeSection == null) {
                plugin.getLogger().warning("Recipe " + key + " is not a valid section.");
                continue;
            }

            String resultStr = recipeSection.getString("result");
            Material resultMaterial = null;
            int customModelData = recipeSection.getInt("custom_model_data", 0);
            
            // Check mapping for result
            String craftEngineId = null;
            if (resultStr != null && itemMappings.containsKey(resultStr)) {
                Ingredient mapped = itemMappings.get(resultStr);
                resultMaterial = mapped.getMaterial();
                if (customModelData == 0) {
                    customModelData = mapped.getCustomModelData();
                }
                craftEngineId = mapped.getCraftEngineId();
            } else if (resultStr != null) {
                resultMaterial = Material.matchMaterial(resultStr);
                // Allow CE ID even if hook disabled initially
                if (resultMaterial == null && (resultStr.contains(":") || CraftEngineHook.isEnabled())) {
                    // Direct CraftEngine ID usage for result
                    craftEngineId = resultStr;
                    resultMaterial = Material.AIR; // Placeholder
                }
            }

            if (resultMaterial == null && craftEngineId == null) {
                plugin.getLogger().warning("Invalid result for recipe " + key + ": " + resultStr);
                continue;
            }
            
            // Safety fallback for resultMaterial if only CE ID exists
            if (resultMaterial == null) resultMaterial = Material.AIR;

            int time = recipeSection.getInt("time", 200); // ticks
            float experience = (float) recipeSection.getDouble("experience", 0.0);
            
            // Food properties
            Integer nutrition = null;
            if (recipeSection.contains("nutrition")) {
                nutrition = recipeSection.getInt("nutrition");
            }
            
            Float saturation = null;
            if (recipeSection.contains("saturation")) {
                saturation = (float) recipeSection.getDouble("saturation");
            }
            
            String customDisplayName = recipeSection.getString("display_name");
            boolean returnContainer = recipeSection.getBoolean("return_container", true);

            List<Ingredient> containers = new ArrayList<>();
            if (recipeSection.isList("container")) {
                List<String> containerKeys = recipeSection.getStringList("container");
                for (String cKey : containerKeys) {
                    Ingredient ing = parseIngredient(key, cKey);
                    if (ing != null) containers.add(ing);
                }
            } else if (recipeSection.isString("container")) {
                String cKey = recipeSection.getString("container");
                if (cKey != null) {
                    Ingredient ing = parseIngredient(key, cKey);
                    if (ing != null) containers.add(ing);
                }
            }

            // Parse ingredients
            // Use LinkedHashMap to preserve order of definition in YAML
            Map<Ingredient, Integer> ingredients = new LinkedHashMap<>();
            ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
            
            // Try raw map if ConfigurationSection fails (common with colon keys)
            Map<String, Object> rawIngredients = null;
            if (ingredientsSection == null) {
                // Try to get as map directly
                try {
                   Object raw = recipeSection.get("ingredients");
                   if (raw instanceof Map) {
                       rawIngredients = (Map<String, Object>) raw;
                   } else if (raw instanceof ConfigurationSection) {
                       ingredientsSection = (ConfigurationSection) raw;
                   }
                } catch (Exception e) {
                   plugin.getLogger().warning("Failed to parse ingredients for " + key + ": " + e.getMessage());
                }
            }
            
            if (ingredientsSection != null) {
                for (String ingKey : ingredientsSection.getKeys(false)) {
                    if (ingredientsSection.isConfigurationSection(ingKey)) {
                         // Handle nested keys (e.g. unquoted "default:cap: 1" parsing as "default" -> "cap" -> 1)
                         ConfigurationSection sub = ingredientsSection.getConfigurationSection(ingKey);
                         for (String subKey : sub.getKeys(false)) {
                             String fullKey = ingKey + ":" + subKey;
                             int amount = sub.getInt(subKey);
                             parseAndAddIngredient(key, fullKey, amount, ingredients);
                         }
                    } else if (ingredientsSection.isInt(ingKey)) {
                        int amount = ingredientsSection.getInt(ingKey);
                        parseAndAddIngredient(key, ingKey, amount, ingredients);
                    } else {
                        // Handle case where value is a string like "cap: 1" (unlikely but possible)
                        // Or just log warning
                        Object val = ingredientsSection.get(ingKey);
                        plugin.getLogger().warning("Ingredient " + ingKey + " in " + key + " has unexpected type: " + (val == null ? "null" : val.getClass().getName()) + ". Value: " + val);
                        // Try to interpret as int anyway
                        int amount = ingredientsSection.getInt(ingKey, 1);
                        parseAndAddIngredient(key, ingKey, amount, ingredients);
                    }
                }
            } else if (rawIngredients != null) {
                 for (Map.Entry<String, Object> entry : rawIngredients.entrySet()) {
                     String ingKey = entry.getKey();
                     Object val = entry.getValue();
                     int amount = 1;
                     if (val instanceof Number) {
                         amount = ((Number) val).intValue();
                     }
                     parseAndAddIngredient(key, ingKey, amount, ingredients);
                 }
            } else {
                plugin.getLogger().warning("No ingredients found for recipe " + key);
            }
            
            if (ingredients.isEmpty()) {
                plugin.getLogger().warning("Recipe " + key + " has no valid ingredients loaded. Skipping to prevent infinite crafting.");
                continue;
            }

            recipes.add(new Recipe(key, resultMaterial, ingredients, containers, time, customModelData, experience, craftEngineId, nutrition, saturation, customDisplayName, returnContainer));
            plugin.debug("Successfully loaded recipe: " + key);
        }
        plugin.debug("Loaded " + recipes.size() + " recipes.");
    }
    
    public Ingredient parseIngredient(String recipeKey, String ingKey) {
        if (itemMappings.containsKey(ingKey)) {
            return itemMappings.get(ingKey);
        } else if (ingKey.startsWith("#")) {
            // Tag support
            try {
                String tagName = ingKey.substring(1);
                List<ItemStack> cachedItems = new ArrayList<>();
                org.bukkit.Tag<Material> tag = null;
                boolean isBlockTag = false;

                // 1. Try Bukkit Tag
                org.bukkit.NamespacedKey key;
                if (tagName.contains(":")) {
                    key = org.bukkit.NamespacedKey.fromString(tagName);
                } else {
                    key = org.bukkit.NamespacedKey.minecraft(tagName);
                }
                
                if (key != null) {
                    tag = org.bukkit.Bukkit.getTag(org.bukkit.Tag.REGISTRY_ITEMS, key, Material.class);
                    // Fallback to blocks registry if items registry fails
                    if (tag == null) {
                        tag = org.bukkit.Bukkit.getTag(org.bukkit.Tag.REGISTRY_BLOCKS, key, Material.class);
                        if (tag != null) isBlockTag = true;
                    }
                    
                    if (tag != null) {
                        try {
                            int size = tag.getValues().size();
                            plugin.getLogger().info("Recipe " + recipeKey + ": Loaded vanilla tag " + ingKey + " from " + (isBlockTag ? "BLOCKS" : "ITEMS") + " registry. Contains " + size + " materials.");
                            // Add vanilla items to cache
                            for (Material m : tag.getValues()) {
                                cachedItems.add(new ItemStack(m));
                            }
                        } catch (Exception e) {
                             plugin.getLogger().info("Recipe " + recipeKey + ": Loaded vanilla tag " + ingKey + " from " + (isBlockTag ? "BLOCKS" : "ITEMS") + " registry. (Values check skipped)");
                        }
                    }
                }

                // 2. Try CraftEngine Tag
                if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                    List<ItemStack> ceItems = com.example.simplecuisine.util.CraftEngineHook.getTagCustomItemsNoPlayer(tagName);
                    if (!ceItems.isEmpty()) {
                        plugin.getLogger().info("Recipe " + recipeKey + ": Loaded CraftEngine tag " + ingKey + ". Contains " + ceItems.size() + " items.");
                        // Deduplicate items
                        for (ItemStack ceItem : ceItems) {
                            boolean exists = false;
                            for (ItemStack existing : cachedItems) {
                                if (existing.isSimilar(ceItem)) {
                                    exists = true;
                                    break;
                                }
                                // Strict check for vanilla items without meta
                                if (!existing.hasItemMeta() && !ceItem.hasItemMeta() && existing.getType() == ceItem.getType()) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                cachedItems.add(ceItem);
                            }
                        }
                    }
                }
                
                // 3. Try ItemsAdder Tag (if supported later)
                if (com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
                    // Placeholder for future IA tag support
                }

                if (tag != null || !cachedItems.isEmpty()) {
                    return new Ingredient(ingKey, null, 0, null, tag, tagName, cachedItems);
                } else {
                    plugin.getLogger().warning("Invalid tag " + ingKey + " in recipe " + recipeKey + ". Checked Vanilla, CraftEngine and ItemsAdder.");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error parsing tag " + ingKey + " in recipe " + recipeKey + ": " + e.getMessage());
            }
        } else {
            // Try explicit material name first
            Material mat = Material.getMaterial(ingKey);
            
            // If not found, try upper case (users often use lowercase in config)
            if (mat == null) {
                mat = Material.getMaterial(ingKey.toUpperCase());
            }

            // If still not found, try stripping minecraft: prefix
            if (mat == null && ingKey.toLowerCase().startsWith("minecraft:")) {
                String stripped = ingKey.substring(10);
                mat = Material.getMaterial(stripped.toUpperCase());
            }

            if (mat != null) {
                return new Ingredient(ingKey, mat, 0);
            } else {
                 // Force CraftEngine ID treatment even if hook says disabled (it might enable later)
                 // or if it doesn't contain colon (some IDs might not?)
                 // But usually they do.
                 // Let's be lenient: If it's not a material, treat as potential CE ID
                 if (ingKey.contains(":")) {
                     return new Ingredient(ingKey, Material.AIR, 0, ingKey);
                 } else {
                     plugin.getLogger().warning("Invalid ingredient " + ingKey + " in recipe " + recipeKey + ". Not a material and not a valid CE ID pattern.");
                 }
            }
        }
        return null;
    }

    private void parseAndAddIngredient(String recipeKey, String ingKey, int amount, Map<Ingredient, Integer> ingredients) {
        Ingredient ing = parseIngredient(recipeKey, ingKey);
        if (ing != null) {
            ingredients.put(ing, amount);
        }
    }

    public ItemStack getGuiAsset(String key, Material defaultMat, String defaultNameKey) {
        String path = "gui_assets." + key;
        FileConfiguration config = plugin.getConfig();
        if (config.contains(path)) {
            String matName = config.getString(path + ".material", defaultMat.name());
            Material mat = Material.matchMaterial(matName);
            if (mat == null) mat = defaultMat;
            
            int cmd = config.getInt(path + ".custom_model_data", 0);
            String name = config.getString(path + ".name");
            
            ItemStack item = new ItemStack(mat);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (name != null) {
                    if (name.contains("&")) {
                        meta.displayName(net.kyori.adventure.text.Component.text(name.replace("&", "§")));
                    } else {
                        meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(name));
                    }
                } else {
                    meta.displayName(com.example.simplecuisine.config.ConfigManager.getGuiText(defaultNameKey));
                }
                
                if (cmd != 0) {
                    meta.setCustomModelData(cmd);
                }
                item.setItemMeta(meta);
            }
            return item;
        }
        
        // Fallback
        ItemStack item = new ItemStack(defaultMat);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.displayName(com.example.simplecuisine.config.ConfigManager.getGuiText(defaultNameKey));
        item.setItemMeta(meta);
        return item;
    }

    public List<Recipe> getRecipes() {
        return recipes;
    }

    public Recipe matchRecipe(List<ItemStack> inputs) {
        for (Recipe recipe : recipes) {
            if (recipe.matches(inputs)) {
                return recipe;
            }
        }
        return null;
    }

    public Recipe getRecipeById(String id) {
        for (Recipe recipe : recipes) {
            if (recipe.getId().equalsIgnoreCase(id)) {
                return recipe;
            }
        }
        return null;
    }

    public Recipe getRecipeByResult(ItemStack resultItem) {
        if (resultItem == null) return null;

        // Try CraftEngine check first
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String ceId = com.example.simplecuisine.util.CraftEngineHook.getItemId(resultItem);
            if (ceId != null) {
                for (Recipe recipe : recipes) {
                    if (ceId.equals(recipe.getCraftEngineId())) {
                        return recipe;
                    }
                }
            }
        }

        for (Recipe recipe : recipes) {
            if (recipe.getResult() == resultItem.getType()) {
                if (recipe.getCustomModelData() == 0) return recipe;
                if (resultItem.hasItemMeta() && resultItem.getItemMeta().hasCustomModelData() 
                        && resultItem.getItemMeta().getCustomModelData() == recipe.getCustomModelData()) {
                    return recipe;
                }
            }
        }
        return null;
    }

    public boolean isHeatSource(org.bukkit.block.Block block) {
        if (block == null) return false;
        
        // Check for ItemsAdder stove
        if (com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded()) {
            String iaId = com.example.simplecuisine.util.ItemsAdderHook.getCustomBlockId(block);
            if (iaId != null && guiConfig.heatSourceItemsAdder != null) {
                if (guiConfig.heatSourceItemsAdder.contains(iaId)) {
                    return true;
                }
            }
        }

        // Check for CraftEngine stove
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String ceId = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(block);
            if (ceId != null) {
                if (guiConfig.heatSourceCraftEngine != null) {
                    for (String configEntry : guiConfig.heatSourceCraftEngine) {
                        // Format: namespace:id or namespace:id[property=value]
                        String targetId = configEntry;
                        String targetProp = null;
                        String targetVal = null;
                        
                        if (configEntry.contains("[") && configEntry.contains("]")) {
                            int start = configEntry.indexOf("[");
                            int end = configEntry.indexOf("]");
                            targetId = configEntry.substring(0, start);
                            String propPart = configEntry.substring(start + 1, end); // property=value
                            String[] parts = propPart.split("=");
                            if (parts.length == 2) {
                                targetProp = parts[0];
                                targetVal = parts[1];
                            }
                        }
                        
                        if (ceId.equals(targetId)) {
                            if (targetProp != null) {
                                String actualVal = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, targetProp);
                                if (actualVal != null && actualVal.equalsIgnoreCase(targetVal)) {
                                    return true;
                                }
                            } else {
                                // No property requirement, just ID match is enough
                                return true;
                            }
                        }
                    }
                }
            }
        }

        // Check Vanilla heat sources
        if (guiConfig.heatSourceVanilla != null && guiConfig.heatSourceVanilla.contains(block.getType())) {
            if (block.getBlockData() instanceof org.bukkit.block.data.Lightable) {
                return ((org.bukkit.block.data.Lightable) block.getBlockData()).isLit();
            }
            return true;
        }
        
        return false;
    }

    public ItemStack getItemWithFallback(String id) {
        if (id == null) return null;
        ItemStack item = null;

        // 1. Try preferred mode
        if ("itemsadder".equalsIgnoreCase(guiConfig.mode)) {
            if (plugin.getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
                item = com.example.simplecuisine.util.ItemsAdderHook.getItem(id);
            }
        } else if ("craftengine".equalsIgnoreCase(guiConfig.mode)) {
            if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                item = com.example.simplecuisine.util.CraftEngineHook.getItem(id);
            }
        }

        // 2. Fallback if not found
        if (item == null || item.getType().isAir()) {
            // Try ItemsAdder if not tried or failed
            if (!"itemsadder".equalsIgnoreCase(guiConfig.mode) && plugin.getServer().getPluginManager().isPluginEnabled("ItemsAdder")) {
                ItemStack iaItem = com.example.simplecuisine.util.ItemsAdderHook.getItem(id);
                if (iaItem != null && !iaItem.getType().isAir()) {
                    item = iaItem;
                }
            }
            
            // Try CraftEngine if not tried or failed (and still null)
            if ((item == null || item.getType().isAir()) && !"craftengine".equalsIgnoreCase(guiConfig.mode) && com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
                ItemStack ceItem = com.example.simplecuisine.util.CraftEngineHook.getItem(id);
                if (ceItem != null && !ceItem.getType().isAir()) {
                    item = ceItem;
                }
            }
        }

        return item;
    }

    private void loadGuiConfig() {
        guiConfig = new GuiConfig();
        FileConfiguration config = plugin.getConfig();
        
        guiConfig.mode = config.getString("mode", "itemsadder");
        guiConfig.iaNamespace = config.getString("itemsadder.namespace", "simplecuisine");
        guiConfig.iaCookingPotId = config.getString("itemsadder.cooking_pot_id", "cooking_pot");
        
        guiConfig.title = config.getString("cooking_pot.gui.title", "烹饪锅");
        
        guiConfig.layoutItemsAdderOffset = config.getString("cooking_pot.gui.layout.itemsadder.offset", "");
        guiConfig.layoutItemsAdderIcon = config.getString("cooking_pot.gui.layout.itemsadder.icon", "");
        
        guiConfig.layoutCraftEngineOffset = config.getString("cooking_pot.gui.layout.craftengine.offset", "");
        guiConfig.layoutCraftEngineIcon = config.getString("cooking_pot.gui.layout.craftengine.icon", "");
        
        guiConfig.layoutVanillaOffset = config.getString("cooking_pot.gui.layout.vanilla.offset", "");
        guiConfig.layoutVanillaIcon = config.getString("cooking_pot.gui.layout.vanilla.icon", "");
        
        guiConfig.useFillers = config.getBoolean("cooking_pot.gui.use_fillers", false);
        guiConfig.fillerItem = config.getString("cooking_pot.gui.filler.item");
        String fillerMat = config.getString("cooking_pot.gui.filler.material", "LIGHT_GRAY_STAINED_GLASS_PANE");
        guiConfig.fillerMaterial = Material.getMaterial(fillerMat);
        if (guiConfig.fillerMaterial == null) guiConfig.fillerMaterial = Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        guiConfig.fillerModelData = config.getInt("cooking_pot.gui.filler.model_data", 0);
        
        guiConfig.progressBarEnabled = config.getBoolean("cooking_pot.gui.progress_bar.enabled", false);
        guiConfig.progressBarSlot = config.getInt("cooking_pot.gui.progress_bar.slot", 22);
        guiConfig.progressBarItems = config.getStringList("cooking_pot.gui.progress_bar.items");
        
        // Fallback vanilla settings
        String progMat = config.getString("cooking_pot.gui.progress_bar.material", "PAPER");
        guiConfig.progressBarMaterial = Material.getMaterial(progMat);
        if (guiConfig.progressBarMaterial == null) guiConfig.progressBarMaterial = Material.PAPER;
        guiConfig.progressBarModels = config.getIntegerList("cooking_pot.gui.progress_bar.models");
        
        guiConfig.heatIndicatorEnabled = config.getBoolean("cooking_pot.gui.heat_indicator.enabled", false);
        guiConfig.heatIndicatorSlot = config.getInt("cooking_pot.gui.heat_indicator.slot", 20);
        guiConfig.heatIndicatorItemOn = config.getString("cooking_pot.gui.heat_indicator.item_on");
        guiConfig.heatIndicatorItemOff = config.getString("cooking_pot.gui.heat_indicator.item_off");
        
        // Fallback vanilla settings
        String heatMat = config.getString("cooking_pot.gui.heat_indicator.material", "PAPER");
        guiConfig.heatIndicatorMaterial = Material.getMaterial(heatMat);
        if (guiConfig.heatIndicatorMaterial == null) guiConfig.heatIndicatorMaterial = Material.PAPER;
        guiConfig.heatIndicatorModelOn = config.getInt("cooking_pot.gui.heat_indicator.model_on", 0);
        guiConfig.heatIndicatorModelOff = config.getInt("cooking_pot.gui.heat_indicator.model_off", 0);

        // Load Heat Sources
        guiConfig.heatSourceVanilla = new ArrayList<>();
        List<String> vanillaSources = config.getStringList("cooking_pot.heat_sources.vanilla");
        for (String s : vanillaSources) {
            Material mat = Material.getMaterial(s);
            if (mat != null) guiConfig.heatSourceVanilla.add(mat);
        }
        
        guiConfig.heatSourceItemsAdder = config.getStringList("cooking_pot.heat_sources.itemsadder");
        guiConfig.heatSourceCraftEngine = config.getStringList("cooking_pot.heat_sources.craftengine");
    }

    public GuiConfig getGuiConfig() {
        return guiConfig;
    }

    public static class GuiConfig {
        public String mode;
        public String iaNamespace;
        public String iaCookingPotId;
    
        public String title;
        public String titleItemsAdder;
        public String titleCraftEngine;
        
        public String layoutItemsAdderOffset;
        public String layoutItemsAdderIcon;
        public String layoutCraftEngineOffset;
        public String layoutCraftEngineIcon;
        public String layoutVanillaOffset;
        public String layoutVanillaIcon;
        
        public boolean useFillers;
        public String fillerItem;
        public Material fillerMaterial;
        public int fillerModelData;

        public boolean progressBarEnabled;
        public int progressBarSlot;
        public List<String> progressBarItems;
        public Material progressBarMaterial;
        public List<Integer> progressBarModels;

        public boolean heatIndicatorEnabled;
        public int heatIndicatorSlot;
        public String heatIndicatorItemOn;
        public String heatIndicatorItemOff;
        public Material heatIndicatorMaterial;
        public int heatIndicatorModelOn;
        public int heatIndicatorModelOff;

        public List<Material> heatSourceVanilla;
        public List<String> heatSourceItemsAdder;
        public List<String> heatSourceCraftEngine;
    }
}
