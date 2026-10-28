# CURRENT.md —— Mili Platform 当前实现状态

> **这是唯一描述"当前实现"的文档。** 任何与本文件冲突的描述都视为过时。
> 历史分析见 `docs/history/`。

最后更新：2026-10-28（ABI 2：平台总线统一、Tick 事件接通、跨 Mod 服务代理）

---

## 版本锁定

| 项 | 值 | 唯一来源 |
|---|---|---|
| Mili Platform | 0.1.0 | `gradle.properties: miliPlatformVersion` |
| Mili ABI | **2** | `gradle.properties: miliAbiVersion` |
| Minecraft | 26.2 | `gradle.properties: minecraftVersion` |
| Java | 25 | `gradle.properties: javaVersion` |
| ASM | 9.9.1（analysis/util 锁 9.8） | `gradle.properties: asmVersion` |

代码侧对应 `org.loader.api.VersionInfo` 与 `MiliSymbol.MINECRAFT_VERSION`，
由 Gradle 任务 `verifyVersionConstants` 在构建期强制三者一致。
**不允许出现字面量兜底。**

---

## 架构分层

```text
Mod
 │  只依赖 mili-abi
 ▼
mili-abi            零依赖，Mod 唯一编程接口
 ▼
mili-runtime        Scope / Lifecycle / Scheduler / EventBus / ServiceBroker
                    Capability / Permission / TickEngine / TransformerPipeline
 ▼
mili-loader         Discovery / ClassLoader / Dependency / Entrypoint / CLI
 ▼
mili-minecraft-integration   Bootstrap / TickBridge / Registry / Client services
 ▼
Minecraft 26.2      build-time input，绝不进入分发产物
```

---

## 组件装配（当前的真实连线）

平台没有"自动装配"，每一处连接都是显式的。这张表是排查"功能静默不生效"的第一入口：

| 连接 | 装配点 |
|---|---|
| Runtime → 平台总线 | `Runtime` 构造（`new EventBus(id + ":events", rootScope)`） |
| Runtime → 服务代理 | `Runtime` 构造（`new ServiceBrokerImpl(rootScope)`） |
| 默认权限策略 | `Runtime` 构造（`permissionManager.installDefaults()`） |
| 平台总线 → Mod 的 `ctx.events()` | `ModContext.events()` 在装配模式下返回 `runtime.events()` |
| 事件视图 → Scope 资源 | `ApiModContext` 构造时 `delegate.scope().registerResource(eventsView)` |
| 服务代理 → Mod 视图 | `ModContext.services()` → `broker.forScope(scope())` |
| TickEngine → 迟绑定容器 | `EntryPointHook.wireTickPipeline` |
| TickEngine → 平台总线 | `TickEventBridge.install(...)`（同上） |
| Scope 迁移 → `ModLifecycleEvent` | `LoaderMain` 创建 Mod Scope 时注册的监听器 |
| Mod Scope → RUNNING | `LoaderMain.invokeModEntrypoints` 成功后 `driveToRunning()` |
| `Mili.registerProvider` | `LoaderMain.installMiliFacade` |
| `ConfigService.registerProvider` | `LoaderMain.installRuntimeServices` |
| 事件错误 → 平台日志 | `runtime.events().setErrorSink(...)`（同上） |
| Mod 数据目录 | `LoaderMain` 传 `<gameDir>/config/mili/data` |

---

## ClassLoader 拓扑

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

1. **`net.minecraft.*` 全局只有一个定义来源。** 被两个 CL 各加载一份会导致类型不相等与注册表状态分裂。
2. **ModClassLoader 的 parent 永远是 MinecraftClassLoader**，不是"依赖的 Mod"。跨 Mod 访问通过 `ClassVisibility` 导出机制控制。
3. **可见性由 `ClassVisibility` 单点定义**：
   - `FORBIDDEN` — Loader 内部 / Runtime kernel / Installer / JDK 内部 → 拒绝加载
   - `PARENT_FIRST` — ABI、公开 Runtime 包、`net.minecraft.*` → 委派 parent
   - `SELF_FIRST` — 其余包 → Mod 自己定义（天然跨 Mod 隔离）

