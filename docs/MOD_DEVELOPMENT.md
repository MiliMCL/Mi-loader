# Mod 开发指南

本文面向为 Mili 编写 Mod 的开发者。所有内容对应当前代码（ABI 2 / Minecraft 26.2 / Java 25）。

## 1. 项目结构

```
my-mod/
├── build.gradle.kts
└── src/main/
    ├── java/com/example/MyMod.java
    └── resources/
        ├── META-INF/mod.json
        └── assets/<modId>/
            ├── blockstates/*.json
            ├── models/**/*.json
            ├── textures/**/*.png
            └── lang/*.json
```

`build.gradle.kts`：

```kotlin
plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    compileOnly(files("libs/mili-abi-0.1.0.jar"))
}
```

**Mod 的编译依赖只有 `mili-abi`。** 不要引入 `mili-runtime`、`mili-loader` 或 ASM——它们不在 Mod 的运行时可见范围内，编译期能过、运行期会 `NoClassDefFoundError`。

## 2. mod.json

放在 JAR 的 `META-INF/mod.json`（或根目录 `mod.json`）。

```json
{
  "schema": 1,
  "id": "example",
  "name": "Example Mod",
  "version": "1.0.0",
  "author": "You",
  "description": "A minimal Mili mod.",
  "environment": "client",
  "entrypoint": "com.example.ExampleMod",
  "mili": {
    "platform": "0.1.0",
    "abi": 2,
    "minecraft": "26.2"
  },
  "dependencies": [
    { "modId": "corelib", "required": true }
  ]
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | ✅ | 全局唯一。小写字母/数字/`_`/`-`/`.`。重复 id 会让后加载的那个被拒绝 |
| `name` | ❌ | 缺省等于 `id` |
| `version` | ❌ | 缺省 `"1.0.0"` |
| `author` / `description` | ❌ | 缺省空串 |
| `environment` | ❌ | `client` / `server`；用于文档与工具提示，不参与加载判定 |
| `entrypoint` | ✅ | 入口类全名 |
| `mili` | ✅ | 版本三元组。**缺失或任一字段不匹配即拒绝加载** |
| `dependencies` | ❌ | `{ "modId": "...", "required": true }` 列表；`required` 缺省为 `true` |

`mili` 缺失 → `MOD_PLATFORM_MISSING`；`platform` 不匹配 → `MOD_PLATFORM_MISMATCH`；`abi` 不匹配 → `MOD_ABI_MISMATCH`；`minecraft` 不匹配 → `MOD_MINECRAFT_MISMATCH`。

**没有「兼容区间」。** 三元组是精确相等匹配。这是刻意的：模糊的兼容区间会让「为什么这个 Mod 在我这里能用、在他那里不能用」变成需要逐个字段排查的问题。

## 3. 入口点

```java
package com.example;

import org.loader.api.Mod;
import org.loader.api.ModContext;

public final class ExampleMod implements Mod {

    @Override
    public void initialize(ModContext ctx) {
        ctx.logger().info("loaded " + ctx.modId() + " on " + ctx.environment());
    }
}
```

要求：
- `public` 类，非抽象，有 `public` 无参构造器；
- 实现 `org.loader.api.Mod`；
- `initialize(org.loader.api.ModContext)` 是唯一入口。

**没有 fallback。** `onInitialize` / `main` / `init()` 都不会被调用——找不到入口即 `MOD_ENTRYPOINT_INVALID`。这条限制是为了让「我的 Mod 没跑起来」永远有一个明确的答案，而不是先怀疑平台是不是支持了另一种写法。

`initialize()` 抛出的任何异常都会终止整个启动，并以 `MOD_ENTRYPOINT_FAILED` 报告（含完整堆栈）。平台不会「跳过这个 Mod 继续启动」——半初始化的 Mod 比不启动更难排查。

## 4. 上下文能做什么

```java
ctx.modId()            // "example"
ctx.metadata()         // 只读元数据
ctx.environment()      // CLIENT / SERVER / DEDICATED_SERVER
ctx.isActive()         // 是否还能提交工作

