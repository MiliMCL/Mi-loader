# Mili Platform

Mili 是一个从零编写的 Minecraft 客户端 Mod 加载器，基于 Java 25，不克隆也不兼容 Fabric。它自带独立的 Runtime 内核（Scope / Scheduler / EventBus / Capability / TickEngine），为 Mod 提供纯 Mili 原生 API，不依赖 Fabric，不使用 Mixin，也不依赖 LaunchWrapper。

整个平台以**运行时契约（ABI）**为核心：Mod 只编译 `mili-abi`，Loader 负责注入真实实现。ABI 与实现之间是单向依赖，实现可以任意重构，只要契约不变，已编译的 Mod 二进制无需重新编译。

## 核心特性

**契约优先，实现可替换。** `mili-abi` 是 Mod 的唯一编译目标，零运行时依赖。Loader 侧任何重构都不会传导到 Mod。

**单一平台事件总线。** 全平台只有一条事件总线，Mod 的 `ctx.events()` 是它的带归属视图。Tick 事件、Mod 生命周期事件、平台阶段事件、Mod 之间的自定义事件全部走同一条总线，并按**类型继承链**派发——订阅 `Object.class` 能收到一切事件，订阅某个基类型能收到所有子类型。这条约束是硬性的：多总线会让「订阅了但永远收不到」变成无法排查的静默失效。

**结构化并发。** 每个任务归属于一个 Scope，Scope 关闭时未完成任务自动取消。任务按优先级出队（同优先级 FIFO），周期任务共用一个计时器线程而不是每任务一条线程。`TaskHandle.state()` 读的是实时状态，不是提交时的快照。

**跨 Mod 服务代理。** `ctx.services()` 提供 `provide` / `discover` / `require`。同一接口只允许一个提供方——冲突在**发布时**失败并指出双方 modId，而不是后注册者静默覆盖。提供方 Scope 停止后服务自动失效。

**按 tick 计数调度。** `ctx.tick()` 提供 `runNextTick` / `runAfterTicks` / `runEveryTick`。与按墙钟计时的 `Scheduler` 分开：游戏卡顿时两者会分叉，需要与游戏节奏对齐的逻辑必须用 tick 服务。

**拒绝即默认的权限模型。** 读写自己的资源默认允许；网络、原生库、四项字节码转换权、方法覆盖默认拒绝，必须由用户配置显式放行。权限在 API 门面层校验，Mod 无法绕过。

**版本三元组严格锁定。** 平台版本、ABI 版本、Minecraft 版本三者必须完全一致才允许加载。没有模糊的兼容区间——升级的显式性本身就是安全保证的一部分。

**无字节码重写的反射桥接。** 与 Minecraft 的交互通过反射完成，不需要 Agent、不需要 premain、不需要改启动参数。桥接坐标集中在 `MiliSymbol`，构建期用**真实 Minecraft JAR** 逐条比对 owner + name + descriptor——符号表过期会让转换器静默不注入，因此这道关卡不可省略。

## 模块构成

| 模块 | 职责 | 依赖 |
|---|---|---|
| `mili-abi` | 稳定契约接口，零运行时依赖，Mod 编译的唯一目标 | — |
| `mili-runtime` | Runtime 内核：Scope、Scheduler、EventBus、ServiceBroker、Capability、Permission、TickEngine | `abi` |
| `mili-loader` | 启动发现、依赖解析、类加载、生命周期调度、CLI | `abi`、`runtime`、`minecraft-integration`、`installer` |
| `mili-minecraft-integration` | 反射桥接与字节码转换：tick 注入、注册表绑定、客户端服务 | `abi`、`runtime` |
| `mili-installer` | 首次运行的 Minecraft 客户端/库/资源安装器 | — |
| `mili-test` | 纯 JVM Mod 测试框架（测试依赖，不属于运行时） | `abi`、`runtime` |

