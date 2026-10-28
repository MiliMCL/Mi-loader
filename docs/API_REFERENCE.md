# API 参考

本文列出 `mili-abi` 的全部公开类型与方法。所有签名与代码一致；`org.loader.api` 之外的包（`org.loader.runtime.*`、`org.loader.loader.*`、`org.loader.runtime.minecraft.*`）**不属于契约**，Mod 不应引用。

约定：
- 「不可为 null」的方法在收到 null 时抛 `NullPointerException` 或 `IllegalArgumentException`（具体见方法说明）。
- 「返回空」指返回 `Optional.empty()` / 空集合，不是返回 `null`。
- 标注「客户端」的方法在服务端环境抛对应 `*UnavailableException`。

---

## 1. 入口

### `org.loader.api.Mod`

```java
public interface Mod {
    void initialize(ModContext context);
}
```

唯一入口点。Loader 要求：
- 入口类是 `public`、非抽象、有 public 无参构造器；
- 实现 `Mod` 接口；
- 声明在 `mod.json` 的 `entrypoint` 字段。

不满足任一条即 `MOD_ENTRYPOINT_INVALID`。不接受 `onInitialize` / `main` / `init` 等 fallback。

### `org.loader.api.Mili`

```java
public final class Mili {
    public static int apiVersion();                       // VersionInfo.ABI_VERSION
    public static String platformVersion();               // VersionInfo.CURRENT_VERSION
    @Deprecated public static String runtimeVersion();    // 同 platformVersion
    public static Optional<ModContext> mod(Object modInstance);
    public static Optional<ModContext> mod(String modId);
    public static EventBus events();                      // 平台总线（无归属视图）
    public static List<String> modIds();                  // 已加载 Mod（按加载顺序）
    public static boolean isLoaded(String modId);
    public static void registerProvider(MiliProvider provider);  // 平台内部调用

    public interface MiliProvider {
        Optional<ModContext> findMod(Object modInstance);
        Optional<ModContext> findMod(String modId);
        default EventBus events() { return null; }
        default List<String> modIds() { return List.of(); }
    }
}
```

`mod(Object)` 按「类名 + 加载器」匹配，不是按对象身份——同一个 Mod 可能被多次实例化。

`events()` 在平台未启动时返回一个占位总线：发布无效果，订阅抛 `IllegalStateException`（而不是返回 null 让调用方在几百行之后 NPE）。通过它注册的监听器**不随 Mod 自动注销**。

### `org.loader.api.VersionInfo`

```java
public final class VersionInfo {
    public static final String CURRENT_VERSION  = "0.1.0";
    public static final int    ABI_VERSION      = 2;
    public static final int    TARGET_JAVA      = 25;
    public static final String TARGET_MINECRAFT = "26.2";
    public static final String PLATFORM_ID      = "mili-0.1.0-mc26.2";
}
```

---

## 2. 上下文

### `org.loader.api.ModContext`

```java
public interface ModContext {
    String modId();
    ModMetadata metadata();
    Lifecycle lifecycle();
    Environment environment();

    Scheduler scheduler();
    EventBus events();
    TickService tick();
    ServiceBroker services();
    PermissionManager permissions();

    MinecraftRegistry registry();   // 可能为 null
    WorldView world();              // 可能为 null
    ResourceManager resources();
    Logger logger();

    <T> Optional<T> getCapability(Class<T> capabilityType);

    // default 方法
    default ModConfig config()      { return ConfigService.load(modId()); }
    default ModStorage storage()    { return StorageService.forMod(modId()); }
    default PlatformMetrics metrics() { return MetricsService.current(); }
    default PerformanceSnapshot performance() { return PerformanceService.current(); }
    default boolean isActive()      { return lifecycle().state().allowsNewWork(); }
    default void submit(Runnable work) { scheduler().submit(work); }
    default void submit(Runnable work, TaskPriority p) { scheduler().submit(work, p); }
}
```

