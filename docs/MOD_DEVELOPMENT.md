# Mili 平台模组开发指南

Mili 是一个从零构建的 Minecraft 客户端模组加载器，其定位接近 Fabric 的使用体验，
但底层实现、运行时架构与模组 API 均与 Fabric 无任何代码层面的复用或兼容关系。
Mili 模组运行在自研的 Runtime 基础设施之上，使用专为该设施设计的原生 API，不依
赖 Fabric 不依赖 Mixin 不依赖 LaunchWrapper。这意味着模组代码无法直接调用任何
Fabric 框架类或 Mixin 注入机制，所有与游戏交互的能力均通过 Runtime 的 Scope、
Lifecycle、Capability Scheduler 与 EventBus 体系暴露。Mili 的设计哲学是将模组
的运行时边界、生命周期边界与资源边界统一在同一套 Scope 模型下，通过强隔离实现模
组之间的相互独立，通过能力注册机制实现模组之间受控的交互通道。Loader 在启动过程
中以顺序的单向推进方式将每个模组从发现状态逐步引导至运行状态，任何阶段出现异常
都会导致模组进入失败状态并隔离其影响范围，避免单个模组的错误在 Runtime 中扩散。

从较高的抽象层级来看，Mili 的三层架构分别为 Loader 层、Runtime 层与模组层。Loader
层是最先启动的组件，它负责读取 `mods/` 目录中的模组档案、构建依赖图、创建 ClassLoader
并将模组字节码按生命周期状态推进。Runtime 层构建在 Loader 之上，Loader 在完成自身
初始化后会通过依赖注入的方式将 Runtime 根 Scope 与基础服务注册到全局上下文中，为
后续模组提供 Scope 树管理能力、任务调度引擎、事件总线基础设施以及 Capability 注册
中心。模组层则是最上层的应用，通过 ModContext 接口装配 Runtime 提供的服务，并使
用 Scope 明确声明自身的资源边界。三层架构的边界十分清晰，Loader 不知道模组内部如
何组织代码与事件，Runtime 不知道模组之间的具体依赖实现细节，模组则不必理解 Loader
内部如何通过字节码变换支持新的游戏版本。这种分层策略使得 Runtime 在游戏版本更新
时需要调整的只是类名与方法签名的映射，上层模组因隔离保护而不受影响，正是这种解耦
使 Mili 具备了高效覆盖多版本 Minecraft 客户端的能力。

## 平台环境要求

Mili 平台要求模组开发者使用 Java 25 进行编译与运行。这是硬性要求，因为 Runtime
内部使用了 Java 25 引入的多项语言特性与 JVM 能力作为基础构建块，包括外部函数与内
存 API、结构化并发、以及新的类文件验证机制。Java 25 提供的这些能力直接影响了
Runtime 的内存模型设计与任务调度架构，因此任何针对低版本 Java 编译的类文件在运
行时都无法通过 Loader 的字节码版本校验。开发者在本地开发环境中必须安装 Java 25 以
上的 JDK，并确保 Gradle 的 `sourceCompatibility` 与 `targetCompatibility` 均设置
为 Java 25。当构建产物被其他使用低版本 Java 的开发者尝试加载时，Loader 会在发现阶
段直接记录版本不兼容错误并跳过该模组。开发者可以通过 JDK 发行版的工具链机制配置多
版本 JDK 并存，让全局环境保留旧版 JDK 而 Mili 构建交叉引用 Java 25 工具链。

Gradle 项目采用多子项目结构，这意味着 Mili 平台本身的源码组织为一个包含多个子项目
的 Gradle 根项目，每个子项目对应一个特定的关注点。主要子项目包括：`loader` 子项
目是加载器核心，负责完成 `mods/` 目录扫描、JAR 文件解析、元数据校验、依赖图构建、
字节码版本验证、ClassLoader 隔离创建与生命周期调度，它是最先被启动的子项目并通过
引导协议将控制权交付给 Runtime。`runtime` 子项目承载 Runtime 的核心资产，包括 Scope
树模型、Capability 注册框架、EventBus 实现、Scheduler 调度引擎以及 Lifecycle 状
态机，这些组件构成模组运行时的公共基础，模组本身不应复现或替换任何 Runtime 组件。
`api` 子项目是模组开发引用的唯一公开 API 层，提供 ModContext 接口定义、事件类定义、
Capability 标记接口以及相关注解处理器，模组工程在任何情况下都应将此项作为仅编译作
用域的依赖。`classes` 子项目维护 Minecraft 客户端反编译与映射模型的 Java 类声明文
件，提供强类型的游戏类引用而无需反射调用。`platform-shared` 子项目包含客户端与服
务端共享的平台抽象层，如文件系统接口、网络通道抽象和配置持久化机制。`platform-client`
子项目包含客户端专属的实现细节，包括渲染钩子、输入事件通道与界面集成逻辑。模组工
程只需声明对 `api` 子项目的编译期依赖，Runtime 与 Loader 由平台在启动时通过注入
ClassLoader 的方式提供给模组，不属于模组构建产物的一部分。

