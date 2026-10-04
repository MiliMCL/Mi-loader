# Mili Mod Development Guide

## Quick Start

1. Create a Java class with an entrypoint method that accepts a `ModLog` (or no-arg):
   ```java
   public class MyMod {
       public void initialize(ModLog log) {
           log.info("MyMod loaded!");
       }
       public interface ModLog { void info(String msg); }
   }
   ```

2. Create `src/main/resources/META-INF/mod.json`:
   ```json
   {
     "id": "mymod",
     "name": "My Mod",
     "version": "1.0.0",
     "environment": "client",
     "entrypoint": "com.example.MyMod",
     "depends": []
   }
   ```

3. Compile, package as JAR, drop into `mods/`.

## Lifecycle

```
DISCOVERED -> RESOLVED -> LOADED -> INITIALIZED -> RUNNING -> STOPPING -> STOPPED
                                              \-> FAILED
```

Each mod has its own `Scope` under `ClientScope` and its ownClassLoader.

## Mili-Native Mod Architecture

```
Minecraft Launcher
        |
        v
Mili Client Loader
        |
        v
Mili Runtime (Scope tree)
        |
        +-- ClientScope
        |     +-- ModScope A  (own ClassLoader)
        |     +-- ModScope B
        |     +-- ModScope C
        |
        v
Minecraft 26.2 Client
        |
        v
Mili Client Bridge (reflection, no Mixin)
        |
        v
Runtime EventBus -> all subscribed mods
```

## Events Available

| Event class | Description |
|---|---|
| `ClientStartingEvent` | Fired before MinecraftClient instance created |
| `ClientStartedEvent` | Fired after MinecraftClient ready |
| `ClientTickEvent` | Fired per client tick (via reflection poller) |
| `ClientWorldJoin/Leave` | World load/unload detection |
| `ClientPlayerJoin/Leave` | Player entity creation/destruction |
| `ClientScreenOpenEvent` | Screen change detection |
| `RenderFrame` | Frame render marker (future) |
| `KeyboardInput/MouseInput` | Input events (future) |

## ModManifest Schema

```json
{
  "id": "unique-id",
  "name": "Human Readable Name",
  "version": "1.0.0",
  "description": "Short description",
  "environment": "client",
  "entrypoint": "com.example.MyModMainClass",
  "depends": [
    { "modId": "other-mod", "required": true }
  ]
}
```

## Distribution

Each mod is a JAR containing:
- Compiled classes under their package path
- `META-INF/mod.json` (the metadata manifest)
- Any resources the mod needs (textures, configs, etc.)

The JAR filename in `mods/` should be `{modId}.jar`.
