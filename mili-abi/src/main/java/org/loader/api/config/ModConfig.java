package org.loader.api.config;

/**
 * 模组配置 —— 每个模组一份、类型化的键值存储。
 *
 * <h2>存储形态（v1 的刻意取舍）</h2>
 * {@code config/mili/<modId>.properties}，纯 UTF-8 Properties。
 * 不做 JSON/TOML：配置的本质是「少量标量的持久化」，Properties
 * 零依赖、玩家可用任何编辑器手改、损坏时逐键降级 —— 而 JSON
 * 一处语法错误整份配置作废。层级、注释、排序这些进阶形态
 * 等真实需求出现再引入，而不是现在就建一套配置 DSL。
 *
 * <h2>类型语义</h2>
 * 键没有静态类型：同一个键可以按不同类型读取。
 * 解析失败（如把 {@code "abc"} 读成 int）返回默认值 ——
 * 手改配置文件的玩家不该得到一个崩溃，而 mod 拿到默认值
 * 总好过拿不到值。
 *
 * <h2>线程模型</h2>
 * 全部方法线程安全（内部同步）。读多写少的典型场景下开销可忽略。
 */
public interface ModConfig {

    /** 拥有这份配置的 modId。 */
    String modId();

    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    double getDouble(String key, double defaultValue);

    /** 设置字符串值（内存中生效；持久化见 {@link #save()}）。 */
    void set(String key, String value);

    /** 设置整型值。 */
    void setInt(String key, int value);

    /** 设置布尔值。 */
    void setBoolean(String key, boolean value);

    /** 设置双精度值。 */
    void setDouble(String key, double value);

    /** 该键是否存在（任何类型）。 */
    boolean has(String key);

    /** 全部键（排序后快照）。 */
    Iterable<String> keys();

    /**
     * 持久化到磁盘。
     *
     * @return 写入的文件路径（人类可读）
     * @throws ConfigWriteException 磁盘写入失败
     */
    String save();
}
