# Mili Transformation System — Phase 1 审计报告与设计方案

> 状态：**设计提案，未修改任何代码**
> 审计日期：2026-10-05
> 审计范围：`settings.gradle.kts`、5 个模块的 `build.gradle.kts`、全部 137 个 Java 源文件、
> `.github/workflows/ci.yml`、`release.yml`、`distribution-boundary.gradle.kts`、`docs/**`

---

## 0. 结论摘要

现有仓库**没有任何字节码转换能力**。`TickEngine` / `TickContract` / `TickBridge`
已经按"由真实 tick 入口调用 `beginTick()`/`endTick()`"设计，但**生产路径上没有任何调用者**——
整条 tick 链目前只在测试和 `DevLauncher` 里被手工驱动。

这意味着：Transformation System 不是"锦上添花的新能力"，而是**填补当前架构最核心的一个空洞**。
`TickBridge` 的类注释已经写明「调用方：字节码注入或官方启动路径」—— 这个位置就是空的。

设计中另有 **1 个阻断性问题**（ASM 版本）、**3 个必须由你裁决的架构冲突**。

---

## 1. 阻断性问题（必须先解决，否则 Phase 3 起无法工作）

### 1.1 ASM 9.7.1 无法读取 Minecraft 26.2 的 class 文件

实测 `test_client_bak/26.2.jar` 中 `net/minecraft/server/MinecraftServer.class`：

```
major version = 69   (Java 25)
```

而仓库两处锁定的 ASM 版本是：

| 位置 | 当前版本 |
|---|---|
| `mili-minecraft-integration/build.gradle.kts:33` | `org.ow2.asm:asm:9.7.1` |
| `mili-loader/build.gradle.kts:40` | `org.ow2.asm:asm:9.7` (仅测试用) |

ASM 9.7.x 的 `ClassReader` 在读到 major 69 时会直接抛：

```
IllegalArgumentException: Unsupported class file major version 69
```

已确认的版本对应关系（社区与上游一致）：

- ASM **9.7** — 支持到 Java 23
- ASM **9.8**（2025-03-29）— 新增 `Opcodes.V25`，支持 Java 25
- ASM **9.9.1** — 支持 Java 25，当前推荐

本地 Gradle 缓存已有 `9.8` 与 `9.9.1`，无需联网。

**结论**：Transformation Engine 的依赖必须提升到 **9.9.1**，且需要四个 artifact
（`asm` / `asm-tree` / `asm-commons` / `asm-analysis`）—— 因为注入逻辑需要
`AdviceAdapter`（在 `asm-commons`）和方法解析（`asm-tree` 的 `MethodNode`），
只用 `asm` 核心包写不了。

> 附带发现：现有 `GeneratedBlockFactory` 用 ASM **生成**字节码（`ClassWriter` 从零构建），
> 所以它至今没踩到这个坑——它从不读取 MC 的 class 文件。转换引擎是要**读**的，必踩。

---

## 2. 关键审计发现

### 2.1 tick 链是悬空的（最重要的发现）

`TickBridge.beginTick()` / `endTick()` 的全部调用者：

| 调用者 | 位置 | 生产路径？ |
|---|---|---|
| `EndToEndIntegrationTest` | 测试 | ❌ |
| `MinecraftIntegrationTest` | 测试 | ❌ |
| `DevLauncher.runTickLoop` | 开发模拟器 | ❌ |
| **生产代码** | **无** | — |

`ReflectiveMinecraftTickSource` 名不副实：它**不做任何反射**，只是把调用转发给 `TickBridge`；
`endpointName` 硬编码为字符串 `"reflective"`；`ready` 标志只能靠 `markReady()` 手工置位，
而 `markReady()` 在生产代码中**无人调用**。

`docs/architecture/CURRENT.md` 自己已经承认了这点（第 241、256 行）：
> 「真实 Minecraft tick 入口注入 — `ReflectiveMinecraftTickSource` 目前只是…」
> 「tick 入口尚未注入。」

### 2.2 真实 tick 锚点已核实（修正了需求文档中的签名）

需求文档写的是 `MinecraftServer.tickServer()`（无参）。实测 26.2 真实签名：

| 类 | 方法 | 真实描述符 |
|---|---|---|
| `net.minecraft.server.MinecraftServer` | `tickServer` | `(Ljava/util/function/BooleanSupplier;)V` |
| `net.minecraft.server.MinecraftServer` | `tickChildren` | `(Ljava/util/function/BooleanSupplier;)V` |
| `net.minecraft.client.multiplayer.ClientLevel` | `tick` | `(Ljava/util/function/BooleanSupplier;)V` |

`MinecraftServer extends ReentrantBlockableEventLoop`，269 个方法，101 个字段。

同时发现一处**文档漂移**：`ReflectiveMinecraftTickSource` 的类注释称优先锚点是
`tickChildren(long)`，实际是 `tickChildren(BooleanSupplier)`。

→ 结论：`tickServer` 存在且适合做 HEAD/RETURN 注入锚点，但描述符必须严格按
`(Ljava/util/function/BooleanSupplier;)V`。这正是需求 §18 要求 `TargetResolver`
做严格匹配的现实理由——手写描述符必错。

### 2.3 ClassVisibility 会挡住 API 的可见性

`ClassVisibility` 是"平台唯一的包级访问规则来源"（其原话）。当前：

- `org.loader.loader.**` → **FORBIDDEN**（Mod 不可见）
- `org.loader.runtime.kernel/**` → FORBIDDEN（少数 SPI 白名单除外）
- `org.loader.api.*` → PARENT_FIRST（Mod 可见）

→ **推论**：Transformation API 如果放在 `mili-loader`，Mod 将完全无法使用它。
API 必须落在 `mili-abi`，或在 `ClassVisibility` 中新增放行条目。

