package org.loader.api.world;

import java.util.List;
import java.util.Optional;

/**
 * 方块实体的受限视图。
 *
 * <p>对应 Minecraft 的 BlockEntity，用于承载容器状态与逐 tick 逻辑。
 * Mod 通过它读写自定义数据、触发产出，但拿不到游戏侧对象。
 */
public interface BlockEntityView {

    /** 该方块实体所在位置。 */
    BlockPos pos();

    /** 该方块实体所属方块句柄。 */
    BlockHandle owner();

    /**
     * 标记方块实体需要保存。
     * <p>世界保存时才会真正序列化，单纯设置标记不会立即写盘。
     */
    void markDirty();

    // ── 泛型数据槽 ─────────────────────────────────────────────────────────
    // 平台不解释键的含义，Mod 自己决定如何存取。这样新增 Mod 时
    // 无需改动平台，也不破坏存档兼容性。

    /**
     * 写入一个数据槽。
     *
     * @param key   槽名（Mod 私有命名空间）
     * @param value 值；null 表示清除该槽
     */
    void setData(String key, Object value);

    /**
     * 读取数据槽，缺失时返回默认值。
     *
     * @param key         槽名
     * @param defaultValue 缺失时的默认值；允许为 null
     */
    <T> T getData(String key, T defaultValue);

    /**
     * 读取数据槽。
     *
     * @param key 槽名
     * @return 槽不存在时返回 empty
     * @throws ClassCastException 若槽内类型与调用方期望不符
     */
    <T> Optional<T> findData(String key);

    /** 列出所有已写入的槽名。 */
    List<String> dataKeys();
}
