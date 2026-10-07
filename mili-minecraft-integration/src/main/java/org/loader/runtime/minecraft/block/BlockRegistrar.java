package org.loader.runtime.minecraft.block;

import org.loader.api.registry.BlockSpec;
import org.loader.runtime.minecraft.BootstrapGate;
import org.loader.runtime.minecraft.RegistrationPhase;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Mod 声明的方块真正写进 Minecraft 的方块注册表。
 *
 * <p><b>为什么单独一个类</b>：{@link GeneratedBlockFactory} 只负责「造出一个
 * {@code Block} 实例」，而「注册」是另一件事 —— 它需要 {@code Registry} /
 * {@code ResourceKey} / {@code Identifier} 三者配合，还必须在注册表<b>可写</b>
 * 的时间窗口内调用。历史上这两件事被混在一个类里，结果「能造出来但没注册」，
 * 方块在游戏里根本不存在。
 *
 * <h2>26.2 的注册流程（实测）</h2>
 * <pre>
 *   Registry&lt;Block&gt; registry = BuiltInRegistries.BLOCK;
 *   ResourceKey&lt;Block&gt;  key      = ResourceKey.create(Registries.BLOCK, id);
 *   Properties                  props   = Properties.of().setId(key);
 *   Block                       block   = new MiliBlock(props);
 *   Registry.register(registry, key, block);
 * </pre>
 *
 * <p><b>顺序不可换</b>：{@code setId} 必须在 {@code new Block} 之前（否则
 * 构造器抛 {@code NPE: Block id not set}）；{@code register} 必须在
 * {@code new Block} 之后（需要一个实例）。
 *
 * <p>全部走反射 —— integration 模块编译期不依赖 Minecraft（ADR 0005）。
 */
public final class BlockRegistrar {

    private static final String REGISTRY_CLASS = "net.minecraft.core.Registry";
    private static final String BUILTIN_REGISTRIES_CLASS = "net.minecraft.core.registries.BuiltInRegistries";
    private static final String REGISTRIES_CLASS = "net.minecraft.core.registries.Registries";

    private final String modId;
    /** Mod 的 jar 文件；用于从 blockstate 推断方块属性。可为 null（无推断源）。 */
    private final java.nio.file.Path modJar;
    /** 已成功注册的 key → Block，用于重复注册检测与诊断。 */
    private final Map<String, Object> registered = new LinkedHashMap<>();

    public BlockRegistrar(String modId) {
        this(modId, null);
    }

