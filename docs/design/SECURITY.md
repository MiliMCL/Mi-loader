# 分发边界与安全模型

> 这是 Mili 的**架构红线**。

---

## 核心原则

> **Mili 绝不重新分发 Minecraft。**

Minecraft 官方文件（Mojang / Microsoft 所有）只能作为
**build-time input**，绝不成为 **Mili distribution artifact**。

---

## 允许的流程

CI 中的 Minecraft 处理链：

```text
下载 Mojang Minecraft（官方 CDN）
        ↓
SHA-1 / 官方 manifest 校验
        ↓
临时保存到 CI 工作区
        ↓
反编译（CFR）
        ↓
用于 API / ABI / Integration 编译
        ↓
用于测试
        ↓
用于生成兼容性 metadata
        ↓
CI 工作区销毁
```

## 禁止的行为

```text
✗ 将 Minecraft JAR 打包到 Release
✗ 将 Minecraft 源码提交到 Git
✗ 将反编译源码放入 Maven Package
✗ 将 Minecraft 放入 GitHub Release
✗ 将 Minecraft 放入 Mili distribution
✗ 将反编译源码作为 Mili source distribution 发布
```

## 发布物只能是

```text
mili-abi
mili-runtime
mili-loader
mili-minecraft-integration
mili-installer
```

Minecraft 由 **Installer 在用户拥有合法获取条件时**从官方来源获取。

---

## 强制检查

### 1. Gradle `distributionBoundaryCheck` + `verifyPlatformJar`

任务实现：[`mili-loader/build.gradle.kts`](../../mili-loader/build.gradle.kts)
（规则说明保留在 [`mili-loader/distribution-boundary.gradle.kts`](../../mili-loader/distribution-boundary.gradle.kts)，
该文件已不再被 `apply` —— 早期写法在 `:mili-loader` 上注册不了任务，见 build 文件里的注释）

递归扫描：

- 平台 fat JAR 的**全部 ZIP 条目**
- `build/distributions/` 分发目录树
- `build/release/` 发布目录

禁止内容：

| 类型 | 模式 |
|---|---|
| Minecraft 类 | `net/minecraft/` |
| Mojang 类 | `com/mojang/` |
| 反编译源码 | `decompiled/` |
| 其他加载器 | `net/minecraftforge/`、`cpw/mods/`、`org/bukkit/`、`io/papermc/` |
| Minecraft 本体 JAR | `minecraft*.jar`、`server*.jar`、`client*.jar` |
| Minecraft 数据 | `*.mcpack`、`*.mca` |

同时校验：

- `SHA256SUMS` 存在
- `release-manifest.json` 存在且含 `platformId`/`version`/`abi`/`minecraft`/`java`/`assets`

### 2. CI 的 `module-boundary-check` job（模块**源码**边界，不是分发包扫描）

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)

该 job 用 grep 校验源码级边界：`mili-runtime` 不得 import `org.loader.runtime.minecraft.*` /
`org.loader.loader.*`，`mili-abi` 不得 import runtime/loader，也不得 import ASM。

> **已知缺口**：文档曾声称存在一套「刻意不复用 Gradle 逻辑的独立 shell 扫描」，
> 但仓库里并没有这个 job。目前分发包内容的检查只有 Gradle 侧一处实现
> （`distributionBoundaryCheck` / `verifyPlatformJar`），单一实现意味着它的 bug 不可见。
> 这不是"设计上的双保险"，是待补的一项。

### 3. `verifyPlatformJar` 强制断言

[`mili-loader/build.gradle.kts`](../../mili-loader/build.gradle.kts)

```kotlin
require(mcClasses == 0) {
    "分发边界违规：平台 JAR 含 $mcClasses 个 net.minecraft.* 类。" +
    "Mili 不得重新分发 Minecraft —— Minecraft 只能作为构建输入。"
}
```

> 这条断言过去**只打日志不报错**，等于没有约束。已修正。

---

## 允许的"提及"Minecraft

平台 JAR 中的以下内容**允许**，因为它们是指纹而非 Minecraft 内容：

