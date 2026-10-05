package org.loader.installer;

import org.loader.installer.download.Progress;
import org.loader.installer.install.InstallOrchestrator;

import java.nio.file.Path;
import java.util.List;

/**
 * 安装器命令行入口。
 *
 * <p>用法：
 * <pre>
 *   java -jar mili-installer.jar [--game-dir DIR] [--version V]
 *                                [--skip-assets] [--skip-libraries]
 *                                [--asset-threads N] [--dry-run]
 * </pre>
 *
 * <p>设计约束：只把环境装好，<b>不做账号认证</b>。登录仍由 Minecraft 官方
 * 流程完成 —— 安装器无法也不应绕过它。
 */
public final class InstallerMain {

    private static final String DEFAULT_VERSION = "26.2";

    private InstallerMain() {
    }

    public static void main(String[] args) {
        Options opts;
        try {
            opts = parseArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[Mili] 参数错误: " + e.getMessage());
            printUsage();
            System.exit(2);
            return;
        }

        if (opts.help) {
            printUsage();
            return;
        }

        String requiredJava = detectRequiredJava(opts.version);
        String actualJava = System.getProperty("java.version", "?");
        if (requiredJava != null && !requiredJava.equals(actualJava)) {
            // 仅提示，不阻断：JVM 通常可以运行目标版本以下字节码，
            // 但 MC 26.2 使用了 Java 25 的 preview 特性，实际仍建议匹配。
            System.out.println("[Mili] 提示: Minecraft " + opts.version
                    + " 建议使用 Java " + requiredJava + "，当前为 Java " + actualJava);
        }

        Progress progress = Progress.create(System.out);
        InstallOrchestrator orchestrator = new InstallOrchestrator(progress);

        if (opts.dryRun) {
            System.out.println("[Mili] 试运行模式：只解析元数据，不下载任何文件。");
            try {
                var meta = new org.loader.installer.meta.MojangMetaClient()
                        .fetchVersionMeta(opts.version);
                long libs = meta.libraries().stream()
                        .filter(l -> l.isAllowedOnCurrentPlatform())
                        .count();
                System.out.println("[Mili] 版本: " + meta.id()
                        + "，要求 Java " + meta.javaMajorVersion());
                System.out.println("[Mili] 客户端: " + org.loader.installer.download.Progress
                        .humanBytes(meta.clientSize()));
                System.out.println("[Mili] 依赖库: 共 " + meta.libraries().size()
                        + "，本平台适用 " + libs);
                if (meta.assetIndex() != null) {
                    System.out.println("[Mili] 资源: " + meta.assetIndex().totalSize() / (1024 * 1024)
                            + " MB (index " + meta.assetIndex().id() + ")");
                }
                System.out.println("[Mili] 目标目录: " + opts.gameDir.toAbsolutePath().normalize());
            } catch (Exception e) {
                System.err.println("[Mili] 试运行失败: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        try {
            InstallOrchestrator.Report report = orchestrator.install(
                    new InstallOrchestrator.Options(
                            opts.gameDir,
                            opts.version,
                            opts.skipAssets,
                            opts.skipLibraries,
                            opts.assetThreads));

            printSummary(report);
            System.exit(report.ok() ? 0 : 1);
        } catch (Exception e) {
            progress.finish();
            System.err.println("[Mili] 安装失败: " + e.getMessage());
            if (System.getenv("MILI_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static void printSummary(InstallOrchestrator.Report r) {
        System.out.println();
        System.out.println("========================================");
        System.out.println(" Mili 安装完成");
        System.out.println("========================================");
        System.out.println(" Minecraft 版本 : " + r.versionId() + "  (Java " + r.javaRequired() + ")");
        System.out.println(" 平台         : " + r.platform());
        System.out.println(" 客户端       : " + r.clientJar().getFileName());
        System.out.println(" 依赖库       : " + r.librariesApplied() + " 个已安装");
        if (r.librariesSkippedByRule() > 0) {
            System.out.println("               " + r.librariesSkippedByRule() + " 个按平台规则跳过");
        }
        if (r.nativeArchives() > 0) {
            System.out.println(" native       : " + r.nativeArchives() + " 个已释放");
        }
        if (r.assetsTotal() > 0) {
            System.out.println(" 资源         : " + r.assetsTotal() + " 个（新增 "
                    + r.assetsDownloaded() + "，已有 " + r.assetsSkipped() + "）");
        } else if (r.assetsDownloaded() == 0) {
            System.out.println(" 资源         : 未安装");
        }
        System.out.println(" 目录总大小   : "
                + Progress.humanBytes(r.totalBytes()));
        if (!r.warnings().isEmpty()) {
            System.out.println(" 警告         : " + r.warnings().size() + " 条");
            for (String w : r.warnings()) {
                System.out.println("   - " + w);
            }
        }
        System.out.println("========================================");
        System.out.println("接下来运行加载器启动 Minecraft，登录使用你的正版账号。");
        System.out.println("========================================");
    }

    private static String detectRequiredJava(String version) {
        try {
            return new org.loader.installer.meta.MojangMetaClient()
                    .fetchVersionMeta(version).javaMajorVersion();
        } catch (Exception e) {
            return null;
        }
    }

    // ── 参数解析 ────────────────────────────────────────────────────────────

    private static final class Options {
        Path gameDir = Path.of(".");
        String version = DEFAULT_VERSION;
        boolean skipAssets;
        boolean skipLibraries;
        int assetThreads = 8;
        boolean dryRun;
        boolean help;
    }

    private static Options parseArgs(String[] args) {
        Options o = new Options();
        List<String> positional = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--help", "-h" -> o.help = true;
                case "--skip-assets" -> o.skipAssets = true;
                case "--skip-libraries" -> o.skipLibraries = true;
                case "--dry-run" -> o.dryRun = true;
                case "--game-dir" -> o.gameDir = Path.of(requireValue(args, ++i, "--game-dir"));
                case "--version", "-v" -> o.version = requireValue(args, ++i, "--version");
                case "--asset-threads" -> {
                    String v = requireValue(args, ++i, "--asset-threads");
                    try {
                        o.assetThreads = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--asset-threads 需要是整数: " + v);
                    }
                    if (o.assetThreads < 1 || o.assetThreads > 32) {
                        throw new IllegalArgumentException("--asset-threads 取值范围 1..32");
                    }
                }
                default -> {
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("未知参数: " + a);
                    }
                    positional.add(a);
                }
            }
        }
        // 位置参数：第一个当作 gameDir（方便 `java -jar installer.jar ./mc`）
        if (!positional.isEmpty() && o.gameDir.equals(Path.of("."))) {
            o.gameDir = Path.of(positional.get(0));
        }
        return o;
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少参数值");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
            Mili Minecraft 安装器

            用法:
              java -jar mili-installer.jar [选项] [游戏目录]

            选项:
              --game-dir DIR       安装目标目录（默认当前目录）
              --version, -v V      Minecraft 版本（默认 26.2）
              --skip-assets        跳过资源下载（约省 480MB，但进世界会缺资源）
              --skip-libraries     跳过依赖库下载
              --asset-threads N    资源并发数 1..32（默认 8）
              --dry-run            只查询元数据并打印体积，不下载
              --help, -h           显示本帮助

            说明:
              安装器从 Mojang 官方 CDN 拉取游戏文件并逐个校验 SHA-1。
              全程幂等，重复执行只补齐缺失部分。
              安装器不处理账号登录，登录请使用你的正版账号。
            """);
    }
}
