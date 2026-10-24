# Mili Platform

Mili-loader 是一个从零编写的 Minecraft Mod 加载器，基于 Java 25，不克隆也不兼容 Fabric。它自带独立的 Runtime 内核（Scope / Scheduler / EventBus / Capability / ResourceManager），为 Mod 提供纯 Mili 原生 API，不依赖 Fabric，不使用 Mixin，也不依赖 LaunchWrapper。整个平台以运行时契约（ABI）为核心，所有 Mod 只面向契约编程， Loader 负责注入真实实现。Mili 的目标不是替代 Fabric，而是为 Minecraft 客户端 Mod 工程提供一个全新的、无历史包袱的基础设施选择。我们面向的是那些对现有加载器架构有深刻不满的 Mod 开发者——那些受够了字节码操控的脆弱性、Classpath 污染的微妙性、以及事件系统优先级战争的混乱的开发者。同样，我们也面向那些想要构建大型复杂 Mod 但又苦于基础设施不足的玩家端 Mod 作者——Mili 的结构化并发与能力系统让多 Mod 协作的工程维护成为可能。

## 核心特性

Mili 的 API 层完全不含 Minecraft 依赖，所有与游戏本体的交互通过独立的反射桥接层完成——这意味着 Mod 开发者永远不会无意中耦合到具体的 Minecraft 版本或内部类，也意味着 Minecraft 版本升级时无需重新编译已有的 Mod JAR。Scheduler 提供结构化并发原语，替代传统的线程手动管理，让异步任务的生命周期天然受控于 Scope，取消传播自动且确定。Scope 决定了一个 Mod 的可见资源边界：离开 Scope 即释放，不再有悬挂引用或内存泄漏。Capability 系统采用拒绝即默认的访问控制模型，Mod 必须显式声明自己能提供或消费的能力，运行时在匹配阶段校验，能力不匹配则拒绝加载而非静默失败。版本锁定严格执行三元组机制——平台版本、ABI 版本、Minecraft 版本三者必须完全一致才允许加载，不存在模糊的兼容区间，升级的显式性是其安全保证的一部分。每个 Mod 拥有独立的 ClassLoader，类加载严格隔离，Mod 之间的唯一通信通道是 ABI 接口与反射桥接，杜绝了类污染与循环依赖。

这份特性清单背后的统一哲学是：显式优于隐式，编译错误优于运行时错误，拒绝优于静默妥协。当某件事做错了的时候，Mili 倾向于在最早可能的时刻——加载期、编译期甚至设计期——将其暴露出来，而不是等待它在生产环境中某个不经意的角落爆开。这个哲学既是对 Mod 平台长期维护经验的总结，也是对 Minecraft 客户端 Mod 生态中"弱类型 + 强耦合"现状的有意识回应。

## 模块构成

Mili 由五个 Gradle 模块组成（与 `settings.gradle.kts` 的 `include` 一一对应）：

| 模块 | 职责 |
|---|---|
| `mili-abi` | 稳定契约接口，零运行时依赖，Mod 编译的唯一目标 |
| `mili-runtime` | Runtime 核心：Scope、Scheduler、EventBus、Capability、ResourceManager |
| `mili-loader` | 启动发现、依赖解析、类加载、生命周期调度、分发包 |
| `mili-minecraft-integration` | 反射桥接 + 平台自有字节码转换，负责与 Minecraft 26.2 客户端交互 |
| `mili-installer` | 独立安装器：从 Mojang 官方源拉取并校验游戏文件（只依赖 JDK） |

实际依赖方向（见各模块 `build.gradle.kts`）为 `runtime → abi`、`minecraft-integration → runtime, abi`、
`loader → runtime, minecraft-integration, abi, installer`；`installer` 不依赖任何其它模块 —— 它必须能用
单个 JDK 直接运行，因为它是「启动前」的工具。Minecraft 集成模块不反向引用 Loader，这样补丁发布时
只需重打集成模块 JAR。`mili-abi` 只对外暴露 `api` 依赖，下游无法通过传递依赖引入内部类型。

