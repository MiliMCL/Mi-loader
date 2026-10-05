package org.loader.loader.discovery;

import org.loader.loader.config.LoaderConfig;
import org.loader.loader.util.MiliJson;
import org.loader.runtime.error.ModLoadError;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.VersionBinding;

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

    private void scanModJar(Path jarPath) {
        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile());
            java.util.zip.ZipEntry entry = zip.getEntry("mod.json");
            if (entry == null) entry = zip.getEntry("META-INF/mod.json");
            if (entry != null) {
                String content = new String(zip.getInputStream(entry).readAllBytes());
                ModManifest manifest = parseManifest(content, jarPath);
                discovered.add(manifest);
                byId.put(manifest.id(), manifest);
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

    public List<ModManifest> discover() {
        return Collections.unmodifiableList(discovered);
    }

    public List<ModManifest> resolveDependencies(List<ModManifest> mods) {
        Map<String, ModManifest> index = new LinkedHashMap<>();
        for (ModManifest m : mods) index.put(m.id(), m);
        List<ModManifest> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (ModManifest mod : mods) resolveRecursive(mod, index, result, visited);
        return result;
    }

    private void resolveRecursive(ModManifest mod, Map<String, ModManifest> index,
                                  List<ModManifest> result, Set<String> visited) {
        if (visited.contains(mod.id())) return;
        visited.add(mod.id());
        for (ModManifest.DependencyEntry dep : mod.dependencies()) {
            if (dep.required() && index.containsKey(dep.modId())) {
                resolveRecursive(index.get(dep.modId()), index, result, visited);
            }
        }
        result.add(mod);
    }
}