**`registry()` / `world()` 返回 null 的情形**：注册表未装配（`RegistryBinder.bind` 未执行）、世界未加载、平台已关闭。契约要求 Mod 判空——平台不会返回一个「能调但不写进游戏」的空实现。

**`isActive()`**：平台实现返回 `!scope().isStopped()`，与 `lifecycle().state().allowsNewWork()` 略有差异（`DISCOVERED` 状态下前者为 true、后者为 false）。需要严格语义时用 `lifecycle().state()`。

### `org.loader.api.ModMetadata`

```java
public interface ModMetadata {
    String id();
    String name();
    String version();
    String author();       // 未声明时为空串
    String description();  // 未声明时为空串
    List<? extends ModDependency> dependencies();
    String entrypoint();   // 未声明时为空串
}
```

### `org.loader.api.ModDependency`

```java
public interface ModDependency {
    String modId();
    String versionRange();  // 未声明时为空串
    boolean required();
}
```

### `org.loader.api.Environment`

```java
public enum Environment {
    SERVER, CLIENT, DEDICATED_SERVER;
    public boolean isClient();   // == CLIENT
    public boolean isServer();   // == SERVER || DEDICATED_SERVER
}
```

### `org.loader.api.Logger`

```java
public interface Logger {
    void info(String message);
    void warning(String message);
    void severe(String message);
    void severe(String message, Throwable throwable);
    void fine(String message);
    void finer(String message);
    void finest(String message);
}
```

输出带 Mod ID 前缀，同时写控制台与 `logs/mili-platform.log`。

---

## 3. 生命周期

### `org.loader.api.lifecycle.Lifecycle`

```java
public interface Lifecycle {
    LifecycleState state();
    default boolean isTerminal()     { return state().isTerminal(); }
    default boolean allowsNewWork()  { return state().allowsNewWork(); }
    void addListener(LifecycleListener listener);
    void removeListener(LifecycleListener listener);   // 按身份移除

    @FunctionalInterface
    interface LifecycleListener {
        void onStateChange(LifecycleState from, LifecycleState to);
    }
}
```

同一监听器注册两次只挂一个包装器；`removeListener` 传当初注册的同一个实例即可精确移除。

### `org.loader.api.lifecycle.LifecycleState`

```java
public enum LifecycleState {
    DISCOVERED, RESOLVED, LOADED, INITIALIZED, REGISTERED,
    RUNNING, STOPPING, STOPPED, FAILED;

    public boolean isTerminal();      // STOPPED || FAILED
    public boolean allowsNewWork();   // RUNNING || REGISTERED || INITIALIZED || LOADED
}
```

Mod 的 Scope 在 `initialize()` 返回后由 Loader 推进到 `RUNNING`。

---

## 4. 事件

### `org.loader.api.event.EventBus`

```java
public interface EventBus {
    <T> void addListener(Class<T> eventType, EventListener<T> listener);
    <T> void removeListener(Class<T> eventType, EventListener<T> listener);
    <T> Subscription subscribe(Class<T> eventType, EventListener<T> listener);
    <T> void post(T event);
    <T> CompletableFuture<Void> postAsync(T event);
    default boolean hasListeners(Class<?> eventType) { return true; }
    default int listenerCount() { return -1; }
    void close();
}
```

派发顺序：精确类型 → 父类 → 接口 → `Object`。监听器异常不中断分发，异常写日志。

`removeListener` 对从未通过本总线注册的监听器抛 `IllegalArgumentException`（而不是静默无效）。

### `org.loader.api.event.EventListener`

```java
@FunctionalInterface
public interface EventListener<T> {
    void onEvent(T event);
}
```

### `org.loader.api.event.Subscription`

```java
public interface Subscription {
    void unsubscribe();   // 幂等
    boolean isActive();
}
```

### `org.loader.api.event.TickEvent`

