# SimpleCuisine 技术手册

## 1. 炉灶系统 (Stove System)
炉灶系统允许玩家在指定的炉灶方块上烹饪物品。

### 1.1 外部插件集成
- **方块识别**: 通过 **ItemsAdder** 和 **CraftEngine** 的自定义方块 ID 识别炉灶。
- **配方支持**: 
    - **输入**: 支持原版材质 (Material)、原版标签 (Vanilla Tags, 如 `#minecraft:wool`) 以及自定义物品 ID (ItemsAdder/CraftEngine)。
    - **输出**: 支持原版材质和自定义物品 ID。
### 1.2 配置说明 (`stove_recipes.yml`)
```yaml
recipes:
  custom_steak:
    input: "itemsadder:raw_steak"   # 支持 Material, IA ID, CE ID, Tags
    result: "itemsadder:cooked_steak"
    time: 200 # 烹饪时间 (Tick)
    experience: 1.0 # 经验值
  tag_example:
    input: "#minecraft:logs"
    result: "minecraft:charcoal"
    time: 100
```

## 2. 砧板系统 (Cutting Board System)
砧板系统允许玩家在砧板上通过使用刀具或其他工具加工物品。

### 2.1 外部插件集成
- **物品支持**: 支持原版物品及 **ItemsAdder** / **CraftEngine** 自定义物品作为输入和输出。
- **视觉自定义**: 支持在 `config.yml` 中配置物品在砧板上的显示位置、缩放和旋转。

### 2.2 配置说明 (`cutting_board_recipes.yml`)
支持单输出和多输出两种格式。

**单输出格式 (Legacy):**
```yaml
recipes:
  example_cut:
    input: "minecraft:beef"
    tool: "knife"
    result: "farmersdelight:minced_beef"
    amount: 2
    sound: "item.axe.strip"
```

**多输出格式 (Recommended):**
```yaml
recipes:
  complex_cut:
    input: "minecraft:golden_apple"
    tool: "knife"
    results:
      - "minecraft:gold_nugget:8"  # 格式: id:数量
      - "minecraft:apple:1"
      - "itemsadder:custom_item:2" # 支持外部插件物品
    sound: "block.amethyst_block.break"
```

### 2.3 视觉配置 (`config.yml`)
```yaml
cutting_board:
  visual:
    offset_y: 0.07   # 垂直偏移 (默认 0.07)
    scale: 0.5       # 物品缩放比例 (默认 0.5)
    block_scale: 0.25 # 方块缩放比例 (默认 0.25, 适用于方块类型物品)
    rotation_x: 90.0 # 旋转角度 (默认 90.0)
```

## 3. 厨锅系统 (Cooking Pot System)
厨锅系统允许玩家制作复杂的汤、炖菜等料理。

### 3.1 外部插件集成
- **配方支持**:
    - **输入**: 支持原版材质、原版标签 (`#minecraft:wool`)、ItemsAdder/CraftEngine 自定义物品。
    - **容器**: 支持自定义容器物品（如碗、桶）。
    - **输出**: 支持自定义物品，并在 GUI 中正确显示自定义名称（支持 ItemsAdder/CraftEngine 物品名解析）。

### 3.2 配置说明 (`pot_recipes.yml`)
```yaml
recipes:
  example_soup:
    ingredients:
      - "minecraft:carrot:2"
      - "#minecraft:wool" # 使用标签
      - "itemsadder:custom_vegetable"
    container: "minecraft:bowl"
    result: "itemsadder:vegetable_soup"
    time: 600
    experience: 0.5
    display_name: "<green>蔬菜汤"
```

