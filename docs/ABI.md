# Mili ABI Contract

`org.loader.api` 是 Mili Platform 的稳定契约面。所有 Mod 只编译 `mili-abi`，运行时由 Loader 注入具体实现。`mili-abi` 模块本身零运行时依赖，不引用任何 Minecraft 类、第三方库或 Mili 内部组件。一旦接口进入 1.0 版本，即视为 ABI 稳定——任何破坏性变更必须提升 `ABI_VERSION`，不接受 Patch 级别的隐式变更。`VersionInfo` 常量表是版本信息的唯一权威来源：`CURRENT_VERSION="0.1.0"` 代表 Loader 实现版本，`ABI_VERSION="1.0"` 代表 Mod 契约版本，`TARGET_JAVA="25"` 与 `TARGET_MINECRAFT="26.2"` 锁定平台基线。Loader 在启动时读取这些常量并执行版本三元组校验，任何不匹配都会导致加载流程中止，同时生成结构化的错误报告供用户参考。

## 主要接口描述

每个 Mod 加载后会收到一个 `ModContext` 实例，它是 Mod 与整个平台交互的主要 SDK 门面，提供日志、配置、资源访问与生命周期查询。ModContext 是 Mod 进入整个 Mili 运行时的唯一入口，任何试图绕过 ModContext 直接访问底层服务的行为都会导致平台级异常。`ModMetadata` 承载元数据（id、名称、版本、环境），声明式描述 Mod 自身信息，由 Loader 在加载期解析。ModMetadata 中的 id 字段全局唯一，重复的 id 会导致后续 Mod 被拒绝加载。所有 Mod 入口点类必须实现 `Mod` 标记接口，Loader 通过该接口识别加载入口并调用其生命周期回调。

`Environment` 枚举区分 `CLIENT` 与 `SERVER`，Mod 可据此在加载期裁剪逻辑，避免在错误的端上初始化不该初始化的子系统。`Logger` 提供结构化日志输出，Mod 不应直接使用 `System.out`——直接写入控制台会导致日志上下文缺失（时间戳、Mod ID 前缀、级别），在排查问题时极不友好。日志级别共有六级：`TRACE`、`DEBUG`、`INFO`、`WARN`、`ERROR`、`FATAL`，默认启用级别为 `INFO`，通过 Loader 的全局配置可以向下调整。每个日志事件都会携带时间戳、Mod ID 与日志级别，格式统一的输出使得在复杂多 Mod 环境中快速定位问题成为可能。

`Mili` 是静态门面类，提供 `apiVersion()`、`runtimeVersion()` 查询版本信息，支持通过 `mod(context)` 获取运行时上下文，以及 `registerProvider()` 注册自有服务。Mod 调用 `Mili.registerProvider()` 将自己暴露给其他 Mod 发现的接口实例。门面方法在 Loader 未加载时会抛出 `IllegalStateException`——这意味着它们不能在静态初始化块或类加载时调用，必须等待 Loader 完全启动后才能安全使用。Mili 门面实现为单例模式，Loader 在启动时会将自身实例注入其中，后续所有调用都路由到同一 Loader 实例。

`Lifecycle` 与 `LifecycleState` 定义了 Mod 的状态机：从加载、初始化、活跃到卸载，状态转换不可逆。当需要查询状态时，`Lifecycle.state()` 返回当前 `LifecycleState` 实例。状态转换通知通过 `LifecycleListener` 传递——Mod 可以注册监听器以响应自己或其他 Mod 的状态变化。监听器抛出的异常会捕获并记录，但不阻止状态转换的继续。状态机的不可逆性是一个核心约束：在 Mod 系统的语境下，"加载失败后重试"的状态机循环只会让问题被掩盖而非解决，因此 Loader 要求重试逻辑必须显式地经 `Lifecycle.unload()` 后再进入 `Lifecycle.load()`，即重新加载的语义是全新实例而非状态回溯。