这套边界不是靠文档约定维持的：`mili-loader` 的 `ApiBoundaryTest` 会扫描源码里对
`org.loader.runtime.*` 的 import，只放行显式白名单（`runtime.kernel.Scope/Runtime`、
`runtime.mod.*`、`runtime.tick.TickContract` 等），越界即测试失败。

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

将 Loader JAR、Runtime JAR 与 Minecraft 集成 JAR 放入 `<loader-dir>/core/`，Mod JAR 放入 `<loader-dir>/mods/`，运行 Loader 即可启动。Loader 会自动扫描 Mod 目录、验证元数据、解析依赖图，并按拓扑顺序初始化每个 Mod。依赖图排序由 `org.loader.runtime.util.DependencySorter` 统一实现（三态 DFS）：缺失依赖单独报告，循环依赖在任何入口都会被拒绝并给出完整环链（`A -> B -> C -> A`）。启动过程的关键步骤都有 `[Mili]` 前缀的日志输出，平台日志另存于 `logs/mili-platform.log`。

开发模式下可以直接在 IDE 中以 `mili-loader` 的 `main`（`org.loader.loader.LoaderMain`）启动；该模块没有 `application` 插件，因此没有 `./gradlew :mili-loader:run` 这个任务。安装器有：`./gradlew :mili-installer:run --args="--dry-run --version 26.2"`。

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

反射桥接不依赖 Java Agent，也不需要 `-javaagent`/`premain` 或修改 JVM 启动参数：它对 Minecraft 内部类的访问全部经过 `org.loader.runtime.minecraft` 下的统一门面，访问点在启动时被校验存在性，失败会给出结构化错误而不是让 `NoSuchMethodError` 在运行期随机爆发。

**但「无 Agent」不等于「无字节码改写」。** 平台确实会把部分 Mojang 类改写后再交给游戏
（例如在 `MinecraftServer.tickServer` 的首尾注入 tick 回调、在客户端主界面 `init` 头部注入
标题界面钩子、替换创造栏标签布局的取值），实现位于 `mili-runtime` 的
`transform/engine` + `transform/asm`（ASM 9.9.1），由 `mili-loader` 的
`ClassTransformInterceptor` 在 `MinecraftClassLoader` 的 `findClass` 阶段挂接。
设计取舍与边界见 [ADR-0011](docs/decisions/0011-platform-owned-asm-transformation.md)
和 [TRANSFORMATION_ENGINE.md](docs/design/TRANSFORMATION_ENGINE.md)。

这条边界是有代价的：字节码改写天然脆弱于版本变更，因此平台把「改哪些类、改哪个方法、按什么描述符」
全部收敛到符号表 `MiliSymbol`，并用真实 Minecraft jar 在 CI 上逐条校验坐标存在性 ——
符号过期时构建立刻失败，而不是「转换器静默不注入、Mod 功能悄悄消失」。

结构化并发通过 Scheduler 与 Scope 提供，替代了裸线程手动管理。每个异步任务的生命周期天然有界，取消传播确定，不会留下悬挂的线程或泄漏的资源。Capability 系统提供了声明式、可组合的访问控制，能力不匹配则拒绝而非静默失败。这一切的组合使得 Mili 平台的 Mod 比传统加载器上的 Mod 更易于推理其行为边界。结构化并发不是一个时髦的术语——它是 Mili 平台区别于其他加载器的最核心技术差异。当传统的 Mod 作者在 `CompletableFuture` 的取消传播链上苦苦挣扎时，Mili 开发者可以通过 Scope 的上下文取消机制优雅地终止任务链，无需关心线程级的中断状态传播。

## 类加载与隔离模型

每个 Mod 拥有独立的 ClassLoader，类的解析遵循父委托模型，但委托目标经过精心控制：ABI 接口由平台 ClassLoader 提供，Minecraft 类由集成层 ClassLoader 提供，而 Mod 自定义类由自身 ClassLoader 解析。Mod A 无法直接引用 Mod B 的类，除非通过 ABI 接口显式共享。这个模型避免了"classpath 污染"——那种在旧加载器上常见的、因为类被意外共享而导致静默崩溃的问题。ClassLoader 隔离的另一个好处是，当某个 Mod 存在严重 Bug 导致 JVM 崩溃时，Loader 可以通过崩溃前日志追溯具体的 Mod ID，而不是在混合堆栈中费力猜测。