### 3.3 标签与动态展示
- **动态轮播**: 在配方详情界面中，对于使用标签（如 `#minecraft:logs`）的原料，系统会自动解析该标签下的所有物品，并进行动态轮播展示。
- **外部标签**: 支持形如 `#namespace:key` 的外部插件标签（如 `#farmersdelight:leafs`），系统会自动解析 CraftEngine 或其他 Hook 提供的物品列表。
- **智能本地化 (Smart Localization)**:
        - 系统针对 CraftEngine 物品实现了深度本地化支持。无论物品是通过 ID 直接获取还是通过标签 (Tag) 批量解析，都能正确应用本地化名称。
        - **混合源解析 (Hybrid Resolution)**:
            - **配置直读 (Config Hijack)**: 系统启动时直接扫描 `CraftEngine/resources` 下的 YAML 配置文件，建立 `Key -> item-name` 的高速缓存。这确保了即使 API 返回的是原版材质（如牛排），系统也能强制显示配置中的真实名称（如卷心菜）。
            - **TranslationManager**: 若配置缓存未命中，则通过反射调用 TranslationManager API。
            - **智能回退**: 仅在所有本地化手段失效时使用格式化 ID。
- **详情提示**: 鼠标悬停在标签原料上时，Lore 会列出该标签包含的所有具体物品名称（如 "- Wagyu Beef"），且名称会自动适应玩家语言。最多显示10项，超过部分显示剩余数量。

### 3.4 物品本地化与智能继承 (Smart Inheritance)
为解决外部插件（如CraftEngine）物品在GUI中显示原始名称的问题，系统采用了多级回退策略：
1.  **直接反射 (Reflection)**: 尝试直接从CustomItem对象获取显示名称。
2.  **浏览器回退 (ItemBrowserManager)**: 如果反射失败，尝试从ItemBrowserManager检索物品（支持Key和String ID），并复制其显示名称和Lore。
3.  **持久化ID兜底 (Persistent ID Fallback)**: 在物品生成阶段将CustomID注入到PersistentDataContainer。当CraftEngine内部NBT数据丢失（如在Tag解析或复杂堆叠逻辑中）时，系统优先读取此PDC数据以重建物品上下文，确保本地化流程永不中断。
4.  **智能语言降级 (Smart Locale Fallback)**: 在获取翻译时，如果玩家语言环境返回的是ASCII名称（通常为英文ID或原名），系统会自动尝试获取 `zh_CN` 语言环境的名称。若中文翻译有效（非ASCII），则优先使用中文名称，确保在中文服务器环境下显示正确。
5.  **ID兜底 (ID Fallback)**: 如果以上都失败，使用物品ID作为显示名称。
6.  **玩家上下文 (Player Context)**: 在构建和获取物品时，始终传递Player对象以确保语言环境正确的本地化。

### 3.5 厨锅逻辑
- **缓冲槽堆叠**: 允许同种食物在缓冲槽中堆叠（直至最大堆叠数），但严格校验配方匹配性。系统在判定“同种食物”时会忽略 Lore 的差异，确保带有不同描述（如动态生成的Lore）但本质相同的食物能正常堆叠。
- **离线持久化**: 即使世界未加载，挂起的烹饪锅数据也会被保存，防止数据丢失。
- **动态更新**: 配置重载时，所有活跃的烹饪锅GUI会实时刷新。

## 4. API 参考
配方浏览界面支持高度自定义与交互优化。

### 4.1 标题偏移与图标 (GUI Layout)
系统支持在 `config.yml` 中为厨锅主界面 (`cooking_pot.gui.title`) 和配方菜单 (`recipe_menu`) 配置复杂的图文混排。
- **Offset 解析**: 自动读取 `config.yml` 中的 offset 配置（如 `<shift:-8>`），并调用 `RecipeMenuManager.getNegativeSpace` 转换为负空间字符。
- **Icon 解析**: 支持 `<image:ID>` 标签，自动解析 CraftEngine 字体图标 ID。
- **优先级**: `config.yml` 中的配置优先级高于 `messages.yml`，确保复杂的自定义 UI 布局（包含 `<offset>` 和 `<icon>`）不会被简单的文本覆盖。

