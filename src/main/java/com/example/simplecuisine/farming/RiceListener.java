package com.example.simplecuisine.farming;

import com.example.simplecuisine.SimpleCuisine;
import com.example.simplecuisine.config.ConfigManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;

import org.bukkit.event.block.BlockGrowEvent;

public class RiceListener implements Listener {

    private final SimpleCuisine plugin;
    private final RiceManager riceManager;

    public RiceListener(SimpleCuisine plugin, RiceManager riceManager) {
        this.plugin = plugin;
        this.riceManager = riceManager;
    }

    /**
     * Prevent soil under Rice from turning into Grass
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.BlockFormEvent event) {
        if (event.getNewState().getType() == Material.GRASS_BLOCK) {
            if (isProtectedSoil(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        if (event.getSource().getType() == Material.GRASS_BLOCK && event.getBlock().getType() == Material.DIRT) {
            if (isProtectedSoil(event.getBlock())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Helper to check if soil should be protected (i.e. has Rice/Water crops above)
     */
    private boolean isProtectedSoil(Block soil) {
        Block above = soil.getRelative(BlockFace.UP);
        
        // 1. Check strict Rice definition
        if (riceManager.isRiceBlock(above)) return true;
        
        // 2. Generic check for water crops (Kelp, Seagrass) to be safe
        // This covers cases where CustomID might be missing or vanilla behavior kicks in
        Material type = above.getType();
        return type == Material.KELP || type == Material.KELP_PLANT || 
               type == Material.SEAGRASS || type == Material.TALL_SEAGRASS;
    }

