# CURRENT.md —— Mili Platform 当前实现状态

> **这是唯一描述"当前实现"的文档。**
> 任何与本文件冲突的描述都视为过时。历史分析见 `docs/history/`。

最后更新：2026-10-05（ClassLoader 拓扑重建 + Tick 契约重写）

---

## 版本锁定

| 项| 值 | 唯一来源 |
|---|---|---|
| Mili Platform | 0.1.0 | `gradle.properties: miliPlatformVersion` |
| Mili ABI | 1 | `gradle.properties: miliAbiVersion` |
| Minecraft | 26.2 | `gradle.properties: minecraftVersion` |
| Java | 25 | `gradle.properties: javaVersion` |

代码侧对应 `org.loader.api.VersionInfo`，由 Gradle 任务
`verifyVersionConstants` 在构建期强制校验二者一致。**不允许出现字面量兜底。**

---

## 架构分层

```text
Mod
 │  只依赖 mili-abi
 ▼
Mili ABI (mili-abi)              零依赖，Mod 唯一编程接口
 ▼
Mili Runtime (mili-runtime)      Scope / Lifecycle / Scheduler / Capability / Tick
 ▼
Mili Loader (mili-loader)        Discovery / ClassLoader / Dependency / Entrypoint
 ▼
Minecraft Integration            Bootstrap / TickBridge / WorldBridge / EntityBridge
 ▼
Minecraft 26.2                   —— build-time input，绝不进入分发产物
```

---

## ClassLoader 拓扑（Phase 2 已重建）

```text
AppClassLoader（平台：abi / runtime / loader / integration）
     │
     └─ MinecraftClassLoader            ← 唯一定义 net.minecraft.* 的地方
            │  · 平台自有包 (org.loader.*) 委派给 parent
            │  · Minecraft 与 Mojang 库   self-first
            │
            ├─ ModClassLoader (mod-a)    ← child-first，parent = gameCL
            ├─ ModClassLoader (mod-b)
            └─ ModClassLoader (mod-c)
```

### 三条硬约束

1. **`net.minecraft.*` 全局只有一个定义来源。**
   Minecraft 内部有大量静态状态与跨类强引用；若被两个 CL 各加载一份，
   类型不相等会导致 ClassCastException，且注册表状态分裂。

2. **ModClassLoader 的 parent 永远是 MinecraftClassLoader，
   绝不是"依赖的 Mod"。** 跨 Mod 访问通过 `ClassVisibility` 导出机制控制。
   历史上这里传的是 `null`，依赖链是死代码。

3. **可见性由 `ClassVisibility` 单点定义**：
   - `FORBIDDEN` — Loader 内部 / Runtime kernel / Installer / JDK 内部 → 拒绝加载
   - `PARENT_FIRST` — ABI、公开 Runtime 包、`net.minecraft.*` → 委派 parent
   - `SELF_FIRST` — 其余包 → Mod 自己定义（天然跨 Mod 隔离）

详见 `docs/architecture/CLASSLOADER.md`。

---

## Minecraft 启动与状态机（Phase 3）

```text
LoaderMain.main
  → ensureMinecraftPresent()        缺失时反射调InstallerMain 现场拉取
  → locateGame()                    MinecraftDiscovery
  → createGameClassLoader()         建立唯一的 MinecraftClassLoader（含主类可解析硬校验）
  → ModDiscovery + resolveDependencies()
  → runtime.start()                 Mili Runtime 先于 Minecraft 启动
  → validateVersionBindings()       platform/abi/minecraft 三元组精确校验
  → ModClassLoaderManager.create()  每个 Mod 挂在 gameCL 之下
  → invokeModEntrypoints()          只接受 void initialize(ModContext)
  → MinecraftGameProvider.launch()  独立线程调用游戏 main
  → finally shutdown()              逆序释放
```

### BootstrapState 九态

```text
CREATED → DISCOVERING → PREPARING → LOADING → BOOTSTRAPPING → RUNNING
                                                                 ↓
                        FAILED ←─────────────────────  STOPPING → STOPPED
```

- 迁移**单向且受校验**，跳步或回退抛 `IllegalStateTransitionException`
- `fail(cause)` 会逆序释放外部资源 → 关闭 Scope
- 已注册资源由 `Scope.registerResource` 管理，`stop()` 递归释放

---

## Tick 集成（Phase 5/6 已重写）

### 关键决策：废弃轮询式 tick

**旧实现（已删除）是错的。** `ClientTickPoller` 用 10Hz 轮询
`level.getGameTime()`「推测」tick 是否发生：

