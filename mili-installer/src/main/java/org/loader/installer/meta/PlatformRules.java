package org.loader.installer.meta;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在安装现场对 Mojang {@code rules} 求值。
 *
 * <p>Mojang 的 rule 语义（与原版启动器一致）：
 * <ul>
 *   <li>一条 rule 内部的多个特征（os/arch/version）是<b>与</b>关系；</li>
 *   <li>多条 rule 之间按声明顺序<b>短路</b>：第一条匹配的 rule 决定结果；</li>
 *   <li>只有 action 参与判定，rule 内未出现的特征视为「无约束」；</li>
 *   <li>无任何 rule 匹配时，默认 {@code allow}。</li>
 * </ul>
 *
 * <p>未识别的 {@code os.name}（例如 {@code osx} 在旧元数据里的写法）不会被
 * 静默放行 —— 依赖库猜错会导致运行时 {@code NoClassDefFoundError}，
 * 宁可漏装也不能错装。
 */
public final class PlatformRules {

    private PlatformRules() {
    }

    /** 当前平台的规范化 {@code os.name}。 */
    public static String currentOsName() {
        String raw = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (raw.contains("win")) {
            return "windows";
        }
        if (raw.contains("mac") || raw.contains("darwin")) {
            return "osx";
        }
        if (raw.contains("nux") || raw.contains("nix") || raw.contains("aix")) {
            return "linux";
        }
        return raw;
    }

    /** 当前平台的 {@code os.arch}（x86_64 归一为 amd64，与 Mojang 命名一致）。 */
    public static String currentArch() {
        String raw = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "x86_64", "amd64", "x64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> raw;
        };
    }

    /** 求值一组 rules，返回该库是否适用于本机。 */
    public static boolean evaluate(List<VersionMeta.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        for (VersionMeta.Rule rule : rules) {
            if (matches(rule)) {
                return "allow".equals(rule.action());
            }
        }
        return true;
    }

    /** 判断单条 rule 的全部特征是否都命中本机。 */
    private static boolean matches(VersionMeta.Rule rule) {
        return featureMatches(rule.os(), "os", PlatformRules::matchOs)
                && featureMatches(rule.arch(), "arch", PlatformRules::matchArch)
                && featureMatches(rule.version(), "version", PlatformRules::matchVersion);
    }

    private interface FeatureMatcher {
        boolean matches(String spec);
    }

    /**
     * rule 内未声明该特征时，{@code defaultValue} 生效
     * （os/arch 缺省为 true，version 缺省为 true）。
     */
    private static boolean featureMatches(Map<String, Object> feature,
                                          String unusedName,
                                          FeatureMatcher matcher) {
        if (feature == null) {
            return true;
        }
        Object nameNode = feature.get("name");
        if (nameNode instanceof String name) {
            return matcher.matches(name);
        }
        Object valueNode = feature.get("value");
        if (nameNode == null && valueNode == null) {
            // 只写了 {"name": ...} 的残缺 rule，视为无约束
            return true;
        }
        return false;
    }

    private static boolean matchOs(String spec) {
        String os = currentOsName();
        if ("*".equals(spec)) {
            return true;
        }
        // Mojang 用 "osx" 指代 macOS；部分元数据写作 "macos"。
        String normalizedSpec = "macos".equals(spec) ? "osx" : spec;
        if (normalizedSpec.equals(os)) {
            return true;
        }
        // Linux 发行版在元数据里可能写作 "linux"，与 os.name 一致即可。
        return normalizedSpec.equals(os);
    }

    private static boolean matchArch(String spec) {
        if ("*".equals(spec)) {
            return true;
        }
        return spec.equals(currentArch());
    }

    private static boolean matchVersion(String spec) {
        // Minecraft 的 os.version rule 只用于区分旧版 macOS 的 GLFW 行为，
        // 在非 macOS 平台上不会命中；此处按「不匹配」处理是安全的：
        // 唯一受影响的历史条目是 macOS 专用的 lwjgl 修复版本。
        return false;
    }

    /**
     * 找出适用于本机的 natives classifier 名称。
     *
     * <p>形如 {@code natives-windows} / {@code natives-linux} /
     * {@code natives-macos-arm64}。返回空集合表示该库无本机 native。
     */
    public static Set<String> nativeClassifierKeys(Map<String, Map<String, Object>> classifiers) {
        if (classifiers == null || classifiers.isEmpty()) {
            return Set.of();
        }
        String os = currentOsName();
        String arch = currentArch();

        // macOS 的 natives 命名不带后缀（natives / natives-macos），
        // 而 lwjgl 对 arm64 有专门的 natives-macos-arm64。
        String preferred = os.equals("osx")
                ? "natives-macos-" + arch
                : "natives-" + os;
        String legacy = "natives-" + os;
        String bare = "natives";

        if (classifiers.containsKey(preferred)) {
            return Set.of(preferred);
        }
        if (classifiers.containsKey(legacy)) {
            return Set.of(legacy);
        }
        // x86_64 mac 回落到通用 natives（lwjgl 2.9.x 时代命名）
        if (os.equals("osx") && classifiers.containsKey(bare)) {
            return Set.of(bare);
        }
        return Set.of();
    }
}
