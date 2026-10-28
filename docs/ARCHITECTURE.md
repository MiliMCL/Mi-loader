# 架构

本文描述 Mili Platform 在**当前代码**下的运行时结构、组件装配与启动时序。所有描述都能在代码里找到对应实现，不描述规划中的东西。

## 1. 分层

```
                    ┌──────────────────────────────────┐
    Mod 只编译这里  │           mili-abi               │  零运行时依赖
                    │  Mod / ModContext / EventBus /   │
                    │  Scheduler / TickService /       │
                    │  ServiceBroker / Registry / ...  │
                    └───────────────┬──────────────────┘
                                    │
                    ┌───────────────┴──────────────────┐
    Runtime 内核    │          mili-runtime            │
                    │  Scope / LifecycleManager /      │
                    │  Scheduler / EventBus /          │
                    │  ServiceBrokerImpl /             │
                    │  CapabilityManager /             │
                    │  PermissionManager / TickEngine  │
                    │  TransformerPipeline / ...       │
                    └───────┬───────────────┬──────────┘
                            │               │
        ┌───────────────────┴──┐   ┌────────┴──────────────────────┐
        │     mili-loader      │   │  mili-minecraft-integration   │
        │  发现 / 类加载 /      │   │  反射桥接 / 字节码转换 /       │
        │  生命周期调度 / CLI   │   │  注册表绑定 / 客户端服务       │
        └──────────────────────┘   └───────────────────────────────┘
```

依赖严格单向、无环。`mili-loader` 依赖 `mili-minecraft-integration`（启动时装配集成层），但集成层不反向引用 Loader——这样集成层可以独立替换。

## 2. Runtime 内核

`org.loader.runtime.kernel.Runtime` 是内核的唯一装配点。它创建并持有：

| 组件 | 职责 | 生命周期 |
|---|---|---|
| `Scope rootScope` | 根生命周期边界，所有其他 Scope 的父 | Runtime 关闭时释放 |
| `LifecycleManager` | 受管 Scope 的顺序启动与逆序关闭 | rootScope 资源 |
| `CapabilityManager` | 能力定义与授予 | rootScope 资源 |
| `PermissionManager` | 权限策略与显式授予 | rootScope 资源 |
| `ResourceRegistry` | 全局资源索引 | rootScope 资源 |
| `Scheduler` | 平台级线程池 + 优先级队列 | rootScope 资源 |
| `PerformanceMonitor` | TPS / tick 延迟采样 | rootScope 资源 |
| `EventBus` | **平台总线**（全平台唯一） | rootScope 资源 |
| `ServiceBrokerImpl` | 跨 Mod 服务表 | rootScope 资源 |
| `TickEngineHolder` | tick 引擎迟绑定容器 | 由集成层装配引擎 |

### 2.1 Scope

Scope 是生命周期边界，也是资源所有权边界。关闭协议有四条硬约束：

1. **幂等**：`shutdown()` 用 CAS 抢关闭权，抢不到立即返回。并发调用不会重复释放资源。
2. **子先于父**：父 Scope 的资源可能被子 Scope 使用，顺序反了会让子 Scope 撞上已释放的资源。
3. **失败汇总不吞**：单个资源关闭失败会汇总成 `ScopeShutdownException` 抛出，其余资源仍然关闭。
4. **监听器异常隔离但留痕**：监听器抛异常不中断状态迁移，但会写 WARNING 日志——静默吞掉会让「Mod 的生命周期回调没生效」无从解释。

`driveToRunning()` 把 Scope 从 `DISCOVERED` 一次推进到 `RUNNING`。中间四个状态对 Mod 作者没有可观察意义，但必须逐个迁移才能满足状态机；让每个调用方各写四行 `transitionTo` 的结果是有人漏写一行，然后 Mod 永远停在 `LOADED`。

### 2.2 Scheduler

