# Minecraft 26.x Bootstrap

目标：真实 Minecraft 启动过程中可靠创建 Runtime。

推荐流程：

```text
Launch → Detect 26.x → Create Runtime → Root Scope → Config → Discover Mods → Resolve → Init Minecraft Bridge → Start Minecraft → RUNNING
```

实际顺序以逆向结果为准。

Bootstrap 必须幂等：不能创建第二个 Runtime、重复 Scheduler、重复 subscriptions 或重复 listener。

startup failure 必须记录诊断、停止 Runtime、释放资源并返回失败。
