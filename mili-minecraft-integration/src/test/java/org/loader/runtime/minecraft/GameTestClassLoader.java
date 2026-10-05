package org.loader.runtime.minecraft;

import org.loader.runtime.minecraft.reflect.Reflect;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 测试专用的「游戏 ClassLoader」，拓扑等价于生产环境的
 * {@code org.loader.loader.classloader.MinecraftClassLoader}。
 *
 * <h2>为什么不能直接用裸 URLClassLoader</h2>
 *
 * <p>早期版本这里用的是 {@code new URLClassLoader(urls, null)} —— 父加载器
 * 为 {@code null}，于是游戏 CL 完全看不到平台类。看起来能跑通大部分断言，
 * 实际上它<b>没有复现真实拓扑</b>：生产环境里
 * {@code MinecraftClassLoader} 的 parent 就是平台 ClassLoader，且显式把
 * {@code org.loader.*} 委派给 parent。
 *
 * <p>这个差异不是小事，它直接决定了绑定层的核心机制能不能成立：
 * <ul>
 *   <li>生成的 {@code MiliBlock} 类活在游戏 CL 里，其方法体
 *       {@code INVOKESTATIC org/loader/runtime/minecraft/block/BehaviourDispatch.tick}
 *       需要在<b>游戏 CL</b> 视角下解析 {@code BehaviourDispatch}；</li>
 *   <li>父加载器为 null 时它解析不到，抛
 *       {@code NoClassDefFoundError: BehaviourDispatch}；</li>
 *   <li>而这恰恰是<b>生产环境不会发生</b>的情况 —— 平台拓扑保证它可见。</li>
 * </ul>
 *
 * <p>换句话说：父加载器为 null 会让测试<b>验证一个不存在的故障</b>，
 * 让人误以为绑定层的设计有问题，从而改坏本来正确的实现。本类消除这个陷阱。
 *
 * <h2>与生产加载器的等价性</h2>
 * <p>本类复刻 {@code MinecraftClassLoader.loadClass} 的三条规则：
 * <ol>
 *   <li>{@code org.loader.*}（平台自有包）→ parent-first委派；</li>
 *   <li>{@code java.*} / {@code javax.*} / {@code jdk.*} / {@code sun.*} /
 *       {@code com.sun.} → 委派 parent，绝不用游戏 classpath 里的 shaded 副本；</li>
 *   <li>其余（{@code net.minecraft.*} 与 Mojang 库）→ self-first，
 *       找不到才回退 parent。</li>
 * </ol>
 *
 * <p>不直接依赖 {@code mili-loader} 是因为那会造成模块循环依赖
 * （loader 需要 integration 的反射层）。等价实现 + 明确的等价性注释，
 * 比引入循环依赖好。
 */
public final class GameTestClassLoader extends URLClassLoader {

    private static final List<String> PLATFORM_OWNED_PREFIXES =
            List.of("org.loader.");

    /** Minecraft 的 classpath 上带着 {@code java.*} 的第三方副本，必须委派。 */
    private static final List<String> JDK_OWNED_PREFIXES =
            List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

    public GameTestClassLoader(String name, URL[] gameUrls, ClassLoader platformParent) {
        super("mili-game", gameUrls, platformParent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            if (startsWithAny(name, PLATFORM_OWNED_PREFIXES)
                    || startsWithAny(name, JDK_OWNED_PREFIXES)) {
                Class<?> c = getParent().loadClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
            try {
                Class<?> c = findClass(name);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException notLocal) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private static boolean startsWithAny(String className, List<String> prefixes) {
        for (String p : prefixes) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    @Override
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

    // ── Minecraft 安装目录探测 ────────────────────────────────────────────

    /**
     * 找到本地 Minecraft 安装目录；找不到返回 {@code null}（调用方应跳过测试）。
     *
     * <p>CI 不应该有 Minecraft 构建输入，所以「找不到」必须是正常路径。
     */
    public static Path locateMinecraftDir() {
        String prop = System.getProperty("minecraft.testDir");
        if (prop != null && Files.isDirectory(Path.of(prop))) {
            return Path.of(prop);
        }
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        // mili-minecraft-integration 的工作目录是其子目录
        for (Path candidate : List.of(
                root.resolve("../test_client_bak"),
                root.resolve("test_client_bak"),
                root.resolve("../../test_client_bak"))) {
            Path norm = candidate.normalize();
            if (Files.isDirectory(norm) && Files.exists(norm.resolve("26.2.jar"))) {
                return norm;
            }
        }
        return null;
    }

    /** 收集 26.2.jar + libraries 下全部 JAR。 */
    public static URL[] collectClasspath(Path dir) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(dir.resolve("26.2.jar").toUri().toURL());
        Path libs = dir.resolve("libraries");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> stream = Files.walk(libs)) {
                for (Path jar : stream.filter(p -> p.toString().endsWith(".jar"))
                        .sorted(Comparator.comparing(Path::toString)).toList()) {
                    urls.add(jar.toUri().toURL());
                }
            }
        }
        return urls.toArray(new URL[0]);
    }

    /**
     * 装配并激活游戏 ClassLoader。
     *
     * @return 游戏 ClassLoader；没有 Minecraft 输入时返回 {@code null}
     */
    public static GameTestClassLoader install(String loaderName) throws IOException {
        Path dir = locateMinecraftDir();
        if (dir == null) {
            return null;
        }
        // 父加载器 = 平台 CL。这是与生产拓扑保持一致的关键一行，
        // 理由见类注释。
        GameTestClassLoader loader = new GameTestClassLoader(
                loaderName, collectClasspath(dir), GameTestClassLoader.class.getClassLoader());
        Reflect.useGameClassLoader(loader);
        return loader;
    }
}