- **优先级队列即工作队列**：`ThreadPoolExecutor` 直接从 `PriorityBlockingQueue` 取任务，中间没有二次排队。**值越大越优先**（`CRITICAL(20)` 先于 `LOW(0)`），同优先级按提交顺序（FIFO）。
- **队列有界**：默认容量 16384。队列满且线程池已到上限时 `submit` 抛 `SchedulerError`，而不是把任务塞进无底洞。
- **周期任务共用一个计时器线程**：不是每个 `submitRepeating` 一条线程。周期任务在计时器上直接执行，不占用工作线程（旧实现让工作线程 sleep 等下一周期，corePoolSize 个周期任务就能把池占满）。
- **任务表只装未终结任务**：完成/失败/取消时立即摘除。`activeTaskCount()` 就是表大小，不需要拿提交数减完成数（那个减法在有任务被拒、被跳过时必然算错）。
- **取消不被完成覆盖**：终结状态一律走 CAS，先到先得。

### 2.3 EventBus

全平台唯一的事件总线。派发顺序：**精确类型 → 父类（自下而上）→ 接口（BFS）→ `Object`**。

超类型链按 `Class` 缓存在 `ClassValue` 里：每条链只算一次，之后每次发布是一次数组读取。用 `ClassValue` 而不是普通 Map 的原因：条目与 Class 同生命周期，Mod 卸载后链会随 Class 一起被回收，不会把 `ModClassLoader` 钉在堆上。

监听器集合存成不可变数组，`post()` 零分配。增删监听器时复制数组——监听器数量是个位数到几十，复制远比热路径分配划算。

监听器抛异常不中断分发，异常送到 `ErrorSink`（平台实现接到 `logs/mili-platform.log`）。

### 2.4 能力与服务：两套不同的语义

这是最容易混淆的一对概念，它们在代码里是**两套独立的表**：

| | 能力（Capability） | 服务（Service） |
|---|---|---|
| 回答的问题 | 平台给了**我这个 Mod** 什么 | **别的 Mod** 能为我做什么 |
| 授予方 | 平台（`Scope.grantCapability`） | Mod 自己（`ServiceBroker.provide`） |
| 可见范围 | 仅授予的 Scope 自己 | 全平台 |
| 冲突语义 | 同类型覆盖 | 跨 Scope 冲突显式失败 |
| 用途 | 客户端渲染/输入/声音能力、JVM 能力 | 经济系统、聊天 API、任何跨 Mod 接口 |

`Scope.getCapability` 不向上查找父 Scope——能力的作用域语义是「谁被授予谁才有」。跨 Mod 发现走 `ServiceBroker`。

### 2.5 TickEngine

真实 tick 由字节码注入驱动：

```
MinecraftServer#tickServer  ──注入──▶  TickCallbackDispatch.onTickBegin()
                                            │
                                            ▼
                                      TickBridge.beginTick()
                                            │
                                            ▼
                                      TickEngine.beginTick()
                                        ├─ 新建 TickContract（阶段 TICK_START）
                                        ├─ 推进到 PRE_TICK，触发 preTick 监听器
                                        ├─ 执行 runEveryTick 常驻工作
                                        └─ 通知 TickObserver（发布 TickEvent START）
        ... Minecraft 世界/实体 tick 在此发生 ...
MinecraftServer#tickServer  ──注入──▶  TickCallbackDispatch.onTickEnd()
                                            │
                                            ▼
                                      TickBridge.endTick()
                                        ├─ 幂等推进 SCHEDULE（触发 schedule 监听器）
                                        ├─ 幂等推进 CORE_TICK（触发 coreTick 监听器）
                                        └─ TickEngine.endTick()
                                             ├─ runPendingTasks（执行本 tick 提交的任务）
                                             ├─ 推进 ASYNC_COMPLETION / POST_TICK / TICK_END
                                             ├─ 记录指标
                                             └─ 通知 TickObserver（发布 TickEvent END）
```

注入点只能包住整个 `tickServer`（方法头 + 每个 return 之前），无法精确切到世界 tick 之间。因此本 tick 提交的任务在**世界逻辑之后、POST_TICK 之前**执行。这是注入模型下唯一诚实的语义——声称能在世界 tick 中间插进去、实际做不到，比晚一点执行要糟糕得多。

`TickCallbackDispatch` 是注入字节码唯一引用的类，由平台 AppClassLoader 定义，因此 **Minecraft 字节码对 Mod 类零引用**——这是 `ClassLoaderLeakTest` 守的线。

### 2.6 转换流水线

