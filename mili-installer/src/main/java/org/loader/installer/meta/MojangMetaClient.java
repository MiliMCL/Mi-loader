package org.loader.installer.meta;

import org.loader.installer.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mojang 官方版本元数据客户端。
 *
 * <p>只读取 piston-meta 的公开清单，不涉及任何账号或认证端点。
 * 解析链：{@code version_manifest_v2.json} → 目标版本条目 → 版本 JSON。
 */
public final class MojangMetaClient {

    /** 官方版本清单。 */
    public static final String VERSION_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http;
    private final Duration timeout;

    public MojangMetaClient() {
        this(Duration.ofSeconds(30));
    }

    public MojangMetaClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 查询某个版本 ID 的元数据 JSON URL；版本不存在返回 null。 */
    public String resolveVersionUrl(String versionId) throws IOException, InterruptedException {
        Map<String, Object> manifest = getJson(VERSION_MANIFEST);
        for (Object entry : Json.arr(manifest, "versions")) {
            if (versionId.equals(Json.str(entry, "id"))) {
                return Json.str(entry, "url");
            }
        }
        return null;
    }

    /** 拉取并解析指定版本的完整元数据。 */
    public VersionMeta fetchVersionMeta(String versionId) throws IOException, InterruptedException {
        String url = resolveVersionUrl(versionId);
        if (url == null) {
            throw new IOException("Minecraft version '" + versionId
                    + "' not found in the official version manifest");
        }
        return parseVersionMeta(getJson(url));
    }

    /** 解析版本 JSON（公开，便于测试与离线复用）。 */
    @SuppressWarnings("unchecked")
    public static VersionMeta parseVersionMeta(Map<String, Object> root) {
        String id = Json.str(root, "id");

        String javaMajor = null;
        Map<String, Object> javaVer = Json.obj(root, "javaVersion");
        if (javaVer != null) {
            javaMajor = Json.str(javaVer, "majorVersion");
        }
        if (javaMajor == null) {
            javaMajor = "8";
        }

        String releaseType = Json.str(root, "releaseType");
        if (releaseType == null) {
            releaseType = "release";
        }

        Map<String, Object> downloads = Json.obj(root, "downloads");
        if (downloads == null) {
            throw new IllegalArgumentException("version JSON has no 'downloads' block");
        }

        Map<String, Object> client = Json.obj(downloads, "client");
        if (client == null) {
            throw new IllegalArgumentException("version JSON has no client download entry");
        }
        String clientUrl = Json.str(client, "url");
        String clientSha1 = Json.str(client, "sha1");
        Integer clientSize = Json.integer(client, "size");
        if (clientUrl == null || clientSha1 == null) {
            throw new IllegalArgumentException("client download entry missing url/sha1");
        }

        List<VersionMeta.Library> libraries = parseLibraries(Json.arr(root, "libraries"));
        VersionMeta.AssetIndex assetIndex = parseAssetIndex(Json.obj(root, "assetIndex"));

        String logUrl = null;
        String logSha1 = null;
        Map<String, Object> logging = Json.obj(root, "logging");
        if (logging != null) {
            Map<String, Object> clientLog = Json.obj(logging, "client");
            if (clientLog != null) {
                Map<String, Object> file = Json.obj(clientLog, "file");
                if (file != null) {
                    logUrl = Json.str(file, "url");
                    logSha1 = Json.str(file, "sha1");
                }
            }
        }

        return new VersionMeta(id, javaMajor, releaseType, clientUrl, clientSha1,
                clientSize == null ? 0 : clientSize, libraries, assetIndex, logUrl, logSha1);
    }

    @SuppressWarnings("unchecked")
    private static List<VersionMeta.Library> parseLibraries(List<Object> raw) {
        List<VersionMeta.Library> out = new ArrayList<>();
        for (Object node : raw) {
            if (!(node instanceof Map)) {
                continue;
            }
            String name = Json.str(node, "name");
            if (name == null) {
                continue;
            }

            Map<String, Object> downloads = Json.obj(node, "downloads");
            Map<String, Object> artifact = downloads == null ? null : Json.obj(downloads, "artifact");

            String path = artifact == null ? null : Json.str(artifact, "path");
            String url = artifact == null ? null : Json.str(artifact, "url");
            String sha1 = artifact == null ? null : Json.str(artifact, "sha1");
            Integer size = artifact == null ? null : Json.integer(artifact, "size");

            // classifiers: natives-windows / natives-linux / natives-macos...
            Map<String, Map<String, Object>> classifiers = new LinkedHashMap<>();
            if (downloads != null) {
                Map<String, Object> clsNode = Json.obj(downloads, "classifiers");
                if (clsNode instanceof Map<?, ?> cls) {
                    for (Map.Entry<?, ?> e : cls.entrySet()) {
                        if (e.getKey() instanceof String k && e.getValue() instanceof Map<?, ?> v) {
                            classifiers.put(k, (Map<String, Object>) v);
                        }
                    }
                }
            }

            List<VersionMeta.Rule> rules = new ArrayList<>();
            for (Object r : Json.arr(node, "rules")) {
                Map<String, Object> rm = Json.obj(r, "os");
                Map<String, Object> ra = Json.obj(r, "arch");
                Map<String, Object> rv = Json.obj(r, "version");
                String action = Json.str(r, "action");
                if (action == null) {
                    action = "allow";
                }
                rules.add(new VersionMeta.Rule(rm, ra, rv, action));
            }

            out.add(new VersionMeta.Library(name, path, url, sha1,
                    size == null ? 0 : size, classifiers, rules, artifact != null));
        }
        return out;
    }

    private static VersionMeta.AssetIndex parseAssetIndex(Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        String id = Json.str(node, "id");
        String url = Json.str(node, "url");
        String sha1 = Json.str(node, "sha1");
        Integer size = Json.integer(node, "size");
        Double total = node.get("totalSize") instanceof Double d ? d : null;
        if (id == null || url == null) {
            return null;
        }
        return new VersionMeta.AssetIndex(id, url, sha1,
                size == null ? 0 : size, total == null ? 0L : total.longValue());
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    /** GET 一个 JSON 端点并解析为对象。 */
    public Map<String, Object> getJson(String url) throws IOException, InterruptedException {
        byte[] body = getBytes(url);
        return Json.parseObject(new String(body, StandardCharsets.UTF_8));
    }

    /** GET 任意资源为字节数组（带重试）。 */
    public byte[] getBytes(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", "Mili-Installer/1.0")
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                }
                return resp.body();
            } catch (IOException e) {
                last = e;
                if (attempt < 3) {
                    Thread.sleep(500L * attempt);
                }
            }
        }
        throw last;
    }

    /** 打开一个远程资源的输入流（供流式下载使用，避免整包进内存）。 */
    public InputStream openStream(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mili-Installer/1.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }
}