Gradle 的依赖管理遵循 Gradle 标准的 `api` 与 `implementation` 区分原则。当模组对
Mili 的 API 声明依赖时，应使用平台 Gradle 插件提供的 `miliApi` 配置，这一配置在编
译期对模组可见但在运行期由 Loader 通过 ClassLoader 注入。如果模组自身的公开 API 需
要引用 Mili 的类型，那么应当使用 `miliApi` 配置传递依赖；如果模组内部实现仅在编译
期引用 Mili 类型但不对外暴露，则使用 `miliImplementation` 配置避免泄漏至模组的使
用方。平台 Gradle 插件会在构建时自动插入字节码验证与元数据生成任务，确保模组 JAR
中的 `mod.json` 与 Gradle 项目声明的身份一致。当模组工程中需要引入除 Mili 以外的
第三方 Java 库时，应使用 Gradle 标准的 `implementation` 配置将其打入模组 JAR，并
确保该库的许可证与模组整体许可证兼容；使用 `shade` 或 `relocate` 功能可以重命名第三
方库的包名以避免因不同模组引入了不同版本的同一类库而导致的类冲突问题，对于大规模模
组而言这种防御性重打包几乎是必须的。

## 模组项目结构

每个 Mili 模组是一个标准的 Java Gradle 项目，输出为单个 JAR 文件，放入 Minecraft
客户端目录下的 `mods/` 文件夹即可被 Loader 发现。项目遵循标准的 Maven 目录结构，源
代码位于 `src/main/java`，资源文件位于 `src/main/resources`。模组 JAR 中必须包含编
译后的类文件与一个位于 `META-INF/mod.json` 的元数据描述文件，其他资源文件（如配置
默认值、语言文件、纹理、数据生成素材等）均可按需放入 JAR 内任何路径，通过 `ModContext.resources()`
提供的路径接口在运行时访问。开发过程中，建议为模组工程维护独立的 Gradle 项目目录，
不与其他模组共享同一个 Gradle 根项目，以便每个模组拥有独立的版本控制与独立的发布周
期。在多模组协同开发的场景中，可以使用 Gradle 的复合构建功能将多个模组项目联合编译
而不必合并代码仓库。

模组的 `src/main/java` 目录下应包含完整的 Java 包结构，推荐以模组的 id 作为根包名，
例如 id 为 `mymod` 的模组使用 `com.example.mymod` 作为根包。所有的公开 API 类、事件
处理器类与配置类都应放置在根包之下并按职责划分子包，例如 `event` 子包用于事件监听器、
`config` 子包用于配置定义、`capability` 子包用于能力实现。这种划分约定有助于维护者快
速定位各类功能，也便于开发工具进行静态分析与文档生成。如果需要为模组编写单元测试，
测试代码应放在 `src/test/java` 目录并使用与主代码相同的包结构，测试运行时需要特殊
处理，因为 Mili Runtime 组件无法直接在单元测试环境中被实例化，建议使用 Stub 或 Mock
版本对 ModContext 进行隔离测试。在实际运作中，平台 Gradle 插件会在测试阶段注入一组
Stub 版的 Runtime 组件，允许模组作者在脱离完整 Minecraft 环境的情况下验证事件处
理逻辑与配置解析逻辑的正确性。这些 Stub 实现提供了与真实 Runtime 组件相同的接口，
但其行为是简化的纯内存实现。

资源文件的放置存在一些约定需要遵循。`mod.json` 必须位于 `src/main/resources/META-INF/mod.json`，
打包时会自动保留该路径。其他资源如默认配置文件应放置在 `src/main/resources/assets/{modId}/config/`
路径下，通过 `ModContext.resources().open("assets/{modId}/config/default.json")` 访
问。语言文件与本地化资源遵循同样的路径规范使用 `assets/{modId}/lang/` 作为基础路径。
纹理与模型文件依据客户端资源管道的要求放置，但不要求与模组 JAR 绑定，开发者也可以选
择在运行时从远程资源服务器下载素材包并于内存中使用。对于使用 Loader 内置国际化框架
的模组，字符串资产文件应命名为 `{language_tag}.json` 并放在语言目录之下，Loader 进
入游戏时依据用户语言设置自动加载相应资产。

## 模组元数据（mod.json）

每个模组必须在 JAR 的 `META-INF/mod.json` 中声明自身的元数据。这是 Loader 在发现阶
段读取的第一份信息，任何字段缺失或格式错误都会导致模组进入 FAILED 状态而不会被加载。
该 JSON 文件的结构如下所示，包含模组标识、版本、入口点、兼容性声明与依赖列表。该文
件必须使用 UTF-8 编码，JSON 格式规范严格遵循 RFC 8259，不允许尾随逗号、注释或控制字
符。Loader 在读取该文件后会对字段类型进行严格校验，任何类型不匹配都会触发模组失败并
记录详细的错误信息。

```json
{
  "id": "mymod",
  "name": "My Mod",
  "version": "1.0.0",
  "entrypoint": "com.example.MyMod",
  "mili": { "platform": "0.1.0", "abi": "1.0", "minecraft": "26.2" },
  "dependencies": [{ "modId": "other-mod", "required": true }]
}
```

