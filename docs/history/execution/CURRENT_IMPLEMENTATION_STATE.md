> # ⚠️ 历史文档 —— 不代表当前实现状态
>
> 本文件记录的是**当时**的分析，其中多处论断已被后续实现推翻。
> 判断项目现状请读 [`../../architecture/CURRENT.md`](../../architecture/CURRENT.md)。
> 详见 [`README.md`](../README.md)。

---

# Current Implementation State

**Date:** 2026-10-03
**Version:** 0.1.0-SNAPSHOT
**ABI:** 1.0
**Minecraft Target:** 26.2
**Build:** PASS | **Tests:** 126+ PASS across 18 classes

---

## Per-Package Status

### kernel (12 files)
| Component | Status | Notes |
|-----------|--------|-------|
| Runtime | Complete | RootScope + LifecycleManager + 7 managers. `start()` spins scheduler. |
| Scope | Complete | Lifecycle state machine, children, capabilities, resources, listeners |
| LifecycleState | Complete | Enum + validation |
| CapabilityToken | Complete | Sealed interface + grants + revoke |
| CapabilityManager | Complete | register/canGrant/grant with state machine |
| PermissionManager | Complete | policy-based, hasPermission/checkPermission |
| DependencyResolver | Complete | Topological sort + cycle detection |
| ResourceRegistry | Complete | register/unregister/getByOwner/detectOrphans |
| Resource | Complete | Base interface (AutoCloseable + id + owner + isClosed) |
| ScopeListener | Complete | State change callback |
| ScopeShutdownException | Complete | Runtime exception |

### scheduler (4 files)
| Component | Status | Notes |
|-----------|--------|-------|
| Scheduler | Complete | ThreadPoolExecutor + PriorityBlockingQueue. submit/submitRepeating/metrics. p50/p95/p99 percentile. |
| TaskHandle | Complete | Sealed: cancel/await/state |
| TaskState | Complete | CREATED, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED |
| TaskPriority | Complete | LOW, NORMAL, HIGH, CRITICAL |

### mod (8 files)
| Component | Status | Notes |
|-----------|--------|-------|
| Mod | Complete | Resource with manifest + scope + classloader |
| ModManifest | Complete | id/name/version/dependencies/capabilities/mainClass/modules |
| ModLoader | Complete | load/unload/find |
| ModContext | Complete | SDK: modId/manifest/scope/logger/classLoader, scheduler/events/registry/resources/environment/createConfig/getConfig |
| ModDiscoverer | Complete | ModSource sources list + discover |
| ModCommunication | Complete | channel-based publish/subscribe |
| ServiceRegistry | Complete | typed publish/discover/unpublish |
| ModLoadException | Deprecated | Now extends typed ModLoadError |

### service (7 files)
| Component | Status | Notes |
|-----------|--------|-------|
| EventBus | Complete | addListener/removeListener/post/postAsync. Typed dispatch. |
| Registry | Complete | register/get/keys/remove |
| Configuration | Complete | Typed get/set + change listeners |
| ResourceManager | Complete | Path-based with security |
| NetworkResource | Complete | Abstract base |
| NetworkServer | Complete | address-based |
| NetworkClient | Complete | client connection |

### minecraft (8 files)
| Component | Status | Notes |
|-----------|--------|-------|
| MinecraftBootstrap | Complete | Creates minecraft scope (CLIENT → "minecraft-client" + render-scope; SERVER → "minecraft"). Owns scheduler/tick/event/registry/entity/world bridges. |
| MinecraftLifecycle | Complete | Phase enum + listeners |
| TickBridge | Complete | tick counting, addListener/runOnNextTick/runAfterTicks/pause/resume |
| MinecraftEventBridge | Complete | Typed event dispatch (ServerStartingEvent, ServerStartedEvent, ServerStoppingEvent, ServerStoppedEvent, TickEvent) |
| MinecraftRegistryBridge | Complete | getRegistry/register/get/keys |
| EntityBridge | Complete | spawn/remove/count |
| WorldBridge | Complete | create/get/unload/loadedWorlds |
| RuntimeEnvironment | Complete | SERVER, CLIENT, DEDICATED_SERVER with isClient/isServer |

### client (1 file)
| Component | Status | Notes |
|-----------|--------|-------|
| ClientCapabilities | Complete | Render/Input/Sound. Denied on server (throws UnsupportedOperationException). Per-environment grant methods. |

### instance (7 files)
| Component | Status | Notes |
|-----------|--------|-------|
| Instance | Complete | Builder pattern: instanceId/minecraftVersion/runtimeVersion/abiVersion/modIds/configuration/displayName |
| InstanceManager | Complete | Multi-instance lifecycle, ReentrantReadWriteLock. create/start/stop/remove. |
| ModSet | Complete | Immutable resolved mod collection |
| ModManager | Complete | Discovery + dependency resolution + load into Runtime |
| ModManagerException | Complete | Typed exception |
| ModResolutionResult | Complete | success/failed + missing deps + conflicts |
| ModSources | Complete | directory/listOf/composite builtin sources |

### observability (2 files)
| Component | Status | Notes |
|-----------|--------|-------|
| RuntimeDiagnostics | Complete | DiagnosticSnapshot: metadata, uptime, scopes, threads, errors. detectLeaks. |
| ProfilerHooks | Complete | Timing + counters + frame logging |

### security (1 file)
| Component | Status | Notes |
|-----------|--------|-------|
| AuditLog | Complete | AuditEntry (timestamp/thread/event/target/detail/outcome/success). capabilityGranted, capabilityRevoked, permissionDecision, escalation, privilegedUse. entries/entriesFor/denied/clear. |

### minecraft26 (4 files)
| Component | Status | Notes |
|-----------|--------|-------|
| Minecraft26Server | Complete | Bridge: onInitServer, onLoadComplete, onServerTick, onServerHalt, onServerExit. Snapshot diagnostics. |
| MinecraftRuntimePlugin | Complete | Bukkit-style lifecycle: onLoad, onEnable, onDisable. loadMod, submit via repeating task. |
| FabricServerInitializer | Complete | Fabric entry point (reflection-friendly stub). |
| BukkitStub | Complete | Minimal compile-time stub for Bukkit/Plugin/Scheduler interfaces. |

---

## What is NOT implemented (Risks / Work Remaining)

1. **Real Minecraft JAR execution**: All MC code is stub/named after real classes (net.minecraft.server.Main etc.) but NOT linked to actual Mojang bytecode. The real integration requires either:
   - A Fabric/Forge mod that calls our adapter
   - A Paper plugin entry point compiled with Paper API on classpath
   - Or byte-code instrumentation via Java agent

2. **Main Thread Bridge**: No mechanism to safely hand off Runtime Worker → Minecraft Main Thread and back. Critical for thread-safety.

3. **Resource Leak Detector**: `/tmp/open files / threads / tasks` — no detector that actively polls after shutdown.

4. **Tick Work Classification**: Currently all tick work is homogeneous. Need Category A/B/C/D/E classification.

5. **Parallel tick**: Not implemented. Research phase only (must first do profiling + region/entity ownership).

6. **World/Region/Entity Scope hierarchy**: Only `Server Scope → World Scope` stub. Region and Entity scopes not built.

7. **Deadlock Protection**: None.

8. **Resource Leak Detection as daemon**: Not active.

9. **Real-world benchmarks**: No instrumentation against actual Minecraft server.

---