| 问题 | 后果 |
|---|---|
| 10Hz轮询 vs 20 TPS | tick 粒度与真实 MC tick 解耦 |
| 轮询线程 ≠ MC 主线程 | Mod 以为在改主线程世界，实际不是 |
| `level == null` 时不 tick | 单人世界下 tick 直接停摆 |
| 异常被吞 | 无任何可观测记录 |

该类已删除。`level.getGameTime()` 在多人下与服务端 tick 不同步，
在单人下客户端 `level` 为 null，**不存在可用的轮询方案**。

### 当前实现

```text
Minecraft 真实 tick 入口（主线程）
   ↓ beginTick()                ← MinecraftTickSource 实现负责转发
   ↓ TickBridge.beginTick()
   ↓ TickEngine.beginTick()     新建 TickContract，阶段 TICK_START → PRE_TICK
   ├─ advanceToSchedule()       Mod 在此提交本 tick 任务
   ├─ advanceToCoreTick()       Minecraft 真实世界/实体 tick 在此执行
   │    └─ runPendingTasks()    单线程按提交顺序执行
   ├─ advanceToAsyncCompletion()  awaitAllTasks 收敛
   ├─ POST_TICK
   └─ TICK_END
   ↓ endTick()                  complete()，产出 TickMetrics
```

### TickContract 契约

不是数据结构，而是**一次 tick 的执行信封**：

| 职责 | 归属 |
|---|---|
| 创建 | TickEngine（每 tick 新建） |
| 推进阶段 | TickEngine（唯一 `advance()` 调用方） |
| 提交任务 | 任意线程（线程安全） |
| 等待完成 | TickEngine 在阶段屏障`awaitAllTasks()` |
| 处理异常 | TickEngine → `contract.fail()`，**不逃逸破坏 tick 循环** |
| 完成 tick | TickEngine.complete() |
| deadline | TickEngine 巡检 `isPastDeadline()` |
| cancellation | 任意线程 `cancel()` |

阶段严格有序：
`TICK_START → PRE_TICK → SCHEDULE → CORE_TICK → ASYNC_COMPLETION → POST_TICK → TICK_END`

**当前为单线程执行。** `TickEngine` 强制校验驱动线程唯一——
并行化是 Phase 11+ 的工作，且必须先引入 ExecutionContext。

---

## 已修复的历史缺陷

| 缺陷 | 影响 | 修复 |
|---|---|---|
| Mod CL 与 MC CL 平行无关联 | Mod 无法安全操作 MC 对象（必然 ClassCastException） | MC CL 成为唯一来源，Mod CL 以其为 parent |
| `ModClassLoaderManager` 传 `parent=null` | 依赖链是死代码 | parent 改为 gameCL，跨 Mod 访问走导出机制 |
| 每 Mod 平铺完整 MC classpath | MC 类被重复定义 | Mod CL 只含自身代码源 |
| `ClientTickPoller` 轮询推测 tick | tick 集成形同虚设 | 删除，改真实 tick 入口 |
| `TickContract` 是纯静态类 | 无法表达执行语义 | 改为可实例执行协议 + TickEngine |
| Scheduler 优先级被 FIFO 抹平 | 优先级排序白做 | ScheduledTask 实现 Runnable，线程池直接从优先级队列取 |
| `submitRepeating` 用 `Thread.sleep` 占池线程 | 周期任务占满线程池 → 任务饥饿 | 独立守护计时器线程做延时触发 |
| `verifyPlatformJar` 只统计 mcClasses 不报错 | 分发边界无强制约束 | `require(mcClasses == 0)` |
| CI 无Minecraft 排除检查 | 分发边界靠侥幸 | Gradle + shell 双实现独立扫描 |
| 25 处版本字面量兜底 | 版本漂移 | `rootProject.extra` 单一来源 + 构建期校验 |
| `bootstrap.start()` 无状态机 | 半初始化运行时 | 九态状态机 + 失败清理 |

---

## 测试现状

| 模块 | 测试数 | 说明 |
|---|---|---|
| mili-abi | 0 | 纯接口模块 |
| mili-runtime | 13+ |含 TickEngine/TickContract 单线程正确性 |
| mili-loader | 5 类 | ClassLoader 契约 6 套（见下） |
| mili-integration | 8 类 | 状态机、tick 桥接、生命周期 |
| mili-installer | 4 类 | manifest / SHA-1 / 下载 / 规则 |

### ClassLoader 测试（Phase 2 新增）

