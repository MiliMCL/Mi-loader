# Mili 平台架构文档

## 设计理念

Mili 是一个从零构建的 Minecraft 客户端 Mod 加载器，不是 Fabric 克隆，不带 Mixin 依赖，不封装 LaunchWrapper。整个平台以 MiliRuntime 为内核，将 Minecraft
视为宿主进程而非核心依赖。 Runtime 本身对 Minecraft 无感知，所有对 Minecraft 客户端的桥接、事件翻译和类加载隔离集中在 `mili-minecraft-integration`模块中，从而保证
Runtime 子系统的可复用性与可测试性。

平台所有内部通信均经过 Scope 树、 Capability
令牌和 EventBus 构成的强隔离通道。调度器提供结构化并发语义，生命周期管理器驱动九状态状态机，资源管理器负责追踪与回收，权限系统采用默认拒绝策略。这套设计的目标是实现生产级鲁棒性，让 Mod 开发者在编写扩展时不需要关心底层类加载或线程调度的细节，仅依赖稳定
ABI 接口即可。

平台目标运行在 Java 25 上，对接 Minecraft 26.2 客户端， LWJGL natives 版本锁定在
3.4.1+2。版本常量统一维护在`mili-abi`的`org.loader.api.VersionInfo`中：`CURRENT_VERSION="0.1.0"`，`ABI_VERSION="1.0"`，`TARGET_JAVA="25"`，`TARGET_MINECRAFT="26.2"`。

Mili 平台把 Minecraft 客户端作为宿主进程管理，但理念上 Runtime 并不偶然依赖 Minecraft 存在——所有 Minecraft 相关的适配都集中在单一模块，其他模块在处理
Minecraft 安装不存在时仍能独立完成启动与校验。这一设计为未来无游戏启动场景留出余地。无游戏启动场景包括：服务器专用工具、开发期 CLI 工具、离线分析、集成测试框架等。

Mili 不反对与其他加载器共用代码路径。`GameProvider` SPI 允许装载其他 Game Provider 实现以支持 Bukkit、 Forge 或其他体系，但当前仅聚焦 Minecraft 26.2 客户端，不支持
MultiLoader 式的一码多载； Mod 项目在现阶段不应以跨加载器兼容为目标。

设计理念的一条核心原则是：所有面向 Mod 的 API 只通过 abi 包暴露， runtime 包被视为内部，这样即使平台在后续版本中大幅重构其内部系统， Mod 代码的迁移成本也将降到最低。 ABI
一旦发布，即承诺向后兼容性； ABI 的演化遵循语义化版本规范，`ABI_VERSION="1.0"`表示当前处于初始稳定阶段，跨大版本升级时以主版本号递增，不会在不加警告的情况下删除或破坏性变更已发布的接口。平台还实行强类型规划：除了少数遗留桥接入口外， abi 层
API 的返回类型均使用 sealed interface 与 record，以满足 compile-time exhaustive check。

## 模块职责

| 模块 | 职责 | 关键包入口 | 依赖 |
|---|---|---|---|
| `mili-abi` | 稳定应用二进制接口，纯接口类型与数据类型，零运行时依赖，存放当前版本常量与规范版本号 | `org.loader.api.*` | 无 |
| `mili-runtime` | 运行时内核，提供 Scope 树、状态机、能力管理器、资源管理器、权限系统、调度器、事件总线、 Mod SDK 实现 | `org.loader.runtime.*` | mili-abi |
| `mili-loader` | 加载器启动入口， Mod 发现与清单解析，游戏提供者 SPI，类加载器隔离，版本校验与入口点挂钩 | `org.loader.loader.*` | mili-runtime, mili-abi, mili-minecraft-integration |
| `mili-minecraft-integration` | Minecraft 客户端桥接，基于反射的 Tick 轮询器，生命周期事件转发，注册表桥接，无 Mixin、无 Fabric | `org.loader.runtime.minecraft.*` | mili-runtime, mili-abi |

mili-abi 是整个平台对外契约的根。它只包含接口、枚举与记录类型，除 JDK 类以外没有任何依赖。所有其他模块都依赖 abi，而 abi 不依赖任何内部模块。这种严格的单向依赖在 CI 中通过 ArchUnit
断言强制执行：`mili-runtime` 项目内不得出现`org.loader.runtime.minecraft`包的导入。也就是说，`mili-runtime` 的代码必须通过
`mili-abi`提供类型抽象，间接引用`mili-minecraft-integration`，不得有任何直接包级别的耦合。

mili-runtime 涵盖了平台的所有核心子系统。它从 abi 继承接口并提供实现，同时保留内部类型与包命名空间，确保外部代码通过 SDK 类型访问 Runtime，因而可以随时重构内部结构而不必破坏兼容性。 runtime
包内部的 Scope、 Scheduler、 CapabilityManager、 PermissionManager 全部通过 ServiceLocator /ServiceRegistry
注入，不依赖任何单例模式，使得每个子系统都能在独立测试上下文中被 Mock 或替代。子系统的注入通过 Java 标准的`ServiceLoader` 机制与自定义的 `MiliServiceRegistry` 工厂结合进行。

mili-loader 只行使加载阶段的职责：解析 `mod.json`、决定加载顺序、构建隔离的 `URLClassLoader`、通过`GameProvider` SPI 把控制权交给 Minecraft
客户端，以及通过入口点回调执行 Mod 的初始化方法。 loader 同时承担版本校验的责任，确保每个 Mod 与平台之间的契约清晰，在跨平台或多版本共存场景中能够给出精确的错误提示。

mili-minecraft-integration 是唯一与 Minecraft 客户端直接交互的模块。它把 Native 客户端进程的事件翻译成 Runtime 能理解的桥接并注入事件流，同时提供
`MinecraftBootstrap` 与 `MinecraftLifecycle`，为`mili-loader`所需的启动上下文。`RuntimeEnvironment` 枚举已从 Minecraft Bridge
移入`mili-runtime`， minecraft 模块通过依赖自动访问它。

模块职责划分还有一层更长远的考虑： Runtime 子系统的 Scope、 Scheduler、 CapabilityManager、 PermissionManager 与 EventBus 全部设计为与
Minecraft 无关，将来可以复用到服务器核心或离线模式下的分析工具中；仅当平台决定正式支持服务器进程时，才需要在 `mili-runtime` 中新增 SERVER 分支的事件流。此决定当前被推迟，所有当前开发重心落在
CLIENT 运行时，不会产生 SERVER 方向的设计耦合。

## 依赖关系图