`ModClassLoader.getResourceAsStream` 优先读自己的 JAR，再委托父加载器，并**禁用 JVM 级 JAR 缓存**——否则读过一次资源后 jar 被 Windows 锁住，热重载无法替换。

---

## 启动时序

```text
LoaderMain.main
  → parseArgs                      --mili-mods 摘出，其余转发给 MC
  → ensureMinecraftPresent         缺失时反射调 InstallerMain 现场拉取
  → locateGame                     MinecraftDiscovery
  → createGameClassLoader          建立唯一 MC CL（含转换管线装配）
  → ModDiscovery + resolveDependencies
  → runtime.start()                rootScope → RUNNING，发布 BOOTSTRAPPING
  → validateVersionBindings        三元组精确校验
  → createModClassLoaders          每 Mod 一个 Scope + CL + 归因 + 生命周期监听
  → installRuntimeServices         存储 / 配置 / 客户端服务 / 指标 / ErrorSink
  → invokeModEntrypoints           initialize(apiCtx)，成功后 driveToRunning()
  → installMiliFacade              Mili provider 接通
  → publishPlatformStage(READY)
  → gameProvider.launch()          EntryPointHook，独立线程调用游戏 main（阻塞）
  → finally shutdown()             逆序释放
```

### BootstrapState 九态

```text
CREATED → DISCOVERING → PREPARING → LOADING → BOOTSTRAPPING → RUNNING
                                                                 ↓
                        FAILED ←─────────────────────  STOPPING → STOPPED
```

迁移单向且受校验，跳步或回退抛 `IllegalStateTransitionException`。`fail(cause)` 逆序释放外部资源再关闭 Scope。

---

## Tick 集成

### 废弃轮询式 tick

旧实现的 `ClientTickPoller` 用 10Hz 轮询 `level.getGameTime()` "推测" tick。四个问题：轮询粒度与 20 TPS 不同步、轮询线程不是主线程、单人世界 `level == null` 时 tick 停摆、异常被吞。该类已删除——`level.getGameTime()` 在多人下与服务端 tick 不同步，在单人下客户端 `level` 为 null，**不存在可用的轮询方案**。

### 当前实现（注入驱动）

```text
MinecraftServer#tickServer
  ├─ HEAD          → TickCallbackDispatch.onTickBegin()
  │                    → TickBridge.beginTick()
  │                       → TickEngine.beginTick()
  │                          新建 TickContract，TICK_START → PRE_TICK
  │                          runEveryTickWork()（TickService.runEveryTick 的落点）
  │                          TickObserver.onTickStart → 发布 TickEvent(START)
  │
  │   ... Minecraft 世界 / 实体 tick 在此发生 ...
  │
  └─ 每个 RETURN 前 → TickCallbackDispatch.onTickEnd()
                       → TickBridge.endTick()
                          advanceToSchedule()（幂等，触发 schedule 监听器）
                          advanceToCoreTick()（幂等，触发 coreTick 监听器）
                          → TickEngine.endTick()
                              runPendingTasks()   执行本 tick 提交的任务
                              ASYNC_COMPLETION → POST_TICK → TICK_END
                              record(metrics)
                              TickObserver.onTickEnd → 发布 TickEvent(END)
```

**任务执行位置**：注入点只能包住整个 `tickServer`，无法切到世界 tick 中间。因此本 tick 提交的任务在**世界逻辑之后、POST_TICK 之前**执行。这是注入模型下唯一诚实的语义。

### 关键设计

- **`TickCallbackDispatch` 是注入字节码唯一引用的类**，由 AppClassLoader 定义。Minecraft 字节码对 Mod 类零引用——这是 `ClassLoaderLeakTest` 守的线。
- **阶段推进幂等**：`advanceToSchedule` / `advanceToCoreTick` 在已越过时是空操作。生产路径（`TickBridge`）与手工推进（测试、未来按 region 并行的调度器）可以共存。
- **单线程强制**：`TickEngine` 校验驱动线程唯一（`driverThread` 守卫）。并行化需要先引入 `ExecutionContext`，尚未开始。
- **重复注入检测**：`TickCallbackDispatch.depth` 计数，同一 tick 被 begin 两次即记录错误（`TransformationCache` 失效的直接证据）。