ctx.logger()           // 带 modId 前缀的日志
ctx.scheduler()        // 按墙钟时间的异步调度
ctx.tick()             // 按 tick 计数的调度
ctx.events()           // 平台事件总线（带归属跟踪）
ctx.services()         // 跨 Mod 服务
ctx.permissions()      // 权限查询
ctx.config()           // 玩家可手改的配置
ctx.storage()          // 平台托管的小状态
ctx.resources()        // 资源读取
ctx.registry()         // 内容注册（可能为 null）
ctx.world()            // 世界视图（可能为 null）
ctx.lifecycle()        // 生命周期状态与监听
ctx.metrics()          // 堆 / GC / 线程快照
ctx.performance()      // TPS / tick 延迟 / FPS
ctx.getCapability(X.class)
```

`registry()` 与 `world()` **可能为 null**，必须判空：

```java
var registry = ctx.registry();
if (registry == null) {
    ctx.logger().warning("注册表未装配，跳过内容注册");
    return;
}
```

## 5. 事件

```java
ctx.events().subscribe(TickEvent.class, e -> {
    if (e.isEnd() && e.tick() % 20 == 0) {
        ctx.logger().info("t=" + e.tick() + " cost=" + e.durationMs() + "ms");
    }
});
```

平台事件词汇：

| 事件 | 频率 | 用途 |
|---|---|---|
| `TickEvent` | 每 tick 2 次（`START` / `END`） | 每 tick 逻辑 |
| `ModLifecycleEvent` | 每个 Mod 每次迁移 | 等待其他 Mod 就绪 |
| `PlatformEvent` | 4 次（启动 / 就绪 / 停止中 / 已停止） | 全局一次性工作 |

`START` 时世界尚未被本 tick 修改，`END` 时本 tick 的全部变更已落地。**只在 `isEnd()` 时计数**才是真实 TPS。

**超类型订阅有效**：

```java
ctx.events().subscribe(Object.class, e -> log.info("event: " + e.getClass().getSimpleName()));
```

派发顺序是「精确类型 → 父类 → 接口 → Object」。订阅 `Object.class` 会收到一切事件。

订阅返回 `Subscription`，可退订：

```java
Subscription sub = ctx.events().subscribe(TickEvent.class, this::onTick);
sub.unsubscribe();
```

**Mod 停止时平台会自动注销它注册的全部监听器**——不需要手动清理。但如果你用 `Mili.events()`（无归属视图）注册，就必须自己管。

## 6. 调度

### 按墙钟时间

```java
ctx.scheduler().submit(() -> heavyWork());
ctx.scheduler().submit(() -> urgentWork(), TaskPriority.HIGH);

TaskHandle h = ctx.scheduler().submitRepeating(
        () -> poll(), 1000, 5000, TaskPriority.NORMAL);
h.cancel();
```

`submitRepeating` 的参数是**毫秒**。周期任务在共享计时器上执行，不占用工作线程池。

### 按 tick 计数

```java
ctx.tick().runNextTick(() -> ctx.logger().info("next tick"));
ctx.tick().runAfterTicks(20, () -> ctx.logger().info("1 second later"));
TickTask every = ctx.tick().runEveryTick(() -> pollInput());
every.cancel();
```

两者的区别在游戏卡顿时会显现：`runAfterTicks(20)` 仍然是 20 个逻辑 tick，而 `submitRepeating(..., 1000, 1000)` 会变成 20 tick 以上。

### 线程约束

- `scheduler` 的回调在线程池线程上执行——**不要在里面碰游戏状态**。
- `tick` 的回调在游戏主线程上执行——可以做游戏相关的事，但不要做重活（会直接吃掉 tick 预算）。

需要「后台算完、主线程应用」时：

```java
ctx.scheduler().submit(() -> {
    var result = compute();
    ctx.tick().runNextTick(() -> apply(result));
});
```

## 7. 跨 Mod 服务

提供方：

```java
public interface EconomyApi {
    boolean transfer(String from, String to, int amount);
}

// 在 initialize 里
ctx.services().provide(EconomyApi.class, new MyEconomy(ctx));
```

消费方：

```java
ctx.services().discover(EconomyApi.class).ifPresent(api -> api.transfer("a", "b", 100));

// 或者：缺失即失败
EconomyApi api = ctx.services().require(EconomyApi.class);
```

规则：
- **同一接口只允许一个提供方。** 两个 Mod 提供同一接口会在 `provide` 时抛 `IllegalStateException`，错误信息给出双方 scope id。
- **同一 Mod 重复发布视为替换自己的实现**（热重载、重新初始化场景）。
- **提供方 Scope 停止后 `discover` 返回空**，消费方不需要做任何清理。
- `require` 缺失时抛 `ServiceUnavailableException` 并列出已注册的服务类型。

## 8. 内容注册

```java
var registry = ctx.registry();

