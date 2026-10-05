package org.loader.api.world;

/**
 * 不可变的世界坐标。
 *
 * <p>Mod 永远不接触 Minecraft 的 {@code BlockPos}，只使用这个纯值对象。
 * 平台在反射桥接层负责与游戏侧坐标类型互转。
 *
 * <p>坐标为整数方块坐标，不是像素坐标。
 */
public final class BlockPos {

    private final int x;
    private final int y;
    private final int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /**
     * 返回沿指定方向相邻的坐标。
     *
     * @param direction 方向，不可为 null
     */
    public BlockPos relative(Direction direction) {
        return direction.step(this);
    }

    /** 返回向北/南/西/东/上/下偏移 {@code distance} 格后的坐标。 */
    public BlockPos offset(Direction direction, int distance) {
        return new BlockPos(
                x + direction.stepX() * distance,
                y + direction.stepY() * distance,
                z + direction.stepZ() * distance);
    }

    /**
     * 转为不可变键，用于在 Map 中索引方块位置。
     * <p>把三个 int 压进一个 long，避免装箱与自定义 equals。
     */
    public long asLong() {
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (z & 0x3FFFFFF) << 12)
                | (y & 0xFFF);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockPos other)) {
            return false;
        }
        return x == other.x && y == other.y && z == other.z;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(asLong());
    }

    @Override
    public String toString() {
        return "BlockPos(" + x + ", " + y + ", " + z + ")";
    }
}