依赖声明在 Mod 元数据中完成，Loader 在构建依赖图时检测循环依赖并拒绝加载整个链 ——
排序与环检测由 `org.loader.runtime.util.DependencySorter`（三态 DFS）统一实现，
所有入口（`ModDiscovery` / `ModManager` / `DependencyResolver`）共用同一套语义，
环会被报成 `A -> B -> C -> A` 而不是被当成「已访问」。可选依赖（`required: false`）
不参与排序，缺失时只告警。

**版本冲突解析目前<b>没有</b>实现「最小版本选择」之类的求解器**：依赖条目里的
`versionRange` 只支持精确匹配与 `*`（其余范围语法按「不校验」处理），
版本不匹配会作为独立错误（与「缺失」「循环」分开）报告。多版本共存 →
见下方「同一个 Mod 不能同时存在两个版本」的约束。

ClassLoader 的命名空间规则确保 Mod 之间不会发生意外的类共享——两个不同的 Mod 即使声明了相同全限定名的类，也不会相互干扰，因为它们被独立的 ClassLoader 分别解析。唯一的例外是 ABI 接口类，它们由统一的平台 ClassLoader 提供，这是 ABI 优先设计的必然结果：所有 Mod 共享同一份 ABI 类定义。如果 ABI 接口发生变更（按照破坏性变更策略），所有依赖它的 Mod 需要重新编译——这正是 ABI 版本号语义所约束的范围。ClassLoader 隔离也带来了一些有趣的边缘情况：同一个 Mod 不能同时存在两个不同版本的实例，因为它们的 JAR 文件名相同会导致 Loader 拒绝扫描。如果某个 Mod 需要版本化的模块化能力（如一个"核心模块"和多个"功能模块"），它们应该共享同一个 Mod ID 并通过能力系统区分功能，而不是拆分出独立的 Mod JAR。

热加载与热卸载**目前没有实现**：仓库里既没有该功能的入口，也没有标记为实验性的 API。
`ModClassLoader` 关闭后其 `URLClassLoader` 句柄会释放（这是 Mod JAR 能被删除/替换的前提），
但「JVM 类卸载」并不因此保证发生 —— 只要还有强引用（静态字段、线程栈、缓存），类与其
ClassLoader 就仍然可达。所以当前语义是「重新启动游戏」，不是热替换。

## 生命周期与调度

Mod 的生命周期由 `LifecycleState` 状态机约束，实际状态序列是：
`DISCOVERED → RESOLVED → LOADED → INITIALIZED → REGISTERED → RUNNING → STOPPING → STOPPED`，
失败路径进入 `FAILED`（终态）。状态转换不合法会直接抛 `IllegalStateException`
（`Scope.transitionTo` 的 CAS + `canTransitionTo`），因此不存在「悄悄跳过某个阶段」。
**没有 `SUSPENDED`/`resume()`**：任一 Mod 的入口加载或 `initialize()` 抛异常都会中止整个启动
（`invokeModEntrypoints` 直接抛 `ModLoadError`，fail-fast），错误消息里带 Mod id、入口类名与错误码；
恢复方式是修好 Mod 后重启实例。这是刻意的：把失败 Mod 隔离掉继续跑，会让「部分功能静默不生效」
变成常态。每次转换都会通知 `ScopeListener`；监听器抛出的异常被隔离，不阻断转换本身。

`Scheduler` 是 Runtime 内唯一的异步执行入口，其契约是明确且可测量的：

| 维度 | 行为 |
|---|---|
| 排序 | `TaskPriority` **值越大越先执行**；同优先级按提交顺序 FIFO |
| 并发 | 常驻 `corePoolSize` 个线程，队列满时可扩到 `corePoolSize * 2` |
| 队列 | 有界，默认 16384（`Scheduler.DEFAULT_QUEUE_CAPACITY`，可在构造期指定） |
| 超限 | `submit` 抛 `SchedulerError`（显式失败，不静默丢弃、不在调用线程偷偷执行） |
| 关闭后提交 | 抛 `IllegalStateException` |
| 取消 | 未开始的任务从队列摘除；已开始的走协作取消（`future.cancel(true)`） |
| 指标 | `metrics()` 暴露 `queueDepth/queueCapacity/remainingCapacity/rejected/active` 与等待/执行分位数 |