```mili-loader → mili-runtime
mili-loader → mili-minecraft-integration
mili-loader → mili-abi
mili-minecraft-integration → mili-runtime
mili-minecraft-integration → mili-abi
mili-runtime → mili-abi
mili-abi → (none)```该依赖关系在 Gradle 中通过严格 API 声明生效。 loader 与 minecraft-integration 虽然都依赖 runtime，但它们之间并无直接依赖关系。 mili-loader 不访问`org.loader.runtime.minecraft`包下的任何类型，这一边界与 mili-abi 中的 assertArchUnit 规则相互覆盖，防止对向的意外耦合。

Loader 与 minecraft-integration 的接口定义位于`org.loader.runtime.mod.ModContext`与`org.loader.runtime.minecraft.MinecraftLifecycle`中，所有 interface 契约都归于
runtime 包，使得 loader 与 minecraft 模块彼此不依赖。

依赖图的设计还隐含了一个约束：`mili-minecraft-integration`虽然技术上可以通过直接访问`mili-runtime`的内部类，但从设计上讲，只有通过 SDK 类型获取、通过 ServiceRegistry
查找，才能确保 ABI 版本的稳定性不会因 minecraft 模块的内部微调而被无意中绕过。 CI 用显式的依赖声明校验这一约定，只有 loader 与 minecraft 包内对运行时内部类型的访问路径才会被 ArchUnit
追踪，任何其他路径都会导致校验失败。

runtime 层与 abi 层之间的契约也是 CI 验证的重点：任何 abi 接口被 runtime 实现类缺失或签名不匹配的变更都会在 ArchUnit 断言阶段被捕获。 abi 与 runtime 之间的接口实现归属性通过一个名为`@Implements`的运行时注解进行自动关联； CI 会扫描缺失`@Implements`注解的 abi 方法，或存在`@Implements`注解但未在 runtime 中找到对应实现的错误，都会立刻拒绝构建。

## 包布局

abi 层声明所有公开类型：

org.loader.api.* — 顶层 facade， Mili、 ModMetadata、 Environment、 Logger、版本常量
org.loader.api.lifecycle.* — 接口类型： Lifecycle、 Phase、 Transition
org.loader.api.scheduler.* — Scheduler、 TaskHandle、 TaskPriority、 TaskState
org.loader.api.event.* — EventBus 接口 Subscription、 EventListener
org.loader.api.capability.* — CapabilityManager、 CapabilityToken、 Capability
org.loader.api.resource.* — Resource、 ResourceManager
org.loader.api.permission.* — Permission、 PermissionManager、 DenyReason
org.loader.api.exception.* — MiliException、 ApiException 等

abi 包的所有 public 接口均使用 sealed interface 规划：每个接口的 sealedpermits 列表仅包含该接口在当前版本下允许的第三方实现类，使得任何新增实现必须经过接口拥有者审核。适用于
enum-like 的 abstract 数据类型——如`TaskPriority`是一个密封类而不是简单的 enum，便于平台在不改变公开语义的情况下在内部增加优先级比较逻辑。`Environment`接口（非 sealed）则是值得注意的例外：它提供了一个稳定的公共属性访问器，但它的方法返回类型均为基本类型或字符串，不存在因
ABI 演化导致的风险。

runtime 层提供实现，并划分多个子命名空间：

org.loader.runtime.* — RuntimeEnvironment 枚举（ CLIENT/SERVER/DEDICATED_SERVER）
org.loader.runtime.tick.* — TickContract （多相位的 tick 记录）
org.loader.runtime.kernel.* — Runtime、 Scope、 LifecycleManager、 CapabilityManager、 CapabilityToken、 Permissions、 Resources、 Errors
org.loader.runtime.scheduler.* — Scheduler、 TaskHandle、 TaskPriority、 TaskState 实现
org.loader.runtime.service.* — EventBus、 Configuration、 Registry、 ResourceManager、 Network
org.loader.runtime.mod.* — Mod、 ModContext （实现）、 ModLoader、 ModManifest、 VersionBinding、 ServiceRegistry、 ModDiscoverer
org.loader.runtime.client.* — ClientCapabilities （客户端专有扩展）
org.loader.runtime.error.* — ModLoadError、 LifecycleError、 CapabilityError、 PermissionDenied 等具体错误类型
org.loader.runtime.instance.* — Instance、 ModManager、 ModSet、 ModSources
org.loader.runtime.jvm.* — JvmCapabilities （ JVM 层扩展点）
org.loader.runtime.util.* — ModLogger、 RuntimeVersionInfo
org.loader.runtime.reference.* — ReferenceMod （内置校验 Mod）
org.loader.runtime.security.* — AuditLog
org.loader.runtime.observability.* — RuntimeDiagnostics、 ResourceLeakDetector
org.loader.runtime.launcher.* — DevLauncher （开发模式单进程启动入口）
org.loader.runtime.minecraft.* — minecraft-integration 模块专用，放在此包下

loader 层服务启动阶段：

org.loader.loader.* — LoaderMain （ JAR 入口点）
org.loader.loader.classloader.* — ModClassLoader、 ModClassLoaderManager
org.loader.loader.discovery.* — ModDiscovery、 MinecraftDiscovery、 LibraryResolver
org.loader.loader.config.* — LoaderConfig
org.loader.loader.game.* — GameProvider SPI、 MinecraftGameProvider 实现
org.loader.loader.hook.* — EntryPointHook

包分层背后的原则是：所有在 Mod 代码中可见的类型必须只来自`org.loader.api.*`。运行时虽有丰富的子包结构，但均视为内部实现，不保证跨大版本的兼容性，也绝不应在
Mod 代码中直接导入。 Abi 层记录（ record）的所有字段均为不可变； abi 层的枚举值不允许添加非`@Since`注解标记的新值，以此保护 abi 层的契约不被后期变更破坏。`org.loader.api.scheduler.*`包把 Scheduler、 TaskHandle、 TaskPriority、 TaskState 同时以接口类型暴露，并运用 sealed interface
语言特性使得仅平台内部可以声明具体实现类。同样的策略适用于 abi.event 与 abi.capability
包。`TaskHandle`接口是密封的，仅允许`CompletedHandle`、`RunningHandle`、`CancelledHandle`、`FailedHandle`四种实现——这一编译期约束防止了 Mod
代码出现"未处理所有可能的状态"这一类错误。

包名设计刻意避免 "minecraft" 或 "mod" 字样出现在 api 级别。`Environment`与`RuntimeEnvironment`等值的语义通过显式的 Enum 字段区分。`Permission`定义了命名空间节点，按功能域分块组织。日志接口使用 SLF4J 风格的占位符模式。所有 abi 层的工厂方法都声明在`org.loader.api.Mili`类下，使得开发者只需通过静态 facade 即可入口整个平台。

