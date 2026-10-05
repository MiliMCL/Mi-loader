package org.loader.loader.classloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minecraft 专属 ClassLoader —— 平台内<b>唯一</b>允许定义 {@code net.minecraft.*}
 * 的 ClassLoader。
 *
 * <p><b>为什么必须唯一</b>：Minecraft 内部存在大量跨类强引用与静态状态
 * （注册表、SharedConstants、资源管理器）。如果同一个 MC 类被两个 ClassLoader
 * 各加载一份，两份类互不相等，会导致 ClassCastException，且静态初始化状态分裂。
 * 因此架构上必须保证：整个进程中 {@code net.minecraft.*} 只有一个定义来源。
 *
 * <p><b>拓扑</b>：
 * <pre>
 *   AppClassLoader (平台: abi / runtime / loader / integration)
 *        │
 *        └─ MinecraftClassLoader   ← 唯一定义 net.minecraft.* + Mojang 库
 *               │
 *               ├─ ModClassLoader (mod-a)   ← child-first，parent = 本 CL
 *               ├─ ModClassLoader (mod-b)
 *               └─ ModClassLoader (mod-c)
 * </pre>
 *
 * <p>Mod 通过 parent 委派访问 Minecraft，从而与游戏共享同一份 MC 类，
 * 不再出现类型不相等问题。
 */
