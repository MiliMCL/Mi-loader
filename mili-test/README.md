# mili-test — Mili Mod 纯 JVM 测试框架

不启动 Minecraft、不需要图形会话、只用真实 Runtime 实现（不用 Mock）测试
Mod 逻辑的最小测试框架（MD 里程碑 A4）。

## 引入

```kotlin
// 你的 Mod 的 build.gradle.kts
dependencies {
    testImplementation("org.loader:mili-test:0.1.0") // 随平台发布
}
```

> 本模块只作为**测试依赖**存在；它永远不会进入运行时 ABI，
> 生产模块不依赖它。

## 快速开始

```java
class MyModTest extends MiliTest {   // 基类管理 Runtime 生命周期

    @Test
    void 我的任务随Scope关闭而取消() {
        var mod = mili().installMod("mymod");           // 独立 Scope + 真实 Scheduler/EventBus
        var handle = mod.scheduler().submit(mod.scope(), this::doWork);
        mod.close();                                    // 复现 Mod 卸载语义
        assertCancelled(handle);                        // Scope 关闭 → 任务取消
    }

    @Test
    void 事件按顺序到达() {
        var mod = mili().installMod("mymod");
        try (var rec = EventRecorder.on(mod.eventBus(), MyEvent.class)) {
            mod.eventBus().post(new MyEvent("a"));
            mod.eventBus().post(new MyEvent("b"));
            assertEquals(List.of("a", "b"), rec.events().stream().map(MyEvent::msg).toList());
        }                                                 // try-with-resources 自动退订
    }
}
```

## 提供的能力

| 入口 | 说明 |
|---|---|
| `MiliTestRuntime.create()` | 创建并启动隔离测试 Runtime（真实实现） |
| `installMod(id)` | 注册 fixture Mod：独立 Scope（自动迁移到 RUNNING）+ 绑定 Scope 的 Scheduler（2 线程，与生产 ModContext 一致）与 EventBus |
| `newScope(id)` | 建一个测试 Scope |
| `eventBus(id)` / `scheduler(id)` | 根 Scope 级共享实例 |
| `EventRecorder.on(bus, type)` | 记录事件、`awaitCount` 等待、try-with-resources 退订 |
| `MiliTest` 基类 | 生命周期管理 + `assertCompleted/assertCancelled/assertFailed` |

## 语义边界（诚实声明）

- **虚拟 tick**：`TickEngine` 目前是真实墙钟、单线程绑定驱动，没有可步进的
  虚拟时间 API。本框架 v1 不提供 tick 推进；tick 场景请在测试线程上直接驱动
  真实引擎，或等待动画时间基（里程碑 C2）引入虚拟时钟后统一覆盖。
- **MC 桥接**：本框架不触碰 Minecraft（这是特性，不是缺陷）。涉及 MC 行为的
  测试请使用真实客户端的集成测试（见 CI 的 smoke test）。
- **取消语义**：取消是协作式的——正在运行的任务通过中断传播取消，
  已完成的任务不可取消（与 `TaskHandle` 的生产语义一致）。

## 自检

```bash
./gradlew :mili-test:test   # HarnessSelfTest 即最小示例 Mod 的验收用例
```