    /**
     * 防止水稻 (Kelp) 自然生长
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        if (riceManager.isRiceBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 种植监听
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // Material.isInteractable() is deprecated but still the best way to check for functional blocks
    public void onPlant(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        boolean isDebug = plugin.isDebug();

        Block clickedBlock = event.getClickedBlock();
        
        // 1. RayTrace fallback for RIGHT_CLICK_AIR (often happens when clicking water with non-block items)
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR) {
             try {
                 org.bukkit.util.RayTraceResult result = player.rayTraceBlocks(5.0, org.bukkit.FluidCollisionMode.SOURCE_ONLY);
                 if (result != null) {
                     clickedBlock = result.getHitBlock();
                 }
             } catch (Exception ignored) {
                 // Ignore if RayTrace API is missing
             }
        } else if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        
        if (clickedBlock == null) return;

        ItemStack item = event.getItem();
        if (item == null) return;
        
        boolean isSeed = false;
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String id = com.example.simplecuisine.util.CraftEngineHook.getCustomItemId(item);
            // Allow planting with rice panicle (seed) or rice block item
            if ("farmersdelight:rice_panicle".equals(id) || "farmersdelight:rice".equals(id)) {
                 isSeed = true;
            }
        } else {
            if (item.getType() == Material.WHEAT_SEEDS) isSeed = true;
        }
        
        if (!isSeed) return;
        
        // Allow interaction with functional blocks (Chest, Workbench, etc.) unless sneaking
        if (clickedBlock.getType().isInteractable() && !player.isSneaking()) {
            return;
        }
        
        Location plantLoc = clickedBlock.getLocation();
        boolean potentialTarget = false;
        
        // Scenario 1: Clicked Water directly
        if (clickedBlock.getType() == Material.WATER) {
             potentialTarget = true;
        } 
        // Scenario 2: Clicked Soil under water
        else if (riceManager.isSoil(clickedBlock.getType())) {
             Block above = clickedBlock.getRelative(BlockFace.UP);
             if (above.getType() == Material.WATER) {
                 plantLoc = above.getLocation();
                 potentialTarget = true;
             } else if (isDebug) {
                 player.sendMessage("§c[RiceDebug] Soil clicked, but above is " + above.getType());
             }
        } else {
             if (isDebug) player.sendMessage("§c[RiceDebug] Clicked " + clickedBlock.getType() + " (Not Water or Soil)");
        }
        
        if (!potentialTarget) {
            // Failure: Invalid block clicked (Gravel, Stone, etc.)
            player.sendActionBar(ConfigManager.getMessage("rice_plant_fail"));
            event.setCancelled(true);
            return;
        }
        
        // 尝试种植
        boolean success = riceManager.plantRice(plantLoc);
        if (success) {
            // 扣除物品
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }
            if (isDebug) player.sendMessage("§a[RiceDebug] 水稻种植成功！");
        } else {
            // 新的：失败时通过动作栏提示
            player.sendActionBar(ConfigManager.getMessage("rice_plant_fail"));

            if (isDebug) {
                // 分析失败原因
                Block block = plantLoc.getBlock();
                String reason = "未知";
                if (block.getType() != Material.WATER) reason = "目标不是水源 (" + block.getType() + ")";
                else if (block.getBlockData() instanceof org.bukkit.block.data.Levelled && ((org.bukkit.block.data.Levelled) block.getBlockData()).getLevel() != 0) reason = "水源等级不是 0（非源头）";
                else if (block.getRelative(BlockFace.UP).getType() != Material.AIR) reason = "上方不是空气";
                else if (!riceManager.isSoil(block.getRelative(BlockFace.DOWN).getType())) reason = "下方不是耕地 (" + block.getRelative(BlockFace.DOWN).getType() + ")";
                else reason = "内部错误（放置方块失败 - 请查看控制台）";
                
                player.sendMessage("§c[RiceDebug] 种植失败。原因：" + reason);
            }
        }
        
        event.setCancelled(true); // 无论成功与否，都取消原版交互，防止放置 Ghost Block 或错误位置的方块
    }

    /**
     * 骨粉催熟监听
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBoneMeal(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BONE_MEAL) return;
        
        Block block = event.getClickedBlock();
        if (block == null || !riceManager.isRiceBlock(block)) return;
        
        // 尝试催熟
        if (riceManager.attemptGrowth(block.getLocation())) {
            // 成功生长，扣除骨粉
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }
            // 额外粒子效果
            block.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 15, 0.5, 0.5, 0.5);
        }
        
        event.setCancelled(true); // 阻止原版骨粉行为 (虽然对 Custom Block 可能无效，但以防万一)
    }

    // Helper to handle Knife/Straw drops
    private void handleStrawDrop(Location loc, Player player) {
        if (!com.example.simplecuisine.util.CraftEngineHook.isEnabled()) return;
        
        ItemStack handItem = player.getInventory().getItemInMainHand();
        String itemId = com.example.simplecuisine.util.CraftEngineHook.getCustomItemId(handItem);
        if (itemId != null && itemId.contains("knife")) {
             ItemStack straw = com.example.simplecuisine.util.CraftEngineHook.createItem("farmersdelight:straw", player);
             if (straw != null) {
                 loc.getWorld().dropItemNaturally(loc, straw);
             }
        }
    }

    /**
     * 破坏监听 (联动破坏 & 掉落)
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // 0. Check for Soil Break (Linkage)
        // If we break the block UNDER the rice, the rice should break too
        Block upBlock = block.getRelative(BlockFace.UP);
        if (riceManager.isRiceBlock(upBlock)) {
            // Drop loot before breaking
            int stage = riceManager.getRiceStage(upBlock);
            boolean isMature = riceManager.isMature(stage);
            riceManager.dropRiceLoot(upBlock.getLocation(), stage, false);
            if (isMature) handleStrawDrop(upBlock.getLocation(), event.getPlayer());
            
            // Break the rice
            riceManager.breakRice(upBlock.getLocation(), true);
        }
        
        if (riceManager.isRiceBlock(block)) {
            Player player = event.getPlayer();
            int stage = riceManager.getRiceStage(block);
            
            // Check for Harvest (Mature Stage, Top Block if Double Height)
            boolean isMature = riceManager.isMature(stage);
            boolean isDoubleHeight = riceManager.isDoubleHeight(stage);
            boolean isHarvestTarget = false;
            
            if (isMature) {
                if (isDoubleHeight) {
                    // Must be the upper block (check if block below is rice)
                    if (riceManager.isRiceBlock(block.getRelative(BlockFace.DOWN))) {
                        isHarvestTarget = true;
                    }
                } else {
                    // Single height, so always harvestable if mature
                    isHarvestTarget = true;
                }
            }
            
            if (isHarvestTarget) {
                 // Harvest Logic
                 event.setCancelled(true);
                 
                 // Drop Loot (Panicles + Straw)
                 // isHarvest = true (Don't drop seed)
                 riceManager.dropRiceLoot(block.getLocation(), stage, true);
                 handleStrawDrop(block.getLocation(), player);
                 
                 // Reset Crop
                 riceManager.harvest(block);
                 
                 // Sound
                 block.getWorld().playSound(block.getLocation(), org.bukkit.Sound.BLOCK_CROP_BREAK, 1.0f, 1.0f);
                 return;
            }

            // Normal Break Logic
            // 处理联动破坏 (updateSelf=true, 强制由 Manager 接管方块状态恢复)
            event.setCancelled(true); // Cancel vanilla break to prevent water removal issues
            
            // Drop Loot
            // isHarvest = false (Drop seed)
            riceManager.dropRiceLoot(block.getLocation(), stage, false);
            if (isMature) handleStrawDrop(block.getLocation(), player);
            
            // Manually break
            riceManager.breakRice(block.getLocation(), true);
        }
    }


    /**
     * 物理监听 (防止悬空，允许水流)
     * 
     * 逻辑更新 (2026-01-13):
     * 1. 不再拦截任何物理事件，彻底解决 "锁水" 问题。
     * 2. 主动检查下方支撑 (Support Check)，如果支撑丢失 (如泥土被挖)，则主动破坏水稻。
     *    这解决了 "悬空" 问题，同时保留了掉落物逻辑。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        if (riceManager.isRiceBlock(event.getBlock())) {
            // Check Support
            if (!riceManager.checkSupport(event.getBlock())) {
                // Support lost! Manually break to ensure Loot + Water restoration
                riceManager.breakRice(event.getBlock().getLocation(), true);
            }
            
            // DO NOT CANCEL.
            // Let vanilla physics handle water flow and updates.
            // Rice (Kelp base) is aquatic and handles water naturally.
        }
    }

    /**
     * 区块加载 (重新扫描 Active Crops)
     * 
     * [Performance Fix] 
     * Disabled automatic full-chunk scanning to prevent massive lag spikes (17% CPU usage).
     * Rice crops will rely on persistence or manual interaction to re-register.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // 性能优化：禁用全区块扫描
        // 原有逻辑会导致每个区块加载时遍历 98k+ 方块，严重拖累主线程。
        // 如果需要恢复生长，建议通过持久化数据或玩家交互触发。
        /*
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            riceManager.scanChunk(event.getChunk());
        }, 20L);
        */
    }
}
