package org.loader.api.world;

/**
 * 六个主方向。
 *
 * <p>与 Minecraft 的 {@code Direction} 一一对应，由平台在桥接层转换。
 */
public enum Direction {

    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    private final int stepX;
    private final int stepY;
    private final int stepZ;

    Direction(int stepX, int stepY, int stepZ) {
        this.stepX = stepX;
        this.stepY = stepY;
        this.stepZ = stepZ;
    }

    public int stepX() {
        return stepX;
    }

    public int stepY() {
        return stepY;
    }

    public int stepZ() {
        return stepZ;
    }

    /** 是否为水平方向（不含上下）。 */
    public boolean isHorizontal() {
        return stepY == 0;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case WEST -> EAST;
            case EAST -> WEST;
            case UP -> DOWN;
            case DOWN -> UP;
        };
    }

    BlockPos step(BlockPos origin) {
        return new BlockPos(
                origin.x() + stepX,
                origin.y() + stepY,
                origin.z() + stepZ);
    }
}
