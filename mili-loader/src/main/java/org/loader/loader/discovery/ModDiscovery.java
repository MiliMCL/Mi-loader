package org.loader.loader.discovery;

import org.loader.loader.PlatformLog;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.util.MiliJson;
import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.VersionBinding;
import org.loader.runtime.util.DependencySorter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 从 mods/ 目录发现 Mod。
 * 使用 {@link MiliJson} 可靠地解析 mod.json。
 */
public class ModDiscovery {

    private final LoaderConfig config;
    private final List<ModManifest> discovered = new ArrayList<>();
    private final Map<String, ModManifest> byId = new LinkedHashMap<>();

    /**
     * modId → 实际来源（jar 文件或目录）。
     *
     * <h2>为什么必须记下来</h2>
     * 旧实现让 {@code ModClassLoader} 按 {@code <modsDir>/<modId>.jar}
     * 猜自己的代码源。这条约定在现实中完全站不住：Maven/Gradle 产物叫
     * {@code aurora-1.0.0.jar}，从 Modrinth 下载的叫
     * {@code aurora-crystal-1.0.0+mc26.2.jar}，而 mod.json 里的 id 是
     * {@code miliaurora}。三者永远对不上，结果是
     * <b>Mod 被发现、被列出，但类加载不了</b> —— 报错还是
     * {@code ClassNotFoundException}，看起来像 Mod 自己的问题。
     *
     * <p>发现阶段是唯一知道「这个 id 来自哪个文件」的地方。记下来，
     * 传给 ClassLoader，猜文件名这条路就彻底不需要了。
     */
    private final Map<String, Path> sourcePaths = new LinkedHashMap<>();

    public ModDiscovery(LoaderConfig config) {
        this.config = config;
    }

    public static ModDiscovery scan(LoaderConfig config) {
        ModDiscovery discovery = new ModDiscovery(config);
        discovery.scan();
        return discovery;
    }

    private void scan() {
        Path modsPath = config.getModsPath();
        if (!Files.isDirectory(modsPath)) return;

        try (Stream<Path> paths = Files.list(modsPath)) {
            paths.filter(p -> p.toString().endsWith(".jar") || p.toString().endsWith(".zip"))
                 .forEach(this::scanModJar);
        } catch (IOException e) { /* ignore */ }

        try (Stream<Path> paths = Files.list(modsPath)) {
            paths.filter(Files::isDirectory).forEach(this::scanModDir);
        } catch (IOException e) { /* ignore */ }
    }

