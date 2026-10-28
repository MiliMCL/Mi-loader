# 性能稳定性与生命周期验收手册

> 目标：让 TPS/FPS 的**波动**可见、可解释、可回归；让对象与资源的
> 创建 → 使用 → 释放三个阶段有明确的属主和断点。
>
> 适用范围：Minecraft 26.2 客户端（单人 / 局域网），平台版本 `0.1.0`。
>
> 相关：[`LIFECYCLE_BUDGET.md`](LIFECYCLE_BUDGET.md) —— 同样的生命周期，
> 换一个视角：每个状态区间的成本预算，以及优化项之间的成本搬移规则。

---

## 1. 判据：什么叫「稳定」

| 指标 | GOOD | DEGRADED | POOR |
|---|---|---|---|
| TPS（5s 窗口） | ≥ 19.0 | 15.0 – 19.0 | < 15.0 |
| tick p95 | ≤ 40ms | 40 – 60ms | > 60ms |
| FPS（有帧数据时） | ≥ 50 | — | — |
| tick 峰值 | 仅记录 | 仅记录 | 反复 > 100ms 时人工判定 |

> 这三档与 `PerformanceSnapshot.health()` 是同一套判据 —— 文档与代码
> 不允许各说各话。FPS 只参与 GOOD 的门槛：TPS 正常但帧率低，问题在
> 客户端渲染/GC，不该被 TPS 掩盖。

生命周期另有两条独立判据（与 TPS 无关，见第 6.3 节）：

- **线程数**：10 分钟内不增长（±1）；反复开关界面 100 次后回到基线 ±1。
- **已加载类数**：反复开关界面 100 次后回到基线 ±20；单调增长即
  ClassLoader 泄漏。

这些阈值同时写进了代码：

- `PerformanceSnapshot.health()`：`GOOD / DEGRADED / POOR` 的判据；
- `PerformanceMonitorTest`：窗口、百分位、超时计数、零分配的回归护栏；
- `HotPathBenchmarkTest`：事件总线 `post()` 的零分配护栏。

> 注意：面板上的 TPS 是 **integrated server 的真实 tick 速率**，不是
> 「1 秒内 tick 了多少次」的近似值。1s 窗口用于看瞬时抖动，5s 用于
> 判断是否稳定，15s 用于排除「刚好这一秒」的偶然。

---

## 2. 采样链路（从 tick 到面板）

```
MinecraftServer#tickServer
   └─ TickCallbackDispatch.onTickBegin/End        (字节码注入)
        └─ TickBridge → TickEngine.endTick
             └─ PerformanceMonitor.recordTick(start, end)   ← 每次 tick，零分配
                  ├─ 600 长整型环形缓冲（≈30s）
                  ├─ 超时计数（> 50ms）
                  └─ 1s / 5s / 15s TPS 窗口

Minecraft#tick (20Hz)
   └─ KeyBindingDispatch.tick()
        ├─ 按键轮询
        └─ ClientFrameSampler.sample()
             └─ PerformanceMonitor.recordFrame(fps, frameTimeNs)

K 面板（4Hz）
   └─ PerformanceService.current() → PerformanceSnapshot（不可变）
```

设计约束：

1. **采样路径不分配对象**。tick 写入是两个 `long[]` 赋值 + 两次自增，
   没有装箱、没有 `List`、没有 `stream()`。
2. **快照才分配**。百分位排序使用构造时分配的 scratch 数组，且只在
   4Hz 的读取路径上发生。
3. **诊断不反向影响被测对象**。K 面板声明 `pauseGame(false)`：
   否则单人游戏会在打开面板的瞬间暂停 integrated server，
   TPS 读数变成 0 —— 观测行为改变了被观测对象。

---

## 3. K 键面板：删了什么、留了什么

旧版（Aurora 控制台，按 K）：

```
Aurora Crystal · miliaurora        ← 与标题重复
启动次数: 2                         ← 排障时无用
本次运行: 83s / 1660 ticks          ← 两个同义指标
堆: 182 / 4096 MiB (4%)
GC: 12 次 / 340ms
线程: 38 live / 52 peak
已加载 Mod: 2 / 类: 1840
[重置计数] [关闭]                   ← 「重置计数」会改持久化数据
```

