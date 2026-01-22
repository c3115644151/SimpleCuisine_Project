package com.example.simplecuisine.cooking;

import com.example.simplecuisine.util.CraftEngineHook;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Objects;

public class Ingredient {
    private final String key;
    private final Material material;
    private final int customModelData;
    private final String craftEngineId;
    private final Tag<Material> tag;
    private final String externalTag; // Store external tag string (e.g. "farmersdelight:leafs")
    private List<ItemStack> cachedTagItems; // Cache resolved items

    public Ingredient(String key, Material material, int customModelData, String craftEngineId, Tag<Material> tag, String externalTag, List<ItemStack> cachedTagItems) {
        this.key = key;
        this.material = material;
        this.customModelData = customModelData;
        this.craftEngineId = craftEngineId;
        this.tag = tag;
        this.externalTag = externalTag;
        this.cachedTagItems = cachedTagItems;
    }

    public Ingredient(String key, Material material, int customModelData, String craftEngineId, Tag<Material> tag) {
        this(key, material, customModelData, craftEngineId, tag, null, null);
    }

    public Ingredient(String key, Material material, int customModelData, String craftEngineId) {
        this(key, material, customModelData, craftEngineId, null);
    }

    public Ingredient(String key, Material material, int customModelData) {
        this(key, material, customModelData, null, null);
    }

    public boolean matches(ItemStack item) {
        if (item == null) return false;

        // Priority check: CraftEngine ID
        if (craftEngineId != null && CraftEngineHook.isEnabled()) {
            String itemId = CraftEngineHook.getItemId(item);
            if (itemId != null && itemId.equals(craftEngineId)) {
                return true;
            }
            // If CraftEngine ID is specified but doesn't match, return false immediately?
            // Or should we fallback to vanilla check?
            // Usually if a specific custom item is required, we don't accept vanilla items.
            return false;
        }

        // Tag check
        if (tag != null && tag.isTagged(item.getType())) {
            return true;
        }

        // External Tag check (for custom item tags like CraftEngine)
        if (externalTag != null && cachedTagItems != null && !cachedTagItems.isEmpty()) {
            for (ItemStack cached : cachedTagItems) {
                if (isSimilar(item, cached)) {
                    return true;
                }
            }
        }

        if (item.getType() != material) return false;
        
        // If this ingredient requires specific CustomModelData
        if (customModelData != 0) {
            if (!item.hasItemMeta()) return false;
            ItemMeta meta = item.getItemMeta();
            if (!meta.hasCustomModelData()) return false;
            return meta.getCustomModelData() == customModelData;
        }
        
        // If this ingredient is generic (no CMD required), we usually accept any item of this material.
        return true;
    }

    private boolean isSimilar(ItemStack item1, ItemStack item2) {
        if (item1 == null || item2 == null) return false;
        
        // Check CraftEngine ID first
        if (CraftEngineHook.isEnabled()) {
            String id1 = CraftEngineHook.getItemId(item1);
            String id2 = CraftEngineHook.getItemId(item2);
            if (id1 != null && id2 != null) {
                return id1.equals(id2);
            }
        }
        
        // Fallback to vanilla similarity (Type + Meta, ignoring amount)
        return item1.isSimilar(item2);
    }

    public String getKey() {
        return key;
    }

    public Material getMaterial() {
        return material;
    }

    public int getCustomModelData() {
        return customModelData;
    }

    public String getCraftEngineId() {
        return craftEngineId;
    }

    public Tag<Material> getTag() {
        return tag;
    }

    public String getExternalTag() {
        return externalTag;
    }

    public List<ItemStack> getCachedTagItems() {
        return cachedTagItems;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Ingredient that = (Ingredient) o;
        return customModelData == that.customModelData &&
                material == that.material &&
                Objects.equals(key, that.key) &&
                Objects.equals(craftEngineId, that.craftEngineId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, material, customModelData, craftEngineId);
    }
}
