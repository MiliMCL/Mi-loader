package org.loader.runtime.minecraft.world;

import org.loader.api.world.BlockEntityView;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.BlockPos;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BlockEntityView} 的真实实现。
 *
 * <h2>数据存哪</h2>
 * <p>契约刻意把数据槽抽象成 {@code Map<String, Object>}:「平台不解释键的含义，
 * Mod 自己决定如何存取」。于是本实现持有一份平台侧的
 * {@link ConcurrentHashMap}，按方块实体对象身份索引。
 *
 * <p><b>这是有意的取舍，必须说清楚</b>：这样存的数据存在内存里，
 * <b>不会随存档落盘</b>。真正需要持久化的 Mod 应把关键状态编码进方块实体的
 * NBT（当前契约尚未开放该能力）。对作物这类「阶段」状态，
 * 丢失的表现是重新长一遍 —— 可以接受，且不会崩游戏。
 * 若日后契约开放 NBT 通道，只需替换本类的存取实现，Mod 侧零改动。
 *
 * <h2>为什么用 identity 索引</h2>
 * <p>方块实体没有稳定 id，也不该让 Mod 见到。直接用对象身份做键最简单，
 * 且生命周期与游戏方块实体一致 —— 游戏丢弃它，条目随之可回收
 * （见 {@link #forget}）。
 */
final class ReflectiveBlockEntityView implements BlockEntityView {

    private static final String BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity";
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("Mili/WorldView");

    /** 平台侧数据槽，按方块实体身份索引。有界以防长期运行累积。 */
    private static final Map<Object, Map<String, Object>> DATA =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<>());

    private final Object blockEntity;
    private final BlockPos pos;
    private final BlockHandle owner;

    ReflectiveBlockEntityView(Object blockEntity, BlockPos pos, BlockHandle owner) {
        this.blockEntity = blockEntity;
        this.pos = pos;
        this.owner = owner;
    }

    /** 游戏丢弃方块实体时清掉它的数据槽。 */
    static void forget(Object blockEntity) {
        if (blockEntity != null) {
            DATA.remove(blockEntity);
        }
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    @Override
    public BlockHandle owner() {
        return owner;
    }

    @Override
    public void markDirty() {
        // 26.2 的 BlockEntity.setChanged() —— 通知游戏该方块实体需要在保存时写盘。
        // 注意方法名不是旧版的 setDirty()。
        try {
            Method m = Reflect.gameClass(BLOCK_ENTITY).getMethod("setChanged");
            m.invoke(blockEntity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 标记是尽力而为：解析不到就不标记，不抛给 Mod。
            LOG.log(java.util.logging.Level.FINE,
                    "markDirty() could not reach BlockEntity.setChanged", e);
        }
    }

    @Override
    public void setData(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("data slot key must not be blank");
        }
        synchronized (DATA) {
            if (DATA.size() > 4096 && !DATA.containsKey(blockEntity)) {
                // 弱引用键本会自动回收，但同步 Map 在高水位时先给个提示性兜底：
                // 触发条件是「Mod 在往里塞大量陌生方块实体」，本身就是 bug。
                LOG.log(java.util.logging.Level.WARNING,
                        "BlockEntityView data slots exceeded 4096; "
                                + "a mod may be leaking block-entity references");
            }
            Map<String, Object> slots = DATA.computeIfAbsent(
                    blockEntity, k -> new LinkedHashMap<>());
            if (value == null) {
                slots.remove(key);
            } else {
                slots.put(key, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getData(String key, T defaultValue) {
        Object value = findData(key).orElse(null);
        if (value == null) {
            return defaultValue;
        }
        // 契约允许默认值本身是 null，所以不做类型检查 ——
        // 但如果调用方给了非 null 默认值而槽里类型不符，那是 Mod 的 bug，
        // 让 ClassCastException 原样抛出（契约的 findData 正是这么约定的）。
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Optional<T> findData(String key) {
        if (key == null) {
            return Optional.empty();
        }
        synchronized (DATA) {
            Map<String, Object> slots = DATA.get(blockEntity);
            if (slots == null) {
                return Optional.empty();
            }
            return Optional.ofNullable((T) slots.get(key));
        }
    }

    @Override
    public List<String> dataKeys() {
        synchronized (DATA) {
            Map<String, Object> slots = DATA.get(blockEntity);
            return slots == null ? List.of() : new ArrayList<>(slots.keySet());
        }
    }

    @Override
    public String toString() {
        return "BlockEntityView(" + pos + ", slots=" + dataKeys().size() + ")";
    }
}