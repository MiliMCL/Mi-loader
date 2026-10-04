# Mili Runtime Architecture

## Guiding Principles

- Mili is **NOT** a Fabric clone
- Runtime kernel (Scope/Scheduler/etc) is **NOT** Minecraft server bound
- Mods use **Mili-native** APIs, never Fabric APIs
- Clean separation: `runtime` | `loader` | `minecraft/client` | `mod`

## Package Map

```
org.loader.runtime.*         — Runtime kernel (no Minecraft deps)
org.loader.loader.*          — Loader bootstrap, discovery, classloading
org.loader.loader.game.*     — GameProvider SPI + Minecraft impl
org.loader.loader.hook.*     — EntryPointHook
org.loader.runtime.minecraft.*  — Minecraft integration bridges
org.loader.runtime.mod.*     — Mod SDK (ModContext, ModLoader, ModManifest)
org.loader.runtime.service.* — EventBus, Configuration, Registry
org.loader.runtime.scheduler.* — Scheduler
org.loader.runtime.kernel.*  — Scope, Lifecycle, Resource, Capability
```

## Runtime Kernel Capabilities

| Capability | Status |
|---|---|
| Scope tree with parent/child | ✅ |
| Lifecycle state machine (RESOLVED→STOPPED) | ✅ |
| Resource ownership + cleanup | ✅ |
| Capability tokens + revocation | ✅ |
| Permission enforcement (deny-by-default) | ✅ |
| Scheduler (structured concurrency) | ✅ |
| EventBus (priority, scope-owned) | ✅ |
| AuditLog (observability) | ✅ |

## Mod Lifecycle

```
DISCOVERED     (JAR/directory found)
     ↓
RESOLVED       (metadata parsed)
     ↓
LOADED         (ClassLoader created)
     ↓
INITIALIZED    (entrypoint invoked)
     ↓
REGISTERED     (registered with Runtime)
     ↓
RUNNING        (active in game)
     ↓
STOPPING       (shutdown initiated)
     ↓
STOPPED        (cleanup complete)
```

Error states:
- `FAILED` — entrypoint threw exception
- Failed required dependency → dependent mods NOT loaded

## Client Bridge (No Mixin)

Minecraft Client runs in-process via URLClassLoader. The `ClientTickPoller` uses *reflection* (NOT Mixin/Transform) to:
1. Access `Minecraft.getInstance()` 
2. Detect level/player state changes
3. Forward lifecycle events to `MinecraftEventBus`
4. Tick `TickBridge` for mod task scheduling

This avoids Fabric's Mixin complexity entirely.

## Verified

- ✅ `java -jar mili-runtime-loader.jar <game-dir>` → Minecraft 26.2 Client boots
- ✅ LWJGL 3.4.1 + OpenGL + NVIDIA GPU detection works
- ✅ Library resolution from MC manifest JSON (131 libs filtered to Windows x64)
- ✅ Mod discovery + ClassLoader isolation + Entrypoint invocation
- ✅ 153 unit + integration tests pass
- ✅ TestMod end-to-end loaded during real MC boot