所有 runtime 子包的`package-info.java`都为模块归属标记`@Internal`注解， CI 在 ArchUnit 校验中会强制任何非 runtime 包不能引用带`@Internal`的类型； loader 与 minecraft 模块虽然可以直接引用 runtime，但也不得直接实例化标记了`@SpiOnly`的 SDK 类型——这些类型必须通过工厂方法获得。`org.loader.runtime.minecraft.*`包虽然以`org.loader.runtime.*`为父包，但其内容只来自`mili-minecraft-integration`Gradle
模块——compile-time 通过 sourceSet 隔离保证它是独立编译单元。运行时通过 Gradle 的 dependency 声明与 runtime 代码合并为混合 JAR。 CI 以此为原则强制执行 package 与
module 的双向映射。对于 runtime 层内部互访的同包类型， ArchUnit 的规则通过严格的包间访问方向控制，确保依赖方向在运行时包内部也能保持清晰。

## 生命周期

每个 Mod 实例在平台中经历九状态机。状态按顺序如下：

1. **DISCOVERED** —— JAR 目录或扩展点发现清单文件，尚未解析元数据
2. **RESOLVED** —— 解析`mod.json`，校验版本绑定、依赖图与能力声明
3. **LOADED** —— 为该 Mod 构建独立的`ModClassLoader`，加载并验证字节码是否引用受允许的包
4. **INITIALIZED** —— 调用 Mod 入口点的主类`initialize`方法，得到`ModContext`5. **REGISTERED** —— ModContext 向 Runtime 注册，通过 LifecycleManager 加入 Scope
6. **RUNNING** —— Mod 完整接收事件、调度任务、应用注册；运行期间可以进行动态能力申请
7. **STOPPING** —— 平台发起关机或 Mod 主动退出，开始资源回收、取消挂起任务
8. **STOPPED** —— 资源已释放、 ClassLoader 已关闭，进入终态
9. **FAILED** —— 某阶段抛出不可恢复异常，依赖此 Mod 的后续 Mod 将被跳过加载`STOPPED`与`FAILED`是终态。从`RUNNING`可以直接进入`STOPPING`，但无法回退到更早状态。发现或加载阶段的错误触发状态回退到`FAILED`，`LifecycleError`详细记录错误上下文。 Scope 树上的子节点在父级失败时也会联动失败，避免孤立运行的部分状态。`LifecycleManager.resolve`（解析阶段）与`LifecycleManager.advance`（推进阶段）分别赋予不同权限——解析阶段只需要读 manifest，推进阶段需要持有`LIFECYCLE_ADVANCE`能力。`LifecycleManager`实现状态机并强制合法转换。非法转换将被拒绝并抛`IllegalTransitionException`。这种防御性设计使得严重错误在最早可捕获的边界被隔离，而不是延迟传播到宿主游戏。`LifecycleError`是
sealedexception
类族的主干，其具体子类包含`ModLoadError`（加载阶段错误）、`ScopeError`（ Scope 相关错误）、`ResourceError`（资源操作错误）、`InitializationError`（入口点调用错误）等。`MiliException`类提供`errorCode()`与`debugInfo()`统一抽象；每个异常对应一个错误码枚举，此类错误码枚举通过`@Since`注解标记其引入的 ABI 版本， abi 的兼容性矩阵也涵盖错误码级别。

LifecycleManager 不仅管理单 Mod 的状态，还在全局维护一张 Scope 树。根 Scope 承载 Runtime 自身的服务（ Scheduler、 PermissionManager、 EventBus）； Mod
拥有的 Scope 作为子 Scope 挂到根下。当父 Scope 从`RUNNING`进入`STOPPING`时，子 Scope 立即触发对应状态实现闭包，无论其当前处于`INITIALIZED`还是`REGISTERED`中间状态。这一机制保证运行期间的错误不会导致子 Mod 继续运行于已半关闭的环境中。`LifecycleManager`还提供一种"状态后退保护"： Scope 与 Mod 状态在`RUNNING`后不能后退到`INITIALIZED`，但允许在模式`ModLifecycleMode.DEBUG_MODE`下强制重启 Scope。 DEBUG_MODE 标志使得 DevLauncher 可以执行"热重启"——先停掉 Scope 上的所有服务，重新执行 Initialize 入口，然后重新推进 REGISTERED 与 RUNNING
阶段，整个过程中不需重新创建 ClassLoader。这一特性对开发期间的调试极有帮助，但正式环境的`LifecycleMode.RELEASE_MODE`下严禁使用。

在 FAILED 终态， LifecycleManager 会向 EventBus 发送`MOD_FAILURE`事件，允许其他 Mod 订阅并做降级或关闭流程。这种机制配合
Scope 结构形成一个优雅的级联失败策略。`ScopeError`记录包含异常的 Mod ID、 Scope 路径、因果关系链与导致失败的具体异常类名。在开发模式下 RuntimeDiagnostics 能够将 FAILED 状态的
Scope 路径高亮至 DevHUD。 FAILED 状态的 Mod 在 Scope 关闭时同样会经过资源回收路径——FAILED 不跳过资源回收，以保证即便出错的 Mod 也不会遗留 ClassLoader、文件或句柄泄漏。

## 加载器启动流程

Minecraft 26.2 客户端使用进程内加载 URLClassLoader 启动。这意味着 loader 与 Minecraft 客户端驻留在同一进程， loader 通过`GameProvider`SPI 提供的`MinecraftBootstrap`调用链启动客户端主类`net.minecraft.client.main.Main`。启动流程在设计阶段就确定了"in-process"模式——Mili 不会为每个游戏会话
spawn 一个子进程，而是将自身加载器主类合并到 MC 客户端的 JVM 中运行，以确保 loader 对游戏线程与物理内存的强控制与极低延迟通信。

LoaderMain 是 JAR 的 Premain-Class，入口方法同时声明 Main-Class 以支持两种挂载模式。入口先解析`LoaderConfig`，包含`gameDir`、可选的`abiVersion`覆盖值以及开发模式开关等字段。解析阶段失败会直接打印错误消息并调用`System.exit(1)`退出 JVM。`LoaderConfig`的解析优先级依次为：命令行参数 → 环境变量 → JVM 系统属性
→默认值； LoaderConfig 解析完成后将结果缓存在`StaticContext`中，使得运行时中的任意线程都能一致访问同一份配置。

随后创建一个名为`mili-bootstrap-classloader`的根 URLClassLoader，隔离全部平台模块类与系统类加载器。 bootstrap
classloader 通过锁定来实现：它的构造参数不接受动态添加 URL，仅在构建完成后冻结。`MinecraftDiscovery`解析 MC 主 JAR 中`version.json`的 assetIndex 与 libraries 列表。131 个基础库按规则过滤出当前操作系统与架构对应的必需条目， natives
路径注入到系统属性`org.lwjgl.librarypath`。不能精确匹配时由`LibraryResolver`走平台 natives 递归规则，解压时不覆盖已有 natives 文件以防止多启动器共用 natives
目录时的冲突。 LibraryResolver 的下载操作走平台的`net.mirror`，支持通过 LoaderConfig 使用自建镜像。