另注：`FORBIDDEN_PLATFORM` 已包含 `org.spongepowered.`（Mixin）——
架构上已明确禁止 Mixin 出现在 Mod 可见范围内。新方案会强化这一点。

### 2.4 现有架构已有一条与新方案冲突的硬规则

`docs/history/ARCHITECTURE.md:358` 原文：

> 「禁止引入外部 Mixin、**ASM**、ByteBuddy、LaunchWrapper 等字节码改写框架，
> 所有 MC 桥接代码必须走纯反射。」

而 `GeneratedBlockFactory` 已经引入并 `implementation` 了 ASM 9.7.1。
→ **这条规则事实上已被违反，但文档未更新。** 需要一条新 ADR 取代它（见 §6.1）。

### 2.5 其他确认事实

| 项 | 状态 |
|---|---|
| 依赖方向 | `loader → minecraft-integration → runtime → abi`，CI 有 grep 校验 |
| CI 真实 MC 流水线 | **已存在**：`ci.yml` job `minecraft-pipeline` 拉取 26.2、校验、解包、CFR 反编译、生成元数据 |
| 分发边界 | **已存在**：`distribution-boundary.gradle.kts` + CI job，扫描 `net/minecraft/`、`com/mojang/`、`decompiled/` 与 MC 本体 JAR |
| 审计基础设施 | **已有**：`org.loader.runtime.security.AuditLog`，含 `PRIVILEGED_USE` / `PERMISSION_DECISION` 事件类型 |
| 权限基础设施 | **已有**：`org.loader.api.permission.Permission` 枚举 + `PermissionManager` |
| Capability | ABI 与 runtime 各有一份 `CapabilityManager`（命名重复，实际用途不同） |
| 本地真实 MC 测试 | **不可用**：`test_client/` 目录为空，MC jar 在 `test_client_bak/`。本地只能跑合成测试，真实 MC 测试须走 CI |
| `test_client_bak/26.2.jar` 是否含 ASM 副本 | **否**（`objectweb/asm` 计数 0）。平台 ASM 由 AppClassLoader 定义，MC 类经 parent 委派获取 → 单份，安全 |
| bundler 结构 | 26.2 jar **无** `META-INF/versions/`，`classesRoot = ""` |

---

## 3. 现有能力盘点（对照需求 §三十九 验收清单）

| 能力 | 现状 |
|---|---|
| 不依赖 Mixin | ✅ 已满足，且 `org.spongepowered.*` 已 FORBIDDEN |
| ASM-based transformation | ❌ 仅"生成"，无"转换"；且版本不足以读 MC class |
| ClassLoader integration | ⚠️ `MinecraftClassLoader` 有干净的 `loadClass` 切入点，但无钩子 |
| deterministic transformation | ❌ 无 |
| conflict detection | ❌ 无 |
| injection points | ❌ 无 |
| redirect / modify arg / modify return | ❌ 无 |
| field transformation | ❌ 无 |
| bytecode verification | ⚠️ 仅 JVM 自身校验（`GeneratedBlockFactory` 未做 CheckClassAdapter） |
| transformation cache | ⚠️ 依赖 JVM 隐式缓存（`findLoadedClass`），无显式缓存 |
| security / capability | ✅ 基础设施齐备，需新增 `TRANSFORM_*` 权限 |
| audit | ✅ `AuditLog` 已存在 |
| debug dump | ❌ 无（仅 `GeneratedBlockFactory` 有 `mili.debugBytecode` 开关） |
| Minecraft 26.2 support | ⚠️ 元数据流水线已通，但无转换能力 |
| real Minecraft smoke test | ❌ 无 tick 接入测试 |
| TickBridge integration | ❌ **链已建好但未接线** |
| no Minecraft redistribution | ✅ 强制机制已部署 |

---

## 4. 设计方案

### 4.1 模块归属（严格遵守现有边界）

```
mili-abi                      ← Mod 唯一编译目标，零依赖
└── org.loader.api.transform.
    MiliTransformer, @MiliInject, @MiliRedirect, @MiliOverwrite
    InjectionPoint, InjectionContext, TargetClass/Method/Field
    TransformationResult, TransformationPhase
    （纯接口 + 数据类型，无 ASM、无 Minecraft）

mili-runtime                  ← 引擎内核（CI 保证不 import minecraft/loader）
└── org.loader.runtime.transform.
    engine/     TransformerRegistry, TransformerPipeline, TransformerOrdering
    conflict/   ConflictDetector, TransformationConflictException
    cache/      TransformationCache
    verify/     BytecodeVerifier
    security/   TransformationGuard（接入 PermissionManager）
    （依赖 asm 9.9.1 四件套）

mili-loader                   ← ClassLoader 集成
└── org.loader.loader.transform.
    TransformingClassLoaderSupport（挂在 MinecraftClassLoader.findClass 上）
    symbol/     MiliSymbol, SymbolResolver（26.2 符号表）
    debug/      TransformationLogger, ClassDumper, TransformationAudit（接 AuditLog）

mili-minecraft-integration    ← 核心转换器（唯一知道 26.2 具体形状的地方）
└── org.loader.runtime.minecraft.transform.
    MiliTickTransformer（tickServer HEAD/RETURN → TickBridge）
    TickCallbackDispatch（生成字节码的调用目标）
```

**依赖方向合规**：`loader → integration → runtime → abi`，无一逆向。
CI 的 `grep -rE "import org\.(loader\.runtime\.minecraft|loader)" mili-runtime/`
检查可通过——引擎不需要 import 这些包（它只处理字节码和字符串）。

**ASM 依赖只加在 `mili-runtime`**（`mili-minecraft-integration` 现有的 9.7.1
需同步升级，因为 `GeneratedBlockFactory` 也要读高版本 class）。

### 4.2 数据流