`id` 字段是模组的唯一标识符，在全部已安装模组中不可重复，命名规则与 Maven 的 artifactId
一致，建议使用全小写短横线命名。Loader 对 `id` 实施严格的字符白名单校验，只允许小写字
母、数字与连字符，不允许大写字母或下划线，这样做是为了避免跨平台文件系统的路径兼容性
差异。`name` 字段是显示名称，用于日志输出与加载器管理界面，支持 Unicode 字符，可以
使用中文或其他语言以增强可读性，但该字段不参与任何鉴别或匹配逻辑。`version` 字段遵循
语义化版本规则，用于满足依赖声明的版本匹配逻辑，Loader 支持常见的版本范围声明方式如
`^1.0.0`、`~1.2.0` 与 `>=1.0.0 <2.0.0`，精确匹配时使用等号前缀。在开发阶段使用
`0.0.0-SNAPSHOT` 或时间戳后缀的版本号可以便于 Loader 版本判断逻辑将其识别为开发构
建，并在严格模式下放宽部分兼容性检查以加快迭代。`entrypoint` 全限定类名指向模组主类，
该类必须包含一个公开的、返回值为 `void` 且接受单个 `ModContext` 类型参数的 `initialize`
方法。Loader 在 RESOLVED 阶段就会验证该类名的存在性，在 LOADED 阶段验证类加载、构造
函数可访问性与方法签名匹配。

`mili.platform` 字段声明模组编译所针对的 Mili 平台版本，当 Loader 升级导致平台版本后
退时该模组会被标记为不兼容而跳过加载。建议模组作者将此项约束设置为一个合理的前向兼容
范围，例如声明为 `^0.1.0` 而非精确锁定到补丁版本，从而在平台做非破坏性更新时自动复用
已发布的模组包而不必重新上传。`mili.abi` 字段声明模组 ABI 兼容性版本，用于 API 破坏性
变更时的隔离，Loader 会将_abi 版本不匹配的模组视为潜在不兼容并发出警告但不强制阻断（
除非平台处于严格模式）。严格模式由 Loader 启动参数控制，开启后会拒绝所有 ABI 不兼容的
模组加载，推荐在生产环境中默认开启以确保模组组的整体稳定性。`mili.minecraft` 字段声明
游戏主版本号，当前官方的要求为 `26.2`。`dependencies` 数组中的每一项通过 `modId` 引用
其他模组，`required` 字段为 `true` 时若依赖不存在则本机加载失败，为 `false` 时仅当依赖
存在才会初始化本模组，这种软依赖的设计允许模组在不具备某些可选联动能力的情况下仍然正
常加载并提供基础功能。除 `modId` 与 `required` 外，依赖对象还可选包含 `versionRange` 字
段以限定可接受的依赖版本范围。Loader 的依赖解析器会综合 `required`、`versionRange` 与
依赖模组的实际版本来判断是否满足安装条件，当多个模组同时要求不同版本范围的同一依赖时
Loader 将记录冲突诊断信息并拒绝整个模组组的加载。对于可选的视觉效果类依赖建议使用
`required: false` 并提供优雅的功能降级路径；对于提供基础 API 的依赖建议使用 `required: true`
并指明版本范围，因为这种强依赖通常意味着模组在代码层直接调用了依赖模组的接口。

除了上述核心字段之外，`mod.json` 还允许开发者添加自定义扩展字段，Loader 会忽略未知字
段但将其保留在 `manifest()` 返回的元数据对象中，方便模组在运行时读取自身的手写声明。
例如开发者可以在 `mod.json` 中添加 `"authors": ["Name"]` 或 `"description": "..."` 这
类用于分发列表的元信息，某些工具链或发布扩展也会从 `mod.json` 的自定义字段中提取相应内
容。但开发者不应在扩展字段中放置供 Loader 解析的关键指令，只有上述标准字段才会被 Loader
读取并影响加载流程。Loader 会在 JSON 解析树中保留这些扩展字段的完整结构，包括嵌套对象
与数组，模组作者可以通过 `manifest().getAsJsonObject("键路径")` 的方式在运行时提取配置值。

## 入口点类与初始化

入口点类在 `META-INF/mod.json` 的 `entrypoint` 字段中指定，Loader 在 LOADED 阶段通过
反射实例化该类。类必须提供一个公开的无参构造函数供 ClassLoader 调用，并实现 `void initialize(ModContext ctx)`
方法作为模组的主入口。Loader 调用 `initialize` 时，模组已处于 REGISTERED 阶段，Capability
注册、EventBus 订阅、Scope 任务提交等行为均应在此阶段完成。`initialize` 方法应避免执行
长时间阻塞操作，包括但不限于同步的网络请求、大量磁盘 IO 以及复杂的数据结构初始化，所
有耗时的操作都应通过 `ModContext.submit()` 提交至 Scope 的任务队列异步执行，以便 Loader
能够在调度层统一分配线程资源与优先级。