`TaskPriority` **不映射**为线程优先级，也不触发超时策略 —— 它只决定出队顺序。
周期任务（`submitRepeating`）由独立的守护计时器线程触发，不经过优先级队列，
因此不受队列容量约束，`priority` 也不影响其执行顺序；`cancel()`、Scope 停止、
Scheduler 关闭都会释放该计时器线程。

**这个 Scheduler 不是并行 Tick 引擎。** Minecraft 的 tick 仍由单一驱动线程串行推进
（`TickEngine` + `TickContract`），Scheduler 负责与 tick 解耦的异步工作及其生命周期，
它不会把一次 tick 拆到多线程并行执行，也不改变游戏逻辑的线程亲和性。

不推荐用 Java 原生 `CompletableFuture` 提交长时间运行的任务 —— 它们不受 Scope 控制，
取消传播依赖显式链路，容易造成任务泄漏。所有异步逻辑应通过 `Scheduler.submit(...)` 管理。

## 事件传播

`EventBus` 提供类型安全的发布-订阅机制。订阅通过 `@EventListener` 注解声明，Loader 在初始化阶段自动扫描并注册处理器。`Subscription` 句柄提供取消注册的能力，配合 Scope 确保订阅不跨 Scope 存活。事件不支持冒泡或拦截——这是刻意的简化，避免 Mod 之间的隐式顺序耦合和优先级争斗。如果两个 Mod 需要协调，它们应该通过 Capability 接口显式声明，而不是靠事件顺序隐式约定。这种设计可能会让习惯了 Bukkit 插件体系的开发者感到不适应——Bukkit 有一个庞大的事件继承树和取消机制，但我们认为那套机制在过去十年里带来的维护成本远超其灵活性收益。Mili 的事件系统是"无小聪明"的——它只做了一件事：把事件从发布方传递到订阅方，并确保不丢失、不重复、不混乱。如果你需要更复杂的协调模式，这正是 Capability 系统存在的原因。

事件的类型安全在发布时被检验——`EventBus.publish(Event)` 保证只有匹配该事件类型的监听器会被调用。编译期泛型保证了发布方无法意外地发布错误类型的运行时对象。这对于 Mod 系统的健壮性至关重要——事件类型的错误往往在运行时才暴露，而此时已经造成了难以追溯的状态混乱。类型安全也带来了一个实际的好处：当 Loader 需要梳理事件监听链时，它可以基于泛型参数快速索引相关处理器，而不需要运行时类型检查。

处理器抛出异常不会终止事件传播链——Loader 会捕获并记录异常，然后继续调用下一个处理器。**当前没有**「按错误率熔断/冷却」的机制（仓库里不存在相关实现），因此一个持续抛错的处理器会持续产生日志，但不会拖垮整条链路的其它订阅者。事件系统同样没有优先级与冒泡：需要协调的两个 Mod 应通过 Capability 显式声明。

## 能力与权限

`CapabilityToken` 是能力声明与消费的基础凭证，由提供者注册、由消费者消费。能力匹配发生在加载期，如果消费者的需求无法被任何提供者的声明满足，则 Loader 拒绝加载该消费者 Mod。这个早期失败的哲学——在能验证的时候尽早验证——贯穿了整个 Mili 的设计。能力匹配不只检查 Token 是否存在，还检查 Token 类型的兼容性——`CapabilityToken<NetworkProvider>` 与 `CapabilityToken<RenderProvider>` 代表完全不同的语义，即使它们被错误地共享了同一个字符串标识，Loader 也能通过泛型参数类型区分。这个"类型 + 引用"的双重匹配机制比传统的服务注册模式（通常只依赖字符串键）更加安全，也更易于在规模化项目中治理。

