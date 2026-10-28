# Mili ABI 契约

`org.loader.api` 是 Mili Platform 的稳定契约面。所有 Mod 只编译 `mili-abi`，运行时由 Loader 注入具体实现。

`mili-abi` 模块零运行时依赖：不引用 Minecraft、不引用第三方库、不引用 Mili 内部组件。它的唯一编译期依赖是 JDK 标准库。

## 版本

| 常量 | 值 | 含义 |
|---|---|---|
| `VersionInfo.CURRENT_VERSION` | `"0.1.0"` | Loader 实现版本（语义化） |
| `VersionInfo.ABI_VERSION` | `2` | Mod 契约版本（整数，从 1 递增） |
| `VersionInfo.TARGET_JAVA` | `25` | 运行时最低 Java 主版本 |
| `VersionInfo.TARGET_MINECRAFT` | `"26.2"` | 唯一支持的 Minecraft 版本 |
| `VersionInfo.PLATFORM_ID` | `"mili-0.1.0-mc26.2"` | 平台稳定标识 |

`gradle.properties` 是唯一权威来源，`./gradlew check` 中的 `verifyVersionConstants` 会校验 `VersionInfo` 与它逐字段一致。三处出现同一个版本数字（gradle.properties、VersionInfo、MiliSymbol.MINECRAFT_VERSION）是有意为之：任何一处漂移都会让已编译的 Mod 无法加载，因此必须在构建期失败而不是运行期。

Loader 在加载每个 Mod 时校验三元组（platform / abi / minecraft），任一不匹配即拒绝并给出结构化错误（`MOD_PLATFORM_MISMATCH` / `MOD_ABI_MISMATCH` / `MOD_MINECRAFT_MISMATCH`）。没有模糊的兼容区间。

## 契约清单

### 入口与上下文

| 类型 | 说明 |
|---|---|
| `Mod` | 唯一入口点接口：`void initialize(ModContext)` |
| `ModContext` | Mod 与平台交互的唯一门面（见下） |
| `ModMetadata` | 只读元数据：id / name / version / author / description / dependencies / entrypoint |
| `ModDependency` | `modId()` / `versionRange()` / `required()` |
| `Environment` | `CLIENT` / `SERVER` / `DEDICATED_SERVER`，带 `isClient()` / `isServer()` |
| `Logger` | `info` / `warning` / `severe` / `severe(msg, throwable)` / `fine` / `finer` / `finest` |
| `Mili` | 静态门面：`apiVersion()` / `platformVersion()` / `mod(...)` / `events()` / `modIds()` / `isLoaded(...)` / `registerProvider(...)` |

`ModContext` 的成员：

```
modId()            metadata()      lifecycle()      environment()
scheduler()        events()        tick()           services()
config()           storage()       permissions()    resources()
registry()         world()         logger()         metrics()    performance()
getCapability(Class)               isActive()       submit(...)
```

其中 `config()` / `storage()` / `metrics()` / `performance()` / `isActive()` / `submit(...)` 是 `default` 方法，其余由平台实现。

`registry()` 与 `world()` 在环境不具备时返回 `null`（契约要求 Mod 判空），而不是返回一个「能调但不生效」的空实现。

### 生命周期

| 类型 | 说明 |
|---|---|
| `Lifecycle` | `state()` / `addListener(LifecycleListener)` / `removeListener(...)` / `isTerminal()` / `allowsNewWork()` |
| `LifecycleState` | `DISCOVERED` → `RESOLVED` → `LOADED` → `INITIALIZED` → `REGISTERED` → `RUNNING` → `STOPPING` → `STOPPED` / `FAILED` |

`addListener` 与 `removeListener` 都可用：平台内部维护「Mod 监听器 → 运行时包装器」映射，`removeListener` 按身份精确移除（见 `ApiModContext.LifecycleView`）。

### 事件

| 类型 | 说明 |
|---|---|
| `EventBus` | `addListener` / `removeListener` / `subscribe` / `post` / `postAsync` / `hasListeners` / `listenerCount` / `close` |
| `EventListener<T>` | 函数式接口 `void onEvent(T)` |
| `Subscription` | `unsubscribe()` / `isActive()` |
| `TickEvent` | `record(long tick, TickPhase phase, long durationNanos)`，带 `isEnd()` / `durationMs()` |
| `TickPhase` | `START` / `END` |
| `ModLifecycleEvent` | `record(String modId, LifecycleState from, LifecycleState to)`，带 `isStarted()` / `isStopped()` |
| `PlatformEvent` | `record(PlatformStage stage, String platformVersion, int modCount)` |
| `PlatformStage` | `BOOTSTRAPPING` / `READY` / `STOPPING` / `STOPPED` |