模组不应持有对 Loader 内部类的引用，所有对平台能力的访问必须通过 `ModContext` 接口进行。
这一约束保证了模组与 Loader 实现之间的解耦，当 Loader 内部架构在版本间发生调整时模组无
需重新编译。为了确保这种开发规范得到执行，Loader 的 ClassLoader 实施了字节码级别的引用
检查，若模组加载了不属于 `api` 包的类，Loader 会记录告警并在严格模式下阻止模组加载。入
口点类的静态初始化块应仅包含常量赋值与不可变集合的构建，避免在静态初始化中执行查询外部
状态或注册全局钩子的操作，因为此时模组仍处于 LOADING 阶段，完整的 Runtime 服务可能尚未
就绪。如果模组需要访问其他模组的公开 API 或查询配置文件的初始状态，应将这些查询推迟至
`initialize` 阶段执行，此阶段 Loader 已确保依赖模组完成了自身的基础初始化。

模组的初始化顺序由 Loader 根据依赖图确定：Loader 按拓扑排序顺序依次将每个模组推进至
INITIALIZED 阶段，即所有必须先加载的模组都完成初始化后才会轮到当前模组。这种顺序保证了
当模组在 `initialize` 中调用 `getCapability()` 查询其他模组公开的服务时，被查询的模组
必然已经完成了 `initialize` 并注册了该服务。但模组在初始化阶段对自身依赖以外的模组进行
能力查询是危险行为，因为那些模组的加载顺序在当前模组的控制范围之外。因此模组应只查询
其在 `dependencies` 中显式声明的那些模组提供的服务。

`initialize` 方法执行结束时，模组向 Loader 注册的所有能力实现、事件监听器与配置定义应
当已经全部完成，方法退出后 Loader 进入 RUNNING 阶段并向模组分发首批事件。模组不应在 `initialize`
方法中启动独立的无限循环线程，所有持续性任务都应由 `ModContext.submit()` 提交到 Scope
的 Scheduler。当模组的 `initialize` 方法执行过程中出现异常，Loader 会捕获异常并将模组标
记为 FAILED，然后在模组日志中记录异常堆栈，模组进入 FAILED 状态后无法自行恢复。模组作者
在实现 `initialize` 方法时建议采用防御性编程策略，将所有关键注册逻辑放入 try-catch 块中
并针对每个依赖项单独隔离，避免一个环节的失败导致整个初始化链脱落。

## ModContext 核心 API

`ModContext` 是模组与 Mili Runtime 交互的核心接口，通过方法参数注入到入口点类的 `initialize`
方法中，是模组生命周期内所有平台能力调用的统一入口。该接口提供的方法遵循维度化组织，
分为基础信息、状态与能力、任务调度、事件、配置资源与环境感知六大类别。

在基础信息维度，`modId()` 返回当前模组的唯一标识字符串，与 `mod.json` 中的 `id` 字段
一致。`manifest()` 返回一个加载时解析的不可变元数据对象，包含 `mod.json` 中声明的全部字
段。`classLoader()` 返回加载本模组类的 ClassLoader 实例，ClassLoader 实施了严格的可见
性边界，模组自身 JAR 内的类、依赖模组声明公开的 API 类以及 Java 标准库对该 ClassLoader
可见，而 Loader 内部类与其他模组内部类不可见。`logger()` 返回一个绑定当前模组标识的专用
日志实例，所有模组输出日志均应使用该项而非 `System.out` 或全局日志管理器，以便 Loader
在日志前缀中区分来源模块。`logger()` 实例提供了从 `trace` 到 `error` 的完整日志级别，建
议开发与调试期使用 `debug` 与 `trace` 输出详细运行信息，生产环境下仅使用 `info` 以上级别
以减少输出噪声。

在状态与能力维度，`scope()` 返回当前模组所绑定的运行时作用域对象，生命周期钩子、任务提
交与事件均以此 Scope 为边界，但模组仅在特殊运维场景下才应主动调用停止等状态转换方法。
`isActive()` 返回当前模组是否处于活动状态，活动状态定义为非 STOPPING、STOPPED 或 FAILED，
任何异步回调中应在执行逻辑前先检查该项以避免在已失效的模组上下文中操作 Runtime 资源。
`getCapability(Class<T>)` 用于按类型查询已注册的运行时能力实例，Loader 会在当前 Scope
及其所有祖先 Scope 中递归查找匹配的注册项，若该能力未实现或未注册则返回空 Optional。
`getAllCapabilities()` 返回当前 Scope 链上可见的全部能力集合。`isLargeMod()` 返回一个布
尔值，表明本模组是否被标记为大型模组，大型模组可能在 Loader 资源调度策略上获得特殊对待。

在任务调度维度，`submit(Runnable)` 向当前模组 Scope 的任务队列提交一个同步任务，执行时
与模组生命周期绑定，Scope 进入 STOPPING 后未开始执行的任务将被丢弃并记录警告日志。重载
方法 `submit(Runnable, TaskPriority)` 允许指任务优先级，`TaskPriority` 枚举从高到低包括
HIGH、NORMAL、LOW 与 IDLE，优先级高的任务在同一执行批次中优先调度，但 Loader 不保证
跨模组的优先级顺序。`submit` 返回的 `Future<?>` 可用于取消或等待任务完成，但模组不应阻
塞等待任务结果。Scope 任务调度器的执行时机由 Loader 的主循环驱动，高优先级任务在当 tick
结束时立即执行，普通任务分散到后续空闲 tick 执行，IDLE 任务仅在系统空闲时执行一次。对于
需要精确时序的任务，可以包装为高优先级提交但不保证与特定 tick 同步，实际延迟在一至两帧
之间。

