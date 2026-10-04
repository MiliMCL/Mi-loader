package org.loader.runtime.mod;

import org.loader.api.VersionInfo;

import java.util.*;

/**
 * Mod 清单：描述模组身份、依赖、能力与入口。
 * <p>
 * Mod = Manifest + Scope + Modules + Capabilities + Tasks + Resources + Lifecycle。
 * <p>
 * 清单携带平台版本三元组 (platform + abi + minecraft)，Loader 在加载时
 * 对不匹配的 Mod 严格拒绝。未声明绑定的 Mod 视为无效 (MOD_PLATFORM_MISSING)。
 * <p>
 * 校验语义：未绑定 = 拒绝；任何一字段不匹配 = 拒绝；只有三元组全部精确相等 = 通过。
 */
public record ModManifest(
        String id,
        String name,
        String version,
        String author,
        String description,
        List<DependencyEntry> dependencies,
        List<String> capabilities,
        String entrypoint,
        List<String> modules,
        VersionBinding versionBinding
) {
    public ModManifest {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        capabilities = capabilities != null ? List.copyOf(capabilities) : List.of();
        modules = modules != null ? List.copyOf(modules) : List.of();
        versionBinding = versionBinding != null ? versionBinding : VersionBinding.unbound();
    }

    /**
     * 创建仅含最小字段的清单，且**未绑定**平台 (仅供测试/内部使用)。
     */
    public static ModManifest of(String id, String name, String version) {
        return new ModManifest(id, name, version, "", "", List.of(), List.of(), "", List.of(), VersionBinding.unbound());
    }

    /**
     * 创建显式平台绑定的清单 (platform / abi / minecraft)。
     */
    public static ModManifest bound(String id, String name, String version,
                                     String platform, int abi, String minecraft) {
        return new ModManifest(id, name, version, "", "", List.of(), List.of(), "", List.of(),
                new VersionBinding(platform, abi, minecraft));
    }

    /**
     * 返回 true 当且仅当清单声明了有效的平台绑定。
     */
    public boolean isVersionBound() {
        return versionBinding.platform != null && !versionBinding.platform.isBlank();
    }

    /**
     * 严格版本校验。
     * <p>
     * 任何不对应现行平台的情况都返回失败的 ValidationResult；只有三元组完全相等才通过。
     * 未声明的绑定直接返回 {@link MissingBinding}。
     */
    public ValidationResult validateVersionBinding() {
        if (!isVersionBound()) {
            return ValidationResult.MISSING;
        }
        if (!versionBinding.platform.equals(VersionInfo.CURRENT_VERSION)) {
            return ValidationResult.platformMismatch(versionBinding.platform, VersionInfo.CURRENT_VERSION);
        }
        if (versionBinding.abi != VersionInfo.ABI_VERSION) {
            return ValidationResult.abiMismatch(versionBinding.abi, VersionInfo.ABI_VERSION);
        }
        if (!versionBinding.minecraft.equals(VersionInfo.TARGET_MINECRAFT)) {
            return ValidationResult.minecraftMismatch(versionBinding.minecraft, VersionInfo.TARGET_MINECRAFT);
        }
        return ValidationResult.OK;
    }

    /**
     * 依赖条目。
     */
    public record DependencyEntry(
            String modId,
            String versionRange,
            boolean required
    ) {
    }

    /**
     * 平台版本绑定 —— Mod 构建时所针对的三元组 (platform, abi, minecraft)。
     * <p>
     * abi 使用整数 —— 1, 2, 3, ...；platform 使用语义化版本字符串 "0.1.0"；
     * minecraft 使用官方版本字符串 "26.2"。
     */
    public record VersionBinding(
            String platform,
            int abi,
            String minecraft
    ) {
        public static VersionBinding unbound() {
            return new VersionBinding("", 0, "");
        }
    }

    /**
     * 版本校验结果密封接口。
     * <p>
     * {@link Ok} 表示校验通过；其它类型均为结构化错误，可转译为 Loader 错误码与诊断信息。
     */
    public sealed interface ValidationResult {
        ValidationResult OK = new Ok();

        record Ok() implements ValidationResult {}

        /** 清单未声明 mili 平台绑定。 */
        ValidationResult MISSING = new MissingBinding();

        record MissingBinding() implements ValidationResult {}

        /** Mili Platform 版本不匹配。 */
        record PlatformMismatch(String expected, String actual) implements ValidationResult {}

        /** Mili ABI 版本不匹配。 */
        record AbiMismatch(int expected, int actual) implements ValidationResult {}

        /** Minecraft 版本不匹配。 */
        record MinecraftMismatch(String expected, String actual) implements ValidationResult {}

        static ValidationResult platformMismatch(String expected, String actual) {
            return new PlatformMismatch(expected, actual);
        }

        static ValidationResult abiMismatch(int expected, int actual) {
            return new AbiMismatch(expected, actual);
        }

        static ValidationResult minecraftMismatch(String expected, String actual) {
            return new MinecraftMismatch(expected, actual);
        }

        default boolean isOk() {
            return this instanceof Ok;
        }
    }
}
