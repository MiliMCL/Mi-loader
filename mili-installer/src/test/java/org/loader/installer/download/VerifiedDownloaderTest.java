package org.loader.installer.download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedDownloaderTest {

    private static final String CONTENT = "Minecraft installer payload 中文测试";
    private static final String SHA1 = VerifiedDownloader.sha1OfBytes(
            CONTENT.getBytes(StandardCharsets.UTF_8));

    /** 记录每个 URL 被请求了几次，用于验证幂等跳过。 */
    private static final class CountingSource
            implements VerifiedDownloader.MojangStreamSource {
        private final Map<String, byte[]> responses;
        private final Map<String, String> corrupt;
        final AtomicInteger requests = new AtomicInteger();

        CountingSource(Map<String, byte[]> responses, Map<String, String> corrupt) {
            this.responses = responses;
            this.corrupt = corrupt;
        }

        @Override
        public InputStream open(String url) throws IOException {
            requests.incrementAndGet();
            if (corrupt.containsKey(url)) {
                throw new IOException("simulated network failure: " + url);
            }
            byte[] body = responses.get(url);
            if (body == null) {
                throw new IOException("no such resource: " + url);
            }
            return new ByteArrayInputStream(body);
        }
    }

    private static Map<String, byte[]> okResponses() {
        return Map.of("https://mc/client.jar",
                CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void downloadsAndVerifies(@TempDir Path tmp) throws IOException {
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        Path target = tmp.resolve("client.jar");

        assertEquals(VerifiedDownloader.Outcome.DOWNLOADED,
                dl.download(target, "https://mc/client.jar", SHA1));

        assertTrue(Files.exists(target));
        assertEquals(CONTENT, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void skipsWhenAlreadyValid(@TempDir Path tmp) throws IOException {
        var source = new CountingSource(okResponses(), Map.of());
        var dl = new VerifiedDownloader(source, 16);
        Path target = tmp.resolve("client.jar");

        dl.download(target, "https://mc/client.jar", SHA1);
        int afterFirst = source.requests.get();

        assertEquals(VerifiedDownloader.Outcome.SKIPPED,
                dl.download(target, "https://mc/client.jar", SHA1));
        // 第二次完全没发起网络请求
        assertEquals(afterFirst, source.requests.get());
    }

    @Test
    void redownloadsWhenChecksumMismatch(@TempDir Path tmp) throws IOException {
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        Path target = tmp.resolve("client.jar");

        Files.writeString(target, "corrupted existing content");

        assertEquals(VerifiedDownloader.Outcome.DOWNLOADED,
                dl.download(target, "https://mc/client.jar", SHA1));
        assertEquals(CONTENT, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsWrongChecksum(@TempDir Path tmp) {
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        Path target = tmp.resolve("client.jar");

        IOException e = assertThrows(IOException.class, () ->
                dl.download(target, "https://mc/client.jar", "deadbeef"));
        assertTrue(e.getMessage().contains("SHA-1 mismatch"));
    }

    @Test
    void noPartFileLeftBehindOnChecksumFailure(@TempDir Path tmp) throws IOException {
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        Path target = tmp.resolve("client.jar");

        assertThrows(IOException.class, () ->
                dl.download(target, "https://mc/client.jar", "deadbeef"));

        // 关键：失败后既不能留下目标文件，也不能留下任何临时残骸
        assertFalse(Files.exists(target));
        var leftovers = partFiles(tmp);
        assertTrue(leftovers.isEmpty(), () -> "残留临时文件: " + leftovers);
    }

    @Test
    void noPartFileLeftBehindOnNetworkFailure(@TempDir Path tmp) throws IOException {
        var source = new CountingSource(Map.of(),
                Map.of("https://mc/client.jar", "boom"));
        var dl = new VerifiedDownloader(source, 16);
        Path target = tmp.resolve("client.jar");

        assertThrows(IOException.class, () ->
                dl.download(target, "https://mc/client.jar", SHA1));

        assertFalse(Files.exists(target));
        var leftovers = partFiles(tmp);
        assertTrue(leftovers.isEmpty(), () -> "残留临时文件: " + leftovers);
    }

    /**
     * 校验失败不能把已有的目标文件毁掉。
     *
     * <p>历史实现在发现摘要不符时先 {@code deleteIfExists(target)} 再下载；
     * 一旦下载失败，用户就从「有个坏文件」变成「什么都没有」，且没有任何
     * 日志能说明文件是被安装器删掉的。现在的语义是：新文件校验通过才替换。
     */
    @Test
    void failedDownloadKeepsExistingTargetIntact(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve("client.jar");
        Files.writeString(target, "existing-but-stale", StandardCharsets.UTF_8);

        // 服务器返回的内容与期望摘要不符 → 必然失败
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        assertThrows(IOException.class, () ->
                dl.download(target, "https://mc/client.jar", "deadbeef"));

        assertTrue(Files.exists(target), "失败不应删除已有目标文件");
        assertEquals("existing-but-stale", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(partFiles(tmp).isEmpty());
    }

    /**
     * 并发下载同一目标：各自写独立临时文件，最终文件必须完整且校验通过。
     *
     * <p>固定 {@code <target>.part} 时，两个线程会截断并交错写同一个文件，
     * 摘要必然错；这条用例就是那个缺陷的回归。
     */
    @Test
    void concurrentDownloadsOfSameTargetDoNotCorruptEachOther(@TempDir Path tmp) throws Exception {
        byte[] body = CONTENT.getBytes(StandardCharsets.UTF_8);
        var dl = new VerifiedDownloader(url -> new ByteArrayInputStream(body), 8);
        Path target = tmp.resolve("client.jar");

        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> dl.download(target, "https://mc/client.jar", SHA1)));
            }
            for (var f : futures) {
                f.get();   // 任何一路失败都会在这里抛出
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(SHA1, VerifiedDownloader.sha1Of(target));
        var leftovers = partFiles(tmp);
        assertTrue(leftovers.isEmpty(), () -> "残留临时文件: " + leftovers);
    }

    /** 目录下所有 {@code .part*} 临时文件。 */
    private static java.util.List<Path> partFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().contains(".part"))
                    .toList();
        }
    }

    @Test
    void createsParentDirectories(@TempDir Path tmp) throws IOException {
        var dl = new VerifiedDownloader(new CountingSource(okResponses(), Map.of()), 16);
        Path target = tmp.resolve("a/b/c/client.jar");

        dl.download(target, "https://mc/client.jar", SHA1);
        assertTrue(Files.exists(target));
    }

    @Test
    void multiByteContentSurvivesSmallBuffers(@TempDir Path tmp) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("行").append(i).append('\n');
        }
        String big = sb.toString();
        String bigSha = VerifiedDownloader.sha1OfBytes(big.getBytes(StandardCharsets.UTF_8));

        var dl = new VerifiedDownloader(url ->
                new ByteArrayInputStream(big.getBytes(StandardCharsets.UTF_8)), 7);
        Path target = tmp.resolve("big.txt");

        dl.download(target, "https://mc/big", bigSha);
        assertEquals(big, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void sha1OfFileMatchesBytesHelper(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("x.bin");
        Files.writeString(f, CONTENT, StandardCharsets.UTF_8);
        assertEquals(SHA1, VerifiedDownloader.sha1Of(f));
        assertNotEquals("deadbeef", SHA1);
    }
}
