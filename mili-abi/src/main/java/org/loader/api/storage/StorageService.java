package org.loader.api.storage;

/**
 * 存储服务 —— 按 modId 取 {@link ModStorage}。
 *
 * <h2>为什么按 modId 而不是按 Mod 实例</h2>
 * 同一个 Mod 在一次进程里只有一个实例，但它的存储可能在多个位置被
 * 访问（配置界面、命令处理、后台任务）。按 id 取让「同一份存储」
 * 这个语义不依赖调用方持有正确的引用 —— 拿 id 就能拿到同一实例。
 *
 * <p>实现保证同一 modId 返回同一实例（单例语义）。两份实例会让
 * 「内存已改、磁盘未存」的状态出现分叉，那是最难查的一类配置 bug。
 */
public final class StorageService {

    /** 平台实现。 */
    @FunctionalInterface
    public interface Provider {
        ModStorage forMod(String modId);
    }

    private static volatile Provider provider;

    private StorageService() {
    }

    /** 平台装配；传 null 恢复不可用。 */
    public static void registerProvider(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    /**
     * 取指定 Mod 的存储。
     *
     * @throws StorageUnavailableException 平台未装配
     * @throws IllegalArgumentException     modId 非法
     */
    public static ModStorage forMod(String modId) {
        if (modId == null || modId.isBlank() || !modId.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "modId 非法（应为小写字母/数字/._-）: \"" + modId + "\"");
        }
        Provider p = provider;
        if (p == null) {
            throw new StorageUnavailableException(
                    "StorageService 未装配 —— 平台尚未启动到运行期。");
        }
        return p.forMod(modId);
    }
}
