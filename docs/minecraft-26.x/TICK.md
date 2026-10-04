# Minecraft 26.x Tick Integration

## 第一阶段：只观察

```text
Minecraft tick
 → TickBridge.onTickStart()
 → Minecraft tick body
 → TickBridge.onTickEnd()
```

记录 tick id、线程、start/end、duration、world count、Runtime task count。

## 第二阶段
只把明确无数据竞争的工作交给 Runtime Scheduler。

## 第三阶段
若未来实现并行 Tick，必须新增 ADR 并重新定义 ownership、同步、world partition、entity ownership、network ordering、determinism。

Runtime Scheduler 支持并发，不代表 Minecraft mutable state 可以并发访问。
