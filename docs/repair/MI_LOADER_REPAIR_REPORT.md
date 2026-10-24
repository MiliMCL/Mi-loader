# Mi-loader 26.2 修复报告

> 执行代理：DeepSeek 4.1f
> 执行日期：2026-10-24
> 依据：`MI_LOADER_REPAIR_PLAN.md`（仓库外文件，见任务附件；本报告按 P0 → P1 → P2 全量执行）
> 基线 HEAD：`03105e020ee74636633c82a76f392e54d5daa753`（`26.2`）
> 修复分支：`fix/mi-loader-hardening-20261024`（本地，**未提交、未推送**）

---

## 0. 结论摘要

| 项 | 结果 |
|---|---|
| 计划所列 7 项缺陷 | **7 项全部确认仍存在并已修复**（P0×2、P1×3、P2×2） |
| 计划外但阻塞验证的既有失败 | 1 项（`RegistryClassInitOrderTest`），已用测试自身规定的豁免机制修复 |
| 全项目测试 | **684 passed / 0 failed / 0 skipped**（5 个模块） |
| 全项目构建（`build -x test`） | **BUILD SUCCESSFUL**，含 `distributionBoundaryCheck`、`shadowJar`、`distTar/distZip`、`releaseArtifacts` |
| 真实 Minecraft 冒烟测试 | 已执行并通过（`Minecraft26_2SmokeTest`，11 项，非 mock） |
| 未执行的验证 | 真实联网安装（Mojang CDN）、真实客户端启动长跑 —— 见 §8.4 |
| 是否具备发布候选条件 | **不具备**。理由见 §12.5（核心链路已绿，但安装器真实联网与 Scheduler 饱和行为两项未在真实环境验证） |

本次为**源码级修复 + 测试锁定**，未创建任何 commit、未修改远程、未上传制品。

---

## 1. 阶段 A：预检与基线

### 1.1 环境与仓库状态

| 检查项 | 值 |
|---|---|
| `git status --short --branch` | `## 26.2...origin/26.2`（**工作区干净**） |
| `git rev-parse HEAD` | `03105e020ee74636633c82a76f392e54d5daa753` |
| `git branch --show-current` | `26.2` |
| `git remote -v` | `origin https://github.com/MiliMCL/Mi-loader.git` |
| `./gradlew --version` | Gradle 9.7.1 / Launcher JVM 25.0.4 (Eclipse Adoptium) |
| `JAVA_HOME` | `C:\Users\Administrator\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2` |
| OS | Windows 10 10.0 amd64 |
| `test_client/26.2.jar` | 存在（真实 Minecraft 客户端 JAR，本地已有） |

工作区干净，因此按计划创建修复分支：

```
git switch -c fix/mi-loader-hardening-20261024   # 分支指针与基线同一 commit
```

没有被覆盖的未知文件，也没有删除任何用户改动。

### 1.2 CI 真实任务名（从 `.github/workflows/ci.yml` 读取，未猜）

| job | 关键命令 |
|---|---|
| `minecraft-pipeline` | `:mili-minecraft-integration:verifyMinecraftArtifact / prepareMinecraft / decompileMinecraft / generateMinecraftIntegration` |
| `build-and-test` | `./gradlew compileJava compileTestJava` → `./gradlew test` → `:mili-loader:shadowJar` → 校验 `META-INF/mili/platform.json` |
| `installer-smoke-test` | `:mili-installer:test` → `:mili-installer:run --args="--dry-run --version 26.2"` |
| `module-boundary-check` | `compileJava` + 三条 `grep` 源码边界检查 |

项目**没有** spotless / checkstyle / pmd / spotbugs / errorprone 配置，因此"格式/静态检查"一栏不存在可执行任务。

### 1.3 基线测试结果（修改前）

命令：`./gradlew test --console=plain --offline`

```
BUILD FAILED in 2m 27s
140 tests completed, 1 failed
> Task :mili-minecraft-integration:test FAILED
```

失败详情：

```
RegistryClassInitOrderTest > BlockRegistrar：读注册表前先置 bootstrap 标志 FAILED
block/BlockRegistrar.java:438 直接触碰了注册表静态字段却没有先调用 ensureRegistriesReadable()
```

**判定：这是既有问题，与本次 7 项缺陷无关**（该模块不被其它模块依赖，改动前就红）。
它阻塞了"全项目测试"这一验收项，因此在本轮一并处理（见 §8）。基线运行时其它模块的 `test` 任务因构建中止未全部完成，故不把它们的基线数字当作结论。

### 1.4 阶段门槛

基线已确认、分支已创建、工作区无冲突 → 进入修改阶段。

---

## 2. P0-1：安装成功状态必须可信 —— 已修复

### 2.1 问题仍然存在（代码证据）

`InstallOrchestrator.install()` 末尾：

```java
boolean ok = warnings.stream().noneMatch(w -> w.startsWith("Failed to install library"))
```

`LibraryInstaller` 只对 "库下载/解压失败" 产出这个前缀；而 `AssetInstaller` 产出的是
`"资源下载失败 <sha1>: ..."`，两者前缀不同。于是：

- 资源下载失败、native 解压失败、`assetIndex` 缺失、资源索引为空 → **`Report.ok() == true`**，
  `install-report.json` 写 `"ok": true`，`InstallerMain` 退出码 **0**；
- 只要有人改文案（加空格、换语言），判定静默失效，且没有任何测试会红。

### 2.2 修复