新版（平台内置，按 K；`Esc` 关闭）：

```
TPS 20.0 / 19.9   FPS 61
tick 1.2 / 3.4 / 12.0 ms   超时 0
堆 182 / 4096 MiB (4%)   GC 12 / 340ms
Mod 2  线程 38 live / 52 peak  类 1840
Esc 关闭   ·   K 打开
```

| 行 | 数据 | 对应判断 |
|---|---|---|
| 1 | 1s / 5s TPS、当前 FPS | 是否达标；是瞬时抖动还是持续低 |
| 2 | tick 均值 / p95 / 峰值、窗口内超时次数 | 卡顿形态：偶发尖峰 vs 整体变慢 |
| 3 | 堆占用、GC 次数与总耗时 | 掉帧是否由 GC 引起 |
| 4 | Mod 数、活动/峰值线程、已加载类 | 是否存在线程或 ClassLoader 泄漏 |
| 5 | 操作提示 | 不需要猜怎么关 |

删掉的内容都有明确理由：标题重复、启动次数与 TPS/FPS 无关、
`alive` 与 `ticks` 是同义指标、重置按钮会改持久化状态且无法撤销。

> Aurora 的 Mod 自定义界面移到 **J**：它仍然是 ABI
> `ScreenService` 的活证据（标签 + 按钮 + 点击回调），
> 但不再占用诊断入口。

---

## 4. 这一轮改了哪些「会抖」的地方

| 位置 | 旧行为 | 现在 | 为什么会影响 FPS/TPS |
|---|---|---|---|
| `ModListScreenDispatch.drawFooter` | 每帧拼 `Heap …` 并 `String.format` 时间 | 打开列表时算一次 | 60fps 下每秒 180+ 个短命字符串，全部由 Young GC 买单 |
| `ModListScreenDispatch.ellipsize` | 每帧每行二分查找 + 5~6 次 `substring` | 按「文本+宽度」缓存 256 条 | 20 行 × 60fps ≈ 每秒近万个短命字符串 |
| `KeyBindingDispatch.poll` | 每 tick、每绑定 `getMethod` 查找 | 解析一次并缓存 | 20 TPS × N 绑定 × 2 次方法表遍历 |
| `KeyBindingDispatch.tick` | 正序遍历，回调里注销会跳过下一项 | 倒序遍历 | 少一个绑定就少轮询一个，静默失效 |
| `TickEngine.record` | `synchronizedList` + `remove(0)` 每次搬 200 个元素 | 定长环形数组 | 每 tick 一次 O(200) 位移 |
| `TickEngine.close` | 不清监听器 | 清空 6 个回调表 | Mod 的 lambda 持有 ClassLoader，关服后仍可达 |
| `ResourceRegistry.close` | 只 `clear()`，一个资源都没关 | 逆序真正关闭并聚合异常 | ZIP 句柄 / 线程 / 原生资源不释放 |
| `EventBus` | 每个实例构造时开 2 个线程 | 首次 `postAsync` 才创建 | 10 个 Mod = 20 个闲置线程 |
| `ModContext.scheduler()` | 每个 Mod 的池大小 = CPU 核数 | 固定 2 | 10 个 Mod × 8 线程 = 80 个池线程 |
| `TickContract.complete()` | 每 tick 走一次 `stream().filter()` | 单次循环计数 | 每秒 20 次 stream 管线分配 |
| `RuntimeMetrics.snapshot` | 每次调用遍历 JMX + 所有 Mod 的类集合 | 250ms 合并窗口 | 面板每帧读一次会把渲染线程拖进 ClassLoader 遍历 |

---

## 5. 生命周期契约

### 5.1 三条不变量

1. **有主**：每个 `Resource` 都有 `owner()`；`Scope` 关闭时按**逆序**释放
   自己登记的资源（后创建的先释放）。
