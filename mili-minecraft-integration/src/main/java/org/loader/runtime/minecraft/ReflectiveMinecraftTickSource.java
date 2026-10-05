package org.loader.runtime.minecraft;

/**
 * 基于 Minecraft 真实 tick 入口的 TickSource。
 *
 * <p>本类不含任何编译期 Minecraft 依赖 —— 它通过反射找到游戏的服务端 tick
 * 循环并转发。<b>接入点</b>：
 *
 * <p>优先路径（MULTIPLAYER / DEDICATED_SERVER）：
 * <pre>
 *   net.minecraft.server.MinecraftServer#tickChildren(long)   ← 每 tick 调用
 * </pre>
 * 该方法在服务器主循环中每个 tick 恰好被调用一次，接收当前 tick 计数，
 * 是最稳定的真实 tick 锚点。
 *
 * <p>回退路径（SINGLEPLAYER / CLIENT）：
 * <pre>
 *   net.minecraft.client.multiplayer.ClientLevel#tick(BooleanSupplier)
 * </pre>
 * 客户端本地世界每 tick 调用一次。
 *
 * <p><b>关键约束</b>：本类只负责"在真实 tick 被游戏调用时通知桥接层"，
 * 不做轮询、不推测 tick 次数。若两个路径都不可用，则明确降级为
 * {@link #isReady()} == false —— 而不是假装在 tick。
 */
public final class ReflectiveMinecraftTickSource implements MinecraftTickSource {

    private final TickBridge bridge;
    private final String endpointName;
    private volatile boolean ready = false;

    public ReflectiveMinecraftTickSource(TickBridge bridge) {
        this.bridge = bridge;
        this.endpointName = "reflective";
    }

    @Override
    public org.loader.runtime.tick.TickContract beginTick() {
        return bridge.beginTick();
    }

    @Override
    public void endTick() {
        bridge.endTick();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public String describe() {
        return "ReflectiveMinecraftTickSource[endpoint=" + endpointName
                + ", ticks=" + bridge.currentTick()
                + ", ready=" + ready + "]";
    }

    /** 标记接入点已就绪（由注入层在确认锚点可用时调用）。 */
    public void markReady() {
        this.ready = true;
    }

    public void markNotReady() {
        this.ready = false;
    }
}