在事件维度，`events()` 返回当前模组 Scope 绑定的 EventBus 实例，该 EventBus 在模组初始化
时自动订阅，在 Scope 停止时自动清理所有已注册监听器，模组无需手动注销。通过该 EventBus
可监听或发布平台事件与自定义事件，事件分发在同一 Scope 内为同步跨 Scope 为异步。跨 Scope
异步分发时 Loader 内部采用了无锁队列与批量合并策略以降低同步代价。模组的 EventBus 监听器
签名必须是公开的静态方法或公开的无状态实例方法，接受单一事件类型参数并返回 `void`，如
果事件处理中抛出非受检异常 Loader 会将其封装并传递至模组日志。Loader 还提供了同步事件与
异步事件两种注册模式。

在配置与资源维度，`createConfig(String)` 在模组配置路径下以指定的配置名称创建并返回一个
配置对象，同一配置名称第二次调用会返回同一实例。`getConfig(String)` 读取已存在的配置实例，
若配置从未被创建则返回空 Optional。这两者均通过模组 Scope 的配置命名空间隔离，不同模组
的配置即使同名也不会冲突。配置的持久化触发时机是模组 Scope 进入 STOPPING 时，模组在运
行期对配置视图的修改会实时反映在内存对象中但不会立即写入磁盘。模组调用了 `saveConfig(String)`
后可以将当前配置强制持久化至磁盘。每次配置改动时会触发 `ConfigChangedEvent`，模组可以监
听该事件以在配置生效时重新加载相关模块。`resources()` 提供一个路径导向的资源访问接口，
返回 `Optional<InputStream>`，该接口实施了安全边界，禁止越权访问其他模组 JAR 或 Loader
资源，安全违规将记录至日志并返回空结果。`resources.list()` 方法可以列出模组 JAR 内指定
路径下的所有条目名称。

在环境感知维度，`environment()` 返回当前游戏运行环境枚举值，可能的取值为 `CLIENT`、`SERVER`
与 `DEDICATED_SERVER`，该枚举由 Loader 在启动阶段确定并在模组生命周期中保持不变。辅助
方法 `isClient()` 与 `isServer()` 是快捷封装。需要注意区分 `SERVER`（集成服务器）与
`DEDICATED_SERVER`（独立服务端）的差异，某些仅在独立服务端上存在的系统资源不应在集成
服务器环境中访问。当模组提供的功能同时需要客户端与服务端时，建议将共用逻辑与分离的环
境特定逻辑分别放置于不同的子包中，使用 `environment()` 分支选择需要注册的监听器类型。

`registry()` 返回一个类型化注册中心实例，用于在模组初始化阶段注册公开的服务能力或配置
架构。模组调用 `registry().register(Class<T> type, T implementation)` 注册一个能力实现，
其他模组可通过 `ModContext.getCapability(Class<T>)` 发现并使用该实现。注册中心在模组 Scope
进入 STOPPING 时会自动将所有已注册能力标记为不可用。注册中心使用强类型键约束，即注册
时使用的 Class 对象与查询时使用的 Class 对象必须完全匹配，不支持接口继承或多态查询。
若模组需要在同一能力类型下提供多个实现，应使用包装类或使用命名关键字的注册变体形式加
以区分。

## 模组九阶段生命周期

Mili Loader 为每个模组定义了严格顺序的九阶段生命周期，阶段名称与状态枚举值如下：DISCOVERED、
RESOLVED、LOADED、INITIALIZED、REGISTERED、RUNNING、STOPPING、STOPPED、FAILED。每个阶段
转换均为单向，进入 FAILED 状态的模组无法继续推进至 STOPPED，但由 FAILED 导致停止的模组
同样会触发其 Scope 清理流程，以避免资源泄漏。Loader 的生命周期状态机以原子方式记录每
个模组的当前状态，状态转换由 Loader 严格控制，模组自身无法自行推动状态转换（除非通过
Scope 行为触发安全关闭流程或抛出未处理异常导致强制失败），这种设计避免了因模组误操作
导致的 Loader 状态不一致。

DISCOVERED 阶段是 Loader 在 `mods/` 目录中扫描 JAR 文件、定位 `META-INF/mod.json` 并读取
基本字段的阶段，此时尚未解析依赖关系。Loader 内部对发现阶段的扫描做了性能优化：它会为每
个 JAR 文件维护一个快速校验标记，若文件自上次扫描后修改时间未变则直接从缓存读取元数据
而不是重复解析，这一优化在开发环境频繁构建复用的场景下将扫描开销降低到毫秒级别。

