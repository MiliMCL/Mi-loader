# Mod 独立创造栏页签

> 目标：每个 Mod 在创造模式物品栏里有一个以 `modId` 命名的独立页，
> 页内包含它声明的方块（自动补 BlockItem）与显式物品。

---

## 1. 之前为什么没有页签

两个独立原因叠加，缺一不可：

1. **页签内容是空的。**
   26.2 的 `CreativeModeTab.shouldDisplay()` 对 `CATEGORY` 类型返回
   `hasAnyItems()`。平台只把「方块路径」传给标签，但 26.2 的 `Block`
   **不会自动生成同名 `BlockItem`** —— 原版每个方块物品都是
   `Items.registerBlock(...)` 显式注册的。查不到物品 → 内容为空 →
   页签被隐藏。
   Aurora 的 `aurora_shard` 也没有进入标签（只传了方块路径），
   Companion 则完全没有物品。

2. **页签没有可用的位置。**
   原版只有 TOP / BOTTOM 两行、每行 7 个位置（列 0..6），已全部被原版
   页签占用。`getTabX` 是 `27 * column`，列 8 会画到 195px 宽的面板之外；
   而且 `extractTabButton` 里的 `Mth.clamp(pos, 0, sprites.length)`
   对列 100 会返回 7，直接数组越界。

---

## 2. 现在的机制

### 2.1 数据流

```
Mod.initialize()
  └─ registry.block(...) / registry.item(...)
       └─ RegistrationPhase.defer(...)        ← 此刻还没注册

LoaderMain（所有 Mod initialize 之后）
  ├─ collectDeclaredBlocks() / collectDeclaredItems()
  └─ MinecraftGameProvider.declaredBlocks/Items(...)

EntryPointHook.runModRegistrations()
  ├─ ModCreativeTabs.registerTab(modId, blocks, items)   ← 先建页签
  │     └─ Registry.register(CREATIVE_MODE_TAB, key, tab)
  ├─ RegistrationPhase.defer(ensureBlockItems(...))       ← 追加到队尾
  └─ BootstrapGate.ensureBootstrapped()
        ├─ runRegistrations()   ← Mod 的 block/item + 自动 BlockItem
        └─ closeRegistryWindow() ← 补原版内容 + freeze
```

关键顺序：**页签可以先建（不依赖物品），内容必须等物品注册后再解析**。
`displayItems` 回调在创造栏首次打开时执行，那时 `BuiltInRegistries.ITEM`
已经完整。

### 2.2 自动 BlockItem

`ModCreativeTabs.ensureBlockItems(modId, blockPaths)` 在 deferred 队列末尾执行：

- 若 `modId:path` 已有物品（Mod 显式注册了同 id 的 `BlockItem`）→ 跳过；
- 否则从 `BuiltInRegistries.BLOCK` 取回方块，创建
  `new BlockItem(block, new Item.Properties().setId(itemKey))` 并注册到
  `BuiltInRegistries.ITEM`；
- 于是 `/give modId:path`、搜索页、创造栏都能拿到这个方块。

这修的是平台级缺陷，不是某个 Mod 的问题：**构造 Block 不等于拥有物品**。

### 2.3 页签位置：哨兵列 + 布局转换

- `ModCreativeTabs` 给页签分配 `column = 100 + index`（`MOD_COLUMN_BASE`）；
  偶数索引 `Row.TOP`、奇数 `Row.BOTTOM`。
- `MiliCreativeTabLayoutTransformer`（CORE 阶段）改写客户端
  `CreativeModeInventoryScreen`：
  1. `getTabX` 方法体替换为
     `CreativeTabLayout.tabX(imageWidth, column, alignedRight)`：
     原版列 0..6 的公式逐字节不变；哨兵列排到面板右侧
     `200 + (index / 2) * 27`。
  2. `extractTabButton` 中的 `Mth.clamp` 重定向为
     `CreativeTabLayout.clampSprite`，上界收到 `length - 1`。
- 效果：原版页签位置不动，Mod 页签出现在面板右侧的 TOP/BOTTOM 行上，
  可点击、可悬停、可选中。

> 为什么不用 `MODIFY_RETURN`：`getTabX` 是 private 实例方法，直接重写
> 方法体更可控；新方法体无分支，因此用 `COMPUTE_MAXS` 而不是
> `COMPUTE_FRAMES`，避免在目标类尚未 defineClass 时为类型合并加载
> Minecraft 类。

### 2.4 页名

暂时按需求直接使用 `modId`：`Component.literal(modId)`。
不再使用 `itemGroup.<modId>.<modId>` —— 没有 lang 条目时原版会显示
整串 key，比 modId 更难读。后续接入语言文件时只需改这一处。

---

## 3. 验证

### 3.1 构建期

```bash
./gradlew :mili-minecraft-integration:test \
  --tests '*CreativeTabLayoutTest' \
  --tests '*MiliCreativeTabLayoutTransformerTest'
```

- `CreativeTabLayoutTest`：原版列 0..6 的坐标不变；哨兵列落在 200/227；
  `clampSprite` 不再越界。
- `MiliCreativeTabLayoutTransformerTest`：从真实 26.2 jar 读取
  `CreativeModeInventoryScreen`，断言 `getTabX` 被替换、`Mth.clamp`
  被重定向，并通过 `BytecodeVerifier`（结构 + 数据流）。
- jar 缺失时测试显式跳过（本地开发机不持有 Minecraft 是正确状态）。

### 3.2 游戏内

1. 启动客户端，进入创造模式；
2. 打开物品栏，应看到面板右侧额外出现 `miliaurora` 与 `mili_companion`
   两个页签（一个 TOP、一个 BOTTOM）；
3. 点 `miliaurora`：应有 `aurora_crystal` 方块物品 + `aurora_shard`；
4. 点 `mili_companion`：应有 `aurora_lamp` 方块物品；
5. 搜索页输入 `aurora` 也能搜到上述物品。

对应日志：

```
[Mili] auto BlockItem miliaurora:crystal/aurora_crystal
[Mili] auto BlockItem mili_companion:crystal/aurora_lamp
Creative tab 'miliaurora' created with 2 item(s) ... (TOP) column=100
Creative tab 'mili_companion' created with 1 item(s) ... (BOTTOM) column=101
[Mili] creative tab content build for 'miliaurora' (2 items)
```

### 3.3 边界

- 同一 path 既声明 block 又声明 item：只出现一次（`LinkedHashSet` 去重）。
- Mod 显式注册了同 id 的 BlockItem：`ensureBlockItems` 检测到后跳过，
  不覆盖自定义组件。
- 页签数量超过屏幕右侧空间时，会向右延伸并被窗口裁掉；当前 2 个 Mod
  远未触及。要做分页需再引入第三行/翻页 UI，属于后续工作。
- 服务端也会注册创造栏页签（注册表是 common 的），但只有客户端有布局
  转换器与渲染；服务端不加载 `CreativeModeInventoryScreen`。
