# Minecraft 26.x World Integration

```text
World Loaded → World Scope → World Tasks/Resources → World Unloaded → Scope Close
```

世界卸载后不得创建新的 world task；world resource、event subscription、scheduler task 和缓存必须失效/释放。

默认假设 Minecraft world state 不是线程安全的。跨线程操作必须经过明确 API。

未来并行 world/region 必须重新定义 ownership，不得直接共享 mutable state。
