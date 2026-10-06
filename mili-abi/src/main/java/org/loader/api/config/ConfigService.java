package org.loader.api.config;

/**
 * 配置服务 —— Mod 配置的加载入口。
 *
 * <p>实现由平台在启动期装配；重复 {@link #load} 同一 modId
 * 返回同一实例（配置是单例语义，两份实例会让「内存已改、磁盘未存」
 * 的状态出现分叉）。
 */
public final class ConfigService {

    /** 平台实现。 */
    public interface Provider {
        ModConfig load(String modId);
    }

    private static volatile Provider provider;

    private ConfigService() {
    }

    /** 平台装配；传 null 恢复不可用。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    /**
     * 加载（或创建）指定 mod 的配置。
     *
     * @throws ConfigUnavailableException 平台未装配
     * @throws IllegalArgumentException   modId 非法
     */
    public static ModConfig load(String modId) {
        if (modId == null || modId.isBlank() || !modId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "modId 非法（应为小写字母/数字/._-）: \"" + modId + "\"");
        }
        Provider p = provider;
        if (p == null) {
            throw new ConfigUnavailableException(
                    "ConfigService 未装配 —— 平台尚未启动到运行期。");
        }
        return p.load(modId);
    }
}
