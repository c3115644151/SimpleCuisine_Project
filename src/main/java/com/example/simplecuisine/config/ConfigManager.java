package com.example.simplecuisine.config;

import com.example.simplecuisine.SimpleCuisine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

public class ConfigManager {

    private static SimpleCuisine plugin;
    private static FileConfiguration config;
    private static File configFile;

    public static void init(SimpleCuisine instance) {
        plugin = instance;
        configFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!configFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        reload();
    }

    public static void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        
        // Load defaults from jar
        java.io.InputStream defConfigStream = plugin.getResource("messages.yml");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(defConfigStream, java.nio.charset.StandardCharsets.UTF_8));
            config.setDefaults(defConfig);
        }
        
        // Copy defaults to config if missing
        config.options().copyDefaults(true);
        
        // Save automatically to update the file on disk with new keys
        try {
            config.save(configFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("Could not save messages.yml!");
            e.printStackTrace();
        }
    }

    public static String getRawString(String key) {
        return config.getString(key, key);
    }

    public static Component getMessage(String key) {
        String prefix = config.getString("prefix", "");
        String msg = config.getString("messages." + key);
        if (msg == null) return Component.text(key);
        return MiniMessage.miniMessage().deserialize(prefix + msg);
    }

    public static Component getGuiText(String key) {
        String msg = config.getString("gui." + key);
        if (msg == null) return Component.text(key);
        return MiniMessage.miniMessage().deserialize(msg);
    }
    
    public static Component getGuiText(String key, String... placeholders) {
        String msg = config.getString("gui." + key);
        if (msg == null) return Component.text(key);
        
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                msg = msg.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        return MiniMessage.miniMessage().deserialize(msg);
    }

    public static List<Component> getGuiList(String key) {
        List<String> list = config.getStringList("gui." + key);
        return list.stream()
                .map(s -> MiniMessage.miniMessage().deserialize(s))
                .collect(Collectors.toList());
    }

    public static Component getItemText(String key) {
        String msg = config.getString("items." + key);
        if (msg == null) return Component.text(key);
        return MiniMessage.miniMessage().deserialize(msg);
    }

    public static List<Component> getItemLore(String key) {
        List<String> list = config.getStringList("items." + key + ".lore");
        return list.stream()
                .map(s -> MiniMessage.miniMessage().deserialize(s))
                .collect(Collectors.toList());
    }

    public static Component getMessage(String key, String... placeholders) {
        String prefix = config.getString("prefix", "");
        String msg = config.getString("messages." + key);
        if (msg == null) return Component.text(key);
        
        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                msg = msg.replace(placeholders[i], placeholders[i + 1]);
            }
        }
        return MiniMessage.miniMessage().deserialize(prefix + msg);
    }
}
