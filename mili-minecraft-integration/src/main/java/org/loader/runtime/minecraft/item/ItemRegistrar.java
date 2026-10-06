package org.loader.runtime.minecraft.item;

import org.loader.api.registry.ItemSpec;
import org.loader.runtime.minecraft.RegistrationPhase;
import org.loader.runtime.minecraft.SharedVersionGate;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 把Mod 声明的物品真正写进 Minecraft 的物品注册表。
 *
 * <h2>为什么与 {@code BlockRegistrar} 并列而不是合进去</h2>
 *
 * <p>「造实例」和「注册」在26.2 里是两件事，各需要不同的反射入口
 * （物品是 {@code Item.Properties} + {@code Registries.ITEM}，
 * 方块是 {@code Block.Properties} + {@code Registries.BLOCK}）。
 * 合成一个类会得到一个塞满 {@code if (isItem)} 分支的方法，
 * 而且当初方块那条路径出过「能造出来但没注册」的问题 ——
 * 这里宁可用一点重复换取两条路径各自独立可读。
 *
 * <h2>26.2 的物品注册流程（实测自反编译产物）</h2>
 * <pre>
 *   Registry&lt;Item&gt;      registry = BuiltInRegistries.ITEM;
 *   ResourceKey&lt;Item&gt;   key       = ResourceKey.create(Registries.ITEM, id);
 *   Item.Properties        props     = new Item.Properties();
 *   props.setId(key);          // ← 必须在 new Item 之前
 *   props.stacksTo(n);        // 可选
 *   Item                    item      = new Item(props);   // 或 new BlockItem(block, props)
 *   Registry.register(registry, key, item);
 * </pre>
 *
 * <p><b>为什么 {@code setId} 不可省</b>：26.2 的 {@code Item} 构造器里调用了
 * {@code properties.itemIdOrThrow()}（见反编译的 {@code Item(Properties)}）：
 * 它把组件初始化器挂到 {@code BuiltInRegistries.DATA_COMPONENT_INITIALIZERS}
 * 的那个 id 上。id 为空直接抛异常。也就是说
 * <b>「先 new Item 再补 id」这条路在 26.2 根本不存在</b>，
 * 与方块的 {@code NPE: Block id not set} 是同一类约束。
 *
 * <p><b>intrusive holders</b>：{@code BuiltInRegistries.ITEM} 是
 * {@code DefaultedRegistry} 且用 {@code registerDefaultedWithIntrusiveHolders}
 * 创建 —— 物品自带 {@code Holder.Reference} 字段。
 * 好在 {@link #registryRegister()} 用的是 {@code Registry} 接口上的静态
 * {@code register}，它内部会正确走 intrusive 分支，
 * <b>不需要</b>像某些版本那样手工构造 Holder。
 *
 * <p>全部走反射 —— integration 模块编译期不依赖 Minecraft（ADR 0005）。
 */
public final class ItemRegistrar {

    private static final Logger LOG = Logger.getLogger("Mili/ItemRegistrar");

    private static final String REGISTRY_CLASS = "net.minecraft.core.Registry";
    private static final String BUILTIN_REGISTRIES_CLASS =
            "net.minecraft.core.registries.BuiltInRegistries";
    private static final String REGISTRIES_CLASS = "net.minecraft.core.registries.Registries";
    private static final String ITEM_CLASS = "net.minecraft.world.item.Item";
    private static final String PROPERTIES_CLASS = "net.minecraft.world.item.Item$Properties";

    private final String modId;
    /** 已成功注册的 id → Item 实例，用于重复注册检测与诊断。 */
    private final Map<String, Object> registered = new LinkedHashMap<>();

    public ItemRegistrar(String modId) {
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId must not be blank");
        }
        this.modId = modId;
    }

    public String modId() {
        return modId;
    }

    public int registeredCount() {
        return registered.size();
    }

    /**
     * 注册一个物品。
     *
     * @param spec 物品规格（{@code spec.path()} 决定注册 id）
     * @return 注册后的 {@code Item} 实例
     * @throws BridgeMismatchException 反射路径与目标版本不符时
     * @throws IllegalStateException    同一 id 重复注册时
     */
    public Object register(ItemSpec spec) {
        // 与方块同构：窗口没开就别试，让调用方拿到「来晚了」这种可理解的错误，
        // 而不是深入反射层撞上 intrusive holder 的实现细节。
        RegistrationPhase.openRegistryWindow();

        String fullId = resolveId(spec.path());
        if (registered.containsKey(fullId)) {
            throw new IllegalStateException(
                    "Item already registered by this mod: " + fullId
                            + "\n  两个 ItemSpec 使用了同一个 path。注册 id 必须唯一。");
        }

        try {
            // Properties.of() 需要 SharedConstants 版本就位，与注册表 freeze 无关，
            // 故与方块路径一样单独调用（见 BlockRegistrar 的同位置注释）。
            SharedVersionGate.ensureVersionDetected();

            Object key = itemKey(fullId);
            Object properties = buildProperties(spec, fullId, key);

            // 方块物品必须用 BlockItem，否则游戏不会正确渲染与放置。
            // spec.blockItem() 给出的是 Mod 侧的方块句柄 —— 它在登记时
            // 数值ID 可能还是 -1（落地发生在稍后的注册窗口），所以这里
            // 只能按 path 回查真实 Block 实例。
            Object item = spec.blockItem().isPresent()
                    ? createBlockItem(spec, properties, fullId)
                    : createPlainItem(properties, fullId);

            Object registry = itemRegistry();
            registryRegister().invoke(null, registry, key, item);

            registered.put(fullId, item);
            return item;
        } catch (BridgeMismatchException e) {
            throw e;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable real = e.getCause() != null ? e.getCause() : e;
            throw new BridgeMismatchException(
                    "Registering item " + fullId + " into Minecraft's item registry failed."
                            + "\n  Real cause: " + real
                            + "\n  In 26.2 the item registry is frozen by"
                            + " Bootstrap.bootStrap(); items created afterwards cannot be"
                            + " registered. Registration is deferred to"
                            + " RegistrationPhase and executed by"
                            + " BootstrapGate.ensureBootstrapped() — if you see this from"
                            + " Mod.initialize(), the platform failed to defer it.", real);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot register item " + fullId + " into Minecraft's item registry."
                            + "\n  Expected 26.2 API: new Item.Properties() -> setId(key)"
                            + " -> new Item(props) -> Registry.register(registry, key, item)."
                            + "\n  This is a binding-layer bug, not a mod bug.", e);
        }
    }

    // ── 构造 ────────────────────────────────────────────────────────────────

    /** 普通物品：{@code new Item(properties)}。 */
    private Object createPlainItem(Object properties, String fullId)
            throws ReflectiveOperationException {
        Class<?> itemClass = Reflect.gameClass(ITEM_CLASS);
        return itemClass.getConstructor(Reflect.gameClass(PROPERTIES_CLASS))
                .newInstance(properties);
    }

    /**
     * 方块物品：{@code new BlockItem(block, properties)}。
     *
     * <p>不传 BlockItem 的话，游戏里这个物品放不出方块 —— 契约把
     * {@code blockItem()} 作为独立入口正是为了这个。
     */
    private Object createBlockItem(ItemSpec spec, Object properties, String fullId)
            throws ReflectiveOperationException {
        String blockPath = spec.blockItem().orElseThrow().path();
        Object block = lookupBlock(blockPath);
        if (block == null) {
            throw new BridgeMismatchException(
                    "Item " + fullId + " declares blockItem('" + blockPath
                            + "') but that block is not in Minecraft's block registry."
                            + "\n  方块物品必须先注册对应方块，再注册物品本身。"
                            + "\n  声明顺序通常是 block(...) 先、item(...).blockItem(...) 后。");
        }
        Class<?> blockItemClass = Reflect.gameClass("net.minecraft.world.item.BlockItem");
        return blockItemClass
                .getConstructor(Reflect.gameClass("net.minecraft.world.level.block.Block"),
                        Reflect.gameClass(PROPERTIES_CLASS))
                .newInstance(block, properties);
    }

    /**
     * 把 {@link ItemSpec} 的数据字段搬进 {@code Item.Properties}。
     *
     * <p><b>逐方法探测，而不是假设存在</b>：26.2 的 Properties 用
     * {@code stacksTo} / {@code durability}，而更早版本是
     * {@code maxStackSize} / {@code maxDamage}。写死方法名会在版本
     * 变化时抛 {@code NoSuchMethodException}，而那会把「这个字段没设」
     * 误报成「整个绑定层坏了」。
     */
    private Object buildProperties(ItemSpec spec, String fullId, Object key)
            throws ReflectiveOperationException {
        Class<?> propsClass = Reflect.gameClass(PROPERTIES_CLASS);
        Object props = propsClass.getDeclaredConstructor().newInstance();

        // setId 必须在最前：Item 构造器会读它，缺了直接抛。
        propsClass.getMethod("setId", Reflect.gameClass("net.minecraft.resources.ResourceKey"))
                .invoke(props, key);

        // 堆叠数：stacksTo(int)（26.2）/ maxStackSize(int)（旧版）
        invokeIntSetter(props, propsClass, spec.maxStackSize(),
                "stacksTo", "maxStackSize");

        // 耐久：durability(int)（26.2）/ maxDamage(int)（旧版）。
        // 只在有耐久时设 —— 无耐久物品显式写 0 会与组件默认值冲突。
        if (spec.maxDamage() > 0) {
            invokeIntSetter(props, propsClass, spec.maxDamage(),
                    "durability", "maxDamage");
        }

        // fireResistant() 是无参的（26.2 确认签名如此）
        if (spec.isFireResistant()) {
            Method m = findMethod(propsClass, "fireResistant");
            if (m != null) {
                m.invoke(props);
            }
        }

        return props;
    }

    /**
     * 调用一个 {@code int} 入参的链式 setter，返回 {@code Properties}。
     *
     * <p>两个候选名都试：第一个不存在就退到第二个。都不存在时
     * <b>不抛异常</b> —— 少设一个可选字段不该让整个注册失败，
     * 但要留日志，否则「堆叠数没生效」会变成无头案。
     */
    private void invokeIntSetter(Object props, Class<?> propsClass, int value,
                                 String primary, String fallback) {
        for (String name : new String[]{primary, fallback}) {
            Method m = findMethod(propsClass, name, int.class);
            if (m != null) {
                try {
                    m.invoke(props, value);
                    return;
                } catch (ReflectiveOperationException e) {
                    LOG.log(Level.WARNING, "Item properties setter " + name
                            + " failed for value " + value, e);
                    return;
                }
            }
        }
        LOG.warning("Neither " + primary + " nor " + fallback
                + " exists on Item.Properties — field left at game default."
                + " (Minecraft version drift?)");
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    // ── 回读 ────────────────────────────────────────────────────────────────

    /**
     * 按 id 从游戏注册表取回物品；未注册返回 {@code null}。
     *
     * <p>与方块一样用<b>实例</b>方法 {@code getValue(ResourceKey)} ——
     * {@code Registry} 上没有静态的 {@code get(Registry, ResourceKey)}。
     */
    public Object lookup(String path) {
        Object registry = itemRegistry();
        Class<?> registryType = Reflect.gameClass(REGISTRY_CLASS);
        Class<?> keyType = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        try {
            Object key = itemKey(resolveId(path));
            return registryType.getMethod("getValue", keyType).invoke(registry, key);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot read item '" + modId + ":" + path + "' back from the"
                            + " Minecraft registry."
                            + "\n  Registry.getValue(ResourceKey) is the 26.2 lookup API."
                            + "\n  Real cause: " + e, e);
        }
    }

    /** 已注册物品的数值 ID；查询失败返回 {@code -1}。 */
    public int numericIdOf(String path) {
        Object item;
        // 读注册表会触碰 BuiltInRegistries.ITEM，游戏未 bootstrap 时首次访问
        // 该类会在 <clinit> 里失败并抛 ExceptionInInitializerError（Error 而非
        // RuntimeException）—— 必须兜住 Error，纯只读查询不该变成致命错误。
        try {
            item = lookup(path);
        } catch (RuntimeException | Error notQueryableYet) {
            LOG.log(Level.FINE,
                    "Item registry not queryable yet for " + modId + ":" + path,
                    notQueryableYet);
            return -1;
        }
        if (item == null) {
            return -1;
        }
        try {
            Object id = Reflect.gameClass(REGISTRY_CLASS)
                    .getMethod("getId", Object.class)
                    .invoke(itemRegistry(), item);
            return id instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | Error e) {
            LOG.log(Level.WARNING,
                    "Cannot read numeric id for item " + modId + ":" + path, e);
            return -1;
        }
    }

    /** 按路径回查真实 Block 实例（供 {@code blockItem} 绑定用）。 */
    private Object lookupBlock(String path) {
        try {
            // 与 itemRegistry() 同理：先走安全路径，再触碰注册表类。
            // 这里直接读 BuiltInRegistries.BLOCK，若不置标志就会毒化整个类。
            RegistrationPhase.ensureRegistriesReadable();
            Object registries = Reflect.staticField(BUILTIN_REGISTRIES_CLASS, "BLOCK");
            if (registries == null) {
                return null;
            }
            Class<?> keyType = Reflect.gameClass("net.minecraft.resources.ResourceKey");
            Object key = resourceKey(REGISTRIES_CLASS, "BLOCK", modId, path);            return Reflect.gameClass(REGISTRY_CLASS)
                    .getMethod("getValue", keyType)
                    .invoke(registries, key);
        } catch (ReflectiveOperationException | RuntimeException | Error e) {
            LOG.log(Level.WARNING, "Cannot look up block " + modId + ":" + path, e);
            return null;
        }
    }

    public Set<String> registeredIds() {
        return Collections.unmodifiableSet(registered.keySet());
    }

    // ── 反射工具（与 BlockRegistrar 同构） ──────────────────────────────────

    private static Object itemRegistry() {
        // 【关键】触碰 BuiltInRegistries 前必须先置 Bootstrap.isBootstrapped。
        // 直接读静态字段会触发 <clinit>，而 26.2 的 <clinit> 要求该标志为 true；
        // 且 **类初始化失败不可重试** —— JVM 会永久标记该类为 Erroneous，
        // 之后任何访问都抛 NoClassDefFoundError。
        // 详见 BlockRegistrar.blockRegistry() 的同处注释与 RegistrationPhase.ensureRegistriesReadable()。
        RegistrationPhase.ensureRegistriesReadable();

        Object registries = Reflect.staticField(BUILTIN_REGISTRIES_CLASS, "ITEM");
        if (registries == null) {
            throw new BridgeMismatchException(
                    "BuiltInRegistries.ITEM is null — Bootstrap.bootStrap() not completed?");
        }
        return registries;
    }

    /**
     * {@code ResourceKey.create(Registries.ITEM, identifier)}。
     *
     * <p>返回的 key 同时供 {@code Properties.setId} 与
     * {@code Registry.register} 使用 —— <b>必须是同一个实例</b>，
     * 否则 Item 的 id 与注册表里的 key 不一致。
     */
    private static Object itemKey(String fullId) throws ReflectiveOperationException {
        return resourceKey(REGISTRIES_CLASS, "ITEM", fullId);
    }

    private static Object resourceKey(String registryKeyOwner, String fieldName, String fullId)
            throws ReflectiveOperationException {
        return resourceKey(registryKeyOwner, fieldName, splitId(fullId)[0], splitId(fullId)[1]);
    }

    /**
     * 构造 {@code ResourceKey.create(Registries.<fieldName>, identifier)}。
     *
     * <p><b>字段名必须显式传入</b>：早先这里用「取类名最后一段当字段名」的
     * 推导方式，而 {@code Registries} 的字段（ITEM / BLOCK）恰好与类名不同，
     * 推导必然拿到不存在的字段名 {@code Registries}。这种错误在反射下
     * 表现为 {@code NoSuchFieldException}，完全指不到真正原因。
     */
    private static Object resourceKey(String registryKeyOwner, String fieldName,
                                      String namespace, String path)
            throws ReflectiveOperationException {
        Class<?> resourceKeyClass = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        Class<?> identifierClass = identifierClass();
        Object identifier = identifierClass
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
        // 【同样要保护】读 Registries.<field> 也会触发 Registries 类的 <clinit>。
        // 它与 BuiltInRegistries 是两个独立的类，各自的初始化失败都不可重试。
        RegistrationPhase.ensureRegistriesReadable();
        Object registryKey = Reflect.staticField(registryKeyOwner, fieldName);
        if (registryKey == null) {
            throw new BridgeMismatchException(
                    registryKeyOwner + "." + fieldName
                            + " is null — Minecraft version mismatch?");
        }
        return resourceKeyClass
                .getMethod("create", resourceKeyClass, identifierClass)
                .invoke(null, registryKey, identifier);
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

    private String resolveId(String path) {
        if (path != null && path.indexOf(':') > 0) {
            return path;
        }
        return modId + ":" + normalizePath(path);
    }

    private static String normalizePath(String path) {
        return (path == null || path.isBlank()) ? "item" : path.trim();
    }

    /**
     * {@code Registry.register(Registry, ResourceKey, Object)}。
     *
     * <p>参数类型一律用接口 {@code Registry.class}：静态方法声明在接口上，
     * 用 {@code BuiltInRegistries.ITEM} 的运行时类
     * （{@code DefaultedMappedRegistry}）去匹配会得到
     * {@code NoSuchMethodException}。这与方块路径踩过的坑完全一致。
     */
    private static Method registryRegister() throws NoSuchMethodException {
        Class<?> registryIface = Reflect.gameClass(REGISTRY_CLASS);
        return registryIface.getMethod("register", registryIface,
                Reflect.gameClass("net.minecraft.resources.ResourceKey"), Object.class);
    }
}
