package org.loader.runtime.minecraft.resource;

import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 Mod JAR 里的 {@code assets/} 注入 Minecraft 的资源管理器。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>Mod 的方块/物品能注册进游戏，但它们的模型、贴图、翻译在
 * {@code assets/<namespace>/} 下。游戏只从自己的资源包体系里读这些目录 ——
 * 它<b>不会</b>去翻 Mod 的 ClassLoader。于是症状是：
 * <pre>
 *   Missing model for variant: 'Block{stardewvalley:crops/amaranth}'
 * </pre>
 * 方块存在、ID 正确，就是没有模型 —— 游戏中显示为缺失贴图的方块，
 * 物品名显示成裸 ID。
 *
 * <h2>为什么走 RepositorySource 而不是解压到磁盘</h2>
 *
 * <p>解压到 {@code game/resourcepacks/} 也能生效，但资源就与 JAR 分离了：
 * 换 Mod 版本后旧资源会残留，且很难判断"游戏里这份资源"对应哪个 Mod。
 * 走游戏原生的资源包抽象（{@code RepositorySource} → {@code Pack} →
 * {@code PackResources}）可以让资源随 JAR 走，一一对应。
 *
 * <h2>26.2 的真实结构（逐条从 test_client/26.2.jar 字节码读出，未凭记忆）</h2>
 * <pre>
 *   interface PackRepository$ResourcesSupplier {   ← 注意是 Pack 的【嵌套】接口
 *       PackResources openPrimary(PackLocationInfo);
 *       PackResources openFull(PackLocationInfo, Pack$Metadata);
 *   }
 *   class Pack {
 *       Pack(PackLocationInfo, ResourcesSupplier, Metadata, PackSelectionConfig);
 *   }
 *   interface PackResources {                       ← 13 个方法必须实现
 *       PackLocationInfo location();
 *       String packId();
 *       Optional&lt;KnownPack&gt; knownPack();
 *       IoSupplier getResource(String...);          // 路径分段
 *       IoSupplier getResource(PackType, Identifier);
 *       void listResources(PackType, String, String, ResourceOutput);
 *       Set&lt;String&gt; listResourceLocations(PackType, String, String);
 *       Set&lt;String&gt; listResourceStacks(PackType, String);
 *       &lt;T&gt; Optional&lt;T&gt; getMetadataSection(MetadataSectionType&lt;T&gt;);
 *   }
 * </pre>
 *
 * <p>{@code PackResources} 有 13 个方法且随版本变动，逐一反射实现太脆弱。
 * 因此这里用 {@link Proxy} 动态代理：<b>只处理真正会被调用的方法</b>
 * （资源读取、命名空间枚举、元数据），其余返回默认值。
 * 版本新增方法时代理不会崩，只是那个方法返回默认值。
 *
 * <p>代理类必须定义在<b>游戏 ClassLoader</b>里 —— 代理要实现游戏接口，
 * 而游戏接口在该 CL 下才可见。
 */
public final class ModResourcePacks {

    private static final Logger LOG = Logger.getLogger("Mili/ModResourcePacks");

    private static final String PACKS = "net.minecraft.server.packs.";
    private static final String REPO = PACKS + "repository.";

    /** 已注入的 Mod id → 是否成功，避免重复注入。 */
    private static final Set<String> injected = ConcurrentHashMap.newKeySet();

    private ModResourcePacks() {
    }

