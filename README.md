# Mili Platform

Mili 是一个从零编写的 Minecraft Mod 加载器，基于 Java 25，不克隆也不兼容 Fabric。它自带独立的 Runtime 内核（Scope / Scheduler / EventBus / Capability / ResourceManager），为 Mod 提供纯 Mili 原生 API，不依赖 Fabric，不使用 Mixin，也不依赖 LaunchWrapper。整个平台以运行时契约（ABI）为核心，所有 Mod 只面向契约编程， Loader 负责注入真实实现。Mili 的目标不是替代 Fabric，而是为 Minecraft 客户端 Mod 工程提供一个全新的、无历史包袱的基础设施选择。我们面向的是那些对现有加载器架构有深刻不满的 Mod 开发者——那些受够了字节码操控的脆弱性、Classpath 污染的微妙性、以及事件系统优先级战争的混乱的开发者。同样，我们也面向那些想要构建大型复杂 Mod 但又苦于基础设施不足的玩家端 Mod 作者——Mili 的结构化并发与能力系统让多 Mod 协作的工程维护成为可能。

## 核心特性

Mili 的 API 层完全不含 Minecraft 依赖，所有与游戏本体的交互通过独立的反射桥接层完成——这意味着 Mod 开发者永远不会无意中耦合到具体的 Minecraft 版本或内部类，也意味着 Minecraft 版本升级时无需重新编译已有的 Mod JAR。Scheduler 提供结构化并发原语，替代传统的线程手动管理，让异步任务的生命周期天然受控于 Scope，取消传播自动且确定。Scope 决定了一个 Mod 的可见资源边界：离开 Scope 即释放，不再有悬挂引用或内存泄漏。Capability 系统采用拒绝即默认的访问控制模型，Mod 必须显式声明自己能提供或消费的能力，运行时在匹配阶段校验，能力不匹配则拒绝加载而非静默失败。版本锁定严格执行三元组机制——平台版本、ABI 版本、Minecraft 版本三者必须完全一致才允许加载，不存在模糊的兼容区间，升级的显式性是其安全保证的一部分。每个 Mod 拥有独立的 ClassLoader，类加载严格隔离，Mod 之间的唯一通信通道是 ABI 接口与反射桥接，杜绝了类污染与循环依赖。

这份特性清单背后的统一哲学是：显式优于隐式，编译错误优于运行时错误，拒绝优于静默妥协。当某件事做错了的时候，Mili 倾向于在最早可能的时刻——加载期、编译期甚至设计期——将其暴露出来，而不是等待它在生产环境中某个不经意的角落爆开。这个哲学既是对 Mod 平台长期维护经验的总结，也是对 Minecraft 客户端 Mod 生态中"弱类型 + 强耦合"现状的有意识回应。

## 模块构成

Mili 由四个 Gradle 模块组成，依赖关系严格单向：

| 模块 | 职责 |
|---|---|
| `mili-abi` | 稳定契约接口，零运行时依赖，Mod 编译的唯一目标 |
| `mili-runtime` | Runtime 核心：Scope、Scheduler、EventBus、Capability、ResourceManager |
| `mili-loader` | 启动发现、依赖解析、类加载、生命周期调度 |
| `mili-minecraft-integration` | 反射桥接层，负责与 Minecraft 26.2 客户端交互 |

依赖方向为 `abi ← runtime ← loader` 与 `abi ← minecraft-integration`。`mili-runtime` 依赖 `mili-abi`，Loader 依赖 `mili-runtime` 与 `mili-abi`。Minecraft 集成模块只依赖 ABI，不反向引用 Loader。这种设计保证了 ABI 模块的纯洁性——它不引用自己即将被注入的任何实现类，整个依赖图无环且单向。Minecraft 集成模块之所以独立而非嵌入 Loader，是因为它的替换频率远高于 Loader 本体——Minecraft 补丁发布时只需重打集成模块 JAR，无需重建整套 Loader 链。这个设计决策也允许第三方社区为不同的 Minecraft 快照版本独立维护桥接层实现，只要不违反 ABI 契约。

