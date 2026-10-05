package org.loader.installer.install;

import org.loader.installer.download.Progress;
import org.loader.installer.download.VerifiedDownloader;
import org.loader.installer.meta.PlatformRules;
import org.loader.installer.meta.VersionMeta;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把 {@code libraries} 铺成标准 Minecraft 布局。
 *
 * <p>目标布局（与官方启动器一致，也是 {@code LibraryResolver} 期望的形状）：
 * <pre>
 *   &lt;gameDir&gt;/libraries/&lt;maven/path&gt;      ← 主 artifact
 *   &lt;gameDir&gt;/natives/&lt;os&gt;/&lt;entry&gt;            ← 平台 native（已从 classifier 解压）
 * </pre>
 *
 * <p>为什么要解压 natives：classifier jar 里装的是 {@code liblwjgl.so} /
 * {@code lwjgl.dll} / {@code libjogl.dylib} 这类共享库，还有嵌套的
 * {@code META-INF/versions/9/...} 变体。LWJGL 在运行期按
 * {@code org.lwj.librarypath} / 平台目录查找它们，原样放置 jar 不生效。
 */
public final class LibraryInstaller {

    private final VerifiedDownloader downloader;
    private final Progress progress;

    public LibraryInstaller(VerifiedDownloader downloader, Progress progress) {
        this.downloader = downloader;
        this.progress = progress;
    }

    /** 统计结果，供报告使用。 */
    public record Result(int applied, int skippedByRule, int nativeArchives, List<String> warnings) {
    }

    /**
     * 安装所有适用于本机的库。
     *
     * @param meta     版本元数据
     * @param gameDir  游戏目录（其下创建 libraries/ 与 natives/）
     * @param libs     参与统计的库总数（用于进度分母）
     * @param doneBase 已完成项数（用于进度续接）
     */
    public Result install(VersionMeta meta, Path gameDir, int libs, int doneBase) throws IOException {
        Path librariesDir = gameDir.resolve("libraries");
        Path nativesDir = gameDir.resolve("natives");

        int applied = 0;
        int skippedByRule = 0;
        int nativeArchives = 0;
        List<String> warnings = new ArrayList<>();

        int index = 0;
        for (VersionMeta.Library lib : meta.libraries()) {
            index++;
            if (!lib.isAllowedOnCurrentPlatform()) {
                skippedByRule++;
                progress.file("skip " + lib.name(), doneBase + index, libs, 0, 0);
                continue;
            }

            try {
                if (lib.hasMainArtifact() && lib.artifactPath() != null) {
                    Path target = librariesDir.resolve(lib.artifactPath()).normalize();
                    if (!target.startsWith(librariesDir)) {
                        warnings.add("Refused path traversal in library path: " + lib.artifactPath());
                    } else {
                        downloader.download(target, lib.artifactUrl(), lib.artifactSha1());
                        applied++;
                    }
                }

                Set<String> nativeKeys = PlatformRules.nativeClassifierKeys(lib.classifiers());
                for (String key : nativeKeys) {
                    Map<String, Object> entry = lib.classifiers().get(key);
                    if (entry == null) {
                        continue;
                    }
                    String url = asString(entry.get("url"));
                    String sha1 = asString(entry.get("sha1"));
                    if (url == null) {
                        continue;
                    }
                    Path cacheDir = gameDir.resolve("natives").resolve(".cache");
                    Path archive = cacheDir.resolve(key + "-" + lib.name() + ".jar");
                    downloader.download(archive, url, sha1);
                    extractNatives(archive, nativesDir, warnings);
                    nativeArchives++;
                }
            } catch (IOException e) {
                warnings.add("Failed to install library '" + lib.name() + "': " + e.getMessage());
            }
        }

        // native 缓存只是中转，解压完即可删除，避免双份占用磁盘。
        deleteRecursivelyQuietly(nativesDir.resolve(".cache"));

        return new Result(applied, skippedByRule, nativeArchives, List.copyOf(warnings));
    }

    /**
     * 从 classifier jar 解压共享库到 {@code natives/<os>/}。
     *
     * <p>只抽取真正的共享库与必要的 META-INF 版本化变体，跳过 {@code .SF}/
     * {@code .DSA} 签名文件（解压出来的签名必然校验失败，反而污染 classpath）。
     */
    private void extractNatives(Path archive, Path nativesDir, List<String> warnings) throws IOException {
        String osDir = PlatformRules.currentOsName();
        Path outRoot = nativesDir.resolve(osDir);
        Files.createDirectories(outRoot);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!isNativePayload(name)) {
                    continue;
                }
                Path out = outRoot.resolve(stripLeading(name)).normalize();
                if (!out.startsWith(outRoot)) {
                    warnings.add("Refused path traversal in native entry: " + name);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                // 解压出的文件需可执行（LWJGL 会以 dlopen/LoadLibrary 加载）
                out.toFile().setExecutable(true, false);
            }
        }
    }

    /** 判断一个条目是否为需要释放的 native 产物。 */
    private static boolean isNativePayload(String name) {
        if (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA")
                || name.endsWith("/")) {
            return false;
        }
        // LWJGL 的版本化共享库：META-INF/versions/9/org/lwjgl/.../liblwjgl.so
        if (name.contains("META-INF/versions/")) {
            return hasNativeExtension(name);
        }
        return hasNativeExtension(name);
    }

    private static boolean hasNativeExtension(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".so")
                || lower.endsWith(".dll")
                || lower.endsWith(".dylib")
                || lower.endsWith(".jnilib");
    }

    /** 去掉 classifier jar 里的路径前缀，只留文件名。 */
    private static String stripLeading(String name) {
        int idx = name.lastIndexOf('/');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 缓存清理失败不影响安装结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
