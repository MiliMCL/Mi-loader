# Mili 错误模型（ERROR_MODEL.md）

> 本文是 `ModLoadError` / `ErrorContext` Javadoc 引用的错误模型权威文档，
> 与 `ErrorCatalog`（代码）同步维护。目录条目即文档数据——新增错误码必须
> 同时更新代码目录与本文表格。

## 1. 两层错误体系

| 层 | 面向 | 类型 | 说明 |
|---|---|---|---|
| ABI 异常 | Mod 作者 | `org.loader.api.exception.*`（`MiliException` 基类） | Mod 可见的契约异常；Mod 不应抛出其子类，而应以返回值/回调表达错误 |
| Runtime 错误 | 平台与诊断 | `org.loader.runtime.error.*`（`ModularRuntimeException` 基类） | 平台内部错误，携带 `ErrorContext` 与标准错误码 |

## 2. Runtime 错误的结构

- **`ErrorContext`**（record）：`component / owner / operation / state / cause / retryable / details`。
  `toDiagnosticString()` 输出中文多行诊断。
- **`ModularRuntimeException`**：全部 runtime 错误的基类；`component()`、`isRetryable()`。
- **叶子错误类型**（13 个）：`BootstrapError`、`ClassLoaderError`、`CapabilityError`、
  `ConfigurationError`、`DependencyError`、`InstallationError`、`LifecycleError`、
  `MinecraftIntegrationError`、`NetworkError`、`PermissionError`、`ResourceError`、
  `SchedulerError`、`TickError`。
- **`ModLoadError`**：加载链专用，携带 `errorCode()` 字符串。

## 3. 错误码目录（`mili explain` 可解释的完整集合）

生产码（加载流程实际抛出）：

| 码 | 标题 | 抛出位置 |
|---|---|---|
| `MOD_MANIFEST_INVALID` | mod.json 缺失/损坏/缺 id | `ModDiscovery.parseManifest` |
| `MOD_ENTRYPOINT_INVALID` | 入口类无法用作 Mili Mod | `LoaderMain.invokeModEntrypoints` |
| `MOD_ENTRYPOINT_FAILED` | 入口初始化抛异常 | 同上 |
| `MOD_PLATFORM_MISSING` | 未声明 mili 绑定 | `LoaderMain.validateVersionBindings` |
| `MOD_PLATFORM_MISMATCH` | 平台版本不一致 | 同上 |
| `MOD_ABI_MISMATCH` | ABI 版本不一致 | 同上 |
| `MOD_MINECRAFT_MISMATCH` | MC 版本不一致 | 同上 |
| `MOD_UNKNOWN` | 未分类 Mod 加载失败 | 兜底构造器 |

类型映射码（叶子错误类型 → 码，映射规则在 `MiliError.TYPE_CODES`）：

| 码 | 对应类型 |
|---|---|
| `PLATFORM_BOOTSTRAP_FAILED` | `BootstrapError` |
| `CLASSLOADER_LOAD_FAILED` | `ClassLoaderError` |
| `CAPABILITY_DENIED` | `CapabilityError` |
| `PERMISSION_DENIED` | `PermissionError` |
| `DEPENDENCY_ERROR` | `DependencyError` |
| `LIFECYCLE_INVALID_TRANSITION` | `LifecycleError` |
| `SCHEDULER_REJECTED` | `SchedulerError` |
| `TICK_FAILED` | `TickError` |
| `MC_INTEGRATION_FAILED` | `MinecraftIntegrationError` |
| `RESOURCE_UNAVAILABLE` | `ResourceError` |
| `CONFIG_INVALID` | `ConfigurationError` |
| `NETWORK_FAILED` | `NetworkError` |
| `INSTALL_FAILED` | `InstallationError` |
| `GAME_NOT_FOUND` | `GameDiscoveryException`（mili-loader，checked） |

兜底：`PLATFORM_UNKNOWN`（不在目录中的异常形态；`MiliError.describe` 会给出条目）。

## 4. 命令行使用

```
mili explain <CODE>        # 解释码：标题/成因/定位/修复建议/文档
mili explain --list        # 列出全部码
mili explain <CODE> --json # 稳定 schema 的 JSON（schemaVersion=1.0）
mili doctor <gameDir>      # 只读体检（发现/绑定/依赖），发现的问题复用上述错误码作为 finding ID
```

**退出码契约**：`0`=成功；`1`=业务失败（未知错误码 / doctor 发现 error 级问题）；
`2`=用法错误。

**终端适配**：颜色仅在交互式 TTY 且未设 `NO_COLOR`、`TERM!=dumb` 且未传
`--no-color` 时启用；非 TTY（管道/CI）一律纯文本；`--json` 面向机器消费，
schema 变更即破坏兼容，需升版本号并同步测试。

## 5. doctor 的 finding ID 与错误码的关系

doctor 的 finding ID 尽量复用平台错误码（`MOD_MANIFEST_INVALID`、
`MOD_ABI_MISMATCH`…），使 `mili explain <finding-ID>` 直接可查。doctor 特有
的 finding（不是错误码）：`GAME_DIR_MISSING`、`MODS_DIR_MISSING`、
`MODS_DIR_UNREADABLE`、`MOD_JAR_UNREADABLE`、`MOD_ID_DUPLICATE`、`DEP_MISSING`、
`DEP_OPTIONAL_MISSING`、`DEP_CYCLE`、`DEP_SELF`、`ZIP_ENTRY_UNSAFE`、
`ZIP_ENTRY_LIMIT`、`MOD_BUNDLES_PLATFORM_CLASSES`、`CAPABILITY_DECLARED`、
`MC_FOUND`、`MC_NOT_FOUND`、`MC_PROBE_FAILED`、`LIB_MISSING`。

## 6. 维护规则

1. 新增错误码：先加 `ErrorCatalog` 条目 → 更新本文表格 → `ErrorCatalogTest`
   保持绿。禁止收录生产代码从未产生过的占位码。
2. `mili explain` 的退出码与输出 schema 是稳定契约。
3. 未知码的处理原则：**可发现**（`--list`）+ **明确非零退出**（1）+ 引导用户报告。