进入 RESOLVED 阶段 Loader 已完成全部元数据校验、依赖图构建与冲突检测。Loader 的依赖图按
拓扑排序确定加载顺序，并检测循环依赖后切断其中一条边以避免死断。Loader 在这一阶段的冲突
检测并非仅校验 id 与版本，还会将模组的 `mili.minecraft` 与当前运行环境中的游戏主版本
进行比配，并在不匹配时记录错误原因。当模组图中存在冲突时 Loader 会将冲突诊断信息写入全
局冲突报告中，开发者通过 Loader 的可视化工具或命令行接口可以快速查阅该报告。LOADED 阶段
通过 ClassLoader 加载 `entrypoint` 指向的类文件并执行静态初始化，但尚未调用 `initialize`
方法。ClassLoader 在创建时实施了策略检查，包括字节码版本、是否引用了已知的危险类签名、
以及是否包含未签名或残留的开发调试入口。ClassLoader 隔离策略的一个关键方面是反射调用的
可见性边界：当模组 A 试图通过反射访问模组 B 中一个非公开的类时，即便该类在 Java 语言层
面是可见的，Mili ClassLoader 的 SecurityManager 也会拦截并引发安全异常。

INITIALIZED 阶段调用入口点类的 `initialize` 方法，模组在此阶段注册自己的 EventBus 监听、
Capability 实现、配置实例及提交异步任务。Loader 会为该方法执行设置超时阈值，超时后 Loader
将 `initialize` 线程中断并将模组标记为 FAILED。Loader 还会对模组在 `initialize` 中提交的
初始任务数量设置上限，超出的部分会被延迟到后续 tick 执行以防止启动区间的任务拥堵导致全
局卡顿。REGISTERED 阶段模组已完成自身的静态注册并准备参与跨模组交互，此时 Loader 将按依
赖顺序依次触发各模组的注册完成回调。注册完成回调是一个可选的生命周期钩子，模组通过在入口
点类中声明特定签名方法并通过注解标记来注册。RUNNING 阶段表示模组已完全开始参与游戏运行时，
包括接收事件、响应请求与执行 Scope 任务。Loader 的任何状态转换都会产生对应的 `ModLifecycleEvent`
事件供平台工具与开发者诊断使用。

STOPPING 阶段由游戏正常退出、用户主动卸载或致命错误触发，未开始的 Scope 任务将被丢弃，事
件监听器从 EventBus 移除，Capability 对外部不可见。Loader 进入全局 STOPPING 时会先向所有模
组广播预停止事件，模组可利用这一时机快速保存配置到文件后端并标记自身的关闭意图。随后 Loader
主动调用 `scope().stop()` 触发 Scope 的级联停止，Scope 进入 STOPPING 状态后会先取消所有还
在排队中的任务、同步等待已执行到一半的 tick 任务终止、最后关闭所有注册的资源句柄。模组持有
的非 Scope 管理资源（如打开的文件句柄、网络套接字或原生库句柄）需要在该阶段通过事件监听器
或 `scope().onStop()` 显式释放，否则 ClassLoader 无法完全卸载将导致内存泄漏。Loader 对
Scope 的停止执行设置了整体超时上限，超过超时 Scope 会被强制中断并以警告日志记录。

STOPPED 阶段所有清理已完成，Scope 进入终止状态，ClassLoader 仍存活但不再接收任何调用。Loader
会根据内存压力指示 ClassLoader 是否应尽快回收，模组不应在 STOPPED 后试图重新启动自身，若需
重新加载应当通知 Loader 触发全新的加载周期。当 Loader 对模组进行诊断时，STOPPED 状态的模组
其 Scope 仍保留完整的生命周期转换记录与资源分配记录，开发者可以通过 Loader 的诊断命令查看
这些历史数据来定位问题。

FAILED 阶段表示模组在任一阶段出现未处理异常，Loader 将记录堆栈、关闭该模组的 Scope 并抛出
加载异常对应的错误信息。FAILED 状态的模组在 Loader 管理界面中以红色标记呈现，运维者可以通
过日志或诊断命令查看失败原因。Loader 同时会检查是否有其他模组依赖于失败模组，并将这些因依
赖缺失而连坐的模组也视作失败以避免后续空指针异常。需要注意的是 FAILED 与 STOPPED 的清理流
程是相同的，都会触发 Scope 终止与 ClassLoader 标记不可达，区别在于 FAILED 会额外在 Loader
全局错误记录中创建失败条目并影响整体健康状态报告。在热替换场景下 FAILED 的模组会保留其失
败状态记录，直到下一次替换尝试时 Loader 才会重新推进其生命周期将所有状态重置。模组作者如
果需要在运行时主动触发 FAILED 状态以外的可控关闭，应当调用 `scope().requestStop()` 将模组
平稳过渡到 STOPPING 而非抛出异常。

模组不应自行调用任何 `lifecycle()` 方法，这些概念在 Mili 体系中属于前身设计且已不存在。取而代之，
模组应通过 `scope().state()` 观察当前 Scope 状态以决定可执行操作的边界。Scope 的状态变化与上
述生命周期严格对齐，但表达为 Scope 语义，这意味着模组对自身挂停或失败的反应与 Loader 对全局事
件的响应使用同一套状态模型，继承自 Runtime 的 Scope 状态机设计。模组在实现跨 Scope 协调时可
以使用 `scope().onEnterState(target, callback)` 注册状态转换钩子，这一机制是实现优雅关闭流程的
基础。

## 作用域树与生命周期绑定