`TaskHandle` 代表一个已提交的异步任务，`TaskPriority` 决定调度权重（`CRITICAL` > `HIGH` > `NORMAL` > `LOW` > `IDLE`），`TaskState` 反映执行状态（等待、运行、完成、取消）。`Handle.result()` 在任务完成后返回结果，未完成任务调用会阻塞。`Handle.cancel()` 发送取消信号但不保证立即终止——任务必须在安全点检查取消状态并自行退出。任务抛出异常会被捕获并封装在 `Handle.exception()` 中，不会传播到线程池。Handle 的阻塞等待有默认超时——由 Scope 配置中的 `defaultTimeoutMillis` 决定，超时后抛出 `TaskTimeoutException`，异常的 `TaskHandle` 引用可让调用方判断超时的具体任务。

`EventBus` 提供发布-订阅机制，`EventListener` 标记处理器，`Subscription` 句柄用于取消注册。事件类型通过泛型在编译时绑定，`EventBus.subscribe(Class<T>, EventListener<T>)` 返回 `Subscription<T>`。处理器抛出异常不会终止事件传播链，Loader 会记录异常后继续调用下一个处理器。如果一个事件类型上注册了超过十个处理器且其中连续三个在同一事件上抛错，Loader 会触发频率熔断并临时跳过后续传播。熔断机制是防止"一个坏处理器拖垮整个事件链路"的防御策略，同时也会在日志中输出详细的异常堆栈，帮助 Mod 作者定位问题。

`CapabilityToken` 是能力声明与消费的基础凭证，Capability 系统的基础操作单元。Token 实例必须在 ABI 模块中声明为 `public static final`，确保提供者与消费者引用的是同一对象。注册时提供 Token 与实现接口引用，Loader 在加载期校验 Token 与接口的兼容性。未消费的 Token 不会被拒绝——它们表示"声明但不强制要求"的能力，消费者可以选择是否依赖。Token 的泛型参数 `<T>` 不仅标记了能力的语义类型，也是 Loader 在匹配时的类型校验依据——一个声明为 `CapabilityToken<NetworkProvider>` 的消费者不能接受 `CapabilityToken<RenderProvider>` 的供应。

`Resource` 与 `ResourceManager` 管理 Mod 的资产访问，包括配置文件、语言文件、数据资源与原生库。`Resource.get()` 返回不可变的内容快照——文件更新后必须重新调用 `get()` 获取最新内容。`ResourceWatcher` 在开发模式下提供变更通知，生产模式下禁用。资源查找路径为：Mod 自身 JAR 优先，平台补丁次之，Minecraft 原生最后。资源管理器还有一个有趣的特性：同一资源路径可以被多个 Mod"预处理"，Loader 会按照 Mod 依赖图的拓扑顺序依次调用各 Mod 的预处理拦截器，最终的资源内容是所有拦截器依次作用后的结果。

`Permission` 与 `PermissionManager` 构成运行时的权限校验层，拒绝未声明的操作。权限声明为字符串常量，遵循 `域:动作` 格式（如 `"network:http_get"` 或 `"filesystem:write_config"`）。管理员可以在全局配置文件中通过 `allow` 或 `deny` 规则覆盖默认行为。权限检查发生在 API 入口，Mod 无法绕过。权限的审计日志会记录每次拒绝与通过事件，可通过全局配置的审计级别调整详细程度。审计日志默认关闭，开启后会带来可感知的 I/O 开销——在生产环境中建议仅开启 `DENY` 级别的审计，关闭 `ALLOW` 级别的审计。

`MiliException` 是所有异常的基类。`ModLoadException` 表示加载期问题，包含缺失的类或依赖信息；`CapabilityException` 表示能力匹配冲突，包含声明与期望能力的差异；`ApiException` 表示运行时 API 调用中的错误，通常是参数不合法或状态机转换非法。所有子类均提供结构化上下文（键值对映射），便于序列化到日志与启动报告。异常的 `reason` 字段统一使用英文，以方便跨语言开发者查阅——但 `message` 字段支持本地化，当前支持简体中文与英文两种版本。

## 破坏性变更策略

ABI 版本号仅在以下情形递增主版本号：移除已有的公开接口或方法，修改方法签名（返回值类型、参数类型、参数数量或参数顺序），改变方法语义导致已有调用方无法正常工作，改变类的继承关系或接口实现约定，移除或重命名 `CapabilityToken` 常量，或修改 `VersionInfo` 中任何常量的值。