```
loadClass(name)
  ↓ MinecraftClassLoader.loadClass (self-first, 已存在)
findClass(name) ← URLClassLoader
  ↓
TransformationInterceptor.findClass  ← 新增：唯一插入点
  ├─ registry.matchingTransformers(name)      ← 预索引过滤，零匹配则直接透传
  ├─ read original bytes
  ├─ for each transformer (确定性顺序):
  │    ├─ PermissionManager.hasPermission(scope, TRANSFORM_*)   ← 安全
  │    ├─ transform(ctx)
  │    └─ ConflictLedger.record(...)          ← 冲突检测
  ├─ BytecodeVerifier.verify(bytes)          ← CheckClassAdapter + Analyzer
  ├─ AuditLog.privilegedUse(...)              ← 审计
  ├─ TransformationCache.put(...)             ← 防重复转换
  └─ defineClass
```

**不采用 retransformation**：`java.lang.instrument` 需要 `-javaagent`，
与"无 agent、零字节码改写框架"的历史约束冲突（ADR 0009）。
且需求 §21 明确要求在 `defineClass` 之前完成转换。类加载前拦截是唯一正确位置。

### 4.3 核心 API（含对需求原设计的修改建议）

需求 §五允许我修改接口设计。**我建议改两处**，理由如下。

#### 修改建议 1：`TransformationResult` 需要区分 `Skipped` 与 `Failed`

原设计：

```java
sealed interface TransformationResult {
    record Unchanged() {}
    record Transformed(byte[] bytecode) {}
    record Rejected(String reason) {}   // ← 语义过载
}
```

**问题**：`Rejected` 同时被两种互斥需求使用，接口无法同时满足：

- §19「目标类/方法不存在必须明确失败，**不要静默跳过**关键 Core Transformer」
- §29「检查是否存在 transformer target → NO → 直接 defineClass」

若不区分，Pipeline 无法判断"这个 transformer 不关心这个类"（正常，静默）
与"这个 transformer 关心这个类但目标消失了"（必须炸）。二者会互相掩盖——
这恰好是仓库历史上最痛的一类 bug（见 `EntryPointHook` 里那段"静默失效比崩溃难查"的注释）。

```java
public sealed interface TransformationResult {
    record Unchanged() implements TransformationResult {}

    record Transformed(byte[] bytecode) implements TransformationResult {}

    /** Transformer 明确声明"不适用于此目标" —— 正常，Pipeline 继续。 */
    record Skipped(String reason) implements TransformationResult {}

    /** 声明了目标但执行失败 —— Pipeline 记录后按 conflict policy 处理。 */
    record Failed(TransformationException error) implements TransformationResult {}
}
```

`TransformationException` 继承已有的 `org.loader.runtime.error.ModularRuntimeException`
（该异常体系已存在且有 `ErrorContext`），而不是新造一套。

#### 修改建议 2：`MiliTransformer` 需要身份、版本、优先级、前置过滤

原设计的 `transform(className, classBytes, context)` 无法表达 §6 要求的
priority / phase / ordering / dependencies，也无法表达 §29 的性能要求。

```java
public interface MiliTransformer {
    /** 唯一身份。用于冲突检测、审计、日志。 */
    String id();

    /** 严格 Minecraft 版本，必须精确匹配 "26.2"。禁止 "1.21.x" / "latest"。 */
    String minecraftVersion();

    TransformationPhase phase();

    int priority();

    /**
     * 前置过滤 —— 在读取字节码之前调用，返回 false 时完全跳过，
     * 保证无关 class 的加载开销接近零（§29）。
     */
    default boolean matches(String className) { return false; }

    TransformationResult transform(TransformationContext context);
}
```

`TransformationContext`（class 级，final 类，含你要求的全部字段）：

```java
public final class TransformationContext {
    private final String className;
    private final byte[] originalBytes;
    private final TransformationEnvironment environment;   // pipeline 级：CL / runtime / MC 版本
    private final TransformationPhase phase;
    private final Scope modScope;                          // 权限判定用
    private final String transformerId;
}
```

其中 `TransformationEnvironment`（pipeline 级不可变）持 `ClassLoader`、
`MiliRuntime`、`MinecraftVersion`。**拆分理由**：§17 要求 `InjectionContext`
携带 `className` / `methodName` / `runtime` / `tickContext` / `executionContext` / `modContext`——
这些与 pipeline 无关，是运行时数据，不该塞进转换期上下文。两者混在一起会让
`InjectionContext` 无法为 Region Scheduler 复用。

### 4.4 注入点实现策略（关键取舍）

| 注入类型 | 策略 | 对 §要求的关键点 |
|---|---|---|
| METHOD_HEAD | `AdviceAdapter.onMethodEnter()` | 直接插入 |
| RETURN | `onMethodExit(opcode)` 遍历**全部**退出路径 | §9 要求：不是只找最后一个 RETURN |
| INVOKE | `AdviceAdapter.visitMethodInsn` + ordinal 计数 | §10：owner/name/desc/opcode/ordinal 五元组匹配 |
| FIELD | 同上，`visitFieldInsn` | §11：BEFORE / AFTER / REPLACE |
| REDIRECT | `visitMethodInsn` 内原位移除 + 替换 | §12：栈语义必须完全等价 |
| ModifyArg | 用 `LocalVariablesSorter` 分配临时槽 | §13：primitive/wide/boxing |
| ModifyReturn | `onMethodExit` 改栈顶 | §14：void 不适用，需拒绝 |
| OVERWRITE | 默认 `disabled` | §15：需 `OVERWRITE_METHOD` 能力 + 记录 hash |

**核心设计约束（回应 §16）**：生成的字节码**绝不引用 Mod 的方法**。
所有注入统一指向平台自己的静态分发器：

```
INVOKESTATIC org/loader/loader/transform/dispatch/TickCallbackDispatch
    .onTickEnter()V
```

