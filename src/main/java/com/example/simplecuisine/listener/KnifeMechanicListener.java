package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class KnifeMechanicListener implements Listener {

    private final SimpleCuisine plugin;
    private final Set<String> allowedKnives = new HashSet<>();
    private final Set<Material> grassMaterials = new HashSet<>();
    private final List<Tag<Material>> grassTags = new ArrayList<>();
    private boolean debug = false;

    public KnifeMechanicListener(SimpleCuisine plugin) {
        this.plugin = plugin;
        loadConfigValues();
    }

    private void loadConfigValues() {
        debug = plugin.getConfig().getBoolean("debug", false);
        allowedKnives.clear();
        grassMaterials.clear();
        grassTags.clear();

        // Load Allowed Knives
        List<String> knives = plugin.getConfig().getStringList("cutting_board.allowed_knives");
        if (knives != null && !knives.isEmpty()) {
            allowedKnives.addAll(knives);
        } else {
            // Strict compliance: Do not hardcode fallbacks. Log warning if configuration is missing.
            plugin.getLogger().warning("[KnifeMechanic] 'cutting_board.allowed_knives' is empty! Knife mechanics will not work until configured.");
        }

        // Load Grass Blocks/Tags
        List<String> grassList = plugin.getConfig().getStringList("knife_mechanics.grass_drop.target_blocks");
        if (grassList != null && !grassList.isEmpty()) {
            for (String entry : grassList) {
                if (entry.startsWith("#")) {
                    addTagIfPresent(entry.substring(1));
                } else {
                    addMaterialIfPresent(entry);
                }
            }
        }
    }

    private void addMaterialIfPresent(String name) {
        try {
            grassMaterials.add(Material.valueOf(name));
        } catch (IllegalArgumentException ignored) {
            // Material not found in this version
        }
    }

    private void addTagIfPresent(String tagName) {
        try {
            NamespacedKey key;
            if (tagName.contains(":")) {
                key = NamespacedKey.fromString(tagName);
            } else {
                key = NamespacedKey.minecraft(tagName);
            }

            if (key != null) {
                // Priority: Block Registry -> Item Registry
                Tag<Material> tag = org.bukkit.Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    tag = org.bukkit.Bukkit.getTag(Tag.REGISTRY_ITEMS, key, Material.class);
                }
                
                if (tag != null) {
                    grassTags.add(tag);
                } else {
                    plugin.getLogger().warning("[KnifeMechanic] Tag not found: #" + tagName);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[KnifeMechanic] Invalid tag format: #" + tagName);
        }
    }

    private boolean isKnife(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        
        String id = null;
        if (ItemsAdderHook.isItemsAdderLoaded()) {
            id = ItemsAdderHook.getCustomItemId(item);
        }
        if (id == null && CraftEngineHook.isEnabled()) {
            id = CraftEngineHook.getCustomItemId(item);
        }
        
        if (debug) {
            plugin.getLogger().info("[KnifeMechanic] Checking item: " + item.getType() + ", CustomID: " + id);
            if (id != null) {
                plugin.getLogger().info("[KnifeMechanic] Allowed knives: " + allowedKnives);
                plugin.getLogger().info("[KnifeMechanic] Match found: " + allowedKnives.contains(id));
            }
        }
        
        return id != null && allowedKnives.contains(id);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        if (!plugin.getConfig().getBoolean("knife_mechanics.grass_drop.enabled", true)) return;
        
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        
        if (!isKnife(tool)) return;

        Material blockType = event.getBlock().getType();
        boolean isTarget = grassMaterials.contains(blockType);
        
        if (!isTarget) {
            for (Tag<Material> tag : grassTags) {
                if (tag.isTagged(blockType)) {
                    isTarget = true;
                    break;
                }
            }
        }
        
        if (debug) {
             plugin.getLogger().info("[KnifeMechanic] BlockBreak: " + blockType + ", IsTarget: " + isTarget);
        }
        
        if (!isTarget) return;

        double chance = plugin.getConfig().getDouble("knife_mechanics.grass_drop.chance", 0.25);
        if (ThreadLocalRandom.current().nextDouble() < chance) {
            String itemId = plugin.getConfig().getString("knife_mechanics.grass_drop.item", "farmersdelight:straw");
            dropCustomItem(event.getBlock().getLocation(), itemId, 1);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        
        if (killer == null) return;
        ItemStack tool = killer.getInventory().getItemInMainHand();
        if (!isKnife(tool)) return;

        // Determine Drop Logic
        String configPath = null;
        if (entity.getType() == EntityType.PIG) {
            configPath = "knife_mechanics.mob_drops.pig";
        } else if (entity.getType() == EntityType.HOGLIN) {
            configPath = "knife_mechanics.mob_drops.hoglin";
        }

        if (debug) {
            plugin.getLogger().info("[KnifeMechanic] EntityDeath: " + entity.getType() + ", ConfigPath: " + configPath);
        }

        if (configPath == null) return;
        if (!plugin.getConfig().getBoolean(configPath + ".enabled", true)) return;
        if (entity instanceof Ageable && !((Ageable) entity).isAdult()) return; // Baby check

        // Looting Calculation (Quantity Bonus)
        // Vanilla Meat Drop: Random(min, max + lootingLevel + 1) -> Actually usually Random(1, 3 + level)
        // Our Config: min, max, bonus_per_level
        // Formula: count = Random(min, max + (level * bonus) + 1) -> No, standard random is exclusive upper bound.
        // Let's implement: count = Random(min, max + (level * bonus)) inclusive? 
        // ThreadLocalRandom.nextInt(min, max + 1) is [min, max].
        
        int lootingLevel = 0;
        @SuppressWarnings("deprecation")
        Enchantment looting = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("looting"));
        if (looting != null) {
            lootingLevel = tool.getEnchantmentLevel(looting);
        }

        int min = plugin.getConfig().getInt(configPath + ".min_amount", 1);
        int max = plugin.getConfig().getInt(configPath + ".max_amount", 1);
        int bonusMax = plugin.getConfig().getInt(configPath + ".looting_bonus_max", 1); // Extra max per level
        
        // Effective max = max + (level * bonus)
        int effectiveMax = max + (lootingLevel * bonusMax);
        
        // Safety swap if min > effectiveMax
        if (min > effectiveMax) effectiveMax = min;
        
        // Random amount [min, effectiveMax]
        int amount = ThreadLocalRandom.current().nextInt(min, effectiveMax + 1);

        if (amount > 0) {
            String itemId = plugin.getConfig().getString(configPath + ".item", "farmersdelight:ham");
            dropCustomItem(entity.getLocation(), itemId, amount);
        }
    }

    private void dropCustomItem(org.bukkit.Location location, String itemId, int amount) {
        ItemStack item = null;
        
        // Priority: CraftEngine -> ItemsAdder -> Vanilla (if implemented)
        if (CraftEngineHook.isEnabled()) {
            item = CraftEngineHook.getItem(itemId);
        }
        
        // If not found in CE, try ItemsAdder if enabled (optional fallback logic)
        if (item == null && ItemsAdderHook.isItemsAdderLoaded()) {
             item = ItemsAdderHook.getItem(itemId);
        }

        if (item != null) {
            item.setAmount(amount);
            location.getWorld().dropItemNaturally(location, item);
        }
    }
}
