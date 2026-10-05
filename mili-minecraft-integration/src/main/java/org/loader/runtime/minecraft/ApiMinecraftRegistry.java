package org.loader.runtime.minecraft;

import org.loader.api.registry.BlockSpec;
import org.loader.api.registry.ItemSpec;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.block.BlockRegistrar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link MinecraftRegistry} 的真实实现 —— Mod 侧注册表契约的落地端。
 *
 * <p><b>为什么不能是空壳</b>：早期版本这里是个 {@code Map<String, Object>}，
 * 记下 Mod 声明了什么就返回什么。它能通过所有「注册再查回来」的单元测试，
 * 但游戏里根本没有那个方块 —— Mod 拿到句柄后交给玩法代码，
 * 反射层去 {@code BuiltInRegistries.BLOCK} 找，只会觉得 Mod 作者在编造 id。
 * <b>假绿比没有更糟</b>：契约看起来实现了，实现是空心的。
 *
 * <p>本类把每一句契约都接到真实的 Minecraft 注册表上：
 * <ul>
 *   <li>{@link #block} → {@link BlockRegistrar#register} → 真正的 {@code Block} 实例</li>
 *   <li>{@link #findBlock} → {@code Registry.getValue(ResourceKey)} 回读同一个实例</li>
 *   <li>{@link #isOpen} → {@link RegistrationPhase#isWindowOpen()}，即 26.2 真实的可写状态</li>
 * </ul>
 *
 * <h2>注册时机：为什么用 defer 而不直接注册</h2>
 *
 * <p>{@link #block} 被调用时，游戏几乎总是还没 bootstrap（Mod 的
 * {@code initialize} 早于游戏启动）。此时注册表<b>尚未初始化</b>，
 * 直接 new Block 会因 {@code BuiltInRegistries} 的类初始化门而失败。
 *
 * <p>所以这里的做法是：{@link RegistrationPhase#defer} 把真正的注册动作
 * 排队，等平台在正确时机（{@link BootstrapGate#ensureBootstrapped()}，
 * 窗口开着的那一瞬）统一执行。
 *
 * <p><b>代价，必须说清楚</b>：延后执行意味着 {@link #block} 返回时
 * 句柄已经存在，但游戏里对应的方块<b>还没被创建</b>。
 * {@link BlockHandle#numericId()} 此时只能返回 {@code -1}，
 * 真实 ID 要等注册完成后才可读。这不是缺陷，是 26.2 注册表语义下
 * 唯一可行的做法 —— 句柄是<b>意图</b>的标识，落地发生在稍后。
 * 依赖 numericId 做持久化本就不允许（契约已注明它是「非稳定标识」）。
 */
public final class ApiMinecraftRegistry implements MinecraftRegistry {

    private final String modId;
    private final BlockRegistrar registrar;

    /** 已声明的方块：path → 句柄。按声明顺序保留，便于诊断。 */
    private final Map<String, BlockHandle> declaredBlocks = new LinkedHashMap<>();
    /** 已声明的物品：path → 句柄。 */
    private final Map<String, org.loader.api.registry.ItemHandle> declaredItems =
            new LinkedHashMap<>();

    /**
     * 物品反射路径是否可用 —— <b>惰性求值，首次调 {@link #item} 时才探测</b>。
     *
     * <p><b>为什么不能放在构造函数里探测</b>：那会让「创建一个注册表视图」
     * 这个纯内存动作强依赖 Minecraft 类可加载，于是
     * {@code registry().block(...)} 在没有游戏 ClassLoader 的环境里
     * （平台的纯契约测试、CI 的无 MC 构建）直接构造失败 ——
     * 而方块注册走的是 {@code defer} 排队，此刻<b>根本不需要</b>游戏类。
     *
     * <p>探测只查类是否存在，不做任何写入，理由同
     * {@code GeneratedBlockFactory.selfCheck()}：探测绝不能有副作用。
     */
    private volatile Boolean itemBindingAvailable;

    private boolean itemBindingAvailable() {
        Boolean cached = itemBindingAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            org.loader.runtime.minecraft.reflect.Reflect
                    .gameClass("net.minecraft.world.item.Item");
            available = true;
        } catch (RuntimeException e) {
            available = false;
        }
        itemBindingAvailable = available;
        return available;
    }

    public ApiMinecraftRegistry(String modId) {
        this(modId, new BlockRegistrar(modId));
    }

    public ApiMinecraftRegistry(String modId, BlockRegistrar registrar) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    public String modId() {
        return modId;
    }

    // ── 契约实现 ────────────────────────────────────────────────────────────

    @Override
    public BlockHandle block(BlockSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String path = normalizePath(spec.path());

        BlockHandle existing = declaredBlocks.get(path);
        if (existing != null) {
            // 重复注册必须响亮失败。静默返回旧句柄会让 Mod 以为改配置生效了，
            // 而游戏里还是第一个方块。
            throw new IllegalStateException(
                    "Mod '" + modId + "' already registered block '" + path + "'."
                            + "\n  Registering the same path twice is a bug in the mod:"
                            + " the second call would silently shadow the first.");
        }

        BlockHandle handle = BlockHandle.create(modId, path, -1);
        declaredBlocks.put(path, handle);

        try {
            RegistrationPhase.defer(() -> registrar.register(spec, spec.behavior().orElse(null)));
        } catch (RuntimeException e) {
            // 排队失败就别留下一个永远不会被注册的句柄。
            declaredBlocks.remove(path);
            throw e;
        }
        return handle;
    }

    @Override
    public org.loader.api.registry.ItemHandle item(ItemSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (!itemBindingAvailable()) {
            // 诚实失败 > 静默失效。见字段注释。
            throw new UnsupportedOperationException(
                    "Item registration is not available: net.minecraft.world.item.Item"
                            + " could not be loaded in this game instance."
                            + "\n  Block registration (registry().block(...)) works and is"
                            + " unaffected.");
        }
        String path = normalizePath(spec.path());
        if (declaredItems.containsKey(path)) {
            throw new IllegalStateException(
                    "Mod '" + modId + "' already registered item '" + path + "'.");
        }
        // 走到这里说明物品反射路径存在，但本类尚未接通其注册流程。
        // 明确说清楚是哪一环没做，不让调用方误以为是自己写错了。
        throw new UnsupportedOperationException(
                "Item registration is declared by the ABI contract but the binding to"
                        + " Minecraft 26.2's item registry is not implemented yet."
                        + "\n  Mod '" + modId + "' asked for '" + path + "'."
                        + "\n  Use registry().block(...) meanwhile — block registration is"
                        + " fully functional end to end.");
    }

    @Override
    public Optional<BlockHandle> findBlock(String path) {
        String key = normalizePath(path);
        BlockHandle handle = declaredBlocks.get(key);
        if (handle == null) {
            return Optional.empty();
        }
        // 如果已经落地，就补上真实的数值 ID，让句柄反映游戏真实状态。
        //
        // 这一步是【增强】而非【查询本身】：游戏类尚未可用时（还没 bootstrap、
        // 或根本没有游戏 ClassLoader），查不到数值 ID 不该让整个 findBlock 失败 ——
        // 契约问的是「这个方块注册了吗」，答案已经确定是「注册了」。
        //
        // 早期版本在这里直接调 registrar.numericIdOf(...) 且不容错，结果在没有
        // Minecraft 的环境（平台的纯契约测试、CI）里，一个只读的查询会抛
        // BridgeMismatchException: Minecraft class not found。
        // 那种环境下 Mod 只想确认「我声明的方块在不在」，却被告知
        // 「net.minecraft.core.registries.BuiltInRegistries 找不到」——
        // 错误完全指错了方向。
        if (handle.numericId() >= 0) {
            return Optional.of(handle);
        }
        int numeric;
        try {
            numeric = registrar.numericIdOf(key);
        } catch (RuntimeException notQueryableYet) {
            // 游戏侧还问不了（未 bootstrap / 无游戏 ClassLoader）。如实返回
            // 句柄本身，numericId 保持 -1 直到真正落地。
            return Optional.of(handle);
        }
        return numeric >= 0
                ? Optional.of(BlockHandle.create(modId, key, numeric))
                : Optional.of(handle);
    }

    @Override
    public Optional<org.loader.api.registry.ItemHandle> findItem(String path) {
        return Optional.ofNullable(declaredItems.get(normalizePath(path)));
    }

    @Override
    public Collection<BlockHandle> blocks() {
        return List.copyOf(declaredBlocks.values());
    }

    @Override
    public Collection<org.loader.api.registry.ItemHandle> items() {
        return List.copyOf(declaredItems.values());
    }

    @Override
    public boolean isOpen() {
        // 反映游戏真实状态，不缓存。第一次调用若游戏还没 bootstrap，
        // openRegistryWindow 尚未执行，此时报 false 是正确的 ——
        // 契约说 Mod 应在 initialize 里完成注册，而 defer 让那件事变得可能。
        return RegistrationPhase.isWindowOpen()
                || RegistrationPhase.pendingCount() > 0;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        // 契约规定 path 不含命名空间。若 Mod 传了 "minecraft:stone"，
        // 静默接受会让句柄 id() 变成三段、注册却落在别的命名空间 —— 必须拒绝。
        if (path.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "path must not contain ':' — the namespace is this mod's id ('"
                            + modId + "'), got: " + path);
        }
        return path.trim();
    }

    /** 诊断摘要。 */
    public String diagnostics() {
        List<String> lines = new ArrayList<>();
        lines.add("ApiMinecraftRegistry[" + modId
                + ", declared=" + declaredBlocks.size()
                + ", landed=" + registrar.registeredCount()
                + ", windowOpen=" + RegistrationPhase.isWindowOpen()
                + ", pending=" + RegistrationPhase.pendingCount() + "]");
        for (Map.Entry<String, BlockHandle> e : declaredBlocks.entrySet()) {
            lines.add("  block " + e.getKey() + " -> " + e.getValue());
        }
        return String.join("\n", lines);
    }

    /**
     * 世界视图。
     *
     * <p>契约明确：「世界未加载时返回 {@code null}，Mod 必须自己判空」。
     * 判定由 {@link CurrentWorld} 负责 —— 它按游戏 ClassLoader 登记
     * 最近的 Level，登记来源是游戏自己的方块回调与注入层，
     * 平台不猜测世界是否存在。
     *
     * <p>本方法曾经恒返回 {@code null}。那不是「世界还没准备好」，
     * 而是<b>平台根本没实现这条契约</b>：Mod 里任何一句
     * {@code context.world().getBlock(pos)} 都会 NPE，而错误栈只指向
     * Mod 自己的那一行。
     */
    public WorldView world() {
        return org.loader.runtime.minecraft.world.CurrentWorld.current();
    }
}