```yaml
# config.yml 示例
cooking_pot:
  gui:
    title: "<white><offset><icon><shift:-8>烹饪锅"
    layout:
      craftengine:
        offset: "<shift:-16>"
        icon: "<image:farmersdelight:cooking_pot_bg>"
```

### 4.2 导航与展示
- **翻页按钮**: 始终显示在 Slot 0 (上一页) 和 Slot 8 (下一页)。若无更多页，按钮显示为灰色不可用状态。
- **砧板动画**: 砧板配方详情页的刀具槽位会自动循环展示所有允许的刀具（配置于 `allowed_knives`），支持 ItemsAdder 自定义刀具。
- **原料展示**: 厨锅配方原料若数量大于1，会自动拆分为多个单物品格展示，清晰表达“每格消耗一个”的逻辑。

### 4.3 资源映射 (Resource Mapping)
支持通过 `config.yml` 中的 `gui_assets` 部分直接映射线上资源包的贴图，无需本地 ItemsAdder/CraftEngine 配置文件。这是实现**配置驱动客户端渲染**的关键机制。

- **机制**: 通过指定 `Material` 和 `CustomModelData` 直接引用资源包内容。
- **组件**: 支持配置上一页、下一页、返回键、烹饪时间图标、查看食谱图标等。
- **配置示例**:
```yaml
gui_assets:
  # 按钮与图标
  previous_page: { material: PAPER, custom_model_data: 50003, name: "&e上一页" }
  next_page: { material: PAPER, custom_model_data: 50004, name: "&e下一页" }
  back_button: { material: PAPER, custom_model_data: 50005, name: "&c返回" }
  cooking_time: { material: CLOCK, custom_model_data: 50006, name: "&b时间" }
  recipe_view: { material: KNOWLEDGE_BOOK, custom_model_data: 50007, name: "&a查看菜谱" } # 用于烹饪锅GUI入口和Tag原料展示
```

### 4.4 智能更新机制
- **动态重载**: `config.yml` 重载时，系统会自动检测所有活跃的烹饪锅 GUI，并实时更新其标题和布局配置（如 Offset 和 Icon），无需玩家关闭重开。
- **离线保护**: 对于区块卸载或世界未加载的情况，系统实现了“待处理锅”存储机制，确保这些锅在被重新加载时能正确初始化并应用最新的 GUI 配置。

## 5. 标签系统 (Tag System)
所有工作方块（炉灶、厨锅、砧板、煎锅）的配方输入均支持标签。

### 5.1 语法与解析
使用 `#` 前缀指定标签名。系统支持原版 Tag、ItemsAdder Tag 以及 CraftEngine Tag。
- **格式**: `#namespace:tag_name`
- **示例**: `#minecraft:logs`, `#farmersdelight:leafs`

### 5.2 智能回退机制 (Smart Fallback)
针对 CraftEngine 的 Tag 系统，插件实现了三级解析策略：
1. **缓存优先**: 系统会缓存已解析的 Tag 物品列表，避免重复查询。
2. **Bukkit 原生**: 优先尝试解析为 Bukkit 原生的 Material Tag。
3. **CraftEngine 深度集成 (Enhanced)**:
   - **格式兼容**: 自动去除 `#` 前缀，并尝试多种 Key 格式（如 `namespace:key` 和默认 `minecraft:key`），以兼容 CraftEngine 的内部存储格式。
   - **反射调用**: 通过反射调用 `ItemManager.itemIdsByTag(Key)` 获取精确 ID 列表。
   - **全面覆盖**: 若精确匹配失败，尝试使用 `TagManager` 相关方法，确保自定义物品标签（如 `#farmersdelight:leafs`）能被正确识别和解析。

## 6. 国际化与本地化 (I18n)
系统实现了完全的国际化支持，实现了逻辑代码与显示文本的彻底分离。