```java
public record TickEvent(long tick, TickPhase phase, long durationNanos) {
    public double durationMs();   // START 时恒为 0
    public boolean isEnd();
}
```

每个 tick 发布两次（`START` 与 `END`）。只在 `isEnd()` 时计数才是真实的 TPS。

### `org.loader.api.event.TickPhase`

```java
public enum TickPhase { START, END }
```

### `org.loader.api.event.ModLifecycleEvent`

```java
public record ModLifecycleEvent(String modId, LifecycleState from, LifecycleState to) {
    public boolean isStarted();   // to == RUNNING
    public boolean isStopped();   // to.isTerminal()
}
```

发布在平台总线上，任何 Mod 都能收到**任意** Mod 的迁移。

### `org.loader.api.event.PlatformEvent` / `PlatformStage`

```java
public record PlatformEvent(PlatformStage stage, String platformVersion, int modCount) {}

public enum PlatformStage { BOOTSTRAPPING, READY, STOPPING, STOPPED }
```

`READY` 表示全部 Mod 的 `initialize()` 已返回、平台即将把控制权交给 Minecraft。

---

## 5. 调度

### `org.loader.api.scheduler.Scheduler`

```java
public interface Scheduler {
    TaskHandle submit(Runnable work);
    TaskHandle submit(Runnable work, TaskPriority priority);
    default TaskHandle submitRepeating(Runnable work, long delayMs, long periodMs);
    TaskHandle submitRepeating(Runnable work, long delayMs, long periodMs, TaskPriority priority);
    boolean cancel(TaskHandle handle);
    void close();   // 取消本 Mod 的全部任务，不关闭调度器
}
```

向已停止的 Scope 提交会抛 `IllegalStateException`。

### `org.loader.api.scheduler.TaskHandle`

```java
public interface TaskHandle {
    String taskId();
    TaskState state();               // 实时状态
    TaskPriority priority();
    boolean cancel();
    CompletableFuture<Void> future();
    void await() throws CancellationException, InterruptedException;
    default boolean isActive();      // state().isActive()
    default boolean isFinished();    // state().isFinished()
}
```

`await()` 在任务失败时抛运行时异常（含 `taskId`），在取消时抛 `CancellationException`。

### `org.loader.api.scheduler.TaskState`

```java
public enum TaskState {
    CREATED, QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED;
    public boolean isFinished();
    public boolean isActive();
}
```

### `org.loader.api.scheduler.TaskPriority`

```java
public enum TaskPriority {
    LOW(0), NORMAL(5), HIGH(10), CRITICAL(20);
    public int value();
}
```

值越小越优先。同优先级按提交顺序执行。

---

## 6. Tick

### `org.loader.api.tick.TickService`

```java
public interface TickService {
    long currentTick();                            // 未进入 tick 循环时为 0
    TickTask runNextTick(Runnable work);
    TickTask runAfterTicks(int ticks, Runnable work);
    TickTask runEveryTick(Runnable work);
    boolean isAvailable();

    interface TickTask {
        String taskId();
        boolean isActive();
        boolean cancel();
    }
}
```

执行时机：主线程、该 tick 的 `START` 之后、世界 tick 之前（`runEveryTick`），或下一个 tick 的同一位置（`runNextTick` / `runAfterTicks`）。

没有 tick 源时 `isAvailable()` 为 false，提交抛 `IllegalStateException` 并说明具体原因（客户端未进世界 / 服务端未启动 / 纯 JVM 测试）。

---

## 7. 跨 Mod 服务

### `org.loader.api.service.ServiceBroker`

```java
public interface ServiceBroker {
    <T> void provide(Class<T> serviceType, T implementation);
    boolean withdraw(Class<?> serviceType);      // 只能撤销自己发布的
    <T> Optional<T> discover(Class<T> serviceType);
    default <T> T require(Class<T> serviceType); // 缺失时抛 ServiceUnavailableException
    Set<Class<?>> publishedTypes();
    Set<Class<?>> providedByMe();
}
```