依赖方向严格单向、无环：`abi ← runtime ← loader`，`abi ← runtime ← minecraft-integration`。Minecraft 集成模块独立而非嵌入 Loader，是因为它的替换频率远高于 Loader 本体——Minecraft 补丁发布时只需重打集成模块。

## 版本

| 维度 | 值 | 含义 |
|---|---|---|
| `CURRENT_VERSION` | `0.1.0` | Loader 实现版本（语义化） |
| `ABI_VERSION` | `2` | Mod 契约版本（整数，从 1 递增） |
| `TARGET_JAVA` | `25` | 运行时最低 Java 版本 |
| `TARGET_MINECRAFT` | `26.2` | 唯一支持的 Minecraft 版本 |

`gradle.properties` 是唯一权威来源。`./gradlew check` 会校验 `VersionInfo`、`MiliSymbol.MINECRAFT_VERSION` 与它完全一致——版本漂移会让已编译的 Mod 无法加载，必须在构建期拦住。

ABI 2 相对 ABI 1 的变化见 [ABI.md](docs/ABI.md#abi-2-相对-abi-1-的变更)。

## 快速开始

编译需要 JDK 25：

```
./gradlew clean build test
```

产物位于各模块的 `build/libs/`：

- `mili-abi/build/libs/mili-abi-<version>.jar` — Mod 编译依赖
- `mili-runtime/build/libs/mili-runtime-<version>.jar` — Runtime 内核
- `mili-loader/build/libs/mili-loader-<version>.jar` — Loader 本体
- `mili-minecraft-integration/build/libs/mili-minecraft-integration-<version>.jar` — 反射桥接

分发包布局：

```
<dist>/
├── bin/         启动脚本（mili-loader / mili-loader.ps1）
├── core/        Loader + Runtime + Integration + Installer
├── game/        Minecraft 客户端（首次运行由 Installer 自动拉取）
├── mods/        Mod JAR
└── config/mili/ 平台配置、Mod 配置、Mod 数据
```

启动脚本需传 `--mili-mods <dist>/mods`——分发包里 `game/` 与 `mods/` 不是同一目录，不显式指定就会去扫一个不存在的 `game/mods/`，结果是零个 Mod 且无任何报错（Loader 会在日志里显式警告）。

开发模式下：

| 命令 | 作用 |
|---|---|
| `./gradlew :mili-loader:runClient` | 直接启动（gameDir 默认 `test_client/`，`-Pmili.gameDir=` 覆盖） |
| `./gradlew :mili-loader:miliDoctor` | 对 Mod 目录做只读体检 |
| `java -cp ... org.loader.loader.cli.MiliCli doctor <gameDir>` | 独立 CLI 体检 |
| `java -cp ... org.loader.loader.cli.MiliCli explain <CODE>` | 解释错误码并给出修复建议 |
| `java -cp ... org.loader.loader.cli.MiliCli new <dir> <id>` | 生成 Mod 脚手架 |

## 一个最小 Mod

```java
package com.example;

import org.loader.api.Mod;
import org.loader.api.ModContext;
import org.loader.api.event.TickEvent;

public final class ExampleMod implements Mod {

    @Override
    public void initialize(ModContext ctx) {
        ctx.logger().info("hello from " + ctx.modId());

        // 每 tick 一次（只在 END 阶段计数，避免 2 倍速率）
        ctx.events().subscribe(TickEvent.class, e -> {
            if (e.isEnd() && e.tick() % 20 == 0) {
                ctx.logger().info("1 second passed: t=" + e.tick()
                        + " tickCost=" + e.durationMs() + "ms");
            }
        });

        // 20 tick 之后做一次
        ctx.tick().runAfterTicks(20, () -> ctx.logger().info("ready"));

        // 跨 Mod 服务
        ctx.services().provide(MyApi.class, new MyApiImpl());

        // 配置（玩家可手改 config/mili/<modId>.properties）
        int volume = ctx.config().getInt("volume", 80);
    }
}
```

`META-INF/mod.json`：

```json
{
  "schema": 1,
  "id": "example",
  "name": "Example Mod",
  "version": "1.0.0",
  "environment": "client",
  "entrypoint": "com.example.ExampleMod",
  "mili": { "platform": "0.1.0", "abi": 2, "minecraft": "26.2" }
}
```

入口点是唯一且严格的：`public class <entrypoint> implements org.loader.api.Mod`，public 无参构造器，`public void initialize(org.loader.api.ModContext)`。不接受 `onInitialize` / `main` / `init` 等 fallback——找不到入口即加载失败。

## 启动流程

```
1. parseArgs              --mili-mods 摘出，其余参数转发给 Minecraft
2. ensureMinecraftPresent 缺失时调用 Installer 现场拉取
3. locateGame             发现 Minecraft JAR + 库
4. createGameClassLoader  建立唯一的 MinecraftClassLoader（含转换管线装配）
5. ModDiscovery           发现 Mod、解析依赖（拓扑排序 + 循环检测）
6. runtime.start()        启动 Mili Runtime
7. validateVersionBindings 三元组严格校验
8. createModClassLoaders  每个 Mod 挂在 gameCL 之下，注册 Scope 与归因
9. installRuntimeServices 存储 / 配置 / 客户端服务 / 指标 / 事件错误接收器
10. invokeModEntrypoints  调用 initialize(ModContext)，成功后 Scope 推进到 RUNNING
11. installMiliFacade     接通 Mili.mod(...) / Mili.events() / Mili.modIds()
12. launch Minecraft      交给 EntryPointHook，阻塞到游戏退出
13. shutdown              逆序释放 Mod / ClassLoader / Scope
```

关键顺序约束：MinecraftClassLoader 必须先于所有 ModClassLoader 创建；存储与配置服务必须先于 Mod 的 `initialize()` 装配（Mod 在初始化阶段就会调用 `ctx.storage()` / `ctx.config()`）。

## 设计哲学

**显式优于隐式，编译错误优于运行时错误，拒绝优于静默妥协。**

这条哲学在代码里有一致的体现：

- 能力冲突在发布时抛异常并指出双方 modId，而不是后注册者覆盖。
- 符号名写错在加载期抛异常并列出可用符号，而不是注入不到任何东西。
- 未注册的权限一律拒绝，而不是「没有策略就当允许」。
- 服务缺失时 `require()` 抛异常并列出已注册类型，而不是返回 null。
- 事件订阅超类型能收到子类型，而不是「订阅成功但永远收不到」。

平台反复记录过同一类问题：**代码路径存在、编译通过、运行无异常，但功能静默缺失**（注册链死代码、TickBridge 零调用者、事件零发布者、Mod Scope 永不进入 RUNNING）。这类失效比崩溃难查得多，因此每个新接口都必须回答一个问题：**如果它断了，谁会看到？** 答不上来的接口不应该存在。

## 详细文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 运行时内核、组件装配、启动时序 |
| [ABI.md](docs/ABI.md) | 契约清单、ABI 2 变更、兼容性承诺 |
| [API_REFERENCE.md](docs/API_REFERENCE.md) | 全部公开接口与方法签名 |
| [MOD_DEVELOPMENT.md](docs/MOD_DEVELOPMENT.md) | Mod 开发指南：入口、元数据、注册、事件、调度、服务 |
| [PLATFORM_VERSIONING.md](docs/PLATFORM_VERSIONING.md) | 版本三元组与兼容策略 |
| [ERROR_MODEL.md](docs/ERROR_MODEL.md) | 错误码目录、错误模型、`mili explain` |
| [architecture/](docs/architecture/) | 专题：类加载、Tick 引擎、创造栏 |
| [design/](docs/design/) | 专题：转换引擎、安全模型、液态玻璃转场 |
| [performance/](docs/performance/) | 性能预算与稳定性 |
| [decisions/](docs/decisions/) | 架构决策记录（ADR） |

## 许可证

见仓库根目录的 [LICENSE.txt](LICENSE.txt)。
