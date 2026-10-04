# Mili Runtime Client Mod Loader

A from-scratch Minecraft 26.2 Client Mod Loader built on Mili Runtime.

**NOT** a Fabric clone. **NOT** a Bukkit/Paper/Folia server. **NOT** a Mixin framework.

## Quick Start

### Build
```bash
gradle clean build
```

### Run
```bash
java -jar build/libs/mili-runtime-loader-0.1.0-SNAPSHOT.jar <game-dir>
```

The game directory should contain:
- `26.2.jar` (Mojang-signed Minecraft 26.2 Client JAR)
- `libraries/` (dependencies, resolved automatically from MC manifest JSON)
- `mods/` (your Mili mods, each a JAR with `mod.json` + entrypoint class)

### Write a Mod

**1.** Create a Java class with `initialize(ModContext ctx)`:

```java
import org.loader.runtime.mod.ModContext;
import org.loader.runtime.scheduler.TaskPriority;

public class MyMod {
    public void initialize(ModContext ctx) {
        ctx.logger().info("MyMod loaded!");

        // Schedule async work
        ctx.scheduler().submit(ctx.scope(), () -> {
            ctx.logger().info("Hello from scheduler!");
        }, TaskPriority.NORMAL);

        // Subscribe to events
        ctx.events().addListener(String.class, msg -> {
            ctx.logger().info("Event: " + msg);
        });

        // Register a resource (auto-cleanup on mod stop)
        ctx.scope().registerResource(new MyResource());
    }
}

class MyResource implements org.loader.runtime.kernel.Resource {
    private volatile boolean closed = false;
    public String id() { return "my-mod:resource"; }
    public org.loader.runtime.kernel.Scope owner() { return null; }
    public boolean isClosed() { return closed; }
    public void close() { closed = true; }
}
```

**2.** Create `src/main/resources/META-INF/mod.json`:

```json
{
  "id": "mymod",
  "name": "My Mod",
  "version": "1.0.0",
  "description": "A brief description.",
  "mainClass": "com.example.MyMod",
  "depends": []
}
```

**3.** Package and drop into the `mods/` directory of your game folder.

---

## Architecture

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
        |     +-- ModScope:testmod
        |     +-- ModScope:example
        |
        v
Minecraft 26.2 Client (in-process URLClassLoader)
        |
        v
ClientTickPoller (reflection-based, NO Mixin)
        |
        v
EventBus -> all subscribed mods
```

## Mod SDK (ModContext)

```java
ModContext ctx = ...;
ctx.modId();          // Mod ID from mod.json
ctx.manifest();       // Full manifest record
ctx.scope();          // Mod's own Scope (lifecycle aware)
ctx.isActive();       // True if scope is active
ctx.classLoader();    // Isolated ClassLoader
ctx.scheduler();      // Scope-bound Scheduler
ctx.events();         // Scope-bound EventBus (auto-cleanup)
ctx.registry();       // Scope-bound registry
ctx.resources();      // Resource manager (filesystem)
ctx.createConfig(""); // Create/load config
ctx.environment();    // RuntimeEnvironment (CLIENT/SERVER)
ctx.logger();         // Mod-scoped logger
ctx.getCapability(Class); // Capability lookup
```

## Lifecycle

```
DISCOVERED -> RESOLVED -> LOADED -> INITIALIZED -> REGISTERED -> RUNNING -> STOPPING -> STOPPED
                                              \-> FAILED
```

Mod stop triggers:
1. Cancel all scheduler tasks
2. Unsubscribe all events
3. Revoke capabilities
4. Close all resources
5. Close ClassLoader

## Verified

- Real Minecraft 26.2 Client boot (LWJGL 3.4.1 + OpenGL)
- Real Mod loading via ModContext SDK
- 143+ unit/integration tests passing
- Second start: no thread/classloader/resource leaks

## Anti-Goals

- NOT Fabric: no Mixin, no LaunchWrapper, no Fabric API
- NOT Server: no Bukkit/Paper/Folia/Plugin
- NOT a compatibility layer: Mili mods use Mili SDK only

## Documentation

- [`docs/API_REFERENCE.md`](docs/API_REFERENCE.md) -- Full API reference with examples
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) -- Runtime architecture overview
- [`docs/MOD_DEVELOPMENT.md`](docs/MOD_DEVELOPMENT.md) -- Mod author guide
"# Mi-loader" 
