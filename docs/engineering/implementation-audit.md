# Mi-loader 实现现状审计（阶段 0 产出）

- 审计人：AI 工程执行 Agent
- 日期：2026-10-24
- 基线：分支 `26.2`，HEAD `03105e0`（feat: 补全 Mod 方块状态缓存，修复渲染崩溃问题）
- 审计方式：逐文件阅读 + 三路并行源码探查，全部结论附文件路径与符号证据；未验证处明确标注。

---

## 0.1 环境记录

| 项 | 值 | 证据/来源 |
|---|---|---|
| 工作区 | `E:\loader`，分支 `26.2` 跟踪 `origin/26.2` | `git status --short --branch` |
| 远端 | `https://github.com/MiliMCL/Mi-loader.git` | `git remote -v` |
| 工作区状态 | **存在用户未提交改动，本审计与其后所有工作均不覆盖、不 stash、不重置** | 见下"用户改动清单" |
| Gradle | Wrapper 9.7.1（腾讯镜像 `mirrors.cloud.tencent.com/gradle/gradle-9.7.1-bin.zip`） | `gradle/wrapper/gradle-wrapper.properties` |
| Java 工具链 | Java 25（`javaVersion=25`）；本机 JDK25 位于 `C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/` | `gradle.properties`；用户既定记录 |
| 内存约束 | 本机 8GB，`org.gradle.jvmargs=-Xmx512m`，`maxParallelForks=1`，测试默认 2 分钟/例 | `gradle.properties`、根 `build.gradle.kts:65-79` |
| CI | `.github/workflows/ci.yml`（MC 流水线 + build/test + installer smoke + 模块边界校验）、`release.yml` | 文件存在 |
| MC 输入 | 本地 `test_client/26.2.jar` 存在；CI 经 `MINECRAFT_ARTIFACT` 定位；仓库规则禁止提交/分发 | `ci.yml`、根 `build.gradle.kts:195` |
| 模块图 | `mili-abi ← mili-runtime ← mili-loader → {mili-installer, mili-minecraft-integration}`；`mili-minecraft-integration → {mili-runtime, mili-abi}`（**注意：README 声称集成模块只依赖 ABI，与实际不符**） | `settings.gradle.kts`、`mili-minecraft-integration/build.gradle.kts:34-36` |
| 版本三元组 | platform `0.1.0` / abi `1` / minecraft `26.2` / asm `9.9.1`（asm-analysis 锁 9.8 离线兼容） | `gradle.properties` |

**用户未提交改动清单（保护对象，一律绕开）**：

- 已修改：`docs/performance/STABILITY.md`、`mili-loader/src/main/java/org/loader/loader/LoaderMain.java`（+188/-2，启动时间线/审计接线）、`mili-loader/src/test/java/org/loader/loader/ApiBoundaryTest.java`、`mili-runtime/src/main/java/org/loader/runtime/tick/TickContract.java`、`TickEngine.java`
- 未跟踪（用户 WIP 新文件）：`docs/performance/LIFECYCLE_BUDGET.md`、`mili-runtime/src/main/java/org/loader/runtime/diagnostics/{LifecycleAudit,ModAttribution,ModOwners,ShutdownAudit,StartupTimeline,TickWatchdog}.java` 及对应 4 个测试类、`.designkit-history-delivery/`

本计划的后续工作对 `LoaderMain.java` 仅做**最小增量接线**（A3 失败路径 1 处），其余一律新增文件，不与用户改动交叠。

## 0.2 分项审计

### 0.2.1 Mod 发现、manifest、版本绑定、依赖排序、Capability