- `ClassVisibilityTest` — 可见性契约（可见/隐藏/解析策略/导出登记）
- `ClassIsolationTest` — MC 类共享、Mod 兄弟关系、关闭独立性
- `DuplicateClassTest` — 跨 Mod 重复类检测
- `ResourceIsolationTest` — 资源隔离（真实 JAR 夹具）
- `ClassLoaderLeakTest` — 泄漏检测（WeakReference + GC + 文件句柄）

### Tick 测试（Phase 6 重写）

`TickContractTest` 验证**执行协议**而非算术：阶段机、任务顺序、
异常传播与隔离、取消、deadline、await 屏障、引擎端到端、跨线程拒绝。

>旧版测试断言的是 `50/2=25`、`50/4=12` 这类字段算术，
> 给出的是虚假绿灯。已全部替换。

---

## 分发边界

**Mili 绝不重新分发 Minecraft。**

Minecraft 只能作为 build-time input：CI 下载 → SHA-1 校验 → 反编译 →
用于编译与测试 → 工作区销毁。

强制检查（两套独立实现，必须同时通过）：

1. **Gradle** `:mili-loader:distributionBoundaryCheck`
   - 递归扫描 fat JAR 条目、分发目录、release 目录
   - 禁止 `net/minecraft/`、`com/mojang/`、`decompiled/`、Minecraft 本体 JAR
   - 校验 `SHA256SUMS` 与 `release-manifest.json` 字段完整
2. **CI shell 扫描** `distribution-boundary` job
   - 独立实现，不复用 Gradle 逻辑，避免单点 bug
3. **`verifyPlatformJar`** — `require(mcClasses == 0)`

---

## 当前阶段与下一步

| Phase | 状态 |
|---|---|
| 1 审计 | 完成 |
| 2 ClassLoader | 完成 |
| 3 Bootstrap 状态机 | 完成 |
| 4 Lifecycle 对接 | 完成（Scope 层级已建立） |
| 5 真实 TickContract | 完成（协议层；游戏入口注入待接） |
| 6 单线程正确性 | 完成（测试层） |
| 7+ 调度/Region/并行 | **未开始** |

### 下一步（严格按序）

1. **真实 Minecraft tick 入口注入** — `ReflectiveMinecraftTickSource` 目前只是
   骨架，需要实现对 26.2 真实 tick 方法的定位与转发（`MinecraftServer#tickChildren`
   或 `ClientLevel#tick`）。这是 Tick Integration 从"协议正确"到"真实生效"的最后一环。
2. **真实 Minecraft 26.2 smoke test** — 在 CI 中验证
   启动 → Mod 初始化 → tick 执行 → 世界加载 → 生命周期关闭 → 进程干净退出。
   当前全部测试都是纯 JVM，**没有一个真正加载过 Minecraft**。
3. **Scheduler 与 TickEngine 整合** — Scheduler 的异步任务应能在 tick 屏障处收敛。
4. **Region ownership** — 为 Parallel Tick 做准备，不早于 Phase 8。

---

## 已知问题（诚实清单）

1. **没有任何测试真正启动 Minecraft。** `BootstrapGate` 依赖真实
   `net.minecraft.*`，仅在 CI 真实运行时才被覆盖。
2. **tick 入口尚未注入。** `ReflectiveMinecraftTickSource` 需要实现真实方法定位。
3. **`Runtime.scheduler()` 的优先级语义**已修复但**未经真实负载验证**。
4. **Parallel Tick 尚未开始**，这是有意的：
   原则是 Correctness → Observability → Scheduling → Concurrency，
   不跳级。
5. **实体并行化风险最高**，本阶段刻意未触及。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| `architecture/CURRENT.md` | 本文件，唯一当前状态 |
| `architecture/CLASSLOADER.md` | ClassLoader 契约（Phase 2 重建） |
| `architecture/TICK_ENGINE.md` | Tick 契约与执行模型 |
| `architecture/ABI.md` / `RUNTIME.md` / `LOADER.md` / `MINECRAFT_INTEGRATION.md` | 各层职责 |
| `design/PARALLEL_TICK.md` | 并行 tick 设计（未实施） |
| `design/REGION_SCHEDULER.md` | Region 所有权设计（未实施） |
| `design/TICK_POOL.md` | TickPool 设计（未实施） |
| `design/EXECUTION_CONTEXT.md` | 执行上下文与非法访问检测 |
| `design/SECURITY.md` | 分发边界与安全模型 |
| `compatibility/MINECRAFT_VERSION_POLICY.md` | 版本对齐政策 |
| `decisions/` | 架构决策记录（ADR） |
| `history/` | 历史分析，**不代表当前状态** |