    public BlockRegistrar(String modId, java.nio.file.Path modJar) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        this.modId = modId;
        this.modJar = modJar;
    }

    public String modId() {
        return modId;
    }

    /** 已注册的方块数。 */
    public int registeredCount() {
        return registered.size();
    }

    /**
     * 注册一个方块。
     *
     * @param spec 方块规格（{@code spec.path()} 决定注册 id）
     * @return 注册后的 {@code Block} 实例
     * @throws BridgeMismatchException 反射路径与目标版本不符时
     * @throws IllegalStateException  同一 id 重复注册时
     */
    public Object register(BlockSpec spec) {
        return register(spec, spec.behavior().orElse(null));
    }

    /**
     * 注册一个方块并绑定行为实现。
     *
     * @param spec      方块规格
     * @param behaviour 行为实现；可为 null 表示无行为的纯数据方块
     * @return 注册后的 {@code Block} 实例
     */
    public Object register(BlockSpec spec, org.loader.api.world.MiliBlock behaviour) {
        // 前置检查：注册窗口关着就别试了。让调用方拿到"来晚了"这种可理解的
        // 错误，而不是深入反射层撞上 This registry can't create intrusive holders。
        org.loader.runtime.minecraft.RegistrationPhase.openRegistryWindow();

        String fullId = resolveId(spec.path());
        if (registered.containsKey(fullId)) {
            throw new IllegalStateException(
                    "Block already registered by this mod: " + fullId
                            + "\n  两个 BlockSpec 使用了同一个 path。注册 id 必须唯一。");
        }

        try {
            // 1. Properties（含 setId）—— 必须在 new Block 之前。
            //    注意这里不调 BootstrapGate.ensureBootstrapped()：方块注册必须
            //    发生在游戏 bootstrap 之前（见 RegistrationPhase 的类注释），
            //    而 Properties.of() 需要 SharedConstants 版本就位 —— 那是
            //    tryDetectVersion 的职责，与 freeze 无关，故单独调用。
            org.loader.runtime.minecraft.SharedVersionGate.ensureVersionDetected();
            Object properties = BlockPropertiesBuilder.build(spec, modId);

            // 2. 行为句柄 —— 先拿，便于注册失败时不留悬挂条目
            BehaviourDispatch.Handle handle = behaviour == null
                    ? null
                    : BehaviourDispatch.register(behaviour, fullId);

            // 3. 造出 Block 实例。
            //    属性从 Mod 自己的 blockstate JSON 推断（BlockSpec 尚无属性 API）：
            //    没有 blockstate 就返回空列表，行为与不推断时完全一致。
            String[] idParts = splitId(fullId);
            java.util.List<Object> stateProperties =
                    BlockStatePropertyInference.inferProperties(
                            modJar, idParts[0], idParts[1]);
            Object block;
            try {
                block = GeneratedBlockFactory.createBlock(
                        properties, handle, fullId, stateProperties);
            } catch (RuntimeException | Error e) {
                if (handle != null) {
                    BehaviourDispatch.unregister(handle);
                }
                throw e;
            }

            // 4. 写进注册表。26.2 的注册表在 freeze 后不可写，因此这一步
            //    失败几乎总是"来晚了"——消息必须指向时序而非 API 细节。
            //
            //    泛型擦除后签名是 register(Registry, ResourceKey, Object)，
            //    但参数类型必须用【接口】Registry.class 本身，不能用
            //    BuiltInRegistries.BLOCK 的实现类（DefaultedMappedRegistry）：
            //    静态方法声明在接口上，用实现类去 getMethod 会报
            //    NoSuchMethodException: Registry.register(
            //        DefaultedMappedRegistry, ResourceKey, Object)
            Object key = blockKey(fullId);
            Object registry = blockRegistry();
            registryRegister().invoke(null, registry, key, block);

            registered.put(fullId, block);
            return block;
        } catch (BridgeMismatchException e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable real = e.getCause() != null ? e.getCause() : e;
            throw new BridgeMismatchException(
                    "Registering block " + fullId + " into Minecraft's registry failed."
                            + "\n  Real cause: " + real
                            + "\n  In 26.2 the block registry is frozen by"
                            + " Bootstrap.bootStrap(); blocks created afterwards cannot be"
                            + " registered. Call"
                            + " RegistrationPhase.runRegistrationPhase(...) before"
                            + " BootstrapGate.ensureBootstrapped().", real);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot register block " + fullId + " into Minecraft's block registry."
                            + "\n  Registry.register(Registry, ResourceKey, T) is the"
                            + " expected 26.2 API."
                            + "\n  This is a binding-layer bug, not a mod bug.", e);
        }
    }

    /**
     * 从注册表按 id 取回方块；未注册返回 null。
     *
     * <p>用<b>实例</b>方法 {@code getValue(ResourceKey)}。
     * {@code Registry} 上没有静态的 {@code get(Registry, ResourceKey)} ——
     * 那些 {@code get} 全是按 id/int 取的实例方法，泛型擦除后
     * {@code get(Registry, ResourceKey)} 根本不存在。反射找不到会抛
     * {@code NoSuchMethodException}，若被 catch 吞掉就表现为
     * 「注册成功但永远查不到」，极具误导性。
     */
    public Object lookup(String path) {
        Object registry = blockRegistry();
        Class<?> registryType = Reflect.gameClass(REGISTRY_CLASS);
        Class<?> keyType = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        try {
            Object key = blockKey(modId + ":" + normalizePath(path));
            Method getValue = registryType.getMethod("getValue", keyType);
            return getValue.invoke(registry, key);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot read block '" + modId + ":" + path + "' back from the"
                            + " Minecraft registry."
                            + "\n  Registry.getValue(ResourceKey) is the 26.2 lookup API."
                            + "\n  Real cause: " + e, e);
        }
    }

    /** 本 registrar 已注册的全部 id。 */
    public java.util.Set<String> registeredIds() {
        return java.util.Collections.unmodifiableSet(registered.keySet());
    }

    /**
     * 读回方块在游戏注册表中的数值 ID。
     *
     * <p>26.2 里注册顺序决定数值 ID，Mod 无法自行指定 —— 只能问注册表。
     * {@code Registry.getId(T)} 是实例方法，泛型擦除后签名是
     * {@code getId(Object)}。
     *
     * @return 数值 ID；查询失败返回 {@code -1}
     */
    public int numericIdOf(String path) {
        Object block;
        // lookup() 会触碰 BuiltInRegistries.BLOCK —— 游戏尚未 bootstrap 时，
        // 首次访问该类会触发 <clinit>，其中的 registerDefaulted 调
        // Bootstrap.checkBootstrapCalled 抛 IllegalArgumentException，
        // 被 JVM 包装成 ExceptionInInitializerError（继承 Error，
        // **不是** RuntimeException）。
        //
        // 这里必须一并兜住 Error：读数值ID 是纯只读的增强动作
        // （拿不到就返回 -1，句柄照样有效），绝不该让调用方的
        // 一次查询变成致命错误。
        try {
            block = lookup(path);
        } catch (RuntimeException | Error notQueryableYet) {
            LOG.log(java.util.logging.Level.FINE,
                    "Block registry not queryable yet for " + modId + ":" + path,
                    notQueryableYet);
            return -1;
        }
        if (block == null) {
            return -1;
        }
        try {
            Object id = Reflect.gameClass(REGISTRY_CLASS)
                    .getMethod("getId", Object.class)
                    .invoke(blockRegistry(), block);
            return id instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | Error e) {
            LOG.log(java.util.logging.Level.WARNING,
                    "Cannot read numeric id for block " + modId + ":" + path, e);
            return -1;
        }
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("Mili/BlockRegistrar");

    // ── 反射工具 ────────────────────────────────────────────────────────────

    /**
     * 取出 {@code BuiltInRegistries.BLOCK}，类型是 {@code Registry<Block>}。
     * <p>泛型在运行期被擦除成 {@code Registry}，直接用即可。
     */
    private static Object blockRegistry() {
        // 【关键】触碰 BuiltInRegistries 前必须先置Bootstrap.isBootstrapped。
        //
        // 直接读静态字段会触发 <clinit>，而 26.2 的 <clinit> 要求该标志为 true，
        // 否则抛 "Not bootstrapped"；更麻烦的是 **类初始化失败不可重试** ——
        // JVM 会把该类永久标记为 Erroneous，之后任何访问都是
        // NoClassDefFoundError，而不是重新尝试初始化。
        //
        // 于是「谁先碰到注册表类」就成了全局时序问题：若Mod 在 initialize()
        // 里做了一次只读查询(findBlock)，它就抢在平台开窗之前毒化了这个类，
        // 平台随后开窗必然失败，而这个错误完全指不到「某个 Mod 早先查了一下」。
        //
        // ensureRegistriesReadable() 固化了唯一安全的顺序：先置标志，再触碰。
        RegistrationPhase.ensureRegistriesReadable();

        Object registries = Reflect.staticField(BUILTIN_REGISTRIES_CLASS, "BLOCK");
        if (registries == null) {
            throw new BridgeMismatchException(
                    "BuiltInRegistries.BLOCK is null — Bootstrap.bootStrap() not completed?");
        }
        return registries;
    }

    /**
     * 构造 {@code ResourceKey.create(Registries.BLOCK, identifier)}。
     * <p>拿到的 ResourceKey 同时供 {@code Properties.setId} 与
     * {@code Registry.register} 使用 —— <b>必须是同一个 key 实例</b>，
     * 否则 Block 的 id 与注册表里的 key 不一致，后续
     * {@code BuiltInRegistries.BLOCK.getKey(block)} 会返回 null。
     */
    private static Object blockKey(String fullId) throws ReflectiveOperationException {
        Class<?> resourceKeyClass = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        Class<?> identifierClass = identifierClass();
        String[] parts = splitId(fullId);

        Object identifier = identifierClass
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, parts[0], parts[1]);
        // 【同样要保护】读Registries.BLOCK 也会触发 Registries 类的 <clinit>。
        // 它与 BuiltInRegistries 是两个独立的类，各自的初始化失败都不可重试。
        RegistrationPhase.ensureRegistriesReadable();
        Object blockRegistryKey = Reflect.staticField(REGISTRIES_CLASS, "BLOCK");
        return resourceKeyClass
                .getMethod("create", resourceKeyClass, identifierClass)
                .invoke(null, blockRegistryKey, identifier);
    }

    /** 26.2 是 {@code Identifier}，旧版本是 {@code ResourceLocation}。 */
    private static Class<?> identifierClass() {
        try {
            return Reflect.gameClass("net.minecraft.resources.Identifier");
        } catch (RuntimeException e) {
            try {
                return Reflect.gameClass("net.minecraft.resources.ResourceLocation");
            } catch (RuntimeException e2) {
                throw new BridgeMismatchException(
                        "Neither net.minecraft.resources.Identifier nor ResourceLocation"
                                + " exists. Minecraft version mismatch?", e2);
            }
        }
    }

    private static String[] splitId(String fullId) {
        int colon = fullId.indexOf(':');
        if (colon > 0) {
            return new String[]{fullId.substring(0, colon), fullId.substring(colon + 1)};
        }
        return new String[]{"minecraft", normalizePath(fullId)};
    }

    /**
     * 由 {@code path} 推出完整注册 id。
     * <p>path 已带命名空间时以它为准，否则用本 registrar 的 modId 兜底。
     */
    private String resolveId(String path) {
        if (path != null && path.indexOf(':') > 0) {
            return path;
        }
        return modId + ":" + normalizePath(path);
    }

    private static String normalizePath(String path) {
        return (path == null || path.isBlank()) ? "block" : path.trim();
    }

    /**
     * 找 {@code Registry} 接口的 {@code register} 方法。
     *
     * <p>泛型擦除后 {@code Registry.register} 的签名是
     * {@code (Registry, ResourceKey, Object)}，但 {@code BuiltInRegistries.BLOCK}
     * 的静态字段类型可能是 {@code Registry}（接口）或它的实现类。用实现类去
     * {@code getMethod} 找不到接口上的静态方法 —— 必须拿到接口类型。
     */
    /**
     * {@code Registry.register(Registry, ResourceKey, Object)}。
     *
     * <p>参数类型一律用接口 {@code Registry.class}。泛型擦除后方法签名里
     * 就是 {@code Registry}，而静态方法声明在接口上 —— 用
     * {@code BuiltInRegistries.BLOCK} 的运行时类（{@code DefaultedMappedRegistry}）
     * 去匹配会得到 {@code NoSuchMethodException}。
     */
    private static Method registryRegister() throws NoSuchMethodException {
        Class<?> registryIface = Reflect.gameClass(REGISTRY_CLASS);
        return registryIface.getMethod("register", registryIface,
                Reflect.gameClass("net.minecraft.resources.ResourceKey"), Object.class);
    }
    private static Class<?> findRegistrySuperType(Object registry) {
        for (Class<?> c = registry.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(REGISTRY_CLASS)) {
                return c;
            }
        }
        return Object.class;
    }
}
