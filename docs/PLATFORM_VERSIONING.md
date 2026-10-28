# 版本策略

## 概述

Mili 采用严格的版本锁定：每个 Mod 必须声明它绑定的**平台版本**、**ABI 版本**与**Minecraft 版本**，三者缺一不可。加载器在启动阶段完成校验，不匹配即拒绝加载。

这么做的目的是消除隐式兼容性假设。一个声明与 ABI 2 兼容的 Mod，不会因为某个用户装了 ABI 1 的平台就被「尽力加载」——那种尽力往往以半初始化的状态结束，比拒绝加载难查得多。

## 三元组

| 字段 | 对应常量 | 当前值 | 职责 |
|---|---|---|---|
| `platform` | `VersionInfo.CURRENT_VERSION` | `"0.1.0"` | Loader / Runtime 作为整体的行为承诺 |
| `abi` | `VersionInfo.ABI_VERSION` | `2` | `mili-abi` 暴露的纯接口集合的版本 |
| `minecraft` | `VersionInfo.TARGET_MINECRAFT` | `"26.2"` | 目标 Minecraft 客户端版本 |

另有 `VersionInfo.TARGET_JAVA = 25`，表示构建与运行所需的最小 Java 主版本。它不参与 Mod 的三元组校验（Java 版本由 JVM 本身保证），但参与构建期校验。

`VersionInfo.PLATFORM_ID = "mili-0.1.0-mc26.2"` 是 platform + minecraft 的组合标识，用于日志与报告。

### 为什么 ABI 用整数

`ABI_VERSION` 是整数（1、2、3…），不是语义化版本。理由：ABI 只有「兼容」与「不兼容」两种状态，没有「向后兼容的新特性」这种中间态——新增 `default` 方法虽不破坏二进制兼容，但它改变了契约的承诺范围，需要 Mod 作者显式确认。整数版本强迫每次契约变更都是一次显式决策。

`CURRENT_VERSION` 则是语义化版本，反映实现的迭代节奏。两者独立演进。

## mod.json 中的声明

```json
{
  "id": "mymod",
  "name": "My Mod",
  "version": "1.0.0",
  "entrypoint": "com.example.MyMod",
  "mili": {
    "platform": "0.1.0",
    "abi": 2,
    "minecraft": "26.2"
  },
  "dependencies": []
}
```

`abi` 是**整数**（`2`），不是字符串（`"2.0"`）。加载器也接受字符串形式的数字（`"2"`），但推荐整数。

## 校验规则

校验入口：`ModManifest.validateVersionBinding()`，调用点 `LoaderMain.validateVersionBindings`。

| 情形 | 结果 | 错误码 |
|---|---|---|
| 缺 `mili` 对象，或 `platform` 为空 | 拒绝 | `MOD_PLATFORM_MISSING` |
| `platform` 与 `CURRENT_VERSION` 不逐字符相等 | 拒绝 | `MOD_PLATFORM_MISMATCH` |
| `abi` 与 `ABI_VERSION` 不相等 | 拒绝 | `MOD_ABI_MISMATCH` |
| `minecraft` 与 `TARGET_MINECRAFT` 不逐字符相等 | 拒绝 | `MOD_MINECRAFT_MISMATCH` |
| 三者全部精确相等 | 通过 | — |

规则细节：

- **逐字符相等**，不做 SemVer 范围匹配。`"^0.1.0"`、`"~0.1.0"`、`"[0.1,0.2)"` 都是不匹配。
- **不做前缀/通配符匹配**。`"0.1"` 不等于 `"0.1.0"`。
- **未绑定即失败**。没有「警告放行」的过渡路径——一条规则只有两种结果，才不需要在每次排查时重新确认它当前是哪种。
- **首个不匹配即终止**。`validateVersionBindings` 按 Mod 顺序遍历，遇到第一个不匹配就抛 `ModLoadError`，错误信息包含 modId、期望值、实际值与错误码。

## 构建期校验

三处出现同一个版本数字是有意为之：