public final class MinecraftClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    /** 平台内部包，由 AppClassLoader 定义，Minecraft 不应重复加载。 */
    private static final List<String> PLATFORM_OWNED_PREFIXES = List.of(
            "org.loader."
    );

    /**
     * JDK 公共 API —— 必须委派给平台/boot 层，绝不能在本地 classpath 里找。
     *
     * <p>Minecraft 的 classpath 上确实带着 {@code java.*} 的第三方副本（log4j
     * 的 shaded 包、ASM 等），self-first 会命中它们，导致 Mod 侧出现第二份
     * {@code java.lang.Object} —— 那会让每个类都炸在
     * {@code NoClassDefFoundError} 上。
     */
    private static final List<String> JDK_OWNED_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    );

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Path> gameClasspath;

    /**
     * 字节码转换拦截器 —— 为 null 时本ClassLoader 行为与纯 URLClassLoader 相同。
     *
     * <p>用 {@code volatile}：拦截器在游戏启动阶段由平台装配，
     * 而类加载可能已经在进行。装配完成前加载的类不转换，
     * 装配完成后加载的类才转换 —— 这个顺序由
     * {@code EntryPointHook} 保证（先装配拦截器，再触发类加载）。
     */
    private volatile org.loader.loader.transform.ClassTransformInterceptor interceptor;

    /**
     * @param name        ClassLoader 名（诊断用）
     * @param gameUrls    Minecraft JAR + Mojang 库
     * @param parent      平台 ClassLoader（提供 org.loader.*）
     */
    public MinecraftClassLoader(String name, URL[] gameUrls, ClassLoader parent) {
        super("minecraft-game", gameUrls, parent);
        this.gameClasspath = new ArrayList<>();
        for (URL u : gameUrls) {
            try {
                this.gameClasspath.add(Path.of(u.toURI()));
            } catch (Exception ignored) {
                // 仅用于诊断展示，解析失败不影响加载
            }
        }
    }

    /**
     * 安装字节码转换拦截器。
     *
     * <p><b>必须在任何 {@code net.minecraft.*} 类被加载之前调用。</b>
     * 之后加载的类会被转换；之前加载的已经 {@code defineClass} 完成，
     * 无法回溯 —— 这也是平台必须在启动最早期装配它的原因。
     *
     * @param interceptor 拦截器；传 null 表示停用转换
     */
    public void setInterceptor(
            org.loader.loader.transform.ClassTransformInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /** 当前拦截器；未装配时为 null。 */
    public org.loader.loader.transform.ClassTransformInterceptor interceptor() {
        return interceptor;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1) 已在缓存中
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            // 2) 平台自有包（org.loader.*）与 JDK 包一律委派给 parent。
            //    绝不能让 MC 的 classpath 阴影掉平台的 abi/runtime，
            //    也不能让 MC 依赖里的 shaded java.* 复制一份 JDK 类。
            if (startsWithAny(name, PLATFORM_OWNED_PREFIXES)
                    || startsWithAny(name, JDK_OWNED_PREFIXES)) {
                Class<?> c = getParent().loadClass(name);
                if (resolve) resolveClass(c);
                return c;
            }

            // 3) Minecraft 及其依赖库：优先本地（self-first），
            //    找不到才回退 parent。这保证 Mojang 库版本不被平台污染。
            try {
                Class<?> c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException notLocal) {
                // 4) 回退：JDK / 平台提供的类
                return super.loadClass(name, resolve);
            }
        }
    }

    /**
     * 覆写 {@code findClass} —— 平台唯一的字节码转换插入点。
     *
     * <h2>为什么覆写这里</h2>
     * {@code loadClass} 决定「从哪个 ClassLoader 找」，
     * {@code findClass} 决定「如何把字节码变成类」。
     * Minecraft 与 Mojang 库走的是 {@code findClass}（self-first 分支），
     * 因此转换必须落在这里 —— 落在 {@code loadClass} 会连平台自己的类
     * 一起转换，那是错的。
     *
     * <h2>异常策略</h2>
     * <b>转换失败必须抛出。</b>不捕获、不降级为「用原始字节码继续」。
     * 后者等于把 {@code TransformationTargetNotFoundException}
     * 重新变回静默失效 —— Mod 的功能悄悄消失、游戏照常运行、
     * 日志里什么都没有。这正是本系统要消灭的那类问题。
     *
     * <h2>流必须关闭</h2>
     * jar 内嵌在 zip 中，Windows 上不关流会<b>锁住 Minecraft jar</b>。
     * 本仓库已在 {@link ModClassLoader#getResourceAsStream} 处记录过这个教训
     * （为此专门禁用了 JVM 级 jar 缓存）。此处用 try-with-resources。
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        org.loader.loader.transform.ClassTransformInterceptor hook = this.interceptor;
        if (hook == null) {
            // 未装配转换：走 URLClassLoader 的标准实现
            return super.findClass(name);
        }

        // 平台自有包永不转换 —— 转换它们会造成对 Mod 类的引用，
        // 形成 ClassLoader 泄漏。短路放在这里比放在拦截器内更早，
        // 也让「平台类不经过转换管线」这件事在代码上更显眼。
        if (startsWithAny(name, PLATFORM_OWNED_PREFIXES)) {
            return super.findClass(name);
        }

        try (InputStream in = getResourceAsStream(resourcePath(name))) {
            if (in == null) {
                return super.findClass(name);   // 交回标准路径，保持行为一致
            }
            byte[] transformed = hook.transformFromStream(name, this, in);
            return defineClass(name, transformed, 0, transformed.length);
        } catch (java.io.IOException e) {
            // 读取失败：退到标准路径，让 ClassNotFoundException 表达真实原因
            return super.findClass(name);
        }
    }

    /** 点分/斜杠形式的资源路径 —— jar 内条目用斜杠。 */
    private static String resourcePath(String binaryName) {
        return binaryName.replace('.', '/') + ".class";
    }

    private boolean startsWithAny(String className, List<String> prefixes) {
        for (String p : prefixes) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 探测当前 ClassLoader 能否真的加载 MC 主类 —— 用于启动前的硬校验。
     * 不能只检查文件存在，必须验证类可解析。
     */
    public boolean canLoad(String mainClassName) {
        if (closed.get()) {
            return false;
        }
        try {
            Class.forName(mainClassName, false, this);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Minecraft classpath（诊断用）。 */
    public List<Path> gameClasspath() {
        return Collections.unmodifiableList(gameClasspath);
    }

    /** 已加载的 net.minecraft 类数量（诊断用，非精确）。 */
    public int loadedMinecraftClassCount() {
        // URLClassLoader 未暴露已加载类集合，这里通过类名探测的关键类给出信号。
        try {
            Class.forName("net.minecraft.SharedConstants", false, this);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
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
     * 读取 Minecraft classpath 中的资源（诊断/测试用）。
     *
     * <p>禁用 JVM 级 jar 缓存，理由同 {@link ModClassLoader#getResourceAsStream}：
     * 缓存会让 Minecraft jar 在 Windows 上被永久锁住。
     */
    public InputStream getResourceAsStream(String name) {
        URL url = findResource(name);
        if (url == null) {
            return null;
        }
        try {
            URLConnection conn = url.openConnection();
            conn.setUseCaches(false);
            return conn.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }

    /** 供诊断输出的类路径集合。 */
    public Set<String> classpathNames() {
        Set<String> names = new LinkedHashSet<>();
        for (URL u : getURLs()) {
            names.add(u.toString());
        }
        return names;
    }
}