### 6.1 配置文件 (`messages.yml`)
位于插件配置文件夹下，支持 MiniMessage 格式的富文本（`<red>`, `<bold>`, `<gradient:red:blue>`）。
```yaml
gui:
  common:
    previous_page: "<yellow>上一页"
  pot:
    heat_on: "<red>🔥 热源活跃"
    heat_off: "<gray>❄ 无热源"
command:
  only_player: "<red>只有玩家可以使用此命令"
  reload_success: "<green>SimpleCuisine 配置已重载！"
```

### 6.2 文本解析架构
- **ConfigManager**: 统一管理所有文本获取。
- **动态占位符**: 支持运行时参数替换，例如 `<count>`, `<recipe>`, `<percent>`。
- **GUI 降级策略**: 
  1. `config.yml` 显式定义的 `name`/`title` (支持 Legacy 颜色代码 `&`)。
  2. `messages.yml` 定义的 I18n 键值 (支持 MiniMessage)。

## 7. 性能与稳定性 (Performance & Stability)
### 7.1 GUI 动画管理
- **集中管理**: 所有 GUI 动画任务（如 Tag 轮播、刀具展示）统一由 `RecipeMenuManager` 的 `activeAnimationTasks` 映射表管理。
- **安全销毁**: 实现了防御性的任务取消机制，防止在玩家退出或插件重载时因并发修改导致 NPE 或资源泄漏。
- **优雅关闭**: 插件禁用时 (`onDisable`) 会强制调用 `stopAllAnimations()`，清理所有后台动画线程，确保服务器关闭过程无残留任务。

## 8. 外部插件集成 (External Integrations)
### 8.1 CraftEngine 深度集成
插件通过反射机制实现了与 CraftEngine 的无缝对接，解决了从物品识别到 GUI 展示的全链路问题。

#### 8.1.1 物品本地化桥接 (Localization Bridge)
由于 CraftEngine 的物品构建机制在某些上下文中可能回退到默认语言（英文）或 API 返回原版名称（如“牛排”而非“卷心菜”），本插件实现了**混合源解析 (Hybrid Resolution)** 策略：

1.  **配置劫持 (Config Hijack) [New]**:
    *   **机制**: 系统启动时递归扫描 `plugins/CraftEngine/resources` 目录下的所有 YAML 配置文件。
    *   **作用**: 直接建立 `id -> item-name` 的内存映射，完全绕过 CraftEngine 的 Java API。
    *   **优势**: 即使 API 出现幻觉或 Bug，也能强制显示配置文件中定义的真实中文名称。
    *   **智能匹配**: 支持模糊匹配（忽略 Namespace）和插件目录自动发现。

2.  **玩家上下文优先 (Player Context)**: 使用 `Player` 对象调用 `translateWithLocaleMethod`，优先尝试获取玩家当前语言环境（Locale）的翻译。若失败，自动回退到 `zh_CN`。
3.  **上下文重构 (Context Reconstruction)**: 使用玩家上下文 (`Player Context`) 调用 `buildItemStack`，确保物品 Lore 中的变量（如动态属性）针对玩家正确解析。
4.  **智能继承 (Smart Inheritance)**: 在重构过程中，如果物品丢失显示名称（变为原版材质名），系统会自动从 `CustomItem` 定义或 `ItemBrowserManager` 中恢复原始名称。
5.  **翻译注入**: 最后尝试调用 `TranslationManager` 获取本地化翻译并覆盖显示名称。

#### 8.1.2 标签系统兼容
针对 CraftEngine 的自定义标签（如 `#farmersdelight:leafs`），系统实现了增强型解析逻辑：
1.  **自定义物品优先 (Custom Item Priority)**: 使用 `customItemIdsByTag` 接口优先获取自定义物品 ID，而非可能解析为原版材质的通用 ID。这彻底解决了自定义标签物品在 GUI 中显示为原版回退物品（如牛排、干海带）的问题。
2.  **PDC 注入**: 在解析过程中自动将 CraftEngine ID 注入物品的 PersistentDataContainer，确保后续流程能正确识别其自定义身份并应用本地化。
3.  **格式兼容**: 自动去除 `#` 前缀并尝试多种 Key 格式的解析逻辑，确保能正确读取 `TagManager` 中的物品列表。

