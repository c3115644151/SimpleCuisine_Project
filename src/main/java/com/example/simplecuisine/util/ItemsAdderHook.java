package com.example.simplecuisine.util;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;

public class ItemsAdderHook {
    public static boolean isItemsAdderLoaded() {
        try {
            Class.forName("dev.lone.itemsadder.api.ItemsAdder");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static ItemStack getItem(String id) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method getInstance = clazz.getMethod("getInstance", String.class);
            Object stack = getInstance.invoke(null, id);
            
            if (stack != null) {
                Method getItemStack = clazz.getMethod("getItemStack");
                return (ItemStack) getItemStack.invoke(stack);
            }
        } catch (Throwable t) {
            // Silently fail if class not found or error
        }
        return null;
    }
    
    // Alias for getItem to match StoveManager usage
    public static ItemStack getCustomStack(String id) {
        return getItem(id);
    }

    public static String getCustomId(Block block) {
        String id = getCustomBlockId(block);
        if (id == null) {
            id = getCustomFurnitureId(block);
        }
        return id;
    }

    public static boolean isCustomBlock(Block block) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomBlock");
            Method byBlock = clazz.getMethod("byBlock", Block.class);
            return byBlock.invoke(null, block) != null;
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[SimpleCuisine] ItemsAdderHook.isCustomBlock error: " + t.getMessage());
            return false;
        }
    }

    public static String getCustomBlockId(Block block) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomBlock");
            Method byAlreadyPlaced = clazz.getMethod("byAlreadyPlaced", Block.class);
            Object customBlock = byAlreadyPlaced.invoke(null, block);
            
            if (customBlock != null) {
                Method getNamespacedID = clazz.getMethod("getNamespacedID");
                return (String) getNamespacedID.invoke(customBlock);
            }
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[SimpleCuisine] ItemsAdderHook.getCustomBlockId error: " + t.getMessage());
        }
        return null;
    }

    public static String getCustomFurnitureId(Block block) {
        // CustomFurniture does not support retrieving by Block in the current API version
        // or the method signature is different. Returning null to avoid NoSuchMethodException.
        return null;
    }

    public static String getCustomFurnitureId(org.bukkit.entity.Entity entity) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomFurniture");
            Method byEntity = clazz.getMethod("byEntity", org.bukkit.entity.Entity.class);
            Object customFurniture = byEntity.invoke(null, entity);
            
            if (customFurniture != null) {
                Method getNamespacedID = clazz.getMethod("getNamespacedID");
                return (String) getNamespacedID.invoke(customFurniture);
            }
        } catch (Throwable t) {
            // Silently fail
        }
        return null;
    }

    public static String getCustomStackId(ItemStack item) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method byItemStack = clazz.getMethod("byItemStack", ItemStack.class);
            Object customStack = byItemStack.invoke(null, item);
            
            if (customStack != null) {
                Method getNamespacedID = clazz.getMethod("getNamespacedID");
                return (String) getNamespacedID.invoke(customStack);
            }
        } catch (Throwable t) {
            // Silently fail
        }
        return null;
    }

    public static String getCustomItemId(ItemStack item) {
        return getCustomStackId(item);
    }

    public static boolean placeCustomBlock(String id, org.bukkit.Location loc) {
        try {
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.CustomBlock");
            Method place = clazz.getMethod("place", String.class, org.bukkit.Location.class);
            place.invoke(null, id, loc);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String getFontImage(String namespacedId) {
        try {
            // Try new package location first
            Class<?> clazz = Class.forName("dev.lone.itemsadder.api.FontImages.FontImageWrapper");
            java.lang.reflect.Constructor<?> constructor = clazz.getConstructor(String.class);
            Object wrapper = constructor.newInstance(namespacedId);
            Method getString = clazz.getMethod("getString");
            return (String) getString.invoke(wrapper);
        } catch (Throwable t1) {
            try {
                // Try old package location
                Class<?> clazz = Class.forName("dev.lone.itemsadder.api.FontImageWrapper");
                java.lang.reflect.Constructor<?> constructor = clazz.getConstructor(String.class);
                Object wrapper = constructor.newInstance(namespacedId);
                Method getString = clazz.getMethod("getString");
                return (String) getString.invoke(wrapper);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    public static String replaceFontImages(String text) {
        if (text == null) return null;
        
        // Pattern for :namespace:name:
        java.util.regex.Pattern dualPattern = java.util.regex.Pattern.compile(":([a-z0-9_]+):([a-z0-9_]+):");
        java.util.regex.Matcher matcher = dualPattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String namespace = matcher.group(1);
            String name = matcher.group(2);
            String fullId = namespace + ":" + name;
            String replacement = getFontImage(fullId);
            
            if (replacement != null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        
        String result = sb.toString();
        
        // Pattern for :name: (e.g. offsets)
        java.util.regex.Pattern singlePattern = java.util.regex.Pattern.compile(":([a-z0-9_\\-]+):");
        matcher = singlePattern.matcher(result);
        sb = new StringBuffer();
        
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = getFontImage(name);
            if (replacement != null) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
            } else {
                matcher.appendReplacement(sb, matcher.group(0));
            }
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
}
