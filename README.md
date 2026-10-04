# Mili Client Mod Loader

Minecraft 26.2 客户端 Mod 加载器，基于 Mili 自研 Runtime。

**不是** Fabric 复刻，**不是** Bukkit/Paper，**不是** Mixin 框架。

## 快速开始

### 构建

```bash
gradle clean build
```

### 运行

```bash
java -jar build/libs/minecraft-runtime-0.1.0-SNAPSHOT.jar <game-dir>
```

### 开发 Mod

1. 创建 Java 类实现 `Mod` 接口
2. 创建 `META-INF/mod.json`
3. 编译打包为 JAR
4. 放入 `mods/` 文件夹

## 项目结构

```
E:\loader\
├── build/libs/minecraft-runtime-0.1.0-SNAPSHOT.jar
├── src/main/java/org/loader/
│   ├── api/          (公共 API 层)
│   ├── runtime/      (Runtime 内核)
│   ├── loader/       (Loader + GameProvider)
│   ├── minecraft/    (Minecraft 集成)
│   ├── service/      (EventBus, Registry, Configuration)
│   └── mod/          (Mod SDK 实现)
├── testmod/          (测试 Mod)
├── example-mod/      (API-only 示例)
├── mod-template/     (第三方开发模板)
└── docs/             (文档)
```

## Mod SDK (ModContext)

| API | 说明 |
|-----|------|
| `ctx.modId()` | Mod 唯一标识 |
| `ctx.manifest()` | Mod 元数据 |
| `ctx.scope()` | Mod 作用域 |
| `ctx.lifecycle()` | 生命周期监听 |
| `ctx.classLoader()` | 隔离的 ClassLoader |
| `ctx.scheduler()` | 任务调度 |
| `ctx.events()` | 事件总线 |
| `ctx.resources()` | 资源管理 |
| `ctx.capabilities()` | 能力系统 |
| `ctx.permissions()` | 权限系统 |
| `ctx.environment()` | 环境信息 |
| `ctx.logger()` | Mod 日志 |

## 生命周期

```
DISCOVERED → RESOLVED → LOADED → INITIALIZED → REGISTERED → RUNNING → STOPPING → STOPPED
                                                                              ↘ FAILED
```

Mod 停止时自动：取消任务、取消事件订阅、撤销能力、关闭资源、关闭 ClassLoader。

## 架构

```
Minecraft Launcher
        ↓
Mili Client Loader
        ↓
Mili Runtime (Scope tree)
  +-- ClientScope
        +-- ModScope:mod-a
        +-- ModScope:mod-b
        ↓
Minecraft 26.2 Client (in-process URLClassLoader, no Mixin)
        ↓
ClientTickPoller (reflection bridge) → EventBus → 订阅的 Mod
```

## 文档

- [ARCHITECTURE.md](docs/ARCHITECTURE.md) — 完整架构
- [MOD_DEVELOPMENT.md](docs/MOD_DEVELOPMENT.md) — Mod 开发指南
- [API_REFERENCE.md](docs/API_REFERENCE.md) — API 参考

## 已验证

- ✅ 真实 MC 26.2 Client 启动 (LWJGL 3.4.1, OpenGL, NVIDIA GPU)
- ✅ 真实 Mod 加载 (ModContext, Scope, EventBus, Scheduler, Resource)
- ✅ 143 测试零回归
- ✅ 失败隔离
- ✅ 二次启动无泄漏
