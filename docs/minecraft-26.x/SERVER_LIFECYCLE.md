# Minecraft 26.x Server Lifecycle

目标映射：

```text
Minecraft bootstrap → DISCOVERED → RESOLVED → LOADED → INITIALIZED → REGISTERED → RUNNING
shutdown → STOPPING → STOPPED
```

Runtime 不得提前宣布 RUNNING。

所有 Minecraft shutdown path（正常退出、server stop、fatal startup failure、JVM shutdown hook）都必须最终触发 Runtime stop。

Minecraft → Runtime 的启动/tick/network 错误必须经过 boundary；Runtime → Minecraft 的 mod/scheduler/capability 错误也必须有明确 policy，不能静默吞掉。