```
META-INF/mili/platform.json      版本三元组 + MC artifact 的 SHA-256
META-INF/mili/minecraft.json     构建指纹（版本、artifact hash、registry/event 列表）
```

这些是**元数据**，不含任何 Mojang 二进制或源码。

---

## Mod 可见性（与分发边界相关）

即使 Minecraft 不在分发包中，Mod 也不得触碰平台实现。
见 [CLASSLOADER.md](CLASSLOADER.md) 的可见性契约。

要点：

- Mod 不可访问 `org.loader.loader.**`（Loader 实现）
- Mod 不可访问 `org.loader.runtime.kernel.**`（Runtime 内核）
- Mod 不可访问 `org.loader.installer.**`
- Mod 不可访问 JDK 内部与 Mojang 内部
- Mod **可以**访问 `net.minecraft.**`（这是模组的意义所在）

违规会被拒绝并记录 `AccessViolation`，可通过
`ModClassLoaderManager.allViolations()` 审计。

---

## 这不是恶意代码沙箱

**必须说清楚的一件事**：`ModClassLoader` 的可见性契约与 `Capability` / `Permission`
模型是**架构边界**，不是**安全沙箱**。

Mod 代码与平台、与 Minecraft **运行在同一个 JVM、同一个进程**里。由此可推出：

| 边界 | 能防什么 | 不能防什么 |
|---|---|---|
| `ClassVisibility` 拒绝 `org.loader.loader.**` | 编译期依赖被误用、Mod 调用平台内部 API 导致版本锁死 | 反射（`setAccessible`）、`Unsafe`、字节码库、JNI |
| `CapabilityToken` 注册/消费 | 依赖关系显式化、缺失能力时早失败 | 绕过门面直接调用底层对象 |
| `PermissionManager` | 让高危操作**显式**、可审计 | 阻止有意绕过者读取文件/开 socket |
| 独立 `ModClassLoader` | 类名冲突、Minecraft 类重复定义 | 恶意代码执行 |

换句话说：**能装进 Mili 的 Mod 必须是可信任的代码**。这与 Fabric / Forge / Bukkit
的既有事实一致 —— 它们同样不隔离恶意代码，任何声称"沙箱化 Mod"的 JVM 加载器都需要
独立进程、自定义 SecurityManager（已废弃）或字节码级能力检查，Mili 三者都不用。

因此：

- 不要从不可信来源安装 Mod；
- 能力/权限系统的目标是「**让 Mod 的行为可预期、可审计**」，而不是「限制恶意 Mod」；
- 未来若要支持不可信 Mod，需要的是进程级隔离或 JVM 级沙箱方案，
  那是架构变更，不是给 ClassLoader 加几条规则就能得到的。

---

## Mod 版本绑定（供应链）

Mod 必须精确声明平台绑定：

```json
{
  "mili": {
    "platform": "0.1.0",
    "abi": 1,
    "minecraft": "26.2"
  }
}
```

**校验语义**：未绑定 = 拒绝；任一字段不匹配 = 拒绝；
只有三元组全部精确相等 = 通过。

| 错误码 | 含义 |
|---|---|
| `MOD_PLATFORM_MISSING` | 未声明绑定 |
| `MOD_PLATFORM_MISMATCH` | Platform 版本不符 |
| `MOD_ABI_MISMATCH` | ABI 版本不符 |
| `MOD_MINECRAFT_MISMATCH` | Minecraft 版本不符 |
| `MOD_ENTRYPOINT_INVALID` | 入口缺失或签名不符 |

**不默认兼容、不偷偷兼容。** 若未来要支持多版本，必须显式定义
version range 与 compatibility declaration。

---

## 检查清单（新增分发相关代码时）

- [ ] 新的发布产物是否包含任何 Mojang 内容？
- [ ] 新的 metadata 是否只含指纹、不含源码？
- [ ] `distributionBoundaryCheck` 是否覆盖新的产物路径？
- [ ] 是否有第二套独立实现交叉验证？
- [ ] 反编译产物是否只存在于 CI 临时工作区？