### TickContract

不是数据结构，而是**一次 tick 的执行信封**：

| 职责 | 归属 |
|---|---|
| 创建 | `TickEngine`（每 tick 新建） |
| 推进阶段 | `TickEngine`（唯一 `advance()` 调用方） |
| 提交任务 | 任意线程（线程安全） |
| 等待完成 | `TickEngine` 在阶段屏障 `awaitAllTasks()` |
| 处理异常 | `TickEngine` → `contract.fail()`，不逃逸破坏 tick 循环 |
| 完成 tick | `TickEngine.complete()` |
| deadline | `TickEngine` 巡检 `isPastDeadline()` |
| cancellation | 任意线程 `cancel()` |

阶段严格有序：`TICK_START → PRE_TICK → SCHEDULE → CORE_TICK → ASYNC_COMPLETION → POST_TICK → TICK_END`

---

## 事件模型

**全平台一条总线。** `Runtime.events()` 是唯一实例；`ctx.events()` 是它的带归属视图。

派发顺序：精确类型 → 父类（自下而上）→ 接口（BFS）→ `Object`。

超类型链缓存在 `ClassValue` 中，与 Class 同生命周期——Mod 卸载后链随 Class 回收，不会把 `ModClassLoader` 钉在堆上。

监听器集合是不可变数组，`post()` 零分配。监听器异常送到 `ErrorSink`（接到 `logs/mili-platform.log`），不中断分发。

**Mod 侧事件词汇**（`mili-abi` 可见）：

| 事件 | 发布者 |
|---|---|
| `TickEvent` | `TickEventBridge`（挂 `TickEngine` 观测者） |
| `ModLifecycleEvent` | `LoaderMain` 注册的 Scope 监听器 |
| `PlatformEvent` | `Runtime.start()` / `publishPlatformStage` / `Runtime.close()` |

`MinecraftEventBridge` 的内部事件（`ClientStartedEvent` 等）位于集成模块，**Mod 无法引用**——它们服务平台内部组件。

---

## 能力与服务

| | 能力（Capability） | 服务（Service） |
|---|---|---|
| 授予方 | 平台 | Mod 自己 |
| 可见范围 | 仅授予的 Scope | 全平台 |
| 冲突 | 同类型覆盖 | 跨 Scope 显式失败 |
| ABI | `ModContext.getCapability` | `ModContext.services()` |

`Scope.getCapability` 不向上查找父 Scope。跨 Mod 发现走 `ServiceBroker`。

默认权限策略：`resource.read` / `resource.write` / `intermod.communication` 允许，其余（网络、原生库、四项转换权、方法覆盖）拒绝。

---

## 转换流水线

```text
transform(className, bytes, classLoader)
  ├─ registry.matching(className)      零匹配 → 返回原始字节（热路径）
  ├─ cache.get(classLoader, className)  命中 → 返回
  ├─ for each transformer（phase 权重 → priority → id）
  │    ├─ guard.checkTransform(...)
  │    ├─ ledger.claim(...)
  │    ├─ transformer.transform(ctx)
  │    └─ 归约：Unchanged/Skipped 跳过，Transformed 更新字节，Failed 中断
  ├─ verifier.verify(...)
  ├─ audit.log(...)
  ├─ dumper.dump(...)
  └─ cache.put(...)
```

平台转换器（`CORE` 阶段，`modId = null`）：`MiliTickTransformer`、`MiliClientBrandTransformer`、`MiliTitleScreenTransformer`、`MiliClientTickTransformer`、`MiliCreativeTabLayoutTransformer`、`MiliMenuTransitionTransformer`。

符号坐标集中在 `MiliSymbol`，由 `verifyTransformationSymbols` 用**真实 Minecraft JAR** 逐条校验 owner + name + descriptor。符号表与 `AnnotationTransformerScanner` 的短名表一致性由 `assertConsistentWithMapping()` 强制。

---

## 测试现状