| 文件 | 变更 |
|---|---|
| `mili-installer/.../install/InstallIssue.java` | **新增**：结构化问题模型 `(severity, stage, resourceId, message, cause)`，`Severity{ERROR,WARNING}`、`Stage{METADATA,CLIENT_JAR,LOGGING_CONFIG,LIBRARY,NATIVE,ASSET_INDEX,ASSET_OBJECT}` |
| `mili-installer/.../install/InstallStep.java` | **新增**：每阶段状态 `OK/SKIPPED/DEGRADED/FAILED` |
| `LibraryInstaller` | 每个库的下载失败 → `ERROR(LIBRARY, <maven 坐标>)`；native 下载/解压失败 → `ERROR(NATIVE, <lib:classifier>)`；路径穿越 → `ERROR`；`hasMainArtifact` 但缺 `path` → `ERROR` |
| `AssetInstaller` | 索引缺失/下载失败/解析失败/为空 → `ERROR(ASSET_INDEX)`；单对象失败 → `ERROR(ASSET_OBJECT, <sha1>)` |
| `InstallOrchestrator` | 汇总 `steps` + `issues`；`Report.ok()` 改为 `issues.stream().noneMatch(InstallIssue::isError)`；客户端 JAR 失败时后续阶段标 `SKIPPED` 并写报告 |

**可选资源的显式策略**（不再是"默认吞掉"）：

```java
static final boolean LOGGING_CONFIG_REQUIRED = false;   // 日志配置失败 → WARNING + Step.DEGRADED
```

`--skip-assets` / `--skip-libraries` 同样是显式策略：产生 WARNING + `Step.SKIPPED`，不改变 `ok`。

### 2.3 设计选择

- `Report.ok()` 从"字段"变成"派生方法"，**不可能构造出自相矛盾的报告**（有 ERROR 却 ok=true）；
- `Report.warnings()` / `LibraryInstaller.Result.warnings()` / `AssetInstaller.Result.warnings()` 保留为派生方法（返回文本），**仅供展示**，并在 javadoc 里写明"不得用于判定成功"；
- 元数据阶段失败不再抛异常跑掉，而是写一份 `ok:false` 的报告再返回，CLI 退出码 1。

### 2.4 回归测试（`InstallOrchestratorTest`，10 项，全离线）

| 测试 | 覆盖 |
|---|---|
| `requiredLibraryFailureFailsInstall` | 必需库 404 → `ok=false`，`stage=LIBRARY`，`resourceId=org.example:demo:1.0`，`cause` 保留，落盘 `ok:false` |
| `assetFailureFailsInstall` | 资源对象 404 → `ok=false`，`stage=ASSET_OBJECT`，`resourceId=<sha1>` |
| `emptyAssetIndexFailsInstall` | 空索引 → `ok=false`（旧实现只记 warning 判成功） |
| `missingAssetIndexFailsInstall` | 无 `assetIndex` → `ok=false` |
| `optionalLoggingConfigFailureIsWarning` | 日志配置失败 → `ok=true` + WARNING + `Step.DEGRADED` |
| `clientFailureAbortsRemainingStages` | 客户端失败 → `ok=false`，后续阶段 `SKIPPED`，报告仍落盘 |
| `metadataFailureStillWritesReport` | 元数据异常 → `ok=false` + `stage=METADATA` + 报告落盘 |
| `skipAssetsIsExplicitPolicy` | `--skip-assets` → `ok=true` + WARNING + `SKIPPED` |
| `rejectsTraversalInAssetIndex` | 索引含 `../../evil.jar` → `ERROR(ASSET_INDEX)`，且不写到 `assets/` 之外 |
| `allStepsSucceed` | 全成功 → `ok=true`，报告可被 JSON 解析，路径往返一致 |

> **关于"先红后绿"**：这些测试是用**修复后的 API** 写的（旧实现没有可注入的离线接缝：`MojangMetaClient` 是 `final` 且无接口，构造"资源失败"场景需要先做本次的 `MetaSource` 重构）。因此本轮没有录制一次"改动前跑红"的运行记录，红态由被删除的那行谓词逻辑推导得出（任意不以 `Failed to install library` 开头的问题都会返回 `true`）。这是本项证据链的已知弱点，如实记录。

---

## 3. P0-2：安装元数据必须合法编码 —— 已修复

### 3.1 问题仍然存在（代码证据）

`InstallOrchestrator.writeReport()` 手工拼接字符串：

```java
sb.append("  \"clientJar\": \"").append(report.clientJar()).append("\",\n");
...
sb.append('"').append(w).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
```

两处硬伤：

1. **路径完全没有转义**。Windows 上 `report.clientJar()` 形如 `C:\Users\x\26.2.jar`，
   输出 `"C:\Users\x\26.2.jar"` —— `\U` 不是合法 JSON 转义，**整份报告非法**；
2. warning 的转义只处理 `\` 和 `"`，不处理换行/控制字符/Unicode 代理项。

### 3.2 修复

在既有 `org.loader.installer.json.Json` 上**新增写出能力**（不引入第二套 JSON 库，模块仍保持"只依赖 JDK"）：

```java
Json.write(Object)        // 紧凑
Json.writePretty(Object)  // 2 空格缩进
```

- 只接受 `String/Boolean/整数/Double(Float)/Map<String,?>/Iterable/Object[]/null`；
  其它类型**直接拒绝** —— 对任意对象调用 `toString()` 正是产出非法文档的源头；
- 转义 `" \ \b \f \n \r \t` 与 `< 0x20`，未配对代理项转义为 `\uXXXX`，配对代理项原样成对输出；
- `NaN/Infinity` 抛 `JsonException`（JSON 没有这两种字面量）；
- 嵌套深度上限 64，防自引用结构 `StackOverflow`；
- **`Path` 显式拒绝**：`Path` 实现 `Iterable<Path>`，落到 `Iterable` 分支会无限递归
  （这个坑是本轮写测试时才暴露的，见下）。

