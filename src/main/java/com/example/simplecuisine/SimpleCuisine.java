package com.example.simplecuisine;

import com.example.simplecuisine.cooking.CookingManager;
import com.example.simplecuisine.cooking.CookingPotListener;
import com.example.simplecuisine.cooking.CookingPotManager;
import com.example.simplecuisine.item.ItemManager;
import com.example.simplecuisine.listener.StoveListener;
import com.example.simplecuisine.util.CraftEngineHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleCuisine extends JavaPlugin {

    private static SimpleCuisine instance;
    private ItemManager itemManager;
    private CookingPotManager cookingPotManager;
    private CookingManager cookingManager;
    private com.example.simplecuisine.cooking.StoveManager stoveManager;
    private com.example.simplecuisine.cooking.SkilletManager skilletManager;
    private com.example.simplecuisine.cooking.CuttingBoardManager cuttingBoardManager;
    private com.example.simplecuisine.menu.RecipeMenuManager recipeMenuManager;
    private com.example.simplecuisine.farming.RiceManager riceManager;
    private final java.util.Set<java.util.UUID> debuggers = new java.util.HashSet<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        com.example.simplecuisine.config.ConfigManager.init(this);
        
        // Initialize hooks
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            try {
                CraftEngineHook.init();
                getLogger().info("CraftEngine hooked successfully!");
            } catch (Throwable t) {
                getLogger().warning("Failed to hook CraftEngine: " + t.getMessage());
            }
        }
        
        // Save default stove recipes
        if (!new java.io.File(getDataFolder(), "stove_recipes.yml").exists()) {
            saveResource("stove_recipes.yml", false);
        }

        this.itemManager = new ItemManager(this);
        this.cookingManager = new CookingManager(this);
        this.cookingPotManager = new CookingPotManager(this);
        this.stoveManager = new com.example.simplecuisine.cooking.StoveManager(this);
        this.skilletManager = new com.example.simplecuisine.cooking.SkilletManager(this);
        this.cuttingBoardManager = new com.example.simplecuisine.cooking.CuttingBoardManager(this);
        this.recipeMenuManager = new com.example.simplecuisine.menu.RecipeMenuManager(this);
        this.riceManager = new com.example.simplecuisine.farming.RiceManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new CookingPotListener(this), this);
        getServer().getPluginManager().registerEvents(new StoveListener(this), this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.listener.SkilletListener(this), this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.listener.CuttingBoardListener(this), this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.listener.PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.listener.KnifeMechanicListener(this), this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.listener.DebugListener(this), this);
        getServer().getPluginManager().registerEvents(recipeMenuManager, this);
        getServer().getPluginManager().registerEvents(new com.example.simplecuisine.farming.RiceListener(this, riceManager), this);
        
        // Register Commands
        if (getCommand("simplecuisine") != null) {
            getCommand("simplecuisine").setExecutor(new com.example.simplecuisine.command.SimpleCuisineCommand(this));
        }

        // Schedule recipe loading to ensure dependency plugins are fully loaded (Fix for Race Condition)
        Bukkit.getScheduler().runTask(this, () -> {
            getLogger().info("Loading recipes...");
            if (cookingManager != null) cookingManager.loadRecipes();
            if (stoveManager != null) stoveManager.loadRecipes();
            if (cuttingBoardManager != null) cuttingBoardManager.loadRecipes();
            getLogger().info("Recipes loaded!");
        });

        getLogger().info("SimpleCuisine has been enabled!");
    }

    @Override
    public void onDisable() {
        if (riceManager != null) {
            riceManager.saveCrops();
        }
        if (cookingPotManager != null) {
            cookingPotManager.savePots();
        }
        if (stoveManager != null) {
            stoveManager.saveStoves();
            stoveManager.removeAllVisuals();
        }
        if (skilletManager != null) {
            skilletManager.saveSkillets();
            skilletManager.removeAllVisuals();
        }
        if (cuttingBoardManager != null) {
            cuttingBoardManager.saveBoards();
            cuttingBoardManager.removeAllVisuals();
        }
        if (recipeMenuManager != null) {
            recipeMenuManager.stopAllAnimations();
        }
        getLogger().info("SimpleCuisine has been disabled!");
    }
    
    // ... getters ...
    
    public static SimpleCuisine getInstance() {
        return instance;
    }

    public com.example.simplecuisine.cooking.CookingPotManager getPotManager() {
        return cookingPotManager;
    }

    public com.example.simplecuisine.menu.RecipeMenuManager getRecipeMenuManager() {
        return recipeMenuManager;
    }

    public com.example.simplecuisine.cooking.CookingManager getCookingManager() {
        return cookingManager;
    }
    
    public boolean isDebugger(java.util.UUID uuid) {
        return debuggers.contains(uuid);
    }
    
    public void toggleDebugger(java.util.UUID uuid) {
        if (debuggers.contains(uuid)) {
            debuggers.remove(uuid);
        } else {
            debuggers.add(uuid);
        }
    }

    public com.example.simplecuisine.cooking.StoveManager getStoveManager() {
        return stoveManager;
    }

    public com.example.simplecuisine.cooking.CuttingBoardManager getCuttingBoardManager() {
        return cuttingBoardManager;
    }

    public com.example.simplecuisine.cooking.SkilletManager getSkilletManager() {
        return skilletManager;
    }

    public com.example.simplecuisine.item.ItemManager getItemManager() {
        return itemManager;
    }
    
    public void reload() {
        reloadConfig();
        // Clear caches
        com.example.simplecuisine.util.CraftEngineHook.clearCache();
        
        // Re-initialize CraftEngine hook to refresh methods/logic if needed
        if (Bukkit.getPluginManager().isPluginEnabled("CraftEngine")) {
            com.example.simplecuisine.util.CraftEngineHook.init();
        }

        if (itemManager != null) {
            itemManager.reload();
        }
        if (cookingManager != null) {
            cookingManager.reload();
        }
        if (cookingPotManager != null) {
            cookingPotManager.reload();
        }
        if (stoveManager != null) {
            stoveManager.reload();
        }
        if (skilletManager != null) {
            skilletManager.loadSkillets();
        }
        if (cuttingBoardManager != null) {
            cuttingBoardManager.loadRecipes();
            // Clear old visuals before reloading boards to avoid duplication
            cuttingBoardManager.removeAllVisuals();
            cuttingBoardManager.loadBoards();
        }
        getLogger().info("Configuration, recipes, and pot visuals reloaded.");
    }

    public void debug(String message) {
        if (isDebug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }
}