构建完 GameClassLoader 后进入 SPI 注册阶段：`ServiceLoader.load(GameProvider.class)`找到`MinecraftGameProvider`实现，由其拼接客户端启动参数并通过反射调用主类。 loader 整个此阶段从未引用任何 MC 包名，所有接触都通过`GameProvider`SPI 抽象，这一层屏障确保主类签名变化时只需调整
minecraft-integration 实现。`MinecraftLifecycle`在启动成功结束后返回客户端实例引用，由`TickBridge`绑定心跳；启动失败则`MinecraftLifecycle.abnormalShutdown()`被调用。

游戏启动后，`ModDiscovery`扫描`mods/`目录中每个
JAR，读取`META-INF/mod.json`。清单解析期间任何字段缺失、格式错误、能力名无效的情况都会返回`ValidationResult`，其中包含三种错误代码：`MOD_PLATFORM_MISMATCH`表示当前`RuntimeEnvironment`与清单中的`runtimeEnvironment`不匹配；`MOD_ABI_MISMATCH`表示清单`abiVersion`字段与当前
ABI 规范不兼容；`MOD_MINECRAFT_MATCH`表示清单中`minecraftVersion`字段与当前实际 MC 版本不一致。解析失败的 Mod 不会进入下一步。`VersionBinding`的解析使用
MavenArtifact 推荐的版本范围表达式，解析结果为一个四段元组： lowerBound、 upperBound、 lowerInclusive、 upperInclusive。 VersionBinding 支持通配范围如"1.0.*"，语义化范围如
"^1.2.3"、"~1.2.3"，以及显式区间如 "[1.0, 2.0)"。平台当前的实装版本是 "0.1.0"， ABI 版本为"1.0"；按照设计约定，保持 ABI 兼容性的小版本升级仅会改变 CURRENT_VERSION
的帕累托版本号，而 ABI_VERSION 仅在发生破坏性变更时才会升号。

VersionBinding 还支持 "jit-lock" 模式——当`mili.versionBind`字段未指定时， Mod 默认绑定到当前平台版本；在这种模式下升级平台版本会导致 Mod 解析失败，迫使 Mod
作者重新锁定到新版本。

解析通过后 loader 为每个 Mod 构建`ModClassLoader`实例，其父级为`mili-bootstrap-classloader`，仅加载清单中的依赖映射与 ModJAR 本身。 ClassLoader
缓冲由`ModClassLoaderManager`统一管理以支持开发模式热重载。 ModClassLoader 构造期间还会运行字节码验证器，检查是否存在被禁止的反射访问——如试图通过`setAccessible(true)`打开
MC 内部包私有成员。在开发模式下，字节码验证器的行为会相对宽松，仅把违规路径输出到日志而不直接拒绝加载。

随后进入入口点初始化阶段： loader 由清单`entrypoint`字段反射找到主类，调用`initialize`创建`ModContext`实例并委托给 Mod。`EntryPointHook`负责注入正确的运行时上下文，并在入口点方法抛出异常时调用 ModContext.close 回收已申请的资源与挂起任务。 EntryPointHook 的回调采用 try...finally 模式，确保即使
Mod 代码中出现未捕获异常，资源也能被正确回收，并向 EventBus 发布`MOD_ABNORMAL_EXIT`通知相关 Mod 做降级。

入口点回调完毕后 Mod 进入 REGISTERED 阶段。 loader 与 runtime 的边界是 loader 负责有序 BUILD， Runtime 负责运行时的 SCHEDULE。 Loader 在 bootstrap
阶段保持无限循环检测：它持续监听一个名为`phaseStuckWatchdog`的线程，当某个 Mod 在超过设定阈值仍未完成某阶段推进时，强制将对应 Mod 置为 FAILED，避免因某个 Mod 卡壳导致整个平台被阻塞。

Loader 还提供"recovery-mode"。当 LoaderConfig.recoveryMode=true 启动时， loader 尝试通过最近一份成功加载的快照恢复 Mod 加载状态，跳过 manifest
解析扫描阶段。这一模式在开发环境偶发的 loader 崩溃中能够拯救一次游戏会话；在正式模式下 LoaderConfig.recoveryMode 被强制关闭。

## 类加载与隔离

每个 Mod 都拥有独立的`ModClassLoader`实例，使得 Mod 可以在自己的 classpath 中包含不同的库版本，又不污染其他 Mod 与平台核心。 ModClassLoader 继承自
URLClassLoader，覆写了`loadClass`方法来控制类加载顺序。

父子方向隔离由`ModClassLoaderManager`实现，维护了一张 mod 依赖有向图。父 Mod 的 ClassLoader 自然对其子可见，但子 Mod 不能反过来引用父私有包——这种单向依赖规则在
Discovery 阶段就被`LibraryResolver`校验，不符合依赖图的组件将被拒绝加载并报告。在 CI 我们会通过 ArchUnit 再次校验运行时层级中未出现反向 ClassLoader 委托请求的情况。

ClassLoader 回收由`ModClassLoaderManager.dispose(modId)`执行。只要 Mod 处于`STOPPED`或`FAILED`状态且没有其他 Mod 引用它时， Manager
可以立即关闭其 ClassLoader 并清除所有引用。这不仅是资源回收，也明确要求保持一个可卸载 Mod 的目录：关闭 ClassLoader 后 JVM 的 PermGen 与 Metaspace 不再引用该 ClassLoader
对象，可以被 GC 回收。配合`ResourceLeakDetector`，在测试与运行阶段可以精准定位哪个 ClassLoader 依然在未停用的状态。

Mili 平台对待 ClassLoader 隔离的另一重要设计是"isolation degradation"（隔离降级）。当某个 Mod 主动声明`isolationLevel ="RELAXED"`时，平台会为该 Mod
使用一个共享的`RelaxedClassLoader`——多个 RELAXED 隔离级别的 Mod 共享同一个 ClassLoader，使得它们可以共享某些公共库以减少内存占用。默认情况下每个 Mod 都是`FULL`隔离级别。

## 状态机与 Scope 树`LifecycleManager`实现九状态状态机，驱动所有 Scope 的推进。 Scope 表示一个隔离单元，负责承载 Mods 与子 Scope。根 Scope 承载 Runtime 自身的服务， Mod 的 Scope
挂到根下。 Scope 之间没有双向通信的观测，只能通过父 Scope 注册的服务、 EventBus 的冒泡事件或显式的 Capability Token 传递来实现作用。