**派发语义**：先精确类型，再沿继承链自下而上（超类 → 接口 → `Object`）。同一个事件不会在同一个监听器数组里被派发两次，但同一个监听器若注册到多个类型，会按各类型分别收到一次。

监听器抛异常不中断分发，异常会送到平台的 `ErrorSink`（接到 `logs/mili-platform.log`）。

### 调度

| 类型 | 说明 |
|---|---|
| `Scheduler` | `submit(Runnable)` / `submit(Runnable, TaskPriority)` / `submitRepeating(...)` / `cancel(TaskHandle)` / `close()` |
| `TaskHandle` | `taskId()` / `state()` / `priority()` / `cancel()` / `future()` / `await()` / `isActive()` / `isFinished()` |
| `TaskState` | `CREATED` / `QUEUED` / `RUNNING` / `COMPLETED` / `CANCELLED` / `FAILED` |
| `TaskPriority` | `LOW(0)` / `NORMAL(5)` / `HIGH(10)` / `CRITICAL(20)`，值越小越优先 |

`TaskHandle.state()` 返回**实时**状态（读原子引用），不是提交时的快照。`await()` 在任务失败时抛运行时异常，在任务取消时抛 `CancellationException`。

`Scheduler.close()` 取消本 Mod 提交的全部任务，但不关闭调度器本身——调度器是 Scope 的资源，由 Scope 释放。

### Tick

| 类型 | 说明 |
|---|---|
| `TickService` | `currentTick()` / `runNextTick(Runnable)` / `runAfterTicks(int, Runnable)` / `runEveryTick(Runnable)` / `isAvailable()` |
| `TickService.TickTask` | `taskId()` / `isActive()` / `cancel()` |

没有 tick 源时（专用服务器未进入世界、纯 JVM 测试、平台尚未接管 tick），`isAvailable()` 返回 `false`，提交抛 `IllegalStateException` 并说明原因。不会静默排队等一个永远不会到来的 tick。

### 跨 Mod 服务

| 类型 | 说明 |
|---|---|
| `ServiceBroker` | `provide(Class<T>, T)` / `withdraw(Class)` / `discover(Class<T>)` / `require(Class<T>)` / `publishedTypes()` / `providedByMe()` |
| `ServiceUnavailableException` | 继承 `IllegalStateException`，带 `serviceType()` |

同一接口只允许一个提供方。跨 Scope 冲突在 `provide` 时抛 `IllegalStateException` 并指出双方 scope id；同 Scope 重复发布视为替换自己的实现。提供方 Scope 停止后 `discover` 返回空。

### 权限

| 类型 | 说明 |
|---|---|
| `Permission` | 内置权限枚举：`RESOURCE_READ` / `RESOURCE_WRITE` / `NETWORK_ACCESS` / `NATIVE_ACCESS` / `CAPABILITY_REGISTER` / `INTERMOD_COMMUNICATION` / `TRANSFORM_CLASS` / `TRANSFORM_OTHER_MODS` / `TRANSFORM_MINECRAFT` / `TRANSFORM_CORE` / `OVERWRITE_METHOD` |
| `PermissionManager` | `hasPermission(...)` / `checkPermission(...)` / `isClosed()`；`registerPolicy` 对 Mod 抛 `UnsupportedOperationException` |

默认策略：`resource.read` / `resource.write` / `intermod.communication` 允许，其余全部拒绝。Mod 侧只能查询，不能注册策略——那等于自己给自己发权限。

### 配置与存储

| 类型 | 说明 |
|---|---|
| `ConfigService` | `load(String modId)` → `ModConfig`（同 modId 同实例） |
| `ModConfig` | `getString/getInt/getBoolean/getDouble(key, default)` / `set*` / `has` / `keys` / `save()` |
| `ModStorage` / `StorageService` | 平台托管的小状态 KV，`<gameDir>/config/mili/storage/<modId>.properties` |

两者都是 Properties，但语义不同：config 是玩家会手改的文件（解析失败回退默认值），storage 是平台替你管的少量状态（启动次数、窗口位置）。

### 资源