- 发现入口：`mili-loader/.../discovery/ModDiscovery.java`。`scan(LoaderConfig)` 扫 `mods/` 下 `.jar/.zip`（`ZipFile` 找 `mod.json` 或 `META-INF/mod.json`，L83-96）与子目录（`mod.json`，L99-110）。
- **已知静默失效（doctor 需显式化）**：坏 JAR IOException 静默跳过（L96）、坏目录静默跳过（L108）、manifest JSON 解析失败抛 `ModLoadError(MOD_MANIFEST_INVALID)`（RuntimeException，会中断整个扫描）、required 依赖缺失静默跳过（`resolveRecursive` L212）、循环依赖不检测（`visited` 集合 L209-210）。
- manifest 字段（代码为准，`parseManifest` L112-139）：`id`（必填）、`name`（缺省=id）、`version`（缺省 `1.0.0`）、`entrypoint`（缺省 `""`）、`author`、`dependencies[{modId, required}]`（**versionRange 恒填 `"*"`，声明中的版本约束被丢弃**）、`mili{platform,abi,minecraft}`。`capabilities`/`modules`/`description` 恒为空 —— **mod.json 中的 capabilities 字段不被解析**。
- 版本绑定校验：`mili-runtime/.../mod/ModManifest.java` `validateVersionBinding()`（L65-79）——未绑定/任一字段不等即拒绝，返回密封 `ValidationResult`；消费方 `LoaderMain.validateVersionBinding`（L848-869）。
- Capability 校验：`mili-runtime/.../kernel/CapabilityManager.java`（`canGrant` 按 `isAssignableFrom`）与 `Scope.grantCapability/getCapability`；**加载期 capability 声明与消费的匹配校验在发现流水线中不存在**（manifest 根本不带 capabilities）——README "加载期校验能力匹配" 的描述超前于实现。
- 依赖排序：`ModDiscovery.resolveDependencies` DFS 后序拓扑；`kernel/DependencyResolver.java` 另有带环检测（`wouldCreateCycle` 前置拒环）的实现但未接入 Mod 发现链。

### 0.2.2 ClassLoader 拓扑

- 三层：`MinecraftClassLoader`（唯一允许定义 `net.minecraft.*`，child-first 特例 `com.sun.jna`，转换插点在 `findClass`）→ `ModClassLoader`（每 Mod 一个，`extends URLClassLoader`，**已调用 `registerAsParallelCapable()`**）→ 平台 parent。
- 可见性契约唯一源：`classloader/ClassVisibility.java`（`PARENT_FIRST/SELF_FIRST/FORBIDDEN`；白名单 `org.loader.api.` 等，黑名单 `org.loader.loader.`、runtime 内部包、`org.loader.installer.` 等）。
- 管理器：`ModClassLoaderManager`（create/get/all/close 逆序；`detectDuplicateClasses` 仅统计实际加载的 SELF_FIRST 类；`diagnostics()` 文本汇总）。代码源定位 `locateModSources`：显式 source → `<modsDir>/<modId>.jar` → `<modsDir>/<modId>/`（含 build/classes 与 build/libs）。
- 并行加载：parallelCapable 已启用；锁策略继承 URLClassLoader 默认（按类名锁）。`docs/history/execution/THREAD_MODEL.md` 有历史分析。

### 0.2.3 ASM 转换入口、符号表、缓存

- 管道：`loader/transform/ClassTransformInterceptor`（`org/loader/` 前缀短路）→ `mili-runtime/.../transform/engine/TransformerPipeline`（`TransformerRegistry`/`TransformerOrdering`/`AnnotationTransformerScanner`）→ `transform/asm/`（MiliClassTransformer、InjectionMethodVisitor）→ `transform/verify/BytecodeVerifier`。
- 转换器（真实挂点）：`mili-minecraft-integration/.../transform/`：`MiliTickTransformer`（`MinecraftServer#tickServer(BooleanSupplier)V`）、`MiliClientTickTransformer`（`Minecraft#tick()V`）、`MiliTitleScreenTransformer`（`TitleScreen#init()V`）、`MiliClientBrandTransformer`（MODIFY_RETURN `ClientBrandRetriever#getClientModName()`）、`MiliCreativeTabLayoutTransformer`（创意栏布局 ASM 改写）。
- 符号表：`mili-abi/.../transform/symbol/MiliSymbol.java`（8 个 `TargetMethod` 静态常量 + `MINECRAFT_VERSION="26.2"`）；CI 任务 `verifyTransformationSymbols`（根 `build.gradle.kts:182-259`）用真实 MC jar 逐条比对 owner+name+descriptor，jar 缺失时 SKIPPED。`tools/SymbolVerifier.java` 为校验器。
- 缓存：`mili-runtime/.../transform/cache/TransformationCache.java` —— **仅内存**（`ConcurrentHashMap<CacheKey, byte[]>`，key=ClassLoader 引用相等+类名，有 hit/miss 统计与测试）。**没有磁盘持久化缓存**，key 不绑定 MC JAR 哈希/转换器版本/符号表指纹（B1 的范围）。