Scope 树的推进规则是：父 Scope 进入 STOPPING 时同步推进所有子 Scope 也进入 STOPPING。 Scope 同时也是 Scheduler 与 EventBus 的绑定：每个 Scope
可以维护独立的事件总线，使用 Option-Bubble-Up 与子 Scope 共享订阅；子 Scope 的事件默认不冒泡到父，除非监听器显式声明`bubbleToParent(true)`。 Scope 内 Scheduler
的优先级不会向父 Scope 传递； REALTIME 优先级的任务在子 Scope 只能加速子 Scope 的 own tasks 执行。 Scope 关闭后，其内所有优先级为 REALTIME 的任务也会被取消。

Scope 的实现基于`Resource`抽象：每个 Scope 是资源的拥有者。 Scope 关闭时，持有的资源按 LIFO 逆序释放，释放失败的异常会延迟到所有资源都尝试清理后生成一份汇总的`ResourceReleaseException`。 Scope 进入 STOPPED 后，任何尝试再次注册资源的行为都将被拒绝并返回`ScopeClosedException`。这种设计使得 Mod 在`initialize`方法内意外延迟将会被 Scope 自动检测并在 Scope 推进时捕获，减少卡住或资源泄漏的发生。 Scope 资源的注册还提供"onClose" 回调选项。

状态机的核心不变量是：状态转换必须是单向且前置状态已完成。 Mod 在 Runtime 内通过 SDK 类型调用
LifecycleManager 时，都会先经过能力校验：调度操作的权限需要`SCHEDULE_EVENT`，资源回收操作的权限需要`RESOURCE_RELEASE`，事件冒泡操作的权限需要`EVENT_BUBBLING`。

## 能力与权限分离

Runtime 严格分离"能力"与"权限"两个概念：能力声明做什么动作是被允许的，权限检查在运行时根据上下文判断是否实际放行。例如一个 Mod 可以声明能力`WORLD_EDIT_AND_RESAVE`，但当前 Scope
的上下文可能在只读维度上运行；此时该能力仍有效，但执行将被`PermissionManager`拒绝。

CapabilityToken 是不可伪造的值对象，由`CapabilityManager`在 Scope
树上行签发生成。 CapabilityToken 的值对象本身是密封的，仅允许 PlatformToken、 ClientToken、 ServerToken 三种实现。父 Scope
的能力可以向下传递，但子 Scope 无法反向向上请求更高级的能力。撤销能力会导致关联事件订阅被强制取消，挂起任务被中断，关联资源被强制回收，实现了 StructuredConcurrency 意义上的级联清理。

能力申请发生在 Mod 的初始化阶段：入口点方法返回一个`CapabilityRequest`对象， CapabilityManager 解析该申请，生成一个签名的 Token 实例。 Token 的所有权经过时间限制；如 Mod
想继续持有，必须通过 ServiceRegistry 主动 renew。 Token 过期后所有与之绑定的资源与任务自动回收。在开发模式下， Token 过期不会立即回收，而是先对 Mod
发出警告；正式模式下 TimeoutAction.HARD 会立即回收。`CapabilityRequest`还支持声明能力间的依赖关系（ CapabilityDependency），避免 Mod 重复申请。

权限实行默认拒绝（ deny-by-default）策略。未明确授权的访问一律拒绝。`PermissionManager`收集每次拒绝事件并产生`AuditLog`条目，为开发与部署阶段的诊断提供可见性。权限的代价评估通过`PermissionCost`类型实现——每个 Permission 对象都声明一个`cost()`方法（ CPU、内存、网络带宽三项加权求和）， PermissionManager 在执行`check`时会累加当前
Scope 内运行的所有 PermissionCost，如果超出`LoaderConfig.maxCostPerScope`阈值则拒绝所有剩余低优先级的请求——这种限制防止 Mod 恶意申请过多低优先级的权限来绕过
deny-by-default 机制。`Permissions`类族定义了平台已有的权限节点命名空间，涵盖文件 IO、网络、 UI、 Context 访问、其他 Mod 访问等方面的操作。 Permission 的检查采用 Fail-Fast 策略：如果当前 Scope
没有持有对应能力， check 立即抛出`PermissionDeniedException`。异常对象内不只包含失败的操作名与
ModID，还包含检查时所需的能力调用链。`Permission`的命名采用反向域名风格，以避免命名空间冲突。 PermissionManager 提供`denyAll(reason)`一键驱逐方法，由 LifecycleManager 在紧急情况下调用。

## 调度器与事件总线`Scheduler`提供两种形式的任务执行：`runLater`（一次性的延迟执行）和`repeatEvery`（周期执行）。每个任务都通过`TaskHandle`返回给调用方。优先级由`TaskPriority`枚举管理，分为`REALTIME`、`HIGH`、`NORMAL`、`LOW`、`BACKGROUND`五档。任务创建了到 Scope 树节点的绑定，当父 Scope 进入关闭序列时，所有关联任务将被自动取消并清理。

Scheduler 的内部实现基于 Java 21+ 引入的 VirtualThread 机制以单线程池模拟，但随着 Mod 数量的增长会自然分块到多个后台线程以提升 CPU
利用。 Mod 代码不应假设任务在单一线上运行，不应在回调中硬同步；对于需要将执行挪到 MC 场景线程的操作，应使用`MinecraftMainExecutor::execute()`。`TaskHandle`提供的`await(timeout)`方法会阻塞当前线程直到任务完成或超时。`TaskState`枚举驱动任务内部状态： CREATED、 SCHEDULED、 RUNNING、 COMPLETED、 CANCELLED、 FAILED，所有状态转换均为单向推进。`EventBus`采用订阅与分发模型。`EventListener`接口标记处理事件，`Subscription`对象由`subscribe`调用返回，提供取消注册的方法。每个 Scope 子树拥有独立的 EventBus
实例，事件从 Scope 节点自上而下传播；子 Scope 的事件默认不会冒泡到父 Scope，除非 Mod
显式调用`bubbleToParent`。优先级高的监听器先执行；同优先级按照注册顺序分发。订阅对象是 AutoCloseable 的。

订阅所有权与 Scope 绑定，在 Scope
关闭时所有相关订阅自动解除。 EventBus 内部维护订阅列表时使用 CopyOnWriteArrayList，使得分发过程中不阻塞新的订阅与取消操作。分发异常的处理策略是：监听器抛出的 RuntimeException
被捕获并以`EventDispatchException`形式向 EventBus 发出一级事件，分发链不会中断，后续监听器仍会收到此事件。

EventBus 还支持"直接发出"语义：调用`postAndForget`会在当前线程上下文中结合 Scope 栈一次性分发；`postDelayed`则将事件立刻放入 Scheduler 中未来指定 tick
后再触发；`postCancellable`返回一个未完成的 Future，调用方可以主动取消该事件的分发。

## Minecraft Bridge