```
transform(className, bytes, classLoader)
  ├─ 1. registry.matching(className)     零匹配 → 直接返回原始字节（热路径）
  ├─ 2. cache.get(classLoader, className) 命中 → 直接返回
  ├─ 3. for each transformer（确定性顺序：phase 权重 → priority → id）
  │      ├─ guard.checkTransform(...)     权限
  │      ├─ ledger.claim(...)             冲突
  │      ├─ transformer.transform(ctx)    实际转换
  │      └─ 结果归约：Unchanged/Skipped 跳过，Transformed 更新字节，Failed 中断
  ├─ 4. verifier.verify(...)              字节码合法性
  ├─ 5. audit.log(...)
  ├─ 6. dumper.dump(...)                  调试（默认关闭）
  └─ 7. cache.put(...)
```

**失败必须中断整条链**，不能「跳过继续」。跳过会产出一个「A 的逻辑不存在、B 的逻辑存在」的类，表现为游戏正常运行、A 的功能莫名不起作用。

## 3. 组件装配（最容易断的地方）

平台的组件不是「自动发现」的，每一处连接都必须显式装配。这些连接点历史上多次断开，因此每个都记录在案：

| 连接 | 装配位置 | 断开的症状 |
|---|---|---|
| Runtime → 平台总线 | `Runtime` 构造 | Mod 收不到任何事件 |
| TickEngine → 平台总线 | `EntryPointHook.wireTickPipeline` | `TickEvent` 零发布者 |
| TickEngine → Mod 的 `ctx.tick()` | `Runtime.tickEngine()` 容器 | Mod 提交的 tick 任务永不执行 |
| 平台总线 → Mod 的 `ctx.events()` | `ModContext.events()` 返回 `runtime.events()` | 订阅成功但收不到 |
| Scope 迁移 → `ModLifecycleEvent` | `LoaderMain` 注册的 Scope 监听器 | 跨 Mod 就绪观察不可用 |
| Mod Scope → RUNNING | `LoaderMain.invokeModEntrypoints` 成功后 `driveToRunning()` | 生命周期回调永不触发 |
| `Mili.registerProvider` | `LoaderMain.installMiliFacade` | `Mili.mod(...)` 恒为空 |
| `ConfigService.registerProvider` | `LoaderMain.installRuntimeServices` | Mod 在 `initialize` 里读配置失败 |
| 服务代理 → Mod 视图 | `ModContext.services()` → `broker.forScope(scope)` | 服务归属错误、停止不失效 |
| `ModContext` → 数据目录 | `LoaderMain` 传 `<gameDir>/config/mili/data` | Mod 资源读不到 |
| 事件视图 → Scope 资源 | `ApiModContext` 构造时 `registerResource` | Mod 卸载后监听器仍在跑 |

判断一个组件是否真的接上的方法：**找到它的调用者**。零调用者的公开方法等于不存在。

## 4. 启动时序

```
main()
 ├─ StartupTimeline("bootstrap")          时间线从参数解析前开始
 ├─ parseArgs                            --mili-mods 摘出，其余转发
 ├─ LoaderConfig.at(gameDir, modsDir)
 ├─ new LoaderMain(config, timeline)
 └─ launch(mcArgs)
     ├─ ensureMinecraftPresent            缺失时反射调用 InstallerMain
     ├─ gameProvider.locateGame           发现 MC JAR + 库
     ├─ mcProvider.createGameClassLoader  建立唯一 MC CL（装配转换管线）
     ├─ ModDiscovery.scan / discover / resolveDependencies
     ├─ runtime.start()                   rootScope → RUNNING，发布 BOOTSTRAPPING
     ├─ validateVersionBindings           三元组严格校验
     ├─ for each mod:
     │    ├─ rootScope.createChild("mod:<id>")
     │    ├─ classLoaderManager.create(...)
     │    ├─ ModOwners.register(mcl, modId)
     │    └─ lifecycleManager.registerScope + Scope 监听器（发 ModLifecycleEvent）
     ├─ installRuntimeServices            存储 / 配置 / 客户端服务 / 指标 / ErrorSink
     ├─ invokeModEntrypoints              逐 Mod initialize(apiCtx)
     │    └─ 成功后 mod.scope().driveToRunning()
     ├─ installMiliFacade                 Mili provider 接通
     ├─ publishPlatformStage(READY)
     └─ gameProvider.launch(...)
          ├─ PlatformLog.enableFileLogging
          ├─ armModListProvider
          ├─ EntryPointHook.install        创建 MinecraftBootstrap + 接通 tick 管线
          └─ hook.invokeMinecraftMain      状态机推进 → 注册窗口 → 启动 MC（阻塞）
```