### `org.loader.api.service.ServiceUnavailableException`

```java
public class ServiceUnavailableException extends IllegalStateException {
    public String serviceType();
}
```

---

## 8. 权限

### `org.loader.api.permission.Permission`

```java
public enum Permission {
    RESOURCE_READ("resource.read"),
    RESOURCE_WRITE("resource.write"),
    NETWORK_ACCESS("network.access"),
    NATIVE_ACCESS("native.access"),
    CAPABILITY_REGISTER("capability.register"),
    INTERMOD_COMMUNICATION("intermod.communication"),
    TRANSFORM_CLASS("transform.class"),
    TRANSFORM_OTHER_MODS("transform.other-mods"),
    TRANSFORM_MINECRAFT("transform.minecraft"),
    TRANSFORM_CORE("transform.core"),
    OVERWRITE_METHOD("transform.overwrite");

    public String id();
}
```

### `org.loader.api.permission.PermissionManager`

```java
public interface PermissionManager {
    void registerPolicy(String permission, PermissionPolicy policy);  // Mod 调用抛 UnsupportedOperationException
    boolean hasPermission(String permission);
    default boolean hasPermission(Permission permission);
    default void checkPermission(String permission);                  // 拒绝时抛 PermissionDeniedException
    default void checkPermission(Permission permission);
    boolean isClosed();

    @FunctionalInterface
    interface PermissionPolicy {
        boolean evaluate(String permission);
    }
}
```

---

## 9. 配置与存储

### `org.loader.api.config.ConfigService`

```java
public final class ConfigService {
    public interface Provider { ModConfig load(String modId); }
    public static void registerProvider(Provider p);
    public static ModConfig load(String modId);   // 同 modId 同实例
}
```

`modId` 必须匹配 `[a-z0-9_.-]+`，否则抛 `IllegalArgumentException`。平台未装配时抛 `ConfigUnavailableException`。

### `org.loader.api.config.ModConfig`

```java
public interface ModConfig {
    String modId();
    String getString(String key, String defaultValue);
    int getInt(String key, int defaultValue);
    boolean getBoolean(String key, boolean defaultValue);
    double getDouble(String key, double defaultValue);
    void set(String key, String value);
    void setInt(String key, int value);
    void setBoolean(String key, boolean value);
    void setDouble(String key, double value);
    boolean has(String key);
    Iterable<String> keys();      // 排序后快照
    String save();                // 返回写入路径
}
```

存储形态：`config/mili/<modId>.properties`（UTF-8）。解析失败（如把 `"abc"` 读成 int）返回默认值——手改配置的玩家不该得到崩溃。

### `org.loader.api.storage.ModStorage`

```java
public interface ModStorage {
    Optional<String> get(String key);
    default String getOrDefault(String key, String defaultValue);
    default boolean getBoolean(String key, boolean defaultValue);
    default int getInt(String key, int defaultValue);
    default long getLong(String key, long defaultValue);
    void put(String key, String value);
    boolean remove(String key);
    Set<String> keys();
    boolean flush();
    long lastFlushMillis();
    boolean persistent();   // 目录不可写时为 false（仅内存）
}
```

存储形态：`config/mili/storage/<modId>.properties`。平台在 Mod 的 Scope 关闭时自动 flush。

### `org.loader.api.storage.StorageService`

```java
public final class StorageService {
    public static ModStorage forMod(String modId);
}
```

平台未启动到运行期时抛 `StorageUnavailableException`。

---

## 10. 资源

### `org.loader.api.resource.ResourceManager`

```java
public interface ResourceManager extends Resource {
    Path basePath();
    Path resolve(String resourcePath);      // 路径逃逸抛 SecurityException
    byte[] readAllBytes(String resourcePath) throws IOException;
    String readString(String resourcePath) throws IOException;   // UTF-8
    boolean exists(String resourcePath);
}
```