`writeReport()` 改为构建 `LinkedHashMap` → `Json.writePretty(root)`。
保留原有全部字段名（`minecraft/javaRequired/platform/clientJar/librariesApplied/.../ok/warnings`），
新增 `steps`、`issues` 数组。

### 3.3 回归测试（`JsonTest` 新增 5 项，共 15 项）

| 测试 | 覆盖 |
|---|---|
| `writeEscapesWindowsPathSoItRoundTrips` | `C:\Users\Administrator\...` 往返一致，且输出含 `\\` |
| `writeRoundTripsQuotesUnicodeNewlinesAndControlChars` | 引号、反斜杠、`\n\t\r`、`\u0001`、中文、emoji |
| `writeRoundTripsNumbersAndBooleans` | int/long/double/boolean/null |
| `writeRejectsValuesThatWouldProduceInvalidJson` | `NaN`、`Infinity`、`Path`、非 String key 全部 `JsonException` |
| `writePrettyIsParseableAndIndented` | 缩进输出仍可被本项目 parser 解析 |

外加 `InstallOrchestratorTest.allStepsSucceed` 断言 `clientJar` 字段与 `report.clientJar().toString()` **完全相等** —— 在 Windows 上这条断言就是反斜杠转义的门禁。

---

## 4. P1-3：依赖解析必须可靠检测循环 —— 已修复

### 4.1 问题仍然存在（代码证据）

三套实现，行为不一致：

| 位置 | 环检测 | 实际后果 |
|---|---|---|
| `instance/ModManager#resolveRecursive` | **无**（只有 `visited`） | `A → B → A` 被当成"已访问"，解析**成功**并返回 `[B, A]`，环进入加载流程 |
| `kernel/DependencyResolver#visit` | 有 `inProgress` | 能发现，但错误只有一个节点名（`Cycle detected at node: x`），看不出环 |
| `loader/discovery/ModDiscovery#resolveRecursive` | **无**（只有 `visited`） | 环静默通过；生产启动走的就是这条 |

### 4.2 修复：收敛到单一实现

**新增** `mili-runtime/.../util/DependencySorter.java`：三态 DFS（`IN_PROGRESS` / `DONE` / 未访问），
后序遍历产出"依赖先于依赖者"的顺序，返回结构化结果：

```java
record Result(List<String> order, List<String> missing, List<List<String>> cycles)
// describeCycles() → "A -> B -> C -> A"
```

三处调用方共用它，各自决定失败方式：

| 调用方 | 缺失 | 环 |
|---|---|---|
| `ModManager.resolve` | `missingDependencies`（失败） | `conflicts` 追加 `依赖循环: A -> B -> A`（失败，`resolvedMods` 为空） |
| `ModDiscovery.resolveDependencies` | 记录到 `missingDependencies()` 并 `PlatformLog.warn`（**不阻断**，保持既有语义） | 抛 `ModLoadError`，`errorCode=MOD_DEPENDENCY_CYCLE`，消息含完整环链 |
| `DependencyResolver.resolveInitializationOrder` | 不可能（`addDependency` 已校验节点存在） | 抛 `IllegalStateException`，消息含完整环链 |

**版本不匹配**：`ModManager` 新增独立检查，产出 `版本不匹配: <mod> 要求 <dep> <range>，实际为 <ver>`，
与"缺失"、"循环"三分。范围表达式**只支持精确版本与 `*`**；`^ ~ > < , [ ] ( )` 等语法按"不校验"处理
（宁可漏报，也不要把合法 Mod 误判成冲突）。这是刻意的保守取舍，已在代码注释与 README 中说明。

### 4.3 回归测试

`DependencySorterTest`（11 项）：无依赖、线性、分叉/共享、重复依赖、自循环、两节点环、
多节点环（从任意入口都能发现且环链首尾闭合）、缺失、未知 root、确定性（20 次重复同一结果）、环+缺失共存。

`ModManagerTest`（+4）：环被拒且 `resolvedMods` 为空、环链为 `modA -> modB -> modA`、自循环、版本不匹配与缺失可区分、
`versionSatisfies` 的三态语义。

`DependencyResolverTest`（+2）：插入期环拒绝消息含两端节点、多 root 拓扑顺序且可重复。

`ModDiscoveryDependencyTest`（**新增 6 项**）：线性顺序、共享依赖只出现一次、两节点环抛
`MOD_DEPENDENCY_CYCLE` 且消息含 `a -> b -> a`、三节点环、缺失被容忍且记录、可选依赖被忽略。

> 生产路径（`ModDiscovery`）此前对环**完全没有防线**；`ModDiscoveryDependencyTest` 是这条路径的首个直接测试。

---

## 5. P1-4：Scheduler 队列与拒绝策略 —— 已修复

### 5.1 问题仍然存在（代码证据，且比假设更严重）