| 类型 | 说明 |
|---|---|
| `Resource` | `id()` / `isClosed()` / `close()` |
| `ResourceManager` | `basePath()` / `resolve()` / `readAllBytes()` / `readString()` / `exists()` |

读取顺序固定为 **Mod JAR 优先，可写数据目录兜底**。`readString` 固定 UTF-8（不随平台默认字符集漂移）。`resolve` 在 `normalize()` 之后校验前缀，路径逃逸抛 `SecurityException`。

### 内容注册

| 类型 | 说明 |
|---|---|
| `MinecraftRegistry` | `block(BlockSpec)` / `item(ItemSpec)` / `blocks()` / `items()` / `findBlock(path)` / `isOpen()` |
| `BlockSpec` / `BlockPropertySpec` / `ItemSpec` | 声明式内容描述（builder 形式） |
| `BlockHandle` / `ItemHandle` | 注册结果：`id()` / `path()` / `modId()` |
| `WorldView` / `BlockHandle` / `BlockPos` / `Direction` / `BlockEntityView` / `MiliBlock` | 世界只读视图 |

注册窗口只在 Mod 初始化期开放；平台在世界加载前关闭它（Minecraft 的注册表在那一刻冻结）。窗口关闭后再注册会失败并说明原因，而不是静默丢弃。

### 客户端服务

| 类型 | 说明 |
|---|---|
| `ScreenService` | `open(ScreenSpec, ScreenListener)` / `closeCurrent()` / `isShowing()` |
| `ScreenSpec` | 声明式界面描述（尺寸、标签、按钮、暂停策略、HUD 模式） |
| `ScreenListener` | `onOpen()` / `onClick(widgetId)` / `onClose(cancelled)` |
| `KeyBindingService` | `register(KeyBindingSpec, Consumer<KeyEventType>)` → `KeyBinding` |
| `KeyBindingSpec` / `KeyEventType` / `KeyBinding` | 按键描述、事件类型、注册句柄 |

服务端或未装配时抛 `ScreenUnavailableException` / `KeyBindingUnavailableException`——显式失败优于静默无效。

### 字节码转换

| 类型 | 说明 |
|---|---|
| `@transform.annotation.MiliTransformer(target = "...")` | 声明式转换器，`phase` 默认 `MOD` |
| `@transform.annotation.MiliInject(at = InjectionPoint.X, ...)` | 声明注入点与回调 |
| `InjectionPoint` | `HEAD` / `RETURN` / `BEFORE_INVOKE` / `AFTER_INVOKE` / `MODIFY_RETURN` / `MODIFY_CONSTANT` |
| `TransformationPhase` | `CORE` / `MOD` |
| `InjectionContext` / `InjectionCallback` / `ExecutionContext` | 回调上下文 |
| `TargetMethod` / `TargetField` / `TargetInvocation` | 坐标类型（ABI 内部使用，Mod 不直接引用 Minecraft 坐标） |
| `MiliSymbol` / `MiliMapping` | 逻辑符号 → 真实坐标，供平台与 CI 校验 |

Mod 的 classpath 里**永远没有 ASM**。Mod 写注解，平台在加载期扫描、校验并合成转换器——校验失败在加载期以「MyMod 的 onTick 回调签名非法：…」的形式出现，而不是在 `defineClass` 时抛指向 Minecraft 的 `VerifyError`。

### 诊断

| 类型 | 说明 |
|---|---|
| `PlatformMetrics` | 堆、GC、线程、类数、Mod 数 |
| `MetricsService` / `PerformanceService` | provider 持有者，未装配时返回零值快照 |
| `PerformanceSnapshot` | 窗口化 TPS / tick 延迟 / FPS |

### 异常

| 类型 | 说明 |
|---|---|
| `MiliException` | 基类 |
| `ModException` | Mod 相关错误 |
| `CapabilityDeniedException` | 能力被拒绝 |
| `PermissionDeniedException` | 权限被拒绝 |
| `ScreenUnavailableException` / `KeyBindingUnavailableException` | 客户端服务不可用 |
| `StorageUnavailableException` / `ConfigUnavailableException` / `ConfigWriteException` | 存储与配置错误 |

## ABI 2 相对 ABI 1 的变更

ABI 2 是一次**纯新增**的契约升级：没有任何 ABI 1 的公开方法被移除或改签名。已有 Mod 只需把 `mod.json` 的 `"abi": 1` 改为 `"abi": 2` 并重新编译即可。

### 新增类型