### 0.2.4 EventBus

- 实现：`mili-runtime/.../service/EventBus.java`。监听器存储为**不可变数组快照**（`Map<Class<?>, EventListener<?>[]>`，写路径 `compute` 复制数组，发布路径零分配）——B3 设想的快照机制**已存在**。
- 异常隔离：逐 listener try/catch 静默吞（L136-142）。**README 描述的"频率熔断"（百次十错→30 秒冷却→加倍）在代码中不存在** —— 文档超前于实现（A7 需修正）。
- 生命周期：`close()` CAS 后关 dispatchExecutor、清监听器；`postAsync` 惰性 2 线程 daemon 池。
- 精确类型分发（无层次匹配）；`ModContext`（runtime）内每 Mod 一个 EventBus；ABI 侧 `api/event/EventBus` 为契约门面。

### 0.2.5 Scheduler 与计时

- `mili-runtime/.../scheduler/Scheduler.java`：`ThreadPoolExecutor` + 自研 `PriorityWorkQueue`（优先级 value 升序 + createdAtNanos FIFO）；`submit/submitRepeating/submitChild/cancel/cancelAll`；`SchedulerMetrics` 含 p50/p95/p99（样本封顶 10000）。重复任务=每任务一个 daemon timer。
- 时间语义：**真实墙钟 `System.nanoTime()`**，无虚拟时间；`ScheduledTask.deadlineNanos` 恒 0 未启用。
- Tick：`mili-runtime/.../tick/TickEngine.java`（用户 WIP 修改中）——单线程驱动模型（driverThread 绑定，跨线程抛异常）、7 阶段（TICK_START→…→TICK_END）、50ms tick 周期契约（`TickContract.DEFAULT_TICK_PERIOD_MS`）、指标环形缓冲 200、`ModAttribution` 按 ClassLoader→`ModOwners` 归因、`TickWatchdog` 看门狗。**无可步进的虚拟 tick API**（`TickContract.createWithId` 供测试构造 tickId）。

### 0.2.6 Scope 终止与资源释放

- `kernel/Scope.java`：9 态 `LifecycleState` 严格单向状态机（`canTransitionTo`）；`shutdown()` 顺序=子 Scope→撤销能力→**逆序**释放资源（失败收集 suppressed 抛 `ScopeShutdownException`）；listener 异常吞掉。`ResourceRegistry.close()` 同样逆序。`kernel/Runtime.close()` 三层 try/finally。
- 审计（用户 WIP 新增，已并入工作区基线）：`diagnostics/ShutdownAudit`（两快照差集回收审计）、`LifecycleAudit.dumpTo`（失败落盘 `logs/mili-audit.log`）。

### 0.2.7 权限与审计日志

- `kernel/PermissionManager.java`：`Map<String, PermissionPolicy>` + `hasPermission/checkPermission`（失败抛 `java.lang.SecurityException`）。**无审计日志写入实现**；`security/AuditLog.java` 存在（`security/AuditLogTest` 有测试）但权限决策未接入。README 描述的"权限审计日志记录每次拒绝与通过"超前于实现。
- JVM 能力风险分级：`jvm/JvmCapabilityRegistry`（HIGH/CRITICAL 默认 DENY）+ `JvmCapabilityAuditor`（挂 ScopeListener 审计）。

### 0.2.8 反射桥接

- `mili-minecraft-integration/.../reflect/Reflect.java`：每个反射点在游戏类存在时解析一次并缓存（MethodHandle），失败抛 `BridgeMismatchException`（"绑定层唯一的预期内失败类型"）。
- 声明式坐标源=`MiliSymbol`（见 0.2.3）；CI 用真实 jar 校验符号存在性。`ReflectiveWorldView`/`ReflectiveBlockEntityView` 为世界桥接。

### 0.2.9 客户端挂点与渲染边界（C0 审计结论）

已确认的真实挂点（全部经 MiliSymbol + CI 校验或动态生成）：

