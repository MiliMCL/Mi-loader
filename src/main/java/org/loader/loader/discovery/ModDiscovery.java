package org.loader.loader.discovery;

import org.loader.loader.config.LoaderConfig;
import org.loader.runtime.mod.ModManifest;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Discovers mods from the mods/ directory.
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
            // Check both root and META-INF/ for mod.json
            java.util.zip.ZipEntry entry = zip.getEntry("mod.json");
            if (entry == null) entry = zip.getEntry("META-INF/mod.json");
            if (entry != null) {
                String content = new String(zip.getInputStream(entry).readAllBytes());
                ModManifest manifest = parseManifest(content, jarPath);
                discovered.add(manifest);
                byId.put(manifest.id(), manifest);
            }
        } catch (IOException e) { /* skip */ }
    }

    private void scanModDir(Path dir) {
        Path manifestPath = dir.resolve("mod.json");
        if (Files.exists(manifestPath)) {
            try {
                String content = Files.readString(manifestPath);
                ModManifest manifest = parseManifest(content, dir);
                discovered.add(manifest);
                byId.put(manifest.id(), manifest);
            } catch (IOException e) { /* skip */ }
        }
    }

    private ModManifest parseManifest(String json, Path source) {
        String id = extractJsonField(json, "id");
        String name = extractJsonField(json, "name");
        String version = extractJsonField(json, "version");
        String mainClass = extractJsonField(json, "entrypoint");
        if (id == null) id = source.getFileName().toString();
        if (name == null) name = id;
        if (version == null) version = "1.0.0";
        List<ModManifest.DependencyEntry> deps = parseDependencies(json);
        return new ModManifest(id, name, version, "",
                "", deps, List.of(), mainClass != null ? mainClass : "", List.of());
    }

    private List<ModManifest.DependencyEntry> parseDependencies(String json) {
        List<ModManifest.DependencyEntry> deps = new ArrayList<>();
        int arrayStart = json.indexOf("\"dependencies\"");
        if (arrayStart < 0) return deps;
        int bracketStart = json.indexOf('[', arrayStart);
        if (bracketStart < 0) return deps;
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd < 0) return deps;
        String arrayContent = json.substring(bracketStart, bracketEnd);
        String[] objects = arrayContent.split("\\{");
        for (String obj : objects) {
            String modId = extractJsonField(obj, "modId");
            if (modId != null) {
                String required = extractJsonField(obj, "required");
                deps.add(new ModManifest.DependencyEntry(modId, "*",
                        required == null || required.equals("true")));
            }
        }
        return deps;
    }

    private String extractJsonField(String json, String field) {
        String search = "\"" + field + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        // Skip whitespace after colon
        int valueStart = colon + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return null;
        // Handle quoted string
        if (json.charAt(valueStart) == '"') {
            int quoteEnd = json.indexOf('"', valueStart + 1);
            if (quoteEnd < 0) return null;
            return json.substring(valueStart + 1, quoteEnd);
        }
        // Handle unquoted value (number, boolean)
        int valueEnd = valueStart;
        while (valueEnd < json.length() && json.charAt(valueEnd) != ',' && json.charAt(valueEnd) != '}' && json.charAt(valueEnd) != '\n') valueEnd++;
        return json.substring(valueStart, valueEnd).trim();
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