    /**
     * 扫描一个 Mod JAR。
     *
     * <h2>为什么要 try-with-resources</h2>
     * 旧实现直接 {@code new ZipFile(...)} 且从不关闭。在 Windows 上
     * 这是一个**实打实的资源泄漏**：ZipFile 持有文件句柄并带
     * {@code FILE_SHARE_DELETE} 之外的共享模式，于是扫描过一次之后：
     * <ul>
     *   <li>玩家无法删除、重命名、覆盖 mods 目录里的 JAR ——
     *       系统提示「文件正在被另一个程序使用」，而那个程序就是自己；</li>
     *   <li>{@code docs} 里承诺的热重载不可能工作 ——
     *       旧 JAR 根本没被释放；</li>
     *   <li>测试里 {@code @TempDir} 清理失败，报的是一句与根因无关的
     *       "Failed to close extension context"。</li>
     * </ul>
     * 三种症状看起来毫不相干，根因都是同一个没关的句柄。
     */
    private void scanModJar(Path jarPath) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("mod.json");
            if (entry == null) entry = zip.getEntry("META-INF/mod.json");
            if (entry != null) {
                String content;
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    content = new String(in.readAllBytes());
                }
                ModManifest manifest = parseManifest(content, jarPath);
                discovered.add(manifest);
                byId.put(manifest.id(), manifest);
                sourcePaths.put(manifest.id(), jarPath);
            }
        } catch (IOException e) { /* skip bad jar */ }
    }

    private void scanModDir(Path dir) {
        Path manifestPath = dir.resolve("mod.json");
        if (Files.exists(manifestPath)) {
            try {
                String content = Files.readString(manifestPath);
                ModManifest manifest = parseManifest(content, dir);
                discovered.add(manifest);
                byId.put(manifest.id(), manifest);
                sourcePaths.put(manifest.id(), dir);
            } catch (IOException e) { /* skip bad dir */ }
        }
    }

    @SuppressWarnings("unchecked")
    private ModManifest parseManifest(String json, Path source) {
        Map<String, Object> root;
        try {
            root = MiliJson.parseObject(json);
        } catch (Exception e) {
            throw new ModLoadError(diagnostic(source, "mod.json 解析失败: " + e.getMessage(),
                    "MOD_MANIFEST_INVALID"), "MOD_MANIFEST_INVALID");
        }

        String id = str(root, "id");
        if (id == null || id.isBlank()) {
            throw new ModLoadError(diagnostic(source, "mod.json 缺少必填字段 id",
                    "MOD_MANIFEST_INVALID"), "MOD_MANIFEST_INVALID");
        }
        String name = str(root, "name");
        String version = str(root, "version");
        String entrypoint = str(root, "entrypoint");
        String author = str(root, "author");
        if (name == null || name.isBlank()) name = id;
        if (version == null || version.isBlank()) version = "1.0.0";
        if (entrypoint == null) entrypoint = "";

        List<ModManifest.DependencyEntry> deps = parseDependencies(root.get("dependencies"));
        VersionBinding binding = parseVersionBinding(root.get("mili"));
        return new ModManifest(id, name, version, author != null ? author : "", "",
                deps, List.of(), entrypoint, List.of(), binding);
    }

    @SuppressWarnings("unchecked")
    private VersionBinding parseVersionBinding(Object miliObj) {
        if (!(miliObj instanceof Map)) return VersionBinding.unbound();
        Map<String, Object> mili = (Map<String, Object>) miliObj;
        String platform = str(mili, "platform");
        Object abiObj = mili.get("abi");
        String minecraft = str(mili, "minecraft");
        int abi = 0;
        if (abiObj instanceof Number) {
            abi = ((Number) abiObj).intValue();
        } else if (abiObj instanceof String && !((String) abiObj).isBlank()) {
            try { abi = Integer.parseInt((String) abiObj); } catch (NumberFormatException ignored) {}
        }
        if (platform == null || platform.isBlank()) return VersionBinding.unbound();
        return new VersionBinding(platform, abi, minecraft != null ? minecraft : "");
    }

    @SuppressWarnings("unchecked")
    private List<ModManifest.DependencyEntry> parseDependencies(Object depObj) {
        List<ModManifest.DependencyEntry> deps = new ArrayList<>();
        if (!(depObj instanceof List)) return deps;
        for (Object item : (List<Object>) depObj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> dep = (Map<String, Object>) item;
            String modId = str(dep, "modId");
            if (modId == null) continue;
            Object reqObj = dep.get("required");
            boolean required = !(reqObj instanceof Boolean) || (Boolean) reqObj;
            deps.add(new ModManifest.DependencyEntry(modId, "*", required));
        }
        return deps;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    private String diagnostic(Path source, String detail, String code) {
        return "[ModSource: " + source.getFileName() + "] " + detail + "\n" +
               "  ErrorCode: " + code;
    }

    /**
     * modId → 来源路径（jar 或目录）。只读快照。
     *
     * <p>返回给 loader 用于装配 {@code ModClassLoader}，见
     * {@code sourcePaths} 字段的注释。
     */
    public Map<String, Path> sources() {
        return Map.copyOf(sourcePaths);
    }

    public List<ModManifest> discover() {
        return Collections.unmodifiableList(discovered);
    }

    /**
     * 按依赖拓扑顺序排列 Mod。
     *
     * <p><b>循环依赖必须在这里被拦住</b>：Mod 加载顺序是不归路 ——
     * 一旦以错误顺序初始化，Mod 之间的相互引用就已经发生，事后无法补救。
     * 历史实现用 {@code visited} 去重，{@code A → B → A} 会被当成
     * 「已访问」而静默通过，两个 Mod 以任意顺序进入加载流程。
     *
     * <p>缺失依赖仍然保持宽松（只告警不阻断）：可选依赖很常见，
     * 而且「声明了但没装」在开发期经常是有意的。
     *
     * @throws org.loader.runtime.error.ModLoadError 依赖成环（MOD_DEPENDENCY_CYCLE）
     */
    public List<ModManifest> resolveDependencies(List<ModManifest> mods) {
        Map<String, ModManifest> index = new LinkedHashMap<>();
        for (ModManifest m : mods) {
            index.putIfAbsent(m.id(), m);
        }

        List<DependencySorter.Node> nodes = new ArrayList<>(index.size());
        for (ModManifest mod : index.values()) {
            List<String> required = new ArrayList<>();
            for (ModManifest.DependencyEntry dep : mod.dependencies()) {
                if (dep.required()) {
                    required.add(dep.modId());
                }
            }
            nodes.add(new DependencySorter.Node(mod.id(), required));
        }

        DependencySorter.Result sorted =
                DependencySorter.sort(nodes, new ArrayList<>(index.keySet()));

        if (sorted.hasCycles()) {
            String chain = sorted.describeCycles();
            throw new ModLoadError(
                    "依赖循环，拒绝启动: " + chain + "\n  ErrorCode: MOD_DEPENDENCY_CYCLE",
                    "MOD_DEPENDENCY_CYCLE");
        }

        if (sorted.hasMissing()) {
            missingDependencies = List.copyOf(sorted.missing());
            PlatformLog.warn("以下依赖在 mods 目录中不存在（按可选依赖处理）: "
                    + String.join(", ", missingDependencies));
        } else {
            missingDependencies = List.of();
        }

        List<ModManifest> result = new ArrayList<>(sorted.order().size());
        for (String id : sorted.order()) {
            ModManifest manifest = index.get(id);
            if (manifest != null) {
                result.add(manifest);
            }
        }
        return result;
    }

    /**
     * 上一次 {@link #resolveDependencies} 中未能解析的依赖 id。
     *
     * <p>Loader 用它把「装了 A 但没装 A 依赖的 B」写进启动报告，
     * 而不是让 Mod 在运行期抛 {@code NoClassDefFoundError} 时才发现。
     */
    public List<String> missingDependencies() {
        return missingDependencies;
    }

    private volatile List<String> missingDependencies = List.of();
}