模块之间的边界不仅是代码层面的约定，也通过 Gradle 的 `api` 与 `implementation` 关键字严格实施。`mili-abi` 只暴露 `api` 依赖，下游 Mod 无法通过传递依赖引入任何内部类型。`mili-runtime` 对 Loader 暴露 `api` 依赖，但对自己内部的实现细节使用 `implementation` 确保编译期隔离。Gradle 编译器插件定期审核这些依赖声明，一旦发现违规（如 ABI 模块引入了意外依赖），构建会立即失败并给出修复建议。

## Minecraft 26.2 集成流水线

Minecraft 26.2 客户端 JAR 是 Mili Platform 的**正式构建输入**——不只是 manifest 里写 `minecraft=26.2`，而是经过完整处理流水线：

```
Minecraft 26.2 JAR (build input)
        ↓  verifyMinecraftArtifact   — 验证存在、可读、版本匹配、Bundler 布局
        ↓  prepareMinecraft          — 处理 Mojang Bundler，规范化到 build/minecraft/
        ↓  decompileMinecraft        — CFR 反编译到 build/minecraft/decompiled/（临时产物）
        ↓  generateMinecraftIntegration — 分析反编译源码，生成元数据 + 桥接源码
        ↓  compileJava               — 编译集成模块（含自动生成的源）
        ↓  shadowJar                 — 打入 mili-platform.jar
```

产物：
- `META-INF/mili/platform.json` — 含 `minecraftArtifact.sha256` 指纹
- `META-INF/mili/minecraft.json` — Minecraft 构建指纹（版本、SHA-256、反编译器、类/事件/注册中心数量）
- `build/minecraft/metadata/minecraft-build-report.json` — 完整构建报告

本地开发时把 `test_client/26.2.jar` 放入仓库根目录即可；CI 通过 `MINECRAFT_ARTIFACT` 环境变量定位。反编译源码是临时构建产物（已加入 `.gitignore`），不提交也不随 Release 发布。

## 快速开始

编译项目需要 Java 25 环境。以下是完整构建命令：

```
./gradlew clean build test
```

构建完成后，产物位于各模块的 `build/libs/*.jar` 目录：

- `mili-abi/build/libs/mili-abi-<version>.jar` — Mod 编译依赖
- `mili-runtime/build/libs/mili-runtime-<version>.jar` — Runtime 核心
- `mili-loader/build/libs/mili-loader-<version>.jar` — Loader 本体
- `mili-minecraft-integration/build/libs/mili-minecraft-integration-<version>.jar` — 反射桥接

将 Loader JAR、Runtime JAR 与 Minecraft 集成 JAR 放入 `<loader-dir>/core/`，Mod JAR 放入 `<loader-dir>/mods/`，运行 Loader 即可启动。Loader 会自动扫描 Mod 目录、验证元数据、解析依赖图，并按拓扑顺序初始化每个 Mod。启动过程的所有步骤都有对应的日志输出，使得排查 Mod 加载失败的原因成为一件可追溯的工作。如果 Mod 加载失败，Loader 会在日志中输出具体的错误原因（缺失的依赖、能力不匹配、类无法实例化等），并在 `startup-report.json` 中生成一份完整的启动报告。

开发模式下，可以通过 `./gradlew :mili-loader:run` 直接在 IDE 中启动 Loader。开发模式跳过了部分严格校验以加速迭代（例如不强制校验 Mod 的版本三元组在 patch 级别上完全一致），但生产构建中的校验一个都不会少。开发与生产环境的差异是 Mili 刻意维护的——它让开发者可以在本地快速试错，同时保证发布产物始终经过完整验证。开发模式的差异范围在文档中有明确列表，任何未在文档中列出的 Loader 行为都假定在两种模式下表现一致。

## 构建说明

前置要求：JDK 25 已安装并设为默认 `JAVA_HOME`。Gradle Wrapper 会自动下载所需 Gradle 版本，无需手动安装。