| 类型 | 用途 |
|---|---|
| `event.TickEvent` / `event.TickPhase` | 平台 tick 事件（每 tick 两次） |
| `event.ModLifecycleEvent` | 任意 Mod 的状态迁移（平台总线可见） |
| `event.PlatformEvent` / `event.PlatformStage` | 平台整体阶段 |
| `service.ServiceBroker` | 跨 Mod 服务代理 |
| `service.ServiceUnavailableException` | 服务缺失 |
| `tick.TickService` / `TickService.TickTask` | 按 tick 计数调度 |

### 新增方法

| 类型 | 新增 |
|---|---|
| `ModContext` | `services()` / `tick()` / `permissions()` / `config()`（后两者中 `config()` 是 default） |
| `EventBus` | `hasListeners(Class)` / `listenerCount()`（均为 default） |
| `Mili` | `events()` / `modIds()` / `isLoaded(String)`；`MiliProvider` 新增两个 default 方法 |
| `MiliProvider` | `events()` / `modIds()`（default，旧实现无需改动） |

### 语义修正（不破坏二进制兼容，但行为变了）

这些修正针对的是「声明了但从未真正工作」的部分：

| 项 | ABI 1 | ABI 2 |
|---|---|---|
| `TaskHandle.state()` | 恒返回提交时的快照（`QUEUED`） | 返回实时状态 |
| `Lifecycle.removeListener()` | 抛 `UnsupportedOperationException` | 按身份精确移除 |
| `Scheduler.close()` | 空操作 | 取消本 Mod 的全部任务 |
| `EventBus` 类型匹配 | 仅精确类 | 精确类 + 继承链 + `Object` |
| `ModContext.events()` | 每个 Mod 一条私有总线 | 平台共享总线（类型隔离） |
| `ModContext.resources()` | 读一个不存在的相对路径 | JAR 优先 + 数据目录兜底 |
| `ModContext` 的 Scope 状态 | 永远停在 `DISCOVERED` | 初始化成功后推进到 `RUNNING` |

### 为什么升主版本号

ABI 2 引入了三个 `ModContext` 抽象方法。Mod 不实现 `ModContext`（平台提供实现），因此对 Mod **源码与二进制都兼容**。升版本的理由是契约本身的承诺变了：`events()` 的语义从「Mod 私有总线」变成「平台共享总线」，`TaskHandle.state()` 从「快照」变成「实时」——这两条都会改变已有代码的可观察行为，必须让 Mod 作者显式确认，而不是悄悄改掉。

## 破坏性变更策略

**递增 `ABI_VERSION` 的情形**：移除公开接口或方法；修改方法签名；改变方法语义导致已有调用方无法正常工作；改变类/接口的继承关系；移除或重命名 `CapabilityToken` 常量；修改 `VersionInfo` 中任何常量的值；新增抽象方法（即使 Mod 通常不实现该接口）。

**不递增（可随平台次版本发布）的情形**：新增独立接口；新增 `default` 方法；纯性能优化；`@Deprecated` 标注过渡期方法；新增枚举常量（调用方的 `switch` 若未写 `default` 会编译失败，因此这类变更需在发布说明中标注）。

**流程**：提案（RFC）→ 过渡期（旧 API 标 `@Deprecated` 并给迁移指引，至少一个次版本）→ 移除。迁移指南记录在 [MOD_DEVELOPMENT.md](MOD_DEVELOPMENT.md#从-abi-1-迁移到-abi-2)。

## 实现约束

`mili-abi` 的约束由构建强制，而不是靠自觉：

- **零 compile 依赖**：`mili-abi/build.gradle.kts` 不声明任何 `api` / `implementation` 依赖。
- **不引用 Minecraft**：Minecraft 坐标只出现在 `MiliSymbol` 的字符串里，且由构建期用真实 JAR 校验。
- **公开类型是接口、`record` 或 `enum`**：没有可变字段的公开类。
- **注解 `RetentionPolicy.RUNTIME`**：平台在加载期通过反射读取。
- **Javadoc 是契约的一部分**：如果 Javadoc 声明「不会返回 null」，实现必须遵守。

平台实现类位于 `org.loader.runtime.*`、`org.loader.loader.*`、`org.loader.runtime.minecraft.*`。Mod 引用它们不会在编译期报错（这些包不在 Mod 的 compile classpath 上，实际上根本编译不过），运行期也不会出现在 Mod 的 ClassLoader 可见范围内。
