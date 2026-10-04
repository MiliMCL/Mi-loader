# Mili Mod 开发模板

这是一个完整的 Mili Mod 开发模板，演示了 **Mili Public API** 的所有核心功能。

## 快速开始

### 1. 复制模板

```bash
cp -r mod-template my-mod
cd my-mod
```

### 2. 修改 mod.json

编辑 `src/main/resources/META-INF/mod.json`：

```json
{
  "id": "my-mod",
  "name": "My First Mod",
  "version": "1.0.0",
  "entrypoint": "com.example.mymod.MyMod",
  "dependencies": []
}
```

### 3. 实现你的 Mod

编辑 `src/main/java/com/example/mod/TemplateMod.java`，保留需要的 API 演示代码，删除不需要的部分。

### 4. 构建并部署

```bash
# 复制到 game/mods/ 目录
cp build/libs/template-1.0.0.jar /path/to/mods/template.jar

# 运行 Minecraft
java -jar minecraft-runtime-0.1.0-SNAPSHOT.jar <game-dir>
```

## API 覆盖

| 类别 | API | 说明 |
|------|-----|------|
| 入口 | `initialize(ModContext ctx)` | Mod 生命周期单一入口（无旧 fallback） |
| 上下文 | `ModContext` | 核心 API 入口 |
| 元数据 | `ctx.metadata()` | Mod ID、版本等 |
| 作用域 | `ctx.scope()` | Mod 独立的 Scope |
| 生命周期 | `ctx.lifecycle()` | 9 种状态 + 监听器 |
| 事件 | `ctx.events()` | 订阅/发布事件 |
| 调度 | `ctx.scheduler()` | 一次性/周期/延迟任务 |
| 资源 | `ctx.resources()` | 注册/查找资源 |
| 能力 | `ctx.capabilities()` | 请求/检查能力 |
| 权限 | `ctx.permissions()` | 检查权限 |
| 环境 | `ctx.environment()` | 客户端/服务端信息 |
| 日志 | `ctx.logger()` | 6 种日志级别 |

## 目录结构

```
mod-template/
├── build.gradle              # Gradle 构建脚本
├── settings.gradle           # 项目设置
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/mod/
│       │       └── TemplateMod.java   # Mod 主类
│       └── resources/
│           └── META-INF/
│               └── mod.json          # Mod 元数据
└── build/
    └── libs/
        └── template-1.0.0.jar          # 构建产物
```

## 最佳实践

1. **资源必须注册** — 所有需要清理的资源（文件、网络、监听器）都应该通过 `scope.registerResource()` 或 `resources().register()` 注册，确保 Mod 停止时自动清理。

2. **避免长任务阻塞 EventBus** — EventBus 回调中不要执行耗时操作，使用 `scheduler.submit()` 异步执行。

3. **Capability 请求尽早** — 在 `initialize` 中请求所需 Capability，如果被拒绝应优雅降级。

4. **生命周期监听器** — 监听 `STOPPING` 事件来做最后的清理工作。

5. **避免直接依赖 Minecraft 类** — 通过 Mili API 访问 Runtime，不要 `import net.minecraft.*`。

## 下一步

1. 复制此模板
2. 修改 `mod.json` 中的元数据
3. 保留需要的 API 代码
4. 添加你自己的逻辑
5. 构建部署

---

Mili Client Mod Loader - 公共 API 开发模板