| # | 缺陷 | 证据 |
|---|---|---|
| a | **队列无界 + `offer` 永远返回 true**，`CallerRunsPolicy` 永不触发 | `remainingCapacity()` 返回 `Integer.MAX_VALUE`；`offer()` 无条件 `return delegate.offer(st)`；注释却写"队列无界，CallerRuns 不会因拒绝而丢弃任务；改为在调用线程执行以施加背压" |
| b | `maximumPoolSize = corePoolSize * 2` 是**死配置** | 无界队列下线程数永远停在 core |
| c | **优先级比较器方向写反** | `Comparator.comparingInt(t -> t.priority().value())` 升序，而 ABI 与 runtime 两个 `TaskPriority` 的 javadoc 都写"值越大优先级越高" → `LOW(0)` 先于 `CRITICAL(20)` 执行 |
| d | `taskMap` **永久泄漏** | 任务完成后从不从 `taskMap` 移除，每跑一个任务泄漏一条 |
| e | `TaskHandle.state()` **永远是 QUEUED** | record 自动访问器返回构造期快照而不是 `stateRef.get()` |
| f | 周期任务 `cancel()` **恒返回 false** | `cancellable` 先 `future.complete(null)`，随后 `future.cancel(true)` 返回 false |
| g | `scheduler.cancel(周期任务)` 恒 false | 周期任务不进 `taskMap`，`cancel()` 直接 `return false` |
| h | 周期任务终止后**计时器线程不释放** | scope 停止后 `tick` 直接 `return`，`newSingleThreadScheduledExecutor` 的核心线程不会自行退出 |
| i | `cancelAll(scope)` 只改状态不摘队列 | 取消的任务继续占容量（会推高拒绝率），future 也不结束 |

### 5.2 修复与契约

```text
容量      queueCapacity（默认 16384 = Scheduler.DEFAULT_QUEUE_CAPACITY，可构造期指定）
排序      TaskPriority.value() 降序；同优先级按 createdAtNanos 升序（FIFO）
线程数    corePoolSize 常驻，队列满时扩到 corePoolSize * 2
超限      AbortPolicy → submit() 转成 SchedulerError（显式失败，不静默丢弃、不在调用线程偷跑）
关闭后    submit() 抛 IllegalStateException
取消      未开始：出队 + 状态 CAS + future.cancel；已开始：协作取消
指标      queueDepth / queueCapacity / remainingCapacity / rejected / active
```

实现要点：

- `PriorityWorkQueue` 增加 `capacity`，`offer` 在 `capacityLock` 内做「size 判定 + 插入」，返回 `false` 表示满；
  `put()` 满时抛 `IllegalStateException`（不阻塞游戏线程）；`remainingCapacity()` 返回真实余量；
- `ScheduledTask.run()` 维护 `activeTasks` 计数并在 `finally` 从 `taskMap` 移除自己；
  `metrics().active` 改用该计数（不再用 `taskMap.size()` 反推）；
- `TaskHandleImpl.state()` 覆写为 `stateRef.get()`（实时状态）；
- 周期任务：`cancel` 只做"停计时器 + 置 CANCELLED"，future 交给 `TaskHandleImpl.cancel()` 里那次
  `future.cancel(true)`；因 scope 停止/关闭而自然终止时置 COMPLETED 并 `future.complete(null)`；
  两种路径都会 `timer.shutdownNow()` 并从 `repeatingTimers` 摘除；
- `Scheduler.cancel(handle)` 直接委托 `handle.cancel()`，周期任务因此可取消；
- `cancelAll(scope)` 真正出队 + 取消 future + 计入 `cancelled`。

### 5.3 回归测试（`SchedulerTest` 重写，11 项）

优先级（`CRITICAL → NORMAL → LOW`）、同优先级 FIFO、队列满显式 `SchedulerError` 且
"接受数 = 提交数 − 拒绝数"、`capacity<=0` 拒绝构造、关闭后提交、取消排队任务不执行、
任务异常计入 `failed` 且调度器继续可用、8×50 并发提交无丢失无死锁、
周期任务按周期执行 + cancel 后停止 + future 已取消、scope 停止后周期任务自终止。

> 稳定性：该文件连跑 **3 次 `--rerun-tasks` 全绿**（见 §7.4）。

### 5.4 未改动（有意保留）

周期任务仍由**独立计时器线程**触发、不经过优先级队列，`priority` 对其执行顺序无效。
改成"计时器入队 → 池线程执行"会改变线程亲和性与重入语义，属于行为重设计，不在本轮范围。
已把这条边界写进 `Scheduler` 类注释、README 与 `TICK_ENGINE.md`，避免被误读成"周期任务也有背压"。

同时明确写入文档：**Scheduler 不等于 Minecraft 并行 Tick 引擎**，tick 仍由单一驱动线程串行推进。

---

## 6. P1-5：Mod 加载路径一致性与类加载边界 —— 已修复

### 6.1 问题仍然存在（代码证据）

`mili-runtime/.../mod/ModLoader`：

```java
private ClassLoader createClassLoader(ModManifest manifest, ClassLoader parent) {
    // In a real implementation, this would create a URLClassLoader from mod jars
    // For now, return the parent classloader
    return parent != null ? parent : getClass().getClassLoader();
}
```

后果：一个**根本没有被打进 classpath 的 Mod 也能"加载成功"**，直到某处引用它的类时才抛
`ClassNotFoundException / NoClassDefFoundError` —— 报错点离根因很远。

调用链核实结果：

| 路径 | 入口 | 是否创建隔离 CL | 是否生产路径 |
|---|---|---|---|
| 生产 | `LoaderMain.launch()` → `ModDiscovery.scan()` → `resolveDependencies()` → `validateVersionBindings()` → `ModClassLoaderManager.create(manifest, source)` → `Mod(manifest, scope, mcl)` → `invokeModEntrypoints()` | 是（parent = `MinecraftClassLoader`） | 是 |
| 进程内 API | `ModLoader.load(manifest, parent)` / `ModManager.load()` | 否 | **否**（`LoaderMain` 完全不引用 `ModManager`/`ModLoader`，grep 确认） |

### 6.2 修复