常用 Gradle 任务：

| 命令 | 作用 |
|---|---|
| `./gradlew build` | 编译全部模块并打包 JAR |
| `./gradlew test` | 运行全部单元测试 |
| `./gradlew clean build` | 清理后完整重建 |
| `./gradlew :mili-abi:build` | 仅构建 ABI 模块 |
| `./gradlew :mili-runtime:test` | 仅运行 Runtime 测试 |

构建系统对 Java 版本敏感，低于 25 的 JDK 将导致编译失败——这是预期行为。反射桥接层依赖 25 引入的特定 JVM 行为，不允许降级到旧版本。如果有多个 JDK 同时安装在系统中，通过设置 `JAVA_HOME` 环境变量指定 JDK 25 路径即可。建议在使用 Gradle Wrapper 时显式设置 `org.gradle.java.home` 以确保构建环境一致。Gradle Wrapper 本身需要联网访问 Gradle 服务来下载发行版，如果网络受限，可以预先将 Gradle 发行版放置到 `~/.gradle/wrapper/dists/` 对应的目录中以离线构建。

构建产物还可以进一步通过 `./gradlew distTar` 或 `./gradlew distZip` 打包为适合分发的压缩包，其中包含启动依赖的 JAR、默认配置文件与示例 Mod 项目结构。分发包不包含源码与测试代码——它们通过独立的源码分发渠道获得。分发包内部提供了一个启动脚本（`bin/mili-loader`），用户可以通过命令行直接启动 Loader，不需要手动拼装类路径。Windows 用户 PowerShell 脚本（`bin/mili-loader.ps1`）也已提供，确保在受限执行策略下也能安全运行。

## 设计理念

Mili 不与 Fabric 竞争生态位，也不试图兼容 Fabric 的 Mod。Fabric 是一个已经成熟且广泛使用的加载器，Mili 选择了一条不同的路：完全自研 Runtime、全新 ABI、面向未来的并发原生设计。我们认为这值得，因为 Minecraft 客户端本身正在经历结构性的变化，Fabric 所继承的 LaunchWrapper 遗产已经越来越制约上层创新。我们不是在做一个更好的 Fabric——我们是在做一个不同的东西。Mod 生态从零开始建立，虽然短期内 Mod 数量无法与 Fabric 相比，但这也意味着我们可以确立一套从底层就正确的架构，不必背负历史包袱。每个设计决策都可以基于当下需求做出，而不受"十年前这个接口被大量 Mod 使用了"的约束。这不是对历史的傲慢——这是对技术决策时效性的尊重。

ABI 优先是 Mili 最核心的设计决策。所有 Mod 只编译 `mili-abi`，这意味着 Loader 实现可以任意重构、优化甚至重写，只要保持 ABI 不变，已有的 Mod 二进制无需重新编译即可运行。这种接口与实现的严格分离是整个平台可维护性的基石。但 ABI 优先不仅仅是技术约束——它也是一种承诺：一旦进入稳定契约，我们保证不会在次要版本中静默破坏 Mod 的二进制兼容性。这个承诺是 Mili 与其他"ABI 优先"项目竞争时的关键差异——其他项目可能声称 ABI 优先但在实践中频繁做出破坏性变更，Mili 通过将 ABI 版本号语义与破坏性变更策略写入项目治理文件来制度化这一承诺。具体而言，破坏性变更必须经历：提案阶段（在社区 RFC 中论证变更的必要性）、过渡期（旧 API 标记为 `@Deprecated` 并附带迁移指引）、移除期（在经过至少一个次版本号的过渡期后才真正将旧 API 移除）。

