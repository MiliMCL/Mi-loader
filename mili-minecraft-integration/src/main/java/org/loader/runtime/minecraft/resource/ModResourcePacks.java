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
 *       Pack(PackLocationInfo, ResourcesSupplier, PackType, PackSelectionConfig);
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
            reloadResources(minecraft);
            LOG.info("Injected resources for mod '" + modId + "' from " + modJar.getFileName());
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
                        }
                        return null;
                    }
                    return defaultValue(method);
                });
    }

    /**
     * 构造 {@code Pack}：优先走游戏自己的
     * {@code Pack.readMetaAndCreate(...)}，让它解析 pack.mcmeta；
     * 解析失败时退回到直接构造一个最小可用包。
     */
    private static Object newPack(String modId, Path modJar) {
        try {
            Class<?> packClass = Reflect.gameClass(REPO + "Pack");
            Class<?> supplierIface = Reflect.gameClass(REPO + "Pack$ResourcesSupplier");
            Class<?> packTypeClass = Reflect.gameClass(PACKS + "PackType");
            Class<?> selectionClass = Reflect.gameClass(PACKS + "PackSelectionConfig");
            Class<?> locationClass = Reflect.gameClass(PACKS + "PackLocationInfo");

            Object supplier = newResourcesSupplier(supplierIface, modId, modJar);
            Object clientResources = packTypeClass.getField("CLIENT_RESOURCES").get(null);

            Object location = newLocationInfo(locationClass, modId, modJar);

            // PackSelectionConfig 是 record，构造器签名需现场查
            Object selection = newSelectionConfig(selectionClass);

            // 用 getDeclaredConstructor + setAccessible：Pack 的 4 参构造器
            // 不是 public（字节码里它是包级/受保护的），getConstructor 会
            // 抛 NoSuchMethodException 而那与「版本不匹配」无关，容易误导。
            java.lang.reflect.Constructor<?> ctor =
                    packClass.getDeclaredConstructor(locationClass, supplierIface,
                            packTypeClass, selectionClass);
            ctor.setAccessible(true);
            return ctor.newInstance(location, supplier, clientResources, selection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.WARNING,
                    "Cannot build resource Pack for mod '" + modId
                            + "'. Its models/textures will be missing.", e);
            return null;
        }
    }

    private static Object newLocationInfo(Class<?> locationClass, String modId, Path jar)
            throws ReflectiveOperationException {
        // PackLocationInfo 在 26.2 是 record，构造器参数需查。
        // 用 getDeclaredConstructors：record 的构造器虽是 public，
        // 但用 getDeclared* + setAccessible 更稳，且能匹配到非 public 变体。
        for (java.lang.reflect.Constructor<?> c : locationClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
                c.setAccessible(true);
                return c.newInstance(idOf(modId), jar.getFileName().toString());
            }
            if (p.length == 3 && p[0] == String.class && p[1] == String.class) {
                c.setAccessible(true);
                return c.newInstance(idOf(modId), jar.getFileName().toString(), null);
            }
        }
        throw new BridgeMismatchException(
                "PackLocationInfo has no (String, String[, PackSource]) constructor."
                        + "\n  Real constructors: " + java.util.Arrays.toString(
                        locationClass.getDeclaredConstructors()));
    }

    private static Object newSelectionConfig(Class<?> selectionClass)
            throws ReflectiveOperationException {
        for (java.lang.reflect.Constructor<?> c : selectionClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            // 26.2: PackSelectionConfig(boolean required, Pack.Position position)
            if (p.length == 2 && p[0] == boolean.class && p[1].isEnum()) {
                c.setAccessible(true);
                Object top = enumConstant(p[1], "TOP");
                return c.newInstance(false, top);
            }
            if (p.length == 1 && p[0] == boolean.class) {
                c.setAccessible(true);
                return c.newInstance(false);
            }
        }
        throw new BridgeMismatchException(
                "PackSelectionConfig has no recognised constructor. Real: "
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
                            for (String entryName : listEntries(jar, prefix)) {
                                String rel = entryName.substring(prefix.length());
                                Object id = identifier(idClass, ns, rel);
                                if (id == null) {
                                    continue;
                                }
                                // 复制成 final 局部量：lambda 捕获循环变量在
                                // Java 21+ 的 effectively-final 规则下会编译失败，
                                // 且值会在循环推进后变化 —— 每次迭代必须绑定自己的值。
                                final String jarEntry = entryName;
                                Method accept = outputIface.getMethod("accept",
                                        idClass, java.util.function.Supplier.class);
                                // 注意：Supplier 没有 of() 工厂方法（那是 Optional 的），
                                // 直接用 lambda 构造。
                                java.util.function.Supplier<java.io.InputStream> supplier =
                                        () -> new java.io.ByteArrayInputStream(
                                                readOrEmpty(jar, jarEntry));
                                accept.invoke(output, id, supplier);
                            }
                            return null;
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

    /** 构造游戏侧的 {@code Identifier}（运行期才存在，编译期不能引用）。 */
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