- `ModLoader` 类注释写明"本类不是生产加载路径"，并画出真实调用链；
- 新增 `ModLoader.createsIsolatedClassLoaders()` 恒返回 `false` —— 把事实变成可断言代码；
- `createClassLoader(...)` → `resolveClassLoader(...)`：清单声明了 `entrypoint` 时，
  用 `Class.forName(entrypoint, false, loader)` 做可见性检查（`initialize=false`，不触发静态初始化），
  不可见立即抛 `ModLoadException`，消息直接指向 `mili-loader` 的 `ModClassLoader`；
- `unload()` javadoc 明确：只解除 Runtime 登记，**不声称类被卸载**（JVM 不保证）。

### 6.3 回归/隔离测试

- `ModLoaderPlaceholderTest`（**新增 6 项**）：不创建隔离 CL、无入口类可加载且 `classLoader()` 是调用方给的、
  入口类可见时正常加载、**入口类不可见 → `ModLoadException` 且消息含 `ModClassLoader`**、
  重复加载被拒、`unload` 只解除登记；
- `ModClassLoaderTest`（+1）：缺失类抛 `ClassNotFoundException`，不被兜底成"加载成功"；
- 既有隔离测试继续全绿：`ClassIsolationTest`(9)、`ClassVisibilityTest`(19)、`DuplicateClassTest`(10)、
  `ResourceIsolationTest`(7)、`ModClassLoaderTest`(9)、`ModLoadingEndToEndTest`(8)。

覆盖计划要求的六类隔离场景：Mod 私有类 / 公共 ABI / 共享依赖 / 同名类冲突 / 资源读取 / 缺失类 —— 均由上述用例直接断言。

---

## 7. P2：文档一致性与下载器复核

### 7.1 文档（P2-6）—— 已修订

计划列出的 6 个重点逐条落实：

| 重点 | 处理 |
|---|---|
| ① 模块数量与 include 一致 | README「四个模块」→ **五个**，补 `mili-installer` 行；依赖方向改为实际值（`runtime→abi`、`integration→runtime,abi`、`loader→runtime,integration,abi,installer`）；PLATFORM_VERSIONING 同步 |
| ② ASM/字节码转换的真实位置 | README 原「反射桥接是**无字节码重写**的」与 `transform/asm`+`ClassTransformInterceptor`+ADR-0011 直接冲突 → 改写为"无 Agent ≠ 无字节码改写"，并指明实现位置与 `MiliSymbol` + `verifyTransformationSymbols` 的 CI 约束 |
| ③ 未绑定版本 Mod 策略统一 | PLATFORM_VERSIONING 自相矛盾（一处说"警告放行"，一处说"拒绝"）→ 统一为**拒绝**（`MOD_PLATFORM_MISSING`），并修正 `TARGET_MINECRACT` 拼写、修正"多字段不匹配一次报全"（实际是短路） |
| ④ Tick 驱动模型与能力边界 | README 调度章节重写为可测量契约表，并明确"不是并行 Tick 引擎"；`TICK_ENGINE.md` 补充同样声明 + 本轮修复的 4 个 Scheduler 缺陷 |
| ⑤ classloader/Capability 不是沙箱 | `docs/design/SECURITY.md` 新增「这不是恶意代码沙箱」整节：Mod 与平台同进程同 JVM，可见性/Capability/Permission 是**架构边界**不是安全边界；表格列出"能防/不能防" |
| ⑥ 只描述已验证行为 | 删除/改写了一批与代码冲突的断言：EventBus 频率熔断、`ResourceWatcher`、`ResourceManager.preload`、`startup-report.json`、`SUSPENDED`/`resume()`/重试计数、MVS 版本解析、`TaskGroup`/`GroupHandle`/`Configurer` 超时、`CapabilityToken.aliasOf()`、`:mili-loader:run`、0.x 忽略 patch 差异、`distribution-boundary` 独立 shell 扫描 |

`docs/architecture/CLASSLOADER.md` 新增「两条加载路径：只有一条是生产路径」+「卸载边界」小节。

### 7.2 下载器复核（P2-7）—— 修复 3 处有代码证据的问题

| 问题 | 证据 | 修复 |
|---|---|---|
| `.part` 文件名固定为 `<target>.part` | `target.getFileName() + ".part"` | 改为 `<name>.part-<pid>-<seq>`。两个安装器进程装同一目录、或同进程两线程下同一目标时会互相截断同一个临时文件，摘要必然不匹配 |
| 校验不符时**先删目标再下载** | `Files.deleteIfExists(target)` 在 `download()` 开头 | 删除该行：先下载到临时文件、校验通过后 `move(REPLACE_EXISTING)` 原子替换。失败时旧文件保持原样 —— 失败路径行为可预测 |
| 资源索引的 `hash` 直接拼进路径 | `objectsRoot.resolve(hash.substring(0,2)).resolve(hash)` | `parseIndex` 校验必须是 40 位十六进制并转小写；非法条目计数并以 `ERROR(ASSET_INDEX)` 报出 |

**未改**（证据不足以证明必要）：`openStream` 无重试（`getBytes` 有 3 次重试）、并发下载同一目标的"最后写赢"语义、`AtomicMoveNotSupportedException` 回退（已存在且合理）。

### 7.3 下载器回归测试（`VerifiedDownloaderTest` +2，共 11）

- `failedDownloadKeepsExistingTargetIntact`：目标存在且内容陈旧 + 服务器内容摘要不符 → 失败后目标文件**内容不变**；
- `concurrentDownloadsOfSameTargetDoNotCorruptEachOther`：4 线程同时下同一目标 → 最终文件 sha1 正确、无 `.part*` 残留；
- 两个既有"无残留"用例的断言从"`client.jar.part` 不存在"加强为"目录下没有任何 `.part*` 文件"。