反射桥接是无字节码重写的，这意味着不需要 Agent、不需要 premain、不需要修改启动参数。Mili 通过纯反射访问 Minecraft 运行时类，显式且可追踪。这种方法牺牲了一点启动性能，换来了极高的可调试性与版本可移植性。对于每个反射访问点，桥接层维护一份声明，Loader 在启动时验证声明的字段与方法在目标 Minecraft 版本中确实存在，如果不存在则输出清晰的结构化错误信息而非抛出难以理解的 `NoSuchMethodError`。这份声明的维护工作集成在 CI 中，保证桥接层与目标 Minecraft 版本的持续同步。当 Minecraft 26.2.x 补丁发布时，这个 CI 流程会自动检测桥接声明是否仍然有效，失效点会在第一时间被修复或标记。

结构化并发通过 Scheduler 与 Scope 提供，替代了裸线程手动管理。每个异步任务的生命周期天然有界，取消传播确定，不会留下悬挂的线程或泄漏的资源。Capability 系统提供了声明式、可组合的访问控制，能力不匹配则拒绝而非静默失败。这一切的组合使得 Mili 平台的 Mod 比传统加载器上的 Mod 更易于推理其行为边界。结构化并发不是一个时髦的术语——它是 Mili 平台区别于其他加载器的最核心技术差异。当传统的 Mod 作者在 `CompletableFuture` 的取消传播链上苦苦挣扎时，Mili 开发者可以通过 Scope 的上下文取消机制优雅地终止任务链，无需关心线程级的中断状态传播。

## 类加载与隔离模型

每个 Mod 拥有独立的 ClassLoader，类的解析遵循父委托模型，但委托目标经过精心控制：ABI 接口由平台 ClassLoader 提供，Minecraft 类由集成层 ClassLoader 提供，而 Mod 自定义类由自身 ClassLoader 解析。Mod A 无法直接引用 Mod B 的类，除非通过 ABI 接口显式共享。这个模型避免了"classpath 污染"——那种在旧加载器上常见的、因为类被意外共享而导致静默崩溃的问题。ClassLoader 隔离的另一个好处是，当某个 Mod 存在严重 Bug 导致 JVM 崩溃时，Loader 可以通过崩溃前日志追溯具体的 Mod ID，而不是在混合堆栈中费力猜测。

依赖声明在 Mod 元数据中完成，Loader 在构建依赖图时检测循环依赖并拒绝加载整个链。版本冲突解析采用最小版本选择（MVS），即在所有声明的需求版本中选择满足所有约束的最小版本。这个策略与 Gradle 自身一致，避免引入新的心智负担。可选依赖与强制依赖有明确区分：如果可选依赖不存在，Mod 加载流程不受影响，但运行时访问该依赖的能力时会收到空结果而非异常。

ClassLoader 的命名空间规则确保 Mod 之间不会发生意外的类共享——两个不同的 Mod 即使声明了相同全限定名的类，也不会相互干扰，因为它们被独立的 ClassLoader 分别解析。唯一的例外是 ABI 接口类，它们由统一的平台 ClassLoader 提供，这是 ABI 优先设计的必然结果：所有 Mod 共享同一份 ABI 类定义。如果 ABI 接口发生变更（按照破坏性变更策略），所有依赖它的 Mod 需要重新编译——这正是 ABI 版本号语义所约束的范围。ClassLoader 隔离也带来了一些有趣的边缘情况：同一个 Mod 不能同时存在两个不同版本的实例，因为它们的 JAR 文件名相同会导致 Loader 拒绝扫描。如果某个 Mod 需要版本化的模块化能力（如一个"核心模块"和多个"功能模块"），它们应该共享同一个 Mod ID 并通过能力系统区分功能，而不是拆分出独立的 Mod JAR。

热加载与热卸载在开发模式下由 Loader 的实验性功能支持——但这不意味着 Mod 可以无痛支持热替换。资源回收、事件注销与 ClassLoader 销毁的语义目前仍在积极讨论中，因此不建议在生产环境依赖热加载。开发模式下的热加载主要用于 UI 迭代、配置文件验证与轻量级逻辑调试，重度状态管理的场景每次仍然需要完整重启。Loader 的热加载 API 标记为 `@ExperimentalApi`，行为可能在次版本变更中发生不兼容调整。热加载的实现基于 ClassLoader 重新创建——旧 ClassLoader 中的状态不会迁移到新实例，因此这是一个"语义冷重启"而非"真正的热替换"。