读取顺序：**Mod JAR 优先，可写数据目录兜底**。两者都没有时 `readAllBytes` 抛 `NoSuchFileException`（不是返回 null）。

### `org.loader.api.resource.Resource`

```java
public interface Resource extends AutoCloseable {
    String id();
    boolean isClosed();
    void close();   // 幂等
}
```

---

## 11. 内容注册

### `org.loader.api.registry.MinecraftRegistry`

```java
public interface MinecraftRegistry {
    BlockHandle block(BlockSpec spec);
    ItemHandle item(ItemSpec spec);
    Optional<BlockHandle> findBlock(String path);
    Optional<ItemHandle> findItem(String path);
    Collection<BlockHandle> blocks();
    Collection<ItemHandle> items();
    boolean isOpen();
}
```

注册窗口只在 Mod 初始化期开放。窗口关闭后调用 `block` / `item` 抛 `IllegalStateException`。

### `org.loader.api.registry.BlockSpec`

```java
public final class BlockSpec {
    public static Builder builder(String path);

    public String path();
    public Optional<MiliBlock> behavior();
    public Material material();
    public Map<String, String> textures();
    public boolean hasCollision();
    public boolean isReplaceable();
    public boolean hasNoCollision();
    public float hardness();
    public boolean requiresTool();
    public boolean hasBlockEntity();
    public boolean isAir();
    public int lightLevel();
    public boolean conductsRedstone();
    public boolean isSolid();
    public List<BlockPropertySpec> properties();

    public enum Material { /* SOLID, ... */ }

    public static final class Builder {
        public Builder behavior(MiliBlock behavior);
        public Builder material(Material material);
        public Builder texture(String key, String spec);
        public Builder collision(boolean collision);
        public Builder noCollision();
        public Builder hardness(float hardness);
        public Builder requiresTool(boolean requiresTool);
        public Builder replaceable();
        public Builder blockEntity();
        public Builder air();
        public Builder lightLevel(int lightLevel);
        public Builder conductsRedstone();
        public Builder solid(boolean solid);
        public Builder property(BlockPropertySpec property);
        public BlockSpec build();
    }
}
```

### `org.loader.api.registry.ItemSpec`

```java
public final class ItemSpec {
    public static Builder builder(String path);

    public String path();
    public int maxStackSize();
    public int maxDamage();
    public boolean hasSubtypes();
    public Optional<BlockHandle> blockItem();
    public String modelTemplate();
    public Map<String, String> textures();
    public String group();
    public int rarity();
    public boolean isFireResistant();
    public boolean needsGlint();

    public static final class Builder {
        public Builder maxStackSize(int maxStackSize);
        public Builder maxDamage(int maxDamage);
        public Builder hasSubtypes();
        public Builder blockItem(BlockHandle block);
        public Builder modelTemplate(String template);
        public Builder texture(String key, String spec);
        public Builder texture(String spec);
        public Builder group(String group);
        public Builder rarity(int rarity);
        public Builder fireResistant();
        public Builder glint();
        public ItemSpec build();
    }
}
```

### `org.loader.api.registry.BlockPropertySpec`

```java
public final class BlockPropertySpec {
    public enum Kind { INTEGER, BOOLEAN }

    public static BlockPropertySpec integer(String name, int min, int max);
    public static BlockPropertySpec booleanProperty(String name);

    public String name();
    public Kind kind();
    public int min();
    public int max();
}
```

### `org.loader.api.world.BlockHandle` / `ItemHandle`

```java
public final class BlockHandle {
    public static BlockHandle create(String modId, String path, int numericId);
    public String modId();
    public String path();
    public String id();          // "modId:path"
    public int numericId();
}
```

### `org.loader.api.world.WorldView`