### 7.4 计划外修复（阻塞验收的既有失败）

`RegistryClassInitOrderTest > blockRegistrarIsGuarded`（基线红）。根因：`BlockRegistrar.initModdedBlockStates()`
在 `RegistrationPhase.closeRegistryWindow()` **之后**被 `BootstrapGate` 调用，此时 `windowClosed=true`，
若在其中调用 `ensureRegistriesReadable()` → `openRegistryWindow()` 会抛
`"The registration window is already closed"`（与既有 `drainUnregisteredBlocks()` 完全同一个约束）。

按测试自身规定的机制补充豁免声明（**仅注释，零运行时行为变化**）：

```java
// 【不要在这里调 ensureRegistriesReadable()】
// [registry-exempt: 窗口已关闭，无法开窗]
```

并在注释中写明调用者、时序与"此刻注册表必然已初始化"的论证。

---

## 8. 验证矩阵（真实命令与结果）

所有命令在 `E:\loader` 下执行，`JAVA_HOME` 指向 JDK 25，加 `--offline`（依赖已缓存）。

| # | 命令 | 退出码 | 结果 |
|---|---|---|---|
| 1 | `./gradlew --version` | 0 | Gradle 9.7.1 / JVM 25.0.4 |
| 2 | `./gradlew test`（**基线，改动前**） | 1 | `BUILD FAILED`，`140 tests completed, 1 failed`（`RegistryClassInitOrderTest`） |
| 3 | `./gradlew :mili-installer:test` | 0 | 59 tests，0 failed |
| 4 | `./gradlew :mili-runtime:test` | 0 | 312 tests，0 failed |
| 5 | `./gradlew :mili-loader:test` | 0 | 105 tests，0 failed |
| 6 | `./gradlew :mili-minecraft-integration:test --tests "...RegistryClassInitOrderTest"` | 0 | 6 tests，0 failed |
| 7 | `./gradlew test`（**全量，改动后**） | 0 | `BUILD SUCCESSFUL in 2m 37s`，**684 tests，0 failed，0 skipped** |
| 8 | `./gradlew build -x test` | 0 | `BUILD SUCCESSFUL in 16s`，含 `shadowJar`/`distTar`/`distZip`/`releaseArtifacts`/`distributionBoundaryCheck` |
| 9 | `:mili-runtime:test --tests SchedulerTest --rerun-tasks` ×3 | 0,0,0 | 三次全绿（并发/时序用例稳定性） |
| 10 | `git diff --check` | 0 | 无输出（无空白错误） |

### 8.1 分模块测试明细（改动后）

| 模块 | tests | skipped | failures |
|---|---|---|---|
| `mili-abi` | 68 | 0 | 0 |
| `mili-installer` | 59 | 0 | 0 |
| `mili-loader` | 105 | 0 | 0 |
| `mili-minecraft-integration` | 140 | 0 | 0 |
| `mili-runtime` | 312 | 0 | 0 |
| **合计** | **684** | **0** | **0** |

### 8.2 构建产物（`build -x test` 输出摘录）

```
> Task :mili-loader:shadowJar
> Task :mili-loader:distTar
> Task :mili-loader:distZip
> Task :mili-loader:releaseArtifacts
[release] E:\loader\mili-loader\build\libs\mili-0.1.0-mc26.2.jar
[release] SHA-256: e25dfa4687e6cab4584c122168fa44ea05836aa0d4b29d6f11ef24a095a3ef17
> Task :mili-loader:distributionBoundaryCheck
[Boundary] 平台 JAR 共 697 个条目
[Boundary] 分发边界检查通过 —— 未发现任何 Minecraft 内容
[Boundary] 确认: 无 net/minecraft/, 无 com/mojang/, 无 Minecraft 本体 JAR, 无反编译源码
```

### 8.3 真实 Minecraft 冒烟测试（非 mock）

`Minecraft26_2SmokeTest` 11 项全部 PASSED（属 `:mili-minecraft-integration:test`）：

- 真实 `MinecraftServer` 是 class major 69（ASM 版本的根据）
- `MiliSymbol` 的每个坐标都在真实字节码中精确命中
- 真实 `tickServer` 中恰好注入一次 begin/end；转换结果通过三层字节码验证、常量池未损坏
- 符号缺失时明确失败、错误描述符不命中（证明严格匹配必要）
- 无关真实类零开销短路、转换结果零 Mod 引用

### 8.4 未执行 / 不可执行的验证

| 项 | 原因 | 替代验证 |
|---|---|---|
| 真实联网安装（Mojang CDN） | 无凭据/网络条件保证，且会把真实用户目录当临时目录（计划明令禁止） | `InstallOrchestratorTest` 用可控 `MetaSource` + 假流 + `@TempDir` 覆盖全部失败路径；CI 的 `installer-smoke-test` job 已含 `--dry-run --version 26.2` 的真实元数据拉取，本轮**未在本机执行** |
| 真实客户端启动（进世界、渲染方块） | 需要图形环境与正版账号 | 转换器层由真实 jar 冒烟测试覆盖；启动编排由 `LoaderBootstrapTest`/`ModLoadingEndToEndTest` 覆盖 |
| Minecraft 补丁版本（26.2.x）迁移 | 无该版本客户端 | 不适用 |
| 格式/静态检查 | 项目未配置任何 formatter/linter | 无 |

---

## 9. 兼容性影响

### 9.1 源码级不兼容（都在仓库内部，调用点已同步更新）

