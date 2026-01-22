package com.example.simplecuisine.command;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.config.ConfigManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Location;

import com.example.simplecuisine.cooking.Recipe;
import com.example.simplecuisine.cooking.Ingredient;
import java.util.Map;

public class SimpleCuisineCommand implements CommandExecutor, org.bukkit.command.TabCompleter {

    private final SimpleCuisine plugin;

    public SimpleCuisineCommand(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("givepot")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_player"));
                    return true;
                }
                Player player = (Player) sender;
                if (!player.isOp()) {
                    player.sendMessage(ConfigManager.getMessage("command.only_op"));
                    return true;
                }
                player.getInventory().addItem(plugin.getItemManager().getCookingPot());
                player.sendMessage(ConfigManager.getMessage("command.give_pot_success"));
                return true;
            } else if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.isOp()) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_op"));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(ConfigManager.getMessage("command.reload_success"));
                return true;
            } else if (args[0].equalsIgnoreCase("recipes")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_player"));
                    return true;
                }
                Player player = (Player) sender;
                
                if (args.length < 2) {
                    sender.sendMessage(ConfigManager.getMessage("command.recipes_usage"));
                    return true;
                }
                
                String type = args[1];
                if (type.equalsIgnoreCase("pot")) {
                    plugin.getRecipeMenuManager().openPotRecipeMenu(player, 0);
                    return true;
                } else if (type.equalsIgnoreCase("board")) {
                    plugin.getRecipeMenuManager().openBoardRecipeMenu(player, 0);
                    return true;
                } else {
                    sender.sendMessage(ConfigManager.getMessage("command.recipes_usage"));
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("debug")) {
                 if (!(sender instanceof Player)) return true;
                 Player player = (Player) sender;
                 if (!player.isOp()) return true;
                 
                 plugin.toggleDebugger(player.getUniqueId());
                 boolean isDebug = plugin.isDebugger(player.getUniqueId());
                 
                 player.sendMessage(ConfigManager.getMessage("command.debug_mode_status", "<status>", isDebug ? "开启" : "关闭"));
                 if (isDebug) {
                     player.sendMessage(ConfigManager.getMessage("command.debug_hint"));
                 }
                 return true;
            } else if (args[0].equalsIgnoreCase("diagnose")) {
                if (!(sender instanceof Player)) return true;
                Player player = (Player) sender;
                if (!player.isOp()) return true;

                if (args.length < 2) {
                    player.sendMessage(ConfigManager.getMessage("command.diagnose_usage"));
                    return true;
                }

                String recipeId = args[1];
                Recipe recipe = plugin.getCookingManager().getRecipeById(recipeId);
            
            if (recipe == null) {
                player.sendMessage(ConfigManager.getMessage("command.recipe_not_found", "<id>", recipeId));
                player.sendMessage(ConfigManager.getMessage("command.loaded_recipes_list"));
                for (Recipe r : plugin.getCookingManager().getRecipes()) {
                    player.sendMessage(Component.text(" - " + r.getId()));
                }
                return true;
            }

                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir()) {
                    player.sendMessage(ConfigManager.getMessage("command.hold_ingredient"));
                    return true;
                }

                player.sendMessage(ConfigManager.getMessage("command.diagnose_header", "<id>", recipeId));
                player.sendMessage(ConfigManager.getMessage("command.current_held", "<type>", held.getType().toString(), "<amount>", String.valueOf(held.getAmount())));
                
                // Debug raw CE ID
                String ceId = CraftEngineHook.getItemId(held);
                player.sendMessage(ConfigManager.getMessage("command.ce_id_detected", "<id>", (ceId != null ? ceId : "None")));

                // Debug Tag matching specifically
                player.sendMessage(ConfigManager.getMessage("command.tags_info"));

                boolean matchedAny = false;
                for (Map.Entry<Ingredient, Integer> entry : recipe.getIngredients().entrySet()) {
                    Ingredient ing = entry.getKey();
                    boolean matches = ing.matches(held);
                    
                    String reqDesc;
                    if (ing.getCraftEngineId() != null) {
                        reqDesc = "CE:" + ing.getCraftEngineId();
                    } else if (ing.getKey().startsWith("#")) {
                         reqDesc = "Tag:" + ing.getKey();
                         // Detailed Tag info
                         try {
                             org.bukkit.Tag<org.bukkit.Material> t = ing.getTag();
                             if (t != null) {
                                 boolean isTagged = t.isTagged(held.getType());
                                 reqDesc += " (isTagged=" + isTagged + ")";
                                 // Check if material is present in values manually to debug
                                 boolean contains = t.getValues().contains(held.getType());
                                 reqDesc += " (contains=" + contains + ")";
                             } else {
                                 reqDesc += " (TagObj=null)";
                             }
                         } catch (Exception e) {
                             reqDesc += " (Error checking tag)";
                         }
                    } else {
                        reqDesc = "Mat:" + ing.getMaterial();
                    }
                    
                    if (matches) {
                        player.sendMessage(ConfigManager.getMessage("command.requirement_match", "<req>", reqDesc));
                        matchedAny = true;
                    } else {
                        player.sendMessage(ConfigManager.getMessage("command.requirement_mismatch", "<req>", reqDesc));
                    }
                }

                if (matchedAny) {
                    player.sendMessage(ConfigManager.getMessage("command.diagnose_success"));
                } else {
                    player.sendMessage(ConfigManager.getMessage("command.diagnose_fail"));
                }
                return true;
            } else if (args[0].equalsIgnoreCase("dump")) {
                if (!sender.isOp()) return true;
                if (args.length > 1 && args[1].equalsIgnoreCase("ce")) {
                CraftEngineHook.dumpRegisteredItems(sender);
            } else if (args.length > 1 && args[1].equalsIgnoreCase("ce-methods")) {
                sender.sendMessage(ConfigManager.getMessage("command.dump_removed"));
            } else {
                sender.sendMessage(ConfigManager.getMessage("command.dump_usage"));
            }
                return true;
            } else if (args[0].equalsIgnoreCase("inspect")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_player"));
                    return true;
                }
                Player player = (Player) sender;
                if (!player.isOp()) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_op"));
                    return true;
                }
                ItemStack item = player.getInventory().getItemInMainHand();
                if (item.getType().isAir()) {
                    sender.sendMessage(ConfigManager.getMessage("command.inspect_no_item"));
                    return true;
                }
                
                player.sendMessage(ConfigManager.getMessage("command.inspect_header"));
                player.sendMessage(ConfigManager.getMessage("command.inspect_material", "<material>", item.getType().name()));
                
                int cmd = 0;
                if (item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
                    cmd = item.getItemMeta().getCustomModelData();
                    player.sendMessage(ConfigManager.getMessage("command.inspect_cmd", "<cmd>", String.valueOf(cmd)));
                } else {
                    player.sendMessage(ConfigManager.getMessage("command.inspect_no_cmd"));
                }
                
                String ceId = null;
                if (CraftEngineHook.isEnabled()) {
                    ceId = CraftEngineHook.getItemId(item);
                    if (ceId != null) {
                        player.sendMessage(ConfigManager.getMessage("command.inspect_ce_id", "<id>", ceId));
                    } else {
                        player.sendMessage(ConfigManager.getMessage("command.inspect_no_ce_id"));
                        
                        // Dump all PDC keys for debugging
                        if (item.hasItemMeta()) {
                            org.bukkit.persistence.PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
                            if (!pdc.getKeys().isEmpty()) {
                                player.sendMessage(ConfigManager.getMessage("command.inspect_pdc_keys"));
                                for (org.bukkit.NamespacedKey key : pdc.getKeys()) {
                                    String val = "unknown";
                                    if (pdc.has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
                                        val = pdc.get(key, org.bukkit.persistence.PersistentDataType.STRING);
                                    }
                                    player.sendMessage(Component.text("§7 - " + key.toString() + " = " + val));
                                }
                            } else {
                                player.sendMessage(ConfigManager.getMessage("command.inspect_pdc_none"));
                            }
                        }
                    }
                }
                return true;
            } else if (args[0].equalsIgnoreCase("cleanup")) {
                 if (!(sender instanceof Player)) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_player"));
                    return true;
                 }
                 Player player = (Player) sender;
                 if (!player.isOp()) {
                    sender.sendMessage(ConfigManager.getMessage("command.only_op"));
                    return true;
                 }
                 
                 int count = 0;
                 int protectedCount = 0;
                 for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                     for (org.bukkit.entity.Entity e : world.getEntities()) {
                         if (e.getScoreboardTags().contains("sc_cooking_progress")) {
                             Location blockLoc = e.getLocation().getBlock().getLocation();
                 if (plugin.getPotManager().isPot(blockLoc)) {
                     protectedCount++;
                 } else {
                                 e.remove();
                                 count++;
                             }
                         }
                     }
                 }
                 
                 player.sendMessage(ConfigManager.getMessage("command.cleanup_finish", "<count>", String.valueOf(count)));
                 player.sendMessage(ConfigManager.getMessage("command.cleanup_protected", "<count>", String.valueOf(protectedCount)));
                 return true;
            }
        }
        
        sender.sendMessage(ConfigManager.getMessage("command.unknown_command"));
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return java.util.List.of("givepot", "reload", "diagnose", "inspect", "debug", "dump", "cleanup", "recipes");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("recipes")) {
            return java.util.List.of("pot", "board");
        }
        return java.util.List.of();
    }
}