| 挂点 | 机制 | 文件 |
|---|---|---|
| 服务端 tick | 字节码注入 `MinecraftServer#tickServer(BooleanSupplier)V` 方法出口 | `MiliTickTransformer.java` |
| 客户端 tick | 注入 `Minecraft#tick()V` | `MiliClientTickTransformer.java` |
| 标题界面初始化 | 注入 `TitleScreen#init()V`；`TitleScreenDispatch.onTitleScreenInit` 反射安装 "Mods" 按钮 | `MiliTitleScreenTransformer.java`、`TitleScreenDispatch.java` |
| 客户端品牌名 | MODIFY_RETURN `ClientBrandRetriever#getClientModName()` → `"Mili-loader"` | `MiliClientBrandTransformer.java` |
| 创意栏布局 | ASM MethodVisitor 改写（`ReplaceGetTabX`/`ClampRedirect`） | `MiliCreativeTabLayoutTransformer.java` |
| 自定义 Screen | 运行时生成 `MiliScreenHost extends Screen`（tick/isPauseScreen/onClose 转发） | `MiliScreenHostGenerator.java`、`ScreenHostDispatch.java` |
| Mod 列表界面 | 动态生成 `MiliModListScreen`（鼠标滚动/键盘/搜索） | `MiliModListScreenGenerator.java` |
| 按键 | 直改 `Options.keyMappings` 数组 + tick 轮询 | `KeyBindingDispatch.java` |
| 帧采样 | 反射读 `Minecraft.getFrameTimeNs()`（原版 fps/frameTimeNs） | `ClientFrameSampler.java` |

**不存在的挂点**：HUD/InGameHud（grep 零命中）、Toast、聊天栏、镜头、世界内飘字、帧循环（render tick）直接钩子。C1 性能叠加层的"显示"面当前只能依托已有 Screen 体系；帧数据源 `ClientFrameSampler` 已可用。C3 的 Toast/滚动/转场等效果需要新增挂点（ADR + 符号表扩展），本期不做。

### 0.2.10 Installer、启动脚本、模板、文档

- Installer：`mili-installer/.../InstallerMain.java`（`--game-dir/--version/--dry-run/--skip-assets/--skip-libraries/--asset-threads`）；`InstallOrchestrator` 写 `<gameDir>/logs/install-report.json`（schema：`minecraft/javaRequired/platform/clientJar/librariesApplied/.../ok/warnings`）。`VerifiedDownloader`（SHA-1 流式 + `.part` 原子替换）。
- 启动脚本：`mili-loader/distribution/bin/mili-loader.bat`（布局：`<dist>/{bin,core,mods,game}`；bat 消费 `core/mili-*.jar`，Java 侧从 `args[0]` 取 gameDir，`--mili-mods` 指定 mods）。`distTar/distZip` 布局由 `mili-loader/build.gradle.kts` `configureDistContents`（L337-355）生成。**README 提到的 `:mili-loader:run` 任务不存在**。
- 模板工程五套并存且不一致：`mod-template/`（**过期**：import runtime 内部包、`compileOnly` 指向不存在的 `minecraft-runtime-0.1.0-SNAPSHOT.jar`）、`example-mod/`（纯 `org.loader.api.*`，无构建脚本）、`testmod/`（面向 runtime 内部 API + `BadMod` 故意失败样本）、`demo-mod/`（miliaurora，build.sh+javac，全 ABI 能力演示）、`demo-mod2/`（mili_companion，多 Mod 共存演示）。A6 的权威模板应以 `demo-mod` 的 API 用法为准重新生成。
- 文档：`docs/` 下 architecture/decisions(11 ADR)/design/history/minecraft-26.x/performance 六组；`docs/engineering/` 不存在；`GOVERNANCE.md`/`CONTRIBUTING.md` 不存在。**README 死链 3 处**（L171 `docs/ARCHITECTURE.md`、L173 `docs/API_REFERENCE.md`、L175 `ABI.md`——实际在 `docs/history/` 与 `docs/ABI.md`）。README 与实现不符处：熔断器（无）、权限审计日志（未接入）、`startup-report.json`（无实现）、"集成模块只依赖 ABI"（实际还依赖 runtime）、`:mili-loader:run`（无此任务）、Capability 加载期校验（manifest 不携带）。

### 0.2.11 错误码、结构化报告、日志、Crash