| 模块 | 覆盖重点 |
|---|---|
| `mili-abi` | 转换契约、符号映射、ScreenSpec、BlockSpec 属性 |
| `mili-runtime` | Scope 关闭协议、Scheduler 句柄状态与回收、EventBus 类型派发、ServiceBroker、Tick 协议与归因、转换管线与字节码验证 |
| `mili-loader` | ClassLoader 契约（可见性/隔离/重复类/资源/泄漏）、启动参数透传、CLI、报告与脱敏 |
| `mili-minecraft-integration` | Bootstrap 状态机、Tick 桥接端到端、真实 Minecraft 26.2 冒烟测试（非 mock） |
| `mili-installer` | manifest / SHA-1 / 下载 / 平台规则 |

`Minecraft26_2SmokeTest` 是**真实 Minecraft 类**的冒烟测试：从 `test_client/26.2.jar` 加载真实类，走完整 Registry → Pipeline 链路，并用 ASM 读回验证常量池未损坏。它不 mock Minecraft。

---

## 分发边界

**Mili 绝不重新分发 Minecraft。** Minecraft 只能作为 build-time input：CI 下载 → SHA-256 校验 → 反编译 → 用于编译与测试 → 工作区销毁。

强制检查（两套独立实现）：

1. Gradle `:mili-loader:distributionBoundaryCheck` — 递归扫描 fat JAR、分发目录、release 目录，禁止 `net/minecraft/`、`com/mojang/`、`decompiled/`
2. CI shell 扫描 `distribution-boundary` job — 独立实现，避免单点 bug
3. `verifyPlatformJar` — `require(mcClasses == 0)`

---

## 阶段状态

| Phase | 状态 |
|---|---|
| 1 审计 | 完成 |
| 2 ClassLoader | 完成 |
| 3 Bootstrap 状态机 | 完成 |
| 4 Lifecycle 对接 | 完成 |
| 5 真实 TickContract | 完成 |
| 6 单线程正确性 | 完成 |
| 7 tick 入口注入 | 完成（`MiliTickTransformer` + `TickCallbackDispatch`） |
| 8 平台总线统一 | 完成（ABI 2） |
| 9 跨 Mod 服务 | 完成（ABI 2） |
| 10 Region / 并行 tick | **未开始** |

---

## 已知限制（诚实清单）

1. **tick 并行化未开始。** `TickEngine` 强制单线程推进。按 region 并行需要 `ExecutionContext` 校验。
2. **热重载是"语义冷重启"。** ClassLoader 可以重建，但 Scope 中的状态不会迁移。
3. **没有配置热重载。** 没有 `ResourceWatcher`；配置改动需要重启。
4. **能力不跨 Mod。** `Scope.getCapability` 只看自己，跨 Mod 用 `ServiceBroker`。
5. **注册窗口关闭后无法注册内容。** Minecraft 注册表在 `bootStrap()` 后冻结，运行期动态注册需要新的注入点。
6. **`ctx.world()` 在非世界阶段为 null。** 契约要求 Mod 判空。
7. **`TickService` 在主菜单阶段可能不可用。** 取决于 tick 注入是否已发生；用 `isAvailable()` 判断。

---

## 文档索引

| 文档 | 内容 |
|---|---|
| `README.md` | 项目概览与快速开始 |
| `architecture/CURRENT.md` | 本文件，唯一当前状态 |
| `ARCHITECTURE.md` | 内核结构、组件装配、启动时序 |
| `ABI.md` | 契约清单与 ABI 2 变更 |
| `API_REFERENCE.md` | 全部公开接口 |
| `MOD_DEVELOPMENT.md` | Mod 开发指南 |
| `PLATFORM_VERSIONING.md` | 版本策略 |
| `ERROR_MODEL.md` | 错误码与诊断 |
| `architecture/CLASSLOADER.md` | ClassLoader 契约 |
| `architecture/TICK_ENGINE.md` | Tick 契约与执行模型 |
| `architecture/CREATIVE_TABS.md` | 创造栏注入 |
| `design/TRANSFORMATION_ENGINE.md` | 转换引擎 |
| `design/SECURITY.md` | 分发边界与安全模型 |
| `performance/` | 性能预算与稳定性 |
| `decisions/` | 架构决策记录（ADR） |
| `history/` | 历史分析（不代表当前状态） |