## 生命周期与调度

Mod 的生命周期严格受 `Lifecycle` 状态机约束：加载（`LOADING`）、初始化（`INITIALIZING`）、活跃（`ACTIVE`）、中止（`SUSPENDED`）、卸载（`UNLOADED`）。状态转换不可逆，一旦进入 `UNLOADED` 即不可回退。这个模型确保了 Mod 不会进入半初始化状态而难以推理。每一个状态转换都会触发对应的生命周期事件，订阅这些事件的 Mod 可以在状态变更时执行自己的逻辑——例如当某个依赖 Mod 进入 `ACTIVE` 状态时，本 Mod 才开始它的网络初始化。监听器抛出的异常会捕获并记录，但不阻止状态转换的继续。状态机的不可逆性是一个核心约束：在 Mod 系统的语境下，"加载失败后重试"的状态机循环只会让问题被掩盖而非解决，因此 Loader 要求重试逻辑必须显式地经 `Lifecycle.unload()` 后再进入 `Lifecycle.load()`，即重新加载的语义是全新实例而非状态回溯。

`Scheduler` 基于结构化并发设计——每个任务归属于一个 `Scope`，Scope 关闭时所有未完成任务自动取消。任务优先级通过 `TaskPriority` 控制，映射为线程优先级与超时策略。`TaskState` 提供等待、运行、完成、取消四种状态的查询，调用方可以据此做出决策而不需要轮询。超时配置通过 `Configurer` 在 Scope 创建时设定，后续不可更改；子 Scope 继承父 Scope 的超时配置但可以通过自己的配置进行覆盖。任务分组（`TaskGroup`）允许批量管理同一逻辑域下的任务，整个组的取消与状态查询都通过统一的 `GroupHandle` 完成。

不推荐使用 Java 原生的 `CompletableFuture` 提交长时间运行的任务——它们不受 Scope 控制，取消传播依赖显式的链式取消调用，极易导致任务泄漏。所有异步逻辑应当通过 `Scheduler.submit(Scope, Task)` 统一管理。如果一个第三方库返回 `CompletableFuture`，应当在收到结果后尽快通过 `Handle.adapt()` 将其桥接到 Scheduler 的管理框架中——桥接后，任务的取消状态会与 Scope 关联，但不保证原始 `CompletableFuture` 本身会被取消。这是结构化并发哲学的直接体现：任务的取消应该从外向内传播，而不是由内部任务自行决定如何响应取消。

## 事件传播

`EventBus` 提供类型安全的发布-订阅机制。订阅通过 `@EventListener` 注解声明，Loader 在初始化阶段自动扫描并注册处理器。`Subscription` 句柄提供取消注册的能力，配合 Scope 确保订阅不跨 Scope 存活。事件不支持冒泡或拦截——这是刻意的简化，避免 Mod 之间的隐式顺序耦合和优先级争斗。如果两个 Mod 需要协调，它们应该通过 Capability 接口显式声明，而不是靠事件顺序隐式约定。这种设计可能会让习惯了 Bukkit 插件体系的开发者感到不适应——Bukkit 有一个庞大的事件继承树和取消机制，但我们认为那套机制在过去十年里带来的维护成本远超其灵活性收益。Mili 的事件系统是"无小聪明"的——它只做了一件事：把事件从发布方传递到订阅方，并确保不丢失、不重复、不混乱。如果你需要更复杂的协调模式，这正是 Capability 系统存在的原因。

事件的类型安全在发布时被检验——`EventBus.publish(Event)` 保证只有匹配该事件类型的监听器会被调用。编译期泛型保证了发布方无法意外地发布错误类型的运行时对象。这对于 Mod 系统的健壮性至关重要——事件类型的错误往往在运行时才暴露，而此时已经造成了难以追溯的状态混乱。类型安全也带来了一个实际的好处：当 Loader 需要梳理事件监听链时，它可以基于泛型参数快速索引相关处理器，而不需要运行时类型检查。