`TickCallbackDispatch` 内部持有 `modId → MiliCallback` 注册表，由平台管理生命周期。
这样 Minecraft 字节码对 Mod 类零引用，Mod 的 ClassLoader 可以独立回收，
且天然满足 §27 的能力模型。

### 4.5 冲突检测（§7）

`ConflictLedger` 以 `(className, methodName, injectionKind, ordinal)` 为主键：

- **可组合**（不冲突）：多个 `METHOD_HEAD`、多个 `RETURN`、多个不同 ordinal 的 `INVOKE`
- **冲突**（抛 `TransformationConflictException`）：同一位置的 `REDIRECT`、
  `REPLACE_FIELD_ACCESS`、`OVERWRITE`、同一 ordinal 的 `BEFORE_INVOKE`

冲突异常按需求格式输出 class / method / transformers / conflict 原因。
Core transformer（`phase = CORE`）与 Mod transformer 冲突时，**Core 优先且 Mod 报错**——
不静默覆盖（§30）。

### 4.6 版本隔离（§19）

`MiliTransformer.minecraftVersion()` 与 `VersionInfo.TARGET_MINECRAFT` 严格比对。
但**这一层不够**：版本号相同不保证方法名相同（26.2 是快照，Mojang 可能重映射）。
所以双层防护：

1. 注册时版本字符串不匹配 → 直接拒绝注册（配置期失败）
2. 目标解析时方法不存在 → `TransformationTargetNotFoundException`，
   附 class / method / descriptor / 尝试的符号 / MC 版本 / sha256 前缀

### 4.7 符号层（§20）

**当前只做 26.2 的最小实现**，不做过早泛化：

```java
public final class MiliSymbol {
    // "minecraft.server.tick" → 26.2 的实际 JVM 坐标
    static final SymbolKey SERVER_TICK =
        new SymbolKey("minecraft.server.tick",
                       "net.minecraft.server.MinecraftServer",
                       "tickServer",
                       "(Ljava/util/function/BooleanSupplier;)V");
}
```

符号表在 CI 中由真实 MC jar 自动生成 + 校验（与现有 `generateMinecraftIntegration`
流水线合并），保证描述符不会靠人手写错。

---

## 5. 实施路线（在需求 §38 基础上修正）

| Phase | 内容 | 修正说明 |
|---|---|---|
| 0 | **升级 ASM 到 9.9.1**（4 个 artifact） | **新增，阻断性** |
| 1 | 审计 + 设计 | ✅ 本文档 |
| 2 | Transformation API + Registry + Pipeline + 单测 | 按原计划 |
| 3 | ClassLoader 集成 + 验证 + 缓存 | 按原计划 |
| 4 | HEAD / RETURN / INVOKE / FIELD | 按原计划 |
| 5 | REDIRECT / ModifyArg / ModifyReturn | 按原计划 |
| 6 | 冲突 / 排序 / 安全 / 审计 / 调试 | 按原计划 |
| 7 | 接入真实 26.2 | 按原计划，**且必须在 CI，本地无 MC jar** |
| 8 | `MiliTickTransformer` 接线 | 这是**最高价值**的一步——它让 §31 的整条链第一次真正闭合 |
| 9 | `ExecutionContext` | 按原计划 |
| 10 | `mili-mixin-compat` | 建议**不做**（无实际需求） |

**测试策略**（回应 §32/§33）：需求列的 19 个测试类全部落地，但必须区分两类：

- `mili-runtime` / `mili-loader` 单元测试：用**合成 class**（自己生成的 fixture class）
  验证注入正确性。这是真测试，不是伪测试——它验证的是转换逻辑本身。
- 真实 Minecraft 测试：`minecraft-integration` + CI，验证 §33 要求的完整链路。

绝不用 Mock Minecraft 类来声称"集成可用"。

---

## 6. 需要你裁决的问题

### 6.1 ADR：作废"禁止 ASM"规则

`docs/history/ARCHITECTURE.md:358` 的「禁止 ASM，所有桥接走纯反射」已被
`GeneratedBlockFactory` 事实上违反。建议新增 ADR-0011 取代它，
同时**保留** ADR-0009 的核心约束（Minecraft 依赖不进 Kernel）。

新 ADR 应明确区分：

| 允许 | 禁止 |
|---|---|
| 平台内部（loader/runtime）用 ASM | Mod 直接接触 ASM API |
| 平台生成字节码 | Mod 任意转换任意类（需 `TRANSFORM_*` 能力） |
| ClassLoader 加载期拦截 | `java.lang.instrument` / agent |
| | Mixin 成为基础设施 |

**请确认**：是否同意新增 ADR-0011？

### 6.2 API 归属：是否接受 API 落在 `mili-abi`

如 §2.3 所述，放 `mili-loader` 会让 Mod 不可见。但这会让 ABI 体积增加。
替代方案是放 `mili-runtime` 的公开包（`PUBLIC_RUNTIME_PACKAGES` 已放行
`org.loader.runtime.tick.` / `.minecraft.` 等）——代价是破坏"Mod 只编译 against ABI"的契约。

**请选择**：`mili-abi`（我推荐）/ `mili-runtime` 公开包 / 其他。

### 6.3 ClassVisibility 是否需要新条目

若采纳 6.2 的 `mili-abi` 方案，Mod 可见 `org.loader.api.*` 已覆盖，**无需改动** `ClassVisibility`。
但转换引擎会生成对平台类的引用，需确认这些类对 MinecraftClassLoader 可见
（`org.loader.*` 已是 `PLATFORM_OWNED_PREFIXES`，会委派给 AppClassLoader → 可见）。

**结论：无需改动 `ClassVisibility`。** 但 `ModClassLoader` 的 `violations()`
审计已存在，转换产生的违规访问也应记入——请确认这个扩展是否要做。

### 6.4 Overwrite 默认策略确认

需求 §15 建议默认 `disabled`。我同意，并建议再加一层：
即使开启，也**只允许 Mod 覆盖自己声明的目标**，
禁止覆盖任何 `phase = CORE` 的目标。是否同意？