1. `gradle.properties` — 唯一权威来源
2. `mili-abi/src/main/java/org/loader/api/VersionInfo.java` — 编译期常量
3. `mili-abi/src/main/java/org/loader/api/transform/symbol/MiliSymbol.java` — `MINECRAFT_VERSION`

`./gradlew check` 中的 `verifyVersionConstants` 任务校验三者一致，任一漂移即构建失败：

```
[version] 常量一致: platform=0.1.0 abi=2 minecraft=26.2 java=25 asm=9.9.1
```

为什么必须构建期拦住：版本常量漂移会让已编译的 Mod 无法加载，而症状是「昨天还能用的 Mod 今天不行了」——排查方向会被引向 Mod 本身，而不是平台的版本声明。

另有 `verifyTransformationSymbols` 用**真实 Minecraft JAR** 校验 `MiliSymbol` 的每个坐标（owner + name + descriptor 三元组）确实存在。JAR 缺失时该任务 SKIPPED（本地开发机不该持有 Minecraft），CI 上必须通过。

## ABI 版本演进

### 递增 `ABI_VERSION` 的情形

- 移除公开接口或方法
- 修改方法签名（返回值、参数类型、参数数量或顺序）
- 改变方法语义导致已有调用方无法正常工作
- 改变类/接口的继承关系
- 移除或重命名 `CapabilityToken` 常量
- 修改 `VersionInfo` 中任何常量的值
- **新增抽象方法**（即使 Mod 通常不实现该接口——契约的实现方变了）

### 不递增的情形

- 新增独立接口
- 新增 `default` 方法
- 纯性能优化，不改变可观察行为
- `@Deprecated` 标注过渡期方法
- 新增枚举常量（需在发布说明中标注：调用方的 `switch` 若未写 `default` 会编译失败）

### 流程

```
提案（RFC）→ 过渡期（旧 API 标 @Deprecated + 迁移指引，至少一个次版本）→ 移除
```

每个废弃项在移除前必须经历至少一个次版本的过渡期。迁移指南记录在 [MOD_DEVELOPMENT.md](MOD_DEVELOPMENT.md#从-abi-1-迁移到-abi-2)。

### 历史

| ABI | 平台版本 | 主要变化 |
|---|---|---|
| 1 | 0.1.0 | 首个稳定契约：Mod / ModContext / Scheduler / EventBus / Capability / Registry / 转换注解 |
| 2 | 0.1.0 | 新增 TickEvent / ModLifecycleEvent / PlatformEvent / ServiceBroker / TickService；修正 `TaskHandle.state()` 快照、`Lifecycle.removeListener()` 不可用、`EventBus` 仅精确类型匹配、Mod Scope 永不进入 RUNNING 等缺陷 |

## 开发模式与生产模式

平台**没有**开发/生产两套校验规则。版本三元组在任何模式下都是精确匹配。

这与某些加载器不同：它们提供「开发模式跳过严格校验」以便本地快速试错。Mili 不做这件事，因为开发模式下能跑通、生产模式下被拒绝的差异，会让开发者在上线前才发现问题——而那恰好是最不方便修改的时候。

本地迭代的正确做法是：改完 `mili-abi` 后重新构建并替换 Loader 侧的 JAR，而不是放宽校验。

## 版本漂移的常见来源

| 来源 | 症状 | 处理 |
|---|---|---|
| Mod 用了旧 `mili-abi` 编译 | `MOD_ABI_MISMATCH` | 换新 JAR 重编译 |
| 手动改了 `gradle.properties` 但没改 `VersionInfo` | 构建失败（`verifyVersionConstants`） | 两处一起改 |
| 修改了 `MiliSymbol` 的坐标但没验证 | `verifyTransformationSymbols` 失败（CI） | 用真实 JAR 校验 |
| 分发包里混入了旧版 Loader | 三元组不匹配或行为异常 | 检查 `core/` 目录 |
| Mod 的 `mod.json` 里 `abi` 写成字符串 `"2.0"` | 解析为 0 → `MOD_ABI_MISMATCH` | 写成整数 `2` |