Bridge 位于`mili-minecraft-integration`包中，表示
Minecraft 客户端事件流与平台子系统的翻译层。它包含`MinecraftBootstrap`、`MinecraftLifecycle`、`MinecraftEventBridge`、`TickBridge`、`EntityBridge`、`WorldBridge`、`MinecraftRegistryBridge`、`MinecraftMainExecutor`、`RuntimeEnvironment`（从 runtime 重导出）与`TickContract`。

最关键的设计是`ClientTickPoller`，它使用纯反射机制轮询`Minecraft.getInstance()`的状态，通过比较上一 tick 与当前
tick 的世界与玩家引用，翻译出`WorldEvent.ENTERED`、`WorldEvent.LEFT`、`PlayerEvent.JOINED`、`PlayerEvent.LEFT`等游戏内事件，写入平台 EventBus。此实现不涉及
Mixin、 ASM 或任何字节码改写技术；全部直接依赖反射。 ClientTickPoller 的轮询频率固定为每秒一次，硬编码判定 tick 状态是否发生变化以决定是否写入事件，避免对 MC 主线程产生可测量的开销。轮询检测方法通过在
bootstrap 阶段保存 MethodHandle 实例缓存到`MinecraftLifecycle`中，使得每次轮询只产生一次 lookup 成本。轮询检测逻辑还覆盖了以下状态变化：当前 MC`Screen`实例切换、当前开放容器改变、玩家生命值发生变化等。`MinecraftEventBridge`定义了 13
条事件记录，涵盖客户端 tick 起始与结束、玩家加入、世界加载、区块加载、实体注册、方块注册、物品注册、容器打开、屏幕切换、键盘输入、网络数据包收发与游戏关闭。这些记录类型均位于 abi 模块——说明平台把事件契约声明权归属 abi
层； minecraft 模块仅将内部信号翻译成这些标准化事件。事件记录定义为 Java record，使得 Immutable 与线程安全由语言本身附带实现。 EventBridge
内部使用名为"EventFilter"的机制：每个事件记录声明`eventFilter="STRICT|PERMISSIVE|NONE"`属性。`MinecraftRegistryBridge`提供访问 MC 命名注册表的能力，通过授权`REGISTRY_ACCESS`权限进行包装。注册表访问采用 MethodHandle 方式，每个 MC 方法都缓存到`Registry`调用器中，访问速度接近直接调用。注册表桥接还支持 ETag-based 缓存：当一个注册表查询完成后，返回一个包含 ETag 的查询结果，下次查询使用 If-None-Match 头验证，以此来大幅减少对 MC
内部注册表的重复遍历。

事件翻译流程严格按由下至上的方向进行： Minecraft 内部的调用栈经 EventBus 被翻译成平台标准化事件； MC 内部的 accessor 调用被包裹在 MethodHandle 与委托模式中以避免运行时强依赖。 MC 内部
MethodHandle 缓存是否随 MC 版本更新而刷新，也取决于`MinecraftLifecycle.updateMappings()`和`MinecraftRegistryBridge.refresh()`的调用。`TickBridge`定义了 MC 侧 TickEvent 与平台 TickContract
字段之间的位掩码映射，平台的 TickContract 包含五个字段（ phase、 tickId、 deltaTimestamp、 currentTime、 currentTickMillis），由 MC 侧对应的五次
MethodHandle 调用获取对应值后拼接成一个不可变记录。 MC tick 的频率固定为 20 TPS （每秒 20 次），高于此频率的需求应该使用 Scheduler 的`TaskPriority.REALTIME`来请求更频繁的执行，但此优先级仅保证在
MC 主线程空闲时才会运行；`MinecraftMainExecutor`的调度方法对容错提供了硬件级别的事务保护：如果在执行期间抛出异常，异常会包围在`ModExecutionException`内转发给 Mod，不会中断 MC
主线程；同时`TickBridge`会把异常事件桥接回平台 EventBus。`EntityBridge`与`WorldBridge`提供相同的抽象方法包装 MC 的 Entity 与 World 类，支持跨版本兼容性。这两个桥接方法选择 observable-mode 的实现模式：通过观察每一
tick 刷新实体列表，不影响原有基类的操作，同时也暴露了"当新增、移除实体发生时"这一内建事件。 EntityBridge 的观察器工作在 MC 主线程被提交的同步队列内； WorldBridge
在处理跨维度跳转时通过复制当前实体快照，确保安全遍历。