处理器抛出异常不会终止事件传播链——Loader 会捕获并记录异常，然后继续调用下一个处理器。但连续多处理器抛错会触发"频率熔断"。Loader 内部的维护窗口记录：每个事件类型上，每经过一百次事件传播，如果其中有超过十次处理器抛错，该事件类型会被临时送入"冷却期"三十秒。冷却期内仍接受发布但不调用任何处理器——所有抛错的日志仍正常输出，只是不触发用户代码。冷却期结束后自动恢复，若再次触发，冷却期加倍。这是防止"一个坏处理器拖垮整个事件链路"的防御策略，同时也在日志中输出详细的异常堆栈。

## 能力与权限

`CapabilityToken` 是能力声明与消费的基础凭证，由提供者注册、由消费者消费。能力匹配发生在加载期，如果消费者的需求无法被任何提供者的声明满足，则 Loader 拒绝加载该消费者 Mod。这个早期失败的哲学——在能验证的时候尽早验证——贯穿了整个 Mili 的设计。能力匹配不只检查 Token 是否存在，还检查 Token 类型的兼容性——`CapabilityToken<NetworkProvider>` 与 `CapabilityToken<RenderProvider>` 代表完全不同的语义，即使它们被错误地共享了同一个字符串标识，Loader 也能通过泛型参数类型区分。这个"类型 + 引用"的双重匹配机制比传统的服务注册模式（通常只依赖字符串键）更加安全，也更易于在规模化项目中治理。

`Permission` 与 `PermissionManager` 构成运行时的第二层访问控制：加载期校验能力匹配，运行时校验具体操作权限。权限可以在 Mod 的配置文件中声明，默认配置对所有 Mod 开放常用权限，而对高危操作（如网络访问、文件系统写入）要求显式声明。管理员可以通过覆盖默认配置收紧权限策略。权限检查发生在 API 调用层面，Mod 无法绕过——所有敏感操作都通过 `PermissionManager` 的门面进入系统。权限的审计日志会记录每次拒绝与通过事件，可通过全局配置的审计级别调整详细程度。审计日志默认关闭，开启后会带来可感知的 I/O 开销——在生产环境中建议仅开启 `DENY` 级别的审计，关闭 `ALLOW` 级别的审计。

能力匹配的语义基于"完全相等"而非"兼容或继承"——`CapabilityToken` 的比较使用引用相等性而非自定义比较器。这么设计的意图是让能力语义显式化：如果两个 Mod 希望它们的能力可互相替代，它们必须使用同一个 `CapabilityToken` 实例（通过共享 ABI 模块中的常量）或使用 `CapabilityToken.aliasOf()` 声明显式别名。没有隐式的继承推导——这避免了"看似兼容但行为微妙不同"的尴尬情况。

## 资源管理

`Resource` 与 `ResourceManager` 负责 Mod 的资产访问，包括配置文件、语言文件、数据资源与原生库。每个 Mod 通过 `ModContext.getResourceManager()` 获取自己的资源管理器，资源访问范围严格限定在 Mod 自身的 JAR 范围内。跨 Mod 的资源共享必须通过 ABI 接口暴露，不允许直接文件系统访问其他 Mod 的资产。这个设计约束了 Mod 系统的安全边界——一个 Mod 无法盗用另一个 Mod 的创意资产，也无法因为读取了错误的文件而静默加载错误的内容。

资源加载遵循优先级规则：Mod 自身的资源优先，平台补丁次之，Minecraft 原生的最后。这个规则确保了 Mod 可以安全地覆盖任何级别的原生资源而不必担心循环依赖。资源解析在首次访问时惰性完成，而非在加载期一次性读取全部资源——这种惰性设计减少了启动开销，也让内存中的资源占用与实际使用量成正比。如果 Mod 的资源文件数量巨大（几千个以上的语言文件），惰性解析不是唯一的管理手段——ResourceManager 还提供了一个 `preload(String... paths)` 方法，允许 Mod 显式声明哪些资源应在加载期解析并在内存中缓存。

