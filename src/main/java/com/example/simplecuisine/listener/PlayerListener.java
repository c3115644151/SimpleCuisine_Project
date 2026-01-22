package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.cooking.Recipe;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

public class PlayerListener implements Listener {

    private final SimpleCuisine plugin;

    public PlayerListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        // Check if return container logic is enabled in config
        if (!plugin.getConfig().getBoolean("mechanics.return_container", true)) {
            return;
        }

        ItemStack item = event.getItem();
        // Check if this item corresponds to a SimpleCuisine recipe result
        Recipe recipe = plugin.getCookingManager().getRecipeByResult(item);

        if (recipe != null) {
            // Check if recipe defines a container (e.g., BOWL)
            if (recipe.isReturnContainer() && recipe.getContainers() != null && !recipe.getContainers().isEmpty()) {
                // Get the primary container
                com.example.simplecuisine.cooking.Ingredient containerIng = recipe.getContainers().get(0);
                ItemStack containerItem = recipe.createItemFromIngredient(containerIng);
                
                if (containerItem != null) {
                    // Check if vanilla already handles this return
                    // Vanilla handles:
                    // - Stews/Soups -> Bowl
                    // - Honey Bottle -> Glass Bottle
                    // - Potion -> Glass Bottle
                    // If the item is already one of these types AND the container matches vanilla expectation, vanilla will give the container.
                    // We only need to intervene if vanilla DOESN'T (e.g. custom item type acting as food).
                    
                    Material type = item.getType();
                    boolean vanillaHandles = 
                        ((type == Material.MUSHROOM_STEW ||
                        type == Material.RABBIT_STEW ||
                        type == Material.BEETROOT_SOUP ||
                        type == Material.SUSPICIOUS_STEW) && containerItem.getType() == Material.BOWL) ||
                        ((type == Material.HONEY_BOTTLE ||
                        type == Material.POTION) && containerItem.getType() == Material.GLASS_BOTTLE);
    
                    // If vanilla handles it, we might duplicate the bowl if we give one too.
                    // However, the user complained "eating it won't return".
                    // This implies vanilla is NOT handling it (maybe because it's a custom item using PAPER/etc).
                    
                    if (!vanillaHandles) {
                        giveContainer(event.getPlayer(), containerItem);
                    }
                }
            }
        }
    }

    private void giveContainer(Player player, ItemStack container) {
        HashMap<Integer, ItemStack> left = player.getInventory().addItem(container);
        if (!left.isEmpty()) {
            for (ItemStack drop : left.values()) {
                player.getWorld().dropItem(player.getLocation(), drop);
            }
        }
    }
}
