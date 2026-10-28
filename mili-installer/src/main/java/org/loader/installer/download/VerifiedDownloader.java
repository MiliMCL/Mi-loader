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
 *   <li><b>原子替换</b>：先写唯一的临时文件（{@code <name>.part-<pid>-<seq>}），
 *       校验通过才 rename 到最终路径 —— 断网或磁盘写满时不会留下「看起来存在
 *       但已损坏」的目标文件，也不会破坏原有文件；</li>
 *   <li><b>幂等跳过</b>：目标文件已存在且摘要匹配时直接返回，重复执行安装
 *       几乎零开销（这是 600MB assets 场景的必需品）。</li>
 * </ul>
 *
 * <p>本类只负责「一个 URL → 一个文件」。目标路径是否合法（例如资源 hash
 * 能否用作路径段）由调用方校验，见 {@code AssetInstaller}。
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
            // 摘要不符说明现有文件不可用，但**不能现在删**：
            // 若随后的下载也失败，删掉就等于把一个「虽然损坏、但至少是完整文件」
            // 直接变成「什么都没有」，失败路径的行为变得不可预测。
            // 正确做法是先把新文件校验完，再原子替换（见本方法末尾的 move）。
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = target.resolveSibling(partName(target));

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
            // 某些文件系统（网络盘、部分 FAT/exFAT 挂载）不支持原子 move；
            // 回退到普通替换。此时仍不会出现「半个文件」—— part 已经写完并校验过。
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Outcome.DOWNLOADED;
    }

    /** 进程内自增序号，用于让临时文件名在一次运行内唯一。 */
    private static final java.util.concurrent.atomic.AtomicLong PART_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 临时文件名。
     *
     * <p>历史实现固定用 {@code <target>.part}，这在两种真实场景下会互相踩：
     * <ul>
     *   <li>两个安装器进程装同一个目录（用户重复双击）—— 双方写同一个
     *       {@code .part}，内容交错，摘要必然不匹配，最后两边都失败；</li>
     *   <li>同一进程内两个线程下载同一目标 —— 同样交错。</li>
     * </ul>
     * 加入 pid 与进程内序号后，各写各的临时文件，最后一个成功者原子替换目标。
     */
    private static String partName(Path target) {
        return target.getFileName() + ".part-"
                + ProcessHandle.current().pid() + "-" + PART_SEQ.incrementAndGet();
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
