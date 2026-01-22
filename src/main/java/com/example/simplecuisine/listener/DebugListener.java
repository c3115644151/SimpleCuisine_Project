package com.example.simplecuisine.listener;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.util.CraftEngineHook;
import com.example.simplecuisine.util.ItemsAdderHook;
import net.kyori.adventure.text.Component;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class DebugListener implements Listener {
    private final SimpleCuisine plugin;

    public DebugListener(SimpleCuisine plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        // 只处理主手右键方块
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        
        Player player = event.getPlayer();
        // 检查玩家是否开启了 debug 模式
        if (!plugin.isDebugger(player.getUniqueId())) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        player.sendMessage(Component.text("§e[SimpleCuisine Debug] §f正在分析方块..."));
        player.sendMessage(Component.text("§7类型: " + block.getType()));
        player.sendMessage(Component.text("§7位置: " + block.getX() + ", " + block.getY() + ", " + block.getZ()));

        // Check CraftEngine
        boolean ceEnabled = CraftEngineHook.isEnabled();
        player.sendMessage(Component.text("§7CraftEngine Hook: " + (ceEnabled ? "§a启用" : "§c禁用")));
        if (ceEnabled) {
            try {
                String ceId = CraftEngineHook.getCustomBlockId(block);
                player.sendMessage(Component.text("§7CraftEngine ID: " + (ceId == null ? "§cnull" : "§a" + ceId)));
            } catch (Exception e) {
                player.sendMessage(Component.text("§cCraftEngine Error: " + e.getMessage()));
            }
        }

        // Check ItemsAdder
        boolean iaLoaded = ItemsAdderHook.isItemsAdderLoaded();
        player.sendMessage(Component.text("§7ItemsAdder Hook: " + (iaLoaded ? "§a启用" : "§c禁用")));
        if (iaLoaded) {
            try {
                String iaId = ItemsAdderHook.getCustomId(block);
                player.sendMessage(Component.text("§7ItemsAdder ID: " + (iaId == null ? "§cnull" : "§a" + iaId)));
            } catch (Exception e) {
                player.sendMessage(Component.text("§cItemsAdder Error: " + e.getMessage()));
            }
        }

        // Check Internal Managers
        boolean isPot = plugin.getPotManager().isPot(block.getLocation());
        boolean isStove = plugin.getStoveManager().isStove(block.getLocation());
        boolean isSkillet = plugin.getSkilletManager().isSkillet(block.getLocation());
        
        player.sendMessage(Component.text("§7内部状态: " + 
            (isPot ? "§6[锅] " : "") + 
            (isStove ? "§6[炉灶] " : "") + 
            (isSkillet ? "§6[煎锅] " : "") +
            (!isPot && !isStove && !isSkillet ? "§8[无记录]" : "")
        ));
        
        event.setCancelled(true); // 阻止交互，专心调试
    }
}