## 内部组件结构`RuntimeEnvironment`枚举区分客户端、服务器与专用服务器。当前实现严格限定仅`CLIENT`。枚举位于 runtime 包根，使得 loader 与 integration
模块可以在不同边界截断环境判断逻辑。枚举声明了`isClient()`、`isServer()`、`isDedicatedServer()`三个便捷方法。`ServiceRegistry`作为 runtime 层的内部服务目录，负责将`EventBus`、`Configuration`、`Registry`、`Network`等基础服务与 Scope 一一对应。根
Scope 提供全局服务，子 Scope 可以通过该目录查找继承链上游的已注册服务。 ServiceRegistry 不提供注册服务的能力，仅允许读取；服务的注册通过 LifecycleManager 在 Scope
初始化阶段统一处理。 ServiceRegistry 内部使用 ServiceLoader/ServiceFactory 模式作为扩展点，允许平台通过 ServiceRegistry 工厂注入 Mock 服务供测试使用。`Resource`抽象表示拥有生命周期的外部句柄——文件流、网络连接、 native 句柄、监听器。`ResourceManager`与 Scope 绑定， Scope 关闭时所有关联的`Resource`都必须调用`close`方法。`ResourceLeakDetector`在运行时捕获已注册的资源在 Scope 关闭后仍未释放的实例，记录`LeakReport`到诊断日志。 Resource 类族使用
ResourceFamily 抽象作为其家族树根，不同家族拥有各自独立的 close 策略与诊断指标。每个 Resource 在创建时被分配一个唯一的 long 资源 ID，资源 ID 提供并发安全的 CAS 机制，用于防止重复
close。`ModDiscovery`解析 manifest 的过程中同时构造`VersionBinding`所需的数据结构。错误处理流程中，任何一处校验失败都会立即把全局状态推入`FAILED`。解析成功的 Mod
进入轮次加载。`LibraryResolver`负责从互联网或缓存下载平台所需的传递依赖，仅在缺失于本地缓存时触发。 LibraryResolver 的平台库优先权高于 Mod 自带的同版本库。`LoaderConfig`作为单例启动配置持有者贯穿所有阶段，包含 Game Provider 类名、 Mod 扫描路径、开发模式开关、白名单规则等字段。命令行参数解析借助`picocli`库实现。 LoaderConfig 一旦实例化就不再可变，使得运行期无法动态切换配置，从而保证平台状态在启动后的一致性。 LoaderConfig 也提供了 Builder 模式，便于测试中构建场景配置。`DevLauncher`位于`org.loader.runtime.launcher`包中，提供了一个直接以命令行启动 MC 客户端旁路 loader 的入口。 DevLauncher 内建一个轻量级 Mod 扫描仪，模拟
loader 的 manifest 解析步骤，构建`MemoryModRepository`以支持热重载。 DevLauncher 仅用于开发期，其代码不得引用任何 MC 内部类型，所有 MC
相关操作都委托给`GameProvider`SPI 实现。 DevLauncher 在开发模式下还会自动打开诊断 HUD，展示实时 FPS、 Scope 数量、活跃 Mod 数、挂起任务数等关键指标。 DevLauncher 还可以提供`liveReload`、`forceGc`与`dumpClassloader`等快速命令。 DevLauncher 还内建了"web-inspector" 模式，启动一个嵌入式 HTTP
服务器，在指定端口监听，可以通过 HTTP 请求查询当前 Scope 状态、 ClassLoader 信息、活跃任务数，使得 IDE 插件可以集成 Diagnostic HUD 的数据到开发工作流中。`ReferenceMod`与`RuntimeDiagnostics`类族为 runtime 提供自观察入口。 ReferenceMod 是一个与 Runtime 同生共死的最小 SDKMod，调用了所有核心 SDK
函数来实现"Self-Dogfood"测试。 RuntimeDiagnostics 记录了每个 Scope 的创建时间、 ClassLoaderID、 Capability
Owner、资源数量、任务数量与最近一次心跳。 ReferenceMod 在 LoaderConfig.enableReferenceMod=true （默认 true）的情况下由 loader 注入到 mods 目录。`AuditLog`类定义了审计条目记录结构，包含 Mod ID、操作对象、时间戳、结果代码与上下文信息。这些信息文件以结构化 JSON
形式写入`logs/audit/`目录，同时以内存环形缓冲支持实时联调。日志格式虽然面向人类阅读，但不允许包含敏感数据（如 API Token）。 AuditLog 提供了
tail 阅读接口、实时订阅接口与批量导出接口，支持各类现代化的监控与告警系统集成。`ModLogger`是每个 Mod 实例的专属日志通道。它内部持有 Mod ID 前缀（自动添加）和可选的自定制归档路径。 ModLogger 不把 MC 的日志混流在一起；它与
MC 日志之间有一层适配桥，使得严重级别（ WARN、 ERROR）以上的日志同时复制到 MC 原生日志，以便 MC 级别的监控工具也能捕获 Mod 级别的错误。 ModLogger
的异步写入由内部的 SingleThreadExecutor 驱动，不占用 MC 主线程或 Scheduler 线程资源。 ModLogger 还提供`debugf`、`infof`、`warnf`、`errorf`四类平铺方法。`Configuration`类通过 ConfigProperty 抽象提供强类型的属性访问器， ConfigProperty 类型在执行时通过 Supplier 注入实际值源，使得 Mod 可以通过`config.get(ConfigProperty)`读取配置而不需要记住字符串键名。键名拼写错误在编译期即被 IDE 检错而非暴露于生产期。 ConfigProperty 的读写都可以附加变换器（ Mapper）与合法性
Validator，从源头确保配置值类型合法。`Network`类通过 NetworkEndpoint 抽象提供安全通信入口。 NetworkEndpoint 提供 HTTP 客户端封装、 WebSocket 客户端封装与 genericSocket 封装，所有通信都被
LifecycleManager 的生命周期管理框架所监管。平台的`PermissionManager`会拦截所有网络地址的出站数据包，使其无法在未经授权的情况下发送到被保护端口。`JvmCapabilities`类族提供了 JVM 层级的扩展能力： HotSpotDiagnostic、 MemoryInspection、 ApiResponse。这些能力都是可选的。`ClientCapabilities`类族则提供了游戏运行级别的能力：窗口句柄访问、 OpenGL 上下文拾取、游戏渲染暂停与重开、资源包加载覆盖等，这些能力涉及
MC 内部更敏感的访问边界，因此申请需要`CLIENT_CAPABILITY_ENDORSEMENT`高级权限。`ModManager`维护整个
ModRegistry 的运行时视图，提供`findById`、`findByScope`、`findAllWithCapability`、`findByDependencyOf`等查询方法。 ModManager
特别提供了一个`aliveMods()`枚举，仅返回当前状态为 RUNNING 的 Mod 列表，避免 Mod 与已关闭的 Mod 通信。`ModSet`与`ModSources`两个工具类分别提供了 Mod ID 集合运算与
Mod 来源注册，辅助 loader 与 runtime 的缓存管理。

## 开发与运维

构建与发布流程完全由 Gradle + Kotlin DSL 驱动。每个模块都声明了自己的`sourceCompatibility=25`与`targetCompatibility=25`，使用`--enable-preview`以启用 SealedInterface 语言特性。构建产物为 Gradle shadowJar 构造的 fat-JAR，其中 Loader Main 由`org.loader.loader.LoaderMain`组成， Premain-Class 声明在 manifest 列表中，便于未来扩充为支持 Java Agent 模式动态挂载。 Release 构建在
GitHubActions 的`release.yml`workflow 中触发，生成包含原文件的 fat-JAR 与包含所有传递依赖的独立 ZIP 包两种形式。

CI 流水线定义在`.github/workflows/ci.yml`与`.github/workflows/release.yml`中。校验流程跑三步：第一是编译与单测（ ArchUnit
包含在单测路径内），第二是`boot-test.ps1`真实启动 MC 客户端并跑一次 TestMod 端到端测试，第三是发布条件校验：没有 Tag push 不会触发 Release 构建。这些步骤配合保证每次 Tag
都针对真实启动链路通过与表现。 boot-test.ps1 真实启动 MC 客户端使用`test_client/`目录下的26.2.jar 与对应的 natives；脚本内部先启动 Platform bootstrap，再注入`testmod.jar`到 mods 目录，随后劫持`gameDir`指向`test_client/`并等待特定日志行（如
ReferenceMod 的初始化完成日志）以确认加载成功。 boot-test.ps1 还包含一套"异常注入"测试模块：通过人为注入 Mod 入口点异常、 Manifest 解析异常、 ClassLoader
类找不到异常，验证平台的错误处理路径是否按预期推进 Mod 到 FAILED 并输出正确的错误码。

性能与可观察性方面，`RuntimeDiagnostics`类为 Runtime 内部提供了一套诊断接口，包括 Scope 路径、 ClassLoader 数量、 Capabilities 注册数量、 Scheduler
任务队列深度、活跃资源数、当前 tick 负载与最近一次 GC 暂停的信息。 DiagnosticsHUD 在开发模式下自动渲染，展示实时数据于屏幕左上角，其渲染使用 Scrift API 直接操作 MC Framebuffer。

