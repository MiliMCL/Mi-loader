# ADR-0010: Observe Before Parallelize

第一次 Minecraft 26.x Tick 集成只观察，不直接并行化 Minecraft 主 Tick。

原因：Minecraft mutable state 的真实线程语义必须先通过字节码和运行时确认。

未来并行 Tick 必须重新定义 ownership、同步、world partition、entity ownership、network ordering 和 deterministic behavior。
