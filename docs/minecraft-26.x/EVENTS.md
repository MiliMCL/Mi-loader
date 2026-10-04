# Minecraft 26.x Events

第一阶段事件：Runtime started、Minecraft ready、tick start/end、world loaded/unloaded、player joined/left、server stopping/stopped。

Minecraft internal event type 不得直接暴露给 Runtime ABI。

每个 subscription 必须属于 Scope；Scope 关闭时自动 unsubscribe。

Listener failure 默认不杀死整个 Minecraft，除非 event contract 明确规定 fatal。