`Permission` 与 `PermissionManager` 构成运行时的第二层访问控制：加载期校验能力匹配，运行时校验具体操作权限。权限可以在 Mod 的配置文件中声明，默认配置对所有 Mod 开放常用权限，而对高危操作（如网络访问、文件系统写入）要求显式声明。管理员可以通过覆盖默认配置收紧权限策略。权限检查发生在 API 调用层面，Mod 无法绕过——所有敏感操作都通过 `PermissionManager` 的门面进入系统。权限的审计日志会记录每次拒绝与通过事件，可通过全局配置的审计级别调整详细程度。审计日志默认关闭，开启后会带来可感知的 I/O 开销——在生产环境中建议仅开启 `DENY` 级别的审计，关闭 `ALLOW` 级别的审计。

能力匹配的语义基于"完全相等"而非"兼容或继承"——`CapabilityToken` 的比较使用引用相等性而非自定义比较器。这么设计的意图是让能力语义显式化：如果两个 Mod 希望它们的能力可互相替代，它们必须使用同一个 `CapabilityToken` 实例（通常来自共享 ABI 模块中的常量）。**没有别名/继承机制**（`aliasOf()` 不存在），也没有隐式推导 —— 这避免了"看似兼容但行为微妙不同"的尴尬情况。

## 资源管理

`Resource` 与 `ResourceManager` 负责 Mod 的资产访问，包括配置文件、语言文件、数据资源与原生库。每个 Mod 通过 `ModContext.getResourceManager()` 获取自己的资源管理器，资源访问范围严格限定在 Mod 自身的 JAR 范围内。跨 Mod 的资源共享必须通过 ABI 接口暴露，不允许直接文件系统访问其他 Mod 的资产。这个设计约束了 Mod 系统的安全边界——一个 Mod 无法盗用另一个 Mod 的创意资产，也无法因为读取了错误的文件而静默加载错误的内容。

资源加载遵循优先级规则：Mod 自身的资源优先，平台补丁次之，Minecraft 原生的最后。这个规则确保了 Mod 可以安全地覆盖任何级别的原生资源而不必担心循环依赖。资源解析在首次访问时惰性完成，而非在加载期一次性读取全部资源。**没有 `preload(...)` 这类预加载 API**：资源读取走 ClassLoader，每次打开流即读，平台不做整包缓存。

**没有文件变更监视器**（`ResourceWatcher` 不存在）：资源与配置都在启动时读取，运行期不监听文件变化。需要改配置时重启游戏。

## 反射桥接层

`mili-minecraft-integration` 是 Mili 与 Minecraft 26.2 之间的唯一边界。它通过
`org.loader.runtime.minecraft.reflect.Reflect` 这一个门面访问游戏类：`Reflect.gameClass(...)`、
`Reflect.method(owner, name, paramTypes...)`、`Reflect.staticField(...)` 等，解析结果缓存复用
（`clearCaches()` 可整体失效）。

**方法坐标不是「清单文件」，而是符号表代码 + CI 实测**：会被平台改写的目标方法的坐标集中在
`mili-abi` 的 `org.loader.api.transform.symbol.MiliSymbol`；根构建的
`verifyTransformationSymbols` 用真实 Minecraft jar 逐条比对 owner+name+descriptor
（只比名字会命中重载，生成调错方法的字节码）。本地没有 `test_client/26.2.jar` 时该任务 SKIPPED，
CI 上则必须通过。

**不会「输出错误信息并跳过桥接点」**：符号缺失是构建失败，转换器 `matches` 命中目标却找不到方法时
抛 `TargetNotFoundException`。静默跳过会让「游戏照常跑、Mod 功能消失、日志无异常」，这是最坏的失效形态。

反射门面不做业务决策 —— 它只负责「按坐标取到成员并调用」。当补丁导致签名变化时，修改集中在
`MiliSymbol` 与各转换器的坐标常量上，业务逻辑不受影响。

## 错误与异常

