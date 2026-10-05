package org.loader.installer.install;

import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.meta.MojangMetaClient;
import org.loader.installer.meta.PlatformRules;
import org.loader.installer.meta.VersionMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装流程编排。
 *
 * <p>全程<b>幂等</b>：每一步先校验目标文件是否已存在且摘要匹配，重复执行
 * 只补齐缺失部分。600MB 的资源量使这一点成为刚性需求 —— 网络中断后重跑
 * 不应从头再来。
 */
public final class InstallOrchestrator {

    /** 安装选项。 */
    public record Options(
            Path gameDir,
            String versionId,
            boolean skipAssets,
            boolean skipLibraries,
            int assetThreads
    ) {
    }

    /** 安装报告。 */
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
            List<String> warnings,
            boolean ok
    ) {
    }

    private final MojangMetaClient metaClient;
    private final Progress progress;

    public InstallOrchestrator(Progress progress) {
        this(new MojangMetaClient(), progress);
    }

    public InstallOrchestrator(MojangMetaClient metaClient, Progress progress) {
        this.metaClient = metaClient;
        this.progress = progress;
    }

    public Report install(Options options) throws IOException, InterruptedException {
        Path gameDir = options.gameDir().toAbsolutePath().normalize();
        Files.createDirectories(gameDir);

        progress.status("[Mili] 目标目录: " + gameDir);
        progress.status("[Mili] 平台: " + PlatformRules.currentOsName()
                + "/" + PlatformRules.currentArch());

        // ── 1. 元数据 ──────────────────────────────────────────────────────
        progress.status("[Mili] 正在获取官方版本元数据: " + options.versionId());
        VersionMeta meta = metaClient.fetchVersionMeta(options.versionId());
        progress.detail("[Mili] 版本元数据就绪，libraries 共 "
                + meta.libraries().size() + " 项，要求 Java " + meta.javaMajorVersion());

        int applicableLibs = 0;
        for (VersionMeta.Library lib : meta.libraries()) {
            if (lib.isAllowedOnCurrentPlatform()) {
                applicableLibs++;
            }
        }

        // 注意：MojangMetaClient.openStream 额外声明了 InterruptedException，
        // 不能直接以方法引用传给只声明 IOException 的函数式接口。
        VerifiedDownloader downloader = new VerifiedDownloader(url -> {
            try {
                return metaClient.openStream(url);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("下载被中断: " + url, e);
            }
        });

        // ── 2. 客户端 JAR ──────────────────────────────────────────────────
        Path clientJar = gameDir.resolve(meta.id() + ".jar");
        progress.status("[Mili] 正在准备客户端 " + clientJar.getFileName()
                + " (" + Progress.humanBytes(meta.clientSize()) + ")");
        VerifiedDownloader.Outcome clientOutcome =
                downloader.download(clientJar, meta.clientUrl(), meta.clientSha1());
        boolean clientDownloaded = clientOutcome == VerifiedDownloader.Outcome.DOWNLOADED;
        if (clientDownloaded) {
            progress.status("[Mili] 客户端已下载并通过 SHA-1 校验");
        } else {
            progress.status("[Mili] 客户端已存在且校验通过，跳过下载");
        }

        List<String> warnings = new ArrayList<>();

        // ── 3. 客户端日志配置（log4j2 xml）────────────────────────────────
        if (meta.loggingClientUrl() != null) {
            try {
                Path logDir = gameDir.resolve("logs");
                Files.createDirectories(logDir);
                String fileName = meta.loggingClientUrl().substring(
                        meta.loggingClientUrl().lastIndexOf('/') + 1);
                downloader.download(logDir.resolve(fileName),
                        meta.loggingClientUrl(), meta.loggingClientSha1());
            } catch (IOException e) {
                warnings.add("下载客户端日志配置失败（不影响启动）: " + e.getMessage());
            }
        }

        // ── 4. 依赖库 + natives ────────────────────────────────────────────
        int applied = 0;
        int skippedByRule = 0;
        int nativeArchives = 0;
        if (options.skipLibraries()) {
            progress.status("[Mili] 已跳过依赖库安装（--skip-libraries）");
        } else {
            LibraryInstaller libInstaller = new LibraryInstaller(downloader, progress);
            LibraryInstaller.Result res = libInstaller.install(meta, gameDir,
                    applicableLibs, 1);
            applied = res.applied();
            skippedByRule = res.skippedByRule();
            nativeArchives = res.nativeArchives();
            warnings.addAll(res.warnings());
            progress.status("[Mili] 依赖库完成：安装 " + applied
                    + "，按平台规则跳过 " + skippedByRule
                    + "，native 包 " + nativeArchives);
        }

        // ── 5. 资源（assets）───────────────────────────────────────────────
        int assetsTotal = 0;
        int assetsDownloaded = 0;
        int assetsSkipped = 0;
        if (options.skipAssets()) {
            progress.status("[Mili] 已跳过资源安装（--skip-assets）");
            warnings.add("资源未安装：首次进入世界时 Minecraft 会报缺少资源文件");
        } else {
            AssetInstaller assetInstaller =
                    new AssetInstaller(metaClient, downloader, progress, options.assetThreads());
            AssetInstaller.Result res = assetInstaller.install(meta, gameDir,
                    1 + applicableLibs, 1 + applicableLibs);
            assetsTotal = res.total();
            assetsDownloaded = res.downloaded();
            assetsSkipped = res.skipped();
            warnings.addAll(res.warnings());
            progress.status("[Mili] 资源完成：新下载 " + assetsDownloaded
                    + "，已存在 " + assetsSkipped + "，共 " + assetsTotal);
        }

        // ── 6. 报告落盘 ────────────────────────────────────────────────────
        long totalBytes = dirSize(clientJar.getParent());
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
                totalBytes,
                List.copyOf(warnings),
                warnings.stream().noneMatch(w -> w.startsWith("Failed to install library"))
        );

        writeReport(gameDir, report);
        progress.finish();
        return report;
    }

    private void writeReport(Path gameDir, Report report) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"minecraft\": \"").append(report.versionId()).append("\",\n");
        sb.append("  \"javaRequired\": \"").append(report.javaRequired()).append("\",\n");
        sb.append("  \"platform\": \"").append(report.platform()).append("\",\n");
        sb.append("  \"clientJar\": \"").append(report.clientJar()).append("\",\n");
        sb.append("  \"librariesApplied\": ").append(report.librariesApplied()).append(",\n");
        sb.append("  \"librariesSkippedByRule\": ").append(report.librariesSkippedByRule()).append(",\n");
        sb.append("  \"nativeArchives\": ").append(report.nativeArchives()).append(",\n");
        sb.append("  \"assetsTotal\": ").append(report.assetsTotal()).append(",\n");
        sb.append("  \"assetsDownloaded\": ").append(report.assetsDownloaded()).append(",\n");
        sb.append("  \"assetsSkipped\": ").append(report.assetsSkipped()).append(",\n");
        sb.append("  \"totalBytes\": ").append(report.totalBytes()).append(",\n");
        sb.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"ok\": ").append(report.ok()).append(",\n");
        sb.append("  \"warnings\": [");
        for (int i = 0; i < report.warnings().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(report.warnings().get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        sb.append("]\n");
        sb.append("}\n");

        Files.createDirectories(gameDir.resolve("logs"));
        Files.writeString(gameDir.resolve("logs").resolve("install-report.json"),
                sb.toString(), StandardCharsets.UTF_8);
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
