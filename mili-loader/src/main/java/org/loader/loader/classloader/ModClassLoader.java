package org.loader.loader.classloader;

import org.loader.runtime.mod.ModManifest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 Mod 的隔离 ClassLoader。
 *
 * <p><b>委托策略（child-first，但受 {@link ClassVisibility} 契约约束）</b>：
 * <ol>
 *   <li><b>FORBIDDEN</b> —— Loader 内部 / Runtime 内部 / 安装器内部 / JDK 内部：
 *       直接拒绝，抛 {@link ClassNotFoundException}。Mod 无法触碰平台实现。</li>
 *   <li><b>PARENT_FIRST</b> —— ABI（{@code org.loader.api}）、公开 Runtime 包、
 *       Minecraft（{@code net.minecraft}）：委派给 parent。
 *       <b>这是修复历史缺陷的关键</b>：Mod 与 Minecraft 共享同一份 MC 类，
 *       因此 Mod 拿到的 Minecraft 对象可以直接传给游戏，不再 ClassCastException。</li>
 *   <li><b>SELF_FIRST</b> —— 其余包：Mod 自己定义。两个 Mod 各自定义同名类
 *       时互不可见，实现真正的类隔离。</li>
 * </ol>
 *
 * <p><b>parent 语义</b>：parent 是 {@link MinecraftClassLoader}（进而委派到平台 CL），
 * 而<b>不是</b>依赖 Mod。这保证 Minecraft 类全局唯一；
 * 跨 Mod 访问只能通过 {@link ClassVisibility#isExportedTo} 显式导出的包。
 */
public final class ModClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final ModManifest manifest;
    private final String modId;
    private final MinecraftClassLoader gameClassLoader;
    private final Path gameDir;
    private final List<Path> modSources = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 该 Mod 实际加载过的类名（诊断 + 泄漏检测）。 */
    private final Set<String> loadedClasses = ConcurrentHashMap.newKeySet();
    /** 被拒绝的访问尝试（诊断 + 安全审计）。 */
    private final List<AccessViolation> violations = Collections.synchronizedList(new ArrayList<>());

    /**
     * @param manifestMod Mod 清单
     * @param gameCL      全局唯一的 Minecraft ClassLoader（作为 parent）
     * @param gameDir     游戏目录，用于定位 mods/&lt;id&gt;.jar
     */
    public ModClassLoader(ModManifest manifestMod, MinecraftClassLoader gameCL, Path gameDir) {
        super("mili-mod-" + manifestMod.id(), new URL[0], gameCL);
        this.manifest = manifestMod;
        this.modId = manifestMod.id();
        this.gameClassLoader = gameCL;
        this.gameDir = gameDir != null ? gameDir : Path.of(".");

        // 仅本 Mod 自己的代码进入 classpath —— 不再把整个 Minecraft classpath
        // 平铺进每个 Mod，那会导致 MC 类被重复定义。
        for (Path src : locateModSources()) {
            try {
                super.addURL(src.toUri().toURL());
                modSources.add(src);
            } catch (Exception e) {
                violations.add(new AccessViolation(modId, src.toString(), "无法加入 classpath: " + e.getMessage()));
            }
        }
    }

    /**
     * 定位 Mod 自身的代码源：mods/&lt;id&gt;.jar 或 mods/&lt;id&gt;/。
     */
    private List<Path> locateModSources() {
        List<Path> found = new ArrayList<>();
        Path modsDir = gameDir.resolve("mods");

        Path jar = modsDir.resolve(modId + ".jar");
        if (Files.exists(jar)) {
            found.add(jar);
        }
        Path dir = modsDir.resolve(modId);
        if (Files.isDirectory(dir)) {
            found.add(dir);
            // 开发态：展开 Gradle 输出，便于本地调试
            Path classes = dir.resolve("build/classes/java/main");
            if (Files.isDirectory(classes)) {
                found.add(classes);
            }
            Path resources = dir.resolve("build/resources/main");
            if (Files.isDirectory(resources)) {
                found.add(resources);
            }
            Path libs = dir.resolve("build/libs");
            if (Files.isDirectory(libs)) {
                try (var stream = Files.list(libs)) {
                    stream.filter(p -> p.toString().endsWith(".jar")).forEach(found::add);
                } catch (IOException ignored) {
                    // 目录不可读时跳过
                }
            }
        }
        return found;
    }

    // ── 核心：受契约约束的类加载 ────────────────────────────────────────────

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            if (closed.get()) {
                throw new ClassNotFoundException(
                        "Mod '" + modId + "' ClassLoader 已关闭，无法加载 " + name);
            }

            ClassVisibility.Resolution resolution = ClassVisibility.resolve(name);

            switch (resolution) {
                case FORBIDDEN -> {
                    violations.add(new AccessViolation(modId, name,
                            "访问被 ClassVisibility 契约禁止（Loader/Runtime/Installer/JDK 内部）"));
                    throw new ClassNotFoundException(
                            "Mod '" + modId + "' 禁止访问内部类: " + name
                                    + "（ClassVisibility 契约拒绝）");
                }
                case PARENT_FIRST -> {
                    // ABI / 公开 Runtime / Minecraft —— 委派，保证与游戏同源
                    Class<?> c = getParent().loadClass(name);
                    if (resolve) resolveClass(c);
                    loadedClasses.add(name);
                    return c;
                }
                case SELF_FIRST -> {
                    // Mod 私有类：优先自身 classpath
                    try {
                        Class<?> c = findClass(name);
                        if (resolve) resolveClass(c);
                        loadedClasses.add(name);
                        return c;
                    } catch (ClassNotFoundException notMine) {
                        // 自己没有 → 交给 parent（Minecraft 库等）
                        Class<?> c = getParent().loadClass(name);
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
                default -> throw new ClassNotFoundException(name);
            }
        }
    }

    // ── 资源隔离 ────────────────────────────────────────────────────────────

    /**
     * Mod 资源优先从自身 classpath 读取；不泄漏平台内部资源。
     *
     * <p><b>关键：必须 {@code setUseCaches(false)}</b>。默认的
     * {@code jar:} URLConnection 会把 {@link java.util.zip.ZipFile} 放进
     * JVM 级的 {@code JarFileFactory} 缓存，于是：
     * <ol>
     *   <li>读过一次资源后，即使流已close、ClassLoader 已 close，
     *       jar 文件仍被 Windows 锁住，无法删除；</li>
     *   <li>Mod 热重载时无法替换 jar —— 而这正是 ClassLoader 架构必须支持的场景。</li>
     * </ol>
     * 关闭缓存让每次读取使用独立的句柄，读取结束即释放。代价是无法复用
     * 缓存的 ZipFile，对 Mod 资源读取完全可以接受。
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        if (closed.get()) {
            return null;
        }
        // 禁止读取平台内部资源
        if (isInternalResource(name)) {
            violations.add(new AccessViolation(modId, name, "尝试读取平台内部资源"));
            return null;
        }
        // findResource 返回 URL（URLClassLoader 契约），需自行开流。
        URL own = findResource(name);
        if (own != null) {
            try {
                return openWithoutCache(own);
            } catch (IOException e) {
                // 落到 parent 兜底
            }
        }
        return getParent().getResourceAsStream(name);
    }

    /** 打开流并禁用 JVM 级 jar 缓存（见 {@link #getResourceAsStream} 的说明）。 */
    static InputStream openWithoutCache(URL url) throws IOException {
        URLConnection conn = url.openConnection();
        conn.setUseCaches(false);
        return conn.getInputStream();
    }

    private boolean isInternalResource(String name) {
        if (name == null) return true;
        return name.startsWith("META-INF/mili/platform")
                || name.startsWith("org/loader/loader/")
                || name.startsWith("org/loader/installer/");
    }

    // ── 对外接口 ────────────────────────────────────────────────────────────

    public ModManifest manifest() {
        return manifest;
    }

    public String modId() {
        return modId;
    }

    public MinecraftClassLoader gameClassLoader() {
        return gameClassLoader;
    }

    /** 本 Mod 的代码源（诊断用）。 */
    public List<Path> modSources() {
        return Collections.unmodifiableList(modSources);
    }

    public Class<?> loadModClass(String className) throws ClassNotFoundException {
        return loadClass(className, true);
    }

    /**
     * 扫描本 Mod 已加载的类，用于重复类检测与泄漏诊断。
     */
    public Set<String> loadedClasses() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(loadedClasses));
    }

    /**
     * 返回被拒绝的访问尝试记录。安全审计与测试断言用。
     */
    public List<AccessViolation> violations() {
        synchronized (violations) {
            return List.copyOf(violations);
        }
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            super.close();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 一次被拒绝的访问尝试。
     */
    public record AccessViolation(String modId, String target, String reason) {
        @Override
        public String toString() {
            return "[Mod " + modId + "] 拒绝访问 " + target + " — " + reason;
        }
    }
}