`org.loader.api.exception.MiliException` 是契约层异常的基类。加载期错误统一用
`org.loader.runtime.error.ModLoadError`（带 `errorCode()`，如 `MOD_PLATFORM_MISSING`、
`MOD_DEPENDENCY_CYCLE`、`MOD_ENTRYPOINT_INVALID`、`MOD_ENTRYPOINT_FAILED`），
运行时错误继承 `ModularRuntimeException`。

**失败语义是 fail-fast，不是「隔离坏 Mod 继续跑」**：`LoaderMain.invokeModEntrypoints`
在入口类缺失、未实现 `org.loader.api.Mod`、抽象类、构造或 `initialize()` 抛异常时，
一律抛 `ModLoadError` 中止启动。错误消息包含 Mod id、入口类全名、错误码与原始异常 ——
目标是让根因一眼可见，而不是让一个半初始化的 Mod 留在进程里。

因此**没有 `SUSPENDED` 状态、没有 `resume()`、没有重试次数限制**：这些机制在代码里不存在。
「一个 Mod 出错不影响其他 Mod」在 Mili 里不是靠运行时隔离实现的，而是靠启动期严格校验 +
清晰的错误码，让问题在启动时就暴露、修好再启动。

启动失败时没有 `startup-report.json`：诊断信息全部走日志 —— 控制台/`logs/latest.log` 里的 `[Mili]` 行，以及平台自有的 `logs/mili-platform.log`（含 ClassLoader 拓扑、重复类、被拒绝的访问）。安装器会写 `logs/install-report.json`（结构化 steps/issues），那是安装阶段的报告，不是启动报告。

## 版本策略

版本锁定遵循严格三元组机制，三者必须一致：Loader 实现版本（`CURRENT_VERSION`）、ABI 契约版本（`ABI_VERSION`）、目标 Minecraft 版本（`TARGET_MINECRAFT`）。Loader 在加载每个 Mod 时会校验三元组，任一不匹配即拒绝并给出明确的错误码。

**没有任何「0.x 阶段忽略 patch 差异」的宽容**：`ModManifest.validateVersionBinding()` 对三个字段
逐字符/逐整数比较，`platform="0.1.0"` 与 `"0.1.1"` 视为不匹配；未声明 `mili` 对象的 Mod
直接以 `MOD_PLATFORM_MISSING` 拒绝。这是当前唯一的行为，不存在隐藏的 dev/prod 分支。

当前版本状态：

| 维度 | 值 | 含义 |
|---|---|---|
| `CURRENT_VERSION` | `0.1.0` | Loader 实现版本 |
| `ABI_VERSION` | `1` | Mod 契约版本（已进入稳定，整数形式） |
| `TARGET_JAVA` | `25` | 运行时最低 Java 版本 |
| `TARGET_MINECRAFT` | `26.2` | 唯一支持的 Minecraft 版本 |

详细版本策略与兼容性承诺见 [PLATFORM_VERSIONING.md](docs/PLATFORM_VERSIONING.md)。

## 详细文档

- [docs/architecture/CURRENT.md](docs/architecture/CURRENT.md) — 当前架构总览（Scope、Scheduler、EventBus 的实际形态）
- [MOD_DEVELOPMENT.md](docs/MOD_DEVELOPMENT.md) — Mod 开发指南，包含入口点声明、元数据配置、依赖声明
- [ABI.md](docs/ABI.md) — 契约接口清单（源码即参考：`mili-abi/src/main/java/org/loader/api/`）
- [PLATFORM_VERSIONING.md](docs/PLATFORM_VERSIONING.md) — 版本策略与三元组锁定机制
- [ABI.md](ABI.md) — ABI 契约与接口规范，明确哪些变更属于破坏性

## 文档状态

本文档在 26.2 修复轮次中按「只描述已验证行为」逐段核对过实现，并修正了多处与代码冲突的断言
（模块数量、字节码改写、Scheduler 优先级/背压、事件熔断、资源监视器、`startup-report.json`、
`SUSPENDED` 状态等）。核对过程、证据与尚未覆盖的部分见
[docs/repair/MI_LOADER_REPAIR_REPORT.md](docs/repair/MI_LOADER_REPAIR_REPORT.md)。

## 许可证

（l2）