    /**
     * 把一个 Mod JAR 的 {@code assets/} 注册为资源包。
     *
     * <p>必须在游戏已启动、资源管理器可访问时调用（而不是 Mod 的
     * {@code initialize()}）—— 那时 {@code Minecraft} 实例与
     * {@code PackRepository} 才存在。
     *
     * @param modId      Mod ID（资源包 id 与日志标识）
     * @param modJar     Mod JAR 路径
     * @param minecraft  {@code net.minecraft.client.Minecraft} 实例
     */
    public static void inject(String modId, Path modJar, Object minecraft) {
        if (!injected.add(modId)) {
            return;
        }
        try {
            if (!Files.isRegularFile(modJar)) {
                LOG.warning("Mod JAR missing, cannot inject resources: " + modJar);
                return;
            }
            if (!hasAssets(modJar)) {
                LOG.info("Mod " + modId + " has no assets/ in its JAR — nothing to inject");
                return;
            }
            Object repository = repositoryOf(minecraft);
            if (repository == null) {
                throw new BridgeMismatchException(
                        "Cannot reach Minecraft.resourcePackRepository."
                                + "\n  Resource injection needs the client instance;"
                                + " it must run after the game object exists, not during"
                                + " Mod.initialize().");
            }
            addRepositorySource(repository, modId, modJar);
            // 让游戏重新加载资源：PackRepository 变更后必须显式 reload，
            // 否则新资源包不会进入 ReloadableResourceManager。
            // 【必须先等稳态】启动首轮 reload 还挂在前台时请求 reload 会崩游戏，
            // 见 awaitReloadSafePoint 的说明。
            awaitReloadSafePoint(minecraft);
            reloadResources(minecraft);

            // 【如实报告，不做过度承诺】
            // 上面两步只保证"源已注册"和"已请求重载"，
            // 并不保证包真的被打开 —— 那要等游戏在 reload 里
            // discoverAvailable → rebuildSelected → openAllSelected 走完。
            // 上一版在这里打 "Injected resources ..." 让人以为成功了，
            // 而实际上 newPack 可能返回 null（连一个包都没产出）。
            // 真正的判据是下一次 loadPacks 里打出 "produced 1 pack"。
            LOG.info("Registered resource source for mod '" + modId
                    + "' and requested a resource reload. Confirm it took effect by"
                    + " looking for \"loadPacks produced 1 pack for '" + modId + "'\""
                    + " and the ABSENCE of any Missing model warnings.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 资源注入失败不该阻止游戏启动 —— 游戏能跑，只是 Mod 缺贴图。
            //
            // 【必须同时 catch ReflectiveOperationException】它是受检异常，
            // 不属于 RuntimeException —— 只写 catch (RuntimeException) 会让它
            // 直接穿透，恰好违背这里"失败不阻断启动"的意图。
            LOG.log(Level.WARNING,
                    "Resource injection failed for mod '" + modId + "'. The game will run"
                            + " but this mod's models/textures will be missing.", e);
        }
    }

    /** JAR 里是否含 {@code assets/} 目录（避免为纯代码 Mod 做无谓工作）。 */
    private static boolean hasAssets(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(e -> {
                String n = e.getName();
                // Mod 通常把资源放在 assets/ 下；也接受直接放在根的
                // blockstates/ models/ textures/ lang/（老式布局）
                return n.startsWith("assets/") || n.startsWith("blockstates/")
                        || n.startsWith("models/") || n.startsWith("textures/")
                        || n.startsWith("lang/");
            });
        } catch (Exception e) {
            return false;
        }
    }

    // ── PackRepository 接入 ──────────────────────────────────────────────

    private static Object repositoryOf(Object minecraft) {
        // 字段名在 26.2 是 resourcePackRepository
        return Reflect.instanceField(minecraft, "net.minecraft.client.Minecraft",
                "resourcePackRepository");
    }

    /**
     * 给 {@code PackRepository} 追加一个 {@code RepositorySource}。
     *
     * <p>{@code PackRepository} 没有公开的 addSource，只有构造器接受
     * 变长 {@code RepositorySource}。
     *
     * <p><b>关键：那个集合是不可变的，且字段是 final</b>。
     * 26.2 构造器里是：
     * <pre>
     *   this.sources = ImmutableSet.copyOf((Object[]) sources);
     * </pre>
     * 所以 {@code add()} 必然抛 {@code UnsupportedOperationException}
     * （Guava 的 ImmutableCollection.add 直接 throw），
     * 而字段又是 {@code final}，不能改内容。
     *
     * <p><b>正确做法</b>：造一个「原有全部 + 新的」的可变 Set，
     * 再用 {@code Field.set} 整个替换掉final 字段。
     * 这样不必与不可变集合较劲，也不依赖任何未公开的写入口。
     */
    private static void addRepositorySource(Object repository, String modId, Path modJar)
            throws ReflectiveOperationException {
        Class<?> sourceIface = Reflect.gameClass(REPO + "RepositorySource");
        Object source = newRepositorySource(sourceIface, modId, modJar);

        java.lang.reflect.Field sourcesField = findSourcesField(repository.getClass());
        if (sourcesField == null) {
            throw new BridgeMismatchException(
                    "PackRepository has no 'sources' collection field."
                            + "\n  Looked for a field named 'sources' of type List or Set;"
                            + " real fields: " + describeFields(repository.getClass())
                            + "\n  This is a binding-layer bug, not a mod bug.");
        }
        sourcesField.setAccessible(true);
        Object original = sourcesField.get(repository);

        // 造新集合：原有全部 + 新的
        java.util.Set<Object> merged = new java.util.LinkedHashSet<>();
        if (original instanceof java.util.Collection<?> coll) {
            merged.addAll(coll);
        }
        merged.add(source);

        // 整体替换 final 字段。final 只约束「正常赋值」，反射写入仍可生效
        // —— 这是 Java 反射的既定行为，也是此路可行的原因。
        sourcesField.set(repository, merged);
        LOG.info("Added RepositorySource for '" + modId
                + "' to PackRepository (replaced final field, "
                + (original == null ? 0 : merged.size() - 1) + " existing source(s))");
    }