- 错误体系：`mili-runtime/.../error/`（16 类）。基类 `ModularRuntimeException` + `ErrorContext`（component/owner/operation/state/cause/retryable/details，Builder，`toDiagnosticString()` 中文诊断）。12 个叶子错误类多数为纯透传；`ModLoadError` 携带 `errorCode`。
- **实际使用的错误码全集（8 个）**：`MOD_MANIFEST_INVALID`、`MOD_ENTRYPOINT_INVALID`、`MOD_ENTRYPOINT_FAILED`、`MOD_PLATFORM_MISSING`、`MOD_PLATFORM_MISMATCH`、`MOD_ABI_MISMATCH`、`MOD_MINECRAFT_MISMATCH`、`MOD_UNKNOWN`（兜底）。无 `MLI-xxxx` 数字码体系。
- 诊断消息格式：`LoaderMain.diagnostic` → `"[Mod: <id>] <detail>\n  ErrorCode: <code>\n  ModFile: <name> v<version>"`；`ModDiscovery.diagnostic` → `"[ModSource: <文件名>] <detail>\n  ErrorCode: <code>"`。
- 日志：`PlatformLog`（控制台 + `logs/mili-platform.log`，行格式 `[HH:mm:ss] [LEVEL] [Mili] <msg>`）。
- 结构化报告：**`startup-report.json` 无任何实现**（README L62/L152 承诺）。现成素材：`StartupTimeline`（phases/render/totalNanos）、`LifecycleAudit.dumpTo`（失败墓碑 `logs/mili-audit.log`）、`ShutdownAudit.report`、`ModClassLoaderManager.diagnostics()`。`ErrorContext` 的 Javadoc 引用了不存在的 `MiliError` 类；`ModLoadError` Javadoc 引用了不存在的 `ERROR_MODEL.md` —— 两处文档漂移。

## 0.3 基线（状态：受"仅收尾验证一次"策略约束，部分延后）

按用户授权的执行策略（中途不运行本地 Java 进程，收尾时执行一次完整构建+测试），基线分两半：

**已完成的基线证据收集**：

- 构建体系：根 `build.gradle.kts`（verifyVersionConstants/verifyTransformationSymbols 挂 check；JUnit 5.11.3；2min/测试超时）；`mili-loader/build.gradle.kts`（verifyPlatformJar、launcherScriptCheck、distributionBoundaryCheck、releaseArtifacts、distTar/distZip）。
- CI 基线（可视为既有绿灯证据来源）：`ci.yml` 的 build-and-test job = `compileJava compileTestJava → test → :mili-loader:shadowJar → platform.json 校验`。
- 测试资产现状：mili-runtime 38 个测试类、mili-loader 14 个、mili-abi 8 个、mili-minecraft-integration 13 个、mili-installer 4 个（含用户 WIP 的 4 个 diagnostics 测试）。

**延后到收尾验证的基线命令**（届时真实结果写入 `agent-final-report.md`）：

```
JAVA_HOME=C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2
./gradlew compileJava compileTestJava --console=plain --no-daemon   # 编译门禁
./gradlew test --console=plain --no-daemon                          # 全量单测
./gradlew :mili-loader:shadowJar --console=plain --no-daemon        # fat JAR
```

微基准（B0）按 MD 要求只对现存实现建立：EventBus 派发、Scheduler 提交/完成、PermissionManager 检查。遵循仓库既有做法（`performance.HotPathBenchmarkTest` 的纯 Java 预算式基准，无 JMH 依赖），不引入新依赖以免破坏离线构建。GPU/图形场景无 CI 机器，只保留 `ClientFrameSampler` 本地脚本路径，不设硬门禁。

## 已知风险与未知

1. **未知**：`TickEngine`/`TickContract`/`LoaderMain` 处于用户活跃修改中，本审计基于工作区版本快照（2026-10-24 13:42）；后续接线以当时工作区内容为准。
2. **风险**：README 多处承诺超前于实现（见 0.2.10），按 MD 第 10 节要求在 A7 中修正为"已验证/实验/未实现"三态。
3. **风险**：`ModDiscovery` 的 manifest 解析失败会中断整个扫描（RuntimeException 逃逸），多 Mod 目录中一个坏 manifest 会掩盖其余 Mod —— doctor 需独立容错扫描，loader 主流程的修复超出本期范围（记录为后续建议）。
4. **事实矛盾**：`mili-minecraft-integration/build.gradle.kts` 实际依赖 `:mili-runtime`，与 README "只依赖 ABI" 矛盾 —— 修正 README 而非构建（依赖是真实的：Screen 桥接使用 runtime 类型）。
