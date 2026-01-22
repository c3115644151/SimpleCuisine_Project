package com.example.simplecuisine.menu;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.config.ConfigManager;
import com.example.simplecuisine.cooking.CuttingBoardManager;
import com.example.simplecuisine.cooking.Ingredient;
import com.example.simplecuisine.cooking.Recipe;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RecipeMenuManager implements Listener {

    private final SimpleCuisine plugin;
    private static final int PAGE_SIZE = 45; // 5 rows of 9
    
    private final Map<UUID, Integer> activeAnimationTasks = new HashMap<>();

    // Keys for persistent data to track menu state
    private final NamespacedKey KEY_MENU_TYPE = new NamespacedKey("simplecuisine", "menu_type");
    private final NamespacedKey KEY_PAGE = new NamespacedKey("simplecuisine", "menu_page");
    private final NamespacedKey KEY_RECIPE_ID = new NamespacedKey("simplecuisine", "recipe_id");

    public RecipeMenuManager(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // GUI Asset Helper
    // ==========================================

    private ItemStack getGuiAsset(String key, Material defaultMat, String defaultNameKey) {
        return plugin.getCookingManager().getGuiAsset(key, defaultMat, defaultNameKey);
    }

    // ==========================================
    // Cooking Pot Menu
    // ==========================================

    public void openPotRecipeMenu(Player player, int page) {
        List<Recipe> recipes = plugin.getCookingManager().getRecipes();
        int totalPages = (int) Math.ceil((double) recipes.size() / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        Component title = getMenuTitle("level_1", "pot.menu_title").append(ConfigManager.getGuiText("common.page_info", "<current>", String.valueOf(page + 1), "<total>", String.valueOf(totalPages == 0 ? 1 : totalPages)));
        Inventory inv = Bukkit.createInventory(new MenuHolder("pot_list", page), 54, title);

        int startIndex = page * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, recipes.size());

        for (int i = startIndex; i < endIndex; i++) {
            Recipe recipe = recipes.get(i);
            ItemStack icon = createRecipeIcon(recipe, player);
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(KEY_RECIPE_ID, PersistentDataType.STRING, recipe.getId());
            icon.setItemMeta(meta);
            
            // Slot mapping: 9-53
            inv.setItem(9 + (i - startIndex), icon);
        }

        setupNavigation(inv, page, totalPages, "pot");

        player.openInventory(inv);
    }

    public void openPotRecipeDetail(Player player, Recipe recipe, int fromPage) {
        // Stop existing animation
        if (activeAnimationTasks.containsKey(player.getUniqueId())) {
             Bukkit.getScheduler().cancelTask(activeAnimationTasks.remove(player.getUniqueId()));
        }

        ItemStack resultItem = getRecipeResultItem(recipe, player);
        Component title = getMenuTitle("level_2_pot", "pot.recipe_detail_title").append(Component.text(": ")).append(recipe.getDisplayName());
        Inventory inv = Bukkit.createInventory(new MenuHolder("pot_detail", fromPage), 54, title);

        // Slot 0: Back
        ItemStack back = getGuiAsset("back_button", Material.ARROW, "common.back");
        ItemMeta backMeta = back.getItemMeta();
        backMeta.getPersistentDataContainer().set(KEY_PAGE, PersistentDataType.INTEGER, fromPage);
        backMeta.getPersistentDataContainer().set(KEY_MENU_TYPE, PersistentDataType.STRING, "pot_back");
        back.setItemMeta(backMeta);
        inv.setItem(0, back);

        // Ingredients (Mapped to GUI slots: 19, 20, 21, 28, 29, 30)
        int[] inputSlots = {19, 20, 21, 28, 29, 30}; 
        int slotIndex = 0;
        
        Map<Integer, List<ItemStack>> animatedSlots = new HashMap<>();
        Map<Integer, List<Component>> slotLores = new HashMap<>();

        for (Map.Entry<Ingredient, Integer> entry : recipe.getIngredients().entrySet()) {
            Ingredient ing = entry.getKey();
            int amount = entry.getValue();
            
            // Split amount into individual items
            for (int i = 0; i < amount; i++) {
                if (slotIndex >= inputSlots.length) break;
                int currentSlot = inputSlots[slotIndex++];
                
                ItemStack item;
                // Check if ingredient has tag items (Bukkit Tag OR Cached External Tag)
                if ((ing.getTag() != null) || (ing.getCachedTagItems() != null && !ing.getCachedTagItems().isEmpty())) {
                    List<ItemStack> tagItems;
                    if (ing.getCachedTagItems() != null && !ing.getCachedTagItems().isEmpty()) {
                        tagItems = ing.getCachedTagItems();
                    } else {
                        tagItems = resolveTagItems(ing, player);
                    }
                    
                    // Localize all tag items for display
                    if (CraftEngineHook.isEnabled() && !tagItems.isEmpty()) {
                        List<ItemStack> localized = new ArrayList<>();
                        for (ItemStack ti : tagItems) {
                            localized.add(CraftEngineHook.localizeItem(ti, player));
                        }
                        tagItems = localized;
                    }

                    if (tagItems.isEmpty()) {
                        item = createIngredientIcon(ing, player);
                    } else {
                        // Use first item as base, but ensure we use the "Recipe View" book icon style if configured
                        // Actually, if it's a tag, we usually want to show the items cycling.
                        // But if the user wants a specific "Book" icon for tags, we should check that.
                        // The user said "Recipe View Component (usually book icon)".
                        // Let's assume for now we use the cycling item itself.
                        item = tagItems.get(0).clone();
                        
                        // Debug Log for CMD
                        if (item.getItemMeta().hasCustomModelData()) {
                            SimpleCuisine.getInstance().debug("  -> Tag Item " + item.getType() + " has CMD: " + item.getItemMeta().getCustomModelData());
                        } else {
                            SimpleCuisine.getInstance().debug("  -> Tag Item " + item.getType() + " has NO CMD!");
                        }
                        
                        // Build Lore
                        List<Component> lore = new ArrayList<>();
                        lore.add(ConfigManager.getGuiText("pot.tag_contains"));
                        int count = 0;
                        for (ItemStack sub : tagItems) {
                            if (count >= 10) {
                                lore.add(ConfigManager.getGuiText("pot.tag_more", "<count>", String.valueOf(tagItems.size())));
                                break;
                            }
                            
                            // Robust Name Resolution for Lore
                            Component displayName = null;
                            
                            // Try CraftEngine Hook first (handles wrapped items correctly)
                            if (CraftEngineHook.isEnabled()) {
                                displayName = CraftEngineHook.getCustomItemDisplayName(sub, player);
                            }
                            
                            // Fallback to ID-based lookup if direct lookup failed
                            if (displayName == null) {
                                String ceId = CraftEngineHook.getCustomItemId(sub);
                                if (ceId != null) {
                                    displayName = CraftEngineHook.getCustomItemDisplayName(ceId, player);
                                }
                            }
                            
                            if (displayName == null) {
                                displayName = getDisplayName(sub, player);
                            }
                            
                            lore.add(Component.text("- ", NamedTextColor.WHITE).append(displayName));
                            count++;
                        }
                        
                        ItemMeta meta = item.getItemMeta();
                        meta.lore(lore);
                        item.setItemMeta(meta);
                        
                        animatedSlots.put(currentSlot, tagItems);
                        slotLores.put(currentSlot, lore);
                    }
                } else {
                    item = createIngredientIcon(ing, player);
                    item.setAmount(1);
                }
                inv.setItem(currentSlot, item);
            }
        }
        
        // Containers (Slot 41)
        if (recipe.getContainers() != null && !recipe.getContainers().isEmpty()) {
             ItemStack containerIcon = createIngredientIcon(recipe.getContainers().get(0), player);
             ItemMeta cm = containerIcon.getItemMeta();
             cm.displayName(ConfigManager.getGuiText("pot.container_needed"));
             containerIcon.setItemMeta(cm);
             inv.setItem(41, containerIcon);
        }

        // Result (Slot 43)
        inv.setItem(43, resultItem);

        // Time (Slot 14)
        ItemStack timeIcon = getGuiAsset("cooking_time", Material.CLOCK, "pot.cooking_time_title");
        ItemMeta timeMeta = timeIcon.getItemMeta();
        timeMeta.displayName(ConfigManager.getGuiText("pot.cooking_time_format", "<time>", String.valueOf(recipe.getTime() / 20.0)));
        timeIcon.setItemMeta(timeMeta);
        inv.setItem(14, timeIcon);
        
        player.openInventory(inv);
        
        // Start Animation Task
        if (!animatedSlots.isEmpty()) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                int tick = 0;
                @Override
                public void run() {
                    // Safety Check: Ensure player is still viewing this inventory
                    if (player.getOpenInventory().getTopInventory() != inv) {
                        this.cancel();
                        return;
                    }

                    tick++;
                    if (tick % 20 == 0) {
                         int index = tick / 20;
                         for (Map.Entry<Integer, List<ItemStack>> entry : animatedSlots.entrySet()) {
                             int slot = entry.getKey();
                             List<ItemStack> items = entry.getValue();
                             if (items.isEmpty()) continue;
                             
                             ItemStack next = items.get(index % items.size()).clone();
                             ItemMeta meta = next.getItemMeta();
                             meta.lore(slotLores.get(slot));
                             next.setItemMeta(meta);
                             
                             inv.setItem(slot, next);
                         }
                    }
                }
                
                private void cancel() {
                    Integer taskId = activeAnimationTasks.remove(player.getUniqueId());
                    if (taskId != null) {
                        Bukkit.getScheduler().cancelTask(taskId);
                    }
                }
            }, 1L, 1L);
            activeAnimationTasks.put(player.getUniqueId(), task.getTaskId());
        }
    }

    public void stopAllAnimations() {
        for (Integer taskId : activeAnimationTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        activeAnimationTasks.clear();
    }

    // ==========================================
    // Cutting Board Menu
    // ==========================================

    public void openBoardRecipeMenu(Player player, int page) {
        List<CuttingBoardManager.CuttingRecipe> recipes = plugin.getCuttingBoardManager().getRecipes();
        int totalPages = (int) Math.ceil((double) recipes.size() / PAGE_SIZE);
        if (page < 0) page = 0;
        if (page >= totalPages && totalPages > 0) page = totalPages - 1;

        Component title = getMenuTitle("level_1", "board.menu_title").append(ConfigManager.getGuiText("common.page_info", "<current>", String.valueOf(page + 1), "<total>", String.valueOf(totalPages == 0 ? 1 : totalPages)));
        Inventory inv = Bukkit.createInventory(new MenuHolder("board_list", page), 54, title);

        int startIndex = page * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, recipes.size());

        for (int i = startIndex; i < endIndex; i++) {
            CuttingBoardManager.CuttingRecipe recipe = recipes.get(i);
            ItemStack icon = createCuttingRecipeIcon(recipe, player);
            ItemMeta meta = icon.getItemMeta();
            meta.getPersistentDataContainer().set(KEY_RECIPE_ID, PersistentDataType.STRING, String.valueOf(i));
            icon.setItemMeta(meta);
            
            inv.setItem(9 + (i - startIndex), icon);
        }

        setupNavigation(inv, page, totalPages, "board");

        player.openInventory(inv);
    }

    public void openBoardRecipeDetail(Player player, CuttingBoardManager.CuttingRecipe recipe, int fromPage) {
        // Stop existing animation
        if (activeAnimationTasks.containsKey(player.getUniqueId())) {
             Bukkit.getScheduler().cancelTask(activeAnimationTasks.remove(player.getUniqueId()));
        }

        Component title = getMenuTitle("level_2_board", "board.recipe_detail_title");
        Inventory inv = Bukkit.createInventory(new MenuHolder("board_detail", fromPage), 54, title);

        // Back (Slot 0)
        ItemStack back = getGuiAsset("back_button", Material.ARROW, "common.back");
        ItemMeta backMeta = back.getItemMeta();
        backMeta.getPersistentDataContainer().set(KEY_PAGE, PersistentDataType.INTEGER, fromPage);
        backMeta.getPersistentDataContainer().set(KEY_MENU_TYPE, PersistentDataType.STRING, "board_back");
        back.setItemMeta(backMeta);
        inv.setItem(0, back);

        // Input (Slot 28)
        ItemStack input = createIngredientIcon(recipe.getInput(), player);
        inv.setItem(28, input);

        // Tool (Slot 30) - Animated
        if ("knife".equalsIgnoreCase(recipe.getToolType())) {
            List<ItemStack> tools = new ArrayList<>();
            Set<String> allowedKnives = plugin.getCuttingBoardManager().getAllowedKnives();
            
            // Debug logging for knives
            plugin.getLogger().info("Loading knives for animation. Allowed count: " + allowedKnives.size());
            
            for (String id : allowedKnives) {
                ItemStack item = resolveItem(id);
                if (item != null) {
                    tools.add(item);
                } else {
                    plugin.getLogger().warning("Failed to resolve knife for menu: " + id);
                }
            }
            
            plugin.getLogger().info("Resolved knives count: " + tools.size());
            if (!tools.isEmpty()) {
                ItemMeta meta = tools.get(0).getItemMeta();
                String name = meta.hasDisplayName() ? MiniMessage.miniMessage().serialize(meta.displayName()) : tools.get(0).getType().name();
                plugin.getLogger().info("First knife: " + tools.get(0).getType() + " " + name);
            }
            
            if (!tools.isEmpty()) {
                inv.setItem(30, tools.get(0));
            } else {
                 ItemStack toolIcon = new ItemStack(Material.IRON_SWORD);
                 ItemMeta tm = toolIcon.getItemMeta();
                 tm.displayName(ConfigManager.getGuiText("board.tool_knife_needed"));
                 toolIcon.setItemMeta(tm);
                 inv.setItem(30, toolIcon);
            }
        } else {
            ItemStack toolIcon = new ItemStack(Material.IRON_SWORD);
            ItemMeta tm = toolIcon.getItemMeta();
            tm.displayName(ConfigManager.getGuiText("board.tool_other_needed", "<tool>", recipe.getToolType()));
            toolIcon.setItemMeta(tm);
            inv.setItem(30, toolIcon);
        }

        // Output (Slots 32, 33, 41, 42)
        List<ItemStack> results = recipe.getResults();
        int[] outputSlots = {32, 33, 41, 42};
        if (results != null) {
            for (int i = 0; i < Math.min(results.size(), outputSlots.length); i++) {
                inv.setItem(outputSlots[i], results.get(i));
            }
        }

        player.openInventory(inv);

        // Start animation task AFTER opening inventory to avoid cancellation by CloseEvent
        if ("knife".equalsIgnoreCase(recipe.getToolType())) {
            List<ItemStack> tools = new ArrayList<>();
            Set<String> allowedKnives = plugin.getCuttingBoardManager().getAllowedKnives();
            for (String id : allowedKnives) {
                ItemStack item = resolveItem(id);
                if (item != null) tools.add(item);
            }

            if (tools.size() > 1) {
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                    int index = 0;
                    @Override
                    public void run() {
                        try {
                            // Safety Check: Ensure player is still viewing this inventory
                            if (player.getOpenInventory().getTopInventory() != inv) {
                                this.cancel();
                                return;
                            }

                            index = (index + 1) % tools.size();
                            Inventory top = player.getOpenInventory().getTopInventory();
                            
                            boolean isSame = top.getHolder() == inv.getHolder();
                            // plugin.getLogger().info("Anim Tick: " + index + " IsSameHolder: " + isSame);

                            if (isSame) {
                                top.setItem(30, tools.get(index));
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            this.cancel();
                        }
                    }

                    private void cancel() {
                        Integer taskId = activeAnimationTasks.remove(player.getUniqueId());
                        if (taskId != null) {
                            Bukkit.getScheduler().cancelTask(taskId);
                        }
                    }
                }, 20L, 20L);
                activeAnimationTasks.put(player.getUniqueId(), task.getTaskId());
            }
        }
    }

    // ==========================================
    // Helpers
    // ==========================================

    private void setupNavigation(Inventory inv, int page, int totalPages, String type) {
        // Previous Page Button (Slot 0)
        if (page > 0) {
            ItemStack prev = getGuiAsset("previous_page", Material.ARROW, "common.previous_page");
            ItemMeta meta = prev.getItemMeta();
            // Preserve Display Name if not set in config (though getGuiAsset handles it)
            // But we need to add PDC
            meta.getPersistentDataContainer().set(KEY_PAGE, PersistentDataType.INTEGER, page - 1);
            meta.getPersistentDataContainer().set(KEY_MENU_TYPE, PersistentDataType.STRING, type + "_prev");
            prev.setItemMeta(meta);
            inv.setItem(0, prev);
        } else {
            ItemStack prev = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = prev.getItemMeta();
            meta.displayName(ConfigManager.getGuiText("common.already_first_page"));
            prev.setItemMeta(meta);
            inv.setItem(0, prev);
        }

        // Next Page Button (Slot 8)
        if (page < totalPages - 1) {
            ItemStack next = getGuiAsset("next_page", Material.ARROW, "common.next_page");
            ItemMeta meta = next.getItemMeta();
            meta.getPersistentDataContainer().set(KEY_PAGE, PersistentDataType.INTEGER, page + 1);
            meta.getPersistentDataContainer().set(KEY_MENU_TYPE, PersistentDataType.STRING, type + "_next");
            next.setItemMeta(meta);
            inv.setItem(8, next);
        } else {
            ItemStack next = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = next.getItemMeta();
            meta.displayName(ConfigManager.getGuiText("common.already_last_page"));
            next.setItemMeta(meta);
            inv.setItem(8, next);
        }
    }

    private Component getMenuTitle(String configKey, String defaultTitleKey) {
        if (!plugin.getConfig().contains("recipe_menu." + configKey)) {
            // Try i18n
            return ConfigManager.getGuiText(defaultTitleKey);
        }

        String title = plugin.getConfig().getString("recipe_menu." + configKey + ".title");
        if (title == null) {
            title = ConfigManager.getRawString("gui." + defaultTitleKey);
        }
        String offset = "";
        String icon = "";

        if (CraftEngineHook.isEnabled()) {
            String offsetConfig = plugin.getConfig().getString("recipe_menu." + configKey + ".layout.craftengine.offset", "");
            // Parse shift tag
            if (offsetConfig != null && offsetConfig.startsWith("<shift:") && offsetConfig.endsWith(">")) {
                try {
                    int amount = Integer.parseInt(offsetConfig.substring(7, offsetConfig.length() - 1));
                    // Only handle negative shifts for now as they are used for UI offsets
                    if (amount < 0) {
                        offset = getNegativeSpace(Math.abs(amount));
                    }
                } catch (NumberFormatException e) {
                    offset = "";
                }
            } else {
                offset = offsetConfig;
            }

            String iconConfig = plugin.getConfig().getString("recipe_menu." + configKey + ".layout.craftengine.icon", "");
            
            // Parse icon if it is an image tag
            if (iconConfig != null && iconConfig.startsWith("<image:") && iconConfig.endsWith(">")) {
                String id = iconConfig.substring(7, iconConfig.length() - 1);
                String charCode = CraftEngineHook.getFontImageChar(id);
                if (charCode != null) {
                    icon = charCode;
                    plugin.getLogger().info("Resolved icon for " + id + ": " + Integer.toHexString(charCode.codePointAt(0)));
                } else {
                    plugin.getLogger().warning("Failed to resolve CraftEngine icon: " + id);
                }
            } else if (iconConfig != null) {
                icon = iconConfig;
            }
        }

        // Simple string replacement to inject raw tags/chars
        String finalTitle = title.replace("<offset>", offset).replace("<icon>", icon);

        // Parse <shift:N> tags in the final title (e.g. for correcting cursor position after icon)
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<shift:(-?\\d+)>").matcher(finalTitle);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            try {
                int amount = Integer.parseInt(matcher.group(1));
                String replacement = "";
                if (amount < 0) {
                    replacement = getNegativeSpace(Math.abs(amount));
                }
                matcher.appendReplacement(sb, replacement);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        matcher.appendTail(sb);
        finalTitle = sb.toString();

        // plugin.getLogger().info("Final menu title: " + finalTitle);

        return MiniMessage.miniMessage().deserialize(finalTitle);
    }

    public static String getNegativeSpace(int amount) {
        StringBuilder sb = new StringBuilder();
        int remaining = amount;
        
        // Standard Negative Space Font mapping (Amber/ItemsAdder)
        while (remaining > 0) {
            if (remaining >= 128) { sb.append("\uF80C"); remaining -= 128; }
            else if (remaining >= 64) { sb.append("\uF80B"); remaining -= 64; }
            else if (remaining >= 32) { sb.append("\uF80A"); remaining -= 32; }
            else if (remaining >= 16) { sb.append("\uF809"); remaining -= 16; }
            else if (remaining >= 8) { sb.append("\uF808"); remaining -= 8; }
            else if (remaining >= 7) { sb.append("\uF807"); remaining -= 7; }
            else if (remaining >= 6) { sb.append("\uF806"); remaining -= 6; }
            else if (remaining >= 5) { sb.append("\uF805"); remaining -= 5; }
            else if (remaining >= 4) { sb.append("\uF804"); remaining -= 4; }
            else if (remaining >= 3) { sb.append("\uF803"); remaining -= 3; }
            else if (remaining >= 2) { sb.append("\uF802"); remaining -= 2; }
            else if (remaining >= 1) { sb.append("\uF801"); remaining -= 1; }
            else { break; } // Should not happen
        }
        
        return sb.toString();
    }

    private ItemStack createRecipeIcon(Recipe recipe, Player player) {
        ItemStack item = getRecipeResultItem(recipe, player);
        
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
            if (lore == null) lore = new ArrayList<>();
            lore.add(Component.text("点击查看详情", NamedTextColor.GRAY));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        
        return item;
    }

    private ItemStack getRecipeResultItem(Recipe recipe, Player player) {
        if (recipe == null) {
            return new ItemStack(Material.BARRIER);
        }

        ItemStack item = null;

        // 1. Try Custom Item (CraftEngine / ItemsAdder)
        if (recipe.getCraftEngineId() != null) {
            // Try CraftEngine
            if (CraftEngineHook.isEnabled()) {
                item = CraftEngineHook.getItem(recipe.getCraftEngineId());
                if (item != null) {
                    item = CraftEngineHook.localizeItem(item, player);
                }
            }
            // Try ItemsAdder
            if ((item == null || item.getType().isAir()) && ItemsAdderHook.isItemsAdderLoaded()) {
                item = ItemsAdderHook.getItem(recipe.getCraftEngineId());
            }
        }

        // 2. Fallback / Hybrid: If hooks failed, try manual construction if CMD exists
        if (item == null || item.getType().isAir()) {
            Material mat = recipe.getResult();
            // If result is AIR but we have CMD, use PAPER as fallback base
            // This handles cases where CookingManager couldn't resolve ID to Material but we have CMD from config
            if ((mat == null || mat == Material.AIR) && recipe.getCustomModelData() != 0) {
                mat = Material.PAPER; 
            }
            
            if (mat != null && mat != Material.AIR) {
                item = new ItemStack(mat);
            }
        }

        // Handle Air/Invalid items that return null meta
        if (item == null || item.getType().isAir() || !item.getType().isItem()) {
             ItemStack barrier = new ItemStack(Material.BARRIER);
             ItemMeta meta = barrier.getItemMeta();
             if (meta != null) {
                 meta.displayName(ConfigManager.getGuiText("pot.invalid_recipe", "<id>", recipe.getId()));
                 List<Component> lore = new ArrayList<>();
                 lore.add(ConfigManager.getGuiText("pot.invalid_result"));
                 if (recipe.getCraftEngineId() != null) {
                     lore.add(Component.text("ID: " + recipe.getCraftEngineId(), NamedTextColor.DARK_GRAY));
                 }
                 meta.lore(lore);
                 barrier.setItemMeta(meta);
             }
             return barrier;
        }

        if (recipe.getCustomModelData() != 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(recipe.getCustomModelData());
                item.setItemMeta(meta);
            }
        }
        
        return item;
    }

    private ItemStack createCuttingRecipeIcon(CuttingBoardManager.CuttingRecipe recipe, Player player) {
        List<ItemStack> results = recipe.getResults();
        ItemStack item;
        if (results != null && !results.isEmpty()) {
            item = results.get(0).clone();
            if (CraftEngineHook.isEnabled()) {
                item = CraftEngineHook.localizeItem(item, player);
            }
        } else {
            item = new ItemStack(Material.BARRIER);
        }
        
        // Handle Air/Invalid
        if (item.getType().isAir() || !item.getType().isItem()) {
            item = new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text("无效产物", NamedTextColor.RED));
                item.setItemMeta(meta);
            }
        }
        
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
            lore.add(ConfigManager.getGuiText("board.ingredients_label").append(getIngredientName(recipe.getInput(), player)));
            lore.add(ConfigManager.getGuiText("common.click_for_detail"));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
    
    private Component getIngredientName(Ingredient ing, Player player) {
        if (ing.getTag() != null) {
            return Component.text("#" + ing.getKey(), NamedTextColor.AQUA);
        }
        if (ing.getCraftEngineId() != null) {
             if (CraftEngineHook.isEnabled()) {
                 ItemStack item = CraftEngineHook.getItem(ing.getCraftEngineId());
                 if (item != null) {
                     item = CraftEngineHook.localizeItem(item, player);
                     if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                         return item.getItemMeta().displayName();
                     }
                 }
             }
             return Component.text(ing.getCraftEngineId(), NamedTextColor.AQUA);
        }
        if (ing.getMaterial() != null) {
            return Component.translatable(ing.getMaterial().translationKey());
        }
        return ConfigManager.getGuiText("common.unknown_item");
    }
    
    private List<ItemStack> resolveTagItems(Ingredient ing, Player player) {
        List<ItemStack> items = new ArrayList<>();
        
        // Debug Log
        if (ing.getExternalTag() != null || ing.getTag() != null) {
            SimpleCuisine.getInstance().debug("Resolving Tag: " + (ing.getExternalTag() != null ? ing.getExternalTag() : ing.getTag().getKey().toString()));
        }

        // 1. Try looking up via CraftEngineHook first (Runtime Priority)
        if (CraftEngineHook.isEnabled()) {
            String tagName = ing.getExternalTag();
            if (tagName == null && ing.getTag() != null) {
                tagName = ing.getTag().getKey().toString();
            }
            if (tagName != null) {
                 SimpleCuisine.getInstance().debug("Resolving tag via CE Hook: " + tagName);
                 List<ItemStack> ceItems = CraftEngineHook.getTagCustomItems(tagName, player);
                 if (!ceItems.isEmpty()) {
                     SimpleCuisine.getInstance().debug("  -> Found " + ceItems.size() + " items via CE Hook.");
                     return ceItems;
                 } else {
                     SimpleCuisine.getInstance().debug("  -> CE Hook returned 0 items.");
                 }
            }
        }

        // 2. Try CraftEngine/CookingManager cached items
        if (ing.getCachedTagItems() != null && !ing.getCachedTagItems().isEmpty()) {
            SimpleCuisine.getInstance().debug("  -> Found " + ing.getCachedTagItems().size() + " cached items.");
            return ing.getCachedTagItems();
        }
        
        // 3. Fallback to Bukkit Tag
        org.bukkit.Tag<Material> tag = ing.getTag();
        if (tag == null) return items;
        for (Material m : tag.getValues()) {
            items.add(new ItemStack(m));
        }
        SimpleCuisine.getInstance().debug("  -> Fallback to Bukkit Tag: " + items.size() + " items.");
        return items;
    }

    // ==========================================
    // GUI Asset Helper
    // ==========================================

    /**
     * Helper to create recipe icons (replaces createRecipeIcon method logic part 1 - base item)
     * Note: This method is used inside createRecipeIcon below, or we modify createRecipeIcon to use getGuiAsset for the "book" part?
     * Actually, createRecipeIcon logic is complex (shows result or book).
     * Let's modify createRecipeIcon to use our asset system for the fallback/book icon if needed.
     * But usually recipe icon IS the result item. 
     * Wait, the user mentioned "Recipe View Component". Is this the icon representing the recipe in the list?
     * "查看食谱组件" -> Likely the icon players click to view details, OR an icon inside the detail view?
     * In openPotRecipeMenu, we use createRecipeIcon(recipe).
     * In openPotRecipeDetail, we show resultItem at slot 43.
     * 
     * If "Recipe View Component" refers to a specific button, let's look for where a generic "View Recipe" icon might be used.
     * Currently:
     * - List Menu: Icons are the resulting food.
     * - Detail Menu: Icons are Ingredients, Result, Time, Container.
     * 
     * Maybe "Recipe View Component" is just a decorative item? Or maybe the user means the "Book" icon used for Tag ingredients?
     * "查看食谱组件 (通常是书本图标)" in my config comment.
     * 
     * Let's look at createIngredientIcon for Tags. It uses Material.BOOK.
     */
     

    
    // ...
    
    // Actually, looking at the user request: "查看食谱组件"
    // If it's a specific button in the GUI, maybe I missed it?
    // In Pot Detail, we have: Back (0), Time (14), Result (43), Container (41), Ingredients.
    // In Board Detail: Back (0), Input (28), Tool (30), Output (32+).
    // In List: Just recipes.
    
    // Maybe "Recipe View Component" is used as a placeholder or title icon?
    // Or maybe the user intends to replace the "Result Item" in the list with a generic "Recipe Book" icon that has the result as CustomModelData?
    // No, that doesn't make sense.
    
    // Let's assume for now "Recipe View Component" might be used for the "Book" icon when showing TAG ingredients?
    // In createIngredientIcon:
    // if (ing.getTag() != null) { ItemStack item = new ItemStack(Material.BOOK); ... }
    // Let's replace this Material.BOOK with getGuiAsset("recipe_view", Material.BOOK, "食谱组").
    
    private ItemStack createIngredientIcon(Ingredient ing, Player player) {
        if (ing.getCraftEngineId() != null) {
            ItemStack item = null;
            if (CraftEngineHook.isEnabled()) {
                item = CraftEngineHook.getItem(ing.getCraftEngineId());
                if (item != null) {
                    item = CraftEngineHook.localizeItem(item, player);
                }
            }
            if ((item == null || item.getType().isAir()) && ItemsAdderHook.isItemsAdderLoaded()) {
                item = ItemsAdderHook.getItem(ing.getCraftEngineId());
            }
            if (item != null && item.getType() != Material.AIR) return item;
        }
        
        if (ing.getTag() != null || ing.getExternalTag() != null) {
            ItemStack item = getGuiAsset("recipe_view", Material.BOOK, "pot.tag_ingredient");
            ItemMeta meta = item.getItemMeta();
            
            // Set Name (Localize tag name?)
            // We don't have a good way to localize the tag key itself easily without a map
            // But we can append the tag key
            String tagName = ing.getExternalTag() != null ? ing.getExternalTag() : (ing.getTag() != null ? ing.getTag().getKey().toString() : "Unknown");
            meta.displayName(ConfigManager.getGuiText("pot.tag_ingredient_format", "<tag>", tagName));
            
            // Add warning if empty
            if (ing.getCachedTagItems() == null || ing.getCachedTagItems().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                lore.add(ConfigManager.getGuiText("pot.invalid_tag_empty"));
                meta.lore(lore);
            } else {
                 // List items in lore
                 List<Component> lore = new ArrayList<>();
                 lore.add(ConfigManager.getGuiText("pot.tag_contains")); // "Contains items:"
                 
                 List<ItemStack> tagItems = ing.getCachedTagItems();
                 int limit = 10;
                 for (int i = 0; i < Math.min(tagItems.size(), limit); i++) {
                     ItemStack tagItem = tagItems.get(i);
                     Component name = getDisplayName(tagItem, player);
                     lore.add(Component.text(" - ", NamedTextColor.GRAY).append(name));
                 }
                 
                 if (tagItems.size() > limit) {
                     lore.add(ConfigManager.getGuiText("pot.tag_more", "<count>", String.valueOf(tagItems.size() - limit)));
                 }
                 meta.lore(lore);
            }
            item.setItemMeta(meta);
            return item;
        }
        
        if (ing.getMaterial() != null) {
            ItemStack item = new ItemStack(ing.getMaterial());
            // ... (rest of logic)
            if (item.getType().isAir() || !item.getType().isItem()) {
                 ItemStack barrier = new ItemStack(Material.BARRIER);
                 ItemMeta meta = barrier.getItemMeta();
                 meta.displayName(ConfigManager.getGuiText("pot.invalid_ingredient"));
                 barrier.setItemMeta(meta);
                 return barrier;
            }

            if (ing.getCustomModelData() != 0) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setCustomModelData(ing.getCustomModelData());
                    item.setItemMeta(meta);
                }
            }
            return item;
        }
        
        return new ItemStack(Material.BARRIER);
    }

    private Component getDisplayName(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) return Component.text("空气");
        
        if (CraftEngineHook.isEnabled()) {
            item = CraftEngineHook.localizeItem(item, player);
        }

        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().displayName();
        }
        return Component.translatable(item.getType().translationKey());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (activeAnimationTasks.containsKey(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().cancelTask(activeAnimationTasks.remove(event.getPlayer().getUniqueId()));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof MenuHolder)) return;
        MenuHolder holder = (MenuHolder) inv.getHolder();

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;
        
        String menuType = holder.type; 
        
        // Handle Navigation
        if (meta.getPersistentDataContainer().has(KEY_MENU_TYPE, PersistentDataType.STRING)) {
            String type = meta.getPersistentDataContainer().get(KEY_MENU_TYPE, PersistentDataType.STRING);
            int targetPage = meta.getPersistentDataContainer().getOrDefault(KEY_PAGE, PersistentDataType.INTEGER, 0);
            
            if ("pot_prev".equals(type) || "pot_next".equals(type) || "pot_back".equals(type)) {
                openPotRecipeMenu(player, targetPage);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
            } else if ("board_prev".equals(type) || "board_next".equals(type) || "board_back".equals(type)) {
                openBoardRecipeMenu(player, targetPage);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
            }
            return;
        }

        // Handle Recipe Click
        if (meta.getPersistentDataContainer().has(KEY_RECIPE_ID, PersistentDataType.STRING)) {
            String id = meta.getPersistentDataContainer().get(KEY_RECIPE_ID, PersistentDataType.STRING);
            int currentPage = holder.page;

            if ("pot_list".equals(menuType)) {
                for (Recipe r : plugin.getCookingManager().getRecipes()) {
                    if (r.getId().equals(id)) {
                        openPotRecipeDetail(player, r, currentPage);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
                        break;
                    }
                }
            } else if ("board_list".equals(menuType)) {
                try {
                    int index = Integer.parseInt(id);
                    List<CuttingBoardManager.CuttingRecipe> recipes = plugin.getCuttingBoardManager().getRecipes();
                    if (index >= 0 && index < recipes.size()) {
                        openBoardRecipeDetail(player, recipes.get(index), currentPage);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private ItemStack resolveItem(String id) {
        // 1. Try ItemsAdder
        if (ItemsAdderHook.isItemsAdderLoaded()) {
            ItemStack iaItem = ItemsAdderHook.getItem(id);
            if (iaItem != null) return iaItem;
        }
        // 2. Try CraftEngine
        if (CraftEngineHook.isEnabled()) {
            ItemStack ceItem = CraftEngineHook.getItem(id);
            if (ceItem != null) return ceItem;
        }
        // 3. Try Vanilla
        Material mat = Material.matchMaterial(id);
        if (mat != null) {
            return new ItemStack(mat);
        }
        return null;
    }

    private static class MenuHolder implements InventoryHolder {
        String type;
        int page;
        public MenuHolder(String type, int page) { 
            this.type = type; 
            this.page = page;
        }
        @Override public @NotNull Inventory getInventory() { return null; }
    }
}
