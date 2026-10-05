# TickEngine 与 TickContract

> 权威实现：`mili-runtime/.../tick/`、`mili-minecraft-integration/.../minecraft/TickBridge.java`
> 当前状态见 [CURRENT.md](CURRENT.md)

---

## 为什么 TickContract 必须是实例

历史实现里`TickContract` 是个**纯静态类**，只有两个 record 和一个 enum：

```java
public final class TickContract {
    public enum TickPhase { TICK_START, PRE_TICK, ... }
    public record TickBudget(...) { ... }
    public record TickMetrics(...) { ... }
}
```

它无法实例化，因此**根本无法表达"状态推进"** —— 而这正是 tick 协议的核心语义。

与之配套的 `TickContractTest` 断言的是：

```java
assertEquals(50, budget.tickPeriodMs());
assertEquals(25, budget.schedulerBudgetMs());   // 50 / 2
assertEquals(12, budget.ioBudgetMs());          // 50 / 4
```

这是**验证字段能做减法**，不是验证 tick 能跑。绿灯是假的。

现在 TickContract 是可实例的执行信封，测试覆盖阶段机、任务顺序、
异常传播、取消、deadline、await 屏障与引擎端到端。

---

## 执行模型

```text
beginTick()                          新建契约，TICK_START → PRE_TICK，触发 PRE_TICK 回调
  │
  ├─ advanceToSchedule()             触发 SCHEDULE 回调 —— Mod 在此提交任务
  ├─ advanceToCoreTick()             触发 CORE_TICK 回调 —— MC 真实世界/实体 tick
  │    └─ runPendingTasks()          在驱动线程按提交顺序执行
  ├─ advanceToAsyncCompletion(t)     awaitAllTasks(t)，触发 ASYNC_COMPLETION
  ├─ advanceTo(TICK_END)路径         POST_TICK 回调 → TICK_END 回调
  │
endTick()                            complete() → TickMetrics
```

**endTick() 会自动补齐未推进的后续阶段**，因此即使调用方只做
`beginTick(); endTick();` 也能得到完整阶段序列。

---

## 责任划分

| 职责 | 归属 | 说明 |
|---|---|---|
| 创建契约 | TickEngine | 每 tick 一个新实例 |
| 推进阶段 | TickEngine | 唯一的 `advance()` 调用方 |
| 提交任务 | 任意线程 | `submitTask()` 线程安全 |
| 等待完成 | TickEngine | 阶段屏障 `awaitAllTasks()` |
| 处理异常 | TickEngine | 捕获 → `contract.fail()`，**不逃逸** |
| 完成 tick | TickEngine | `complete()` 产出指标 |
| deadline | TickEngine | 巡检 `isPastDeadline()` |
| cancellation | 任意线程 | `cancel()` 中止未执行任务 |

**契约实例是单线程专属的**：只应由驱动它的那一个线程推进阶段。

---

## 阶段

```text
TICK_START → PRE_TICK → SCHEDULE → CORE_TICK → ASYNC_COMPLETION → POST_TICK → TICK_END
```

严格有序。规则：

- 只能相邻正向推进
- 不能回退
- 终阶段（TICK_END）后不可再推进
- 已完成/已取消的契约不可推进

违规抛 `IllegalStateException` 或 `TickCancelledException`。

---

## 任务

```java
TickTask submitTask(String id, Runnable work, TaskPriority priority)
```

状态机：`CREATED → RUNNING → COMPLETED | FAILED | CANCELLED | LEAKED`

**关键性质**：

1. **异常不逃逸。** `run(contract)` 捕获 Throwable，记录到任务自身
   并传播给契约，**不会打断 tick 循环**。
2. **失败隔离。** 一个任务失败后，后续任务照常执行。
3. **取消协作。** `cancel()` 把 CREATED 任务标记为 CANCELLED，
   已在 RUNNING 的任务协作式停止。
4. **末端拒绝。** POST_TICK / TICK_END 阶段拒绝提交新任务。

---

## deadline

```java
boolean isPastDeadline();   // 是否已超期
long remainingMs();         // 剩余预算，不为负
boolean missedDeadline();   // complete() 时写入指标
```

deadline 是**观察与上报**，不是强制中断。强制中断会导致世界处于
不一致状态 —— 这是刻意的取舍：宁可超期也不产生半完成 tick。

---

## await 屏障

```java
boolean awaitAllTasks(long timeoutMs);
```

在阶段屏障等待所有任务结束。**超时返回 false，绝不永久阻塞**
——死锁会让整个服务器停摆，比超期严重得多。

---

## TickBridge

`TickBridge` 是 Mili 侧的接入点：

```java
TickContract beginTick();          // 主线程，游戏 tick 开始
TickContract.TickMetrics endTick(); // 主线程，游戏 tick 结束
void onTick(Runnable work);        // Mod 提交本 tick 工作
```

**强制约束**：`beginTick()` 绑定首次调用的线程（Minecraft 主线程），
跨线程调用抛 `IllegalStateException`。这是并行化前的不变量。

不在 tick 内时`endTick()` 返回 null（而非抛异常）——
因为游戏退出路径可能不经过配对调用。

---

## MinecraftTickSource

接口，定义真实 tick 接入点：

```java
public interface MinecraftTickSource {
    TickBridge beginTick();
    void endTick();
    boolean isReady();
    String describe();
}
```

**不变量**：
- `beginTick` / `endTick` 必须同线程成对调用
- 该线程即 Minecraft 主线程
- 异常不得逃逸到游戏主循环

抽象这个接口的用意：**tick 正确性可以在不启动 Minecraft 的情况下
被完整验证** —— 这是 Phase 6 单线程正确性测试的基础。

接入点候选（优先级从高到低）：

| 环境 | 锚点 |
|---|---|
| SERVER / MULTIPLAYER | `net.minecraft.server.MinecraftServer#tickChildren(long)` |
| CLIENT / SINGLEPLAYER | `net.minecraft.client.multiplayer.ClientLevel#tick(BooleanSupplier)` |

---

## Scheduler 与 Tick 的关系

Scheduler 负责**跨 tick 的异步工作**，TickEngine 负责**单次 tick 内的执行信封**。

两者在阶段屏障处收敛：`ASYNC_COMPLETION` 阶段的
`awaitAllTasks` 是异步工作必须落地的位置。

Scheduler 已修复的两个真实缺陷（见 CURRENT.md）：

1. 优先级被 FIFO 线程池抹平 → 现按优先级出队
2. `submitRepeating` 用 `Thread.sleep` 占池线程 → 现用独立计时器

---

## 指标

```java
engine.currentTick();          // 已执行 tick 数
engine.averageTickMs();        // 平均耗时
engine.maxTickDurationMs();    // 峰值
engine.currentTps();           // 真实 TPS（基于墙钟）
engine.missedDeadlineCount();  // 超期次数
engine.failedTickCount();      // 出错次数
engine.recentMetrics();        // 最近 200 次指标
engine.diagnostics();          // 人类可读摘要
```

回答"为什么这个 tick 慢"目前只能到 tick 级别。
更细的（哪个 Mod / 哪个 Region / 哪个 Entity）需要
Phase 9 ExecutionContext 与 Region 埋点 ——**尚未实施**。

---

## 下一步

1. **实现真实游戏 tick 入口注入** — `ReflectiveMinecraftTickSource`
   目前是骨架，需实现对 26.2 真实 tick 方法的定位与转发。
2. **真实 Minecraft smoke test** — 验证 tick 真的在跑。
3. **Scheduler↔TickEngine 整合** — 异步任务在屏障收敛。
4. **ExecutionContext 埋点** — 更细的归因。