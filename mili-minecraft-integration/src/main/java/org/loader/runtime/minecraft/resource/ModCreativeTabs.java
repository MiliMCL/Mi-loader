package org.loader.runtime.minecraft.resource;

import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 按 Mod id 自动分发创造模式标签。
 *
 * <h2>26.2 的机制（逐条查证，不是推测）</h2>
 *
 * <p>26.2 <b>没有</b>「按 group 自动归类」这回事：
 * <ul>
 *   <li>{@code Item.Properties} 里<b>没有</b> {@code creativeTab} / {@code group} 方法
 *       —— 所以 {@code ItemSpec.group()} 写进去无处可去；</li>
 *   <li>{@code CreativeModeTabs} 里<b>零处</b>遍历 {@code BuiltInRegistries.ITEM}，
 *       每个标签都是硬编码的 {@code accept(Items.OAK_LOG, ...)} 列表。</li>
 * </ul>
 *
 * <p><b>结论</b>：物品必须被某个标签的 {@code displayItems} 显式 {@code accept}
 * 才会出现在创造栏，哪怕它已经注册进物品注册表。
 * 搜索页（{@code SEARCH}）只是聚合所有其他标签，所以进了任一标签就能被搜到。
 *
 * <h2>为什么必须在注册窗口内注册</h2>
 *
 * <p>{@code CREATIVE_MODE_TAB} 是普通注册表，而
 * {@code BuiltInRegistries.bootStrap()} 末尾会 {@code freeze()} 全部注册表 ——
 * {@code bootStrap()} 正是平台 {@code closeRegistryWindow()} 调用的东西。
 * <b>标签注册必须发生在它之前</b>，否则注册会失败
 * （表现为「创造栏里什么都没有」，且报错不指向真因）。
 *
 * <p>上一版把标签注册排在游戏启动之后，那时注册表已冻结 —— 必然失败。
 *
 * <h2>为什么按 mod id 就够了</h2>
 *
 * <p>标签 id = modId 小写化，标题 key = {@code itemGroup.<modId>.<modId>}。
 * 只要 Mod 有 id 就有自己的标签，<b>无需任何额外配置</b>。
 */
public final class ModCreativeTabs {

    private static final Logger LOG = Logger.getLogger("Mili/ModCreativeTabs");

    /** 已创建的标签：tabId → tabId（幂等标记）。 */
    private static final Map<String, String> CREATED = new ConcurrentHashMap<>();

    private ModCreativeTabs() {
    }

    /**
     * 为一个 Mod 创建创造栏标签并填入它声明的全部方块。
     *
     * <p>必须在注册窗口内、{@code closeRegistryWindow()} 之前调用。
     *
     * @param modId    Mod ID —— 标签 id 由它推导，无需额外配置
     * @param blockIds 该 Mod 声明的方块路径（相对其命名空间，如 {@code crops/amaranth}）
     */
    public static void registerTab(String modId, java.util.List<String> blockIds) {
        if (modId == null || modId.isBlank() || blockIds == null || blockIds.isEmpty()) {
            return;
        }
        String tabId = sanitize(modId);
        if (tabId == null || CREATED.containsKey(tabId)) {
            return;
        }
        try {
            Object tab = buildTab(modId, tabId, blockIds);
            if (tab != null) {
                CREATED.put(tabId, tabId);
                LOG.info("Creative tab '" + tabId + "' created with "
                        + blockIds.size() + " block item(s) for mod '" + modId + "'");
            }
        } catch (RuntimeException e) {
            // 创造栏是便利功能，失败不该影响游戏 —— 方块仍可 /give 获得。
            LOG.log(Level.WARNING,
                    "Cannot create creative tab for mod '" + modId
                            + "'. Its blocks will still be obtainable via /give"
                            + " and the search tab.", e);
        }
    }

