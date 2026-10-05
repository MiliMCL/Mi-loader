> # ⚠️ 历史文档 —— 不代表当前实现状态
>
> 本文件记录的是**当时**的分析，其中多处论断已被后续实现推翻。
> 判断项目现状请读 [`../architecture/CURRENT.md`](../architecture/CURRENT.md)。
> 详见 [`README.md`](README.md)。

---

# Mili Client Mod API Reference

## Table of Contents

1. [Introduction](#introduction)
2. [Getting Started](#getting-started)
3. [Mod Manifest](#mod-manifest)
4. [Mod Entry Point](#mod-entry-point)
5. [ModContext](#modcontext)
6. [Events](#events)
7. [Scheduler](#scheduler)
8. [Resources](#resources)
9. [Capabilities](#capabilities)
10. [Lifecycle](#lifecycle)
11. [Environment](#environment)
12. [Dependency System](#dependency-system)
13. [Registry](#registry)
14. [Configuration](#configuration)
15. [API Versioning](#api-versioning)
16. [Best Practices](#best-practices)

---

## Introduction

The Mili Client Mod API is the official SDK for building mods that run on top of the Mili Runtime. Every mod receives a `ModContext` instance at initialization time, which provides scoped access to:

- **Scoped execution** via the `Scheduler`
- **Typed events** via the `EventBus`
- **Capability-gated services** (rendering, input, sound)
- **Resource ownership** with automatic cleanup
- **Configuration storage** with change notifications
- **Dependency resolution** and isolation

Mili mods communicate with Minecraft through Mili-native APIs only. There is no Mixin, no LaunchWrapper, and no Fabric API compatibility layer.

### Architecture Overview

```
Minecraft Launcher
        |
        v
Mili Client Loader
        |
        v
Mili Runtime (Scope tree)
        +-- Client Scope
              +-- ModScope:mod-a  (own ClassLoader)
              +-- ModScope:mod-b
              +-- ModScope:mod-c
        |
        v
Minecraft 26.2 Client (in-process, no Mixin)
        |
        v
ClientTickPoller -> Runtime Scheduler -> EventBus -> subscribed mods
```

---

## Getting Started

### Prerequisites

- **Java 25** (or later) — Mili Client compiles and runs on Java 25+
- **Mili Runtime** — the loader JAR (`mili-runtime-loader.jar`)
- **Minecraft 26.2 Client** — the official Mojang-signed JAR

### Project Structure

```
my-mod/
|-- src/
|   |-- main/
|   |   |-- java/
|   |   |   +-- com/example/
|   |   |       +-- MyMod.java
|   |   +-- resources/
|   |       +-- META-INF/
|   |           +-- mod.json
|   +-- build.gradle (optional)
+-- my-mod.jar (output)
```

### Dependency Declaration

If building with Gradle:

```groovy
dependencies {
    compileOnly files("libs/milli-runtime-api.jar")
}
```

If building with Maven:

```xml
<dependency>
    <groupId>org.loader</groupId>
    <artifactId>mili-runtime-api</artifactId>
    <version>0.1.0</version>
    <scope>provided</scope>
</dependency>
```

The Mili API is `provided` / `compileOnly` because it is supplied by the runtime at load time.

### Build and Run

```bash
# Compile your mod
javac -cp libs/mili-runtime-api.jar -d build/classes src/main/java/com/example/MyMod.java

# Package as JAR with manifest
jar cf my-mod.jar -C build/classes . -C src/main/resources META-INF/mod.json

# Run the loader
java -jar mili-runtime-loader.jar <game-directory>
```

The `<game-directory>` must contain:

- `26.2.jar` — Mojang-signed Minecraft 26.2 Client
- `libraries/` — resolved from MC manifest JSON (handled automatically)
- `mods/` — your Mili mod JARs

---

## Mod Manifest

Each mod ships a `META-INF/mod.json` file that describes its identity, entry point, dependencies, and declared capabilities.

### Schema

```json
{
  "id": "my-mod",
  "name": "My Modification",
  "version": "1.0.0",
  "author": "YourName",
  "description": "A short description of what this mod does.",
    "entrypoint": "com.example.MyMod",
  "dependencies": [
    {
      "modId": "other-mod",
      "required": true
    }
  ]
}
```

### Field Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | String | Yes | Unique mod identifier. Must be lowercase, alphanumeric + hyphens. |
| `name` | String | Yes | Human-readable display name. |
| `version` | String | Yes | Semantic version (e.g. `"1.0.0"`). |
| `author` | String | No | Mod author name. Default: `""`. |
| `description` | String | No | Short mod description. Default: `""`. |
| `entrypoint` | String | Yes | Fully-qualified class name with `initialize(ModContext)` method. |
| `dependencies` | Array | No | List of mod dependencies. Default: `[]`. |
| `capabilities` | Array | No | Declared capability strings. Default: `[]`. |
| `modules` | Array | No | Child module names for large mods. Default: `[]`. |

### Dependency Entry Schema

```json
{
  "modId": "other-mod",
  "versionRange": "1.0.0",
  "required": true
}
```

- `modId`: ID of the dependency mod.
- `versionRange`: Minimum version constraint (future: semver ranges).
- `required`: If `true`, the mod fails to load when this dependency is missing. If `false`, the dependency is loaded if present but its absence does not block loading.

---

## Mod Entry Point

A mod implements the `Mod` interface with a single `initialize(ModContext)` method.

### Required Signature

```java
package com.example;

import org.loader.api.Mod;
import org.loader.runtime.mod.ModContext;

public class MyMod implements Mod {

    @Override
    public void initialize(ModContext ctx) {
        ctx.logger().info("MyMod initializing...");
        // Subscribe to events, schedule tasks, register resources here
    }
}
```

### Constraints

- The class must be public and have a public no-arg constructor.
- The `initialize` method must accept exactly one argument of type `ModContext`.
- Declaring exceptions in `initialize` is allowed but not required.
- The entrypoint class is loaded in an **isolated ClassLoader** scoped to your mod. You cannot access classes from other mods by default.

### Multiple Entrypoints

A large mod can declare `modules` in `mod.json`. Each module name corresponds to a child `Scope` under the parent mod scope. The main entrypoint class is always loaded first.

---

## ModContext

`ModContext` is the primary interface between your mod and the Mili Runtime. One instance is created per mod and passed to your `initialize` method.

**Package:** `org.loader.runtime.mod.ModContext`

### Metadata Methods

#### `modId() -> String`

Returns the unique identifier from your `mod.json`.

```java
String id = ctx.modId(); // "my-mod"
```

#### `manifest() -> ModManifest`

Returns the full `ModManifest` record containing all fields from `mod.json`.

```java
ModManifest manifest = ctx.manifest();
ctx.logger().info("Mod: " + manifest.name() + " v" + manifest.version());
ctx.logger().info("Author: " + manifest.author());
ctx.logger().info("Description: " + manifest.description());
```

### Scope and Lifecycle Methods

#### `scope() -> Scope`

Returns the mod `"s scope. The scope is the lifecycle boundary for this mod" s tasks, resources, and capabilities.

```java
Scope myScope = ctx.scope();
ctx.logger().info("Scope ID: " + myScope.id()); // "mod:my-mod"
```

#### `isActive() -> boolean`

Returns `true` if the mod `"s scope is still in an active state (not `STOPPING`, `STOPPED`, or `FAILED`).

```java
if (ctx.isActive()) {
    ctx.logger().info("Mod is still running, safe to submit work.");
}
```

### ClassLoader Method

#### `classLoader() -> ClassLoader`

Returns the isolated ClassLoader assigned to this mod. Mods use this to load their own classes without polluting the parent or other mods.

```java
ClassLoader loader = ctx.classLoader();
// Use for loading classes specific to your mod:
Class<?> myConfig = Class.forName("com.example.MyConfig", true, loader);
```

### Scheduler Methods

#### `scheduler() -> Scheduler`

Returns a scope-bound `Scheduler` instance. All async work should go through this scheduler rather than creating raw threads.

```java
Scheduler scheduler = ctx.scheduler();
TaskHandle handle = scheduler.submit(
    ctx.scope(),
    () -> ctx.logger().info("Hello from scheduler!"),
    TaskPriority.NORMAL
);
```

#### `submit(Runnable work) -> TaskHandle`

Shortcut for `scheduler().submit(scope(), work, TaskPriority.NORMAL)`.

```java
TaskHandle handle = ctx.submit(() -> {
    ctx.logger().info("Simple async task");
});
```

#### `submit(Runnable work, TaskPriority priority) -> TaskHandle`

Shortcut for `scheduler().submit(scope(), work, priority)`.

```java
TaskHandle handle = ctx.submit(() -> {
    // high-priority work here
}, TaskPriority.HIGH);
```

### Event Methods

#### `events() -> EventBus`

Returns a scope-bound `EventBus`. Listeners registered here are automatically unsubscribed when the mod stops.

```java
EventBus bus = ctx.events();

// Subscribe to a typed event
bus.addListener(String.class, event -> {
    ctx.logger().info("Received string event: " + event);
});

// Post an event
bus.post("Hello from MyMod!");

// Post asynchronously (dispatches on EventBus internal thread pool)
bus.postAsync("Async event!").thenRun(() -> {
    ctx.logger().info("Event dispatched");
});
```

### Capability Methods

#### `getCapability(Class<T> capabilityType) -> Optional<T>`

Returns an active capability of the requested type, or `Optional.empty()` if not granted. Capabilities follow a deny-by-default policy.

```java
Optional<MyService> service = ctx.getCapability(MyService.class);
service.ifPresent(s -> {
    ctx.logger().info("MyService is available!");
    s.doSomething();
});
```

#### `getAllCapabilities() -> Collection<CapabilityToken<?>>`

Returns all capability tokens assigned to this mod (both active and inactive).

```java
Collection<CapabilityToken<?>> caps = ctx.getAllCapabilities();
ctx.logger().info("This mod has " + caps.size() + " capability tokens.");
```

### Resource Methods

#### `resources() -> ResourceManager`

Returns a `ResourceManager` scoped to this mod `"s directory on disk. All filesystem access is validated through path traversal protection.

```java
ResourceManager res = ctx.resources();

try {
    byte[] data = res.readAllBytes("data/myconfig.dat");
    ctx.logger().info("Read " + data.length + " bytes.");
} catch (IOException e) {
    ctx.logger().severe("Failed to read resource", e);
}
```

### Environment Methods

#### `environment() -> RuntimeEnvironment`

Returns the `RuntimeEnvironment` for this mod. Walks up the scope tree to find the registered environment capability.

```java
RuntimeEnvironment env = ctx.environment();

if (env.isClient()) {
    ctx.logger().info("Running on Minecraft Client");
} else if (env.isServer()) {
    ctx.logger().info("Running on Minecraft Server");
}

// Possible values:
// RuntimeEnvironment.CLIENT
// RuntimeEnvironment.SERVER
// RuntimeEnvironment.DEDICATED_SERVER
```

### Logger Methods

#### `logger() -> ModLogger`

Returns a scope-bound logger. All output is prefixed with the mod name, scope ID, and timestamp. Logging to a stopped scope is silently dropped.

```java
ModLogger log = ctx.logger();

log.info("Informational message");
log.warning("Warning message");
log.severe("Error message");
log.severe("Error with exception", throwable);
log.fine("Debug message (visible if level <= FINE)");
log.finer("Detailed debug message");
log.finest("Trace-level debug message");

// Change minimum log level:
log.setLevel(java.util.logging.Level.FINE);
```

### Configuration Methods

#### `createConfig(String configId) -> Configuration`

Creates a new scoped configuration store. The configuration is automatically registered as a mod resource and cleaned up on mod stop.

```java
Configuration config = ctx.createConfig("main");
config.set("enabled", true);
config.set("maxRetries", 5);
config.set("serverHost", "localhost");
```

#### `getConfig(String configId) -> Optional<Configuration>`

Retrieves an existing configuration by ID.

```java
Optional<Configuration> existing = ctx.getConfig("main");
existing.ifPresent(cfg -> {
    boolean enabled = cfg.getBoolean("enabled", false);
    ctx.logger().info("enabled=" + enabled);
});
```

### Large Mod Methods

#### `isLargeMod() -> boolean`

Returns `true` if this mod declares child modules in its manifest.

```java
if (ctx.isLargeMod()) {
    ctx.logger().info("Large mod with modules: " + ctx.manifest().modules());
}
```

#### `modules() -> List<Scope>`

Returns the child module scopes created under this mod `"s scope. Each module gets its own child `Scope` for resource and lifecycle isolation.

```java
List<Scope> moduleScopes = ctx.modules();
for (Scope module : moduleScopes) {
    ctx.logger().info("Module scope: " + module.id());
    // Each module scope can have its own resources and capabilities
}
```

---

## Events

Mili `"s event system is a typed, scope-bound `EventBus`. Events are plain Java objects (records are recommended) dispatched to registered listeners.

### Posting Events

```java
// Define an event as a record
public record PlayerMessageEvent(String playerName, String message) {}

// Post synchronously (on the calling thread)
ctx.events().post(new PlayerMessageEvent("Steve", "Hello!"));

// Post asynchronously (on the EventBus dispatch thread pool)
ctx.events().postAsync(new PlayerMessageEvent("Steve", "Hello async!"));
```

### Subscribing to Events

```java
// Subscribe with lambda listener
ctx.events().addListener(PlayerMessageEvent.class, event -> {
    ctx.logger().info(event.playerName() + " says: " + event.message());
});

// Subscribe and keep a reference for later removal
EventListener<PlayerMessageEvent> listener = event -> {
    ctx.logger().info("Got message: " + event.message());
};
ctx.events().addListener(PlayerMessageEvent.class, listener);

// Remove when no longer needed
ctx.events().removeListener(PlayerMessageEvent.class, listener);
```

### Event Listener Semantics

- Events are dispatched to listeners in registration order.
- Listener exceptions are caught and must not break event dispatch. An exception in one listener will stop that listener but will not prevent other listeners from receiving the same event.
- Use `postAsync` for fire-and-forget scenarios where you do not need the result.
- If the EventBus is closed (e.g., during mod shutdown), subsequent calls to `post` are silently dropped and `postAsync` returns a completed future.

### Minecraft Events

Minecraft game events are defined as nested records inside `org.loader.runtime.minecraft.MinecraftEventBridge`. These bridge events flow through the runtime `EventBus` and can be subscribed to by mods.

#### Client Lifecycle Events

| Event Class | Description | Fields |
|-------------|-------------|--------|
| `ClientStartingEvent` | Fired before Minecraft instance is created | (none) |
| `ClientStartedEvent` | Fired after Minecraft client is fully ready | (none) |
| `ClientStoppingEvent` | Fired when the client begins shutdown | (none) |
| `ClientStoppedEvent` | Fired after the client has stopped | (none) |
| `ClientTickEvent` | Fired each game tick (~20 TPS) | `tick: long` |

#### Client World Events

| Event Class | Description | Fields |
|-------------|-------------|--------|
| `ClientWorldJoinEvent` | Fired when a world/level is loaded | (none) |
| `ClientWorldLeaveEvent` | Fired when a world/level is unloaded | (none) |

#### Client Player Events

| Event Class | Description | Fields |
|-------------|-------------|--------|
| `ClientPlayerJoinEvent` | Fired when player entity joins | (none) |
| `ClientPlayerLeaveEvent` | Fired when player entity leaves | (none) |

#### Client Screen Event

| Event Class | Description | Fields |
|-------------|-------------|--------|
| `ClientScreenOpenEvent` | Fired when a screen/overlay is opened | `screenClass: String` |

#### Server Events (for server-side mods)

| Event Class | Description | Fields |
|-------------|-------------|--------|
| `ServerStartingEvent` | Fired before server starts | (none) |
| `ServerStartedEvent` | Fired when server is ready | (none) |
| `ServerStoppingEvent` | Fired when server begins shutdown | (none) |
| `ServerStoppedEvent` | Fired after server has stopped | (none) |

### Subscribing to Minecraft Events

Since `MinecraftEvent` classes are internal bridge types, you can subscribe using `Class.forName` if they are not directly importable from the API:

```java
try {
    Class<?> clientStarted = Class.forName(
        "org.loader.runtime.minecraft.MinecraftEventBridge$ClientStartedEvent"
    );
    ctx.events().addListener(clientStarted, e -> {
        ctx.logger().info("*** Minecraft client is ready! ***");
        // initialization logic here
    });
} catch (ClassNotFoundException e) {
    ctx.logger().warning("ClientStartedEvent not available (standalone test)");
}
```

Or, with direct API access:

```java
import org.loader.runtime.minecraft.MinecraftEventBridge.ClientStartedEvent;
import org.loader.runtime.minecraft.MinecraftEventBridge.ClientTickEvent;

ctx.events().addListener(ClientStartedEvent.class, event -> {
    ctx.logger().info("Minecraft client ready.");
});

ctx.events().addListener(ClientTickEvent.class, event -> {
    if (event.tick() % 100 == 0) {
        ctx.logger().info("Tick #" + event.tick());
    }
});
```

```java
import org.loader.runtime.minecraft.MinecraftEventBridge.ClientWorldJoinEvent;
import org.loader.runtime.minecraft.MinecraftEventBridge.ClientWorldLeaveEvent;

ctx.events().addListener(ClientWorldJoinEvent.class, event -> {
    ctx.logger().info("World loaded -- set up world-specific state.");
});

ctx.events().addListener(ClientWorldLeaveEvent.class, event -> {
    ctx.logger().info("World unloaded -- clean up world-specific state.");
});
```

---

## Scheduler

The `Scheduler` is the central execution authority for the Mili Runtime. All mod async work must pass through it.

### Obtaining the Scheduler

```java
Scheduler scheduler = ctx.scheduler();
```

### Submitting One-Shot Tasks

```java
// Submit with default (NORMAL) priority
TaskHandle handle = scheduler.submit(ctx.scope(), () -> {
    ctx.logger().info("Task executed!");
});

// Submit with explicit priority
TaskHandle urgent = scheduler.submit(
    ctx.scope(),
    () -> ctx.logger().info("Urgent work"),
    TaskPriority.HIGH
);

// Shortcut via ModContext
TaskHandle quick = ctx.submit(() -> ctx.logger().info("Quick task"));
```

### Submitting Repeating Tasks

```java
// Repeating task: initial delay 0ms, period 1000ms, NORMAL priority
TaskHandle repeating = scheduler.submitRepeating(
    ctx.scope(),
    () -> ctx.logger().info("Heartbeat"),
    0L,    // delayMs
    1000L, // periodMs
    TaskPriority.NORMAL
);

// Cancel the repeating task
repeating.cancel();
```

### TaskHandle

`TaskHandle` provides monitoring and cancellation for submitted tasks.

```java
TaskHandle handle = ctx.submit(() -> { /* work */ });

// Cancel the task
boolean cancelled = handle.cancel(); // true if successful

// Wait for completion
try {
    handle.await();
} catch (CancellationException e) {
    ctx.logger().info("Task was cancelled before completion.");
}

// Check state
TaskState state = handle.state();
// Possible states: CREATED, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED

// Access the underlying future
CompletableFuture<Void> future = handle.future();
future.thenRun(() -> ctx.logger().info("Task completed."));
```

### TaskPriority

Tasks are executed in priority order (higher value = higher priority).

| Priority | Value | Use Case |
|----------|-------|----------|
| `LOW` | 0 | Background work, logging |
| `NORMAL` | 5 | Default for most tasks |
| `HIGH` | 10 | Urgent gameplay logic |
| `CRITICAL` | 20 | Shutdown, error handling |

### Task States

```
CREATED -> QUEUED -> RUNNING -> COMPLETED
                  \-> CANCELLED
                  \-> FAILED
```

### Scope-Bound Guarantees

- Tasks submitted to a stopped scope throw `IllegalStateException`.
- When a mod scope is closed, all associated tasks are cancelled.
- Thread pool threads are daemon threads (`runtime-scheduler-N`) and do not block JVM exit.

---

## Resources

Resources are managed objects that have a defined lifecycle tied to the owning scope. When the scope is closed, `close()` is called on every registered `Resource`.

### Implementing a Resource

```java
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;

public class MyResource implements Resource {
    private final String id;
    private final Scope owner;
    private volatile boolean closed = false;

    public MyResource(String id, Scope owner) {
        this.id = id;
        this.owner = owner;
    }

    @Override
    public String id() { return id; }

    @Override
    public Scope owner() { return owner; }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (closed) return; // Must be idempotent
        closed = true;
        // Release underlying handles, connections, files, etc.
    }
}
```

### Registering a Resource

```java
MyResource resource = new MyResource("my-mod:db", ctx.scope());
ctx.scope().registerResource(resource);
```

### Automatic Cleanup

When a mod scope transitions to `STOPPING`, the runtime iterates all registered resources and calls `close()` on each. If `close()` throws, the exception is caught and logged; cleanup continues.

### ResourceManager

`ResourceManager` provides filesystem access with path traversal protection. The base path is the mod `"s directory under `mods/`.

```java
ResourceManager res = ctx.resources();

// Read file as bytes
byte[] data = res.readAllBytes("assets/texture.png");

// Read file as String
String configText = res.readString("data/config.txt");

// Check existence
if (res.exists("data/save.dat")) {
    ctx.logger().info("Save file found.");
}

// Resolve a path (normalized)
java.nio.file.Path path = res.resolve("assets/icons/icon.png");

// Open stream for large files
try (InputStream is = res.openStream("data/large.bin")) {
    // Read incrementally
}
```

**Security:** If the resolved path escapes the base path, a `SecurityException` is thrown.

---

## Capabilities

A `CapabilityToken` represents a granted ability to interact with a service. Capabilities follow a **deny-by-default** model: if a capability is not explicitly granted, access is denied.

### Client Capabilities

Client capabilities are only available when the `RuntimeEnvironment` is `CLIENT`.

#### RenderService Capability

```java
import org.loader.runtime.client.ClientCapabilities;

Optional<ClientCapabilities.RenderService> opt = ctx.getCapability(ClientCapabilities.RenderService.class);
opt.ifPresent(cap -> {
    // Register a render callback
    cap.registerRenderCallback(() -> {
        // Called during each render frame
    });

    // Get current FPS
    int fps = cap.currentFps();
    ctx.logger().info("Current FPS: " + fps);
});
```

#### InputService Capability

```java
import org.loader.runtime.client.ClientCapabilities;

Optional<ClientCapabilities.InputService> opt = ctx.getCapability(ClientCapabilities.InputService.class);
opt.ifPresent(cap -> {
    // Register a key handler
    cap.registerKeyHandler("key.keyboard.r", () -> {
        ctx.logger().info("R key pressed!");
    });

    // Check if a key is currently pressed
    if (cap.isKeyPressed("key.keyboard.w")) {
        ctx.logger().info("W is being held.");
    }
});
```

### Granting Capabilities

Capabilities are granted by the loader during startup via `MinecraftBootstrap`. A typical mod cannot grant capabilities to itself through `ModContext` — it must be granted by the loader based on `capabilities` declared in `mod.json`.

For large mods with module scopes, you can grant capabilities directly:

```java
import org.loader.runtime.client.ClientCapabilities;
import org.loader.runtime.minecraft.RuntimeEnvironment;

CapabilityToken<ClientCapabilities.RenderService> token =
    ClientCapabilities.grantRenderCapability(ctx.scope(), RuntimeEnvironment.CLIENT);
```

Attempting to grant a client capability in a non-CLIENT environment throws `UnsupportedOperationException`.

### Revoking Capabilities

```java
scope.getCapability(MyService.class).ifPresent(token -> {
    token.revoke(); // Sets active = false
    // After revoke, isActive() returns false
});
```

### CapabilityToken Interface

```java
public interface CapabilityToken<T> {
    Class<T> capabilityType();   // The capability interface type
    Scope scope();               // The owning scope
    T get();                     // The capability implementation
    void revoke();               // Revokes this capability
    boolean isActive();          // True if active and scope is alive
}
```

---

## Lifecycle

Every mod follows a deterministic lifecycle with well-defined state transitions.

### State Machine

```
DISCOVERED -> RESOLVED -> LOADED -> INITIALIZED -> REGISTERED -> RUNNING -> STOPPING -> STOPPED
                                                          \-> FAILED
```

### State Reference

| State | Description | `allowsNewWork()` |
|-------|-------------|-------------------|
| `DISCOVERED` | JAR found by discovery | No |
| `RESOLVED` | `mod.json` parsed, metadata available | No |
| `LOADED` | ClassLoader created | No |
| `INITIALIZED` | `initialize()` invoked | Yes |
| `REGISTERED` | Mod registered with Runtime | Yes |
| `RUNNING` | Fully active, can submit work | Yes |
| `STOPPING` | Shutdown initiated, resources being closed | No |
| `STOPPED` | Cleanup complete | No |
| `FAILED` | Entrypoint threw or fatal error | No |

### Observing State Changes

```java
import org.loader.runtime.kernel.ScopeListener;
import org.loader.runtime.kernel.LifecycleState;

ctx.scope().addListener((scope, from, to) -> {
    ctx.logger().info("Scope " + scope.id() + ": " + from + " -> " + to);
    if (to == LifecycleState.STOPPING) {
        // Perform pre-shutdown logic
    }
});
```

### Lifecycle Utilities

```java
// Check current state
LifecycleState state = ctx.scope().state();

// Check if terminal (STOPPED or FAILED)
boolean done = state.isTerminal();

// Check if work can now be submitted
boolean canWork = state.allowsNewWork();

// Validate a transition (returns false if invalid)
boolean valid = LifecycleState.RUNNING.canTransitionTo(LifecycleState.STOPPING); // true
boolean invalid = LifecycleState.STOPPED.canTransitionTo(LifecycleState.RUNNING); // false
```

### Automatic Cleanup on Shutdown

When a mod stops, the runtime performs these steps in order:

1. Transition to `STOPPING`
2. Cancel all scheduler tasks for this scope
3. Unsubscribe all EventBus listeners
4. Revoke all capability tokens
5. Call `close()` on all registered `Resource` instances
6. Close the ClassLoader
7. Transition to `STOPPED`

---

## Environment

The `RuntimeEnvironment` enum identifies the execution context of the mod.

### Values

| Value | Description | `isClient()` | `isServer()` |
|-------|-------------|--------------|--------------|
| `CLIENT` | Integrated client (single-player or multiplayer) | true | false |
| `SERVER` | Server with integrated client | false | true |
| `DEDICATED_SERVER` | Headless server only | false | true |

### Usage

```java
RuntimeEnvironment env = ctx.environment();

if (env == RuntimeEnvironment.CLIENT) {
    // Safe to access rendering, input, sound capabilities
    ctx.logger().info("Client environment detected.");
}

if (env.isServer()) {
    // Server-side logic only
    ctx.logger().info("Server environment -- client capabilities unavailable.");
}
```

---

## Dependency System

Dependencies are declared in `mod.json` and resolved at load time. Mili uses topological sorting for initialization order.

### Declaring Dependencies

```json
{
  "id": "my-mod",
  "entrypoint": "com.example.MyMod",
  "dependencies": [
    {
      "modId": "library-mod",
      "versionRange": "1.0.0",
      "required": true
    },
    {
      "modId": "optional-mod",
      "versionRange": "2.0.0",
      "required": false
    }
  ]
}
```

### Dependency Entry Fields

| Field | Type | Description |
|-------|------|-------------|
| `modId` | String | ID of the required mod |
| `versionRange` | String | Minimum required version |
| `required` | boolean | `true` = mod fails to load if missing; `optional` if `false` |

### Resolution Behavior

The `DependencyResolver` computes:

- **Initialization order:** topological sort of the dependency graph (dependencies load before dependents).
- **Shutdown order:** reverse of initialization (dependents unload before dependencies).
- **Cycle detection:** circular dependencies throw `IllegalStateException`.
- **Missing required dependency:** the depending mod fails to load with an error.
- **Missing optional dependency:** the depending mod loads normally.

### Inter-Mod Communication

Mods communicate through the `EventBus` and `Registry`. Direct class access between mods is not allowed by default.

```java
// Mod A: register a service in registry
ctx.registry().register("com.example.MyService", new MyServiceImpl());

// Mod B: discover and use the service
Optional<Object> svc = otherRegistry.get("com.example.MyService");
```

---

## Registry

The `Registry<T>` is a named, scope-bound storage for typed objects.

### Obtaining the Registry

```java
Registry<Object> registry = ctx.registry();
```

### Using the Registry

```java
// Register a named object
registry.register("my-block", new BlockDefinition("minecraft:stone"));
registry.register("my-item", new ItemDefinition("minecraft:diamond"));

// Look up by name
Optional<Object> block = registry.get("my-block");
block.ifPresent(b -> ctx.logger().info("Found: " + b));

// Remove an entry
Optional<Object> removed = registry.remove("my-block");

// Enumerate
Set<String> keys = registry.keys();
Collection<Object> values = registry.values();
```

---

## Configuration

The `Configuration` class provides typed, scoped key-value storage with change notifications.

### Creating a Configuration

```java
Configuration config = ctx.createConfig("main");
```

### Basic Operations

```java
Configuration config = ctx.createConfig("main");

// Set values
config.set("enabled", true);
config.set("maxConnections", 10);
config.set("serverName", "My Server");

// Get with type inference (may return empty)
Optional<Boolean> enabled = config.get("enabled");
Optional<Integer> maxConn = config.get("maxConnections");
Optional<String> name = config.get("serverName");

// Get with default value
boolean isEnabled = config.getBoolean("enabled", false);
String server = config.getString("serverName");
int port = config.getInt("port", 25565);

// Get required (throws if not present)
boolean required = config.getRequired("enabled");
```

### Change Notifications

```java
config.addListener((key, oldVal, newVal) -> {
    ctx.logger().info(key + " changed: " + oldVal + " -> " + newVal);
});
```

### Retrieving Existing Configurations

```java
Optional<Configuration> existing = ctx.getConfig("main");
existing.ifPresent(cfg -> {
    boolean enabled = cfg.getBoolean("enabled", false);
    ctx.logger().info("Existing config, enabled=" + enabled);
});
```

---

## API Versioning

### Versioning Policy

- Mili Runtime follows semantic versioning when stable (`MAJOR.MINOR.PATCH`).
- Current development version: `0.1.0-SNAPSHOT`.
- Breaking changes may occur in `0.x` releases.
- `MAJOR` version bumps indicate breaking API changes.
- `MINOR` version bumps indicate backward-compatible additions.
- `PATCH` version bumps indicate bug fixes only.

### What is Stable

The following are considered part of the stable public API:

- `ModContext` (all public methods)
- `Scope` (lifecycle transitions, resource/capability registration)
- `EventBus` (listener registration, event posting)
- `Scheduler` (task submission, repeating tasks)
- `Resource` interface and `ResourceManager`
- `Configuration` (save, listen, retrieve)
- `CapabilityToken` interface
- `RuntimeEnvironment`, `LifecycleState`, `TaskPriority`, `TaskState` enums
- `ModManifest` record and `DependencyEntry` record

### What May Change Without Notice

- Internal loader implementation classes (`org.loader.loader.*`)
- Implementation details of `Scheduler` thread pools
- `MinecraftEventBridge` event ordering details
- `MinecraftRegistryBridge` entity/block access patterns

---

## Best Practices

### 1. Always Use the Scheduler

Never create unmanaged background threads. Use `ctx.scheduler()` for all async work.

```java
// WRONG: Unmanaged thread
new Thread(() -> { /* ... */ }).start();

// CORRECT: Scope-bound scheduler task
ctx.scheduler().submit(ctx.scope(), () -> { /* ... */ }, TaskPriority.NORMAL);
```

### 2. Implement Resources with Idempotent Close

```java
@Override
public void close() {
    if (closed) return; // Idempotent
    closed = true;
    // cleanup
}
```

### 3. Check Capability Availability Before Use

Never assume a capability is granted. Always use `Optional.ifPresent()`.

```java
// WRONG: Assumes capability exists
ctx.getCapability(MyService.class).get(); // NoSuchElementException if missing

// CORRECT: Check first
ctx.getCapability(MyService.class).ifPresent(svc -> svc.render());
```

### 4. Subscribe in Initialization, Let Runtime Cleanup

Register event listeners in `initialize(ModContext)`. The runtime auto-cleans subscriptions via scope shutdown. If using inner scopes, unsubscribe explicitly:

```java
EventListener<MyEvent> listener = e -> ctx.logger().info("Got event");
ctx.events().addListener(MyEvent.class, listener);
// Manual cleanup only when needed:
ctx.events().removeListener(MyEvent.class, listener);
```

### 5. Use `isActive()` Before Modifying State

```java
if (ctx.isActive()) {
    ctx.scheduler().submit(ctx.scope(), () -> updateState());
}
```

### 6. Guard Listeners Against Exceptions

```java
ctx.events().addListener(MyEvent.class, event -> {
    try {
        processEvent(event);
    } catch (Exception e) {
        ctx.logger().severe("Listener error", e);
    }
});
```

### 7. Prefer Records for Events

Events should be immutable data carriers. Records provide this for free:

```java
public record PlayerChatEvent(UUID senderId, String message, Instant timestamp) {}
```

### 8. Keep Logic Out of Constructors

Put all initialization in `initialize(ModContext)`, not in the constructor:

```java
// CORRECT:
public class MyMod {
    public void initialize(ModContext ctx) {
        // All setup here
    }
}
```

### 9. Respect Scope Isolation

Do not access other mods `" classes directly. Use events, registry, or capabilities for inter-mod communication.

```java
// WRONG: Directly accessing another mod" s class
// OtherModClass cls = (OtherModClass) otherModInstance;

// CORRECT: Use events for communication
ctx.events().post(new MyRequestEvent("data-request"));
```

### 10. Test with the Real Runtime

The ultimate test is running within the actual Mili Runtime, not just unit tests. Verify compilation against `mili-runtime-api.jar` and test loading in the real loader before publishing your mod.
