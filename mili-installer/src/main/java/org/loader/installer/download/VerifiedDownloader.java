package org.loader.installer.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 带 SHA-1 校验的流式下载器。
 *
 * <p>三条关键设计：
 * <ul>
 *   <li><b>流式落盘</b>：边下边算摘要，不把 39MB 的 client.jar 整体读进内存
 *       （assets 单文件可到数十 MB，必须流式）；</li>
 *   <li><b>原子替换</b>：先写 {@code .part} 临时文件，校验通过才 rename 到最终
 *       路径 —— 断网或磁盘写满时不会留下「看起来存在但已损坏」的目标文件；</li>
 *   <li><b>幂等跳过</b>：目标文件已存在且摘要匹配时直接返回，重复执行安装
 *       几乎零开销（这是 600MB assets 场景的必需品）。</li>
 * </ul>
 */
public final class VerifiedDownloader {

    private static final HexFormat HEX = HexFormat.of();

    /** 单文件下载结果。 */
    public enum Outcome {
        /** 已存在且校验通过，已跳过。 */
        SKIPPED,
        /** 本次新下载并通过校验。 */
        DOWNLOADED
    }

    private final MojangStreamSource source;
    private final int bufferSize;

    public VerifiedDownloader(MojangStreamSource source) {
        this(source, 64 * 1024);
    }

    public VerifiedDownloader(MojangStreamSource source, int bufferSize) {
        this.source = source;
        this.bufferSize = bufferSize;
    }

    /**
     * 下载到 {@code target}，校验其 SHA-1。
     *
     * @param target   最终路径（父目录会被创建）
     * @param url      官方下载地址
     * @param expectedSha1 期望摘要；为 null 时只下载不校验（不推荐）
     * @throws IOException 下载失败或摘要不匹配
     */
    public Outcome download(Path target, String url, String expectedSha1) throws IOException {
        if (expectedSha1 != null && Files.isRegularFile(target)) {
            String actual = sha1Of(target);
            if (actual.equalsIgnoreCase(expectedSha1)) {
                return Outcome.SKIPPED;
            }
            // 摘要不符说明文件损坏，删除后重下。
            Files.deleteIfExists(target);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");

        MessageDigest digest = newSha1();
        try (InputStream in = source.open(url);
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[bufferSize];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }

        String actual = HEX.formatHex(digest.digest());
        if (expectedSha1 != null && !actual.equalsIgnoreCase(expectedSha1)) {
            Files.deleteIfExists(part);
            throw new IOException("SHA-1 mismatch for " + url
                    + "\n  expected: " + expectedSha1
                    + "\n  actual:   " + actual);
        }

        try {
            Files.move(part, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Outcome.DOWNLOADED;
    }

    /** 计算文件 SHA-1（流式，不载入内存）。 */
    public static String sha1Of(Path file) throws IOException {
        MessageDigest digest = newSha1();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    /** 计算字节数组的 SHA-1（供测试与内存数据使用）。 */
    public static String sha1OfBytes(byte[] data) {
        MessageDigest digest = newSha1();
        digest.update(data);
        return HEX.formatHex(digest.digest());
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable on this JVM", e);
        }
    }

    /** 抽象远程流来源，便于测试时注入。 */
    @FunctionalInterface
    public interface MojangStreamSource {
        InputStream open(String url) throws IOException;
    }
}
