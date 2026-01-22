package com.example.simplecuisine.cooking;

import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Recipe {
    private final String id;
    private final Material result;
    private final Map<Ingredient, Integer> ingredients;
    private final List<Ingredient> containers;
    private final int time;
    private final int customModelData;
    private final float experience;
    private final String craftEngineId;
    private final Integer nutrition;
    private final Float saturation;
    private final String customDisplayName;
    private final boolean returnContainer;

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData, float experience, String craftEngineId, Integer nutrition, Float saturation, String customDisplayName, boolean returnContainer) {
        this.id = id;
        this.result = result;
        this.ingredients = ingredients;
        this.containers = containers;
        this.time = time;
        this.customModelData = customModelData;
        this.experience = experience;
        this.craftEngineId = craftEngineId;
        this.nutrition = nutrition;
        this.saturation = saturation;
        this.customDisplayName = customDisplayName;
        this.returnContainer = returnContainer;
    }

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData, float experience, String craftEngineId, Integer nutrition, Float saturation, String customDisplayName) {
        this(id, result, ingredients, containers, time, customModelData, experience, craftEngineId, nutrition, saturation, customDisplayName, true);
    }

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData, float experience, String craftEngineId, Integer nutrition, Float saturation) {
        this(id, result, ingredients, containers, time, customModelData, experience, craftEngineId, nutrition, saturation, null);
    }

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData, float experience, String craftEngineId) {
        this(id, result, ingredients, containers, time, customModelData, experience, craftEngineId, null, null, null);
    }


    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData, float experience) {
        this(id, result, ingredients, containers, time, customModelData, experience, null);
    }

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time, int customModelData) {
        this(id, result, ingredients, containers, time, customModelData, 0.0f, null);
    }

    public Recipe(String id, Material result, Map<Ingredient, Integer> ingredients, List<Ingredient> containers, int time) {
        this(id, result, ingredients, containers, time, 0, 0.0f, null);
    }

    public String getId() {
        return id;
    }

    public Material getResult() {
        return result;
    }

    public Map<Ingredient, Integer> getIngredients() {
        return ingredients;
    }

    public List<Ingredient> getContainers() {
        return containers;
    }
    
    public boolean isContainer(ItemStack item) {
        if (containers == null || containers.isEmpty()) return false;
        for (Ingredient ing : containers) {
            if (ing.matches(item)) return true;
        }
        return false;
    }

    public int getTime() {
        return time;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public float getExperience() {
        return experience;
    }

    public String getCraftEngineId() {
        return craftEngineId;
    }

    public Integer getNutrition() {
        return nutrition;
    }

    public Float getSaturation() {
        return saturation;
    }

    // Removed cachedDisplayName to ensure dynamic updates (e.g. if CE loads late)
    // private Component cachedDisplayName;

    public Component getDisplayName() {
        // Use custom display name if set in config
        if (customDisplayName != null) {
             // Support legacy color codes in config
             String legacy = customDisplayName.replace("&", "§");
             return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(legacy);
        }

        // Always regenerate to handle late-loading resources or item updates
        if (craftEngineId != null) {
            ItemStack item = createResult();
            if (item != null && item.getType() != Material.AIR) {
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    return item.getItemMeta().displayName();
                }
            }
            
            // Fallback for CE items without server-side display name
            // Format ID: "namespace:foo_bar" -> "Foo Bar"
            String name = craftEngineId;
            if (name.contains(":")) {
                name = name.split(":")[1];
            }
            name = name.replace("_", " ");
            
            // Capitalize words
            StringBuilder sb = new StringBuilder();
            for (String word : name.split(" ")) {
                if (!word.isEmpty()) {
                    sb.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase())
                      .append(" ");
                }
            }
            return Component.text(sb.toString().trim());
        } else {
            return Component.translatable(result);
        }
    }

    public boolean isReturnContainer() {
        return returnContainer;
    }

    public ItemStack createResult() {
        ItemStack item = null;
        
        // Priority: CraftEngine / ItemsAdder ID
        if (craftEngineId != null) {
            if (CraftEngineHook.isEnabled()) {
                item = CraftEngineHook.getItem(craftEngineId);
            }
            if ((item == null || item.getType() == Material.AIR) && ItemsAdderHook.isItemsAdderLoaded()) {
                item = ItemsAdderHook.getItem(craftEngineId);
            }

            if (item == null || item.getType() == Material.AIR) {
                com.example.simplecuisine.SimpleCuisine.getInstance().debug("Failed to retrieve custom item: " + craftEngineId);
            }
        }

        // Fallback to vanilla
        if (item == null || item.getType() == Material.AIR) {
            item = new ItemStack(result != null ? result : Material.AIR);
            if (item.getType() != Material.AIR && customModelData != 0) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setCustomModelData(customModelData);
                    item.setItemMeta(meta);
                }
            }
        }

        if (item.getType() == Material.AIR) return item;

        // Apply Food Properties (Nutrition & Saturation & Container Return)
        if (nutrition != null || saturation != null || (returnContainer && containers != null && !containers.isEmpty())) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                try {
                    FoodComponent food = meta.getFood();
                    if (nutrition != null) food.setNutrition(nutrition);
                    if (saturation != null) food.setSaturation(saturation);
                    if (returnContainer && containers != null && !containers.isEmpty()) {
                        ItemStack containerItem = createItemFromIngredient(containers.get(0));
                        if (containerItem != null) {
                            food.setUsingConvertsTo(containerItem);
                        }
                    }
                    food.setCanAlwaysEat(true); // Custom food is often intended to be always edible (like soups)
                    meta.setFood(food);
                    item.setItemMeta(meta);
                } catch (Throwable t) {
                    // In case of API version mismatch, ignore.
                }
            }
        }
        
        return item;
    }

    public ItemStack createItemFromIngredient(Ingredient ing) {
        if (ing.getCraftEngineId() != null) {
             ItemStack item = null;
             if (CraftEngineHook.isEnabled()) {
                 item = CraftEngineHook.getItem(ing.getCraftEngineId());
             }
             if ((item == null || item.getType() == Material.AIR) && ItemsAdderHook.isItemsAdderLoaded()) {
                 item = ItemsAdderHook.getItem(ing.getCraftEngineId());
             }
             if (item != null && item.getType() != Material.AIR) return item;
        }
        if (ing.getMaterial() != null) {
             ItemStack item = new ItemStack(ing.getMaterial());
             if (ing.getCustomModelData() != 0) {
                 ItemMeta meta = item.getItemMeta();
                 if (meta != null) {
                    meta.setCustomModelData(ing.getCustomModelData());
                    item.setItemMeta(meta);
                 }
             }
             return item;
        }
        return null;
    }

    public boolean matches(List<ItemStack> inputs) {
        // Create a working copy of inputs to track consumption without affecting originals yet
        List<ItemStack> workingInputs = new ArrayList<>();
        for (ItemStack is : inputs) {
            if (is != null && !is.getType().isAir()) {
                workingInputs.add(is.clone());
            }
        }

        // Iterate through each ingredient requirement
        for (Map.Entry<Ingredient, Integer> entry : ingredients.entrySet()) {
            Ingredient ingredient = entry.getKey();
            int amountNeeded = entry.getValue();

            // Try to satisfy amountNeeded from workingInputs
            for (ItemStack item : workingInputs) {
                if (amountNeeded <= 0) break;
                if (item.getAmount() <= 0) continue; // Skip exhausted items

                if (ingredient.matches(item)) {
                    int consume = Math.min(item.getAmount(), amountNeeded);
                    item.setAmount(item.getAmount() - consume);
                    amountNeeded -= consume;
                }
            }

            if (amountNeeded > 0) {
                return false; // Insufficient ingredients
            }
        }

        // Strict check: Ensure no "foreign" items are left.
        // But allow "junk" containers (empty buckets, bottles, bowls) to remain.
        for (ItemStack input : inputs) {
            if (input == null || input.getType().isAir()) continue;
            
            // Allow empty containers to exist without breaking the recipe
            Material type = input.getType();
            if (type == Material.BUCKET || type == Material.GLASS_BOTTLE || type == Material.BOWL) {
                continue;
            }

            boolean isRelevant = false;
            // Check ingredients
            for (Ingredient ing : ingredients.keySet()) {
                if (ing.matches(input)) {
                    isRelevant = true;
                    break;
                }
            }
            
            // Check containers if not found in ingredients
            if (!isRelevant && containers != null) {
                for (Ingredient ing : containers) {
                    if (ing.matches(input)) {
                        isRelevant = true;
                        break;
                    }
                }
            }
            
            if (!isRelevant) return false;
        }

        return true;
    }
}