    private static Object buildTab(String modId, String tabId, java.util.List<String> blockIds) {
        try {
            Class<?> tabClass = Reflect.gameClass("net.minecraft.world.item.CreativeModeTab");
            Class<?> rowClass = Reflect.gameClass("net.minecraft.world.item.CreativeModeTab$Row");
            Class<?> componentClass = Reflect.gameClass("net.minecraft.network.chat.Component");
            Class<?> builderClass =
                    Reflect.gameClass("net.minecraft.world.item.CreativeModeTab$Builder");

            // 标题：itemGroup.<modId>.<tabId>，交给 lang 翻译
            Object title = componentClass
                    .getMethod("translatable", String.class)
                    .invoke(null, "itemGroup." + modId + "." + tabId);

            // 列位置：TOP 行从 8 起顺延，避开原版标签
            int column = 8 + (CREATED.size() % 7);

            Object builder = tabClass.getMethod("builder", rowClass, int.class)
                    .invoke(null, enumOf(rowClass, "TOP"), column);

            builderClass.getMethod("title", componentClass).invoke(builder, title);

            // 【不要调type()】它在 26.2 里是 **protected**（已查反编译源码第 187 行：
            //   protected Builder type(Type type)）
            // 而 Class.getMethod() 只返回 public 方法，直接调会抛
            // NoSuchMethodException —— 这正是上一版创造栏失败的原因。
            //
            // 它的默认值已经是 Type.CATEGORY，正是我们想要的，所以直接跳过。
            // 若某个版本改了默认值，才需要用 getDeclaredMethod + setAccessible 兜底。
            setTabTypeIfNeeded(builderClass, builder);

            // 图标：第一个方块的物品形式。
            // icon 的形参类型是 Supplier<ItemStack>（26.2 实测），不是 Supplier<Object>。
            //
            // 【为什么要用 Proxy 而不是 lambda】泛型擦除后
            // Supplier<Object> 与 Supplier<ItemStack> 是**同一个**运行时类型
            // (java.util.function.Supplier)，但 lambda 会生成实现 Supplier<Object>
            // 的实现类传给期望 Supplier<ItemStack> 的方法 ——
            // 报 IllegalArgumentException: argument type mismatch。
            // 用 Proxy 生成实现游戏侧 Supplier 的类，泛型擦除后类型天然匹配。
            //
            // 延迟解析：标签首次显示时才调用，那时物品注册表已完全就绪。
            builderClass.getMethod("icon", java.util.function.Supplier.class)
                    .invoke(builder, newIconSupplier(modId, blockIds.get(0)));

            // displayItems：把所有方块物品加进这个标签
            //
            // 【同 icon，必须用 Proxy 而非 lambda】
            // DisplayItemsGenerator 是双参函数式接口，形参类型是
            // (ItemDisplayParameters, Output)。传 BiConsumer<Object,Object>
            // 会在 invoke 时抛 IllegalArgumentException: argument type mismatch
            // —— javac 按声明类型检查，lambda 实现类与期望的接口类型不匹配。
            builderClass.getMethod("displayItems",
                            Reflect.gameClass(
                                    "net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator"))
                    .invoke(builder, newDisplayItemsGenerator(modId, blockIds));

            Object tab = builderClass.getMethod("build").invoke(builder);
            return registerIntoRegistry(modId, tabId, tab);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new BridgeMismatchException(
                    "Cannot build CreativeModeTab for mod '" + modId + "'."
                            + "\n  26.2 creative tabs are hard-coded lists — an item only"
                            + " shows up if some tab's displayItems accepts it."
                            + "\n  And registration must happen BEFORE"
                            + " BuiltInRegistries.bootStrap() freezes the registry."
                            + "\n  Real cause: " + e, e);
        }
    }

