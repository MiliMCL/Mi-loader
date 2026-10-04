# Minecraft 26.x Entrypoints

| Purpose | Class | Method | Descriptor | Evidence |
|---|---|---|---|---|
| launcher | TBD | TBD | TBD | |
| bootstrap | TBD | TBD | TBD | |
| server start | TBD | TBD | TBD | |
| tick | TBD | TBD | TBD | |
| shutdown | TBD | TBD | TBD | |

不要用旧版本类名填 TBD。若存在 wrapper 链，完整记录：Launcher → Bootstrap → Game → Server。

入口选择优先级：稳定入口 > 生命周期 hook > 已验证 instrumentation > Mixin/bytecode > reflection > agent/native。