## 9. 常见问题 (FAQ)
*   **Q: 缓冲槽无法堆叠食物？**
    *   A: 已修复。现在缓冲槽 (Slot 7) 允许交互，并支持同种食物堆叠（需符合配方匹配规则）。
*   **Q: 标签物品显示为原版物品（如牛排）而不是自定义物品？**
    *   A: 已修复。系统现在优先通过 `customItemIdsByTag` 获取真正的自定义物品 ID，并注入身份数据以确保显示正确的本地化名称。
*   **Q: "查看食谱组件" 图标如何修改？**
    *   A: 在 `config.yml` 中配置 `gui_assets.recipe_view`。
*   **Q: 烹饪锅无法识别单个格子的多个食材？**
    *   A: 已修复。烹饪逻辑现在正确处理单格堆叠的食材扣除。

## 10. 开发日志
*   **2025-01-09**: 
    *   修复缓冲槽交互限制，恢复堆叠功能。
    *   实现配置驱动的 "查看食谱组件" 图标 (`gui_assets.recipe_view`)。
    *   优化标签物品解析逻辑，优先使用 CE 物品并支持本地化显示。
    *   添加 CustomItem 反射方法转储逻辑，以辅助解决名称丢失问题。
    *   **关键修复**: 当 `displayName` 方法不可用时，使用 `translationKey` 配合 `TranslationManager` 获取本地化名称。
    *   添加详细 Debug 日志以排查标签解析问题。
    *   **架构升级**: 引入 `CraftEngineConfigLoader` 实现配置劫持，彻底解决 API 无法获取本地化名称的顽疾。
    *   **性能优化**: 重构 `CookingPot.consumeIngredients` 实现批量物品消耗与容器高效返还。
    *   **文档更新**: 新增 `config-hijack-pattern` 与 `retrospective-deepwiki-failure` 知识沉淀。
    *   **指令修复**: 修复 `/sc recipes` 指令失效问题，现在支持 `pot` 和 `board` 子命令。
    *   **机制配置**: 新增 `mechanics.return_container` 配置项，允许开关“食用后返还容器”逻辑，以解决与外部插件的冲突。

## 15. 系统稳定性 (System Stability)

### 15.1 启动时序优化 (Startup Sequence Optimization)
为了解决与 CraftEngine、ItemsAdder 等大型依赖插件的加载竞态条件 (Race Condition)，本插件实现了 **延迟配方加载 (Delayed Recipe Loading)** 机制。

*   **问题背景**: 在服务器启动时，若本插件在依赖插件完全初始化之前尝试加载配方（特别是涉及 Tag 解析和自定义物品 ID 的配方），会导致配方部分成分丢失（如 Tag 为空）或加载失败。
*   **解决方案**: 所有 Manager（CookingManager, StoveManager, CuttingBoardManager）的配方加载逻辑已从构造函数中移除，并统一调度至 `onEnable` 的末尾（下一 Tick）执行。这确保了所有依赖插件的 API 和内部数据已完全准备就绪。
*   **影响**: 即使在网络延迟较高或插件加载顺序不确定的线上服务器环境中，也能保证配方加载的完整性。

## 11. 指令列表 (Command List)
| 指令 | 描述 | 权限 |
| :--- | :--- | :--- |
| `/sc givepot` | 获取一个厨锅物品 | OP |
| `/sc reload` | 重载插件配置 | OP |
| `/sc debug` | 开启/关闭调试模式 | OP |
| `/sc diagnose <recipe_id>` | 诊断手持物品是否匹配指定配方 | OP |
| `/sc inspect` | 查看手持物品的详细信息 (Material, CMD, CE ID, PDC) | OP |
| `/sc cleanup` | 清理残留的幽灵实体 | OP |
| `/sc recipes pot` | 打开厨锅配方大全 | 玩家 |
| `/sc recipes board` | 打开砧板配方大全 | 玩家 |

