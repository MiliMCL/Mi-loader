package org.loader.loader.classloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
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

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final List<Path> gameClasspath;

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

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1) 已在缓存中
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }

            // 2) 平台自有包（org.loader.*）一律委派给 parent。
            //    绝不能让 MC 的 classpath 阴影掉平台的 abi/runtime。
            if (startsWithPlatformOwned(name)) {
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

    private boolean startsWithPlatformOwned(String className) {
        for (String p : PLATFORM_OWNED_PREFIXES) {
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

    /** 读取 Minecraft classpath 中的资源（诊断/测试用）。 */
    public InputStream getResourceAsStream(String name) {
        URL url = findResource(name);
        if (url == null) {
            return null;
        }
        try {
            return url.openStream();
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