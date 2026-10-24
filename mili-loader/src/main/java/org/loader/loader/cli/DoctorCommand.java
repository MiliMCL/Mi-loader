package org.loader.loader.cli;

import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.LibraryResolver;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.util.MiliJson;
import org.loader.runtime.mod.ModManifest;
import org.loader.runtime.mod.ModManifest.VersionBinding;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code mili doctor} —— Mod 目录只读体检。
 *
 * <h2>设计边界（与 MD A2 验收一一对应）</h2>
 * <ul>
 *   <li><b>只读</b>：不启动 Minecraft、不实例化 Mod、不执行 Mod 类、
 *       不触发任何静态初始化 —— 只做文件级检查。</li>
 *   <li><b>复用正式规则</b>：路径推导用 {@link LoaderConfig}；三元组校验用
 *       {@link ModManifest#validateVersionBinding()}；关键库核对用
 *       {@link LibraryResolver#validateClasspath}。这里不发明第二套版本规则。</li>
 *   <li><b>显式化静默失效</b>：正式发现流程（ModDiscovery）对坏 JAR 静默跳过、
 *       对缺失依赖静默跳过、对循环依赖不检测 —— 这些正是 doctor 要暴露的。
 *       manifest 字段语义（id 必填、name/version 缺省值、mili 绑定解析）与
 *       ModDiscovery.parseManifest 保持一致，一致点由测试锁定。</li>
 *   <li><b>不提取</b>：JAR 内文件只读取字节流，绝不解压 —— zip 路径穿越
 *       （条目名含 {@code ..} / 绝对路径）只报告、不落盘。</li>
 * </ul>
 *
 * <h2>退出码</h2>
 * {@code 0}=无 error；{@code 1}=存在 error 级问题；{@code 2}=用法错误。
 * warning 不影响退出码（文档化于 {@code docs/ERROR_MODEL.md}）。
 */
final class DoctorCommand {

    private static final int MAX_ZIP_ENTRIES_SCANNED = 20_000;
    private static final int MAX_EXAMPLES_PER_FINDING = 5;

    enum Severity { ERROR, WARNING, INFO }

    /** 一条诊断结论。modId/source 可空；details 供 JSON 消费。 */
    record Finding(String id, Severity severity, String message,
                   String modId, String source, Map<String, Object> details) {
        static Finding of(String id, Severity s, String message) {
            return new Finding(id, s, message, null, null, Map.of());
        }

        static Finding of(String id, Severity s, String message, String modId, String source) {
            return new Finding(id, s, message, modId, source, Map.of());
        }
    }

    /** 一个被发现 Mod 的体检档案。 */
    record ModEntry(String id, String name, String version, String source,
                    boolean bound, String platform, int abi, String minecraft,
                    List<Map.Entry<String, Boolean>> dependencies) {
    }

    private final List<Finding> findings = new ArrayList<>();
    private final List<ModEntry> mods = new ArrayList<>();

    private DoctorCommand() {
    }

    /** 入口；返回退出码。 */
    static int run(String[] args, PrintStream out, PrintStream err, boolean tty) {
        Path gameDir = null;
        Path modsDirOverride = null;
        boolean json = false;
        boolean graph = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            // 前缀形式必须先于 switch 处理：switch 只匹配整串
            if (a.startsWith("--mili-mods=")) {
                modsDirOverride = Path.of(a.substring("--mili-mods=".length()));
                continue;
            }
            switch (a) {
                case "--json" -> json = true;
                case "--graph" -> graph = true;
                case "--mili-mods" -> {
                    if (i + 1 >= args.length) {
                        err.println("doctor: --mili-mods 需要一个目录参数");
                        return 2;
                    }
                    modsDirOverride = Path.of(args[++i]);
                }
                default -> {
                    if (a.startsWith("--")) {
                        err.println("doctor: 未知选项 " + a);
                        return 2;
                    }
                    if (gameDir != null) {
                        err.println("doctor: 一次只能指定一个 gameDir（收到 " + a + "）");
                        return 2;
                    }
                    gameDir = Path.of(a);
                }
            }
        }
        if (gameDir == null) gameDir = Path.of(".");
        gameDir = gameDir.toAbsolutePath().normalize();
        if (modsDirOverride != null) {
            modsDirOverride = modsDirOverride.toAbsolutePath().normalize();
        }

        DoctorCommand doctor = new DoctorCommand();
        doctor.examine(gameDir, modsDirOverride);

        if (json) {
            out.println(doctor.renderJson(gameDir, modsDirOverride));
        } else {
            doctor.renderText(out, gameDir, modsDirOverride);
        }
        if (graph) {
            out.println(doctor.renderMermaid());
        }
        return doctor.errorCount() > 0 ? 1 : 0;
    }

    // ── 检查逻辑 ────────────────────────────────────────────────────────

    private void examine(Path gameDir, Path modsDirOverride) {
        LoaderConfig config = (modsDirOverride != null)
                ? LoaderConfig.at(gameDir, modsDirOverride)
                : LoaderConfig.at(gameDir);
        Path modsDir = config.getModsPath();

        // 1. gameDir 本体
        if (!Files.isDirectory(gameDir)) {
            findings.add(Finding.of("GAME_DIR_MISSING", Severity.ERROR,
                    "gameDir 不存在或不是目录: " + gameDir));
            return; // 后续检查全部失去意义
        }

        // 2. Minecraft 存在性（只探测，不加载）
        try {
            MinecraftDiscovery mc = MinecraftDiscovery.scan(LoaderConfig.at(gameDir));
            if (mc.found()) {
                findings.add(Finding.of("MC_FOUND", Severity.INFO,
                        "找到 Minecraft (" + mc.getGameType() + "): " + mc.getGameJar()));
                // 关键库核对复用正式解析器（log4j/gson/netty 等 9 项，缺失仅告警）
                List<String> libWarnings = LibraryResolver.validateClasspath(mc.getClasspath());
                for (String w : libWarnings) {
                    findings.add(Finding.of("LIB_MISSING", Severity.WARNING,
                            "关键库缺失: " + w));
                }
            } else {
                findings.add(Finding.of("MC_NOT_FOUND", Severity.WARNING,
                        "未在 gameDir 找到 Minecraft JAR（首次启动会触发自动安装）"));
            }
        } catch (Exception e) {
            findings.add(new Finding("MC_PROBE_FAILED", Severity.WARNING,
                    "Minecraft 探测异常: " + e.getMessage(), null, null, Map.of()));
        }

        // 3. mods 目录
        if (!Files.isDirectory(modsDir)) {
            findings.add(Finding.of("MODS_DIR_MISSING", Severity.WARNING,
                    "Mod 目录不存在: " + modsDir + "（没有 Mod 也可能是有意为之）"));
            return;
        }

        // 4. 逐文件体检（容错：一个坏文件不中断其余）
        Map<String, List<Path>> byId = new LinkedHashMap<>();
        List<Path> entries;
        try (var stream = Files.list(modsDir)) {
            entries = stream.sorted().toList();
        } catch (Exception e) {
            findings.add(Finding.of("MODS_DIR_UNREADABLE", Severity.ERROR,
                    "Mod 目录不可读: " + modsDir + " (" + e.getMessage() + ")"));
            return;
        }

        for (Path p : entries) {
            String name = p.getFileName().toString();
            if (name.endsWith(".jar") || name.endsWith(".zip")) {
                scanJar(p, byId);
            } else if (Files.isDirectory(p)) {
                scanDir(p, byId);
            }
        }

        // 5. 重复 Mod ID
        for (var e : byId.entrySet()) {
            if (e.getValue().size() > 1) {
                List<String> sources = e.getValue().stream()
                        .map(x -> x.getFileName().toString()).toList();
                findings.add(Finding.of("MOD_ID_DUPLICATE", Severity.ERROR,
                        "Mod ID 重复: " + e.getKey() + " 来自 " + sources,
                        e.getKey(), sources.get(0)));
            }
        }

        // 6. 版本绑定 + 依赖图（用 ModManifest 的正式校验）
        List<ModManifest> manifests = new ArrayList<>();
        for (ModEntry m : mods) {
            ModManifest mm = rebuild(m);
            manifests.add(mm);
            checkBinding(m, mm);
        }
        checkDependencies(manifests);
    }

    private void scanJar(Path jar, Map<String, List<Path>> byId) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("mod.json");
            if (entry == null) entry = zip.getEntry("META-INF/mod.json");

            // 防御检查：条目名含穿越标记（doctor 不解压，但恶意条目值得点名）
            List<String> unsafe = new ArrayList<>();
            int scanned = 0;
            int forbidden = 0;
            List<String> forbiddenExamples = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                if (++scanned > MAX_ZIP_ENTRIES_SCANNED) {
                    findings.add(Finding.of("ZIP_ENTRY_LIMIT", Severity.INFO,
                            "JAR 条目过多，仅扫描前 " + MAX_ZIP_ENTRIES_SCANNED + " 条: "
                                    + jar.getFileName(),
                            null, jar.getFileName().toString()));
                    break;
                }
                String en = it.nextElement().getName();
                if (en.contains("..") || en.startsWith("/") || en.contains(":")) {
                    unsafe.add(en);
                }
                if (en.startsWith("org/loader/") && en.endsWith(".class")) {
                    forbidden++;
                    if (forbiddenExamples.size() < MAX_EXAMPLES_PER_FINDING) {
                        forbiddenExamples.add(en);
                    }
                }
            }
            if (!unsafe.isEmpty()) {
                findings.add(new Finding("ZIP_ENTRY_UNSAFE", Severity.ERROR,
                        "JAR 条目名含路径穿越标记（本工具未解压，其他解压方需警惕）: "
                                + head(unsafe), null, jar.getFileName().toString(),
                        Map.of("entries", List.copyOf(unsafe))));
            }
            if (forbidden > 0) {
                findings.add(new Finding("MOD_BUNDLES_PLATFORM_CLASSES", Severity.WARNING,
                        "JAR 捆绑了平台类（org/loader/**，应由平台提供而非 Mod 自带）"
                                + forbidden + " 个，例如 " + forbiddenExamples,
                        null, jar.getFileName().toString(),
                        Map.of("count", forbidden)));
            }

            if (entry == null) {
                findings.add(Finding.of("MOD_MANIFEST_INVALID", Severity.ERROR,
                        "JAR 内没有 mod.json / META-INF/mod.json", null,
                        jar.getFileName().toString()));
                return;
            }
            String content;
            try (var in = zip.getInputStream(entry)) {
                content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            ingest(content, jar, byId);
        } catch (Exception e) {
            findings.add(Finding.of("MOD_JAR_UNREADABLE", Severity.ERROR,
                    "JAR 无法读取: " + e.getMessage(), null, jar.getFileName().toString()));
        }
    }

    private void scanDir(Path dir, Map<String, List<Path>> byId) {
        Path manifest = dir.resolve("mod.json");
        if (!Files.exists(manifest)) return; // 目录非 Mod，静默（与正式发现一致）
        try {
            ingest(Files.readString(manifest), dir, byId);
        } catch (Exception e) {
            findings.add(Finding.of("MOD_MANIFEST_INVALID", Severity.ERROR,
                    "mod.json 无法读取: " + e.getMessage(), null,
                    dir.getFileName().toString()));
        }
    }

    /** 解析 manifest 并建档；字段语义与 ModDiscovery.parseManifest 一致。 */
    private void ingest(String json, Path source, Map<String, List<Path>> byId) {
        String srcName = source.getFileName().toString();
        Map<String, Object> root;
        try {
            root = MiliJson.parseObject(json);
        } catch (Exception e) {
            findings.add(Finding.of("MOD_MANIFEST_INVALID", Severity.ERROR,
                    "mod.json 解析失败: " + e.getMessage(), null, srcName));
            return;
        }
        String id = str(root, "id");
        if (id == null || id.isBlank()) {
            findings.add(Finding.of("MOD_MANIFEST_INVALID", Severity.ERROR,
                    "mod.json 缺少必填字段 id", null, srcName));
            return;
        }
        String name = str(root, "name");
        String version = str(root, "version");
        String author = str(root, "author");
        if (name == null || name.isBlank()) name = id;
        if (version == null || version.isBlank()) version = "1.0.0";

        VersionBinding binding = parseBinding(root.get("mili"));
        List<Map.Entry<String, Boolean>> deps = parseDeps(root.get("dependencies"));
        Object caps = root.get("capabilities");
        if (caps != null) {
            findings.add(new Finding("CAPABILITY_DECLARED", Severity.INFO,
                    "声明了 capabilities 字段（当前平台加载流程不消费该字段，仅记录）",
                    id, srcName, Map.of("declared", String.valueOf(caps))));
        }

        ModEntry m = new ModEntry(id, name, version, srcName,
                binding != null, binding != null ? binding.platform() : "",
                binding != null ? binding.abi() : 0,
                binding != null ? binding.minecraft() : "",
                deps);
        mods.add(m);
        byId.computeIfAbsent(id, k -> new ArrayList<>()).add(source);
    }

    private void checkBinding(ModEntry m, ModManifest formal) {
        ModManifest.ValidationResult r = formal.validateVersionBinding();
        String source = m.source();
        switch (r) {
            case ModManifest.ValidationResult.MissingBinding b ->
                    findings.add(Finding.of("MOD_PLATFORM_MISSING", Severity.ERROR,
                            "未声明 mili 平台绑定（platform/abi/minecraft 三元组）",
                            m.id(), source));
            case ModManifest.ValidationResult.PlatformMismatch p ->
                    findings.add(new Finding("MOD_PLATFORM_MISMATCH", Severity.ERROR,
                            "平台版本不匹配: 声明 " + p.actual() + "，当前 "
                                    + p.expected(), m.id(), source, Map.of()));
            case ModManifest.ValidationResult.AbiMismatch a ->
                    findings.add(new Finding("MOD_ABI_MISMATCH", Severity.ERROR,
                            "ABI 版本不匹配: 声明 " + a.actual() + "，当前 "
                                    + a.expected(), m.id(), source, Map.of()));
            case ModManifest.ValidationResult.MinecraftMismatch mc ->
                    findings.add(new Finding("MOD_MINECRAFT_MISMATCH", Severity.ERROR,
                            "Minecraft 版本不匹配: 声明 " + mc.actual() + "，当前 "
                                    + mc.expected(), m.id(), source, Map.of()));
        }
    }

    private void checkDependencies(List<ModManifest> manifests) {
        Map<String, ModManifest> index = new LinkedHashMap<>();
        for (ModManifest m : manifests) index.put(m.id(), m);

        for (ModManifest m : manifests) {
            for (ModManifest.DependencyEntry dep : m.dependencies()) {
                if (dep.modId().equals(m.id())) {
                    findings.add(Finding.of("DEP_SELF", Severity.ERROR,
                            "Mod 依赖自身: " + m.id(), m.id(), null));
                    continue;
                }
                if (!index.containsKey(dep.modId())) {
                    findings.add(new Finding(dep.required() ? "DEP_MISSING" : "DEP_OPTIONAL_MISSING",
                            dep.required() ? Severity.ERROR : Severity.INFO,
                            (dep.required() ? "缺失依赖: " : "可选依赖不存在: ")
                                    + dep.modId() + "（由 " + m.id() + " 声明）",
                            m.id(), null, Map.of("dep", dep.modId())));
                }
            }
        }

        // 环检测：DFS 三色标记，报告完整环路径
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> inStack = new HashSet<>();
        for (ModManifest m : manifests) {
            if (!visited.contains(m.id())) {
                dfs(m.id(), index, visited, stack, inStack);
            }
        }
    }

    private void dfs(String id, Map<String, ModManifest> index,
                     Set<String> visited, Deque<String> stack, Set<String> inStack) {
        visited.add(id);
        stack.push(id);
        inStack.add(id);
        ModManifest m = index.get(id);
        if (m != null) {
            for (ModManifest.DependencyEntry dep : m.dependencies()) {
                if (!index.containsKey(dep.modId())) continue;
                if (inStack.contains(dep.modId())) {
                    List<String> cycle = new ArrayList<>();
                    for (Iterator<String> it = stack.descendingIterator(); it.hasNext(); ) {
                        String n = it.next();
                        cycle.add(n);
                        if (n.equals(dep.modId())) break;
                    }
                    Collections.reverse(cycle);
                    cycle.add(dep.modId());
                    findings.add(new Finding("DEP_CYCLE", Severity.ERROR,
                            "循环依赖: " + String.join(" -> ", cycle),
                            id, null, Map.of("cycle", cycle)));
                } else if (!visited.contains(dep.modId())) {
                    dfs(dep.modId(), index, visited, stack, inStack);
                }
            }
        }
        stack.pop();
        inStack.remove(id);
    }

    // ── 输出 ────────────────────────────────────────────────────────────

    private int errorCount() {
        return (int) findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
    }

    private void renderText(PrintStream out, Path gameDir, Path modsDir) {
        out.println("mili doctor — 只读体检");
        out.println("gameDir: " + gameDir);
        out.println("modsDir: " + modsDir);
        out.println();
        for (Severity s : List.of(Severity.ERROR, Severity.WARNING, Severity.INFO)) {
            for (Finding f : findings) {
                if (f.severity() != s) continue;
                out.printf("[%s] %s: %s%n", s, f.id(), f.message());
            }
        }
        out.println();
        if (mods.isEmpty()) {
            out.println("未发现任何 Mod。");
        } else {
            out.println("Mod 清单（" + mods.size() + " 个，按加载期正式规则校验三元组）：");
            for (ModEntry m : mods) {
                out.printf("  %-24s v%-8s %s%n", m.id(), m.version(), m.source());
                out.printf("    绑定: platform=%s abi=%d minecraft=%s%n",
                        m.platform(), m.abi(), m.minecraft());
                if (!m.dependencies().isEmpty()) {
                    out.printf("    依赖: %s%n", m.dependencies().stream()
                            .map(d -> d.getKey() + (d.getValue() ? "" : "(可选)"))
                            .reduce((a, b) -> a + ", " + b).orElse(""));
                }
            }
        }
        out.println();
        out.printf("结论: %d error, %d warning, %d info%s%n",
                errorCount(), warnCount(), infoCount(),
                errorCount() > 0 ? " —— 存在 error 级问题，退出码 1"
                        : " —— 体检通过");
    }

    private String renderMermaid() {
        StringBuilder sb = new StringBuilder("```mermaid\ngraph TD\n");
        for (ModEntry m : mods) {
            String node = mermaidNode(m.id());
            sb.append("  ").append(node).append("[\"").append(escapeMermaid(m.id()))
              .append(" v").append(escapeMermaid(m.version())).append("\"]\n");
            for (Map.Entry<String, Boolean> d : m.dependencies()) {
                if (index(d.getKey())) {
                    sb.append("  ").append(node).append(d.getValue() ? " --> " : " -.-> ")
                      .append(mermaidNode(d.getKey())).append('\n');
                }
            }
        }
        sb.append("```\n");
        return sb.toString();
    }

    private boolean index(String id) {
        return mods.stream().anyMatch(m -> m.id().equals(id));
    }

    private String renderJson(Path gameDir, Path modsDir) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"schemaVersion\":\"").append(MiliCli.SCHEMA_VERSION).append('"');
        sb.append(",\"gameDir\":\"").append(MiliCli.jsonEscape(gameDir.toString())).append('"');
        sb.append(",\"modsDir\":\"").append(MiliCli.jsonEscape(modsDir.toString())).append('"');
        sb.append(",\"summary\":{\"errors\":").append(errorCount())
          .append(",\"warnings\":").append(warnCount())
          .append(",\"infos\":").append(infoCount()).append('}');
        sb.append(",\"findings\":[");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(MiliCli.jsonEscape(f.id()))
              .append("\",\"severity\":\"").append(f.severity())
              .append("\",\"message\":\"").append(MiliCli.jsonEscape(f.message())).append('"');
            if (f.modId() != null) {
                sb.append(",\"modId\":\"").append(MiliCli.jsonEscape(f.modId())).append('"');
            }
            if (f.source() != null) {
                sb.append(",\"source\":\"").append(MiliCli.jsonEscape(f.source())).append('"');
            }
            sb.append('}');
        }
        sb.append(']');
        sb.append(",\"mods\":[");
        for (int i = 0; i < mods.size(); i++) {
            ModEntry m = mods.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(MiliCli.jsonEscape(m.id()))
              .append("\",\"name\":\"").append(MiliCli.jsonEscape(m.name()))
              .append("\",\"version\":\"").append(MiliCli.jsonEscape(m.version()))
              .append("\",\"source\":\"").append(MiliCli.jsonEscape(m.source()))
              .append("\",\"bound\":").append(m.bound())
              .append(",\"binding\":{\"platform\":\"").append(MiliCli.jsonEscape(m.platform()))
              .append("\",\"abi\":").append(m.abi())
              .append(",\"minecraft\":\"").append(MiliCli.jsonEscape(m.minecraft()))
              .append("\"},\"dependencies\":[");
            for (int j = 0; j < m.dependencies().size(); j++) {
                var d = m.dependencies().get(j);
                if (j > 0) sb.append(',');
                sb.append("{\"modId\":\"").append(MiliCli.jsonEscape(d.getKey()))
                  .append("\",\"required\":").append(d.getValue()).append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // ── 小工具 ──────────────────────────────────────────────────────────

    private int warnCount() {
        return (int) findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
    }

    private int infoCount() {
        return (int) findings.stream().filter(f -> f.severity() == Severity.INFO).count();
    }

    private static String head(List<String> l) {
        return l.size() <= MAX_EXAMPLES_PER_FINDING ? l.toString()
                : l.subList(0, MAX_EXAMPLES_PER_FINDING) + "（共 " + l.size() + " 条）";
    }

    private static String mermaidNode(String id) {
        return "m_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String escapeMermaid(String s) {
        return s.replace("\"", "'");
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    /** 与 ModDiscovery.parseVersionBinding 相同的字段语义。 */
    @SuppressWarnings("unchecked")
    private static VersionBinding parseBinding(Object obj) {
        if (!(obj instanceof Map)) return null;
        Map<String, Object> mili = (Map<String, Object>) obj;
        String platform = str(mili, "platform");
        Object abiObj = mili.get("abi");
        String minecraft = str(mili, "minecraft");
        int abi = 0;
        if (abiObj instanceof Number n) {
            abi = n.intValue();
        } else if (abiObj instanceof String s && !s.isBlank()) {
            try {
                abi = Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        if (platform == null || platform.isBlank()) return null;
        return new VersionBinding(platform, abi, minecraft != null ? minecraft : "");
    }

    /** 与 ModDiscovery.parseDependencies 相同的字段语义。 */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, Boolean>> parseDeps(Object obj) {
        List<Map.Entry<String, Boolean>> deps = new ArrayList<>();
        if (!(obj instanceof List)) return deps;
        for (Object item : (List<Object>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> dep = (Map<String, Object>) item;
            String modId = str(dep, "modId");
            if (modId == null) continue;
            Object req = dep.get("required");
            boolean required = !(req instanceof Boolean b) || b;
            deps.add(Map.entry(modId, required));
        }
        return deps;
    }

    /** 用正式规则重建 ModManifest（供 validateVersionBinding 使用）。 */
    private static ModManifest rebuild(ModEntry m) {
        List<ModManifest.DependencyEntry> deps = m.dependencies().stream()
                .map(d -> new ModManifest.DependencyEntry(d.getKey(), "*", d.getValue()))
                .toList();
        VersionBinding vb = m.bound()
                ? new VersionBinding(m.platform(), m.abi(), m.minecraft())
                : VersionBinding.unbound();
        return new ModManifest(m.id(), m.name(), m.version(), "", "",
                deps, List.of(), "", List.of(), vb);
    }
}