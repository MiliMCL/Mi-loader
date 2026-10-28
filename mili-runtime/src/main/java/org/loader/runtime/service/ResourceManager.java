package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages resource loading (configs, assets, data packs).
 * <p>
 * All filesystem access is capability-controlled.
 *
 * <h2>两级来源：Mod JAR 优先，数据目录兜底</h2>
 * 旧实现只有 {@code basePath} 一个来源，而 {@code ModContext} 传进来的是
 * {@code Path.of("mods/<modId>")} —— 一个<b>相对于进程工作目录</b>、
 * 在分发包布局下根本不存在的路径。结果是 Mod 读自己的任何资源都
 * {@code NoSuchFileException}，包括它明明打进 JAR 的贴图与语言文件。
 *
 * <p>现在有两个来源，顺序固定：
 * <ol>
 *   <li><b>Mod 的 ClassLoader</b>（读 JAR 内的 {@code assets/}、
 *       {@code data/}、{@code lang/}）。{@code ModClassLoader} 已经实现了
 *       「自己的 jar 优先、父加载器兜底」并且禁用 JAR 缓存 —— 这里直接复用，
 *       不重复实现一遍资源查找。</li>
 *   <li><b>可写数据目录</b>（{@code <gameDir>/config/mili/data/<modId>}）。
 *       给 Mod 一个确定的落盘位置，而不是让它去猜工作目录。</li>
 * </ol>
 *
 * <h2>路径穿越防护</h2>
 * {@link #resolve} 在 {@code normalize()} <b>之后</b>校验前缀，
 * 且 {@link #exists} 走同一条路径（旧实现里 {@code exists} 绕过了校验，
 * 可以被用来探测任意文件是否存在）。
 */
public class ResourceManager implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final Path basePath;
    private final ClassLoader sourceLoader;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ResourceManager(String id, Scope owner, Path basePath) {
        this(id, owner, basePath, null);
    }

    /**
     * @param basePath     可写数据目录（不存在时读取会失败，写入由调用方负责创建）
     * @param sourceLoader Mod 的 ClassLoader；为 {@code null} 时只读文件系统
     */
    public ResourceManager(String id, Scope owner, Path basePath, ClassLoader sourceLoader) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.basePath = Objects.requireNonNull(basePath).toAbsolutePath().normalize();
        this.sourceLoader = sourceLoader;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    public Path basePath() {
        return basePath;
    }

    /**
     * Resolves a resource path relative to the base path.
     *
     * @throws SecurityException 路径逃逸出 basePath
     */
    public Path resolve(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        Path resolved = basePath.resolve(resourcePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new SecurityException("Path traversal attempt: " + resourcePath);
        }
        return resolved;
    }

    /**
     * Opens an input stream for reading a resource.
     *
     * <p>先查 Mod JAR，再查数据目录。两者都没有时抛 {@link NoSuchFileException}
     * 而不是返回 {@code null} —— 返回 null 会让调用方在
     * {@code readAllBytes()} 里 NPE，堆栈完全指不到「文件不存在」。
     */
    public InputStream openStream(String resourcePath) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("ResourceManager is closed");
        }
        Path path = resolve(resourcePath);

        if (sourceLoader != null) {
            String normalized = resourcePath.replace('\\', '/');
            InputStream fromJar = sourceLoader.getResourceAsStream(normalized);
            if (fromJar != null) {
                return fromJar;
            }
        }
        if (!Files.exists(path)) {
            throw new NoSuchFileException(path.toString(),
                    null,
                    "资源既不在 Mod JAR 内，也不在数据目录中: " + resourcePath);
        }
        return Files.newInputStream(path);
    }

    /**
     * Reads all bytes from a resource.
     */
    public byte[] readAllBytes(String resourcePath) throws IOException {
        try (InputStream is = openStream(resourcePath)) {
            return is.readAllBytes();
        }
    }

    /**
     * Reads a resource as a UTF-8 String.
     *
     * <p>固定 UTF-8 而不是平台默认字符集：Windows 上默认是 GBK，
     * 同一份 {@code lang/en_us.json} 在中文系统上会读成乱码 ——
     * 而且只在中文玩家那里复现。
     */
    public String readString(String resourcePath) throws IOException {
        return new String(readAllBytes(resourcePath), StandardCharsets.UTF_8);
    }

    /**
     * Returns whether a resource exists.
     *
     * <p>与 {@link #openStream} 使用同一套来源顺序与同一条路径校验。
     */
    public boolean exists(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return false;
        }
        if (sourceLoader != null) {
            try {
                java.net.URL url = sourceLoader.getResource(resourcePath.replace('\\', '/'));
                if (url != null) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 非法路径 —— 落到文件系统检查
            }
        }
        try {
            return Files.exists(resolve(resourcePath));
        } catch (SecurityException e) {
            // 路径逃逸：按「不存在」处理，不暴露 basePath 之外的信息
            return false;
        }
    }

    /**
     * 列出 Mod JAR 内某个前缀下的全部资源路径。
     *
     * <p>只覆盖文件系统来源：JAR 内的目录枚举需要自己打开 ZipFile，
     * 而那会引入 Windows 文件锁问题（见 {@code ModClassLoader} 的注释）。
     * 需要枚举 JAR 内容的场景应该用 {@code ModClassLoader.modSources()}
     * 自己打开 jar —— 那是 loader 的职责，不是资源管理器的。
     *
     * @return 相对 basePath 的路径列表；目录不存在时返回空列表
     */
    public java.util.List<String> list(String directory) {
        try {
            Path dir = resolve(directory);
            if (!Files.isDirectory(dir)) {
                return java.util.List.of();
            }
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile)
                        .map(p -> basePath.relativize(p).toString().replace('\\', '/'))
                        .sorted()
                        .toList();
            }
        } catch (IOException | SecurityException e) {
            return java.util.List.of();
        }
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