    /**
     * 仅当默认值不是 {@code CATEGORY} 时才显式设置标签类型。
     *
     * <p>26.2 的 {@code Builder.type(Type)} 是 <b>protected</b>，且默认值已是
     * {@code CATEGORY} —— 所以正常路径下这里什么都不做。
     *
     * <p>存在的意义是「防御性读取」：若某个版本改了默认值，或把它挪到别的类，
     * 也不该让整个标签创建失败 —— 退化成一个外观稍异的标签，远好过没有标签。
     */
    private static void setTabTypeIfNeeded(Class<?> builderClass, Object builder) {
        try {
            java.lang.reflect.Field typeField =
                    builderClass.getDeclaredField("type");
            typeField.setAccessible(true);
            Object current = typeField.get(builder);
            Class<?> typeClass = Reflect.gameClass(
                    "net.minecraft.world.item.CreativeModeTab$Type");
            if (current instanceof Enum<?> e
                    && e.name().equals("CATEGORY")) {
                return; // 默认值已正确，什么都不做
            }
            java.lang.reflect.Method type = builderClass.getDeclaredMethod(
                    "type", typeClass);
            type.setAccessible(true);
            type.invoke(builder, enumOf(typeClass, "CATEGORY"));
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            // 字段或方法改名了 —— 保持默认构建，不影响标签存在
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "Cannot verify creative tab type; using default", e);
        }
    }

    /** 注册进 BuiltInRegistries.CREATIVE_MODE_TAB。 */
    private static Object registerIntoRegistry(String modId, String tabId, Object tab)
            throws ReflectiveOperationException {
        Class<?> idClass = Reflect.gameClass("net.minecraft.resources.Identifier");
        Class<?> keyClass = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        Class<?> registryClass = Reflect.gameClass("net.minecraft.core.Registry");

        // 触碰注册表类前必须先置 bootstrap 标志（类初始化失败不可重试）
        org.loader.runtime.minecraft.RegistrationPhase.ensureRegistriesReadable();

        Object tabRegistry = Reflect.staticField(
                "net.minecraft.core.registries.BuiltInRegistries", "CREATIVE_MODE_TAB");
        if (tabRegistry == null) {
            throw new BridgeMismatchException(
                    "BuiltInRegistries.CREATIVE_MODE_TAB is null — version mismatch?");
        }
        Object registryKey = Reflect.staticField(
                "net.minecraft.core.registries.Registries", "CREATIVE_MODE_TAB");
        if (registryKey == null) {
            throw new BridgeMismatchException(
                    "Registries.CREATIVE_MODE_TAB is null — version mismatch?");
        }
        Object id = idClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, sanitize(modId), tabId);
        Object key = keyClass.getMethod("create", keyClass, idClass)
                .invoke(null, registryKey, id);

        Method register = registryClass.getMethod("register",
                registryClass, keyClass, Object.class);
        return register.invoke(tabRegistry, key, tab);
    }

    /** 把任意字符串规整成合法的资源路径段。 */
    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return s.isBlank() ? null : s;
    }

    /**
     * 把 {@code modId:path} 解析为 ItemStack 并加入标签输出。
     *
     * <p>方块的物品形式即「该 id 对应的 BlockItem」：26.2 的物品注册表为每个
     * Block 都提供了一个同名 {@code BlockItem}，按同一 id 查物品即可。
     */
    private static void addBlockItem(String modId, String path, Object output) {
        try {
            Object item = lookupItem(modId, path);
            if (item == null) {
                return;
            }
            Class<?> outputIface = Reflect.gameClass(
                    "net.minecraft.world.item.CreativeModeTab$Output");
            Class<?> visibilityClass = Reflect.gameClass(
                    "net.minecraft.world.item.CreativeModeTab$TabVisibility");

            // 【在接口上取方法，而不是实现类】
            // output 的运行时类是游戏为lambda 生成的合成类，
            // 对它 getMethod("accept", ...) 虽然能拿到，但合成类的可见性
            // 不受我们控制；接口上的方法签名是稳定的定义。
            // 另用 Output.accept(ItemLike, TabVisibility) 重载 ——
            // 它内部自己 new ItemStack，省一层手工构造。
            Method accept = outputIface.getMethod("accept",
                    Reflect.gameClass("net.minecraft.world.item.ItemLike"),
                    visibilityClass);
            accept.invoke(output, item,
                    enumOf(visibilityClass, "PARENT_AND_SEARCH_TABS"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "Cannot add " + modId + ":" + path
                    + " to creative tab", e);
        }
    }

    /** 延迟图标：取第一个方块的 ItemStack，取不到返回 null（游戏画默认图标）。 */
    private static Object blockItemStack(String modId, String path) {
        try {
            Object item = lookupItem(modId, path);
            return item == null ? null : newItemStack(item);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 受检异常必须一起 catch：只写 RuntimeException 会让它直接穿透
            // （本项目已在三处栽过这个坑）。
            return null;
        }
    }

    /** 按 {@code modId:path} 从物品注册表取 {@code Item}。 */
    private static Object lookupItem(String modId, String path)
            throws ReflectiveOperationException {
        Class<?> idClass = Reflect.gameClass("net.minecraft.resources.Identifier");
        Class<?> keyClass = Reflect.gameClass("net.minecraft.resources.ResourceKey");
        Class<?> registryClass = Reflect.gameClass("net.minecraft.core.Registry");

        Object itemRegistry = Reflect.staticField(
                "net.minecraft.core.registries.BuiltInRegistries", "ITEM");
        if (itemRegistry == null) {
            return null;
        }
        Object registryKey = Reflect.staticField(
                "net.minecraft.core.registries.Registries", "ITEM");
        if (registryKey == null) {
            return null;
        }
        Object id = idClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, modId, path);
        Object key = keyClass.getMethod("create", keyClass, idClass)
                .invoke(null, registryKey, id);
        return registryClass.getMethod("getValue", keyClass).invoke(itemRegistry, key);
    }

    /** 造出实现游戏 {@code DisplayItemsGenerator} 的代理。 */
    private static Object newDisplayItemsGenerator(String modId,
                                                   java.util.List<String> blockIds) {
        Class<?> genClass = Reflect.gameClass(
                "net.minecraft.world.item.CreativeModeTab$DisplayItemsGenerator");
        return java.lang.reflect.Proxy.newProxyInstance(
                Reflect.gameClassLoader(),
                new Class<?>[]{genClass},
                (proxy, method, args) -> {
                    if ("accept".equals(method.getName()) && args != null && args.length == 2) {
                        for (String path : blockIds) {
                            addBlockItem(modId, path, args[1]);
                        }
                        return null;
                    }
                    return switch (method.getName()) {
                        case "toString" -> "MiliCreativeTabItems(" + modId + ")";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args != null ? args[0] : null);
                        default -> null;
                    };
                });
    }

    /** 造出实现游戏 {@code Supplier} 的代理对象。 */
    private static Object newIconSupplier(String modId, String blockPath) {
        ClassLoader gameCl = Reflect.gameClassLoader();
        return java.lang.reflect.Proxy.newProxyInstance(
                gameCl,
                new Class<?>[]{Reflect.gameClass("java.util.function.Supplier")},
                new IconSupplier(modId, blockPath));
    }

    /**
     * 标签图标 supplier 的处理逻辑（实际对象由 Proxy 包装，见
     * {@link #newIconSupplier}）。
     *
     * <p><b>为什么必须用 Proxy</b>：{@code icon(Supplier<ItemStack>)} 的形参
     * 声明带泛型实参，泛型擦除后是 {@code Supplier}，但 javac 会为
     * {@code Supplier<Object>} 的 lambda 生成一个"实现 Supplier"却声明为
     * {@code Supplier<Object>} 的类；把它传给期望 {@code Supplier<ItemStack>}
     * 的方法时，编译器按声明类型检查，运行时擦除后又不匹配 ——
     * 抛 {@code IllegalArgumentException: argument type mismatch}。
     *
     * <p>Proxy 生成的类直接实现游戏侧的 {@code Supplier} 接口，
     * 擦除后类型天然一致。这与 {@code BlockPropertiesBuilder} 里
     * {@code lightLevel(ToIntFunction)} 用 Proxy 是同一个理由。
     */
    private static final class IconSupplier implements java.lang.reflect.InvocationHandler {

        private final String modId;
        private final String blockPath;

        IconSupplier(String modId, String blockPath) {
            this.modId = modId;
            this.blockPath = blockPath;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "get" -> blockItemStack(modId, blockPath);
                case "toString" -> "MiliCreativeTabIcon(" + modId + ":" + blockPath + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args != null ? args[0] : null);
                default -> null;
            };
        }
    }

    /**
     * {@code new ItemStack(ItemLike)}。
     *
     * <p>26.2 <b>没有</b> {@code ItemStack.getItemStack(Item)} 静态方法
     * （已用字节码核实）—— 只能走构造器，{@code Item} 实现了 {@code ItemLike}。
     */
    private static Object newItemStack(Object item) {
        Class<?> itemStackClass = Reflect.gameClass("net.minecraft.world.item.ItemStack");
        try {
            return itemStackClass.getConstructor(item.getClass()).newInstance(item);
        } catch (NoSuchMethodException e) {
            try {
                Class<?> itemClass = Reflect.gameClass("net.minecraft.world.item.Item");
                return itemStackClass.getConstructor(itemClass).newInstance(item);
            } catch (ReflectiveOperationException e2) {
                throw new BridgeMismatchException(
                        "ItemStack has no constructor accepting the game's Item type."
                                + "\n  Real constructors: "
                                + java.util.Arrays.toString(itemStackClass.getConstructors()), e2);
            }
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException("Cannot create ItemStack: " + e, e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumOf(Class<?> enumClass, String name) {
        Class<? extends Enum> e = (Class<? extends Enum>) enumClass.asSubclass(Enum.class);
        try {
            return Enum.valueOf(e, name);
        } catch (IllegalArgumentException ex) {
            Object[] cs = enumClass.getEnumConstants();
            if (cs == null || cs.length == 0) {
                throw new BridgeMismatchException(
                        "Enum " + enumClass.getSimpleName() + " has no constant '" + name
                                + "'; available: "
                                + java.util.Arrays.toString(enumClass.getEnumConstants()));
            }
            return cs[0];
        }
    }

    /** 诊断用：已创建的标签。 */
    public static Map<String, String> createdTabs() {
        return Map.copyOf(CREATED);
    }
}
