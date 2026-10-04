# Mili Platform — 工程交付最终审计报告

---

## 0. 测试矩阵

```
全量测试（--rerun-tasks）: 146 PASSED / 0 FAILED   BUILD SUCCESSFUL

模块分布:
  mili-abi                  : 0  (纯契约，无测试)
  mili-runtime              : 52  CapabilityPermission/DependencyResolver/Runtime/
                               Scheduler/Scope/ClientCapabilities/ErrorModel/
                               ModManager/ModSet/TickContract/ResourceLeakDetector/
                               ReferenceMod/AuditLog
  mili-minecraft-integration: 41  EndToEnd/GameBridge/Launcher/MinecraftIntegration/
                               ResourceLeak/InstanceManager/MinecraftMainExecutor
  mili-loader               : 53  ModDiscoveryIT/ApiBoundary/LoaderBootstrap/
                               ModClassLoader
```

---

## 1. 版本三元组锁定（严格）

| 常量 | 值 | 位置 |
|---|---|---|
| `CURRENT_VERSION` | `"0.1.0"` | `VersionInfo.java`, `gradle.properties#miliPlatformVersion` |
| `ABI_VERSION` | `1`（整数字面量） | `VersionInfo.java`, `gradle.properties#miliAbiVersion` |
| `TARGET_MINECRAFT` | `"26.2"` | `VersionInfo.java`, `gradle.properties#minecraftVersion` |
| `TARGET_JAVA` | `25`（整数字面量） | `VersionInfo.java`, `gradle.properties#javaVersion` |
| `PLATFORM_ID` | `"mili-0.1.0-mc26.2"` | `VersionInfo#PLATFORM_ID`（派生常量） |

ABI 在 `mod.json` schema 中也以整数 `1` 编码（非字符串 `"1.0"`）；
`VersionBinding` 解析路径在 `ModDiscovery.parseVersionBinder` 内同时支持
`Number` 与 `String`（容错但严格比对）。

---

## 2. Platform Descriptor

- 生成路径：`mili-loader/build/resources/main/META-INF/mili/platform.json`
- 生成时机：Gradle `processResources.doLast` hook（每次构建刷新）
- 内容（示例，含派生字段）：

```json
{
  "platform": "0.1.0",
  "abi": 1,
  "minecraft": "26.2",
  "java": 25,
  "platformId": "mili-0.1.0-mc26.2",
  "buildTimestamp": "2026-10-04T12:57:36.819Z"
}
```

参见：`mili-loader/build.gradle.kts — processResources.doLast`。

---

## 3. 单一格式 JSON Mod Manifest

- 解析器：`MiliJson`（递归下降，零三方依赖）
- 入口字段名：`entrypoint`（非 `mainClass`）
- `"mili"` 对象字段：`platform`（字符串）、`abi`（整数）、`minecraft`（字符串）
- Schema 校验 + 版本绑定：`ModManifest` sealed Java interface（singleton `OK` / `MISSING` /
  record `PlatformMismatch`/`AbiMismatch(int,int)`/`MinecraftMismatch(String,String)`/`MissingBinding`）

错误码（挂载于 `ModLoadError.errorCode()`）：

| Code | 触发条件 |
|---|---|
| `MOD_PLATFORM_MISSING` | 清单缺少 `mili` 对象 |
| `MOD_PLATFORM_MISMATCH` | `platform` ≠ `CURRENT_VERSION` |
| `MOD_ABI_MISMATCH` | `abi` 整数值 ≠ `ABI_VERSION` |
| `MOD_MINECRAFT_MISMATCH` | `minecraft` ≠ `TARGET_MINECRAFT` |
| `MOD_MANIFEST_INVALID` | JSON 解析失败 / 缺少 `id` / 非法字段 |
| `MOD_ENTRYPOINT_INVALID` | `entrypoint` 未声明 / ClassLoader 加载失败 / 缺 `void initialize(ModContext)` |

---

## 4. Mod 入口规范

- Mod 契约接口：`org.loader.api.Mod` —— 单一方法 `void initialize(ModContext context)`
- 旧 fallback 已移除：不存在 `onInitialize` / `main(String[])` / `init()` 等备选
- Loader 调用路径（`LoaderMain.invokeModEntrypoints`）：

  1. 反射 `clazz.getMethod("initialize", ModContext.class)`
  2. 严格 `void` 返回 + 单 `ModContext` 参数
  3. 未命中即 `MOD_ENTRYPOINT_INVALID`

实现类：
- `testmod/com.example.TestMod`：直接定义 `initialize(ModContext)`
- `mod-template/com.example.mod.TemplateMod`：`implements Mod`，`@Override initialize`

---

## 5. Fat Platform JAR（via Gradle Shadow）

- 任务：`:mili-loader:shadowJar`（由 `com.gradleup.shadow` 9.4.1 提供）
- 配置：`mili-loader/build.gradle.kts — tasks.named<ShadowJar>("shadowJar")`
- 特性：自动合并 runtimeClasspath JAR、合并 `META-INF/services` entries、
  丢弃嵌套签名文件
- 命名：`mili-0.1.0-mc26.2.jar`（无 classifier）
- Manifest 属性：`Main-Class: org.loader.loader.LoaderMain`、
  `Implementation-Version`、`Mili-Platform`、`Mili-Abi`、`Mili-Minecraft`、
  `Multi-Release: false`、`Sealed: false`