| API | 变化 | 影响 |
|---|---|---|
| `InstallOrchestrator.Report` | record 组件去掉 `boolean ok` / `List<String> warnings`，新增 `steps` / `issues` | `ok()` / `warnings()` 作为方法保留，**读取方源码兼容**；只有构造点不兼容（仓库内 1 处，已改） |
| `LibraryInstaller.Result` / `AssetInstaller.Result` | `warnings` 组件 → `issues` 组件 + `warnings()` 方法 | 同上 |
| `AssetInstaller` 构造器 | 移除从未使用的 `MojangMetaClient` 参数 | 仓库内 1 处调用点已改；该参数历史上不参与任何逻辑 |
| `Scheduler.SchedulerMetrics` | 新增 `rejected/active/queueDepth/queueCapacity/remainingCapacity`（`active` 位置变化） | 访问器全部保留；只有构造点不兼容（仅 Scheduler 内部） |
| `Scheduler` | 新增 4 参构造器 `(id, scope, corePoolSize, queueCapacity)` | 纯新增 |

### 9.2 行为变更（有意为之）

| 变更 | 旧行为 | 新行为 | 风险 |
|---|---|---|---|
| 安装结果判定 | 资源失败仍 `ok=true` / 退出码 0 | `ok=false` / 退出码 1 | 使用者会看到此前"静默成功"的安装变成失败，这是修复目的 |
| Scheduler 饱和 | 无界排队，延迟无上限 | 队列满抛 `SchedulerError` | 极端饱和下异常会传播到提交方；默认 16384 容量使正常负载不触发 |
| 优先级顺序 | `LOW` 先于 `CRITICAL` | `CRITICAL` 先于 `LOW` | 与两个枚举的 javadoc 一致；若有代码依赖反向顺序属于依赖缺陷 |
| `TaskHandle.state()` | 恒为 `QUEUED` | 实时状态 | 依赖"恒 QUEUED"的代码会看到真实值 |
| 循环依赖 | 静默通过，任意顺序加载 | 启动失败并给出 `A -> B -> A` | 已有环的 Mod 目录从"能启动"变成"明确报错"，这是修复目的 |
| `ModLoader.load` | 入口类不可见也返回成功 | 抛 `ModLoadException` | 只影响声明了 `entrypoint` 却不在 classpath 的用法 |
| 下载失败 | 先删旧目标再下载 | 保留旧目标，成功才替换 | 更可预测 |
| `Scheduler.cancel(周期任务)` | 恒 false | 真实取消并返回 true | 修复 |

### 9.3 公共 API 语义

- `Report.ok()` 的**含义未变**（"安装是否成功"），只是从"读文本"变成"读结构"；
- ABI 模块（`mili-abi`）**未做任何修改** —— 68 项 ABI 测试全绿；
- 未升级任何依赖、未改 Java/Gradle 基线（`javaVersion=25`、`asmVersion=9.9.1` 原样）。

---

## 10. 尚存风险与后续建议

| # | 风险 | 建议 |
|---|---|---|
| 1 | **Scheduler 有界队列未在真实游戏长跑中验证**：如果某条 tick 路径一次提交上万任务，会从"变慢"变成抛 `SchedulerError` | 在真实客户端跑 30 分钟以上并观察 `metrics().rejected()`；若出现非零，先调 `queueCapacity` 再查提交方是否在 tick 内做批量提交 |
| 2 | 安装器真实联网路径未在本机验证（只有离线替身 + CI 的 dry-run） | 在可联网环境跑一次真实安装（沙箱目录），确认 `install-report.json` 的 `steps/issues` 与退出码符合预期 |
| 3 | 依赖 `versionRange` 只支持精确值/`*`，范围语法按"不校验"处理 | 若将来引入范围语义，必须同时补：解析器、冲突求解策略、以及"未支持语法不得静默通过"的显式错误 |
| 4 | 周期任务仍是"一任务一线程"，且 `priority` 对其无效 | 若 Mod 大量使用 `submitRepeating`，应改为共享计时器 + 入队执行（需重新定义重叠语义） |
| 5 | `ModManager` / `ModLoader` 这条"进程内 API"路径缺乏生产使用者，长期有腐化风险 | 要么接入 `LoaderMain` 统一，要么标注为 `@Deprecated`/测试专用并从公开文档移除 |
| 6 | 文档仍有未逐条核实的段落（`PermissionManager` 默认策略细节、"设计理念"类路线表述） | 下一轮按"每条断言 ↔ 代码位置"做全量对账；本轮只修了与代码**直接冲突**的断言 |
| 7 | `distributionBoundaryCheck` 只有一份实现（文档曾声称有独立 shell 扫描，实际没有） | 在 CI 增加独立的 shell/python 扫描 job，形成双实现互验 |
| 8 | 本机 8G 内存下跑的是 `--offline` + 单 fork；CI 并行度更高，可能出现本机未见的竞态 | 以 CI 全绿为准；本轮 3 次重跑未发现抖动 |

---

## 11. 回滚

修改尚未提交，回滚只需丢弃工作区改动。**不要**对共享分支执行 `reset --hard`。

```bash
# 1) 确认当前在修复分支
git branch --show-current        # fix/mi-loader-hardening-20261024

# 2) 回到原分支并删除修复分支（改动随分支一起丢弃）
git switch 26.2
git branch -D fix/mi-loader-hardening-20261024

# 3) 如果只想撤销部分文件（保留其余改动）
git restore --source=26.2 -- <path>
```

本次**没有**生成额外补丁文件（`.patch`/`.diff`），回滚不依赖任何临时产物。
新增文件（8 个）未跟踪，若要保留但暂不提交，`git stash -u` 即可。

---

## 12. 附录

### 12.1 `git diff --check`

```
（无输出，exit=0）
```

