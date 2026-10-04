# Mili Mod Development Guide

## Quick Start

1. Implement the `Mod` interface with `onInitialize(ModContext)`:

   ```java
   package com.example;

   import org.loader.api.Mod;
   import org.loader.runtime.mod.ModContext;

   public class MyMod implements Mod {
       @Override
       public void onInitialize(ModContext ctx) {
           ctx.logger().info("MyMod loaded!");
       }
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

3. Compile, package as JAR, drop into `mods/` as `mymod.jar`.

## Project Structure

```
my-mod/
├── build.gradle
├── settings.gradle
└── src/main/
    ├── java/com/example/MyMod.java
    └── resources/META-INF/mod.json
```

## Lifecycle

```
DISCOVERED -> RESOLVED -> LOADED -> INITIALIZED -> RUNNING -> STOPPING -> STOPPED
                                              \-> FAILED
```

Each mod has its own `Scope` under `ClientScope` and its own ClassLoader.

## Mod Stop (Automatic)

When a mod stops, Mili Runtime automatically:
1. Cancels all scheduler tasks
2. Unsubscribes all event listeners
3. Revokes all capability leases
4. Closes all resources
5. Closes ClassLoader

You do NOT need to manually clean up.

## mili-native Mod Architecture

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
Runtime -> EventBus -> subscribed mods
```

## API Summary

| API | Method | Description |
|-----|--------|-------------|
| Identity | `ctx.modId()` | Mod unique ID |
| Identity | `ctx.manifest()` | Full metadata |
| Scope | `ctx.scope()` | Mod's scope |
| Scope | `ctx.scope()` | Mod's scope (state + parent) |
| Scope | `ctx.classLoader()` | Isolated ClassLoader |
| Scheduler | `ctx.scheduler()` | Scope-bound task scheduler |
| Scheduler | `ctx.submit(work)` | Submit async task |
| Events | `ctx.events()` | Scope-bound EventBus |
| Resources | `ctx.resources()` | Resource manager |
| Capabilities | `ctx.getCapability(Class)` | Request capability |
| Permissions | `ctx.permissions()` | Check permissions |
| Environment | `ctx.environment()` | Runtime information |
| Logging | `ctx.logger()` | Mod-scoped logger |

## Scheduler API

```java
Scheduler scheduler = ctx.scheduler();
Scope scope = ctx.scope();

// One-shot task
scheduler.submit(scope, () -> { ... }, TaskPriority.NORMAL);

// Repeating task (delayMs, periodMs)
scheduler.submitRepeating(scope, () -> { ... }, 0L, 1000L, TaskPriority.NORMAL);

// Cancel a task
TaskHandle handle = ...;
scheduler.cancel(handle);

// Auto-cancel on scope shutdown
// (no manual cleanup needed)
```

## EventBus API

```java
EventBus bus = ctx.events();

// Subscribe (auto-unsubscribe on mod stop)
bus.addListener(ClientTickEvent.class, event -> { ... });
bus.addListener(String.class, msg -> { ... });

// Post an event
bus.post("hello");
bus.postAsync("hello"); // async

// Generic listener
bus.addListener(Object.class, e -> { ... });
```

## Events Available

| Event class | Description |
|---|---|
| `ClientStartingEvent` | Fired before MinecraftClient instance created |
| `ClientStartedEvent` | Fired after MinecraftClient ready |
| `ClientTickEvent` | Fired per client tick |
| `ClientWorldJoinEvent` | World load detected |
| `ClientWorldLeaveEvent` | World unload detected |
| `ClientPlayerJoinEvent` | Player entity created |
| `ClientPlayerLeaveEvent` | Player entity removed |
| `ClientStoppingEvent` | Client stopping |
| `ClientStoppedEvent` | Client stopped |
| `ServerStartingEvent` | Server starting |
| `ServerStartedEvent` | Server started |
| `ServerStoppingEvent` | Server stopping |
| `ServerStoppedEvent` | Server stopped |
| `ClientScreenOpenEvent` | Screen opened |

## Capability System (Deny-by-Default)

```java
// This will return Optional.empty() unless explicitly granted
var renderCap = ctx.getCapability(RenderService.class);
if (renderCap.isPresent()) {
    // Use the capability
} else {
    // Graceful degradation
}
```

## Permission System

```java
PermissionManager perms = ctx.permissions();
boolean canRead = perms.hasPermission(Permission.RESOURCE_READ);
boolean canNetwork = perms.hasPermission(Permission.NETWORK_ACCESS);
```

## ModManifest Schema

```json
{
  "id": "unique-id",
  "name": "Human Readable Name",
  "version": "1.0.0",
  "description": "Short description",
  "author": "Developer Name",
  "environment": "client",
  "entrypoint": "com.example.MyModMainClass",
  "depends": [
    { "modId": "other-mod", "required": true }
  ]
}
```

### Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique mod identifier |
| `name` | String | Yes | Display name |
| `version` | String | Yes | Semantic version |
| `author` | String | No | Author name |
| `description` | String | No | Short description |
| `environment` | String | No | `"client"` / `"common"` |
| `entrypoint` | String | Yes | Fully-qualified class name |
| `depends` | Array | No | List of dependencies |

## Distribution

Each mod JAR contains:
- Compiled classes under their package path
- `META-INF/mod.json` (metadata manifest)
- Any resources (textures, configs, etc.)

The JAR filename in `mods/` should be `{modId}.jar`.

## Building with Gradle

```groovy
plugins { java }
group = "com.example"
version = "1.0.0"
java { sourceCompatibility = JavaVersion.VERSION_25 }

dependencies {
    compileOnly(files("../build/libs/minecraft-runtime-0.1.0-SNAPSHOT.jar"))
}
```

## Best Practices

1. **Always register resources** — Files, network handles, listeners all go through `scope.registerResource()` or `resources.register()`
2. **Don't block EventBus callbacks** — Use `scheduler.submit()` for heavy work
3. **Check capabilities early** — Request at init, degrade gracefully
4. **No manual cleanup needed** — Runtime handles it on mod stop
5. **Logger shows mod ID** — All log lines are prefixed with `[Mod:yourmod]`