代码质量层次由 ArchUnit 系列规则保障。 ArchUnit 测试在 JUnit 5 下并行执行，失败会直接导致 CI 无法通过。我们还通过 ErrorProne 插件禁止一些不安全 API 的使用。 codestyle
方面遵循项目内部的格式化规范， gradle spotless 插件使用 Palantir Java Format 进行自动格式化。

错误监控与追踪方面，平台使用了三级分级系统：`MiliException`为所有平台异常的根类；其下按功能域派生子类；每个特定错误都与一个错误码（枚举）绑定，错误码的字符串表示形式为`MILI_<DOMAIN>_<CODE>`的命名风格。错误通过错误码进行日志分发、用户侧诊断与未来多语言翻译的基础。在开发与 CI 测试中，错误码的文档化与单元测试必须覆盖每一个新增的错误码，使得平台可以提供稳定的错误语义。平台还在生产环境中为错误监控
agent 提供了集成接口，当平台检测到某个 Mod 抛出异常频率超过阈值时，自动为该 Mod 申请临时降级权限，避免频繁的 Mod 崩溃拖垮整个游戏会话。

## 错误码与版本校验

`ModDiscovery` 在解析 `mod.json` 时执行严格校验，任何不符合条件的清单都将返回 `ValidationResult`，其中包含以下三种错误码。`MOD_PLATFORM_MISMATCH` 表示当前 RuntimeEnvironment（CLIENT/SERVER/DEDICATED_SERVER）与清单中 `runtimeEnvironment` 字段声明的不匹配——例如在 CLIENT 环境中加载 `runtimeEnvironment: "SERVER"` 的 Mod。`MOD_ABI_MISMATCH` 表示清单中 `abiVersion` 字段声明的版本号与当前平台 ABI 规范不兼容，通常是因为 Mod 使用了旧版 ABI 编译且 API 已被破坏性变更。`MOD_MINECRAFT_MATCH` 表示清单中 `minecraftVersion` 字段声明的 MC 版本与当前实际客户端版本（26.2）不一致，意味着 Mod 可能依赖了已被移除或更名的 Minecraft 内部成员。

三种错误均为加载阻断型错误，解析失败的 Mod 不会进入 LOADER 阶段。loader 将异常信息与控制台高亮错误路径并返回清单文件路径，便于修复。在开发模式下，`LoaderConfig.strictManifestValidation=false` 可以将部分阻断错误降级为警告，允许 Mod 加载流程继续进行，但 `<WARN>` 标签会附加在每条日志上，便于追踪潜在问题。

错误码遵循命名约定 `MILI_<DOMAIN>_<CODE>`，目前已定义的完整集合包括上述三种清单校验错误，以及运行时异常码如 `MILI_LIFECYCLE_ILLEGAL_TRANSITION`（非法状态转换）、`MILI_CAPABILITY_TOKEN_EXPIRED`（能力令牌过期）、`MILI_PERMISSION_DENY_DEFAULT`（权限默认拒绝）、`MILI_RESOURCE_LEAK_DETECTED`（资源泄漏检测）等。错误码的增删受 ab compatibility 约束——生成的错误码永不删除，仅可标记 `@Deprecated`，并保留至下一个主版本号发布后方可移除。

## ABI 演化与兼容保证

ABI_VERSION 是平台对外承诺的契约版本号。按照当前设计，CURRENT_VERSION 用于平台自身的迭代跟踪，反映功能演进的粒度；ABI_VERSION 仅在发生 ABI 破坏性变更时递增主版本号。平台遵循以下兼容保证：已发布的接口类型永不删除，所有公开方法的签名在同名 ABI 主版本内保持兼容，record 类型的字段只增不删。接口可以新增默认方法（default method），但不能新增抽象方法——这一约束确保现有 Mod 代码无需因接口演化而重写实现类。

类型系统的演化采用 `@Since("ABI_VERSION")` 注解标记新增的类型成员。@Since 注解可用作 IDE 的版本检查辅助，也可以被运行时校验器利用，使得 Mod 在加载时即可检测到"引用了比声明的 ABI 版本更新的 API"并拒绝加载，而不是在运行中因 `NoSuchMethodError` 暴露问题。

平台采用语义化版本规范 2.0.0，要求所有跨主版本升级提供 Changelog 与迁移指南。主版本号递增意味着至少一个已公开 ABI 接口被标记为 `@Deprecated(forRemoval = true)`，并承诺在下一个主版本号发布前仍保持可用——这一过渡期最短不得少于一个完整的功能迭代周期，以保证 Mod 开发者有充分时间完成迁移。

## 主题不变量与约束

Mili 的目标是整个平台投入生产使用，每一项设计决策都为长期可维护性服务。 runtime 包内的实现可以选择用 sealed interface
与 record 收紧类继承边界； Scheduler、 EventBus、 ResourceManager 均为测试替换注入，新主版本随时可以引入并行版本； Errorhandling 采用 sealedexception 类族使得代码中的
catch 分支在编译期被穷举；可观测性与安全的组件设有独立的包，可独立关闭。这些设计原则将在未来版本的演进中继续严格执行。

平台中同样有一条重要的设计约束是：未发布（即尚未在 abi 中正式声明的） API 不得在示例 Mod 或用户可见文档中暴露。 Mod 开发者只能通过 abi 层 API 扩展平台，所有未官方声明的内部类型都被视为不稳定实现。这条约束确保
Mod 开发者的投资与时间不被平台内部重构所破坏。

CI 强制以下不变量：

1.`mili-runtime`内不得出现`org.loader.runtime.minecraft`包的任何导入。
2. `mili-abi` 不得引用任何其他内部模块。
3. `mili-loader` 不直接访问任何 Minecraft 类包名。
4. `mili-minecraft-integration` 不暴露 Minecraft 原生类型至 abi 层。
5. Gradle 模块依赖由 ArchUnit 断言双重校验。
6. 禁止引入外部 Mixin、ASM、ByteBuddy、LaunchWrapper 等字节码改写框架，所有 MC 桥接代码必须走纯反射。
7. 测试脚手架禁止引入 Minecraft 类的 mock 模拟，真实 MC 客户端启动才能通过端到端测试。

这些不变量确保了项目在演进过程中维持清晰的模块拆分。测试同时保证了整个平台并不允许"软测试"或"跳过真实 MC 启动"宣布开发完成——所有最终历史必须由 boot-test.ps1 真实执行并通过一份真实 MC
客户端的完整启动链路来验证。

最终，一切设计目标是共同服务于让 Mod 开发者将关注点限制在`org.loader.api.*`包内即可完整表达扩展能力，无需关心类加载隔离、线程调度、资源回收或事件分拣的底噪。这条原则将继续主导架构决策与代码审核方向。