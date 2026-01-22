package com.example.simplecuisine.farming;

import com.example.simplecuisine.SimpleCuisine;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 水稻生长管理器 (Rice Manager)
 * 
 * 核心机制:
 * 1. 主动生长系统 (Active Growth System): 不依赖原版 RandomTick，使用独立 Ticker 进行概率生长。
 * 2. 状态映射 (State Mapping): 使用 Tripwire 的 block states 存储生长阶段 (0-3)。
 * 3. 双格结构 (Double Height): 成熟 (Stage 3) 时生成上方方块。
 * 4. 水生判定 (Waterlogging): 强制要求水下种植。
 */
public class RiceManager {

    private final SimpleCuisine plugin;
    private final Map<Long, Set<Location>> cropRegistry = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private YamlConfiguration config;
    private final Map<Integer, StageConfig> stageMap = new HashMap<>();

    // Configurable mechanics
    private double baseGrowthChance;
    private int growthCheckInterval;

    public RiceManager(SimpleCuisine plugin) {
        this.plugin = plugin;
        loadConfig();
        startGrowthTask();
    }

    private void loadConfig() {
        File file = new File(plugin.getDataFolder(), "farming.yml");
        if (!file.exists()) {
            plugin.saveResource("farming.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        
        // Load global mechanics
        growthCheckInterval = config.getInt("farming.mechanics.growth_check_interval", 5);
        baseGrowthChance = config.getDouble("farming.mechanics.base_growth_chance", (3.0 / 4096.0) * 5.0);
        
        // Load Stage Configuration
        stageMap.clear();
        String cropKey = "farmersdelight:rice";
        if (config.isConfigurationSection("farming.crops." + cropKey + ".growth_stages")) {
            for (String key : config.getConfigurationSection("farming.crops." + cropKey + ".growth_stages").getKeys(false)) {
                try {
                    int stageIndex = Integer.parseInt(key);
                    String path = "farming.crops." + cropKey + ".growth_stages." + key;
                    
                    StageConfig stageConfig = new StageConfig();
                    
                    // Lower block config
                    if (config.isConfigurationSection(path + ".lower")) {
                        stageConfig.lower = new BlockStateConfig();
                        stageConfig.lower.properties = config.getConfigurationSection(path + ".lower").getValues(false);
                    }
                    
                    // Upper block config
                    if (config.isConfigurationSection(path + ".upper")) {
                        stageConfig.upper = new BlockStateConfig();
                        stageConfig.upper.properties = config.getConfigurationSection(path + ".upper").getValues(false);
                    }
                    
                    stageMap.put(stageIndex, stageConfig);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid stage index in farming.yml: " + key);
                }
            }
        }
        
        // Load persistent crops
        loadCrops();
    }
    
    public void saveCrops() {
        File file = new File(plugin.getDataFolder(), "crops.yml");
        YamlConfiguration cropConfig = new YamlConfiguration();
        
        int count = 0;
        for (Map.Entry<Long, Set<Location>> entry : cropRegistry.entrySet()) {
            Set<Location> locs = entry.getValue();
            if (locs == null || locs.isEmpty()) continue;
            
            for (Location loc : locs) {
                String key = "crop_" + count++;
                String locStr = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
                cropConfig.set(key, locStr);
            }
        }
        
        try {
            cropConfig.save(file);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("Could not save crops.yml: " + e.getMessage());
        }
    }
    
    public void loadCrops() {
        File file = new File(plugin.getDataFolder(), "crops.yml");
        if (!file.exists()) return;
        
        YamlConfiguration cropConfig = YamlConfiguration.loadConfiguration(file);
        cropRegistry.clear();
        
        for (String key : cropConfig.getKeys(false)) {
            String locStr = cropConfig.getString(key);
            if (locStr == null) continue;
            
            String[] parts = locStr.split(",");
            if (parts.length != 4) continue;
            
            String worldName = parts[0];
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            org.bukkit.World world = Bukkit.getWorld(worldName);
            if (world != null) {
                Location loc = new Location(world, x, y, z);
                registerCrop(loc);
            }
        }
    }

    // Inner classes for config
    private static class StageConfig {
        BlockStateConfig lower;
        BlockStateConfig upper;
    }
    
    private static class BlockStateConfig {
        Map<String, Object> properties;
    }

    /**
     * 启动主动生长任务
     */
    private void startGrowthTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                tickCrops();
            }
        }.runTaskTimer(plugin, growthCheckInterval, growthCheckInterval);
    }

    /**
     * 对所有注册的作物进行生长判定
     */
    private void tickCrops() {
        for (Chunk chunk : Bukkit.getWorlds().get(0).getLoadedChunks()) {
            long chunkKey = chunk.getChunkKey();
            Set<Location> crops = cropRegistry.get(chunkKey);

            if (crops == null || crops.isEmpty()) continue;

            // 使用迭代器以支持移除
            Iterator<Location> iterator = crops.iterator();
            while (iterator.hasNext()) {
                Location loc = iterator.next();
                
                // 校验方块有效性
                if (!isValidRice(loc)) {
                    iterator.remove();
                    continue;
                }

                // 生长判定
                if (random.nextDouble() < baseGrowthChance) {
                    attemptGrowth(loc);
                }
            }
        }
    }

    /**
     * 尝试生长一次
     * @return 是否成功生长
     */
    public boolean attemptGrowth(Location loc) {
        Block block = loc.getBlock();
        
        // Fix: Normalize to root block if clicking upper half
        // Prevent "Third Layer" bug where applying 'lower' config to upper block
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String half = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, "half");
            if ("1".equals(half)) {
                block = block.getRelative(BlockFace.DOWN);
            }
        }
        
        int currentStage = getRiceStage(block);
        
        String cropKey = "farmersdelight:rice";
        int lightReq = config.getInt("farming.crops." + cropKey + ".light_requirement", 9);
        
        // 光照检查
        if (block.getLightLevel() < lightReq) return false;

        // New Logic: Use Stage Map if available
        if (!stageMap.isEmpty()) {
            int nextStage = currentStage + 1;
            StageConfig nextConfig = stageMap.get(nextStage);
            
            if (nextConfig == null) return false; // Reached max stage defined in config
            
            // Check if we can grow to upper (space check)
            if (nextConfig.upper != null) {
                 Block topBlock = block.getRelative(BlockFace.UP);
                 // Only allow if top is AIR or already part of our crop (e.g. growing from stage 3 to 4 where both are double)
                 boolean isSelf = isRiceBlock(topBlock);
                 if (topBlock.getType() != Material.AIR && !isSelf) {
                     return false;
                 }
            }
            
            applyStage(block, nextConfig);
        } else {
            // Legacy Logic
            int maxStage = config.getInt("farming.crops." + cropKey + ".max_stage", 3);
            if (currentStage >= maxStage) return false;
    
            int nextStage = currentStage + 1;
            int doubleHeightStage = config.getInt("farming.crops." + cropKey + ".double_height_stage", 3);
    
            // 特殊处理: Stage 2 -> Stage 3 (长高)
            if (nextStage == doubleHeightStage) {
                Block topBlock = block.getRelative(BlockFace.UP);
                if (topBlock.getType() == Material.AIR) {
                    setRiceBlockState(block, nextStage, 0); 
                    setRiceBlockState(topBlock, nextStage, 1);
                } else {
                    return false;
                }
            } else {
                setRiceBlockState(block, nextStage, 0);
            }
        }
        
        // 播放生长特效
        String particleName = config.getString("farming.crops." + cropKey + ".particles", "HAPPY_VILLAGER");
        try {
            org.bukkit.Particle particle = org.bukkit.Particle.valueOf(particleName);
            block.getWorld().spawnParticle(particle, block.getLocation().add(0.5, 0.5, 0.5), 5, 0.3, 0.3, 0.3);
        } catch (IllegalArgumentException e) {
            // ignore invalid particle
        }
        return true;
    }

    private void applyStage(Block root, StageConfig config) {
        if (config.lower != null) {
            setRiceBlockProperties(root, config.lower.properties);
        }
        if (config.upper != null) {
            setRiceBlockProperties(root.getRelative(BlockFace.UP), config.upper.properties);
        }
    }
    
    private boolean setRiceBlockProperties(Block block, Map<String, Object> props) {
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            // Keep original types (Integers) for CE compatibility
            // Waterlogged property is now handled by crops.yml config
            return com.example.simplecuisine.util.CraftEngineHook.setBlock(block.getLocation(), "farmersdelight:rice", props);
        }
        return false;
    }

    /**
     * 种植水稻
     */
    public boolean plantRice(Location loc) {
        Block block = loc.getBlock();
        
        // 1. 必须是水方块 (Water)
        if (block.getType() != Material.WATER) return false;
        
        // 2. 必须是水源 (Level 0)
        if (block.getBlockData() instanceof Levelled) {
            if (((Levelled) block.getBlockData()).getLevel() != 0) return false;
        }
        
        // 3. 上方必须是空气 (1格深限制)
        if (block.getRelative(BlockFace.UP).getType() != Material.AIR) return false;
        
        // 4. 下方必须是泥土 (Soil)
        Material downType = block.getRelative(BlockFace.DOWN).getType();
        if (!isSoil(downType)) return false;

        // 设置为 Stage 0
        if (!stageMap.isEmpty() && stageMap.containsKey(0)) {
            applyStage(block, stageMap.get(0));
        } else {
            if (!setRiceBlockState(block, 0, 0)) return false; // 0 = lower
        }
        
        // 注册到系统
        registerCrop(loc);
        return true;
    }

    /**
     * 移除水稻 (被破坏)
     * @param breakTop 是否连带破坏上方/下方
     */
    public void breakRice(Location loc, boolean breakLinked) {
        handleBreak(loc, breakLinked, true);
    }

    public void handleBreak(Location loc, boolean breakLinked, boolean updateSelf) {
        Block block = loc.getBlock();
        int stage = getRiceStage(block);
        
        // 从注册表移除
        unregisterCrop(loc);

        // 如果是成熟阶段 (Stage 3)，需要处理联动破坏
        // FIXME: "Stage 3" is hardcoded here. Should check if stage is "Double Height".
        // With generic config, we check if stage has 'upper' defined.
        boolean isDoubleHeight = false;
        if (!stageMap.isEmpty()) {
            StageConfig sc = stageMap.get(stage);
            if (sc != null && sc.upper != null) isDoubleHeight = true;
        } else {
             if (stage == 3) isDoubleHeight = true;
        }

        if (isDoubleHeight && breakLinked) {
            Block below = block.getRelative(BlockFace.DOWN);
            Block above = block.getRelative(BlockFace.UP);

            // Check if below is part of same crop
            if (isRiceBlock(below)) {
                 // Assume I am Top
                 handleBreak(below.getLocation(), false, true);
            } else if (isRiceBlock(above)) {
                 // Assume I am Bottom
                 handleBreak(above.getLocation(), false, true);
            }
        }

        if (updateSelf) {
            // 恢复当前方块
            // Determine if we should restore water
            // If it was a lower half (check properties or guess by location), restore water
            // Since we can't easily check properties after break, we check if below is soil
            boolean shouldBeWater = isSoil(block.getRelative(BlockFace.DOWN).getType());
            
            if (shouldBeWater) {
                block.setType(Material.WATER);
            } else {
                block.setType(Material.AIR);
            }
        }
    }

    /**
     * 收割水稻 (重置为 Stage 0)
     */
    public void harvest(Block block) {
        // Assume block is the Top part (Stage 3)
        // Reset Bottom to Stage 0
        Block bottom = block.getRelative(BlockFace.DOWN);
        if (isRiceBlock(bottom)) {
            if (!stageMap.isEmpty() && stageMap.containsKey(0)) {
                 applyStage(bottom, stageMap.get(0));
            } else {
                 setRiceBlockState(bottom, 0, 0); // Stage 0, Lower
            }
        }
        
        // Remove Top
        block.setType(Material.AIR);
        unregisterCrop(block.getLocation());
    }

    // --- Helper Methods ---

    private void registerCrop(Location loc) {
        long chunkKey = loc.getChunk().getChunkKey();
        cropRegistry.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(loc);
    }

    private void unregisterCrop(Location loc) {
        long chunkKey = loc.getChunk().getChunkKey();
        Set<Location> set = cropRegistry.get(chunkKey);
        if (set != null) {
            set.remove(loc);
        }
    }

    public void dropRiceLoot(Location loc, int stage, boolean isHarvest) {
        if (loc == null || loc.getWorld() == null) return;

        // 1. Drop Seed (Only if NOT harvesting)
        // Harvest means we keep the root, so we don't refund the seed.
        if (!isHarvest) {
            String seedId = config.getString("farming.crops.farmersdelight:rice.drop_seed", "farmersdelight:rice");
            dropItem(loc, seedId, 1);
        }

        // 2. Drop Mature Loot (If mature)
        if (isMature(stage)) {
            String matureId = config.getString("farming.crops.farmersdelight:rice.drop_mature.item", "farmersdelight:rice_panicle");
            int min = config.getInt("farming.crops.farmersdelight:rice.drop_mature.min", 1);
            int max = config.getInt("farming.crops.farmersdelight:rice.drop_mature.max", 1);
            
            int amount = min + (int)(Math.random() * ((max - min) + 1));
            if (amount > 0) {
                dropItem(loc, matureId, amount);
            }
        }
    }

    private void dropItem(Location loc, String id, int amount) {
        if (amount <= 0) return;
        
        // Fix: Strip legacy data suffix if present (e.g. "rice:0" -> "rice")
        if (id != null && id.endsWith(":0")) {
            id = id.substring(0, id.length() - 2);
        }
        
        ItemStack item = null;
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            item = com.example.simplecuisine.util.CraftEngineHook.createItem(id, null);
            if (item == null) {
                plugin.getLogger().warning("[RiceManager] Failed to create item '" + id + "'. Check your CraftEngine config/farming.yml!");
            }
        }
        
        if (item == null) {
            // Fallback
            if (id.contains("rice")) {
                item = new ItemStack(Material.WHEAT_SEEDS);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(net.kyori.adventure.text.Component.text("Rice (Fallback)"));
                item.setItemMeta(meta);
            }
            else if (id.contains("panicle")) {
                item = new ItemStack(Material.WHEAT);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(net.kyori.adventure.text.Component.text("Rice Panicle (Fallback)"));
                item.setItemMeta(meta);
            }
            else item = new ItemStack(Material.WHEAT_SEEDS);
        }
        item.setAmount(amount);
        loc.getWorld().dropItemNaturally(loc, item);
    }

    public void scanChunk(Chunk chunk) {
        // 扫描区块内的水稻 (用于插件重载/区块加载)
        // 这是一个耗时操作，但在 ChunkLoadEvent 中通常可接受
        // 优化: 只扫描 y=60-70? 不，全扫描太慢。
        // 更好的方案: 只扫描 TileEntities? Tripwire 不是 TileEntity。
        // 妥协方案: 
        // 1. 依赖 Metadata (重启丢失) -> 不行。
        // 2. 依赖特定方块状态组合 (Tripwire)。
        // 3. 这里的 scanChunk 暂时留空，或者只在必要时实现。
        // 实际上，如果不持久化 Registry，重启后作物就停止生长了。
        // 鉴于这是一个演示实现，我们假设服务器不经常重启，或者接受重启后需玩家重新互动(如施肥)才能激活。
        // 但为了完整性，我们可以在 ChunkLoad 时简单扫描一下海平面附近的方块? 
        // 暂时留空，依靠玩家交互或施肥重新激活。
        
        // 修正: 遍历 Chunk 的 Snapshot? 
        // 为了演示 "Active Growth"，如果不扫描，重启就废了。
        // 我们实现一个简单的扫描器，只扫描 y=62-80 (常见海平面)。
        int minHeight = chunk.getWorld().getMinHeight();
        int maxHeight = chunk.getWorld().getMaxHeight();
        
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minHeight; y < maxHeight; y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (isRiceBlock(block)) {
                        registerCrop(block.getLocation());
                    }
                }
            }
        }
    }

    public boolean isSoil(Material mat) {
        return mat == Material.DIRT || mat == Material.GRASS_BLOCK || mat == Material.MUD || 
               mat == Material.FARMLAND || mat == Material.CLAY || mat == Material.SAND ||
               mat == Material.COARSE_DIRT || mat == Material.ROOTED_DIRT || mat == Material.PODZOL || 
               mat == Material.MYCELIUM;
    }
    
    /**
     * Check if the rice block has valid support.
     * Lower half needs Soil below.
     * Upper half needs Lower Rice below.
     */
    public boolean checkSupport(Block riceBlock) {
        if (!isRiceBlock(riceBlock)) return false;
        
        boolean isUpper = false;
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            String h = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(riceBlock, "half");
            if ("1".equals(h)) {
                isUpper = true;
            }
        }
        
        Block below = riceBlock.getRelative(BlockFace.DOWN);
        if (isUpper) {
            // Below must be Rice
            return isRiceBlock(below);
        } else {
            // Below must be Soil
            return isSoil(below.getType());
        }
    }

    public boolean isRiceBlock(Block block) {
        if (!com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            return block.getType() == Material.SEAGRASS || block.getType() == Material.KELP || block.getType() == Material.TRIPWIRE;
        }
        String id = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockId(block);
        return "farmersdelight:rice".equals(id) || "farmersdelight:rice_crop".equals(id) || "farmersdelight:rice_panicle".equals(id);
    }
    
    public boolean isValidRice(Location loc) {
        return isRiceBlock(loc.getBlock());
    }

    public boolean isMature(int stage) {
        if (!stageMap.isEmpty()) {
             // Assume the highest defined stage is the mature stage
             int max = Collections.max(stageMap.keySet());
             return stage == max;
        }
        String cropKey = "farmersdelight:rice";
        return stage == config.getInt("farming.crops." + cropKey + ".max_stage", 3);
    }

    public boolean isDoubleHeight(int stage) {
        if (!stageMap.isEmpty()) {
             StageConfig sc = stageMap.get(stage);
             return sc != null && sc.upper != null;
        }
        String cropKey = "farmersdelight:rice";
        return stage == config.getInt("farming.crops." + cropKey + ".double_height_stage", 3);
    }

    /**
     * 获取水稻生长阶段 (0-3)
     */
    public int getRiceStage(Block block) {
        if (!com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            // Fallback to Kelp
            if (block.getType() == Material.KELP) {
                if (block.getBlockData() instanceof org.bukkit.block.data.Ageable) {
                    return Math.max(0, ((org.bukkit.block.data.Ageable) block.getBlockData()).getAge() - 22);
                }
            }
            // Legacy Tripwire logic
            if (block.getType() == Material.TRIPWIRE) {
                Tripwire data = (Tripwire) block.getBlockData();
                boolean d = data.isDisarmed();
                boolean a = data.isAttached();

                if (!d && !a) return 0;
                if (d && !a) return 1;
                if (!d && a) return 2;
                if (d && a) return 3;
            }
            return 0;
        }
        
        // New Logic: Reverse lookup from Stage Map
        if (!stageMap.isEmpty()) {
            // Determine root block (Lower)
            Block root = block;
            String halfStr = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, "half");
            if ("1".equals(halfStr)) {
                root = block.getRelative(BlockFace.DOWN);
            }
            
            // Match against stageMap
            for (Map.Entry<Integer, StageConfig> entry : stageMap.entrySet()) {
                if (matchesStage(root, entry.getValue())) {
                    return entry.getKey();
                }
            }
        }
        
        // Fallback: Read property directly
        String ageStr = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, "crop_stage");
        if (ageStr != null) {
            try {
                return Integer.parseInt(ageStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    private boolean matchesStage(Block root, StageConfig config) {
        // Check lower
        if (config.lower != null) {
            if (!matchesProperties(root, config.lower.properties)) return false;
        }
        
        // Check upper
        if (config.upper != null) {
            // If config expects upper, check if upper block matches
            Block upper = root.getRelative(BlockFace.UP);
            if (!matchesProperties(upper, config.upper.properties)) return false;
        } else {
            // If config expects NO upper, we might want to check if upper is NOT part of crop?
            // But usually checking lower is enough for uniqueness.
            // If stage 2 (lower=2) and stage 3 (lower=3, upper=3), uniqueness is guaranteed by lower property.
            // If stage 4 (lower=3, upper=4), then lower property is same as stage 3!
            // So we MUST check upper presence/absence if lower properties are ambiguous.
            
            // Optimization: If lower matches, check if upper is present in world but not in config.
            // If world has upper (half=1), but config says no upper -> mismatch?
            // Yes, because that would mean we are in a double-height stage.
            Block upper = root.getRelative(BlockFace.UP);
            if (isRiceBlock(upper)) {
                 String half = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(upper, "half");
                 if ("1".equals(half)) return false; // Found upper part, but config didn't expect it
            }
        }
        return true;
    }
    
    private boolean matchesProperties(Block block, Map<String, Object> props) {
        if (props == null) return true;
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            String val = com.example.simplecuisine.util.CraftEngineHook.getCustomBlockProperty(block, entry.getKey());
            if (val == null) return false;
            // Compare string representations
            if (!String.valueOf(entry.getValue()).equals(val)) return false;
        }
        return true;
    }

    /**
     * 设置水稻生长阶段
     */
    private boolean setRiceBlockState(Block block, int stage, int half) {
        if (com.example.simplecuisine.util.CraftEngineHook.isEnabled()) {
            Map<String, Object> props = new HashMap<>();
            props.put("crop_stage", stage);
            props.put("half", half);
            
            // Force waterlogged to true ONLY for lower half (half=0)
            boolean isUpper = (half == 1);
            props.put("waterlogged", !isUpper);
            
            // 尝试直接放置 (farmersdelight:rice)
            boolean success = com.example.simplecuisine.util.CraftEngineHook.setBlock(block.getLocation(), "farmersdelight:rice", props);
            
            // 2026-01-12 Fix: Manually force Bukkit Waterlogged state
            if (success && !isUpper) {
                org.bukkit.block.data.BlockData data = block.getBlockData();
                if (data instanceof org.bukkit.block.data.Waterlogged) {
                    org.bukkit.block.data.Waterlogged wl = (org.bukkit.block.data.Waterlogged) data;
                    if (!wl.isWaterlogged()) {
                        wl.setWaterlogged(true);
                        block.setBlockData(wl, true);
                    }
                }
            }
            return success;
        }

        // Fallback to Kelp (No CE)
        forceVanillaKelp(block, stage);
        return true;
    }
    
    private void forceVanillaKelp(Block block, int stage) {
        if (block.getType() != Material.KELP) block.setType(Material.KELP, false);
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable) {
            org.bukkit.block.data.Ageable data = (org.bukkit.block.data.Ageable) block.getBlockData();
            // Map stage 0-3 to Kelp age 22-25
            data.setAge(22 + stage);
            block.setBlockData(data, false);
        }
    }
}