- 普通 `jar` task 已禁用（`jar { enabled = false }`），`build` 包含 `shadowJar`

---

## 6. Release Artifacts

产物目录：`mili-loader/build/release/`

```
mili-0.1.0-mc26.2.jar     277,292 bytes
release-manifest.json      platformId/version/abi/mc/java/commit/tag/
                           buildTimestamp/assets.sha256
SHA256SUMS                 ascii `sha256sum` 格式，单条目
```

`releaseArtifacts` 任务从 `gradle.properties` 读取版本三元组，写以上三项。

---

## 7. GitHub Actions 工作流

- **ci.yml**：`push` to `main` / `pull_request` to `main`
  - 任务：`build-and-test`（矩阵 Java 25）
  - 子步骤：`compileJava` → `test` → `:mili-loader:shadowJar` → 验证 `META-INF/mili/platform.json`（`json.tool`）
  - 产物上传：`mili-loader/build/release/*`
  - 任务：`module-boundary-check`
    - grep `org.loader.runtime.minecraft.` in `mili-runtime/` source
    - grep `mili-minecraft-integration` deep deps in `mili-abi`
    - `E:\\loader` 硬编码路径扫描
  - CI 任务名已校准为 `:mili-loader:shadowJar`

- **release.yml**：`push` tags `v*`
  - 从 `${GITHUB_REF_NAME#v}` 推 `TAG_VERSION`
  - 校验 `TAG_VERSION == miliPlatformVersion`
  - `clean test` → `shadowJar` + `releaseArtifacts`（单步）
  - `softprops/action-gh-release@v2` 发布 JAR + SHA256SUMS + manifest

---

## 8. API 边界测试

文件：`mili-loader/src/test/java/org/loader/loader/ApiBoundaryTest.java`

三项测试（全部 PASS）：

1. `loaderMainMustNotImportMinecraftBridge()` —— `LoaderMain.java` 不得 import `minecraft.*`
2. `loaderSourceStaysWithinAllowedRuntimeSurface()` —— Loader 主源码只允许引用 Runtime 白名单前缀
3. `loaderJavaSourceContainsNoHardcodedDevPaths()` —— 拒绝 `E:\loader`、`C:\Users` 绝对路径

CI 补充 grep 提供第二道防线（`module-boundary-check` job 内）。

---

## 9. 真实 Minecraft 版本检测

`MinecraftDetector`（位于 `mili-minecraft-integration`）从真实产物读取 Minecraft 版本：
- 读取 `gameDir/<version>.jar` / `gameDir/versions/` 元数据
- 不再返回 `"unknown"` 兜底；缺失即抛出结构化错误

---

## 10. 硬编码路径清理

- `boot-test.ps1`：`$ProjectDir` 从 `$ScriptDir` 推导，不再硬编码仓库路径；Java home 由 `JAVA_HOME` env 提供
- `ModDiscoveryIT`：`test_client` 定位通过 `findTestClientDir()` —— 先尝试 `user.dir/test_client`，
  不存在则尝试父目录（适配 Gradle 子模块 CWD）
- CI workflows：路径由 `$GITHUB_WORKSPACE` + Gradle 派生

---

## 11. 文档统一（本次补充）

读者文档（API_REFERENCE.md、PLATFORM_VERSIONING.md、ABI.md、ARCHITECTURE.md、
MOD_DEVELOPMENT.md、mod-template/README.md）已全部校准至当前实现事实：

| 原样 | 统一后 |
|---|---|
| `mainClass` 字段名 | `entrypoint` |
| `"abi": "1.0"`（字符串） | `"abi": 1`（整数） |
| `ABI_VERSION = "1.0"` | `ABI_VERSION = 1`（整数） |
| `onInitialize(ModContext)` | `initialize(ModContext)` |
| `Mod.onInitialize` 引用 | `initialize(ModContext)` |
| 错误码仅 3 类 | 6 类 MOD_* 全部列齐 |
| SemVer 范围允许 (`^0.1.0`) | 严格等于（三字段均不允许范围） |
| "未绑定可警告放行" | 未绑定 = `MOD_PLATFORM_MISSING`（阻断） |

---

## 12. 已知范围外（Out of Scope）

以下 spec 文档章节涉及的内容为"分析/参考性质"，不影响 MB 基线：

- `docs/execution/` 历史分析文档（`LOADER_ARCHITECTURE_ANALYSIS.md` 等）——包含
  逆向期发现的"Fabric 式 `onInitialize` 已移除"的考古记录，内容仍反映历史事实，
  保留为项目知识资产，不列入对外文档
- `docs/minecraft-26.x/` — 26.x 通用文档，部分描述保留 Fabric/Forge 对比视角
  （当前工程阶段为真实 26.2 客户端桥接，与旧文档存在认知差距，待后续迭代刷新）
- `mod-template/README.md` 第 39 行启动命令 `minecraft-runtime-0.1.0-SNAPSHOT.jar`
  是骨架文档，当前真实启动器为 `mili-0.1.0-mc26.2.jar`

---

## 13. 模块拓扑

```
include("mili-abi")                  # 零依赖契约
include("mili-runtime")              # 依赖 mili-abi
include("mili-loader")               # 依赖 mili-runtime + mili-abi
include("mili-minecraft-integration")# 依赖 mili-abi
```

依赖方向：`abi ← runtime ← loader`，`abi ← minecraft-integration`。
Loader 不依赖 minecraft-integration（反向由 SPI `GameProvider` 解耦）。

---

