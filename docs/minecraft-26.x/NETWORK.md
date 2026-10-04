# Minecraft 26.x Network Integration

Minecraft 原生网络由 Minecraft Adapter 管理；Runtime network API 是另一层能力。

每个 Runtime-owned connection 必须有 owner Scope、creation time、state、close handler。Scope 关闭时关闭其 connection。

未经 capability + permission，不允许任意监听端口或创建网络连接。
