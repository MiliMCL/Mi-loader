# Thread Model

Per spec section 12. Every thread has: owner, allowed operations, forbidden operations, lifecycle, shutdown behavior.

---

## Thread Inventory

### Minecraft Main Thread
- **Owner:** Minecraft server (single instance)
- **Allowed:** World mutation, Registry mutation, Entity tick, Command dispatch, Block tick, Block Entity tick, Fluid tick, Scheduled ticks, Chunk load, Player connection handling
- **Forbidden:** Blocking wait on Runtime worker, holding locks while waiting, sleeping > 1ms, creating threads
- **Lifecycle:** Starts at `DedicatedServer.runServer()`, exits at `onServerExit()`

### Runtime Scheduler Workers
- **Owner:** Runtime Scheduler (`runtime-scheduler`)
- **Allowed:** Pure computation, scheduling, async work, logging, metrics
- **Forbidden:** Direct Minecraft world/state mutation, blocking main thread
- **Lifecycle:** Started at `Runtime.start()` via `ThreadPoolExecutor.prestartAllCoreThreads`, terminated at `Runtime.close()` via `shutdown()`
- **Shutdown:** `shutdown()` + `awaitTermination`, then `shutdownNow()` if needed

### IO Workers (future)
- **Owner:** Runtime IO scheduler
- **Allowed:** File read/write, network send, serialization
- **Forbidden:** Minecraft direct state access, long-running computation
- **Lifecycle:** Created on demand, scoped to owning Scope

### Render Thread (CLIENT)
- **Owner:** Minecraft Render Scope
- **Allowed:** GPU operations, render callbacks, sound playback
- **Forbidden:** Any non-render-thread GPU calls, blocking, long computation
- **Lifecycle:** Started when Render Scope enters RUNNING, stopped at shutdown

### Network Workers (future)
- **Owner:** Runtime Network Resource
- **Allowed:** Packet send/receive, serialization
- **Forbidden:** Direct Minecraft state mutation
- **Lifecycle:** Per-connection

### Mod-owned managed tasks
- **Owner:** Individual Mod Scope
- **Allowed:** Whatever capability grants allow
- **Forbidden:** Depends on capability set (default: no thread creation)
- **Lifecycle:** Scope-bound — cancelled when Mod Scope stops

---

## Cross-thread bridging

### Runtime Worker → Main Thread
Use `MinecraftMainExecutor.submitToMainAsync()`. Blocks main execution queue only momentarily.

### Main Thread → Runtime Scheduler
Use `MinecraftMainExecutor.submitToScheduler(...)`. Non-blocking enqueue.

---

## Deadlock Protection (spec section 14)

Forbidden:
```
Minecraft Main Thread
    ↓ wait
Runtime Worker
    ↓ wait
Minecraft Main Thread
```

Rule: Main-thread → worker handoff is ALWAYS fire-and-forget (CompletableFuture).
Worker → main handoff is queued, never blocking-wait from main thread context.

