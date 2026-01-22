package com.example.simplecuisine.cooking;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CookingPotManager {

    private final SimpleCuisine plugin;
    private final Map<Location, CookingPot> activePots = new HashMap<>();
    private final Map<Location, UUID> potEntities = new HashMap<>();
    private final List<PendingPot> pendingPots = Collections.synchronizedList(new ArrayList<>());
    private final File storageFile;
    private Component GUI_TITLE;
    private org.bukkit.scheduler.BukkitTask cookingTask;

    public CookingPotManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "pots.yml");
        
        loadConfigValues();
        
        loadPots();
        startCookingTask();
    }
    
    private void startCookingTask() {
        // [Performance Fix] Reduced tick rate from 2L (0.1s) to 20L (1.0s).
        // Cooking logic does not require millisecond precision.
        cookingTask = new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (CookingPot pot : activePots.values()) {
                    // [Performance Fix] Skip logic if chunk is unloaded
                    if (!pot.getLocation().isChunkLoaded()) continue;
                    pot.tick();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); 
    }
    
    public void disable() {
        if (cookingTask != null && !cookingTask.isCancelled()) {
            cookingTask.cancel();
        }
        savePots();
    }
    
    public void reload() {
        loadConfigValues();
        
        // Update visuals
        for (Location loc : activePots.keySet()) {
            removePotVisual(loc);
            spawnPotVisual(loc);
        }
        
        // Update inventories with new title
        updateInventories();
        
        savePots();
    }
    
    private void updateInventories() {
        plugin.getLogger().info("Updating Cooking Pot inventories with new title...");
        
        // Prepare assets once
        CookingManager.GuiConfig guiConfig = plugin.getCookingManager().getGuiConfig();
        ItemStack recipeBook = plugin.getCookingManager().getGuiAsset("recipe_view", org.bukkit.Material.KNOWLEDGE_BOOK, "common.recipe_view");
        
        ItemStack filler = null;
        if (guiConfig.useFillers) {
            // 1. Try Config ID (IA/CE)
            if (guiConfig.fillerItem != null && !guiConfig.fillerItem.isEmpty()) {
                filler = plugin.getCookingManager().getItemWithFallback(guiConfig.fillerItem);
            }
            
            // 2. Fallback to Material/ModelData
            if (filler == null || filler.getType().isAir()) {
                filler = new ItemStack(guiConfig.fillerMaterial);
                if (guiConfig.fillerModelData != 0) {
                    var meta = filler.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(guiConfig.fillerModelData);
                        filler.setItemMeta(meta);
                    }
                }
            }

            // Apply transparent properties
            var meta = filler.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§r"));
                meta.setHideTooltip(true);
                filler.setItemMeta(meta);
            }
        }

        for (CookingPot pot : activePots.values()) {
            Inventory oldInv = pot.getInventory();
            if (oldInv != null) {
                Inventory newInv = Bukkit.createInventory(null, 27, GUI_TITLE);
                // Copy all contents first (preserves player items)
                newInv.setContents(oldInv.getContents());
                
                // Force update Slot 9 (Recipe Book)
                newInv.setItem(9, recipeBook);
                
                // Update Heat Indicator
                ItemStack heatItem = newInv.getItem(CookingPot.SLOT_HEAT_INDICATOR);
                if (heatItem != null) {
                    var meta = heatItem.getItemMeta();
                    if (meta != null) {
                        if (pot.isHeated()) {
                            meta.displayName(ConfigManager.getGuiText("pot.heat_on"));
                        } else {
                            meta.displayName(ConfigManager.getGuiText("pot.heat_off"));
                        }
                        heatItem.setItemMeta(meta);
                    }
                }
                
                // Force update Fillers if enabled
                if (guiConfig.useFillers && filler != null) {
                    int[] inputSlots = CookingPot.INPUT_SLOTS;
                    int slotBuffer = CookingPot.SLOT_BUFFER;
                    int slotHeat = CookingPot.SLOT_HEAT_INDICATOR;
                    int slotContainer = CookingPot.SLOT_CONTAINER_INPUT;
                    int slotOutput = CookingPot.SLOT_OUTPUT;
                    int slotProgress = guiConfig.progressBarSlot; // Assuming public or accessible
                    
                    for (int i = 0; i < 27; i++) {
                        boolean isSpecial = false;
                        for (int in : inputSlots) if (i == in) isSpecial = true;
                        if (i == slotBuffer || i == slotHeat || i == slotContainer || i == slotOutput) isSpecial = true;
                        if (i == 9) isSpecial = true; // Recipe Book
                        if (i == slotProgress) isSpecial = true; // Progress Bar
                        
                        if (!isSpecial) {
                            newInv.setItem(i, filler);
                        }
                    }
                }
                
                pot.setInventory(newInv);
            }
        }
    }

    public CookingPot getPot(Location loc) {
        return activePots.get(loc);
    }

    public CookingPot getPot(Inventory inv) {
        for (CookingPot pot : activePots.values()) {
            if (pot.getInventory().equals(inv)) {
                return pot;
            }
        }
        return null;
    }

    private void loadConfigValues() {
        plugin.reloadConfig(); // Ensure latest config is loaded

        // 1. Try Config.yml first (supports complex layout/images)
        String titleConfig = plugin.getConfig().getString("cooking_pot.gui.title");
        
        plugin.debug("Loading GUI Title from config.yml: " + titleConfig);
        
        if (titleConfig != null) {
            String offset = "";
            String icon = "";

            boolean ceHook = com.example.simplecuisine.util.CraftEngineHook.isEnabled();
            boolean iaHook = com.example.simplecuisine.util.ItemsAdderHook.isItemsAdderLoaded();
            
            plugin.debug("Hooks status - CraftEngine: " + ceHook + ", ItemsAdder: " + iaHook);

            if (ceHook) {
                String offsetConfig = plugin.getConfig().getString("cooking_pot.gui.layout.craftengine.offset", "");
                // Parse shift tag
                if (offsetConfig != null && offsetConfig.startsWith("<shift:") && offsetConfig.endsWith(">")) {
                    try {
                        int amount = Integer.parseInt(offsetConfig.substring(7, offsetConfig.length() - 1));
                        if (amount < 0) {
                            offset = com.example.simplecuisine.menu.RecipeMenuManager.getNegativeSpace(Math.abs(amount));
                        }
                    } catch (NumberFormatException e) {
                        offset = "";
                    }
                } else {
                    offset = offsetConfig;
                }

                String iconConfig = plugin.getConfig().getString("cooking_pot.gui.layout.craftengine.icon", "");
                // Parse icon if it is an image tag
                if (iconConfig != null && iconConfig.startsWith("<image:") && iconConfig.endsWith(">")) {
                    String id = iconConfig.substring(7, iconConfig.length() - 1);
                    String charCode = com.example.simplecuisine.util.CraftEngineHook.getFontImageChar(id);
                    if (charCode != null) {
                        icon = charCode;
                    } else {
                        icon = iconConfig; // Fallback
                    }
                } else {
                    icon = iconConfig;
                }
            } else if (iaHook) {
                offset = plugin.getConfig().getString("cooking_pot.gui.layout.itemsadder.offset", "");
                icon = plugin.getConfig().getString("cooking_pot.gui.layout.itemsadder.icon", "");
            }
            
            plugin.debug("Resolved GUI Layout - Offset: '" + offset + "', Icon: '" + icon + "'");

            titleConfig = titleConfig.replace("<offset>", offset).replace("<icon>", icon);
            GUI_TITLE = MiniMessage.miniMessage().deserialize(titleConfig);
        } else {
            // 2. Fallback to Messages.yml
            GUI_TITLE = ConfigManager.getGuiText("pot.menu_title");
            plugin.debug("Fallback to messages.yml Title");
        }
    }

    public Component getGuiTitle() {
        return GUI_TITLE;
    }

    public void createPot(Location loc) {
        if (!activePots.containsKey(loc)) {
            Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
            setupGui(inv);
            activePots.put(loc, new CookingPot(plugin, loc, inv));
            savePots();
        }
    }

    public void removePot(Location loc) {
        activePots.remove(loc);
        savePots();
    }

    private void spawnPotVisual(Location loc) {
        // Visuals are now fully handled by ItemsAdder or Vanilla blocks
    }

    private void removePotVisual(Location loc) {
        // Visuals are now fully handled by ItemsAdder or Vanilla blocks
    }

    public boolean isPot(Location loc) {
        return activePots.containsKey(loc);
    }

    public Inventory getPotInventory(Location loc) {
        CookingPot pot = activePots.get(loc);
        return pot != null ? pot.getInventory() : null;
    }

    public void openGui(org.bukkit.entity.Player player, Location loc) {
        Inventory inv = getPotInventory(loc);
        if (inv != null) {
            // [Performance Fix] Trigger Heat Check on interaction
            CookingPot pot = getPot(loc);
            if (pot != null) pot.forceHeatCheck();
            
            plugin.debug("[GUI Debug] Opening GUI for player " + player.getName());
            player.openInventory(inv);
        } else {
             // Recovery
             createPot(loc);
             inv = getPotInventory(loc);
             if (inv != null) {
                 CookingPot pot = getPot(loc);
                 if (pot != null) pot.forceHeatCheck();
                 
                 plugin.debug("[GUI Debug] Opening GUI (Recovered) for player " + player.getName());
                 player.openInventory(inv);
             }
        }
    }

    public Set<Location> getAllPots() {
        return activePots.keySet();
    }



    private void setupGui(Inventory inv) {
        CookingManager.GuiConfig guiConfig = plugin.getCookingManager().getGuiConfig();
        boolean useFillers = guiConfig.useFillers;

        if (useFillers) {
            // Get filler item from config
            ItemStack filler = null;
            
            // 1. Try Config ID (IA/CE)
            if (guiConfig.fillerItem != null && !guiConfig.fillerItem.isEmpty()) {
                filler = plugin.getCookingManager().getItemWithFallback(guiConfig.fillerItem);
            }
            
            // 2. Fallback to Material/ModelData
            if (filler == null || filler.getType().isAir()) {
                filler = new ItemStack(guiConfig.fillerMaterial);
                if (guiConfig.fillerModelData != 0) {
                    var meta = filler.getItemMeta();
                    if (meta != null) {
                        meta.setCustomModelData(guiConfig.fillerModelData);
                        filler.setItemMeta(meta);
                    }
                }
            }

            // Apply transparent properties
            var meta = filler.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("§r")); // Empty name
                meta.setHideTooltip(true); // Hide tooltip if possible (1.20.6+) or just rely on name
                filler.setItemMeta(meta);
            }

            int[] inputSlots = CookingPot.INPUT_SLOTS;
            int slotBuffer = CookingPot.SLOT_BUFFER;
            int slotHeat = CookingPot.SLOT_HEAT_INDICATOR;
            int slotContainer = CookingPot.SLOT_CONTAINER_INPUT;
            int slotOutput = CookingPot.SLOT_OUTPUT;

            for (int i = 0; i < 27; i++) {
                boolean isSpecial = false;
                for (int in : inputSlots) if (i == in) isSpecial = true;
                if (i == slotBuffer || i == slotHeat || i == slotContainer || i == slotOutput) isSpecial = true;
                
                if (!isSpecial) {
                    inv.setItem(i, filler);
                }
            }
        }
        
        // Slot 9: Recipe Book (Using GUI Asset)
        ItemStack recipeBook = plugin.getCookingManager().getGuiAsset("recipe_view", org.bukkit.Material.KNOWLEDGE_BOOK, "common.recipe_view");
        inv.setItem(9, recipeBook);
    }

    public void savePots() {
        YamlConfiguration config = new YamlConfiguration();
        int count = 0;
        
        // Save active pots
        for (Map.Entry<Location, CookingPot> entry : activePots.entrySet()) {
            String path = "pots." + count;
            Location loc = entry.getKey();
            config.set(path + ".world", loc.getWorld().getName());
            config.set(path + ".x", loc.getBlockX());
            config.set(path + ".y", loc.getBlockY());
            config.set(path + ".z", loc.getBlockZ());
            
            UUID visualId = potEntities.get(loc);
            if (visualId != null) {
                config.set(path + ".visual_uuid", visualId.toString());
            }
            
            // Save contents
            Inventory inv = entry.getValue().getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack item = inv.getItem(i);
                if (item != null && !item.getType().isAir()) {
                    config.set(path + ".content." + i, item);
                }
            }
            count++;
        }
        
        // Save pending pots
        synchronized (pendingPots) {
            for (PendingPot pp : pendingPots) {
                String path = "pots." + count;
                config.set(path + ".world", pp.worldName);
                config.set(path + ".x", pp.x);
                config.set(path + ".y", pp.y);
                config.set(path + ".z", pp.z);
                
                if (pp.visualId != null) {
                    config.set(path + ".visual_uuid", pp.visualId.toString());
                }
                
                for (Map.Entry<Integer, ItemStack> entry : pp.content.entrySet()) {
                    config.set(path + ".content." + entry.getKey(), entry.getValue());
                }
                count++;
            }
        }
        
        try {
            config.save(storageFile);
            plugin.getLogger().info("Saved " + count + " cooking pots (Active: " + activePots.size() + ", Pending: " + pendingPots.size() + ").");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadPots() {
        if (!storageFile.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(storageFile);
        ConfigurationSection section = config.getConfigurationSection("pots");
        if (section == null) return;
        
        activePots.clear();
        pendingPots.clear();

        for (String key : section.getKeys(false)) {
            String worldName = section.getString(key + ".world");
            int x = section.getInt(key + ".x");
            int y = section.getInt(key + ".y");
            int z = section.getInt(key + ".z");
            
            if (worldName == null) continue;
            
            UUID visualId = null;
            String uuidStr = section.getString(key + ".visual_uuid");
            if (uuidStr != null) {
                try {
                    visualId = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException ignored) {}
            }
            
            org.bukkit.World world = Bukkit.getWorld(worldName);
            
            // Handle unloaded world
            if (world == null) {
                PendingPot pp = new PendingPot(worldName, x, y, z, visualId);
                ConfigurationSection content = section.getConfigurationSection(key + ".content");
                if (content != null) {
                    for (String slotStr : content.getKeys(false)) {
                        int slot = Integer.parseInt(slotStr);
                        pp.content.put(slot, content.getItemStack(slotStr));
                    }
                }
                pendingPots.add(pp);
                continue;
            }

            Location loc = new Location(world, x, y, z);
            
            if (visualId != null) {
                potEntities.put(loc, visualId);
            }
            
            Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
            setupGui(inv);

            ConfigurationSection content = section.getConfigurationSection(key + ".content");
            if (content != null) {
                for (String slotStr : content.getKeys(false)) {
                    int slot = Integer.parseInt(slotStr);
                    ItemStack item = content.getItemStack(slotStr);
                    inv.setItem(slot, item);
                }
            }
            activePots.put(loc, new CookingPot(plugin, loc, inv));
        }
        plugin.getLogger().info("Loaded " + activePots.size() + " cooking pots (and " + pendingPots.size() + " pending).");
    }
    
    public void tryLoadPending(org.bukkit.World world) {
        if (world == null) return;
        String name = world.getName();
        int loadedCount = 0;
        
        synchronized (pendingPots) {
            java.util.Iterator<PendingPot> it = pendingPots.iterator();
            while (it.hasNext()) {
                PendingPot pp = it.next();
                if (pp.worldName.equals(name)) {
                    Location loc = new Location(world, pp.x, pp.y, pp.z);
                    
                    if (pp.visualId != null) {
                        potEntities.put(loc, pp.visualId);
                    }
                    
                    Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);
                    setupGui(inv);
                    
                    for (Map.Entry<Integer, ItemStack> entry : pp.content.entrySet()) {
                        inv.setItem(entry.getKey(), entry.getValue());
                    }
                    
                    activePots.put(loc, new CookingPot(plugin, loc, inv));
                    
                    // Visuals handled by ItemsAdder/Vanilla blocks mostly, but if there were custom entities:
                    spawnPotVisual(loc); 
                    
                    it.remove();
                    loadedCount++;
                }
            }
        }
        
        if (loadedCount > 0) {
            plugin.getLogger().info("Loaded " + loadedCount + " pending cooking pots for world: " + name);
        }
    }

    private static class PendingPot {
        String worldName;
        int x, y, z;
        UUID visualId;
        Map<Integer, ItemStack> content = new HashMap<>();

        PendingPot(String worldName, int x, int y, int z, UUID visualId) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.visualId = visualId;
        }
    }
}