---

## 7. 实现阶段的偏差记录（Phase 1–9 落地后回填）

本节记录**实现过程中发现、而本文档的设计方案没有预见**的问题。
它们比「按计划实现」的部分更有价值 —— 每一条都对应一类真实会发生的 bug。

### 7.1 ASM 版本不是「顺手升一下」，是硬前置

§1 把它列为阻断性问题，实现时确认了它的影响面比预估大：

`GeneratedBlockFactory` 从未踩到这个坑，因为它只用 `ClassWriter` **生成**字节码，
从不**读** Minecraft 的 class 文件。任何「只用 ASM 写、不用 ASM 读」的代码路径
都不会暴露版本不兼容 —— 直到第一次真正读取 MC class 为止。

因此 `gradle.properties` 里写明了「升级 `javaVersion` 时必须同步核对 `asmVersion`」，
把它变成一条被迫复核的规则，而不是一条需要记住的约定。

### 7.2 字段注入需要独立的 TargetField，不能复用 TargetMethod

设计方案里 FIELD 类注入点写的是「同上，`visitFieldInsn`」，
隐含了复用 `TargetMethod` 的可能。实现时发现**这是错的**：

```
方法描述符：(Ljava/lang/String;)V     ← 含返回类型
字段描述符：Ljava/lang/String;         ← 只有类型
```

拿方法描述符去比字段描述符，**永远为 false**。表现为「字段注入配了但从不生效」，
而且不报任何错。

因此 `MethodInjection` 增加独立的 `TargetField field` 字段，
并在 `matchesField` 中对「缺失 TargetField」显式抛异常 ——
返回 false 会让缺失配置退化成又一次静默失效。

### 7.3 GETSTATIC 在插入前栈是空的 —— 四种 opcode 栈形态互不相同

这是本轮实现中**最隐蔽的一个 bug**，值得完整记录：

```
opcode      插入前栈(底→顶)      插入后栈
GETFIELD    [objref]              [objref, value]
PUTFIELD    [objref, value]       []
GETSTATIC   []                    [value]      ← 插入前是空的
PUTSTATIC   [value]               []
```

若按「instance 形式暂存 objref + value / static 形式暂存 value」的统一写法处理，
`GETSTATIC` 会被去`storeLocal` 一个值 —— 而栈上是<b>空</b>，
弹出的是<b>调用方的实参</b>。

产出的字节码**结构完全合法**（栈深平衡），CheckClassAdapter 抓不到。
表现是「游戏行为诡异且无法定位」。

修复：把四种 opcode 的栈形态显式建模为
`count = isInstance ? (isRead ? 1 : 2) : (isRead ? 0 : 1)`，
并由 `FieldInjectionTest` 的四条断言守住。

### 7.4 REDIRECT 的调用形式不能从描述符推断

方案里没提这一点。实现时一度想用「描述符参数个数比对」判断替换目标是否为静态方法 ——
这是错的：**JVM 描述符不携带 static 信息**，静态方法与实例方法的描述符完全相同。
该启发式会在「无参实例方法」上给出错误答案，生成结构合法但运行期抛
`IncompatibleClassChangeError` 的字节码，而堆栈指向 Minecraft 内部调用点。

因此 `MethodInjection` 增加显式的 `replacementStatic` 布尔字段：
注册期能真正检查目标方法是否为 static，到字节码生成期这个信息已不可得。

### 7.5 MODIFY_ARG 必须在调用指令发出之前完成

方案里把 REDIRECT 与 MODIFY_ARG 放在同一个循环里处理。实现时发现这是**顺序 bug**：

`visitMethodInsn` 被调用时实参已在栈上。REDIRECT 在循环里**立即发出了调用指令**，
随后 MODIFY_ARG 才去「暂存实参再重放」—— 此时暂存到的是返回值，不是实参。

同样，AFTER_INVOKE 必须用**实际发出的**描述符判断返回类型：
若 REDIRECT 把 `()V` 改成返回 `I`，用原描述符会让栈平衡算错，
把返回值当成不存在而直接顶掉。

修复：拆成五个显式阶段 ——
`BEFORE_INVOKE → REDIRECT 决策(不发出) → MODIFY_ARG → 发出调用 → AFTER_INVOKE`。

### 7.6 MODIFY_ARG / MODIFY_RETURN 的回调必须有返回值

原设计里所有注入点的回调签名统一是 `()V`。
实现 MODIFY_ARG 时发现语义上不成立：没有返回值就**只能丢弃原参数而无法提供新值**。

第一版选择「显式抛异常而非静默跳过」，随后实现了完整版本：
`callbackDescriptor` 字段从声明即未使用变为生效，并新增
`effectiveCallbackDescriptor()` 统一处理空值。

生成期校验回调返回类型是**必须的**：`()V` 回调插在返回值之上会让栈上无值，
紧随的 `xRETURN` 直接 `VerifyError`，且堆栈指向 Minecraft 代码。

### 7.7 「合法」不等于「正确」—— 测试必须断言指令序列

ASM 产出的错误字节码**绝大多数是结构合法的**：
栈深平衡、跳转偏移正确、类文件格式无误。CheckClassAdapter 只抓非法结构，
Analyzer 能抓类型不匹配，但抓不到「改错了槽位但类型恰好兼容」。

因此测试不能只断言「验证通过」，必须断言**回调落点**：
RETURN 注入要断言「回调出现次数 == 方法内返回路径数」；
字段注入要断言「GETSTATIC 处不注入 / PUTSTATIC 处才注入」。

`InjectionPointTest` 与 `FieldInjectionTest` 因此全部基于指令序列断言。

### 7.8 真实符号校验：5/5 精确匹配

用 Python 独立解析 `tools/26.2/26.2.jar` 的常量池与方法表，
对 `MiliSymbol` 的 5 个坐标做 name + descriptor **精确**比对：