2. **可撤销**：任何把对象放进 `static` 字段的组件，都必须提供一个
   `Resource`，在 Scope 关闭时把静态引用置空或移除。
   - `TickCallbackDispatch.Registration`
   - `KeyBindingDispatch.lifecycle(scope)`
   - `ScreenHostDispatch.lifecycle(scope)`
   - `ClientFrameSampler` / `PerformancePanel.Registration`
3. **关后拒用**：`EventBus`、`TickEngine`、`ResourceRegistry` 在关闭后
   拒绝登记/推进（抛 `IllegalStateException`），而不是静默 no-op。
   静默 no-op 会让「关闭后仍在提交任务」这类 bug 永远不暴露。

### 5.2 一次界面打开/关闭的资源轨迹

```
按 K
 └─ KeyMapping.consumeClick → PerformancePanel.toggle
      └─ ScreenHostDispatch.open(spec, listener, liveTexts)
           ├─ activeSpec / activeListener / activeLive  ← 唯一强引用
           ├─ setScreen(MiliScreenHost)  → init()
           │    └─ 构建 4 个 StringWidget + hint
           └─ 失败 → clearActive() 回滚（否则 isShowing 永远 true）

每 tick
 └─ MiliScreenHost.tick() → ScreenHostDispatch.onTick
      └─ 200ms 节流 → 只在文本变化时 setMessage

Esc / 关闭
 └─ MiliScreenHost.onClose() → ScreenHostDispatch.onClose
      ├─ clearActive()（断开 spec / listener / liveTexts）
      └─ listener.onClose(true)

Runtime 关闭
 └─ Scope 逆序：
      PerformancePanel.Registration → 注销 K 绑定、关闭面板
      ClientFrameSampler            → 移除 tick 钩子
      ScreenHostDispatch.Lifecycle  → clearActive()
      KeyBindingDispatch.Lifecycle  → 清空 bindings / tickHooks
      TickEngine.close              → 取消契约、清空监听器
      ResourceRegistry.close        → 逆序关闭登记的资源
```

---

## 6. 验证方式

### 6.1 构建期（CI / 本地）

```bash
# 全量（推荐在 CI 跑）
./gradlew :mili-abi:test :mili-runtime:test :mili-minecraft-integration:test

# 本轮新增的护栏
./gradlew :mili-runtime:test --tests '*PerformanceMonitorTest'
./gradlew :mili-runtime:test --tests '*RuntimeMetricsCacheTest'
./gradlew :mili-runtime:test --tests '*ResourceRegistryLifecycleTest'
./gradlew :mili-runtime:test --tests '*TickEngineLifecycleTest'
./gradlew :mili-abi:test --tests '*PerformanceSnapshotTest' --tests '*ScreenSpecTest'
./gradlew :mili-minecraft-integration:test --tests '*ScreenHostContractTest'
```

预期：

| 测试 | 断言 |
|---|---|
| `PerformanceMonitorTest.tpsWindows` | 100 个 50ms tick → 5s TPS ≈ 20 |
| `PerformanceMonitorTest.slowTicksShowUpInPercentiles` | 20/120 个 100ms tick → p95 ≥ 90ms |
| `PerformanceMonitorTest.recordTickDoesNotAllocate` | < 8 B/tick |
| `RuntimeMetricsCacheTest.withinWindowReturnsSameSnapshot` | 同一帧两次读取是同一实例 |
| `ResourceRegistryLifecycleTest.closesInReverseOrder` | 关闭顺序 `c, b, a` |
| `TickEngineLifecycleTest.closeReleasesListeners` | 关闭后 `listenerCount() == 0` |
| `ScreenHostContractTest` | 生成字节码里每条 `INVOKESTATIC` 都能解析到真实方法 |

> `ScreenHostContractTest` 是**必须**跑的那一条：本轮给宿主新增了
> `tick()` / `isPauseScreen()` / `isInGameUi()` 三个覆写，描述符是手写字符串，
> 编译期不会检查。它已经挡下过一次「按 K 直接退出游戏」的事故。

### 6.2 游戏内（5 分钟）

```bash
./gradlew :mili-loader:shadowJar
java -jar mili-loader/build/libs/mili-0.1.0-mc26.2.jar run \
     --mili-mods run/mods
```