资源监视器（`ResourceWatcher`）提供文件变更通知，允许开发者在开发模式下实现配置热重载。生产模式下该功能关闭，所有资源的生命周期与 Mod 同步——Mod 卸载时其资源监视器一并销毁，释放所有文件句柄。Watcher 的轮询间隔在开发模式下是每 500ms，可通过全局配置调整，但不得低于 100ms（低于这个阈值会导致文件系统层面的竞争条件）。

## 反射桥接层

`mili-minecraft-integration` 是 Mili 与 Minecraft 26.2 之间的唯一边界。该模块通过反射访问 Minecraft 运行时类，将所有访问点显式声明在一个集中的清单文件中。Loader 在启动时验证这份清单——如果某个声明的字段或方法在目标 Minecraft 版本中存在但签名不匹配，Loader 会输出结构化错误信息并跳过该桥接点，而不是让整个客户端崩溃。清单文件作为代码而非资源整合在项目中，使得每一次声明变更都有对应的 Git 痕迹与 Code Review 记录。

反射方法句柄（`MethodHandle`）被缓存在类加载阶段，避免每次调用都执行反射查找。在安全前提下，通过 `setAccessible(true)` 移除访问检查以提升性能。所有反射操作都经过统一门面，任何未能按预期工作的访问点都会被记录并在启动报告中列出，便于桥接层的持续维护。门面层还处理了一个隐藏的问题：Minecraft 类中同名方法在不同 JVM 版本下可能有不同的调用约定（如静态方法与实例方法的字节码签名差异），门面层统一了访问入口，上层无需感知这些差异。

反射桥接层不包含任何逻辑代码——它只是一层薄的访问封装，真正的"做什么"决策由 Loader 或上层 Mod 完成。这种设计使得桥接层本身可以极薄且极快地维护，同时也使得它对 Minecraft 版本变化的适应性更强：当方法签名变化时，只需修改桥接层的声明文件，而不需要改动任何业务逻辑代码。当一个新的 Minecraft 补丁发布时（例如从 `26.2.0` 到 `26.2.1`），桥接层清单的维护工作通常集中在一两个小时内完成，不需要深入了解游戏本体的改动——修改聚焦于签名声明上的字段名、方法名与参数类型的变化。CI 系统会自动运行桥接层验证测试，确保修改后的声明与新的游戏客户端 JAR 匹配。

## 错误与异常

`MiliException` 是所有异常的基类。`ModLoadException` 表示加载期问题（类缺失、签名不匹配），`CapabilityException` 表示能力声明与消费之间的冲突，`ApiException` 表示运行时 API 调用错误。Loader 在启动阶段捕获所有异常并以结构化日志输出，包含堆栈链、受影响 Mod ID、建议修复方案。Mod 作者不应抛出 `MiliException` 的子类——它们应该通过返回值或回调表达错误，让 Loader 决定是否属于平台级错误。如果 Mod 未捕获的异常穿透了 Loader，Loader 会将该 Mod 转入 `SUSPENDED` 状态以隔离其影响范围。Loader 的 try-catch 边界位于每个 Mod 的生命周期回调处，因此一个 Mod 的回调抛错不会影响其他 Mod 的正常加载流程。这个隔离边界的设计经过了仔细的辩证：如果 Loader 捕获所有异常并忽略，会导致 Mod 作者误以为一切正常，而实际上部分 Mod 功能并未生效。因此 Loader 将异常信息传达给日志和 UI，但不向上抛出影响平台稳定性。