```java
public interface WorldView {
    BlockHandle getBlock(BlockPos pos);
    boolean setBlock(BlockPos pos, BlockHandle block);
    default boolean removeBlock(BlockPos pos);
    Optional<BlockEntityView> getBlockEntity(BlockPos pos);
    boolean isLoaded(BlockPos pos);
    int minY();
    int maxY();
    void requestSave();
    default long gameTime();
    default long dayCount();
    default long dayTime();
}
```

世界视图是只读快照语义：`getBlock` 返回的是注册表句柄，不是 Minecraft 的 `BlockState` 对象。

---

## 12. 客户端服务

### `org.loader.api.gui.ScreenService`

```java
public final class ScreenService {
    public interface Provider {
        void open(ScreenSpec spec, ScreenListener listener);
        void closeCurrent();
        boolean isShowing();
    }
    public static void registerProvider(Provider p);
    public static void open(ScreenSpec spec, ScreenListener listener);  // 客户端
    public static void closeCurrent();
    public static boolean isShowing();
}
```

### `org.loader.api.gui.ScreenSpec`

```java
public final class ScreenSpec {
    public static Builder builder(String id, String title);

    public String id();
    public String title();
    public int width();
    public int height();
    public List<Widget> widgets();
    public boolean pauseGame();
    public boolean hudMode();

    public sealed interface Widget permits Button, Label, TextField, Checkbox {}
    public record Button(String id, String label, int x, int y, int width, int height) implements Widget {}
    public record Label(String id, String text, int x, int y) implements Widget {}
    public record TextField(String id, String initial, int x, int y, int width, int height) implements Widget {}
    public record Checkbox(String id, String label, int x, int y, boolean initial) implements Widget {}

    public static final class Builder {
        public Builder size(int width, int height);
        public Builder button(String id, String label, int x, int y, int width, int height);
        public Builder label(String id, String text, int x, int y);
        public Builder textField(String id, String initial, int x, int y, int width);
        public Builder textField(String id, String initial, int x, int y, int width, int height);
        public Builder checkbox(String id, String label, int x, int y, boolean initial);
        public Builder pauseGame(boolean pause);
        public Builder hudMode(boolean hud);
        public ScreenSpec build();
    }
}
```

### `org.loader.api.gui.ScreenListener`

```java
public interface ScreenListener {
    default void onOpen() {}
    default void onClick(String widgetId) {}
    default void onTextChange(String widgetId, String value) {}
    default void onToggle(String widgetId, boolean checked) {}
    default void onClose(boolean cancelled) {}
}
```

### `org.loader.api.input.KeyBindingService`

```java
public final class KeyBindingService {
    public interface Provider {
        KeyBinding register(KeyBindingSpec spec, Consumer<KeyEventType> handler);
    }
    public static void registerProvider(Provider p);
    public static KeyBinding register(KeyBindingSpec spec, Consumer<KeyEventType> handler);
}
```

### `org.loader.api.input.KeyBindingSpec` / `KeyBinding` / `KeyEventType`

```java
public record KeyBindingSpec(String id, String category, String defaultKey) {
    public int glfwKeyCode();
}

public interface KeyBinding {
    String id();
    void unregister();
}

public enum KeyEventType { PRESS, RELEASE }
```

---

## 13. 诊断

### `org.loader.api.diagnostics.PlatformMetrics`

```java
public record PlatformMetrics(...) {
    public static PlatformMetrics empty();
    public int heapPercent();
    public String heapText();
    public String gcText();
    public String threadText();
    public String uptimeText();
    public int pressureLevel();   // 0..3
    public record ModMemoryEstimate(String modId, long estimatedBytes, int loadedClasses) {}
}
```

### `org.loader.api.diagnostics.PerformanceSnapshot`

```java
public final class PerformanceSnapshot {
    public static PerformanceSnapshot empty();
    public enum Health { UNKNOWN, HEALTHY, DEGRADED, CRITICAL }
    public boolean hasTickData();
    public boolean hasFrameData();
    public Health health();
    public String tpsText();
    public String tickText();
    public String fpsText();
    public String deadlineText();
}
```

