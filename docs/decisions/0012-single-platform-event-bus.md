# 0012. 全平台单一事件总线，跨 Mod 协作走显式服务代理

- **状态**: Accepted
- **日期**: 2026-10-28
- **决策人**: 维护者
- **关联**: [ABI.md](../ABI.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、ADR-0008（Native-first Mod API）、ADR-0006（Capability 与 Permission 分离）

## 背景与问题

平台在 ABI 1 时期有**四条互不相连的事件总线**：

1. 每个 Mod 自己的 `EventBus`（`ModContext.events()` 创建）
2. `MinecraftEventBridge` 的内部总线（服务端/客户端启动事件）
3. `ModCommunication` 的总线（已删除的跨 Mod 通道）
4. `TickEngine` —— 根本没有总线，只在契约里推进阶段

后果是四类**静默失效**，全部表现为「订阅成功、编译通过、运行无异常、永远收不到」：

- Mod 订阅 `Long.class` 当 tick（示例 Mod 就是这么写的），而没有任何代码发布 `Long`；
- Mod 订阅 `TickEvent` / `ClientTickEvent`，而这两个类型在生产路径上零发布者；
- Mod 想收另一个 Mod 的事件，但两边不在同一条总线上；
- 平台想观察 Mod 的就绪状态，但 Mod 的 Scope 从未进入 `RUNNING`，也没有事件。

同时，`Capability` 系统的 `Scope.getCapability` 只查自己的授权表——Mod A 无法向 Mod B 提供任何东西。跨 Mod 协作只能靠「都订阅同一个平台事件」这种间接方式，而当时连这条路也是断的。

约束：ABI 已发布（不能静默改语义）、Mod 只编译 `mili-abi`（集成模块的类型对 Mod 不可见）、平台需要在 Mod 卸载时精确注销它的监听器（ClassLoader 不能泄漏）。

## 决策

**我们将采用「全平台一条事件总线 + 每 Mod 一个带归属的视图 + 独立的服务代理」的模型。**

1. `Runtime` 持有唯一 `EventBus`，作为 `rootScope` 的资源。
2. `ModContext.events()` 在装配模式下返回这条总线；独立模式（测试）仍返回私有总线。
3. `ApiModContext.EventBusView` 负责：把契约监听器包成 runtime 监听器、记录每一对 (类型, 包装器)、作为 Scope 资源在关闭时全部摘除。
4. 派发按**类型继承链**：精确类型 → 父类 → 接口 → `Object`。
5. tick 事件由 `TickEventBridge`（挂在 `TickEngine` 的观测者上）发布到这条总线。
6. Mod 生命周期事件由 `LoaderMain` 注册的 Scope 监听器发布。
7. 跨 Mod 协作**不用事件总线当服务发现**，而是新增 `ServiceBroker`：`provide` / `discover` / `require`，按类型匹配、跨 Scope 冲突显式失败、提供方停止即失效。

## 备选方案

### 方案 A：单一平台总线 + 带归属视图（已选择）

- 优点：发布方与订阅方永远在同一条总线上，不存在「连错线」的可能；类型隔离天然限制可见范围；卸载清理有唯一归属点（Scope 资源）。
- 缺点：一个 Mod 的私有事件对其他 Mod 可见（按类型过滤）；监听器注册必须在视图层多做一层映射；`Object.class` 监听器会收到一切事件（性能与语义风险）。

### 方案 B：保留每 Mod 私有总线，增加一条「平台总线」，Mod 显式订阅两条

- 优点：私有事件真正私有。
- 缺点：Mod 必须知道「哪个事件在哪条总线上」——这正是历史上出问题的地方，而它要求 Mod 作者记住一张平台内部的路由表。**否决**：把架构复杂度转嫁给使用者。

### 方案 C：事件总线兼做服务发现（发布 `ServiceAvailableEvent`，消费方订阅后自己找）

- 优点：不新增 API。
- 缺点：服务发现变成「先到先得 + 时序敏感」——消费方在提供方发布之前订阅就永远不知道它存在，需要额外的「重放最近事件」机制。**否决**：服务发现是状态查询，不是事件流。

### 方案 D：维持现状，只补 tick 发布

- 优点：改动最小。
- 缺点：跨 Mod 协作仍然不可用；`ModCommunication` 那条死总线仍然存在。**否决**：只补一条断链，另外三条还在。

## 后果

**正面**：

- 四类静默失效同时消除，且每一种都有测试锁定（`ModEventWiringTest`、`EventBusTypeDispatchTest`、`TickEventBridgeTest`）。
- 跨 Mod 协作有了明确语义：状态用 `ServiceBroker`，事件用总线。
- 监听器生命周期有了唯一归属点，ClassLoader 泄漏风险下降。
- 超类型订阅让「观察一切事件」这种诊断需求可以直接表达。

**负面 / 代价**：

- `ctx.events()` 的语义从「私有」变成「共享」，这是 ABI 1 → 2 的升版理由之一。Mod 若把敏感状态放进事件载荷，现在会对其他 Mod 可见。文档已明确要求「真正私有的状态放 `storage()` 或自己的字段」。
- `Object.class` 监听器会收到全部事件。这是有意的（诊断录制器需要它），但代价是一个坏监听器可以观察到所有 Mod 的事件载荷。
- 每次 `post` 多一次 `ClassValue` 查找（实测可忽略，且零分配）。
- 删除 `ModCommunication` 与 `ServiceRegistry` 两个类——它们的功能被 `ServiceBroker` 覆盖。

**中立影响**：

- 集成模块的内部事件（`ClientStartedEvent` 等）现在也发布在平台总线上，但 Mod 无法引用这些类型（不在其 compile classpath 上），因此对 Mod 不可见。

## 验证方式

| 断言 | 测试 |
|---|---|
| Mod 通过 `ctx.events()` 收到 `TickEvent` | `ModEventWiringTest.modReceivesTickEvents` |
| Mod A 发布的事件 Mod B 能收到 | `ModEventWiringTest.crossModEvents` |
| Mod 停止后监听器被摘除 | `ModEventWiringTest.listenersAreRemovedWhenModStops` |
| 超类型订阅生效 | `EventBusTypeDispatchTest.supertypeListenerReceivesSubtype`、`ModEventWiringTest.supertypeSubscriptionThroughContext` |
| 监听器异常可见 | `EventBusTypeDispatchTest.listenerFailureGoesToSink` |
| 服务跨 Mod 可见 / 冲突显式失败 / 提供方停止即失效 | `ServiceBrokerTest`（6 项） |
| 零订阅零成本 | `TickEventBridgeTest.noListenersNoAllocation` |

CI 门禁：`./gradlew check`（含全部上述测试）+ `verifyVersionConstants`（ABI 2 一致性）。