## 12. 全局配置 (Global Configuration)
本章节记录 `config.yml` 中的全局机制配置。

### 12.1 容器返还机制
- **配置项**: `mechanics.return_container`
- **默认值**: `true`
- **说明**: 
    - 控制玩家食用 SimpleCuisine 制作的食物（如汤、炖菜）后，是否返还容器（如碗）。
    - **冲突解决**: 如果您使用了 CraftEngine 或其他插件，并且发现食用后返还了两个碗（或者逻辑冲突），请将此选项设置为 `false`。

## 13. 农业系统 (Farming System)
本插件实现了增强的作物生长与收获机制，特别是对 Farmer's Delight 内容的支持。

### 13.1 水稻 (Rice)
实现了完整的水稻种植、生长与收获逻辑。

- **种植条件 (Strict Planting)**:
    - **水源要求**: 必须种植在 **1格深的静止水源 (Source Water)** 中。
        - 水深必须刚好为 1 格（上方必须是空气）。
        - 必须是水源方块 (Level 0)，不能是流动水。
    - **土壤要求**: 水下必须是有效的土地方块。
        - 支持: 泥土 (Dirt), 草方块 (Grass Block), 泥巴 (Mud), 耕地 (Farmland), 粘土 (Clay), 沙子 (Sand), 砂土 (Coarse Dirt), 缠根泥土 (Rooted Dirt), 灰化土 (Podzol), 菌丝 (Mycelium)。
    - **种植物品**: `farmersdelight:rice` (稻米)。
- **生长机制**:
    - 采用 **主动生长系统 (Active Growth System)**，不依赖原版随机刻。
    - **生长阶段**: 默认 0-3 阶段 (支持通过 `farming.yml` 自定义任意数量的阶段与模型映射)。
    - **双格结构**: 成熟时自动长高 (可配置触发阶段)。
- **交互反馈**:
    - **错误提示**: 种植失败（如水深不对、土壤不适）时，会在 Actionbar 显示红色错误提示，而非聊天栏刷屏。
    - **调试模式**: 开启 `/sc debug` 后，管理员可查看详细的种植判断逻辑（如射线检测结果、方块状态分析），普通玩家不可见。
- **收获逻辑**:
    - **成熟掉落**: 2-4 个 `farmersdelight:rice_panicle`。
    - **刀具加成**: 使用 `knife` 类工具额外掉落 `farmersdelight:straw`。
- **CraftEngine 集成**:
    - 完全支持自定义方块模型与物品 ID。

## 14. 农业配置 (Farming Configuration)
所有的作物生长参数均可配置，文件位于 `farming.yml`。

```yaml
farming:
  mechanics:
    growth_check_interval: 5 # 生长检查间隔 (ticks)
    base_growth_chance: 0.00366 # 基础生长概率
  
  crops:
    farmersdelight:rice:
      type: DOUBLE_HEIGHT_WATER_CROP
      light_requirement: 9
      
      # 动态生长阶段配置 (支持任意数量的阶段与模型映射)
      growth_stages:
        0:
          lower: { crop_stage: 0, half: 0 }
        1:
          lower: { crop_stage: 1, half: 0 }
        2:
          lower: { crop_stage: 2, half: 0 }
        3:
          lower: { crop_stage: 3, half: 0 }
          upper: { crop_stage: 3, half: 1 } # 双格阶段

      # Legacy 配置 (仅在 growth_stages 为空时生效)
      max_stage: 3
      double_height_stage: 3
      
      drop_mature:
        item: "farmersdelight:rice_panicle"
        min: 2
        max: 4
      drop_seed: "farmersdelight:rice"
      extra_drop:
        tool_keyword: "knife"
        item: "farmersdelight:straw"
        chance: 1.0
      particles: "HAPPY_VILLAGER"
```

