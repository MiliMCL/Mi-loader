package org.loader.installer.install;

import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.json.Json;
import org.loader.installer.meta.MetaSource;
import org.loader.installer.meta.MojangMetaClient;
import org.loader.installer.meta.PlatformRules;
import org.loader.installer.meta.VersionMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安装流程编排。
 *
 * <p>全程<b>幂等</b>：每一步先校验目标文件是否已存在且摘要匹配，重复执行
 * 只补齐缺失部分。600MB 的资源量使这一点成为刚性需求 —— 网络中断后重跑
 * 不应从头再来。
 *
 * <h2>成功判定必须是结构化的</h2>
 *
 * <p>历史实现用警告文本前缀判断安装结果：
 * {@code warnings.stream().noneMatch(w -> w.startsWith("Failed to install library"))}。
 * 这同时造成两种错误：资源下载失败、native 解压失败都不以此开头，于是被
 * 判成成功（安装报告写 {@code ok: true}，CLI 退出码 0）；而只要文案改动，
 * 判定就静默失效。
 *
 * <p>现在每个安装步骤都产出 {@link InstallStep}，每个问题都产出
 * {@link InstallIssue}，{@link Report#ok()} 只由 issue 的结构化
 * {@code severity} 推导。<b>任何地方都不得再读日志文本决定成败。</b>
 */
public final class InstallOrchestrator {

    /**
     * 客户端日志配置（log4j2 xml）是否为必需资源。
     *
     * <p><b>显式策略</b>：缺失它只让日志格式回落到 log4j2 默认配置，
     * 不影响游戏启动与资源完整性，因此按可选处理（WARNING）。
     * 这个开关把「为什么可以吞掉这类失败」写在代码里 —— 而不是靠默认吞掉。
     */
    static final boolean LOGGING_CONFIG_REQUIRED = false;

    /** 安装选项。 */
    public record Options(
            Path gameDir,
            String versionId,
            boolean skipAssets,
            boolean skipLibraries,
            int assetThreads
    ) {
    }

    /**
     * 安装报告。
     *
     * <p>{@code ok()} 由 {@code issues} 推导，因此不存在「报告成功但实际有必需
     * 资源失败」的可能 —— 构造一个自相矛盾的报告在类型层面就做不到。
     */
    public record Report(
            String versionId,
            String javaRequired,
            String platform,
            Path clientJar,
            Path librariesDir,
            Path nativesDir,
            Path assetsDir,
            boolean clientDownloaded,
            int librariesApplied,
            int librariesSkippedByRule,
            int nativeArchives,
            int assetsTotal,
            int assetsDownloaded,
            int assetsSkipped,
            long totalBytes,
            List<InstallStep> steps,
            List<InstallIssue> issues
    ) {
        public Report {
            steps = List.copyOf(steps);
            issues = List.copyOf(issues);
        }

        /** 安装是否成功：仅当没有任何必需资源失败。 */
        public boolean ok() {
            return issues.stream().noneMatch(InstallIssue::isError);
        }

        /** 必需资源失败清单（结构化）。 */
        public List<InstallIssue> failures() {
            return issues.stream().filter(InstallIssue::isError).toList();
        }

        /** 警告清单（展示用文本）。 */
        public List<String> warnings() {
            return issues.stream()
                    .filter(i -> i.severity() == InstallIssue.Severity.WARNING)
                    .map(InstallIssue::describe)
                    .toList();
        }
    }

    private final MetaSource metaSource;
    private final VerifiedDownloader.MojangStreamSource streamSource;
    private final Progress progress;

    public InstallOrchestrator(Progress progress) {
        this(new MojangMetaClient(), progress);
    }

    public InstallOrchestrator(MojangMetaClient metaClient, Progress progress) {
        // MojangMetaClient.openStream 额外声明了 InterruptedException，不能直接
        // 以方法引用传给只声明 IOException 的函数式接口；在这里转换一次。
        this(metaClient, url -> {
            try {
                return metaClient.openStream(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断: " + url, e);
            }
        }, progress);
    }

    /**
     * 可注入的构造器 —— 供离线回归测试复现失败路径（必需库失败、资源失败、
     * 校验不符），生产路径不会用到。
     */
    public InstallOrchestrator(MetaSource metaSource,
                               VerifiedDownloader.MojangStreamSource streamSource,
                               Progress progress) {
        this.metaSource = metaSource;
        this.streamSource = streamSource;
        this.progress = progress;
    }

    public Report install(Options options) throws IOException, InterruptedException {
        Path gameDir = options.gameDir().toAbsolutePath().normalize();
        Files.createDirectories(gameDir);

        progress.status("[Mili] 目标目录: " + gameDir);
        progress.status("[Mili] 平台: " + PlatformRules.currentOsName()
                + "/" + PlatformRules.currentArch());

        List<InstallIssue> issues = new ArrayList<>();
        List<InstallStep> steps = new ArrayList<>();

        // ── 1. 元数据 ──────────────────────────────────────────────────────
        progress.status("[Mili] 正在获取官方版本元数据: " + options.versionId());
        VersionMeta meta;
        try {
            meta = metaSource.fetchVersionMeta(options.versionId());
        } catch (IOException | RuntimeException e) {
            issues.add(InstallIssue.error(InstallIssue.Stage.METADATA, options.versionId(),
                    "获取版本元数据失败: " + e.getMessage(), e));
            steps.add(InstallStep.failed(InstallIssue.Stage.METADATA, "无法获取元数据"));
            Report report = emptyReport(options, steps, issues);
            writeReport(gameDir, report);
            progress.finish();
            return report;
        }
        steps.add(InstallStep.ok(InstallIssue.Stage.METADATA,
                "libraries=" + meta.libraries().size() + ", java=" + meta.javaMajorVersion()));
        progress.detail("[Mili] 版本元数据就绪，libraries 共 "
                + meta.libraries().size() + " 项，要求 Java " + meta.javaMajorVersion());

        int applicableLibs = 0;
        for (VersionMeta.Library lib : meta.libraries()) {
            if (lib.isAllowedOnCurrentPlatform()) {
                applicableLibs++;
            }
        }

        // 注意：下载流来源只声明 IOException —— 中断已在构造期转换为
        // InterruptedIOException 语义，安装主流程只关心「是否失败」。
        VerifiedDownloader downloader = new VerifiedDownloader(streamSource);

        // ── 2. 客户端 JAR（必需）───────────────────────────────────────────
        Path clientJar = gameDir.resolve(meta.id() + ".jar");
        progress.status("[Mili] 正在准备客户端 " + clientJar.getFileName()
                + " (" + Progress.humanBytes(meta.clientSize()) + ")");
        boolean clientDownloaded = false;
        boolean clientReady = false;
        try {
            VerifiedDownloader.Outcome clientOutcome =
                    downloader.download(clientJar, meta.clientUrl(), meta.clientSha1());
            clientDownloaded = clientOutcome == VerifiedDownloader.Outcome.DOWNLOADED;
            clientReady = true;
            steps.add(InstallStep.ok(InstallIssue.Stage.CLIENT_JAR,
                    clientDownloaded ? "已下载并通过 SHA-1 校验" : "已存在且校验通过，跳过下载"));
            if (clientDownloaded) {
                progress.status("[Mili] 客户端已下载并通过 SHA-1 校验");
            } else {
                progress.status("[Mili] 客户端已存在且校验通过，跳过下载");
            }
        } catch (IOException e) {
            issues.add(InstallIssue.error(InstallIssue.Stage.CLIENT_JAR, meta.clientUrl(),
                    "客户端下载或校验失败: " + e.getMessage(), e));
            steps.add(InstallStep.failed(InstallIssue.Stage.CLIENT_JAR, "客户端不可用"));
        }

        if (!clientReady) {
            // 客户端缺失时继续装库与资源没有意义，且会把「装了半套」的目录
            // 当成可用环境。直接停止后续阶段，并在报告里写明原因。
            steps.add(InstallStep.skipped(InstallIssue.Stage.LIBRARY, "客户端缺失，未继续"));
            steps.add(InstallStep.skipped(InstallIssue.Stage.ASSET_INDEX, "客户端缺失，未继续"));
            Report report = new Report(meta.id(), meta.javaMajorVersion(),
                    PlatformRules.currentOsName() + "/" + PlatformRules.currentArch(),
                    clientJar, gameDir.resolve("libraries"), gameDir.resolve("natives"),
                    gameDir.resolve("assets"), clientDownloaded, 0, 0, 0, 0, 0, 0,
                    dirSize(gameDir), steps, issues);
            writeReport(gameDir, report);
            progress.finish();
            return report;
        }

        // ── 3. 客户端日志配置（可选，见 LOGGING_CONFIG_REQUIRED）───────────
        if (meta.loggingClientUrl() != null) {
            try {
                Path logDir = gameDir.resolve("logs");
                Files.createDirectories(logDir);
                String fileName = meta.loggingClientUrl().substring(
                        meta.loggingClientUrl().lastIndexOf('/') + 1);
                downloader.download(logDir.resolve(fileName),
                        meta.loggingClientUrl(), meta.loggingClientSha1());
                steps.add(InstallStep.ok(InstallIssue.Stage.LOGGING_CONFIG, fileName));
            } catch (IOException e) {
                InstallIssue issue = LOGGING_CONFIG_REQUIRED
                        ? InstallIssue.error(InstallIssue.Stage.LOGGING_CONFIG,
                                meta.loggingClientUrl(),
                                "下载客户端日志配置失败: " + e.getMessage(), e)
                        : InstallIssue.warning(InstallIssue.Stage.LOGGING_CONFIG,
                                meta.loggingClientUrl(),
                                "下载客户端日志配置失败（可选资源，不影响启动）: "
                                        + e.getMessage(), e);
                issues.add(issue);
                steps.add(LOGGING_CONFIG_REQUIRED
                        ? InstallStep.failed(InstallIssue.Stage.LOGGING_CONFIG, "可选策略=必需")
                        : InstallStep.degraded(InstallIssue.Stage.LOGGING_CONFIG,
                                "可选资源失败，按显式策略降级为警告"));
            }
        }

        // ── 4. 依赖库 + natives（必需）─────────────────────────────────────
        int applied = 0;
        int skippedByRule = 0;
        int nativeArchives = 0;
        if (options.skipLibraries()) {
            progress.status("[Mili] 已跳过依赖库安装（--skip-libraries）");
            issues.add(InstallIssue.warning(InstallIssue.Stage.LIBRARY, "-",
                    "按 --skip-libraries 显式跳过依赖库安装，安装结果不完整"));
            steps.add(InstallStep.skipped(InstallIssue.Stage.LIBRARY, "--skip-libraries"));
        } else {
            LibraryInstaller libInstaller = new LibraryInstaller(downloader, progress);
            LibraryInstaller.Result res = libInstaller.install(meta, gameDir,
                    applicableLibs, 1);
            applied = res.applied();
            skippedByRule = res.skippedByRule();
            nativeArchives = res.nativeArchives();
            issues.addAll(res.issues());
            steps.add(res.hasFailures()
                    ? InstallStep.failed(InstallIssue.Stage.LIBRARY,
                            "安装 " + applied + "，失败 "
                                    + res.issues().stream().filter(InstallIssue::isError).count())
                    : InstallStep.ok(InstallIssue.Stage.LIBRARY,
                            "安装 " + applied + "，按平台规则跳过 " + skippedByRule
                                    + "，native 包 " + nativeArchives));
            progress.status("[Mili] 依赖库完成：安装 " + applied
                    + "，按平台规则跳过 " + skippedByRule
                    + "，native 包 " + nativeArchives);
        }

        // ── 5. 资源（assets，必需）────────────────────────────────────────
        int assetsTotal = 0;
        int assetsDownloaded = 0;
        int assetsSkipped = 0;
        if (options.skipAssets()) {
            progress.status("[Mili] 已跳过资源安装（--skip-assets）");
            issues.add(InstallIssue.warning(InstallIssue.Stage.ASSET_INDEX, "-",
                    "按 --skip-assets 显式跳过资源安装；首次进入世界时 Minecraft 会报缺少资源文件"));
            steps.add(InstallStep.skipped(InstallIssue.Stage.ASSET_INDEX, "--skip-assets"));
        } else {
            AssetInstaller assetInstaller =
                    new AssetInstaller(downloader, progress, options.assetThreads());
            AssetInstaller.Result res = assetInstaller.install(meta, gameDir,
                    1 + applicableLibs, 1 + applicableLibs);
            assetsTotal = res.total();
            assetsDownloaded = res.downloaded();
            assetsSkipped = res.skipped();
            issues.addAll(res.issues());
            steps.add(res.hasFailures()
                    ? InstallStep.failed(InstallIssue.Stage.ASSET_OBJECT,
                            "共 " + assetsTotal + "，失败 "
                                    + res.issues().stream().filter(InstallIssue::isError).count())
                    : InstallStep.ok(InstallIssue.Stage.ASSET_OBJECT,
                            "共 " + assetsTotal + "，新增 " + assetsDownloaded
                                    + "，已存在 " + assetsSkipped));
            progress.status("[Mili] 资源完成：新下载 " + assetsDownloaded
                    + "，已存在 " + assetsSkipped + "，共 " + assetsTotal);
        }

        // ── 6. 报告落盘 ────────────────────────────────────────────────────
        Report report = new Report(
                meta.id(),
                meta.javaMajorVersion(),
                PlatformRules.currentOsName() + "/" + PlatformRules.currentArch(),
                clientJar,
                gameDir.resolve("libraries"),
                gameDir.resolve("natives"),
                gameDir.resolve("assets"),
                clientDownloaded,
                applied, skippedByRule, nativeArchives,
                assetsTotal, assetsDownloaded, assetsSkipped,
                dirSize(gameDir),
                steps,
                issues
        );

        writeReport(gameDir, report);
        progress.finish();
        return report;
    }

    /** 元数据阶段就失败时的报告骨架：其余字段没有可信值，一律填 0/空。 */
    private Report emptyReport(Options options, List<InstallStep> steps, List<InstallIssue> issues) {
        Path gameDir = options.gameDir().toAbsolutePath().normalize();
        return new Report(options.versionId(), "unknown",
                PlatformRules.currentOsName() + "/" + PlatformRules.currentArch(),
                gameDir.resolve(options.versionId() + ".jar"),
                gameDir.resolve("libraries"), gameDir.resolve("natives"),
                gameDir.resolve("assets"), false, 0, 0, 0, 0, 0, 0, 0,
                steps, issues);
    }

    /**
     * 写出安装报告。
     *
     * <p><b>必须走序列化器</b>：报告含文件路径。Windows 路径的 {@code \}
     * 在 JSON 里是转义引导符，手工拼接会产出 {@code "C:\Users\x"} 这种非法
     * 文档（{@code \U} 不是合法转义），下游解析直接失败。引号、换行与控制
     * 字符同理。
     */
    private void writeReport(Path gameDir, Report report) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("minecraft", report.versionId());
        root.put("javaRequired", report.javaRequired());
        root.put("platform", report.platform());
        // 显式转字符串：Path 不是可序列化类型，直接交给序列化器会被拒绝
        // （这是刻意的 —— 静默 toString() 正是产出非法 JSON 的来源）。
        root.put("clientJar", report.clientJar().toString());
        root.put("librariesApplied", report.librariesApplied());
        root.put("librariesSkippedByRule", report.librariesSkippedByRule());
        root.put("nativeArchives", report.nativeArchives());
        root.put("assetsTotal", report.assetsTotal());
        root.put("assetsDownloaded", report.assetsDownloaded());
        root.put("assetsSkipped", report.assetsSkipped());
        root.put("totalBytes", report.totalBytes());
        root.put("timestamp", Instant.now().toString());
        root.put("ok", report.ok());

        List<Object> steps = new ArrayList<>();
        for (InstallStep step : report.steps()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("stage", step.stage().name());
            node.put("status", step.status().name());
            node.put("detail", step.detail());
            steps.add(node);
        }
        root.put("steps", steps);

        List<Object> issues = new ArrayList<>();
        for (InstallIssue issue : report.issues()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("severity", issue.severity().name());
            node.put("stage", issue.stage().name());
            node.put("resource", issue.resourceId());
            node.put("message", issue.message());
            if (issue.cause() != null) {
                node.put("cause", issue.cause().getClass().getName());
            }
            issues.add(node);
        }
        root.put("issues", issues);
        // 兼容旧字段：仍有消费者按 warnings 数组读取
        root.put("warnings", report.warnings());

        Files.createDirectories(gameDir.resolve("logs"));
        Files.writeString(gameDir.resolve("logs").resolve("install-report.json"),
                Json.writePretty(root) + "\n", StandardCharsets.UTF_8);
    }

    private static long dirSize(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
