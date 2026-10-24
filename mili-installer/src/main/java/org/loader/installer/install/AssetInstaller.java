package org.loader.installer.install;

import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.json.Json;
import org.loader.installer.meta.VersionMeta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资源（assets）安装器 —— 26.2 约 480MB，是整个安装流程里最大的一块。
 *
 * <p>Mojang 从 1.7 起把资源改成「虚拟文件系统」：不再提供 assets 目录的
 * 整包下载，而是给一份索引（{@code assets/indexes/32.json}），里面每个资源
 * 映射到 {@code objects/<sha1 前两位>/<完整 sha1>}。因此必须逐个对象下载。
 *
 * <p>并发策略：固定小线程池（默认 8），既能在有限带宽下压满，又不会对
 * Mojang CDN 造成突发压力或耗尽本机文件句柄。
 */
public final class AssetInstaller {

    /** 单个资源的索引条目。 */
    private record AssetObject(String hash, int size) {
    }

    private final VerifiedDownloader downloader;
    private final Progress progress;
    private final int threads;

    public AssetInstaller(VerifiedDownloader downloader, Progress progress) {
        this(downloader, progress, 8);
    }

    /**
     * @param downloader 已注入流来源的下载器（测试可替换为离线实现）
     * @param progress   进度回调
     * @param threads    并发数，下限 1
     *
     * <p>注：历史签名额外接收一个 {@code MojangMetaClient}，但它从未被使用
     * （资源 URL 完全由 sha1 推出）。那个参数已移除 —— 保留一个不参与逻辑的
     * 依赖会让人误以为资源下载走的是元数据客户端。
     */
    public AssetInstaller(VerifiedDownloader downloader,
                          Progress progress,
                          int threads) {
        this.downloader = downloader;
        this.progress = progress;
        this.threads = Math.max(1, threads);
    }

    public record Result(int total, int downloaded, int skipped, List<InstallIssue> issues) {

        public Result {
            issues = List.copyOf(issues);
        }

        public boolean hasFailures() {
            return issues.stream().anyMatch(InstallIssue::isError);
        }

        /** 警告文本（展示用）。 */
        public List<String> warnings() {
            return issues.stream()
                    .filter(i -> i.severity() == InstallIssue.Severity.WARNING)
                    .map(InstallIssue::describe)
                    .toList();
        }
    }