Mili Runtime 使用 Scope 树进行资源隔离与生命周期管理，根节点为 `rootScope`，代表整个 Runtime
的生存期。每个模组加载时 Loader 会在 `rootScope` 下创建一个子节点，命名为 `Scope("mod:{id}")`，
其中 `{id}` 是模组在 `mod.json` 中声明的 id。模组的所有运行时资源（任务队列、事件监听、配置
命名空间、Capability 注册）均挂载于该 Scope 节点上，Scope 的创建与销毁与模组 LOADED 至 STOPPED
阶段一一对应。Scope 树的设计灵感来自结构化并发，子节点的生命周期不能超过父节点，父节点终止时
所有递归子节点必须完成终止。这一设计使得整个模组生态的生命周期管理变得可推演：只要 `rootScope`
还活着，模组的 Scope 就不可能意外消失。

当 Scope 进入停止阶段时，Runtime 将以自底向上的顺序递归清理其子 Scope 资源，首先取消未执行的
任务、注销事件监听器、关闭配置句柄，再标记 ClassLoader 为不可达。Scope 的清理队列是幂等的，
同一资源被释放多次不会导致异常，这一设计便于模组的资源释放代码无需担心因 Loader 级联清理与自
身事件监听器之间的竞态而触发意外错误。如果 Scope 的清理事务执行时间过长，Loader 会在全局 STOPPING
时输出警告并提示可能存在的资源泄漏原因，这是开发阶段重要的诊断信号。

Scope 树的层级深度由 Loader 在创建时确定，通常情况下仅存在 `rootScope` 与 `mod:{id}` 两层结构。
但在模组主动创建嵌套 Scope 的情况下可能形成更深的层级，嵌套 Scope 的生命周期不能超过其父模组
Scope。模组可以通过 `scope().createChild("name")` 创建嵌套 Scope 用于管理具有独立生命周期的子
组件，例如一个模组中的多世界逻辑可以为每个加载的游戏世界创建一个子 Scope，从而实现世界卸载时
的精确资源清理。嵌套 Scope 的命名会在 Loader 中以冒号拼接方式表示，例如 `mod:mymod:world:overworld`
表示 mymod 模组为 overworld 维度创建的子 Scope，这种命名约定便于日志与诊断通道中显示 Scope 树
的结构关系。

嵌套 Scope 的能力继承机制也是隔离模型的重要组成部分：当一个子 Scope 调用 `getCapability()` 未
找到匹配的注册时，Loader 会沿父 Scope 链向上查找直至根节点，因此根节点上的公共能力对所有模组
及其子 Scope 都可见。Loader 的 Capability 注册机制是上层注册优先，即后注册的实现覆盖先注册的
实现，但层级访问遵循最近匹配原则，即上层 Scope 的注册优先于根节点，模组应据此理解同名能力在
不同层级上的查询结果差异。

## 平台事件体系

Mili 事件体系运行于每个 Scope 绑定的 EventBus 之上，模组通过 `ModContext.events()` 获取该总线
并注册监听器。事件总线的分发策略保证了同一 Scope 内事件监听器的同步调用顺序与注册顺序一致，
跨 Scope 分发时引入轻量异步以解耦模块间的时序依赖。事件监听器应声明为静态方法或独立类以避免
隐式持有外部实例引用，减少因 ClassLoader 无法回收带来的内存压力。事件处理中若抛出异常，Loader
将捕获异常、记录至模组日志并跳过后续监听器以继续分发，不会因此终止游戏。

平台共定义以下事件类型，每个事件携带对应阶段的游戏状态快照。客户端侧事件按照发生阶段从早到晚
依次为：ClientStartingEvent 在游戏启动序列开始时触发，此时窗口与渲染系统尚未就绪，适合进行全
局系统预分配工作。ClientStartedEvent 在游戏主循环启动前触发，标志客户端整体初始化完成，此时
模组可以安全地注册渲染、输入等高层系统回调。ClientTickEvent 在主循环每个 tick 触发一次，主
要逻辑应在此事件中推进周期性状态更新。ClientWorldJoinEvent 在加载或切换到某一游戏世界时触发，
携带世界对象与维度信息。ClientWorldLeaveEvent 在离开当前世界时触发，模组应在此释放与世界绑定
的缓存、纹理与运行时数据，若模组存在着色器缓存或几何缓冲区必须在此事件中主动销毁以避免 GPU
资源泄漏。ClientPlayerJoinEvent 在玩家实体生成于世界中时触发，携带玩家对象与游戏模式信息。
ClientPlayerLeaveEvent 在世界中玩家实体移除时触发。ClientScreenOpenEvent 在打开任意游戏界面
时触发，携带新屏幕对象与先前屏幕对象，用于实现界面层逻辑。ClientStoppingEvent 在收到关闭指令
后、保存操作开始前触发，模组应在此快速执行轻量级状态保存与资源标记。ClientStoppedEvent 在所有
保存与清理完成后、进程退出前触发，可用于最终日志写入或临时文件删除。

