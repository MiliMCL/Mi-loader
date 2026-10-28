# 主菜单液态玻璃转场 · 实现规格 v1（定稿）

- 日期：2026-10-24
- 状态：已实现（本机不跑 Java，编译与测试交 CI 验证）。实现落点：
  `MiliMenuTransitionTransformer`（双 RETURN 注入）/ `MenuTransitionDispatch`
  （接管+绘制）/ `GlassBridgeGenerator` + `MinecraftClassLoader#definePlatformBridge`
  （渲染桥接）/ `LiquidGlassTransition`（纯逻辑状态机）。
- 需求来源：主菜单点击「单人游戏 / 多人游戏」后，原版为瞬时换屏；本规格定义一段
  "液态玻璃"转场动画。交互小样经四轮迭代（方块崩解 → 液态玻璃 → 去水潭/通透化 →
  去毛玻璃）由用户确认定稿。

---

## 0. 锁定参数

| 参数 | 值 | 说明 |
|---|---|---|
| 涌起时长 | 300ms | 玻璃圆盘从点击中心扩张至覆盖整屏 |
| 泻落时长 | 360ms | 玻璃整盘加速下坠，液面扫过露出新屏 |
| 总时长 | ~660ms @ 1.0× | 可配 speed 0.6–1.4×（系统属性，见 §6） |
| 涌起缓动 | easeInOutCubic | `t<.5 ? 4t³ : 1-(-2t+2)³/2` |
| 泻落缓动 | easeInQuad | `t²`（重力加速感） |
| 液面波幅 A | 6px（GUI 逻辑像素） | 可配 wave 2–14px |
| 液面波长 | ~140px | 空间频率 0.045 rad/px，时间周期 210ms |
| 玻璃底色 | rgba(220,232,252, **0.04**) | 清玻璃定稿——**无模糊、无磨砂** |
| 玻璃描边 | rgba(255,255,255, 0.28) | 圆盘边缘 1px 高光圈 |
| 液面层透明度 | 涌起 0.20（随进度线性升）→ 泻落 0.26 | 液体高光，勿回到奶白 |
| 呼吸脉动 | ±1.2%，周期 ~90ms× | 涌起期玻璃半径叠加 `1+0.012·sin(ts/90)` |
| 涌起微珠 | 6 颗，半径 4–7px | 贴圆盘边缘游走，仅为点缀 |
| 换屏时机 | 涌起完成瞬间 | 玻璃完全覆盖后调原版换屏 |
| 连点策略 | busy 标志 | 转场期间再次点击直接吞掉 |

## 1. 几何定义（与定稿小样逐帧一致）

坐标均为 GUI 逻辑坐标。`origin=(ox,oy)` = 被点击按钮的中心；
`(W,H)` = 当前屏幕逻辑尺寸。

```
Rmax = max(ox,oy 到四角的距离) + 40     // 保证覆盖 + 余量

阶段一（涌起，0..300ms）：
  p = easeInOutCubic(t/300)
  R   = 1 + (Rmax-1)·p                  // 再叠加呼吸项
  圆盘 = 圆心(origin) 半径 R

阶段二（泻落，300..660ms）：
  q  = easeInQuad((t-300)/360)
  cy = oy + q·(2·Rmax + H + 40)         // 圆心垂直下移直至圆盘完全离屏
  圆盘 = 圆心(ox, cy) 半径 Rmax          // 半径不再变化，顶弧即"液面"

液面线（泻落期可见）：
  y(x) = cy − √(Rmax² − (x−ox)²)        // 圆盘顶弧（天然带弧度，非直线）
         + A·sin(0.045x + t·2π/210) · (0.6 + 0.4·sin(相位扰动))

液面线由 ~40 个小液珠(r≈6px, 半径±噪声)沿弧线密排、相互融合而成，
实现上是一条连续液带——绝不允许出现离散"水潭"（用户明确否决过）。
```

## 2. 落地方案（沿现有架构，零编译期耦合不变）

全部反射实现，模式复用 `TitleScreenDispatch`（builder + Proxy + 吞异常 + 诊断计数）。

### 2.1 按钮接管

- 新增 `MiliMenuTransitionTransformer`：注入 `TitleScreen#init`。
  - 现有 HEAD 点已被 Mods 按钮占用，且 HEAD 时机原版按钮尚未创建。
  - 首选 **RETURN 注入**（`InjectionPoint.RETURN`）——init 返回时按钮已就位。
    若注入引擎暂不支持 RETURN，退路：HEAD 回调里经 `Minecraft.execute` schedule
    一个下一帧任务再查找按钮（沿用 TitleScreenDispatch selftest 的线程模式）。
- 查找「单人游戏 / 多人游戏」按钮：**按 bounds 匹配**（26.2 TitleScreen 布局坐标，
  进 §5 符号清单核实），不要按文案匹配（依赖语言）。