```
[OK]   SERVER_TICK:        tickServer(Ljava/util/function/BooleanSupplier;)V
[OK]   SERVER_TICK_CHILDREN: tickChildren(Ljava/util/function/BooleanSupplier;)V
[OK]   CLIENT_LEVEL_TICK:  tick(Ljava/util/function/BooleanSupplier;)V
[OK]   CLIENT_MAIN:        main([Ljava/lang/String;)V
[OK]   SERVER_MAIN:        main([Ljava/lang/String;)V
```

这是本轮唯一一次用**真实 Minecraft class 文件**（非 mock）得到的验证结果。
`SymbolVerifier.java` 把这套校验固化到 CI，符号表过期将直接导致构建失败。

### 7.9 已交付的模块与验证状态

**全部代码已编译并通过测试：`510 项 / 0 失败 / 4 跳过`**（4 项跳过为
`MinecraftPipelineTest` 中需要真实服务端环境的用例，非缺陷）。

| 模块 | 测试数 | 失败 | 跳过 |
|---|---:|---:|---:|
| `mili-abi` | 46 | 0 | 0 |
| `mili-runtime` | 220 | 0 | 0 |
| `mili-loader` | 89 | 0 | 0 |
| `mili-minecraft-integration` | 114 | 0 | 4 |
| `mili-installer` | 42 | 0 | 0 |
| **合计** | **510** | **0** | **4** |

真实 Minecraft 26.2 冒烟测试 **11/11 通过**（非 mock，真实 class 文件）。
本机8G 内存不足以跑完整客户端，因此走的是「用真实 jar + ASM 验证 +
最简 ClassLoader 通道」，而非启动游戏。

> 早期版本此节写的是「所有 Java 代码均未经过编译验证」。
> 该状态已结束 —— 全部模块可编译、可测试、可在 CI 复现。

---

## 8. 第二阶段交付：补齐剩余缺口

### 8.1 OVERWRITE 的 ASM 实现

`InjectionPoint.OVERWRITE` 此前只存在于 API 枚举、权限守卫与冲突检测中，
字节码层未实现 —— 即「声明了能覆写，实际什么都不发生」。

实现落在 `MiliClassTransformer.visitMethod` 而非 `InjectionMethodVisitor`：
`AdviceAdapter` 会忠实地重放原始指令，那样覆写就名不副实。

生成的方法体是**参数转发**，而非空实现：

```java
// 原: void tickServer(BooleanSupplier bs) { ...50 行游戏逻辑... }
改后: void tickServer(BooleanSupplier bs) {
        INVOKESTATIC 平台分发器.onOverwrite(this, bs);
      }
```

三个设计决定及其理由：

| 决定 | 理由 |
|---|---|
| 不保留原实现的合成副本（如 `tickServer$mili_original`） | 合成方法仍可被反射遍历发现 —— 某些 Mod 会「注入一个永不被调用的方法」且不报错；同时污染 `getDeclaredMethods()` |
| 与其它注入共存时**显式抛异常** | 原始方法体被丢弃后，HEAD/RETURN 注入**永远不会执行**，而调用方得不到任何提示。这与静默失效无法区分 |
| 回调签名必须与原方法**完全一致** | 签名不一致时参数转发会错位，产出结构合法但语义错误的字节码 —— `CheckClassAdapter` 抓不到 |

### 8.2 声明式注解扫描器

`AnnotationTransformerScanner` 把 `@MiliTransformer` 类中的 `@MiliInject`
方法合成为 `MiliTransformer`，这是「Mod 的编译依赖里没有 ASM」得以成立的前提。

核心是**签名校验必须发生在加载期**。以下错误全是纯反射可判定的，
若漏到字节码生成阶段，报错形态是：

```
VerifyError: Bad type on operand stack
  at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:0)
```

堆栈指向游戏代码，Mod 作者与玩家都无法反推是自己的 Mod 出了问题。
因此加载期就拒绝：非 static 回调、参数个数/类型错误、
`MODIFY_RETURN` 返回 void、HEAD 注入点返回非 void。

回调的 JVM 描述符**由反射签名推导**，不手写 —— 手写描述符是本仓库反复记录的
bug 来源（文档里对同一方法曾有两处互相矛盾的写法）。

### 8.3 映射层

`MiliMapping` 提供符号名解析与证据链（official / runtime name + 坐标）。

**关键判断：不实现 obf 映射。** Minecraft 正式版**不做混淆** ——
`MinecraftServer#tickServer` 就是运行时真实名，官方映射与运行时名称一致。
真正需要 obf 的只有 dev jar，而平台不在开发环境运行。

所以当前映射是恒等映射。本类的价值不在于转换，而在于**把「这是恒等映射」
这个事实固定下来并可验证**：若某天出现非恒等条目，`requireIdentity()`
会显式失败，而不是悄悄用官方名去注入（那必然不命中，且不报错）。

### 8.4 发现并修复的真实漏洞：CI 从不验证真实 Minecraft

补 smoke test 时发现：`build-and-test` job 跑 `./gradlew test`，
但 Minecraft jar 是在**另一个 job** 的 runner 上下载的 ——
GitHub Actions 的 job 之间不共享文件系统。

后果：`Minecraft26_2SmokeTest` 会在 CI 上被 `assumeTrue` **静默跳过**，
而 CI 依然全绿。这恰恰是需求「禁止用 Mock Minecraft 声称集成可用」要防的失效：
**绿色的流水线，但集成从未被验证。**

两处修复：

1. `build-and-test` job 自己下载 Minecraft（`needs:` 不传递文件系统）；
2. 新增「Assert real-Minecraft smoke test actually ran」步骤 ——
   读 JUnit XML，要求 `tests > 0 && skipped == 0`。
   jar 丢失（网络抖动、CDN 变更、属性改名）会让 CI 变红而非降级为绿。

