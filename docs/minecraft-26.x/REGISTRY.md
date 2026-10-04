# Minecraft 26.x Registry

Minecraft Registry 与 Runtime Registry 是不同抽象：

```text
Minecraft Registry ↔ Registry Bridge ↔ Runtime Registry API
```

先识别 bootstrap、freeze、dynamic registry、reload behavior，再决定安全注册点。

禁止在 registry freeze 后盲目修改。

Mod 不应拿到任意内部 registry object，只能使用 Runtime API 暴露的能力。