### 为什么这些顺序不能动

- **MC ClassLoader 必须先于所有 ModClassLoader**：否则 Mod 无法与游戏共享同一份 Minecraft 类。
- **存储/配置服务必须先于 `initialize()`**：Mod 在初始化阶段就会调用 `ctx.storage()` / `ctx.config()`。放在 `launch()` 里（初始化之后）会让每个用存储的 Mod 都以 `StorageUnavailableException` 失败。
- **客户端服务必须先于 `initialize()`**：Mod 在初始化里注册按键与界面。晚装配会让 Mod 只看到「当前环境没有客户端输入系统」，而那与事实不符。
- **注册窗口必须在游戏 main 之前关闭**：Minecraft 26.2 的 `BuiltInRegistries.bootStrap()` 末尾会 freeze 全部注册表，之后一个都注册不进去。

## 5. 关闭时序

```
gameProvider.launch 返回
 └─ shutdown()
     ├─ 逆序 shutdown 每个 Mod 的 Scope   → 触发 ModLifecycleEvent(STOPPED)
     ├─ storageService.flushAll()          Mod 的 shutdown 钩子已把最终状态写进内存
     ├─ 采集每 Mod 已加载类数              必须在 CL 关闭之前取
     ├─ classLoaderManager.close()
     ├─ runtime.close()
     │    ├─ PerformanceService.clearProvider
     │    ├─ 发布 PlatformEvent(STOPPING)
     │    ├─ lifecycleManager.close()      逆序关闭受管 Scope
     │    ├─ rootScope.shutdown()          子 Scope → 能力 → 资源（逆序）
     │    └─ scheduler.close()
     └─ reportShutdownAudit                线程基线对比，暴露残留线程
```

类数必须在 ClassLoader 关闭**之前**采集：关掉之后 `loadedClasses()` 就是空集合，报告会变成「每个 Mod 0 个类」，看起来像一次完美回收，实际是没测。

## 6. 类加载

三层结构：

```
Bootstrap / App ClassLoader
  └─ mili-abi、mili-runtime、mili-loader、mili-minecraft-integration
        ▲
        │ 父委托
  MinecraftClassLoader（每个进程一个）
  └─ Minecraft JAR + 库
        ▲
        │ 父委托
  ModClassLoader（每个 Mod 一个）
  └─ 该 Mod 的 JAR / 目录
```

- Mod A 无法引用 Mod B 的类——它们由不同的 ClassLoader 解析。
- ABI 类由 App ClassLoader 提供，所有 Mod 共享同一份定义。
- `ModClassLoader.getResourceAsStream` 优先读自己的 JAR，再委托父加载器；并禁用 JVM 级 JAR 缓存（否则读过一次资源后 jar 文件被 Windows 锁住，热重载无法替换）。

## 7. 归因与诊断

`ModOwners` 维护「ClassLoader → modId」映射。任何由某个 Mod 的 CL 加载的类都能反查到 modId，Mod 侧不需要做任何事。

TickEngine 用这条映射给每个 tick 任务归因（`ModAttribution`）：任务不知道自己属于谁，平台在执行前后读一次 `work.getClass()` 即可。跨 tick 排队的工作同样归因——漏掉这一处会让「某些 Mod 的耗时凭空消失」。

`TickWatchdog` 在单个 tick 任务超过阈值时输出停滞报告（含 modId 与任务 id），接到 `PlatformLog`。

## 8. 已知限制

诚实列出当前实现做不到的事：

- **tick 并行化**：`TickEngine` 强制单线程推进（`driverThread` 守卫）。按 region 并行的调度器需要引入 `ExecutionContext` 校验，尚未实现。
- **热重载**：Mod 的 ClassLoader 可以重建，但 Scope 中的状态不会迁移——这是「语义冷重启」而非真正的热替换。
- **配置热重载**：没有 `ResourceWatcher`。配置改动需要重启。
- **能力跨 Mod 发现**：`Scope.getCapability` 只看自己。跨 Mod 请用 `ServiceBroker`。
- **注册窗口之后的内容注册**：Minecraft 注册表在 `bootStrap()` 后冻结，平台无法绕过。运行期动态注册需要新的注入点。