    /** 列出实际字段名与类型，用于诊断信息。 */
    private static String describeFields(Class<?> type) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = type; c != null && sb.length() < 400; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName());
            }
        }
        return sb.length() == 0 ? "<none>" : sb.toString();
    }

    /**
     * 找 {@code sources} 字段。接受 List 或 Set ——
     * 26.2 用的是 Set，但保持两种都能处理，避免版本差异再成一片。
     */
    private static java.lang.reflect.Field findSourcesField(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!f.getName().equals("sources")) {
                    continue;
                }
                Class<?> ft = f.getType();
                if (java.util.Set.class.isAssignableFrom(ft)
                        || List.class.isAssignableFrom(ft)) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * 构造一个 {@code RepositorySource}，它产出一个包住该 Mod JAR
     * {@code assets/} 的 {@code Pack}。
     */
    private static Object newRepositorySource(Class<?> sourceIface, String modId, Path modJar)
            throws ReflectiveOperationException {
        ClassLoader gameCl = Reflect.gameClassLoader();

        // RepositorySource 是游戏接口 → 代理必须用游戏 CL 定义
        return Proxy.newProxyInstance(gameCl, new Class<?>[]{sourceIface},
                (proxy, method, args) -> {
                    if ("loadPacks".equals(method.getName()) && args != null && args.length == 1) {
                        @SuppressWarnings("unchecked")
                        Consumer<Object> consumer = (Consumer<Object>) args[0];
                        Object pack = newPack(modId, modJar);
                        if (pack != null) {
                            consumer.accept(pack);
                            LOG.info("loadPacks produced 1 pack for '" + modId + "'");
                        } else {
                            // 绝不能静默：newPack 失败时若什么都不做，
                            // 现象是"资源源已注册但资源永远不加载"，
                            // 从日志上看不出任何异常。
                            LOG.severe("loadPacks produced NO pack for '" + modId
                                    + "' — this mod's resources will NOT be visible."
                                    + " See the exception above for the real cause.");
                        }
                        return null;
                    }
                    return defaultValue(method);
                });
    }

    /**
     * 构造 {@code Pack}。
     *
     * <p><b>26.2 真实构造器</b>（已查反编译源码第 45 行）：
     * <pre>
     *   public Pack(PackLocationInfo location,
     *               ResourcesSupplier resources,
     *               Metadata metadata,          ← 不是 PackType！
     *               PackSelectionConfig selectionConfig)
     * </pre>
     *
     * <p>上一版把第 3 参填成了 {@code PackType}，于是
     * {@code getDeclaredConstructor} 抛 NoSuchMethodException → 被 catch吞掉 →
     * {@code loadPacks} 收到 null 而什么都不产出。日志里"3 existing source(s)"
     * 正是在说"源加进去了，但一个包都没产出"。
     *
     * <p>两个必须同时满足的条件（否则包形同虚设）：
     * <ol>
     *   <li><b>selectionConfig.required = true</b> ——
     *       {@code rebuildSelected} 只把 {@code isRequired()} 的包放进 selected，
     *       而 {@code openAllSelected()} 只遍历 selected。required=false 的包
     *       即使被 {@code discoverAvailable} 发现，也<b>永远不会被打开</b>；</li>
     *   <li><b>metadata 非 null</b> —— {@code open()} 走
     *       {@code openFull(location, metadata)}，且 {@code getCompatibility()}
     *       读 {@code metadata.compatibility()}；null 会直接 NPE。</li>
     * </ol>
     */
    private static Object newPack(String modId, Path modJar) {
        try {
            Class<?> packClass = Reflect.gameClass(REPO + "Pack");
            Class<?> supplierIface = Reflect.gameClass(REPO + "Pack$ResourcesSupplier");
            Class<?> selectionClass = Reflect.gameClass(PACKS + "PackSelectionConfig");
            Class<?> locationClass = Reflect.gameClass(PACKS + "PackLocationInfo");
            Class<?> metadataClass = Reflect.gameClass(REPO + "Pack$Metadata");

            Object supplier = newResourcesSupplier(supplierIface, modId, modJar);
            Object location = newLocationInfo(locationClass, modId, modJar);
            Object metadata = newPackMetadata(metadataClass, modId);
            Object selection = newSelectionConfig(selectionClass);

            java.lang.reflect.Constructor<?> ctor =
                    packClass.getDeclaredConstructor(locationClass, supplierIface,
                            metadataClass, selectionClass);
            ctor.setAccessible(true);
            Object pack = ctor.newInstance(location, supplier, metadata, selection);
            LOG.info("Built resource pack '" + idOf(modId) + "' (required=true)");
            return pack;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Cannot build resource Pack for mod '" + modId
                            + "'. Its models/textures will be missing."
                            + "\n  Real constructor: " + describePackConstructor(), e);
            return null;
        }
    }

    /** 把 Pack 真实构造器打进日志，避免下次再靠猜。 */
    private static String describePackConstructor() {
        try {
            return java.util.Arrays.toString(Reflect.gameClass(REPO + "Pack")
                    .getDeclaredConstructors());
        } catch (RuntimeException e) {
            return "<unavailable>";
        }
    }

    /**
     * 构造 {@code Pack.Metadata}。
     *
     * <p>真实定义（第 159 行）：
     * {@code record Metadata(Component description, PackCompatibility compatibility,
     * FeatureFlagSet requestedFeatures, List<String> overlays)}
     *
     * <p>compatibility 取枚举常量 {@code COMPATIBLE}（Mod 资源不需要版本协商），
     * requestedFeatures 取 {@code FeatureFlagSet.of()}（不请求任何特性旗标）。
     */
    private static Object newPackMetadata(Class<?> metadataClass, String modId)
            throws ReflectiveOperationException {
        Class<?> componentClass = Reflect.gameClass("net.minecraft.network.chat.Component");
        Class<?> compatibilityClass =
                Reflect.gameClass(REPO + "PackCompatibility");
        Class<?> flagSetClass = Reflect.gameClass("net.minecraft.world.flag.FeatureFlagSet");

        Object description = componentClass.getMethod("translatable", String.class)
                .invoke(null, "resourcePack." + idOf(modId) + ".name");
        Object compatibility = compatibilityClass.getField("COMPATIBLE").get(null);
        Object flags = flagSetClass.getMethod("of").invoke(null);

        java.lang.reflect.Constructor<?> ctor = metadataClass.getDeclaredConstructor(
                componentClass, compatibilityClass, flagSetClass, java.util.List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(description, compatibility, flags, java.util.List.of());
    }

    private static Object newLocationInfo(Class<?> locationClass, String modId, Path jar)
            throws ReflectiveOperationException {
        // 26.2 真实定义（已查反编译源码第 18 行）：
        //   record PackLocationInfo(String id, Component title, PackSource source,
        //                          Optional<KnownPack> knownPackInfo)
        //
        // 上一版只匹配 (String, String) 与 (String, String, x) 两种形态，
        // 于是全部落空并抛 "no (String, String[, PackSource]) constructor"。
        // 第二参是**显示标题 Component**，不是 String。
        //
        // 这里不再按位置猜参数，而是【按类型逐个填】：
        // String → id，Component → 可翻译标题，其余给"安全的缺省值"。
        for (java.lang.reflect.Constructor<?> c : locationClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 0) {
                continue;
            }
            Object[] args = new Object[p.length];
            boolean fillable = true;
            for (int i = 0; i < p.length; i++) {
                Class<?> t = p[i];
                if (t == String.class) {
                    args[i] = (i == 0) ? idOf(modId) : jar.getFileName().toString();
                } else if (isComponentType(t)) {
                    args[i] = translatableOrLiteral("resourcePack." + idOf(modId) + ".name");
                } else if (t == Optional.class) {
                    args[i] = Optional.empty();
                } else if (t.isEnum()) {
                    args[i] = firstEnumConstant(t);
                } else if (t == boolean.class) {
                    args[i] = Boolean.FALSE;
                } else {
                    // 未知类型：先尝试「该类型的公共静态实例」。
                    //
                    // 真实事故（26.2）：PackSource 从枚举变成了接口 ——
                    // isEnum() 为 false，上面的分支全部落空，于是整个
                    // PackLocationInfo 构造不出来，mod 资源包从未进入
                    // 游戏（全部方块 Missing model）。接口版的实例放在
                    // public static 字段里（DEFAULT/BUILT_IN/...），
                    // 与枚举常量在运行期的用法完全等价。
                    Object constant = firstPublicStaticInstance(t);
                    if (constant == null) {
                        fillable = false;
                        break;
                    }
                    args[i] = constant;
                }
            }
            if (fillable) {
                c.setAccessible(true);
                return c.newInstance(args);
            }
        }
        throw new BridgeMismatchException(
                "PackLocationInfo has no constructor whose parameters this binding"
                        + " knows how to fill."
                        + "\n  Real constructors: " + java.util.Arrays.toString(
                        locationClass.getDeclaredConstructors()));
    }

    /** 是否是 Component 类型（含其实现类）。 */
    private static boolean isComponentType(Class<?> type) {
        try {
            return Reflect.gameClass("net.minecraft.network.chat.Component")
                    .isAssignableFrom(type);
        } catch (RuntimeException e) {
            return "net.minecraft.network.chat.Component".equals(type.getName());
        }
    }

    /** {@code Component.literal(String)} —— 拿不到就返回 null 由调用方兜底。 */
    private static Object translatableOrLiteral(String text) {
        try {
            Class<?> component = Reflect.gameClass("net.minecraft.network.chat.Component");
            return component.getMethod("literal", String.class).invoke(null, text);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object firstEnumConstant(Class<?> enumClass) {
        Object[] cs = enumClass.getEnumConstants();
        return cs != null && cs.length > 0 ? cs[0] : null;
    }

    /**
     * 取「非枚举类型」的公共静态实例 —— 优先 {@code BUILT_IN}/{@code DEFAULT}。
     *
     * <p>26.2 的 {@code PackSource} 是接口，实例全在 public static 字段里。
     * 取不到返回 null，调用方按「填不了」处理。
     */
    private static Object firstPublicStaticInstance(Class<?> type) {
        java.lang.reflect.Field best = null;
        java.lang.reflect.Field fallback = null;
        for (java.lang.reflect.Field f : type.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!type.isAssignableFrom(f.getType())) {
                continue;
            }
            String name = f.getName();
            if (name.equals("BUILT_IN") || name.equals("DEFAULT")) {
                best = f;
                break;
            }
            if (fallback == null) {
                fallback = f;
            }
        }
        java.lang.reflect.Field chosen = best != null ? best : fallback;
        if (chosen == null) {
            return null;
        }
        try {
            return chosen.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object newSelectionConfig(Class<?> selectionClass)
            throws ReflectiveOperationException {
        // 26.2 真实定义（已查反编译源码第 8 行）：
        //   record PackSelectionConfig(boolean required, Pack.Position defaultPosition,
        //                              boolean fixedPosition)
        // 上一版按 1~2 参匹配，全部落空。
        for (java.lang.reflect.Constructor<?> c : selectionClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 0) {
                continue;
            }
            Object[] args = new Object[p.length];
            boolean fillable = true;
            for (int i = 0; i < p.length; i++) {
                Class<?> t = p[i];
                if (t == boolean.class) {
                    // 【required 必须是 true —— 这是最致命的一处】
                    //
                    // PackRepository.rebuildSelected（第 96 行）：
                    //   if (!pack.isRequired() || selectedAndPresent.contains(pack)) continue;
                    // 只有 required 的包才会被自动插入 selected；
                    // 而 openAllSelected() 只遍历 selected。
                    //
                    // 也就是说：required=false 的包即使被 discoverAvailable 发现、
                    // 即使文件都在，也<b>永远不会被打开</b> —— 游戏完全看不到它。
                    // 这类"资源全对但就是不生效"的静默失败，极难从日志发现。
                    //
                    // 第二个 boolean 是 fixedPosition（界面里是否锁定位置），
                    // 那个保持 false 无妨。
                    args[i] = Boolean.TRUE;
                } else if (t.isEnum()) {
                    args[i] = enumConstant(t, "TOP");
                } else {
                    fillable = false;
                    break;
                }
            }
            if (fillable) {
                c.setAccessible(true);
                return c.newInstance(args);
            }
        }
        throw new BridgeMismatchException(
                "PackSelectionConfig has no constructor whose parameters this binding"
                        + " knows how to fill. Real: "
                        + java.util.Arrays.toString(selectionClass.getDeclaredConstructors()));
    }

    /** 取枚举常量，兼容 enum 与 record-like holder 两种形态。 */
    private static Object enumConstant(Class<?> enumClass, String name) {
        try {
            return Enum.valueOf(asEnum(enumClass), name);
        } catch (IllegalArgumentException e) {
            Object[] constants = enumClass.getEnumConstants();
            return constants != null && constants.length > 0 ? constants[0] : null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Class<? extends Enum> asEnum(Class<?> c) {
        return (Class<? extends Enum>) c.asSubclass(Enum.class);
    }

    private static String idOf(String modId) {
        // 资源包 id 只能是 [a-z0-9._-]
        return modId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    // ── ResourcesSupplier / PackResources ─────────────────────────────────

    private static Object newResourcesSupplier(Class<?> supplierIface, String modId, Path jar)
            throws ReflectiveOperationException {
        ClassLoader gameCl = Reflect.gameClassLoader();
        return Proxy.newProxyInstance(gameCl, new Class<?>[]{supplierIface},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "openPrimary", "openFull" -> {
                            return newPackResources(args[0], modId, jar);
                        }
                        default -> {
                            return defaultValue(method);
                        }
                    }
                });
    }

    /**
     * 实现 {@code PackResources}：从 Mod JAR 里读 {@code assets/}。
     *
     * <p>只有三类方法会真的被游戏调用：
     * <ul>
     *   <li>{@code getResource} —— 读单个资源（模型/贴图/语言）</li>
     *   <li>{@code listResourceLocations} —— 枚举某目录下的资源</li>
     *   <li>{@code packId} / {@code location} —— 标识与诊断</li>
     * </ul>
     * 其余（sound、metadata、knownPack…）返回空值：Mod 资源包里没有这些，
     * 返回空比抛异常更安全。
     */
    private static Object newPackResources(Object location, String modId, Path jar)
            throws ReflectiveOperationException {
        ClassLoader gameCl = Reflect.gameClassLoader();
        Class<?> resIface = Reflect.gameClass(PACKS + "PackResources");
        Class<?> packTypeClass = Reflect.gameClass(PACKS + "PackType");
        Class<?> idClass = Reflect.gameClass("net.minecraft.resources.Identifier");
        Class<?> outputIface = Reflect.gameClass(PACKS + "PackResources$ResourceOutput");

        return Proxy.newProxyInstance(gameCl, new Class<?>[]{resIface},
                (proxy, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "location":
                            return location;

                        case "packId":
                            return idOf(modId);

                        case "getResource": {
                            String entry = resolveEntry(args, packTypeClass, idClass);
                            if (entry == null) {
                                return null;
                            }
                            byte[] data = read(jar, entry);
                            if (data == null) {
                                return null;
                            }
                            return ioSupplier(entry, data);
                        }

                        case "listResourceLocations": {
                            // (PackType, String namespace, String path)
                            if (args == null || args.length < 3) {
                                return Set.of();
                            }
                            String ns = String.valueOf(args[1]);
                            String path = String.valueOf(args[2]);
                            Set<String> out = new LinkedHashSet<>();
                            String prefix = "assets/" + ns + "/"
                                    + (path.isEmpty() ? "" : path);
                            for (String e : listEntries(jar, prefix)) {
                                out.add(e.substring(prefix.length()));
                            }
                            return out;
                        }

                        case "listResources": {
                            // (PackType, String ns, String path, ResourceOutput output)
                            if (args == null || args.length < 4) {
                                return null;
                            }
                            String ns = String.valueOf(args[1]);
                            String path = String.valueOf(args[2]);
                            Object output = args[3];
                            String prefix = "assets/" + ns + "/"
                                    + (path.isEmpty() ? "" : path);
                            // 26.2 的 ResourceOutput extends
                            // BiConsumer<Identifier, IoSupplier<InputStream>>——
                            // 第二参是游戏侧 IoSupplier，不是
                            // java.util.function.Supplier（用后者查 getMethod
                            // 直接 NoSuchMethodException，整个资源 reload 被
                            // 原版回滚，b1240c3 实测）。
                            try {
                                Method accept = outputIface.getMethod("accept",
                                        idClass, ioSupplierClass());
                                for (String entryName : listEntries(jar, prefix)) {
                                    String rel = entryName.substring(prefix.length());
                                    Object id = identifier(idClass, ns, rel);
                                    if (id == null) {
                                        continue;
                                    }
                                    // 复制成 final 局部量：lambda 捕获循环变量在
                                    // Java 21+ 的 effectively-final 规则下会编译
                                    // 失败，且值会在循环推进后变化 —— 每次迭代
                                    // 必须绑定自己的值。
                                    final String jarEntry = entryName;
                                    accept.invoke(output, id, lazyIoSupplier(jar, jarEntry));
                                }
                            } catch (ReflectiveOperationException | RuntimeException e) {
                                // 枚举失败只意味着这些资源不被收录，
                                // 绝不能把异常抛出代理 —— 代理抛出的任何
                                // 异常都会包成 UndeclaredThrowableException
                                // 炸掉整个 reload。
                                LOG.log(Level.WARNING,
                                        "listResources failed for " + prefix, e);
                            }
                            return null;
                        }

                        case "getNamespaces": {
                            // (PackType) → 该资源目录下的命名空间集合。
                            // 【不可返回 null】接口契约 Set<String> 非 @Nullable，
                            // 26.2 的 MultiPackResourceManager 对返回值直接 .stream()，
                            // 返回 null = Initializing game 崩溃（8996796 实测）。
                            String dir = packDirectory(args, packTypeClass);
                            return Set.copyOf(listNamespaces(jar, dir));
                        }

                        case "toString":
                            return "MiliModResources(" + modId + ")";

                        case "hashCode":
                            return System.identityHashCode(proxy);

                        case "equals":
                            return proxy == (args != null ? args[0] : null);

                        default:
                            return defaultValue(method);
                    }
                });
    }

    /**
     * 把 getResource 的两种重载都归一成 JAR 内条目路径。
     *
     * <p>两种签名（26.2 实际存在）：
     * <ul>
     *   <li>{@code getResource(PackType, Identifier)}</li>
     *   <li>{@code getResource(String... pathSegments)} —— 路径分段</li>
     * </ul>
     * 老版本只有第一种，所以两种都要认。
     */
    private static String resolveEntry(Object[] args, Class<?> packTypeClass, Class<?> idClass)
            throws ReflectiveOperationException {
        if (args == null || args.length == 0) {
            return null;
        }
        Object first = args[0];
        if (packTypeClass.isInstance(first) && args.length == 2) {
            // (PackType, Identifier) → assets/<ns>/<path>
            Object id = args[1];
            Method ns = id.getClass().getMethod("getNamespace");
            Method path = id.getClass().getMethod("getPath");
            return "assets/" + ns.invoke(id) + "/" + path.invoke(id);
        }
        if (first instanceof String[] segments) {
            // (String...) → 已是资源路径分段，如 ["stardewvalley","models","x.json"]
            StringBuilder sb = new StringBuilder("assets");
            for (String s : segments) {
                sb.append('/').append(s);
            }
            return sb.toString();
        }
        if (first instanceof String s) {
            return "assets/" + s;
        }
        return null;
    }

    /** 游戏侧 {@code IoSupplier<InputStream>} 的 Class（多处要用，统一解析）。 */
    private static Class<?> ioSupplierClass() {
        try {
            return Reflect.gameClass(PACKS + "resources.IoSupplier");
        } catch (RuntimeException e) {
            throw new BridgeMismatchException(
                    "IoSupplier not found; cannot adapt mod resources to this version.", e);
        }
    }

    /**
     * 惰性读取的 {@code IoSupplier<InputStream>}：get() 时才从 Mod JAR 读条目。
     *
     * <p>listResources 一次枚举几十上百个条目，若急切读取会把整包贴图
     * 全部载入内存；游戏实际只会 get() 其中被收录进图集的那部分。
     */
    private static Object lazyIoSupplier(Path jar, String entry) {
        ClassLoader gameCl = Reflect.gameClassLoader();
        return Proxy.newProxyInstance(gameCl, new Class<?>[]{ioSupplierClass()},
                (p, m, a) -> {
                    if ("get".equals(m.getName())) {
                        return new java.io.ByteArrayInputStream(readOrEmpty(jar, entry));
                    }
                    return defaultValue(m);
                });
    }

    private static Object ioSupplier(String entry, byte[] data)
            throws ReflectiveOperationException {
        // IoSupplier<InputStream> 是函数式接口：get() 抛 IOException
        Class<?> ioSupplier = Reflect.gameClass(PACKS + "resources.IoSupplier");
        ClassLoader gameCl = Reflect.gameClassLoader();
        return Proxy.newProxyInstance(gameCl, new Class<?>[]{ioSupplier},
                (p, m, a) -> {
                    if ("get".equals(m.getName())) {
                        return new java.io.ByteArrayInputStream(data);
                    }
                    return defaultValue(m);
                });
    }

    /**
     * PackType 参数 → 资源根目录（assets / data）。
     * 反射调 {@code getDirectory()}，取不到退回 {@code assets}（客户端场景）。
     */
    private static String packDirectory(Object[] args, Class<?> packTypeClass) {
        if (args != null && args.length > 0 && packTypeClass.isInstance(args[0])) {
            try {
                Method d = packTypeClass.getMethod("getDirectory");
                Object v = d.invoke(args[0]);
                if (v != null) {
                    return String.valueOf(v);
                }
            } catch (ReflectiveOperationException ignored) {
                // 26.2 有 getDirectory()；真缺失时退回客户端目录
            }
        }
        return "assets";
    }

    /** 列出 JAR 内 {@code <dir>/<ns>/...} 顶层命名空间集合（不存在返回空集）。 */
    private static Set<String> listNamespaces(Path jar, String dir) {
        Set<String> out = new LinkedHashSet<>();
        String prefix = dir + "/";
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String n = entries.nextElement().getName();
                if (!n.startsWith(prefix) || n.length() <= prefix.length()) {
                    continue;
                }
                String rest = n.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    out.add(rest.substring(0, slash));
                }
            }
        } catch (Exception ignored) {
            // jar 读不了 → 空集：命名空间缺了只是资源不被枚举，不能崩
        }
        return out;
    }

    /**
     * 构造游戏侧的 {@code Identifier}（运行期才存在，编译期不能引用）。
     */
    private static Object identifier(Class<?> idClass, String ns, String path) {
        try {
            Method fromNs = idClass.getMethod("fromNamespaceAndPath", String.class, String.class);
            return fromNs.invoke(null, ns, path);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** 读JAR 内条目；不存在返回 null。 */
    private static byte[] read(Path jar, String entry) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readOrEmpty(Path jar, String entry) {
        byte[] d = read(jar, entry);
        return d == null ? new byte[0] : d;
    }

    /** 列出 JAR 内某前缀下的全部条目。 */
    private static List<String> listEntries(Path jar, String prefix) {
        List<String> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                if (n.startsWith(prefix) && n.length() > prefix.length()) {
                    out.add(n);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Cannot list " + prefix + " in " + jar, e);
        }
        return out;
    }

    /**
     * 等待游戏到达可安全触发资源 reload 的稳态。
     *
     * <p><b>为什么必须等</b>：启动时 {@code Minecraft.<init>} 的首轮 reload 还挂着
     * LoadingOverlay，此时从别的线程调 {@code reloadResourcePacks()} 会命中原版
     * pending 分支（日志 "Reload already ongoing, replacing"）——请求被挂到
     * pendingReload 上不执行，原版 tick 在 overlay 一消失就立刻补发：首轮 reload
     * 负责初始化的贴图被中途作废，下一帧 extract 渲染状态时直接崩
     * {@code IllegalStateException: Texture view does not exist}（b1240c3 实测）。
     * 等 overlay 关闭且 pendingReload 清空后再触发，等价于标题界面按 F3+T
     * 的原版支持路径。
     */
    private static void awaitReloadSafePoint(Object minecraft) {
        if (!Reflect.hasGameClass("net.minecraft.client.gui.screens.LoadingOverlay")) {
            return; // 该版本没有这个类 → 不阻塞，按旧路径走
        }
        final Class<?> loadingOverlay = Reflect.gameClass("net.minecraft.client.gui.screens.LoadingOverlay");
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (reloadSafeNow(minecraft, loadingOverlay)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOG.warning("Resource reload safe point not reached in 60s — requesting reload anyway;"
                + " if the game crashes with 'Texture view does not exist', this is why.");
    }

    /** 现在是否没有进行中的 reload（pendingReload 为空且前台不是 LoadingOverlay）。 */
    private static boolean reloadSafeNow(Object minecraft, Class<?> loadingOverlay) {
        try {
            Object pending = Reflect.instanceField(
                    minecraft, "net.minecraft.client.Minecraft", "pendingReload");
            if (pending != null) {
                return false;
            }
            Object gui = Reflect.instanceField(minecraft, "net.minecraft.client.Minecraft", "gui");
            if (gui == null) {
                return true;
            }
            Object overlay = Reflect.method("net.minecraft.client.gui.Gui", "overlay").invoke(gui);
            return overlay == null || !loadingOverlay.isInstance(overlay);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 不认识这个版本的结构 → 不阻塞，尽力而为
            return true;
        }
    }

    /** 触发游戏重新加载资源包。 */
    private static void reloadResources(Object minecraft) {
        try {
            Method reload = minecraft.getClass().getMethod("reloadResourcePacks");
            Object future = reload.invoke(minecraft);
            LOG.fine("reloadResourcePacks() -> " + future);
        } catch (NoSuchMethodException e) {
            throw new BridgeMismatchException(
                    "Minecraft.reloadResourcePacks() not found."
                            + "\n  Without it, injected mod resources would not take effect"
                            + " until the next game restart.", e);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Calling Minecraft.reloadResourcePacks() failed.", e);
        }
    }

    /** 方法返回类型的默认值（代理必须返回合法值，不能是 null 对基本类型）。 */
    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive()) {
            if (java.util.concurrent.Callable.class.isAssignableFrom(r)) {
                return null;
            }
            if (r == Optional.class) {
                return Optional.empty();
            }
            // 【集合/Map/数组一律空实例，不返回 null】这些返回类型在游戏侧
            // 常被直接 .stream()/.size()/迭代（如 PackResources.getNamespaces
            // 返回 null 直接 NPE 崩启动）。空集合语义=「没有」，null=「崩溃」。
            if (java.util.Collection.class.isAssignableFrom(r)) {
                return java.util.Collections.emptyList();
            }
            if (java.util.Map.class.isAssignableFrom(r)) {
                return java.util.Collections.emptyMap();
            }
            if (r.isArray()) {
                return java.lang.reflect.Array.newInstance(r.getComponentType(), 0);
            }
            return null;
        }
        if (r == boolean.class) {
            return Boolean.FALSE;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == char.class) {
            return (char) 0;
        }
        return null;
    }

    /** 诊断用：已注入的 Mod 列表。 */
    public static Set<String> injectedMods() {
        return Set.copyOf(injected);
    }

    /** 供诊断：读一个 Mod 资源包里的文件内容（测试与排查用）。 */
    public static byte[] readModResource(Path jar, String namespace, String path) {
        return read(jar, "assets/" + namespace + "/" + path);
    }
}