当 Mod 抛出未受检异常时，Loader 将该 Mod 转入 `SUSPENDED` 状态，隔离其事件订阅与能力提供，但不会终止整个平台的运行。这个"隔离失败"的设计确保了单个 Mod 的崩溃不会拖垮整个客户端。SUSPENDED 状态的 Mod 在日志中会被所有潜在依赖方看到，从而避免依赖方陷入无限等待。隔离的范围包括：停止处理该 Mod 的事件、将其能力注册标记为不可达、取消所有由其 Scheduler 提交的任务、但不立即卸载其 ClassLoader（卸载仅在显式调用 `Lifecycle.unload()` 后执行）。这个设计带来了故障排查的便利性——开发者可以在 Mod 进入 SUSPENDED 后，通过日志查看异常堆栈链与上下文信息，同时也保留了通过 `Lifecycle.resume()` 重试恢复的可能性。

崩溃恢复策略取决于异常类型。对于可恢复的 `ApiException`，Loader 允许 Mod 通过 `Lifecycle.resume()` 重试初始化；对于不可恢复的 `ModLoadException`，Loader 会将该 Mod 标记为永久失败，直到重新安装或修复。永久失败的 Mod 在 UI 中会显示为明确的状态指示器，避免用户困惑于某个功能突然不可用的原因。恢复重试有次数限制——连续重试超过五次后，Loader 会认为该 Mod 存在根本性问题，建议完全卸载并等待作者修复。重试计数在每次成功加载后归零，这意味着一个偶发性失败不应导致 Mod 被永久标记——只有反复失败才触发硬限制。

启动失败时 Loader 会生成一份结构化的启动报告（`startup-report.json`），内容包括所有已处理的 Mod、依赖图拓扑序、每个 Mod 的最终状态与加载耗时。这份报告在持续集成环境中被 Mili 维护者用于验证 Loader 本身的正确性，在用户端则被 Mod 开发者用于定位加载失败。报告格式版本化，当前版本为 `1.0`，与 `ABI_VERSION` 独立版本号。启动报告中还包含 Loader 自身的版本信息与环境摘要（Java 版本、操作系统、系统架构），这些信息对于跨环境问题复现有不可替代的价值。

## 版本策略

版本锁定遵循严格三元组机制，三者必须一致：Loader 实现版本（`CURRENT_VERSION`）、ABI 契约版本（`ABI_VERSION`）、目标 Minecraft 版本（`TARGET_MINECRAFT`）。Loader 在加载每个 Mod 时会校验三元组，任一不匹配即拒绝并给出明确的错误提示。没有向后兼容的降级路径——版本锁定是安全的基石。一个声明与 `26.2` ABI 兼容的 Mili Loader 实例，不会因为某个 Mod 错误地声明了 `26.1` ABI 版本而对它放松检查。这种严格的版本锁定的另一面是它对早期开发状态的宽容：在 `0.x` 阶段，Loader 会自动将版本检查限制为忽略 patch 级别的差异，但这只是过渡期的政策——一旦进入 `1.0`，所有级别的差异都会被严格执行。

当前版本状态：

| 维度 | 值 | 含义 |
|---|---|---|
| `CURRENT_VERSION` | `0.1.0` | Loader 实现版本 |
| `ABI_VERSION` | `1` | Mod 契约版本（已进入稳定，整数形式） |
| `TARGET_JAVA` | `25` | 运行时最低 Java 版本 |
| `TARGET_MINECRAFT` | `26.2` | 唯一支持的 Minecraft 版本 |

详细版本策略与兼容性承诺见 [PLATFORM_VERSIONING.md](docs/PLATFORM_VERSIONING.md)。

## 详细文档

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — Runtime 内核与设计决策，包含 Scope、Scheduler、EventBus 的工作原理
- [MOD_DEVELOPMENT.md](docs/MOD_DEVELOPMENT.md) — Mod 开发指南，包含入口点声明、元数据配置、依赖声明
- [API_REFERENCE.md](docs/API_REFERENCE.md) — 完整 API 参考，包含所有公开接口与方法签名
- [PLATFORM_VERSIONING.md](docs/PLATFORM_VERSIONING.md) — 版本策略与三元组锁定机制
- [ABI.md](ABI.md) — ABI 契约与接口规范，明确哪些变更属于破坏性

## 许可证

（l2）