- 接管方式：反射**只读**按钮的 `bounds / message / onPress` 字段（final 字段不写，
  JDK 25 下也写不了），用 `Button.builder(...).bounds(...)` 重建一个同位置按钮，
  Proxy 包装 OnPress，`addRenderableWidget` 换入（先 `removeRenderableWidget` 旧钮）。
  这与 `buildModsButton()` 是同一套手法。
- 幂等：沿用 installedWidgets 的**弱引用 widget 身份判据**（resize → clearWidgets →
  init 重跑的坑，TitleScreenDispatch 注释里已踩过，直接照抄结论）。

### 2.2 动画渲染

- 用原版 **Overlay 体系**（Proxy 实现 Overlay 接口，注册入口见 §5 符号清单）：
  Overlay 画在 Screen 之上，天然盖住旧屏与新屏，且不干扰 screen 生命周期。
- 绘制无圆图元：**竖条近似**。对每 1–2px 列 `fill(x, yTop, x+2, yBottom, argb)`：
  - 玻璃圆盘 = 弦高区间填底色（每列一次）；
  - 液面高光 = 弧线附近 ±液珠半径的短条（约 40 列）；
  - GUI 逻辑分辨率 426×240 下总条带数 ≤ ~500/帧，开销可忽略；
    GuiGraphics 按逻辑坐标走，高分辨率物理屏不会放大条带数。
- 计时：渲染线程 `System.nanoTime()` 驱动；新屏 `setScreen` 在覆盖瞬间调用。

### 2.3 点击链路

```
玩家点击（被接管的按钮）
  → Proxy OnPress:
      busy?  → 直接 return（吞掉）
      否则   → busy=true, origin=按钮中心, 挂 Overlay 动画
  → 涌起完成瞬间: 调用捕获的原版 onPress（真实 setScreen）
  → 泻落完成: 摘除 Overlay, busy=false
```

输入封锁靠 OnPress 层的 busy 判断，**不依赖 Overlay 拦输入**（Overlay 的输入
语义是符号风险点，绕开它）。

## 3. 降级链（沿用"宁可退化，不可静默失效"）

1. **Overlay 符号漂移 / 渲染异常** → 立即调原 onPress 瞬时切屏 + 摘除动画，
   `errorCount++` + WARNING 日志。玩家永远能进列表，只是没有动画。
2. **按钮接管失败**（找不到目标按钮/反射失败）→ 原版按钮原样保留（无转场），日志。
3. **busy 卡死保护**：动画有硬超时（> 2×总时长强制收尾并复位 busy）。
4. **一键关闭**：`-Dmili.menu.transition=false` 时不接管任何按钮。
5. 所有异常吞掉（与 TickCallbackDispatch/TitleScreenDispatch 同一异常策略）。

## 4. 明确不做

- 不做背景模糊/折射 shader——用户定稿为**清玻璃**，无模糊诉求；
  未来若要真折射，属于独立 shader 工作项，与本规格解耦。
- 不动 `Gui.setScreen` 的字节码（只在按钮回调层拦截，爆炸半径最小）。
- 不做多按钮泛化：只有「单人游戏 / 多人游戏」两个入口，写死两个坐标即可，
  YAGNI。

## 5. 符号清单（实现前必须按 build/minecraft/decompiled 26.2 核实，进 CI 校验任务）

| 符号 | 用途 | 状态 |
|---|---|---|
| `TitleScreen#init` | 已有注入点 | ✅ 已核实（现用） |
| `InjectionPoint.RETURN` 引擎支持 | RETURN 注入 | ⚠️ 待确认，否则走 HEAD+schedule 退路 |
| TitleScreen 单人/多人按钮 bounds | 按钮定位 | ⚠️ 待按反编译源核实 |
| `Button` 的 `onPress` 字段坐标 | 只读捕获原回调 | ⚠️ 待核实 |
| `Screen#removeRenderableWidget` | 换入接管按钮 | ⚠️ 待核实（add 已核实过） |
| Overlay 接口方法签名 + `Gui` 注册入口 | 动画层 | ⚠️ 待核实 |
| `GuiGraphics.fill` | 条带绘制 | ⚠️ ModListScreenDispatch 已用，低风险 |

## 6. 配置与自测

- `-Dmili.menu.transition`（默认 true）、`-Dmili.menu.transition.speed`（0.6–1.4）、
  `-Dmili.menu.transition.wave`（2–14）。
- 自测入口（复用现有 selftest 通道）：`-Dmili.selftest.transition=true` 时，
  主界面稳定后模拟点击「单人游戏」→ +300ms 截图（应拍到玻璃覆盖）→ +700ms 截图
  （应拍到选择世界界面）→ 复用 `mili.selftest.screenshot` 截图通道留证。
- 单元测试：动画状态机抽成不依赖 MC 类的纯逻辑类（phase 推进、超时保护、
  降级触发、busy 吞点击），照 `SchedulerTest` 的行为契约风格写断言；
  transformer 结构测试照 `ClientUiTransformersTest`。

## 7. 验收标准（用户口径）

1. 点「单人游戏 / 多人游戏」：玻璃从按钮位置涌起盖屏 → 清玻璃透出列表 →
   液面贴弧下滑露出完整列表，全程 ~660ms。