### `org.loader.api.diagnostics.MetricsService` / `PerformanceService`

```java
public final class MetricsService {
    public static void registerProvider(Provider p);
    public static void clearProvider(Provider p);   // 按身份撤销
    public static PlatformMetrics current();        // 未装配时返回 empty()
}
```

---

## 14. 字节码转换

### `@org.loader.api.transform.annotation.MiliTransformer`

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface MiliTransformer {
    String target();                       // 逻辑符号名，如 "minecraft.server.tick"
    TransformationPhase phase() default TransformationPhase.MOD;
    int priority() default 1000;
}
```

### `@org.loader.api.transform.annotation.MiliInject`

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface MiliInject {
    InjectionPoint at();
    String method() default "";            // BEFORE_INVOKE / AFTER_INVOKE 必填
    String descriptor() default "";        // 同上
    int ordinal() default -1;
    boolean cancellable() default false;
    ...
}
```

### `org.loader.api.transform.InjectionPoint`

```java
public enum InjectionPoint {
    HEAD, RETURN, BEFORE_INVOKE, AFTER_INVOKE, MODIFY_RETURN, MODIFY_CONSTANT
}
```

### 可用逻辑符号名

| 符号名 | 真实坐标 | 环境 |
|---|---|---|
| `minecraft.server.tick` | `MinecraftServer#tickServer(BooleanSupplier)V` | 服务端 |
| `minecraft.server.tick_children` | `MinecraftServer#tickChildren(BooleanSupplier)V` | 服务端 |
| `minecraft.client.level_tick` | `ClientLevel#tick(BooleanSupplier)V` | 客户端 |
| `minecraft.client.tick` | `Minecraft#tick()V` | 客户端 |
| `minecraft.client.brand` | `ClientBrandRetriever#getClientModName()String` | 客户端 |
| `minecraft.client.title_screen_init` | `TitleScreen#init()V` | 客户端 |
| `minecraft.client.screen_init` | `Screen#init(II)V` | 客户端 |
| `minecraft.client.screen_render_state` | `Screen#extractRenderStateWithTooltipAndSubtitles(...)V` | 客户端 |
| `minecraft.client.main` | `Main#main(String[])V` | 客户端 |
| `minecraft.server.main` | `Main#main(String[])V` | 服务端 |

符号名写错在**加载期**抛 `TransformationException` 并列出可用符号。符号表与 `MiliMapping` 的一致性由 `AnnotationTransformerScanner.assertConsistentWithMapping()` 强制——两份表漂移会让「Mod 声明的目标」与「平台使用的目标」不一致，且不报错。

---

## 15. 异常

| 类型 | 基类 | 用途 |
|---|---|---|
| `MiliException` | `RuntimeException` | 平台异常基类 |
| `ModException` | `MiliException` | Mod 相关错误 |
| `CapabilityDeniedException` | `MiliException` | 能力被拒绝 |
| `PermissionDeniedException` | `MiliException` | 权限被拒绝 |
| `ScreenUnavailableException` | `MiliException` | 客户端界面服务不可用 |
| `KeyBindingUnavailableException` | `MiliException` | 客户端输入服务不可用 |
| `StorageUnavailableException` | `MiliException` | 存储服务未装配 |
| `ConfigUnavailableException` | `MiliException` | 配置服务未装配 |
| `ConfigWriteException` | `MiliException` | 配置写入失败 |
| `TransformationException` | `MiliException` | 转换失败 |
| `TransformationConflictException` | `TransformationException` | 转换冲突 |
| `TransformationTargetNotFoundException` | `TransformationException` | 目标方法不存在 |
| `TransformationVerificationException` | `TransformationException` | 转换结果未通过字节码验证 |
| `ServiceUnavailableException` | `IllegalStateException` | 服务缺失 |