    /**
     * 下载全部资源对象。
     *
     * <p><b>失败语义</b>：资源是启动必需项 —— 缺对象会在进世界时报缺文件、
     * 纹理变紫黑。因此索引下载/解析失败与任一对象下载失败都记为
     * {@link InstallIssue.Severity#ERROR}，由 {@link InstallOrchestrator}
     * 汇总为整体失败，而不是塞进 warnings 里让安装报告写 {@code ok: true}。
     *
     * @param meta    版本元数据（须含 assetIndex）
     * @param gameDir 游戏目录，其下创建 {@code assets/}
     * @param doneBase 进度起始计数
     * @param libsTotal 进度分母（libraries 与 assets 合并计数）
     */
    public Result install(VersionMeta meta, Path gameDir, int doneBase, int libsTotal)
            throws IOException, InterruptedException {
        VersionMeta.AssetIndex index = meta.assetIndex();
        if (index == null) {
            // 没有 assetIndex 意味着这份元数据本身不完整：资源无从安装，
            // 这不是「跳过」，是失败。旧实现返回 warning 并让整体判定为成功。
            return new Result(0, 0, 0, List.of(InstallIssue.error(
                    InstallIssue.Stage.ASSET_INDEX, meta.id(),
                    "版本元数据缺少 assetIndex，无法安装资源")));
        }

        // 索引本身也要校验 —— 它决定后续 480MB 的完整性
        Path indexFile = gameDir.resolve("assets").resolve("indexes").resolve(index.id() + ".json");
        try {
            downloader.download(indexFile, index.url(), index.sha1());
        } catch (IOException e) {
            return new Result(0, 0, 0, List.of(InstallIssue.error(
                    InstallIssue.Stage.ASSET_INDEX, index.id(),
                    "资源索引下载或校验失败: " + e.getMessage(), e)));
        }

        Map<String, AssetObject> objects;
        int invalidHashes;
        try {
            ParsedIndex parsed = parseIndex(indexFile);
            objects = parsed.objects();
            invalidHashes = parsed.invalidHashes();
        } catch (IOException | org.loader.installer.json.JsonException e) {
            return new Result(0, 0, 0, List.of(InstallIssue.error(
                    InstallIssue.Stage.ASSET_INDEX, index.id(),
                    "资源索引解析失败: " + e.getMessage(), e)));
        }
        if (objects.isEmpty()) {
            return new Result(0, 0, 0, List.of(InstallIssue.error(
                    InstallIssue.Stage.ASSET_INDEX, index.id(),
                    "资源索引为空（网络异常或索引损坏），请重试")));
        }

        List<InstallIssue> preIssues = new ArrayList<>();
        if (invalidHashes > 0) {
            // hash 会直接拼进 assets/objects/<前两位>/<hash> 路径。非法 hash
            // （含 ../ 或长度不对）可以越出 assets 目录写文件，因此必须在
            // 解析期拒绝，而不是等到 write 的时候。
            preIssues.add(InstallIssue.error(InstallIssue.Stage.ASSET_INDEX, index.id(),
                    "索引含 " + invalidHashes + " 个非法 hash 条目，已拒绝（防止路径穿越）"));
        }

        Path objectsRoot = gameDir.resolve("assets").resolve("objects");
        Files.createDirectories(objectsRoot);

        progress.status("[Mili] 正在下载资源：共 " + objects.size() + " 个对象，约 "
                + Progress.humanBytes(index.totalSize()));

        AtomicInteger done = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<InstallIssue> issues = java.util.Collections.synchronizedList(
                new ArrayList<>(preIssues));

        List<AssetObject> queue = new ArrayList<>(objects.values());
        int total = queue.size();
        int grandTotal = libsTotal > 0 ? libsTotal : total;

        ExecutorService pool = Executors.newFixedThreadPool(threads, namedDaemonFactory());
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (AssetObject obj : queue) {
                futures.add(pool.submit(() -> {
                    Path target = objectsRoot
                            .resolve(obj.hash().substring(0, 2))
                            .resolve(obj.hash());
                    try {
                        VerifiedDownloader.Outcome outcome =
                                downloader.download(target, objectUrl(obj.hash()), obj.hash());
                        if (outcome == VerifiedDownloader.Outcome.DOWNLOADED) {
                            downloaded.incrementAndGet();
                            bytes.addAndGet(obj.size());
                        } else {
                            skipped.incrementAndGet();
                            bytes.addAndGet(obj.size());
                        }
                    } catch (IOException e) {
                        issues.add(InstallIssue.error(InstallIssue.Stage.ASSET_OBJECT,
                                obj.hash(), "资源下载或校验失败: " + e.getMessage(), e));
                    } finally {
                        int n = done.incrementAndGet();
                        // 资源对象极多，节流刷新，避免进度条本身成为瓶颈
                        if (n % 64 == 0 || n == total) {
                            progress.file("assets", doneBase + n, grandTotal, bytes.get(), 0);
                        }
                    }
                }));
            }
            // Each task already traps its own IOException into `issues`, so a
            // throw here can only be interruption. Propagate it rather than
            // silently reporting a truncated install as complete.
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    issues.add(InstallIssue.error(InstallIssue.Stage.ASSET_OBJECT, "-",
                            "资源下载任务异常终止: " + (e.getCause() == null ? e : e.getCause()),
                            e.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        progress.file("assets", doneBase + total, grandTotal, bytes.get(), 0);
        return new Result(total, downloaded.get(), skipped.get(), issues);
    }

    private static String objectUrl(String sha1) {
        return "https://resources.download.minecraft.net/" + sha1.substring(0, 2) + "/" + sha1;
    }

    /** 解析资源索引：{@code {"objects": {"<sha1>": {"hash":..,"size":..}}}}。 */
    private static ParsedIndex parseIndex(Path indexFile) throws IOException {
        String text = Files.readString(indexFile, StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(text);
        Map<String, Object> rawObjects = Json.obj(root, "objects");
        Map<String, AssetObject> out = new LinkedHashMap<>();
        if (rawObjects == null) {
            return new ParsedIndex(out, 0);
        }
        int invalid = 0;
        for (Map.Entry<String, Object> e : rawObjects.entrySet()) {
            String hash = Json.str(e.getValue(), "hash");
            if (hash == null) {
                continue;
            }
            // hash 直接决定落盘路径（objects/<前两位>/<hash>），必须是纯
            // 十六进制 sha1；任何其它形态（含 ../、绝对路径、超长）都拒绝。
            if (!isSha1Hex(hash)) {
                invalid++;
                continue;
            }
            Integer size = Json.integer(e.getValue(), "size");
            String canonical = hash.toLowerCase(java.util.Locale.ROOT);
            out.put(canonical, new AssetObject(canonical, size == null ? 0 : size));
        }
        return new ParsedIndex(out, invalid);
    }

    private static boolean isSha1Hex(String s) {
        if (s.length() != 40) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** 索引解析结果：合法对象 + 被拒绝的非法条目数。 */
    private record ParsedIndex(Map<String, AssetObject> objects, int invalidHashes) {
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "mili-asset-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