1. 进入一个单人世界，站立不动 30 秒；
2. 按 `K`，观察 60 秒：
   - TPS 两列都应 ≥ 19.0（稳态通常 19.8 – 20.0）；
   - `p95` 应 ≤ 40ms（理想 ≤ 20ms）；`超时` 不增长；
   - FPS 与不打开面板时相比，差值 ≤ 1（面板自身成本可忽略）；
3. 快速跑图 / 打开背包 / 切换维度各 30 秒，确认 `超时` 最多增加个位数；
4. 按 `Esc` 关闭，再按 `K` 重开，重复 20 次 —— 线程数不应增长。

判读口径：

- **TPS 低 + p95 高 + FPS 正常** → 服务端 tick 慢（实体/方块实体/Mod tick）；
- **FPS 低 + TPS 正常** → 客户端渲染/GC（看第 3 行 GC 次数是否同步上涨）；
- **两者同时低 + 堆接近上限** → GC 抖动，先调 `-Xmx` 或找分配源；
- **TPS 显示 `—`** → 世界没加载 / 游戏暂停 / tick 未接入（不是 0 TPS）。

### 6.3 生命周期与泄漏

```bash
# 1) 重复开关界面 100 次（K / Esc，Mod 列表按钮）
#    然后看 K 面板第 4 行：
#    线程数应回到基线 ±1；类数不应单调增长

# 2) 堆直方图前后对比（游戏进程 PID）
jcmd <pid> GC.class_histogram > before.txt
#  ... 重复开关 100 次 ...
jcmd <pid> GC.class_histogram > after.txt
#  关注 org.loader.* / MiliScreenHost / 各 Mod 入口类的实例数

# 3) GC 日志（启动参数）
-Xlog:gc*:file=run/logs/gc.log:time,uptime:filecount=3,filesize=20M
#  稳态下不应出现「每隔几秒一次 Young GC 且回收后堆仍上涨」
```

自动化版本（已在仓库里）：

- `ClassLoaderLeakTest`：Mod ClassLoader 在 Scope 关闭后必须可回收；
- `ResourceLeakTest`：`EventBus` / `Scheduler` 关闭后线程必须退出；
- `TickEngineLifecycleTest`：关闭后监听器为 0、契约被取消。

### 6.4 回归护栏（防止「优化」被改回去）

| 护栏 | 触发条件 |
|---|---|
| `HotPathBenchmarkTest` | `post()` 分配 > 8 B/次 |
| `PerformanceMonitorTest.recordTickDoesNotAllocate` | tick 采样分配 > 8 B/次 |
| `RuntimeMetricsCacheTest.withinWindowReturnsSameSnapshot` | 有人删掉 250ms 合并窗口 |
| `ResourceRegistryLifecycleTest` | 有人把 `close()` 改回 `clear()` |
| `ScreenHostContractTest` | 生成宿主的静态调用签名漂移 |

---

## 7. 已知边界（不藏）

1. **FPS 是 20Hz 采样**：客户端 tick 每 50ms 读一次 `Minecraft.getFps()`，
   两次采样之间的瞬时尖峰可能被漏掉。要逐帧归因需要注入渲染循环，
   代价是多一个随版本漂移的字节码注入点 —— 当前不值得。
2. **JMX 快照最多滞后 250ms**：`RuntimeMetrics.snapshot()` 的合并窗口。
   需要绝对实时值时用 `sampleNow()`。
3. **ABI 兼容性**：本轮只做加法（新 record、新 service、接口默认方法、
   `ScreenSpec` 新 builder 选项）。未改任何既有方法签名，`ABI_VERSION` 保持 1。
4. **`demo-mod` 需要重新构建**才能看到 J 键与新面板（先更新 ABI jar，再编译）：
   ```bash
   ./gradlew :mili-abi:jar
   demo-mod/build.sh
   ```
5. **按键冲突**：平台占用 K。若某个 Mod 也注册 K，两者会同时触发 ——
   `KeyBindingDispatch` 目前不做冲突检测（原版 `KeyMapping` 也不做）。
   Aurora 已让出 K，改用 J。