服务器侧事件与客户端侧一一对应，分别为 ServerStartingEvent、ServerStartedEvent、ServerTickEvent、
ServerWorldJoinEvent、ServerWorldLeaveEvent、ServerPlayerJoinEvent、ServerPlayerLeaveEvent、
ServerStoppingEvent、ServerStoppedEvent。这些事件仅在专用的服务端环境中触发，模组需先通过
`ModContext.isServer()` 判断环境再注册对应监听器。Loader 通过环境过滤在分发阶段即跳过不匹配
的事件监听器。特别注意集成服务器环境下的服务端事件也会被触发，模组在实现全平台功能时需要在两
个环境中都正确响应同一套事件语义。开发者在注册事件监听器时，对于环境敏感类事件应在方法入口
处通过 `ModContext.environment()` 进行断言，违反断言时记录错误日志而不是让逻辑静默跳过。

模组可自定义事件类型并通过 `EventBus.publish(Object event)` 发布到当前 Scope，跨模组通信推荐优
先使用事件而非直接方法调用。事件类应为不可变对象，所有字段在构造后不应修改，若需变更应发布新
的事件实例。自定义事件类建议使用 record 类型定义以天然支持不变性与值语义，并显式实现 `equals` 与
`hashCode` 以支持事件去重与缓存。事件传播的跨模组路径通过 EventBus 的桥接机制实现：当一个模组
在其 Scope 发布事件时，Loader 会自动将该事件桥接到已注册对应类型监听器的其他 Scope EventBus 队
列中。Loader 通过 `@InterModPropagation` 注解控制事件默认桥接行为，默认为启用状态。

自定义事件的设计应当遵循语义明确、数据最小化和版本容忍原则。在事件继承层次设计上，Loader 使用
单一平面事件层级，不接受事件父类监听机制，即注册了 `BaseEvent` 类型的监听器不能收到 `SpecificEvent`
事件，开发者必须为每种事件类型单独注册监听器。

## 配置与管理

模组的配置系统由 Loader 以 Scope 为作用域的统一内存与持久化后端支撑，提供实时修改、回退与持
久化保存功能。配置视图代理允许读取操作每次都从内存中读取最新值，写操作会同步修改内存对象并触
发配置变更事件，但不会立即持久化至磁盘；通过 `saveConfig` 可以手动触发持久化，也可以等待 Scope
停止时由 Loader 自动持久化。模组可以实现 Scoped 配置监听器使用 `scope().onConfigChange(configName, key, callback)`
在 Scope 树中的项更新时主动触发回调，这是比轮询更轻量且实时响应最好的做法。

资源访问方面，`resources.open()` 返回 `Optional<InputStream>`，模组内部的资源路径都是相对于该 JAR
根目录而言的。安全边界是其中的关键设计考量：若模组在路径中包含超出自身 JAR 范围的字符序列，
Loader 会自动隔离并返回空结果并记录安全警告。`resources.list()` 方法可以列出模组 JAR 内指定
路径下的所有条目名称。在开发模式下，Loader 支持以解包目录形式加载模组，即在 `mods/` 下放置一
个目录而非 JAR，这在开发期间可以避免重复打包，Loader 会监控该目录的文件变更并通过文件事件回
调触发热重载。

## 构建与分发

模组最终产物是一个包含编译后类文件、META-INF 目录与资源文件的 JAR 包，文件名应为 `{modId}.jar`，
与元数据中声明的 id 完全一致。在 Gradle 构建脚本中可以通过在 `jar` 任务的 `archiveFileName` 配
置中引用 `mod.json` 中的 `id` 字段实现文件名与元数据的自动同步。构建时应在 `build.gradle` 中声
明对 Mili `api` 子项目的编译期依赖，将 Java 兼容性目标设置为 Java 25。混淆配置需要保留入口点
类与 `initialize` 方法签名不被重命名，同时保留所有被 Loader 反射解析的注解类，建议使用平台提
供的混淆规则模板并仅对内部实现类进行混淆。开发者可以在开发环境中使用 Gradle 的 `prepareMods` 
任务直接将构建产物复制到开发环境的 `mods/` 目录，实现一键构建与部署。

模组的分发渠道不限于本地 `mods/` 目录，Loader 通过远程模块索引查询与下载机制支持从官方或第三方
模组仓库自动检索、下载与校验模组文件。构建产物发布时建议携带 SHASUM256 校验文件与可选的数字签
名，以保证分发链路中 JAR 文件未被篡改。

平台支持运行时模组热替换，但仅在 Scope 未处于 RUNNING 时才建议重新部署。开发环境中可配合 Loader
的 `--watch` 模式自动检测 `mods/` 目录变更并触发增量重载，该模式会保留未变更模组的状态不变，仅
重建受影响模块的 Scope 以加快迭代速度。热替换期间 Loader 会比对前后两次 JAR 中的 `mod.json` 以
判断模组元数据是否发生变更。即便是在 `--watch` 模式下模组热替换也并非完全零停机。

模组卸载后的存档兼容性是需要模组作者在设计阶段就考虑的问题。当模组从 `mods/` 目录中被移除时，
其已向游戏世界添加的自定义方块实体、物品 ID 与维度绑定仍然保留在存档文件中，模组需要通过世界
卸载事件将这些持久化数据导出为可由其他模组或原版游戏安全忽略的序列化格式，或者标记为可由原版
行为替代的占位数据。Loader 本身不会自动处理模组退出后的存档清理，这一职责通过模组事件与存档扩
展机制下放给模组自身解决。