BlockHandle crystal = registry.block(BlockSpec.builder("crystal/aurora")
        .material(BlockSpec.Material.SOLID)
        .hardness(3.0f)
        .requiresTool(true)
        .lightLevel(11)
        .texture("all", "example:block/aurora")
        .build());

registry.item(ItemSpec.builder("shard")
        .maxStackSize(64)
        .texture("example:item/shard")
        .group("mili")
        .build());
```

路径是**相对命名空间**的：`"crystal/aurora"` 的完整 id 是 `example:crystal/aurora`。

**注册窗口**：只在 Mod 的 `initialize()` 期间开放。平台在世界加载前关闭它（Minecraft 的注册表在那一刻冻结）。窗口关闭后调用 `block` / `item` 抛 `IllegalStateException`——不会静默丢弃。

**资源必须自己打包**：`assets/<modId>/textures/**`、`assets/<modId>/models/**`、`assets/<modId>/blockstates/**`、`assets/<modId>/lang/*.json`。平台会把 Mod JAR 挂进原版资源包体系。

## 9. 配置与存储

### 配置（玩家会改）

```java
ModConfig cfg = ctx.config();
int volume = cfg.getInt("volume", 80);
boolean fancy = cfg.getBoolean("fancy", true);
cfg.setInt("volume", 60);
cfg.save();   // 返回写入路径
```

文件：`config/mili/<modId>.properties`（UTF-8）。

解析失败（把 `"abc"` 读成 int）返回默认值——手改配置的玩家不该得到崩溃。

### 存储（平台托管）

```java
ModStorage st = ctx.storage();
int launches = st.getInt("launches", 0) + 1;
st.put("launches", String.valueOf(launches));
st.flush();
```

文件：`config/mili/storage/<modId>.properties`。平台在 Mod 的 Scope 关闭时自动 flush。

区别：config 的键是对外契约（玩家会看到、会改），storage 的键是内部状态（玩家不该动）。

## 10. 权限

```java
if (ctx.permissions().hasPermission(Permission.NETWORK_ACCESS)) {
    startNetwork();
} else {
    ctx.logger().info("无网络权限，离线模式");
}
```

默认策略：

| 权限 | 默认 |
|---|---|
| `resource.read` / `resource.write` | ✅ 允许 |
| `intermod.communication` | ✅ 允许 |
| `network.access` / `native.access` | ❌ 拒绝 |
| `capability.register` | ❌ 拒绝 |
| `transform.*` / `transform.overwrite` | ❌ 拒绝 |

Mod **不能**注册策略（`registerPolicy` 抛 `UnsupportedOperationException`）——那等于自己给自己发权限。需要额外权限时在文档中说明，由用户在平台配置中放行。

## 11. 客户端：界面与按键

```java
ScreenService.open(ScreenSpec.builder("example:console", "控制台")
        .size(280, 134)
        .pauseGame(false)
        .hudMode(true)
        .label("status", "运行中", 12, 10)
        .button("close", "关闭", 12, 104, 256, 20)
        .build(), new ScreenListener() {
            @Override public void onClick(String widgetId) {
                if ("close".equals(widgetId)) ScreenService.closeCurrent();
            }
            @Override public void onClose(boolean cancelled) { /* ... */ }
        });

KeyBindingService.register(
        new KeyBindingSpec("example:console", "Example", "J"),
        type -> { if (type == KeyEventType.PRESS) openConsole(); });
```

在服务端调用会抛 `ScreenUnavailableException` / `KeyBindingUnavailableException`。**先判环境**：

```java
if (ctx.environment().isClient()) { ... }
```

按键事件只在「游戏内」派发——打开界面时按键被该界面消费。这是原版行为，不是平台缺陷。

## 12. 字节码转换

如果确实需要注入 Minecraft 方法，用注解而不是直接操作 ASM（Mod 的 classpath 里没有 ASM）：

```java
@org.loader.api.transform.annotation.MiliTransformer(target = "minecraft.server.tick")
public final class MyHooks {

