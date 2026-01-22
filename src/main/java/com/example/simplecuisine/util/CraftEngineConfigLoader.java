package com.example.simplecuisine.util;

import com.example.simplecuisine.SimpleCuisine;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class CraftEngineConfigLoader {

    private static final Map<String, String> NAME_CACHE = new HashMap<>();
    private static boolean loaded = false;
    private static boolean failed = false;

    public static void load() {
        if (loaded || failed) return;
        
        Plugin cePlugin = Bukkit.getPluginManager().getPlugin("CraftEngine");
        
        if (cePlugin == null) {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getDataFolder() != null && p.getDataFolder().getName().equals("CraftEngine")) {
                    cePlugin = p;
                    SimpleCuisine.getInstance().getLogger().info("[ConfigLoader] Found CraftEngine via folder match: " + p.getName());
                    break;
                }
            }
        }
        
        if (cePlugin == null) {
            SimpleCuisine.getInstance().getLogger().warning("[ConfigLoader] CraftEngine plugin not found.");
            failed = true;
            return;
        }

        File resourcesDir = new File(cePlugin.getDataFolder(), "resources");
        SimpleCuisine.getInstance().getLogger().info("[ConfigLoader] Loading from: " + resourcesDir.getAbsolutePath());
        
        if (!resourcesDir.exists()) {
            SimpleCuisine.getInstance().getLogger().warning("[ConfigLoader] Resources directory not found!");
            failed = true;
            return;
        }

        int count = loadRecursively(resourcesDir);
        SimpleCuisine.getInstance().getLogger().info("[ConfigLoader] Loaded " + count + " item names.");
        
        if (count > 0) {
            int i = 0;
            for (String k : NAME_CACHE.keySet()) {
                if (i++ < 3) SimpleCuisine.getInstance().getLogger().info("[ConfigLoader] Cached: " + k + " = " + NAME_CACHE.get(k));
            }
        }
        
        loaded = true;
    }

    private static int loadRecursively(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                count += loadRecursively(file);
            } else if (file.getName().endsWith(".yml")) {
                count += loadFile(file);
            }
        }
        return count;
    }

    private static int loadFile(File file) {
        int count = 0;
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            
            for (String key : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(key);
                if (section == null) continue;

                // Check if this section is the item itself
                if (section.contains("data.item-name")) {
                    String itemName = section.getString("data.item-name");
                    if (itemName != null && !itemName.isEmpty()) {
                        NAME_CACHE.put(key, itemName);
                        count++;
                    }
                } 
                // Otherwise, check if it contains items (e.g. "items" section)
                else {
                    for (String subKey : section.getKeys(false)) {
                        ConfigurationSection subSection = section.getConfigurationSection(subKey);
                        if (subSection != null && subSection.contains("data.item-name")) {
                            String itemName = subSection.getString("data.item-name");
                            if (itemName != null && !itemName.isEmpty()) {
                                NAME_CACHE.put(subKey, itemName);
                                count++;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            SimpleCuisine.getInstance().getLogger().warning("[ConfigLoader] Error reading " + file.getName() + ": " + e.getMessage());
        }
        return count;
    }

    public static String getRawItemName(String key) {
        if (!loaded) load();
        
        String val = NAME_CACHE.get(key);
        if (val != null) return val;
        
        if (key.contains(":")) {
            String[] parts = key.split(":", 2);
            if (parts.length > 1) {
                return NAME_CACHE.get(parts[1]);
            }
        }
        
        return null;
    }
    
    public static String getCleanItemName(String key) {
        String raw = getRawItemName(key);
        if (raw == null) return null;
        return raw.replaceAll("<[^>]+>", "");
    }
}