### 8.5 第二阶段交付清单

| 交付项 | 文件 | 关键断言 |
|---|---|---|
| 真实 MC 26.2 smoke test | `Minecraft26_2SmokeTest` | major=69、5 符号精确命中、真实 tickServer 每个返回路径都注入、常量池零 Mod 引用 |
| OVERWRITE 字节码层 | `MiliClassTransformer.applyOverwrite` | 原方法体确实消失、无合成副本、与其它注入共存时报错 |
| 注解扫描器 | `AnnotationTransformerScanner` | 5 类非法签名全部在加载期被拒 |
| 映射层 | `MiliMapping` | 5 条恒等、一致性断言、未知符号抛异常 |
| 排序确定性 | `TransformerOrderingTest` | 阶段优先于优先级、输入顺序无关 |
| ClassLoader 集成 | `ClassLoaderTransformationTest` | 转换**真的**发生在类加载路径上、缓存不跨 CL 污染 |

累计测试类：13 类（本阶段新增 5 类）。

---

## 9. 引擎审查：修复的生产级缺陷

本节记录对已实现引擎做逐行审查 + 探针验证后确认的缺陷。
它们全部属于同一类问题：**症状与原因相距很远，且多数不报错**。

判定原则（承接 ADR-0011）：**契约与实现脱节时修实现，不改契约。**

### 9.1 带参回调不压栈（最严重）

**症状**：`BytecodeVerifier` 报
`AnalyzerException: Error at instruction 0: Cannot pop operand off an empty stack`，
而指令 0 看起来完全正常（它就是那条 `INVOKESTATIC`）。

**根因**：`pushCallback` 无条件发出 `INVOKESTATIC <描述符>`，
**从不为回调参数压栈**。而 ABI 明确允许
`public static void onTick(InjectionContext ctx)` ——
任何使用 `InjectionContext` 的 Mod 都会得到栈下溢的非法字节码。

**修复**：新增 `InjectionContextFactory`（runtime侧，字节码唯一上下文来源）。
注入 `InjectionContext` 参数时生成：

```
LDC  <目标类内部名>
LDC  <目标方法名>
INVOKESTATIC InjectionContextFactory.forMethod(String,String) -> InjectionContext
INVOKESTATIC ModCallbacks.onTick(InjectionContext)V
```

字节码只携带两个字符串常量，tick 信息在**运行期实时填充**——
而不是把转换那一刻的快照硬编码进常量池。

未知参数类型**显式报错而非塞默认值**：塞 `null`/`0` 会让 Mod
收到看似合法实则错误的数据（坐标变成 0、tickId 变成 0），
基于这些值做出错误决策却没有任何提示。

> 守卫测试：`AnnotationTransformerScannerTest.contextArgumentIsMaterializedBeforeCallback`
> 直接断言指令形状，而不只依赖 `verify()`——
> 因为 `verify()` 在缺依赖时会降级，降级后不再做类型推断，
> 那样这条测试会在某天静默通过。

### 9.2 OVERWRITE 生成的常量池用了点分名

```java
// 错误
Type callbackType = Type.getObjectType(callbackOwner.replace('/', '.'));
// 正确
Type callbackType = Type.getObjectType(callbackOwner);
```

`Type.getObjectType(String)` 收的是**内部名**（斜杠分隔）。
写点分名会被原样写进常量池类名项，JVM 按斜杠解析 →
`ClassFormatError` / `NoClassDefFoundError`。

**为什么能一路绿灯**：结构校验（`CheckClassAdapter`）只检查
「类名项是否合法 UTF8」，**不检查这个类是否真的存在**。
于是一个指向不存在类的字节码可以完全合法。

### 9.3 调用点匹配拿错了对象

```java
// 错误：拿宿主方法比对「被调用目标」的 owner/name/desc
if (!injection.target().owner().equals(callOwner) || ...)
// 正确
var called = injection.invocation().method();
if (!called.owner().equals(callOwner) || ...)
```

`target()` 是**宿主方法**（"我要改哪个方法"），
`invocation().method()` 是**被调用的目标**（"改方法里的哪一次调用"）。
拿前者比对调用指令，**结果永远是 false** —— 而症状是静默不生效：
转换成功、字节码合法、验证通过，只是 `REDIRECT` 从未发生。

### 9.4 ordinal 全局计数违反契约

契约（`TargetInvocation`）：「ordinal 从 0 开始，
**按字节码顺序计数同签名**的调用」。

实现用了一个全局计数器。方法体内混着不同签名的调用时，
「第2 次调用 `list.add`」的实际序号会被中间夹着的 `map.put` 顶高，
`ordinal=2` 永远匹配不到目标。改为按 `owner#name#desc` 分桶计数。

### 9.5 声明了调用点却从未命中，无任何提示

调用点类注入的匹配发生在 `visitMethodInsn`里。
若方法体内根本没有目标调用，匹配只返回 false ——
不抛异常、不产出字节码、**日志无痕**。

表现是：Mod 声明「在所有 `entity.die()` 之前插一刀」，
转换成功、验证通过、游戏正常运行，**而 Mod 的逻辑一次都没执行**。
这与「Mod 写了 bug」无法区分。

修复：在 `visitMaxs`（ASM 保证「该方法所有指令都已访问完」的检查点）
校验每条声明了 `invocation` 的注入是否命中，未命中则抛
`TransformationTargetNotFoundException.forCallSite(...)`。

该异常有专门的措辞：**成员不存在 → 查符号表；调用点不存在 → 符号表是对的、
Mod 声明错了。修法相反**，混为一谈会让排查方向完全错反。

### 9.6 验证器把「无法判定」谎报为「非法」

`CheckClassAdapter` 与 `SimpleVerifier` 做类型推断时会**真的去加载父类与接口**。
游戏类签名里引用了大量 Minecraft 依赖（brigadier、fastutil、Guava……），
**任何一个不在给定 ClassLoader 可见范围内**，验证就会抛
`ClassNotFoundException`。