以下变更不视为破坏性变更，仅增加次版本号即可：新增接口或新方法（保持原有方法不变），新增默认方法（default method），性能优化不影响可观察行为，`@Deprecated` 注解标记为保留兼容而存在的过渡期方法，新增 `CapabilityToken` 常量，泛型参数边界放宽，新增可选参数（仅限于在最后一个参数位置新增、且默认值为 `null` 的情况）。

所有破坏性变更必须在 [MOD_DEVELOPMENT.md](MOD_DEVELOPMENT.md) 的迁移指南章节中明确记录，包含旧代码片段、新代码片段与迁移原因说明。每个废弃项在真正移除之前必须经历至少一个次版本号的过渡期——在此期间方法仍可用但标记为 `@Deprecated`，并附带迁移建议的 Javadoc。废弃公告会在 Mili Platform 的官方渠道同步发布，确保 Mod 开发者有充足时间适配。破坏性变更的发布必须在新版本发布前至少一个月提前公告，给依赖方流出充分的异步迁移窗口。

## 实现约束

`mili-abi` 模块不得引入任何 compile-scope 依赖。所有公开类型必须是接口或 `final` 值类型（`record` 或枚举），保持序列化友好，不允许循环引用。注解使用 `RetentionPolicy.RUNTIME`，确保 Loader 能通过反射在运行时访问并解析。公开接口中的文档注释是契约的一部分——如果 Javadoc 声明"该方法不会返回 `null`"，则实现必须遵守，调用方可以依赖这个保证而不需要额外的空值检查。

`VersionInfo` 的常量值在编译期被压缩为字面量，禁止反射动态访问。Mod 中的版本检查应当使用常量比较而非语义化版本工具类，以避免引入不必要的版本解析逻辑。Loader 在加载 Mod 时只检查编译时 embeds 的版本常量，不读取运行时动态生成的版本字符串——这意味着 Mod 不能通过反射替换 `VersionInfo` 中的值来绕过版本校验。Loader 自身的版本校验完全基于 `VersionInfo` 的字面量，保证了校验的不可绕过性。

Loader 实现类不得直接被 Mod 引用——所有实现类均位于 `org.loader.internal` 子包中。对 `org.loader.internal` 包中任何类型的访问在编译期不会报错（Java 没有真正的包私有约束），但在运行时会导致 `IllegalAccessError`。如果发现某个内部类被 Mod ABI 代码引用，应视为框架的 Bug 并尽快报告。`org.loader.internal` 包中的任何类型都被视为不稳定实现细节，次版本变更中可以任意修改甚至移除。

## 扩展点

Mod 可以通过 `Mili.registerProvider()` 注册自己的服务实例。这些服务通过能力系统（Capability）被其他 Mod 发现。注册时需同时提供 `CapabilityToken` 与服务实现的 ABI 接口引用——Loader 在注册层面就校验接口与 token 的一致性，不匹配则抛出 `CapabilityException`。一个 Token 可以注册多个实现，消费者获取时可以通过附加标签（`qualifier`）区分不同变体。注册的实现以懒加载的方式实例化——首次被消费者访问时才触发构造，避免了启动时的不必要的初始化开销。

如果需要引入新的扩展点（即新的接口），应先在 `mili-abi` 中声明，经过至少一个版本号的"试验期"标记（`@ExperimentalApi`），待接口形式稳定后再移除 `@ExperimentalApi` 标记并正式进入稳定契约。试验期接口的变更不触发版本号递增——它们本就不在稳定契约的范围内，Mod 使用试验期接口时需要自行承担未来不兼容变更的风险。每个试验期接口的 Javadoc 都包含一个 `@since` 标签，表示该接口进入试验期的版本号，以及一个 `@maturity` 标签，指示当前稳定性等级（在 `UNSTABLE`、`TESTING`、`STABLE_CANDIDATE` 三者之一）。

## 规范遵循

ABI 设计遵循以下原则：最小化接口数量（每个概念一个接口）、最大化组合兼容性（接口可以组合但不应强制继承层次）、最小化平台层耦合（ABI 不引用任何非 API 模块的类型）、最大化 ABI 稳定性（变更频率已被显式约束）。这些原则在评审任何 ABI 新增提案时作为检查清单使用——如果某个提案与其中一条或多条原则冲突，提案作者需要显式论证冲突的理由。
