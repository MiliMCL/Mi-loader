package org.loader.installer.meta;

import java.util.List;
import java.util.Map;

/**
 * Minecraft 版本元数据的不可变视图。
 *
 * <p>字段直接对应 Mojang {@code version.json} 的结构，但把 rules 过滤、
 * natives 分类等安装器关心的部分提前算好，避免下游反复解析原始 JSON。
 */
public final class VersionMeta {

    private final String id;
    private final String javaMajorVersion;
    private final String releaseType;
    private final String clientUrl;
    private final String clientSha1;
    private final int clientSize;
    private final List<Library> libraries;
    private final AssetIndex assetIndex;
    private final String loggingClientUrl;
    private final String loggingClientSha1;

    VersionMeta(String id,
                String javaMajorVersion,
                String releaseType,
                String clientUrl,
                String clientSha1,
                int clientSize,
                List<Library> libraries,
                AssetIndex assetIndex,
                String loggingClientUrl,
                String loggingClientSha1) {
        this.id = id;
        this.javaMajorVersion = javaMajorVersion;
        this.releaseType = releaseType;
        this.clientUrl = clientUrl;
        this.clientSha1 = clientSha1;
        this.clientSize = clientSize;
        this.libraries = libraries;
        this.assetIndex = assetIndex;
        this.loggingClientUrl = loggingClientUrl;
        this.loggingClientSha1 = loggingClientSha1;
    }

    public String id() { return id; }

    /** 该版本要求的 Java 主版本号（26.2 为 25）。 */
    public String javaMajorVersion() { return javaMajorVersion; }

    public String releaseType() { return releaseType; }

    public String clientUrl() { return clientUrl; }

    public String clientSha1() { return clientSha1; }

    public int clientSize() { return clientSize; }

    public List<Library> libraries() { return libraries; }

    public AssetIndex assetIndex() { return assetIndex; }

    /** 客户端日志配置（log4j2 xml），缺失时为 null。 */
    public String loggingClientUrl() { return loggingClientUrl; }

    public String loggingClientSha1() { return loggingClientSha1; }

    /** 资产索引。 */
    public static final class AssetIndex {
        private final String id;
        private final String url;
        private final String sha1;
        private final int size;
        private final long totalSize;

        AssetIndex(String id, String url, String sha1, int size, long totalSize) {
            this.id = id;
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
            this.totalSize = totalSize;
        }

        public String id() { return id; }

        public String url() { return url; }

        public String sha1() { return sha1; }

        public int size() { return size; }

        /** 全部资产对象的总字节数（26.2 约 480MB）。 */
        public long totalSize() { return totalSize; }
    }

    /**
     * 单个依赖库。
     *
     * <p>{@code rules} 未在解析期求值 —— 求值依赖运行平台，必须在安装现场
     * （{@link #isAllowedOnCurrentPlatform()}）判定。
     */
    public static final class Library {
        private final String name;
        private final String artifactPath;
        private final String artifactUrl;
        private final String artifactSha1;
        private final int artifactSize;
        private final Map<String, Map<String, Object>> classifiers;
        private final List<Rule> rules;
        private final boolean hasMainArtifact;

        Library(String name,
                String artifactPath,
                String artifactUrl,
                String artifactSha1,
                int artifactSize,
                Map<String, Map<String, Object>> classifiers,
                List<Rule> rules,
                boolean hasMainArtifact) {
            this.name = name;
            this.artifactPath = artifactPath;
            this.artifactUrl = artifactUrl;
            this.artifactSha1 = artifactSha1;
            this.artifactSize = artifactSize;
            this.classifiers = classifiers;
            this.rules = rules;
            this.hasMainArtifact = hasMainArtifact;
        }

        public String name() { return name; }

        /** 主 artifact 的库内相对路径，如 {@code com/google/code/gson/gson/2.14.0/gson-2.14.0.jar}。 */
        public String artifactPath() { return artifactPath; }

        public String artifactUrl() { return artifactUrl; }

        public String artifactSha1() { return artifactSha1; }

        public int artifactSize() { return artifactSize; }

        /** natives 分类 → {path,url,sha1,size}。 */
        public Map<String, Map<String, Object>> classifiers() { return classifiers; }

        public List<Rule> rules() { return rules; }

        /**
         * 本库是否带有可下载的主 artifact。
         *
         * <p>仅含 natives 的库（如 {@code javaobj-encoders}）没有主 jar，
         * 此时应只抽取 classifiers。
         */
        public boolean hasMainArtifact() { return hasMainArtifact; }

        /** 按当前平台求值 rules，判断该库是否适用于本机。 */
        public boolean isAllowedOnCurrentPlatform() {
            return PlatformRules.evaluate(rules);
        }
    }

    /** 单条 rule：os / arch / version 的取值约束。 */
    public static final class Rule {
        final Map<String, Object> os;
        final Map<String, Object> arch;
        final Map<String, Object> version;
        final String action;

        Rule(Map<String, Object> os, Map<String, Object> arch,
             Map<String, Object> version, String action) {
            this.os = os;
            this.arch = arch;
            this.version = version;
            this.action = action;
        }

        public Map<String, Object> os() { return os; }

        public Map<String, Object> arch() { return arch; }

        public Map<String, Object> version() { return version; }

        /** {@code allow} 或 {@code disallow}。 */
        public String action() { return action; }
    }
}
