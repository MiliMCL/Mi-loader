package org.loader.installer.install;

import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.json.Json;
import org.loader.installer.meta.MojangMetaClient;
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

    private final MojangMetaClient metaClient;
    private final VerifiedDownloader downloader;
    private final Progress progress;
    private final int threads;

    public AssetInstaller(MojangMetaClient metaClient,
                          VerifiedDownloader downloader,
                          Progress progress) {
        this(metaClient, downloader, progress, 8);
    }

    public AssetInstaller(MojangMetaClient metaClient,
                          VerifiedDownloader downloader,
                          Progress progress,
                          int threads) {
        this.metaClient = metaClient;
        this.downloader = downloader;
        this.progress = progress;
        this.threads = Math.max(1, threads);
    }

    public record Result(int total, int downloaded, int skipped, List<String> warnings) {
    }

    /**
     * 下载全部资源对象。
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
            return new Result(0, 0, 0, List.of("版本元数据缺少 assetIndex，已跳过资源安装"));
        }

        // 索引本身也要校验 —— 它决定后续 480MB 的完整性
        Path indexFile = gameDir.resolve("assets").resolve("indexes").resolve(index.id() + ".json");
        downloader.download(indexFile, index.url(), index.sha1());

        Map<String, AssetObject> objects = parseIndex(indexFile);
        if (objects.isEmpty()) {
            return new Result(0, 0, 0, List.of("资源索引为空，请检查网络或稍后重试"));
        }

        Path objectsRoot = gameDir.resolve("assets").resolve("objects");
        Files.createDirectories(objectsRoot);

        progress.status("[Mili] 正在下载资源：共 " + objects.size() + " 个对象，约 "
                + Progress.humanBytes(index.totalSize()));

        AtomicInteger done = new AtomicInteger();
        AtomicInteger downloaded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());

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
                        warnings.add("资源下载失败 " + obj.hash() + ": " + e.getMessage());
                    } finally {
                        int n = done.incrementAndGet();
                        // 资源对象极多，节流刷新，避免进度条本身成为瓶颈
                        if (n % 64 == 0 || n == total) {
                            progress.file("assets", doneBase + n, grandTotal, bytes.get(), 0);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        progress.file("assets", doneBase + total, grandTotal, bytes.get(), 0);
        return new Result(total, downloaded.get(), skipped.get(), List.copyOf(warnings));
    }

    private static String objectUrl(String sha1) {
        return "https://resources.download.minecraft.net/" + sha1.substring(0, 2) + "/" + sha1;
    }

    /** 解析资源索引：{@code {"objects": {"<sha1>": {"hash":..,"size":..}}}}。 */
    private static Map<String, AssetObject> parseIndex(Path indexFile) throws IOException {
        String text = Files.readString(indexFile, StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(text);
        Map<String, Object> rawObjects = Json.obj(root, "objects");
        Map<String, AssetObject> out = new LinkedHashMap<>();
        if (rawObjects == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : rawObjects.entrySet()) {
            String hash = Json.str(e.getValue(), "hash");
            Integer size = Json.integer(e.getValue(), "size");
            if (hash == null) {
                continue;
            }
            out.put(hash, new AssetObject(hash, size == null ? 0 : size));
        }
        return out;
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