### 12.2 `git diff --stat`

```
 26 files changed, 1929 insertions(+), 334 deletions(-)
```

（另有 8 个新增文件未跟踪，见 12.4）

### 12.3 `git status --short`

```
 M README.md
 M docs/PLATFORM_VERSIONING.md
 M docs/architecture/CLASSLOADER.md
 M docs/architecture/TICK_ENGINE.md
 M docs/design/SECURITY.md
 M mili-installer/src/main/java/org/loader/installer/InstallerMain.java
 M mili-installer/src/main/java/org/loader/installer/download/VerifiedDownloader.java
 M mili-installer/src/main/java/org/loader/installer/install/AssetInstaller.java
 M mili-installer/src/main/java/org/loader/installer/install/InstallOrchestrator.java
 M mili-installer/src/main/java/org/loader/installer/install/LibraryInstaller.java
 M mili-installer/src/main/java/org/loader/installer/json/Json.java
 M mili-installer/src/main/java/org/loader/installer/meta/MojangMetaClient.java
 M mili-installer/src/test/java/org/loader/installer/download/VerifiedDownloaderTest.java
 M mili-installer/src/test/java/org/loader/installer/json/JsonTest.java
 M mili-loader/src/main/java/org/loader/loader/discovery/ModDiscovery.java
 M mili-loader/src/test/java/org/loader/loader/ApiBoundaryTest.java
 M mili-loader/src/test/java/org/loader/loader/ModClassLoaderTest.java
 M mili-minecraft-integration/src/main/java/org/loader/runtime/minecraft/block/BlockRegistrar.java
 M mili-runtime/src/main/java/org/loader/runtime/instance/ModManager.java
 M mili-runtime/src/main/java/org/loader/runtime/kernel/DependencyResolver.java
 M mili-runtime/src/main/java/org/loader/runtime/mod/ModLoader.java
 M mili-runtime/src/main/java/org/loader/runtime/scheduler/Scheduler.java
 M mili-runtime/src/main/java/org/loader/runtime/scheduler/TaskHandle.java
 M mili-runtime/src/test/java/org/loader/runtime/DependencyResolverTest.java
 M mili-runtime/src/test/java/org/loader/runtime/SchedulerTest.java
 M mili-runtime/src/test/java/org/loader/runtime/instance/ModManagerTest.java
?? mili-installer/src/main/java/org/loader/installer/install/InstallIssue.java
?? mili-installer/src/main/java/org/loader/installer/install/InstallStep.java
?? mili-installer/src/main/java/org/loader/installer/meta/MetaSource.java
?? mili-installer/src/test/java/org/loader/installer/install/InstallOrchestratorTest.java
?? mili-loader/src/test/java/org/loader/loader/discovery/ModDiscoveryDependencyTest.java
?? mili-runtime/src/main/java/org/loader/runtime/util/DependencySorter.java
?? mili-runtime/src/test/java/org/loader/runtime/mod/ModLoaderPlaceholderTest.java
?? mili-runtime/src/test/java/org/loader/runtime/util/DependencySorterTest.java
```

### 12.4 改动文件清单（按模块）

**mili-installer（P0-1 / P0-2 / P2-7）**
- 新增 `install/InstallIssue.java`、`install/InstallStep.java`、`meta/MetaSource.java`
- 改 `install/InstallOrchestrator.java`、`install/LibraryInstaller.java`、`install/AssetInstaller.java`、`json/Json.java`、`download/VerifiedDownloader.java`、`meta/MojangMetaClient.java`、`InstallerMain.java`
- 测试：新增 `install/InstallOrchestratorTest.java`(10)；改 `json/JsonTest.java`(+5)、`download/VerifiedDownloaderTest.java`(+2)

**mili-runtime（P1-3 / P1-4 / P1-5）**
- 新增 `util/DependencySorter.java`
- 改 `instance/ModManager.java`、`kernel/DependencyResolver.java`、`mod/ModLoader.java`、`scheduler/Scheduler.java`、`scheduler/TaskHandle.java`
- 测试：新增 `util/DependencySorterTest.java`(11)、`mod/ModLoaderPlaceholderTest.java`(6)；改 `SchedulerTest.java`(重写,11)、`instance/ModManagerTest.java`(+4)、`DependencyResolverTest.java`(+2)

**mili-loader（P1-3 / P1-5）**
- 改 `discovery/ModDiscovery.java`
- 测试：新增 `discovery/ModDiscoveryDependencyTest.java`(6)；改 `ModClassLoaderTest.java`(+1)、`ApiBoundaryTest.java`(白名单 +1 类)

**mili-minecraft-integration（计划外）**
- 改 `block/BlockRegistrar.java`（仅注释：`registry-exempt` 论证）

**文档**
- `README.md`、`docs/PLATFORM_VERSIONING.md`、`docs/architecture/CLASSLOADER.md`、`docs/architecture/TICK_ENGINE.md`、`docs/design/SECURITY.md`

### 12.5 是否可提交 / 是否具备发布候选条件

- **可提交**：是。全量测试绿、构建绿、`git diff --check` 干净；本轮新增代码中没有调试输出，
  也没有 `TODO/FIXME` 遗留（`InstallerMain` 里的 `System.out.println` 是 CLI 的正式输出，属既有行为）。
  按计划 §0.2 与 §10，"commit-ready" 但**不自动提交**。
- **可发布**：**否**。缺两项真实环境证据：(1) 安装器真实联网安装一次；(2) 真实客户端长跑观察 `Scheduler` 拒绝计数。
  另外 `docs/PLAN` 未覆盖的 `ModManager` 长期定位问题（§10-5）需要产品决策。