**此时字节码本身完全可能是合法的。** 把「验证器看不到」报成「字节码非法」，
后果是：正确无误的转换被拦截，`defineClass` 不执行，
**游戏启动直接失败**，而错误信息里只有一句无法定位的
`com.mojang/brigadier/Message`。

修复为三态语义：

| 状态 | 判定 | 行为 |
|---|---|---|
| 通过 | 三层全过 | 放行 |
| **非法** | 拿到确凿的结构/类型错误 | 抛 `TransformationVerificationException` |
| **无法判定** | 缺游戏依赖 | 降级为 `BasicVerifier` 纯栈深校验，**不抛异常**，记入 `unresolvedTypes()` |

降级不是无条件放行：`BasicVerifier` 仍能抓出栈深不匹配、栈下溢、
跳转前后栈高不一致、局部变量槽类型 —— 覆盖了绝大多数注入事故。

`unresolvedTypes()` 非空即表示生产环境 `MinecraftClassLoader`
的类路径不完整（最常见原因：Minecraft libraries 未加入），
这是给运维的诊断信号。

> `CheckClassAdapter.verify` 有**两条错误通道**：解析/IO 问题直接 `throw`；
> 数据流分析失败则把 `AnalyzerException` 完整堆栈 `printStackTrace` 到
> 传入的 `PrintWriter` 然后**正常返回**。只`catch Throwable` 会漏掉后者
> —— 方法不抛异常，看起来「验证通过」，真相全在那段文本里。

### 9.7 冲突检测的分键方式让核心规则成为死代码

`ConflictLedger.exclusiveKey` 把 `point` 拼进了键：

```
mod-a: "MODIFY_ARG|net/…#tickServer(...)V#0"
mod-b: "OVERWRITE|net/…#tickServer(...)V#0"
```

两键不同 → 落进不同的桶 → `detect()` **从未被调用**。
而 `detect()` 里那条精心写好的 OVERWRITE 规则
（「会丢弃原始方法体，与其他注入点冲突」）**一次都没执行过**。

更深的根因是**粒度不匹配**：`OVERWRITE` 是**方法级**声明
（丢弃整个方法体，与具体哪条指令无关），而 ordinal 是**指令级**的。
任何以 ordinal 为中心的分桶方案都会让方法级声明漏检。

修复：改为**按目标方法索引**，由 `mutuallyExclusive` 按语义判定：

- `OVERWRITE` 与目标方法上的**任何**其他声明互斥（不看 ordinal）
- 同 ordinal 上两个**改写型**声明（`REDIRECT` / `MODIFY_ARG` / `REPLACE_FIELD_ACCESS`）互斥
- **环绕型**（`BEFORE/AFTER_INVOKE`、字段前后访问）语义正交，可共存

### 9.8 dryRun 在空白账本上预演

```java
ConflictLedger probe = new ConflictLedger();   // ← 空账本
```

`dryRun` 的Javadoc 说「在独立账本上预演……不修改自身状态」——
「独立」指的是**不影响自身状态**，不是「从零开始」。
在空白账本上预演，只能检出**同一批声明之间**的冲突，
对「与已装 Mod 冲突」这个主要用途完全无效 ——
而那恰恰是唯一值得在安装阶段报出的冲突。

修复：预演在自身状态的**副本**上进行。

### 9.9 未变化时不写缓存导致转换链重跑

```java
if (Arrays.equals(current, original)) {
    logger.endTrace(trace, className, false);
    cache.put(classLoader, className, original);   // ← 补上
    skippedClasses.incrementAndGet();
    return original;
}
```

缓存的对象是「这个类在这个 ClassLoader 上已被本流水线处理过」这个**事实**，
不是「字节码变没变」。不写缓存的失效链是：
下次缓存未命中 → 整条转换链重跑 → 转换器有副作用时字节码看不出来 →
**tick 数翻倍但游戏完全正常**。这是 `TickCallbackDispatch` 里
那个「重复进入 tick」计数器存在的理由。

### 9.10 缺陷分类

| # | 缺陷 | 症状 | 是否报错 |
|---|---|---|---|
| 9.1 | 带参回调不压栈 | 游戏启动即崩 | 验证器报错但指向错误位置 |
| 9.2 | 常量池点分名 | 运行期 `ClassFormatError` | **否** |
| 9.3 | 调用点匹配拿错对象 | REDIRECT 静默失效 | **否** |
| 9.4 | ordinal 全局计数 | 定位到错误指令 | **否** |
| 9.5 | 调用点未命中 | Mod 逻辑一次不执行 | **否** |
| 9.6 | 无法判定谎报非法 | 游戏启动失败 | 报错但原因指错方向 |
| 9.7 | OVERWRITE 冲突规则死代码 | 静默吃掉其他 Mod 的注入 | **否** |
| 9.8 | dryRun 空白账本 | 装 Mod 时不提示冲突 | **否** |
| 9.9 | 未变化不写缓存 | tick 数翻倍，游戏正常 | **否** |

其中 6 项**完全不报错**。这与本仓库反复强调的判断标准一致：
「Mod 加载成功但功能不生效」比直接抛异常危险一个数量级，
因为它与「Mod 自身写错了」无法区分。

### 9.11 尚未修完的架构偏差

生成字节码目前**直接 `INVOKESTATIC` Mod 的回调方法**，
而 ADR-0011 要求「只引用平台分发器，对 Mod 类零引用」——
现状会让 Minecraft 字节码持有 Mod 类引用，造成 ClassLoader 泄漏。

本次未修的原因：现有测试断言当前行为
（`generatedBytecodeReferencesCallbackOwner`），
且修复需要新增「回调 id →实际回调」的分发表，
属于独立的一次重构。已记为待办。