2. 液面是**一条**连续波纹，没有独立水潭；玻璃**不发毛**。
3. 转场中连点不炸、不叠动画；改窗口大小不残留幽灵 Overlay。
4. 关掉开关后行为与原版完全一致。

## 8. 实现热修记录（2026-10-24 · 真机崩溃后）

首轮实现随分发包上真机（GT 730 / Win10 / Java 25.0.4）启动即崩：

```
NoClassDefFoundError: org.loader.runtime.minecraft.client.screen.MiliGlassBridge
  at Screen.extractRenderStateWithTooltipAndSubtitles(Screen.java:121)
  at LoadingOverlay.extractRenderState(LoadingOverlay.java:83)
```

**根因一（注入丢失）**：转换管线（`TransformerPipeline`）对同一类的多个
转换器是「各自基于原始字节独立转换、最后者覆盖」—— `TitleScreen#init`
同时命中本转换器（RETURN 注入，注册序 4）与 `mili-core-title-screen`
（Mods 按钮 HEAD 注入，注册序 6），本转换器的 init 注入被静默丢弃，
桥接类失去定义时机。

**根因二（时机假设错误）**：原设计假设"客户端第一块屏幕就是主菜单，
init 回调里定义桥接类来得及"。26.2 反编译源证伪：`LoadingOverlay`
在淡入/淡出阶段**直接调用当前屏幕的提取方法**
（`screen().extractRenderStateWithTooltipAndSubtitles(...)`，第 83 行），
渲染钩子在主菜单出现之前就会执行。

**热修（双管齐下）**：

1. init 锚点从 `TitleScreen#init()V` 挪到 **`Screen#init(II)V`**
   （`public final`、只在 Screen 定义、setScreen/resize 共用入口，
   `!initialized` 分支先跑子类 `init()` 创建按钮再返回）。本转换器成为
   Screen 的唯一转换器，冲突类别上消失；回调对全部屏幕触发，
   内部按 `TitleScreen` 实例过滤。
2. 桥接类定义提前到**转换期**：转换器在注入渲染钩子之前调用
   `MenuTransitionDispatch.ensureBridgeDefinedEager()`（转换发生在
   `MinecraftClassLoader.loadClass` 内部，此刻定义必然早于该类任何
   字节码执行）。**定义失败 → 两个锚点都不注入（Skipped）** ——
   绝不埋一颗解析必炸的 INVOKESTATIC，游戏保持原版行为。

随修附带：`MiliMapping` 符号目录补上此前遗漏的
`SCREEN_EXTRACT_RENDER_STATE` 并新增 `SCREEN_INIT`（目录 8 → 10 条）。

## 9. 实装修整记录（2026-10-28 · 真机自测「进不去游戏」后）

分发包（core 2026-10-24 17:52）真机复现：点「单人游戏 / 多人游戏」只播转场
动画，换屏从不发生，玩家被困主菜单。`-Dmili.selftest.transition=true` 自测
抓到两个真实缺陷（stderr 18 次 IllegalAccessException + 7 次 CME）：

**缺陷一：hidden class 访问 —— 换屏从未执行**

`invokeOriginal` 用 `onPress.getClass().getMethod("onPress", Button.class)`
反射调用原版回调。原版 OnPress 是 LambdaMetafactory 生成的 **hidden class**
（JEP 371）：getMethod 的声明类解析为 hidden class 本身，而 hidden class 的
成员对定义之外的一切类不可访问，**public 修饰也不豁免**，invoke 一律抛
`IllegalAccessException`。涌起完成 → swapDue → 原版回调炸 → 被吞 →
界面纹丝不动。失败只进 stderr，mili-platform.log 全程无异常 ——
正是本项目定义的「静默失效」。

修复：从公开接口 `Button$OnPress` 解析（声明类 public 接口，跨 CL 访问
检查通过）并缓存（`onPressMethod()`）。换屏成功现在会打一条 INFO
「转场交棒：原版换屏已执行」—— 它是「换屏真的发生了」的唯一正面证据。

**缺陷二：遍历中修改 children —— 按钮接管只完成一半**

`takeoverButtons` 边遍历 `children()` 边 `removeWidget`：替换完第一个按钮
（单人）迭代器立即 CME，第二个按钮（多人）从未接管。每次 TitleScreen
init / resize 都重复。

修复：`forEachSnapshot` —— 迭代器挂快照，动作里对原列表的增删不 CME。
单元回归 `MenuTransitionDispatchRegressionTest` 同时锁住两个缺陷
（lambda hidden class 用例对旧实现必然红）。

**动效微调（绘制层，状态机参数不动）**

- 玻璃盘平涂（alpha 0.04，游戏背景上几乎不可见）→ 上亮下暗垂直渐变
  （`GuiGraphicsExtractor.fillGradient`），读得出「玻璃厚度」。
- 泻落期液面恒定 0.26 直到末帧戛然而止 → 随 (1−q) 淡出，
  液面是滑出画面而不是闪断；透明后整带跳过绘制。