    @org.loader.api.transform.annotation.MiliInject(at = InjectionPoint.HEAD)
    public static void onTick(InjectionContext ctx) {
        // 每 tick 一次，在世界 tick 之前
    }
}
```

可用符号名见 [API_REFERENCE.md](API_REFERENCE.md#可用逻辑符号名)。

平台在**加载期**校验：
- 回调必须 `static`（注入生成 `INVOKESTATIC`）；
- `HEAD` / `RETURN` 的回调必须返回 `void`（否则栈失衡）；
- `MODIFY_RETURN` 的回调必须返回目标方法的返回类型；
- `BEFORE_INVOKE` / `AFTER_INVOKE` 必须给 `method` 与 `descriptor`；
- 符号名必须存在于符号表。

任一条不满足都在加载期抛 `TransformationException`，错误信息指向你的类与方法，而不是在 `defineClass` 时抛一个指向 Minecraft 的 `VerifyError`。

转换是高权限能力，默认拒绝（`transform.class` 等）。平台自身的转换器走 `CORE` 阶段，Mod 的转换器走 `MOD` 阶段——CORE 阶段占用的目标，MOD 阶段不能覆盖。

## 13. 测试

`mili-test` 提供纯 JVM 的测试基类，不需要启动 Minecraft：

```java
class MyModTest extends org.loader.test.MiliTest {

    @Test
    void schedulerWorks() {
        var mod = mili().installMod("example");
        assertCompleted(mod.scheduler().submit(() -> {}));
    }
}
```

注意：`MiliTestRuntime` 没有 tick 引擎，`ctx.tick()` 会返回 `isAvailable() == false` 的实现。tick 相关逻辑需要在真机或集成测试里验证。

## 14. 从 ABI 1 迁移到 ABI 2

ABI 2 是纯新增的契约升级，没有方法被移除或改签名。迁移步骤：

1. 把 `mod.json` 的 `"abi": 1` 改为 `"abi": 2`。
2. 换成新的 `mili-abi` JAR 重新编译。

需要留意的行为变化：

| 项 | ABI 1 | ABI 2 | 需要做什么 |
|---|---|---|---|
| `TaskHandle.state()` | 恒为 `QUEUED` | 实时状态 | 为绕开这个 bug 写的 `future().isDone()` 判断可以简化为 `state()` |
| `Lifecycle.removeListener()` | 抛异常 | 正常工作 | 之前被迫用 `isActive()` 守卫的代码可以改成正常退订 |
| `Scheduler.close()` | 空操作 | 取消本 Mod 任务 | 如果你依赖「close 之后任务继续跑」，需要改 |
| `EventBus` 类型匹配 | 仅精确类 | 含超类型 | 之前「每种具体类型各订阅一次」的写法可以合并 |
| `ctx.events()` | Mod 私有总线 | 平台共享总线 | 私有事件现在对其他 Mod 可见（按类型过滤）。需要真正私有的状态请放 `storage()` 或自己的字段 |
| `ctx.resources()` | 读不存在的路径 | JAR 优先 | 之前把资源解压到磁盘的 workaround 可以删掉 |
| Mod 的 Scope 状态 | 永远 `DISCOVERED` | 初始化后 `RUNNING` | 依赖 `lifecycle().state()` 的判断现在会按预期工作 |

## 15. 常见错误

| 症状 | 原因 | 定位 |
|---|---|---|
| 游戏正常启动但 Mod 内容不存在 | Mod 放错目录 | 日志里搜 `Mod 目录:`；应为 `<dist>/mods` |
| `MOD_PLATFORM_MISSING` | `mod.json` 缺 `mili` 段 | 补上三元组 |
| `MOD_ABI_MISMATCH` | 编译用的 ABI 与平台不一致 | 换成平台同版本的 `mili-abi` 重编译 |
| `MOD_ENTRYPOINT_INVALID` | 入口类没实现 `Mod` / 是抽象类 / 无无参构造器 | 错误信息里有具体原因 |
| `NoClassDefFoundError` | Mod 里打了平台或 ASM 的类 | 只依赖 `mili-abi`，其余 `compileOnly` |
| 订阅了事件但收不到 | 订阅了从未被发布的类型 | 检查平台是否真的发布该类型（`hasListeners` 只说明有人订阅） |
| `ctx.tick()` 抛异常 | 当前环境没有 tick 源 | 检查 `isAvailable()`；主菜单阶段无 tick |
| `IllegalStateException: 注册窗口已关闭` | 在 `initialize()` 之后注册内容 | 所有内容注册必须在 `initialize()` 内完成 |
