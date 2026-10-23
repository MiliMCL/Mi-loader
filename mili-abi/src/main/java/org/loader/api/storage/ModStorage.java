package org.loader.api.storage;

import java.util.Set;

/**
 * 单个 Mod 的键值存储 —— 跨重启持久的少量状态。
 *
 * <h2>它不该被用来做什么</h2>
 * 这不是数据库。它是给「上次窗口停在哪」「用户接受过引导没有」
 * 「缓存上次扫描结果」这类几十个键的小状态用的。
 * 写超过几百个键的 Mod 应该自己管理文件（通过 {@code ResourceManager}
 * 或直接在配置目录建文件）—— 键值存储的实现会整体加载到内存，
 * 大量键会变成常驻开销。
 *
 * <h2>并发语义</h2>
 * 实现必须线程安全：读可以并发，写串行化。
 * {@link #flush()} 是唯一的落盘时机保证 —— {@link #put} 只保证
 * 内存可见，不保证已经写盘。平台在 Mod Scope 关闭时自动 flush，
 * 所以正常关停路径上 Mod 不需要手动调用。
 *
 * <h2>键的命名</h2>
 * 键必须是 {@code [A-Za-z0-9_.-]}，长度 1..128。这条约束由实现校验，
 * 因为它直接决定了底层属性文件的转义规则 —— 放行任意字符会让
 * 「键里带等号」这种输入破坏文件格式。
 */
public interface ModStorage {

    /** 读一个值；不存在返回空。 */
    java.util.Optional<String> get(String key);

    /** 读一个值，不存在时返回默认值。 */
    default String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    /** 读布尔值；无法解析时返回默认值。 */
    default boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(v -> switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> defaultValue;
        }).orElse(defaultValue);
    }

    /** 读整数；无法解析时返回默认值。 */
    default int getInt(String key, int defaultValue) {
        return get(key).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /** 读长整数；无法解析时返回默认值。 */
    default long getLong(String key, long defaultValue) {
        return get(key).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * 写入（仅内存可见）。值为 null 等价于 {@link #remove(String)}。
     *
     * @throws IllegalArgumentException 键非法
     */
    void put(String key, String value);

    /** 删除一个键。返回它是否曾经存在。 */
    boolean remove(String key);

    /** 全部键的快照（只读）。 */
    Set<String> keys();

    /** 把内存状态落到磁盘。失败不抛异常，只返回 false。 */
    boolean flush();

    /** 最近一次成功落盘的时间戳（毫秒）；从未落盘为 0。 */
    long lastFlushMillis();

    /** 存储是否真的持久化。内存实现返回 false —— Mod 可据此提示用户。 */
    boolean persistent();
}
