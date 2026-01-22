package com.example.simplecuisine.util;

import com.example.simplecuisine.SimpleCuisine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CraftEngineHook {

    private static boolean enabled = false;
    private static final Map<String, ItemStack> itemCache = new ConcurrentHashMap<>();

    // New Fields for setBlock fix
    private static Class<?> compoundTagClass;
    private static Method compoundTagPutStringMethod;
    private static Method compoundTagPutIntMethod;
    private static Method compoundTagPutByteMethod; // New method for boolean support
    private static Method placeMethodWithTag;
    private static Method placeMethodNoTag;

    // Classes
    private static Class<?> ceItemsClass;
    private static Class<?> ceBlocksClass;
    private static Class<?> ceFurnitureClass;
    private static Class<?> keyClass;
    private static Class<?> contextClass;
    private static Class<?> ceClass;
    private static Class<?> fontManagerClass;
    private static Class<?> customItemClass;
    private static Class<?> blockStateClass;

    // Methods
    private static Method isCustomBlockMethod;
    private static Method getCustomBlockStateMethod;
    private static Method blockStateIdMethod;
    private static Method blockStatePropertyMethod; // New method for property
    // Fallback block state methods
    private static Method blockStateOwnerMethod; // owner()
    private static Method ownerValueMethod;      // value()
    private static Method ownerIdMethod;         // id()

    private static Method getLoadedFurnitureByColliderMethod;
    // unused field removed
    private static Method furnitureIdMethod;
    private static Method instanceMethod;
    private static Method fontManagerMethod;
    private static Method codepointByImageIdMethod;
    private static Method keyOfMethod;
    private static Method byIdMethod;
    private static Method getCustomItemIdMethod;
    private static Method emptyContextMethod;
    private static Method buildItemStackNoArgMethod;
    private static Method buildItemStackMethod;
    private static Method displayNameMethod;
    private static Method translationKeyMethod;
    private static Object translationManager;
    private static Object itemBrowserManager;
    private static Method browserGetItemMethod;
    private static Method translateMethod;
    private static Method translateWithLocaleMethod;
    private static Method getTagItemsMethod;
    private static Method getCustomTagItemsMethod; // New method for custom items only
    private static Object tagManager;
    private static Method contextOfPlayerMethod;
    private static Method createWrappedItemMethod; // New method from ItemManager
    private static Method createCustomWrappedItemMethod; // Fallback/Alternative method name
    private static Method wrapItemMethod; // itemManager.wrap(ItemStack)
    private static Method hoverNameComponentMethod; // item.hoverNameComponent()
    private static Method itemGetItemMethod; // item.getItem() -> ItemStack

    // Block methods
    private static Method setBlockMethod; // Static method from CraftEngineBlocks
    private static Object blockManager; // Instance from CraftEngine.instance().blockManager()
    private static Method blockManagerSetBlockMethod; // Instance method from BlockManager

    private static Object itemManager; // CraftEngine.instance().itemManager()

    // Cache key for fallback ID storage
    private static final org.bukkit.NamespacedKey KEY_CE_ID = new org.bukkit.NamespacedKey("simplecuisine", "ce_id");
    private static final org.bukkit.NamespacedKey KEY_CE_TRANS_KEY = new org.bukkit.NamespacedKey("simplecuisine", "ce_trans_key");

    public static void init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) return;

        try {
            // 1. Load Classes First
            // We need Key class early for method matching
            keyClass = Class.forName("net.momirealms.craftengine.core.util.Key");
            
            // Load classes using the official API structure found via documentation
            ceItemsClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            try {
                ceBlocksClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks");
                
                // Load Sparrow NBT CompoundTag
                // Try the shaded path found in jar (Priority 1)
                try {
                    compoundTagClass = Class.forName("net.momirealms.craftengine.libraries.nbt.CompoundTag");
                    Bukkit.getLogger().info("[SimpleCuisine] Found CompoundTag class (Shaded): " + compoundTagClass.getName());
                } catch (ClassNotFoundException e) {
                    try {
                        // Try original path (Priority 2)
                        compoundTagClass = Class.forName("net.momirealms.sparrow.nbt.CompoundTag");
                        Bukkit.getLogger().info("[SimpleCuisine] Found CompoundTag class (Sparrow): " + compoundTagClass.getName());
                    } catch (ClassNotFoundException e2) {
                        Bukkit.getLogger().warning("[SimpleCuisine] Failed to find CompoundTag class via Class.forName");
                    }
                }
                
                if (compoundTagClass != null) {
                    try {
                        compoundTagPutStringMethod = compoundTagClass.getMethod("putString", String.class, String.class);
                        compoundTagPutIntMethod = compoundTagClass.getMethod("putInt", String.class, int.class);
                        try {
                            compoundTagPutByteMethod = compoundTagClass.getMethod("putByte", String.class, byte.class);
                        } catch (NoSuchMethodException e) {
                             // Ignore if missing (some old NBT libs might differ, but unlikely)
                        }
                    } catch (Exception e) {
                        Bukkit.getLogger().warning("[SimpleCuisine] Failed to find putString/putInt methods: " + e.getMessage());
                    }
                }

                // Look for place methods dynamically to handle API variations
                boolean foundPlace = false;
                Bukkit.getLogger().info("[SimpleCuisine] Searching for place methods in " + ceBlocksClass.getName());
                
                for (Method m : ceBlocksClass.getMethods()) {
                    if (m.getName().equals("place")) {
                        Class<?>[] params = m.getParameterTypes();
                        // Debug log for place methods
                        String paramTypes = "";
                        for (Class<?> p : params) paramTypes += p.getSimpleName() + ", ";
                        Bukkit.getLogger().info("[SimpleCuisine] Found place method: (" + paramTypes + ")");
                        
                        // Check for place(Location, Key, boolean)
                        if (params.length == 3 
                                && Location.class.isAssignableFrom(params[0]) 
                                && params[1].equals(keyClass)
                                && (params[2].equals(boolean.class) || params[2].equals(Boolean.class))) {
                            placeMethodNoTag = m;
                            Bukkit.getLogger().info("[SimpleCuisine] -> Selected as placeMethodNoTag");
                            foundPlace = true;
                        }
                        
                        // Check for place(Location, Key, CompoundTag, boolean)
                        if (params.length == 4 
                                && Location.class.isAssignableFrom(params[0]) 
                                && params[1].equals(keyClass)
                                && (params[3].equals(boolean.class) || params[3].equals(Boolean.class))) {
                            
                            // Check the 3rd parameter to ensure it is NOT UpdateOption
                            Class<?> thirdParam = params[2];
                            if (thirdParam.getSimpleName().contains("UpdateOption")) {
                                Bukkit.getLogger().info("[SimpleCuisine] -> Skipping place method with UpdateOption");
                                continue;
                            }
                                    
                            placeMethodWithTag = m;
                            Bukkit.getLogger().info("[SimpleCuisine] -> Selected as placeMethodWithTag");
                            
                            // Capture the CompoundTag class from the method signature if not already found
                            Class<?> tagParamType = params[2];
                            if (compoundTagClass == null || !compoundTagClass.equals(tagParamType)) {
                                compoundTagClass = tagParamType;
                                Bukkit.getLogger().info("[SimpleCuisine] Captured CompoundTag class from method: " + compoundTagClass.getName());
                                try {
                                    compoundTagPutStringMethod = compoundTagClass.getMethod("putString", String.class, String.class);
                                    compoundTagPutIntMethod = compoundTagClass.getMethod("putInt", String.class, int.class);
                                    try {
                                        compoundTagPutByteMethod = compoundTagClass.getMethod("putByte", String.class, byte.class);
                                    } catch (NoSuchMethodException e) {
                                         // ignore
                                    }
                                } catch (Exception e) {
                                    // ignore
                                }
                            }
                            foundPlace = true;
                        }
                    }
                }
                
                if (!foundPlace) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to find ANY place method in CraftEngineBlocks!");
                    // Debug dump
                    for (Method m : ceBlocksClass.getMethods()) {
                         Bukkit.getLogger().info("[SimpleCuisine] CEBlocks Method: " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
                    }
                }

            } catch (ClassNotFoundException e) {
                // Ignore
            }
            try {
                ceFurnitureClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture");
            } catch (ClassNotFoundException e) {
                // Ignore
            }

            keyClass = Class.forName("net.momirealms.craftengine.core.util.Key");
            contextClass = Class.forName("net.momirealms.craftengine.core.item.ItemBuildContext");
            
            // Try to find context creation methods
            try {
                contextOfPlayerMethod = contextClass.getMethod("of", org.bukkit.entity.Player.class);
            } catch (NoSuchMethodException e) {
                // Try finding a builder or other factory method if 'of' doesn't exist
                // For now, we rely on 'of' or fallback to empty
            }
            
            // Try to load CraftEngine instance for FontManager and TagManager
            try {
                ceClass = Class.forName("net.momirealms.craftengine.core.plugin.CraftEngine");
                // The instance() method might be static
                instanceMethod = ceClass.getMethod("instance");
                Object instance = instanceMethod.invoke(null);

                // Get ItemManager
                try {
                    Method itemManagerMethod = null;
                    try {
                        itemManagerMethod = ceClass.getMethod("itemManager");
                    } catch (NoSuchMethodException e) {
                        try {
                            itemManagerMethod = ceClass.getMethod("getItemManager");
                        } catch (NoSuchMethodException e2) {
                            // ignore
                        }
                    }

                    if (itemManagerMethod != null) {
                        itemManager = itemManagerMethod.invoke(instance);
                        Bukkit.getLogger().info("[SimpleCuisine] Hooked ItemManager: " + itemManager.getClass().getName());
                        
                        // Hook wrap(ItemStack) -> Item<ItemStack>
                        for (Method m : itemManager.getClass().getMethods()) {
                            if (m.getName().equals("wrap") && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(ItemStack.class)) {
                                wrapItemMethod = m;
                                Bukkit.getLogger().info("[SimpleCuisine] Found wrap(ItemStack) method");
                            }
                            // Hook createWrappedItem(Key, Player)
                            if (m.getName().equals("createWrappedItem") && m.getParameterCount() >= 1 && m.getParameterTypes()[0].equals(keyClass)) {
                                createWrappedItemMethod = m;
                                Bukkit.getLogger().info("[SimpleCuisine] Found createWrappedItem method");
                            }
                        }
                        
                        // We need to find the Item interface or class returned by wrap/createWrappedItem
                        if (wrapItemMethod != null) {
                            Class<?> itemInterface = wrapItemMethod.getReturnType();
                            try {
                                hoverNameComponentMethod = itemInterface.getMethod("hoverNameComponent");
                                itemGetItemMethod = itemInterface.getMethod("getItem");
                                Bukkit.getLogger().info("[SimpleCuisine] Found hoverNameComponent and getItem methods on " + itemInterface.getName());
                            } catch (NoSuchMethodException e) {
                                Bukkit.getLogger().warning("[SimpleCuisine] Failed to find hoverNameComponent/getItem on " + itemInterface.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to hook ItemManager: " + e.getMessage());
                }

                // Get TranslationManager

                // Get TranslationManager
                try {
                    Method transManagerMethod = null;
                    try {
                        transManagerMethod = ceClass.getMethod("translationManager");
                    } catch (NoSuchMethodException e) {
                        try {
                            transManagerMethod = ceClass.getMethod("getTranslationManager");
                        } catch (NoSuchMethodException e2) {
                            // ignore
                        }
                    }

                    if (transManagerMethod != null) {
                        translationManager = transManagerMethod.invoke(instance);
                        Bukkit.getLogger().info("[SimpleCuisine] Hooked TranslationManager: " + translationManager.getClass().getName());
                        
                        // Search for translate method
                        for (Method m : translationManager.getClass().getMethods()) {
                            // Look for: Component translate(Key key) or String translate(Key key)
                            if (m.getParameterCount() == 1) {
                                Class<?> paramType = m.getParameterTypes()[0];
                                if ((paramType.equals(keyClass) || paramType.equals(String.class)) && 
                                   (Component.class.isAssignableFrom(m.getReturnType()) || String.class.isAssignableFrom(m.getReturnType()))) {
                                    
                                    // Prefer method that takes Key if available, but String is also fine
                                    if (translateMethod == null || paramType.equals(keyClass)) {
                                        translateMethod = m;
                                        Bukkit.getLogger().info("[SimpleCuisine] Found Translate Method: " + m.getName() + "(" + paramType.getSimpleName() + ")");
                                    }
                                }
                            }
                            
                            // Look for: Component translate(Key key, String locale)
                            if (m.getParameterCount() == 2) {
                                Class<?> p1 = m.getParameterTypes()[0];
                                Class<?> p2 = m.getParameterTypes()[1];
                                if ((p1.equals(keyClass) || p1.equals(String.class)) && p2.equals(String.class)) {
                                    translateWithLocaleMethod = m;
                                    Bukkit.getLogger().info("[SimpleCuisine] Found TranslateWithLocale Method: " + m.getName() + "(" + p1.getSimpleName() + ", String)");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to hook TranslationManager: " + e.getMessage());
                }

                // Get ItemBrowserManager
                try {
                    Method browserManagerMethod = null;
                    try {
                        browserManagerMethod = ceClass.getMethod("itemBrowserManager");
                    } catch (NoSuchMethodException e) {
                        try {
                            browserManagerMethod = ceClass.getMethod("getItemBrowserManager");
                        } catch (NoSuchMethodException e2) {
                            // ignore
                        }
                    }

                    if (browserManagerMethod != null) {
                        itemBrowserManager = browserManagerMethod.invoke(instance);
                        Bukkit.getLogger().info("[SimpleCuisine] Hooked ItemBrowserManager: " + itemBrowserManager.getClass().getName());
                        
                        // Check for useful methods in BrowserManager
                        for (Method m : itemBrowserManager.getClass().getMethods()) {
                             // Look for methods that return ItemStack
                             if (m.getReturnType().equals(ItemStack.class) && m.getParameterCount() == 1) {
                                 Bukkit.getLogger().info("[SimpleCuisine] Found Browser Method: " + m.getName() + " -> ItemStack");
                                 browserGetItemMethod = m;
                             }
                        }
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to hook ItemBrowserManager: " + e.getMessage());
                }

                // DEBUG: Dump CraftEngine methods to find tagManager
                Bukkit.getLogger().info("[SimpleCuisine] DEBUG: Dumping CraftEngine methods:");
                for (Method m : ceClass.getMethods()) {
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CE Method: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                }

                try {
                    fontManagerMethod = ceClass.getMethod("fontManager");
                    // FontManager return type
                    fontManagerClass = fontManagerMethod.getReturnType();
                    // FontManager methods
                    codepointByImageIdMethod = fontManagerClass.getMethod("codepointByImageId", keyClass);
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to load CraftEngine FontManager API: " + e.getMessage());
                }

                // DEBUG: Dump CraftEngineItems methods
                if (ceItemsClass != null) {
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: Dumping CraftEngineItems methods:");
                    for (Method m : ceItemsClass.getMethods()) {
                        Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CEItems Method: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                    }
                }

                // DEBUG: Try to find CraftEngineTags
                try {
                    Class<?> ceTagsClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineTags");
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: Found CraftEngineTags class!");
                    for (Method m : ceTagsClass.getMethods()) {
                        Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CETags Method: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                    }
                } catch (ClassNotFoundException e) {
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CraftEngineTags class not found.");
                }

                // DEBUG: Try to find CraftEngineAPI
                try {
                    Class<?> ceApiClass = Class.forName("net.momirealms.craftengine.api.CraftEngineAPI");
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: Found CraftEngineAPI class!");
                    for (Method m : ceApiClass.getMethods()) {
                        Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CEAPI Method: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                    }
                } catch (ClassNotFoundException e) {
                    Bukkit.getLogger().info("[SimpleCuisine] DEBUG: CraftEngineAPI class not found.");
                }

                // Try to find ItemManager (acting as TagManager in newer API)
                try {
                    Method itemManagerMethod = ceClass.getMethod("itemManager");
                    Object itemManager = itemManagerMethod.invoke(instance);
                    if (itemManager != null) {
                        Class<?> itemManagerClass = itemManager.getClass();
                        // Search for itemIdsByTag(Key)
                        for (Method m : itemManagerClass.getMethods()) {
                            if (m.getName().equals("itemIdsByTag") && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(keyClass)) {
                                getTagItemsMethod = m;
                                tagManager = itemManager; // Use itemManager as tagManager
                                Bukkit.getLogger().info("[SimpleCuisine] Found ItemManager.itemIdsByTag method!");
                            }
                            
                            // Search for customItemIdsByTag(Key) - Recommended by DeepWiki
                            if (m.getName().equals("customItemIdsByTag") && m.getParameterCount() == 1 && m.getParameterTypes()[0].equals(keyClass)) {
                                getCustomTagItemsMethod = m;
                                Bukkit.getLogger().info("[SimpleCuisine] Found ItemManager.customItemIdsByTag method!");
                            }
                            
                            // Search for createWrappedItem(Key, Player) or createCustomWrappedItem
                            if ((m.getName().equals("createWrappedItem") || m.getName().equals("createCustomWrappedItem")) 
                                    && m.getParameterCount() == 2 
                                    && m.getParameterTypes()[0].equals(keyClass)
                                    && org.bukkit.entity.Player.class.isAssignableFrom(m.getParameterTypes()[1])) {
                                
                                if (m.getName().equals("createWrappedItem")) {
                                    createWrappedItemMethod = m;
                                    Bukkit.getLogger().info("[SimpleCuisine] Found ItemManager.createWrappedItem method!");
                                } else {
                                    createCustomWrappedItemMethod = m;
                                    Bukkit.getLogger().info("[SimpleCuisine] Found ItemManager.createCustomWrappedItem method!");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().info("[SimpleCuisine] Failed to find ItemManager methods: " + e.getMessage());
                }

            } catch (Exception e) {
                Bukkit.getLogger().warning("[SimpleCuisine] Failed to load CraftEngine instance: " + e.getMessage());
            }

            // Load methods
            keyOfMethod = keyClass.getMethod("of", String.class);
            byIdMethod = ceItemsClass.getMethod("byId", keyClass);
            getCustomItemIdMethod = ceItemsClass.getMethod("getCustomItemId", ItemStack.class);
            emptyContextMethod = contextClass.getMethod("empty");
            
            // Block API
            if (ceBlocksClass != null) {
                try {
                    isCustomBlockMethod = ceBlocksClass.getMethod("isCustomBlock", org.bukkit.block.Block.class);
                    getCustomBlockStateMethod = ceBlocksClass.getMethod("getCustomBlockState", org.bukkit.block.Block.class);
                    blockStateClass = getCustomBlockStateMethod.getReturnType();
                    
                    // Try to find id() method on block state
                    try {
                        blockStateIdMethod = blockStateClass.getMethod("id");
                        // Try to find property method
                    try {
                         // Fallback to getPropertiesAsString if property(String) is missing
                         try {
                             blockStatePropertyMethod = blockStateClass.getMethod("getPropertiesAsString");
                             com.example.simplecuisine.SimpleCuisine.getInstance().debug("Using getPropertiesAsString for property retrieval");
                         } catch (NoSuchMethodException ex) {
                             // Try original property method
                             blockStatePropertyMethod = blockStateClass.getMethod("property", String.class);
                             com.example.simplecuisine.SimpleCuisine.getInstance().debug("Successfully found property method on blockStateClass");
                         }
                    } catch (NoSuchMethodException e) {
                         // Ignore
                         com.example.simplecuisine.SimpleCuisine.getInstance().debug("Failed to find property retrieval method: " + e.getMessage());
                    }
                    } catch (NoSuchMethodException e) {
                        // Try owner().value().id() chain if simple id() doesn't exist
                        try {
                            blockStateOwnerMethod = blockStateClass.getMethod("owner");
                            Class<?> ownerClass = blockStateOwnerMethod.getReturnType();
                            ownerValueMethod = ownerClass.getMethod("value");
                            Class<?> valueClass = ownerValueMethod.getReturnType();
                            ownerIdMethod = valueClass.getMethod("id");
                            
                            // Try to find property method on the value class
                            try {
                                blockStatePropertyMethod = valueClass.getMethod("property", String.class);
                                com.example.simplecuisine.SimpleCuisine.getInstance().debug("Successfully found property method on valueClass");
                            } catch (NoSuchMethodException ex) {
                                // Ignore
                                com.example.simplecuisine.SimpleCuisine.getInstance().debug("Failed to find property method on valueClass: " + ex.getMessage());
                            }
                        } catch (Exception ex) {
                            com.example.simplecuisine.SimpleCuisine.getInstance().debug("Failed to find block ID method via owner chain: " + ex.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to load CraftEngine Block API: " + e.getMessage());
                }
            }

            // Furniture API
            if (ceFurnitureClass != null) {
                try {
                    getLoadedFurnitureByColliderMethod = ceFurnitureClass.getMethod("getLoadedFurnitureByCollider", org.bukkit.entity.Entity.class);
                    Class<?> furnitureClass = getLoadedFurnitureByColliderMethod.getReturnType();
                    furnitureIdMethod = furnitureClass.getMethod("id");
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[SimpleCuisine] Failed to load CraftEngine Furniture API: " + e.getMessage());
                }
            }
            
            // Determine CustomItem class from return type of byId
            customItemClass = byIdMethod.getReturnType();
            
            // Try to load buildItemStack methods
            // Priority: buildItemStack() -> buildItemStack(context)
            try {
                buildItemStackNoArgMethod = customItemClass.getMethod("buildItemStack");
            } catch (NoSuchMethodException e) {
                // Ignore, might not exist in this version
            }
            
            try {
                buildItemStackMethod = customItemClass.getMethod("buildItemStack", contextClass);
            } catch (NoSuchMethodException e) {
                // Should exist as per API
            }

            if (buildItemStackMethod == null && buildItemStackNoArgMethod == null) {
                throw new RuntimeException("No buildItemStack method found in CustomItem class");
            }

            // Try to find displayName method to fix missing names in GUI
            try {
                displayNameMethod = customItemClass.getMethod("displayName");
            } catch (NoSuchMethodException e) {
                try {
                    displayNameMethod = customItemClass.getMethod("name");
                } catch (NoSuchMethodException e2) {
                    try {
                        displayNameMethod = customItemClass.getMethod("getDisplayName");
                    } catch (NoSuchMethodException e3) {
                        try {
                            displayNameMethod = customItemClass.getMethod("getName");
                        } catch (NoSuchMethodException e3b) {
                            try {
                                 // Fallback to translationKey
                                 translationKeyMethod = customItemClass.getMethod("translationKey");
                                 Bukkit.getLogger().info("[SimpleCuisine] Found translationKey method for localization.");
                            } catch (NoSuchMethodException e4) {
                                 Bukkit.getLogger().info("[SimpleCuisine] Could not find displayName/name/getName/translationKey method in CustomItem class.");
                            }
                        }
                    }
                }
            }

            enabled = true;
            Bukkit.getLogger().info("[SimpleCuisine] CraftEngine hooked successfully via Official API!");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SimpleCuisine] Failed to hook CraftEngine API: " + e.getMessage());
            e.printStackTrace();
            enabled = false;
        }
    }

    /**
     * Re-generates the item using the player's context (e.g. language).
     * This ensures the item has the correct localized name and lore for the specific player.
     * 
     * @param item The original item (which might have default/English text)
     * @param player The player who will view the item
     * @return A new ItemStack with localized text, or the original item if localization failed
     */
    public static ItemStack localizeItem(ItemStack item, org.bukkit.entity.Player player) {
        if (!enabled || item == null || player == null) {
            return item;
        }

        String id = getCustomItemId(item);
        if (id == null) return item;

        try {
            Object key = keyOfMethod.invoke(null, id);
            
            // Priority 1: Use ItemManager.createWrappedItem(Key, Player) - Recommended by DeepWiki
            // This applies all ItemDataModifiers including CustomNameModifier correctly
            if (tagManager != null) {
                Method methodToUse = (createWrappedItemMethod != null) ? createWrappedItemMethod : createCustomWrappedItemMethod;
                if (methodToUse != null) {
                    try {
                        Object newItem = methodToUse.invoke(tagManager, key, player);
                        if (newItem instanceof ItemStack) {
                            ItemStack result = (ItemStack) newItem;
                            result.setAmount(item.getAmount());
                            // Ensure ID is preserved in PDC for future lookups
                            org.bukkit.inventory.meta.ItemMeta meta = result.getItemMeta();
                            if (meta != null) {
                                meta.getPersistentDataContainer().set(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING, id);
                                
                                // Restore display name from Original Item if missing in result (Critical for items with direct item-name config)
                                if (!meta.hasDisplayName() && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                                    meta.displayName(item.getItemMeta().displayName());
                                }
                                
                                result.setItemMeta(meta);
                            }
                            
                            // Apply Smart Localization (Ensure returned item is localized)
                            applySmartLocalization(result, key, player);
                            
                            return result;
                        }
                    } catch (Exception e) {
                         SimpleCuisine.getInstance().debug("Failed to use createWrappedItem: " + e.getMessage());
                    }
                }
            }

            // Priority 2: Manual Context Building (Fallback)
            Object customItem = byIdMethod.invoke(null, key);
            
            if (customItem != null && contextOfPlayerMethod != null) {
                Object context = contextOfPlayerMethod.invoke(null, player);
                Object newItem = buildItemStackMethod.invoke(customItem, context);
                if (newItem instanceof ItemStack) {
                    ItemStack result = (ItemStack) newItem;
                    result.setAmount(item.getAmount());

                    org.bukkit.inventory.meta.ItemMeta meta = result.getItemMeta();
                    
                    // Inject PDC into result to preserve identity
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING, id);
                        result.setItemMeta(meta);
                    }

                    // Restore display name from CustomItem definition if missing (Smart Inheritance)
                    if (meta != null && !meta.hasDisplayName()) {
                        boolean restored = false;
                        
                        // 0. Try to use Original Item's name (High Priority Fallback)
                        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                            meta.displayName(item.getItemMeta().displayName());
                            result.setItemMeta(meta);
                            restored = true;
                        }
                        
                        // 1. Try Reflection Method
                        if (!restored && displayNameMethod != null) {
                            try {
                                Object nameObj = displayNameMethod.invoke(customItem);
                                if (nameObj != null) {
                                    if (nameObj instanceof Component) {
                                        meta.displayName((Component) nameObj);
                                    } else {
                                        meta.displayName(Component.text(nameObj.toString()));
                                    }
                                    result.setItemMeta(meta);
                                    restored = true;
                                }
                            } catch (Exception e) {
                                // Ignore
                            }
                        }
                        
                        // 1.5. Try Translation Key
                        if (!restored && translationKeyMethod != null && translationManager != null && translateMethod != null) {
                            try {
                                String transKey = (String) translationKeyMethod.invoke(customItem);
                                if (transKey != null) {
                                    Object transResult = null;
                                    
                                    // Determine argument type
                                    Class<?> paramType = translateMethod.getParameterTypes()[0];
                                    if (paramType.equals(String.class)) {
                                         transResult = translateMethod.invoke(translationManager, transKey);
                                    } else {
                                         // Expects Key
                                         // If transKey is like "item.foo.bar", it might not be a valid Key string (namespace:value)
                                         // Try to force it or wrap it
                                         try {
                                             Object keyObj;
                                             if (transKey.contains(":")) {
                                                 keyObj = keyOfMethod.invoke(null, transKey);
                                             } else {
                                                 // Try to construct Key from raw string if possible, or use dummy namespace
                                                 // But wait, if it's a translation key, it might not be a Key object.
                                                 // Maybe we should just try invoke(null, transKey) if keyOfMethod allows it?
                                                 // Let's try treating it as namespace "minecraft" if no colon? No.
                                                 keyObj = keyOfMethod.invoke(null, "craftengine:" + transKey); 
                                             }
                                             transResult = translateMethod.invoke(translationManager, keyObj);
                                         } catch (Exception ex) {
                                             // Key creation failed
                                         }
                                    }

                                    if (transResult != null) {
                                        if (transResult instanceof Component) {
                                            meta.displayName((Component) transResult);
                                        } else {
                                            meta.displayName(Component.text(transResult.toString()));
                                        }
                                        result.setItemMeta(meta);
                                        restored = true;
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore
                            }
                        }

                        // 2. Fallback to ItemBrowserManager (if reflection failed or didn't exist)
                        if (!restored && itemBrowserManager != null && browserGetItemMethod != null) {
                             try {
                                ItemStack browserItem = null;
                                // Try getting via ID (Key)
                                try {
                                    browserItem = (ItemStack) browserGetItemMethod.invoke(itemBrowserManager, key);
                                } catch (Exception ex) {
                                    // Try via String ID
                                    browserItem = (ItemStack) browserGetItemMethod.invoke(itemBrowserManager, id);
                                }
                                
                                if (browserItem != null && browserItem.hasItemMeta() && browserItem.getItemMeta().hasDisplayName()) {
                                    meta.displayName(browserItem.getItemMeta().displayName());
                                    // Also copy lore if missing?
                                    if (!meta.hasLore() && browserItem.getItemMeta().hasLore()) {
                                        meta.lore(browserItem.getItemMeta().lore());
                                    }
                                    result.setItemMeta(meta);
                                    restored = true;
                                }
                             } catch (Exception e) {
                                 // Ignore
                             }
                        }
                        
                        // 3. Last Resort: Use ID as name
                        if (!restored && meta != null && !meta.hasDisplayName()) {
                            meta.displayName(Component.text(id));
                            result.setItemMeta(meta);
                        }
                    }
                    
                    applySmartLocalization(result, key, player);
                    return result;
                }
            }
        } catch (Exception e) {
            // Silently fail and return original
        }
        return item;
    }
    
    private static boolean isAscii(String str) {
        for (char c : str.toCharArray()) {
            if (c > 127) return false;
        }
        return true;
    }

    public static String getFontImageChar(String imageId) {
        if (!enabled || imageId == null || fontManagerClass == null) return null;
        try {
            // Get instance
            Object instance = instanceMethod.invoke(null);
            // Get FontManager
            Object fontManager = fontManagerMethod.invoke(instance);
            
            // Create Key
            Object key;
            if (imageId.contains(":")) {
                key = keyOfMethod.invoke(null, imageId);
            } else {
                key = keyOfMethod.invoke(null, "craftengine:" + imageId); // Default fallback
            }
            
            // Get codepoint (int)
            // Method signature: codepointByImageId(Key key) -> int
            int codepoint = (int) codepointByImageIdMethod.invoke(fontManager, key);
            
            if (codepoint > 0) {
                return new String(Character.toChars(codepoint));
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[SimpleCuisine] Failed to get font image for '" + imageId + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Retrieves the list of Custom Items associated with a tag.
     * Uses CraftEngine's API to ensure items are localized and have correct Custom Item properties.
     */
    public static java.util.List<ItemStack> getTagCustomItems(String tagKey, org.bukkit.entity.Player player) {
        java.util.List<ItemStack> results = new java.util.ArrayList<>();
        if (!enabled || tagManager == null) return results;

        try {
            Object key = keyOfMethod.invoke(null, tagKey);
            
            // Priority 1: Use customItemIdsByTag (Recommended by DeepWiki to avoid vanilla items)
            if (getCustomTagItemsMethod != null) {
                java.util.List<?> uniqueKeys = (java.util.List<?>) getCustomTagItemsMethod.invoke(tagManager, key);
                
                Method methodToUse = (createWrappedItemMethod != null) ? createWrappedItemMethod : createCustomWrappedItemMethod;
                if (methodToUse != null && uniqueKeys != null) {
                    for (Object uniqueKey : uniqueKeys) {
                        try {
                            // Extract Key from UniqueKey if needed
                            Object itemKey = uniqueKey;
                            try {
                                Method keyMethod = uniqueKey.getClass().getMethod("key");
                                itemKey = keyMethod.invoke(uniqueKey);
                            } catch (Exception e) {
                                // Maybe it is already a Key or doesn't have key() method
                            }
                            
                            Object newItemWrapper = methodToUse.invoke(tagManager, itemKey, player);
                            if (newItemWrapper != null && itemGetItemMethod != null) {
                                Object itemObj = itemGetItemMethod.invoke(newItemWrapper);
                                if (itemObj instanceof ItemStack) {
                                    ItemStack resultStack = (ItemStack) itemObj;
                                    // Inject PDC for identity
                                    org.bukkit.inventory.meta.ItemMeta meta = resultStack.getItemMeta();
                                    if (meta != null) {
                                        String idStr = itemKey.toString();
                                        meta.getPersistentDataContainer().set(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING, idStr);
                                        resultStack.setItemMeta(meta);
                                    }
                                    
                                    // Apply Smart Localization (Fix for missing names)
                                    applySmartLocalization(resultStack, itemKey, player);
                                    
                                    results.add(resultStack);
                                }
                            }
                        } catch (Exception e) {
                            // Ignore individual failures
                        }
                    }
                }
                if (!results.isEmpty()) return results;
            }
            
            // Priority 2: Use getTagItemsMethod (Legacy/Fallback - might mix vanilla items)
            if (getTagItemsMethod != null) {
                java.util.List<?> itemIds = (java.util.List<?>) getTagItemsMethod.invoke(tagManager, key);
                if (itemIds != null) {
                     for (Object itemId : itemIds) {
                         ItemStack item = null;
                         
                         // Try createWrappedItem first
                         Method methodToUse = (createWrappedItemMethod != null) ? createWrappedItemMethod : createCustomWrappedItemMethod;
                         if (methodToUse != null) {
                             try {
                                 Object newItemWrapper = methodToUse.invoke(tagManager, itemId, player);
                                 if (newItemWrapper != null && itemGetItemMethod != null) {
                                     Object itemObj = itemGetItemMethod.invoke(newItemWrapper);
                                     if (itemObj instanceof ItemStack) {
                                         item = (ItemStack) itemObj;
                                     }
                                 }
                             } catch (Exception e) {}
                         }
                         
                         // Fallback to manual localization
                         if (item == null) {
                             try {
                                 Object customItem = byIdMethod.invoke(null, itemId);
                                 if (customItem != null) {
                                     Object context = contextOfPlayerMethod.invoke(null, player);
                                     Object newItem = buildItemStackMethod.invoke(customItem, context);
                                     if (newItem instanceof ItemStack) {
                                         item = (ItemStack) newItem;
                                     }
                                 }
                             } catch (Exception e) {}
                         }
                         
                         if (item != null) {
                             // Inject PDC for identity (Crucial for later name resolution)
                             try {
                                 org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                                 if (meta != null) {
                                     String idStr = itemId.toString();
                                     meta.getPersistentDataContainer().set(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING, idStr);
                                     item.setItemMeta(meta);
                                 }
                             } catch (Exception e) {
                                 // Ignore
                             }
                             results.add(item);
                         }
                     }
                }
            }
            
        } catch (Exception e) {
            SimpleCuisine.getInstance().debug("Failed to get tag items: " + e.getMessage());
        }
        
        return results;
    }

    public static Component getCustomItemDisplayName(ItemStack stack, org.bukkit.entity.Player player) {
        if (!enabled || stack == null) return null;
        
        // 1. Check our injected PDC first (Highest Priority for Tag Items)
        // This avoids the issue where wrapItem() wraps it as a vanilla item (returning vanilla name)
        // because the ItemStack might lack internal CE NBT data but has our injected PDC ID.
        String id = getCustomItemId(stack);
        if (id != null) {
            return getCustomItemDisplayName(id, player);
        }

        // 2. NEW API APPROACH (ItemManager.wrap -> hoverNameComponent)
        if (itemManager != null && wrapItemMethod != null && hoverNameComponentMethod != null) {
            try {
                Object wrappedItem = wrapItemMethod.invoke(itemManager, stack);
                if (wrappedItem != null) {
                    Object optionalComp = hoverNameComponentMethod.invoke(wrappedItem);
                    if (optionalComp instanceof java.util.Optional) {
                         java.util.Optional<?> opt = (java.util.Optional<?>) optionalComp;
                         if (opt.isPresent()) {
                             return (Component) opt.get();
                         }
                    }
                }
            } catch (Exception e) {
                SimpleCuisine.getInstance().debug("Error getting display name from stack: " + e.getMessage());
            }
        }
        
        return null;
    }

    public static Component getCustomItemDisplayName(String id, org.bukkit.entity.Player player) {
        if (!enabled || id == null) return null;
        
        // NEW API APPROACH (ItemManager.createWrappedItem -> hoverNameComponent)
        if (itemManager != null && createWrappedItemMethod != null && hoverNameComponentMethod != null) {
            try {
                Object key = keyOfMethod.invoke(null, id.contains(":") ? id : "craftengine:" + id);
                Object wrappedItem = null;
                // createWrappedItem(Key, Player)
                if (createWrappedItemMethod.getParameterCount() == 2) {
                    wrappedItem = createWrappedItemMethod.invoke(itemManager, key, player);
                } else {
                    wrappedItem = createWrappedItemMethod.invoke(itemManager, key);
                }

                if (wrappedItem != null) {
                    Object optionalComp = hoverNameComponentMethod.invoke(wrappedItem);
                    if (optionalComp instanceof java.util.Optional) {
                         java.util.Optional<?> opt = (java.util.Optional<?>) optionalComp;
                         if (opt.isPresent()) {
                             return (Component) opt.get();
                         }
                    }
                }
            } catch (Exception e) {
                 // Fall through
            }
        }

        try {
            Object key = keyOfMethod.invoke(null, id);
            Object customItem = byIdMethod.invoke(null, key);
            
            if (customItem != null) {
                // Try displayName/getName
                if (displayNameMethod != null) {
                    Object nameObj = displayNameMethod.invoke(customItem);
                    if (nameObj != null) {
                        if (nameObj instanceof Component) {
                            return (Component) nameObj;
                        } else if (nameObj instanceof String) {
                             // Check if it is a translation key
                             String nameStr = (String) nameObj;
                             if (translationManager != null && translateMethod != null) {
                                 // Try to translate it
                                 try {
                                     Object transResult = null;
                                     if (translateMethod.getParameterTypes()[0].equals(String.class)) {
                                         transResult = translateMethod.invoke(translationManager, nameStr);
                                     } else {
                                         // Try wrapping as key
                                         Object keyObj = keyOfMethod.invoke(null, nameStr.contains(":") ? nameStr : "craftengine:" + nameStr);
                                         transResult = translateMethod.invoke(translationManager, keyObj);
                                     }
                                     
                                     if (transResult != null) {
                                         if (transResult instanceof Component) return (Component) transResult;
                                         return Component.text(transResult.toString());
                                     }
                                 } catch (Exception e) {}
                             }
                             return Component.text(nameStr);
                        }
                    }
                }
                
                // Try translationKey fallback
                if (translationKeyMethod != null) {
                    String transKey = (String) translationKeyMethod.invoke(customItem);
                    if (transKey != null && translationManager != null && translateMethod != null) {
                         try {
                             Object transResult = null;
                             if (translateMethod.getParameterTypes()[0].equals(String.class)) {
                                 transResult = translateMethod.invoke(translationManager, transKey);
                             } else {
                                 Object keyObj = keyOfMethod.invoke(null, transKey.contains(":") ? transKey : "craftengine:" + transKey);
                                 transResult = translateMethod.invoke(translationManager, keyObj);
                             }
                             
                             if (transResult != null) {
                                 if (transResult instanceof Component) return (Component) transResult;
                                 return Component.text(transResult.toString());
                             }
                         } catch (Exception e) {}
                    }
                }
            }
        } catch (Exception e) {
            SimpleCuisine.getInstance().debug("Failed to get custom item display name for " + id + ": " + e.getMessage());
        }
        return null;
    }

    public static String getOffsetChar(int offset) {
        // Since we don't have a direct API method confirmed for offset string generation from pixels,
        // and config.yml defines them, it's safer to let user configure the exact char for now
        // OR return null to fallback to config-defined value.
        // If we find the API later, we can implement it.
        return null; 
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void clearCache() {
        itemCache.clear();
    }

    public static String getCustomItemId(ItemStack item) {
        if (!enabled || item == null) return null;
        try {
            // API: CraftEngineItems.getCustomItemId(item) -> Key
            Object key = getCustomItemIdMethod.invoke(null, item);
            if (key != null) {
                return key.toString(); // Key.toString() returns "namespace:value"
            }
            
            // Fallback: Check PersistentDataContainer
            // This handles cases where items are created for display (e.g. in tags) 
            // but miss the internal NBT data required by CraftEngine's API
            if (item.hasItemMeta()) {
                String cachedId = item.getItemMeta().getPersistentDataContainer().get(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING);
                if (cachedId != null) {
                    return cachedId;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getItemId(ItemStack item) {
        return getCustomItemId(item);
    }

    public static boolean isCustomBlock(org.bukkit.block.Block block) {
        if (!enabled || block == null || isCustomBlockMethod == null) return false;
        try {
            return (boolean) isCustomBlockMethod.invoke(null, block);
        } catch (Exception e) {
            com.example.simplecuisine.SimpleCuisine.getInstance().debug("Error checking if block is custom: " + e.getMessage());
            return false;
        }
    }

    public static String getCustomBlockProperty(org.bukkit.block.Block block, String propertyName) {
        if (!enabled || block == null || propertyName == null) return null;
        try {
            if (isCustomBlockMethod != null && (boolean) isCustomBlockMethod.invoke(null, block)) {
                Object blockState = getCustomBlockStateMethod.invoke(null, block);
                if (blockState != null) {
                    // Lazy load method if null
                    if (blockStatePropertyMethod == null) {
                        try {
                            blockStatePropertyMethod = blockState.getClass().getMethod("getPropertiesAsString");
                        } catch (Exception e) {
                             // ignore
                        }
                    }

                    if (blockStatePropertyMethod != null) {
                        // Check if it's getPropertiesAsString (no args) or property (String arg)
                        if (blockStatePropertyMethod.getParameterCount() == 0) {
                            String props = (String) blockStatePropertyMethod.invoke(blockState);
                            // Parse "key=value,key2=value2"
                            if (props != null && !props.isEmpty()) {
                                String[] entries = props.split(",");
                                for (String entry : entries) {
                                    String[] kv = entry.split("=");
                                    if (kv.length == 2 && kv[0].trim().equals(propertyName)) {
                                        return kv[1].trim();
                                    }
                                }
                            }
                        } else {
                            Object value = blockStatePropertyMethod.invoke(blockState, propertyName);
                            return value != null ? value.toString() : null;
                        }
                    } else {
                        com.example.simplecuisine.SimpleCuisine.getInstance().debug("blockStatePropertyMethod is null when getting property: " + propertyName);
                    }
                }
            }
        } catch (Exception e) {
            com.example.simplecuisine.SimpleCuisine.getInstance().debug("Error getting custom block property: " + e.getMessage());
        }
        return null;
    }

    public static boolean setBlock(org.bukkit.Location loc, String key) {
        return setBlock(loc, key, null);
    }

    public static boolean setBlock(org.bukkit.Location loc, String keyStr, Map<String, Object> properties) {
        if (!enabled) return false;
        
        // Convert Key
        Object key = null;
        try {
            key = keyOfMethod.invoke(null, keyStr.contains(":") ? keyStr : "craftengine:" + keyStr);
        } catch (Exception e) {
            com.example.simplecuisine.SimpleCuisine.getInstance().getLogger().warning("Invalid key format: " + keyStr);
            return false;
        }

        // 1. Try using place() method (New API)
        if (placeMethodWithTag != null || placeMethodNoTag != null) {
            try {
                if (properties != null && !properties.isEmpty() && placeMethodWithTag != null && compoundTagClass != null) {
                    // Create CompoundTag
                    Object tag = compoundTagClass.getConstructor().newInstance();
                    
                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        if (entry.getValue() instanceof Integer) {
                            if (compoundTagPutIntMethod != null) {
                                compoundTagPutIntMethod.invoke(tag, entry.getKey(), (int) entry.getValue());
                            }
                        } else if (entry.getValue() instanceof Boolean) {
                            if (compoundTagPutByteMethod != null) {
                                byte b = (Boolean) entry.getValue() ? (byte) 1 : (byte) 0;
                                compoundTagPutByteMethod.invoke(tag, entry.getKey(), b);
                            }
                        } else {
                            if (compoundTagPutStringMethod != null) {
                                compoundTagPutStringMethod.invoke(tag, entry.getKey(), entry.getValue().toString());
                            }
                        }
                    }
                    
                    // place(Location, Key, CompoundTag, boolean update)
                    placeMethodWithTag.invoke(null, loc, key, tag, true);
                    return true;
                } else if (placeMethodNoTag != null) {
                    // place(Location, Key, boolean update)
                    placeMethodNoTag.invoke(null, loc, key, true);
                    return true;
                }
            } catch (Exception e) {
                com.example.simplecuisine.SimpleCuisine.getInstance().getLogger().warning("Failed to place block using place(): " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        // 2. Fallback: Try Static setBlock Method (Old API)
        if (setBlockMethod != null) {
            try {
                if (setBlockMethod.getParameterCount() == 3) {
                    setBlockMethod.invoke(null, loc, keyStr, properties);
                    return true;
                } else if (setBlockMethod.getParameterCount() == 2) {
                    setBlockMethod.invoke(null, loc, keyStr);
                    return true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        // 3. Fallback: Try Instance Method (BlockManager)
        if (blockManager != null && blockManagerSetBlockMethod != null) {
            try {
                // Convert String to Key if needed for BlockManager too
                Object finalKey = key; // Use the key object created above
                
                Class<?>[] params = blockManagerSetBlockMethod.getParameterTypes();
                // Check if it expects String instead of Key (unlikely but possible in old versions)
                if (params.length > 1 && params[1].equals(String.class)) {
                     // finalKey = keyStr; // It's already Key object, but if method wants String, we'd use keyStr
                     // But usually it wants Key
                }
                
                if (params.length == 3) {
                    blockManagerSetBlockMethod.invoke(blockManager, loc, finalKey, properties);
                    return true;
                } else if (params.length == 2) {
                    blockManagerSetBlockMethod.invoke(blockManager, loc, finalKey);
                    return true;
                }
            } catch (Exception e) {
                com.example.simplecuisine.SimpleCuisine.getInstance().getLogger().warning("BlockManager.setBlock failed: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            // Only log if ALL failed
            if (setBlockMethod == null && placeMethodWithTag == null && placeMethodNoTag == null) {
                com.example.simplecuisine.SimpleCuisine.getInstance().getLogger().warning("setBlock failed: No valid placement method found (place, setBlock, or BlockManager)");
            }
        }
        
        return false;
    }

    /**
     * Creates a custom item Stack by ID.
     */
    public static ItemStack createItem(String id, org.bukkit.entity.Player player) {
        if (!enabled || id == null) return null;
        try {
            Object key = keyOfMethod.invoke(null, id);
            Object customItem = byIdMethod.invoke(null, key);
            
            if (customItem != null) {
                // Try context-aware build first
                if (player != null && contextOfPlayerMethod != null && buildItemStackMethod != null) {
                    try {
                        Object context = contextOfPlayerMethod.invoke(null, player);
                        Object newItem = buildItemStackMethod.invoke(customItem, context);
                        if (newItem instanceof ItemStack) {
                            return (ItemStack) newItem;
                        }
                    } catch (Exception e) {
                        // Fallback
                    }
                }
                
                // Fallback to no-arg build
                if (buildItemStackNoArgMethod != null) {
                    Object newItem = buildItemStackNoArgMethod.invoke(customItem);
                    if (newItem instanceof ItemStack) {
                        return (ItemStack) newItem;
                    }
                }
            }
        } catch (Exception e) {
            com.example.simplecuisine.SimpleCuisine.getInstance().debug("Error creating custom item " + id + ": " + e.getMessage());
        }
        return null;
    }

    public static String getCustomBlockId(org.bukkit.block.Block block) {
        if (!enabled || block == null) return null;
        
        try {
            // 1. Try Block API
            if (isCustomBlockMethod != null && (boolean) isCustomBlockMethod.invoke(null, block)) {
                Object blockState = getCustomBlockStateMethod.invoke(null, block);
                if (blockState != null) {
                    if (blockStateIdMethod != null) {
                        try {
                            Object key = blockStateIdMethod.invoke(blockState);
                            return key.toString();
                        } catch (Exception e) {}
                    } 
                    
                    // Fallback: Try to use cached owner method chain
                    if (blockStateOwnerMethod != null && ownerValueMethod != null && ownerIdMethod != null) {
                        try {
                            Object owner = blockStateOwnerMethod.invoke(blockState);
                            if (owner != null) {
                                Object value = ownerValueMethod.invoke(owner);
                                if (value != null) {
                                    Object key = ownerIdMethod.invoke(value);
                                    return key.toString();
                                }
                            }
                        } catch (Exception e) {}
                    }
 
                    // Deep Fallback: Reflection on instance if cached methods failed
                    // This handles cases where the static type (interface) didn't reveal the methods
                    // but the runtime object (ImmutableBlockState) has them.
                    try {
                        Method ownerMethod = blockState.getClass().getMethod("owner");
                        Object owner = ownerMethod.invoke(blockState);
                        if (owner != null) {
                            // Try value() or get()
                            Method valueMethod = null;
                            try { valueMethod = owner.getClass().getMethod("value"); } catch(Exception e) {}
                            if (valueMethod == null) try { valueMethod = owner.getClass().getMethod("get"); } catch(Exception e) {}
                            
                            if (valueMethod != null) {
                                Object value = valueMethod.invoke(owner);
                                if (value != null) {
                                    Method idMethod = value.getClass().getMethod("id");
                                    Object key = idMethod.invoke(value);
                                    return key.toString();
                                }
                            }
                        }
                    } catch (Exception ex) {
                        // ignore deep fallback errors
                    }
                }
            }

            // 2. Try Furniture API (if block check failed or returned null)
            // Some "blocks" might be purely furniture entities in CE
            if (getLoadedFurnitureByColliderMethod != null) {
                // Check entities at the block center
                Location center = block.getLocation().toCenterLocation();
                for (org.bukkit.entity.Entity entity : center.getWorld().getNearbyEntities(center, 0.5, 0.5, 0.5)) {
                    Object furniture = getLoadedFurnitureByColliderMethod.invoke(null, entity);
                    if (furniture != null) {
                         if (furnitureIdMethod != null) {
                             Object key = furnitureIdMethod.invoke(furniture);
                             return key.toString();
                         }
                    }
                }
            }
        } catch (Exception e) {
            // Suppress spam, but log if needed
        }
        return null;
    }

    /**
     * Retrieves the list of Custom Items associated with a tag without requiring a player.
     * Uses customItemIdsByTag to ensure we get custom items, then builds them using getItem(id).
     */
    public static java.util.List<ItemStack> getTagCustomItemsNoPlayer(String tag) {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        if (!enabled || tagManager == null) return items;

        try {
            // Strip # if present
            if (tag.startsWith("#")) tag = tag.substring(1);
            
            Object key = keyOfMethod.invoke(null, tag.contains(":") ? tag : "minecraft:" + tag);
            
            // Priority 1: Use customItemIdsByTag
            if (getCustomTagItemsMethod != null) {
                java.util.List<?> uniqueKeys = (java.util.List<?>) getCustomTagItemsMethod.invoke(tagManager, key);
                
                if (uniqueKeys != null) {
                    for (Object uniqueKey : uniqueKeys) {
                        try {
                            // Extract Key
                            Object itemKey = uniqueKey;
                            try {
                                Method keyMethod = uniqueKey.getClass().getMethod("key");
                                itemKey = keyMethod.invoke(uniqueKey);
                            } catch (Exception e) {
                                // Maybe it is already a Key
                            }
                            
                            String id = itemKey.toString();
                            ItemStack item = getItem(id);
                            if (item != null) {
                                items.add(item);
                            }
                        } catch (Exception e) {
                            SimpleCuisine.getInstance().debug("Error processing tag key: " + e.getMessage());
                        }
                    }
                }
            } else {
                // Fallback to standard getTagItems if custom method not available
                return getTagItems(tag);
            }
        } catch (Exception e) {
            SimpleCuisine.getInstance().debug("Error resolving tag '" + tag + "' (NoPlayer): " + e.getMessage());
        }
        return items;
    }



    public static java.util.List<ItemStack> getTagItems(String tag) {
        if (!enabled || tagManager == null || getTagItemsMethod == null) return new java.util.ArrayList<>();
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        try {
            // Strip # if present
            if (tag.startsWith("#")) tag = tag.substring(1);

            // Try multiple key formats
            Object tagKey = null;
            try {
                if (tag.contains(":")) {
                    tagKey = keyOfMethod.invoke(null, tag);
                } else {
                    tagKey = keyOfMethod.invoke(null, "minecraft:" + tag);
                }
            } catch (Exception e) {
                // Ignore key creation error
            }

            if (tagKey == null) return items;

            // Invoke itemIdsByTag(Key) -> List<Key>
            java.util.List<?> keyList = (java.util.List<?>) getTagItemsMethod.invoke(tagManager, tagKey);
            
            if (keyList != null) {
                SimpleCuisine.getInstance().debug("CE Hook: Tag " + tag + " has " + keyList.size() + " entries.");
                for (Object k : keyList) {
                    String id = k.toString(); // "namespace:value"
                    SimpleCuisine.getInstance().debug("  -> Processing ID: " + id);
                    
                    // 1. Try as Custom Item
                    ItemStack item = getItem(id);
                    if (item != null) {
                        SimpleCuisine.getInstance().debug("     -> Resolved as Custom Item");
                        // Re-apply localization to ensure it's correct for display (e.g. using zh_CN fallback)
                        // This fixes the issue where cached items might lack localization
                        applySmartLocalization(item, k, null);
                        items.add(item);
                        continue;
                    } else {
                        SimpleCuisine.getInstance().debug("     -> Failed to resolve as Custom Item");
                    }
                    
                    // 2. Try as Vanilla Material
                    if (id.startsWith("minecraft:")) {
                        String matName = id.substring(10).toUpperCase();
                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName);
                        if (mat != null) {
                            items.add(new ItemStack(mat));
                        }
                    }
                }
            }
        } catch (Exception e) {
            SimpleCuisine.getInstance().debug("Error resolving tag '" + tag + "': " + e.getMessage());
            e.printStackTrace();
        }
        return items;
    }

    // Debug helper to dump methods once
    private static boolean debugMethodsDumped = false;

    public static ItemStack getItem(String id) {
        if (!enabled || id == null) return null;
        
        if (itemCache.containsKey(id)) {
            return itemCache.get(id).clone();
        }

        try {
            // Handle ID format: Ensure it's a valid Key string
            Object key;
            try {
                key = keyOfMethod.invoke(null, id);
            } catch (Exception e) {
                // If simple ID provided, try adding default namespaces
                // Common convention might be "craftengine:id" or "default:id"
                try {
                    key = keyOfMethod.invoke(null, "craftengine:" + id);
                } catch (Exception e2) {
                     SimpleCuisine.getInstance().debug("Invalid CraftEngine ID format: " + id);
                     return null;
                }
            }

            // API: CraftEngineItems.byId(Key) -> CustomItem
            Object customItem = byIdMethod.invoke(null, key);
            if (customItem != null) {
                
                // DEBUG: Dump relevant methods of CustomItem
                if (!debugMethodsDumped) {
                    SimpleCuisine.getInstance().getLogger().info("[SimpleCuisine] DEBUG: Analyzing CustomItem: " + customItem.getClass().getName());
                    for (Method m : customItem.getClass().getMethods()) {
                        String name = m.getName().toLowerCase();
                        if (m.getParameterCount() == 0 && (name.contains("name") || name.contains("display") || name.contains("lang"))) {
                            SimpleCuisine.getInstance().getLogger().info("  Method: " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                            // Try to invoke it
                            try {
                                Object result = m.invoke(customItem);
                                SimpleCuisine.getInstance().getLogger().info("    Result: " + result);
                            } catch (Exception e) {
                                SimpleCuisine.getInstance().getLogger().info("    Invoke Error: " + e.getMessage());
                            }
                        }
                    }
                    debugMethodsDumped = true;
                }
                
                ItemStack item = null;

                // Try to use ItemBrowserManager first (as it likely produces "display-ready" items)
                if (itemBrowserManager != null && browserGetItemMethod != null) {
                    try {
                        Class<?> paramType = browserGetItemMethod.getParameterTypes()[0];
                        Object arg = null;
                        if (paramType.equals(String.class)) {
                            arg = id;
                        } else if (paramType.isAssignableFrom(keyClass)) {
                            arg = key;
                        } else if (paramType.isAssignableFrom(customItem.getClass())) {
                            arg = customItem;
                        }
                        
                        if (arg != null) {
                            item = (ItemStack) browserGetItemMethod.invoke(itemBrowserManager, arg);
                        }
                    } catch (Exception e) {
                        Bukkit.getLogger().warning("[SimpleCuisine] Failed to get item from BrowserManager: " + e.getMessage());
                    }
                }

                if (item == null) {
                    // Try no-arg build first if available
                    if (buildItemStackNoArgMethod != null) {
                        try {
                            item = (ItemStack) buildItemStackNoArgMethod.invoke(customItem);
                        } catch (Exception e) {
                            // Fallback
                        }
                    }

                    // If failed or null/air, try with empty context
                    if ((item == null || item.getType().isAir()) && buildItemStackMethod != null) {
                        Object context = emptyContextMethod.invoke(null);
                        item = (ItemStack) buildItemStackMethod.invoke(customItem, context);
                    }
                }
                
                if (item != null && !item.getType().isAir()) {
                    // Inject ID into PDC for fallback retrieval
                    org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(KEY_CE_ID, org.bukkit.persistence.PersistentDataType.STRING, id);
                        item.setItemMeta(meta);
                    }
                    
                    // Try to fix missing display name
                    
                    // DEBUG: Log initial name
                     if (!debugMethodsDumped) {
                          if (meta != null && meta.hasDisplayName()) {
                              SimpleCuisine.getInstance().getLogger().info("[SimpleCuisine] DEBUG: Initial Item Name: " + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(meta.displayName()));
                          } else {
                              SimpleCuisine.getInstance().getLogger().info("[SimpleCuisine] DEBUG: Initial Item Name: <none>");
                          }
                     }

                    if (meta != null) {
                        // 1. Try to get name from CustomItem method (e.g. displayName())
                        if (displayNameMethod != null && !meta.hasDisplayName()) {
                            try {
                                Object nameObj = displayNameMethod.invoke(customItem);
                                if (nameObj != null) {
                                    if (nameObj instanceof Component) {
                                        meta.displayName((Component) nameObj);
                                    } else {
                                        meta.displayName(Component.text(nameObj.toString()));
                                    }
                                }
                            } catch (Exception e) {
                                // Ignore
                            }
                            item.setItemMeta(meta);
                        }
                        
                        // 2. Apply Smart Localization (TranslationManager -> Chinese Check -> Clear for Client Loc)
                        applySmartLocalization(item, key, null);
                    }

                    itemCache.put(id, item.clone());
                    return item;
                } else {
                     // SimpleCuisine.getInstance().debug("CraftEngine item built as AIR for ID: " + id);
                }
            } else {
                // SimpleCuisine.getInstance().debug("CraftEngine item not found for ID: " + id);
            }
        } catch (Exception e) {
             SimpleCuisine.getInstance().debug("Failed to get CraftEngine item '" + id + "': " + e.getMessage());
             e.printStackTrace();
        }
        
        return null;
    }



    // Deprecated debug methods - kept empty to prevent compilation errors if called
    public static void dumpRegisteredItems(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text("§c[SimpleCuisine] Item dumping is disabled in API mode."));
    }

    public static void analyzeBlockState(org.bukkit.block.Block block) {
        if (!enabled || block == null) return;
        try {
            if (isCustomBlockMethod != null && (boolean) isCustomBlockMethod.invoke(null, block)) {
                Object blockState = getCustomBlockStateMethod.invoke(null, block);
                if (blockState != null) {
                    SimpleCuisine.getInstance().getLogger().info("Analysis - BlockState Class: " + blockState.getClass().getName());
                    SimpleCuisine.getInstance().getLogger().info("Analysis - Methods:");
                    for (Method m : blockState.getClass().getMethods()) {
                         if (m.getParameterCount() == 0 && m.getReturnType() != void.class) {
                             SimpleCuisine.getInstance().getLogger().info("  " + m.getName() + "() -> " + m.getReturnType().getName());
                         }
                    }

                    // Deep analysis of owner()
                    try {
                        Method ownerMethod = blockState.getClass().getMethod("owner");
                        Object owner = ownerMethod.invoke(blockState);
                        if (owner != null) {
                            SimpleCuisine.getInstance().getLogger().info("Analysis - Owner Class: " + owner.getClass().getName());
                            for (Method m : owner.getClass().getMethods()) {
                                if (m.getParameterCount() == 0) {
                                    SimpleCuisine.getInstance().getLogger().info("  Owner." + m.getName() + "() -> " + m.getReturnType().getName());
                                }
                            }
                            // Try to find value() or get()
                            Method valueMethod = null;
                            try { valueMethod = owner.getClass().getMethod("value"); } catch(Exception e) {}
                            if (valueMethod == null) try { valueMethod = owner.getClass().getMethod("get"); } catch(Exception e) {}
                            
                            if (valueMethod != null) {
                                Object value = valueMethod.invoke(owner);
                                if (value != null) {
                                    SimpleCuisine.getInstance().getLogger().info("Analysis - Owner Value Class: " + value.getClass().getName());
                                    // Check for id()
                                    try {
                                        Method idMethod = value.getClass().getMethod("id");
                                        Object id = idMethod.invoke(value);
                                        SimpleCuisine.getInstance().getLogger().info("Analysis - Owner Value ID: " + id);
                                    } catch (Exception e) {
                                        SimpleCuisine.getInstance().getLogger().info("Analysis - Owner Value has no id() method");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        SimpleCuisine.getInstance().getLogger().info("Analysis - Failed to inspect owner: " + e.getMessage());
                    }

                    // Deep analysis of propertyEntries()
                    try {
                        Method entriesMethod = blockState.getClass().getMethod("propertyEntries");
                        Map<?, ?> map = (Map<?, ?>) entriesMethod.invoke(blockState);
                        SimpleCuisine.getInstance().getLogger().info("Analysis - PropertyEntries: " + map);
                        if (map != null && !map.isEmpty()) {
                            Map.Entry<?, ?> entry = map.entrySet().iterator().next();
                            SimpleCuisine.getInstance().getLogger().info("Analysis - Entry Key Type: " + entry.getKey().getClass().getName());
                            SimpleCuisine.getInstance().getLogger().info("Analysis - Entry Value Type: " + entry.getValue().getClass().getName());
                        }
                    } catch (Exception e) {
                         SimpleCuisine.getInstance().getLogger().info("Analysis - Failed to inspect propertyEntries: " + e.getMessage());
                    }

                    // Check propertiesAsString
                    try {
                        Method propStringMethod = blockState.getClass().getMethod("getPropertiesAsString");
                        String propString = (String) propStringMethod.invoke(blockState);
                        SimpleCuisine.getInstance().getLogger().info("Analysis - getPropertiesAsString: " + propString);
                    } catch (Exception e) {
                        // ignore
                    }

                } else {
                    SimpleCuisine.getInstance().getLogger().info("Analysis - BlockState is null");
                }
            } else {
                SimpleCuisine.getInstance().getLogger().info("Analysis - Not a custom block");
            }
        } catch (Exception e) {
            SimpleCuisine.getInstance().getLogger().info("Analysis Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("deprecation")
    private static void applySmartLocalization(ItemStack item, Object key, org.bukkit.entity.Player player) {
        if (item == null) return;
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        // Config Loader Hook: Force use of configured item-name
        String keyStr = key.toString();
        String rawName = CraftEngineConfigLoader.getRawItemName(keyStr);
        if (rawName != null) {
            try {
                // Try MiniMessage first
                meta.displayName(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(rawName));
                item.setItemMeta(meta);
                // Also store in PDC for persistence if needed
                return;
            } catch (Exception e) {
                // Fallback to plain text
                meta.displayName(Component.text(CraftEngineConfigLoader.getCleanItemName(keyStr)));
                item.setItemMeta(meta);
                return;
            }
        }

        Component transComponent = null;
        String transNameStr = null;
        
        // Check for overridden translation key in PDC
        String pdcTransKey = meta.getPersistentDataContainer().get(KEY_CE_TRANS_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        Object keyToUse = (pdcTransKey != null) ? pdcTransKey : key;
        
        SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Processing Item. Key: " + key + ", KeyToUse: " + keyToUse);

        // Try to resolve proper Translation Key OR Display Name from CustomItem if keyToUse is a Key/ID
        if (byIdMethod != null) {
             try {
                 // Convert string key to Key object if necessary
                 Object keyObj = keyToUse;
                 if (keyToUse instanceof String && keyOfMethod != null) {
                     try {
                         keyObj = keyOfMethod.invoke(null, (String)keyToUse);
                     } catch (Exception e) {
                         SimpleCuisine.getInstance().getLogger().warning("[SmartLoc] Error converting string to Key: " + e.getMessage());
                     }
                 }
                 
                 Object customItem = byIdMethod.invoke(null, keyObj);
                 if (customItem != null) {
                    // 1. Try to get direct Display Name (hoverNameComponent) - fixes hardcoded item-name
                    // We use dynamic reflection here to be safe against class hierarchy issues
                    try {
                        Method hoverMethod = null;
                        try {
                            hoverMethod = customItem.getClass().getMethod("hoverNameComponent");
                        } catch (NoSuchMethodException e) {
                            // Try alternatives
                            try {
                                hoverMethod = customItem.getClass().getMethod("displayName");
                            } catch (NoSuchMethodException e2) {
                                try {
                                    hoverMethod = customItem.getClass().getMethod("getName");
                                } catch (NoSuchMethodException e3) {
                                    // Give up
                                }
                            }
                        }

                        if (hoverMethod != null) {
                            Object hoverComp = hoverMethod.invoke(customItem);
                            if (hoverComp != null) {
                                if (hoverComp instanceof Component) {
                                    transComponent = (Component) hoverComp;
                                    transNameStr = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(transComponent);
                                } else {
                                    transNameStr = hoverComp.toString();
                                    transComponent = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(transNameStr);
                                }
                                if (transNameStr != null) {
                                    SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Found Name via " + hoverMethod.getName() + ": " + transNameStr);
                                }
                            }
                        }
                    } catch (Exception e) {
                        SimpleCuisine.getInstance().getLogger().warning("[SmartLoc] Error invoking name method: " + e.getMessage());
                    }

                    // 2. Try to get Translation Key (only if PDC didn't override it)
                     if (pdcTransKey == null && translationKeyMethod != null) {
                         String tKey = (String) translationKeyMethod.invoke(customItem);
                         if (tKey != null) {
                             keyToUse = tKey;
                         }
                     }
                 }
             } catch (Exception e) {
                 // Ignore, fallback to ID
             }
        }

        // 1. Try TranslationManager (If we haven't found a hardcoded name yet, or if we want to try translating anyway)
        // Note: Even if we found a name via hoverNameComponent, TranslationManager might offer a better localized version.
        // However, if TranslationManager fails (returns ID or key), we should fall back to hoverNameComponent result.
        
        Component tmComponent = null;
        String tmNameStr = null;

        if (translationManager != null) {
            try {
                Object translated = null;
                // Try with locale first
                if (translateWithLocaleMethod != null && player != null) {
                    try {
                        translated = translateWithLocaleMethod.invoke(translationManager, keyToUse, player.getLocale());
                    } catch (Exception e) {}
                }
                
                // Check if we got a valid translation (Non-ASCII implies localized, e.g. Chinese)
                boolean currentIsLocalized = false;
                if (translated != null) {
                    String str = (translated instanceof Component) ? 
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize((Component)translated) : 
                        translated.toString();
                    if (!isAscii(str)) {
                        currentIsLocalized = true;
                    }
                }

                // Fallback to "zh_CN" if:
                // 1. No translation found yet
                // 2. Found translation is ASCII (likely English) but we might want Chinese
                if (translated == null || !currentIsLocalized) {
                    if (translateWithLocaleMethod != null) {
                        try {
                            // Try zh_CN explicitly
                            Object cnTranslated = translateWithLocaleMethod.invoke(translationManager, keyToUse, "zh_CN");
                            if (cnTranslated != null) {
                                String cnStr = (cnTranslated instanceof Component) ? 
                                    net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize((Component)cnTranslated) : 
                                    cnTranslated.toString();
                                
                                // Only switch to CN if it actually provides a non-ASCII name (presumed better)
                                if (!isAscii(cnStr)) {
                                    translated = cnTranslated;
                                } else if (translated == null) {
                                    // If we had nothing, take the CN result even if ASCII
                                    translated = cnTranslated;
                                }
                            }
                        } catch (Exception e) {
                             SimpleCuisine.getInstance().debug("Failed to translate with zh_CN: " + e.getMessage());
                        }
                    }
                }
                
                // Fallback to default translate
                if (translated == null && translateMethod != null) {
                    translated = translateMethod.invoke(translationManager, keyToUse);
                }

                if (translated != null) {
                    if (translated instanceof Component) {
                        tmComponent = (Component) translated;
                        tmNameStr = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(tmComponent);
                    } else if (translated instanceof String) {
                        tmNameStr = (String) translated;
                        tmComponent = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(tmNameStr);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }

        // Merge logic: Decide between Hardcoded (transNameStr) and Translated (tmNameStr)
        if (tmNameStr != null) {
             // If we have a hardcoded name, only overwrite it if translation provides something "better"
             if (transNameStr != null) {
                 if (!isAscii(tmNameStr) && isAscii(transNameStr)) {
                     // Translation is localized (Chinese), hardcode is not (English) -> Use Translation
                     transNameStr = tmNameStr;
                     transComponent = tmComponent;
                 } else if (tmNameStr.contains(":") || tmNameStr.contains(".")) {
                     // Translation looks like a key/ID -> Keep Hardcoded
                 } else {
                     // Both are similar quality. Prefer TranslationManager as source of truth.
                     transNameStr = tmNameStr;
                     transComponent = tmComponent;
                 }
             } else {
                 // No hardcoded name -> Use Translation
                 transNameStr = tmNameStr;
                 transComponent = tmComponent;
             }
        }

        // 2. Check existing name on ItemStack
        Component origComponent = meta.hasDisplayName() ? meta.displayName() : null;
        String origNameStr = (origComponent != null) ? net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(origComponent) : null;

        boolean hasChineseTranslation = transNameStr != null && !isAscii(transNameStr);
        boolean hasChineseOriginal = origNameStr != null && !isAscii(origNameStr);
        
        // DEBUG LOGGING
        SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Trans: " + transNameStr + " (HasCN: " + hasChineseTranslation + ")");
        SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Orig: " + origNameStr + " (HasCN: " + hasChineseOriginal + ")");

        if (hasChineseTranslation) {
            // Priority 1: TranslationManager has Chinese
            meta.displayName(transComponent);
            SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Applied: Translation");
        } else if (hasChineseOriginal) {
            // Priority 2: Original Item has Chinese
            meta.displayName(origComponent);
            SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Applied: Original");
        } else {
            // Priority 3: All English -> 
            // PREVIOUSLY: Remove DisplayName to trigger Client-side Localization (Resource Pack)
            // FIXED: Do NOT remove display name if we have a translation (even English)
            // This ensures RecipeMenuManager gets a valid name (even if English) instead of falling back to ID.
            
            if (transComponent != null) {
                meta.displayName(transComponent);
                SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Applied: Translation (English)");
            } else {
                // Keep original (English) name if present
                SimpleCuisine.getInstance().getLogger().info("[SmartLoc] Applied: Original (English/Fallback)");
            }
        }

        item.setItemMeta(meta);
    }
}
