# Minecraft Work Classification

Per spec section 8. Categories derived from bytecode analysis of
`META-INF/versions/26.2/server-26.2.jar`.

---

## Category A — Main-thread-only (MUST run on Minecraft main thread)

These operations are NOT thread-safe. Direct Execution on any worker thread
causes data races, CME, or JVM crash.

Examples:
- World mutation (`ServerLevel.setBlock`, chunk mutation)
- Registry mutation (block/item registration / runtime registry changes)
- Entity mutation (direct position/AI modification outside entity tick list)
- Command dispatcher mutation
- Lifecycle operations (world load/unload, server start/stop)
- Chunk generation (partially — some parts parallel-safe, many not)
- Player inventory direct mutation from off-main

Owner: Main thread exclusively.

---

## Category B — Run-safe async (can run on Runtime Worker)

These operations do NOT touch mutable Minecraft state.

Examples:
- Pure computation (pathfinding pre-computation, math)
- Data transformation (serialization → deserialization)
- Configuration parsing / validation
- Network packet encoding (without sending)
- File IO (reading assets)
- Logging
- Hash computation

Owner: Runtime Scheduler workers.

---

## Category C — Parallel candidate (individual proved)

These operations MUST be individually profiled with call graph + bytecode.

NOT assumed parallel until proven.

Candidates (tentative, from real MC 26.2 bytecode):
- Weather tick (in per-level isolation)
- Block ticking (within a chunk)
- Block Entity ticking
- Fluid ticking (some)
- Scheduled tick execution (some)

Requires: thread-safety proof, no cross-chunk mutation.

---

## Category D — Ordered (must not be parallelized out of order)

Per spec:
```
A → B → C
```

Examples:
- Entity tick order (within world)
- Block tick order (within chunk)
- Redstone propagation
- Neighbor updates
- Network packet ordering per connection
- Chunk load order
- Plugin/mod event handler invocation order

---

## Category E — IO-bound (filesystem/network)

Examples:
- Chunk save/load to disk
- Player data serialization
- Network send/receive
- World generation disk reads
- Configuration file reads
- Asynchronous plugin message handling

---

## Migration status

| Work | Current target | Status |
|------|---------------|--------|
| Tick observation | TickBridge | DONE |
| Tick driving | TickBridge → Runtime Scheduler | IN PROGRESS |
| Mod task scheduling | ModContext.scheduler() | DONE |
| Chunk IO | (future IO scheduler) | NOT STARTED |
| Network packets | (future network runtime) | NOT STARTED |
| World mutation | Main Thread Bridge | DONE (MinecraftMainExecutor) |

