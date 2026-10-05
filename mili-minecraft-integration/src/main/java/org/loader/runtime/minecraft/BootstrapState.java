package org.loader.runtime.minecraft;

/**
 * Minecraft 集成启动状态机。
 *
 * <pre>
 *   CREATED
 *      ↓
 *   DISCOVERING     ← 定位并校验 Minecraft 26.2
 *      ↓
 *   PREPARING       ← 准备运行环境 / 创建 MinecraftClassLoader
 *      ↓
 *   LOADING         ← 加载 Mod、解析依赖
 *      ↓
 *   BOOTSTRAPPING   ← Minecraft 自身初始化（SharedConstants + Bootstrap）
 *      ↓
 *   RUNNING         ← Minecraft 主循环运行中，Tick 生效
 *      ↓
 *   STOPPING
 *      ↓
 *   STOPPED
 *
 *   任意状态异常 → FAILED
 * </pre>
 *
 * <p>状态迁移是<b>单向</b>且<b>受校验</b>的：跳步或回退都会抛
 * {@link IllegalStateTransitionException}。这保证启动失败时不会留下
 * 半初始化的 Scope / ClassLoader / 线程。
 */
public enum BootstrapState {

    /** 已构造，尚未开始发现流程。 */
    CREATED,
    /** 正在定位并校验 Minecraft。 */
    DISCOVERING,
    /** 正在准备运行环境与 ClassLoader。 */
    PREPARING,
    /** 正在加载 Mod 与解析依赖。 */
    LOADING,
    /** Minecraft 自身初始化中。 */
    BOOTSTRAPPING,
    /** 正常运行，Tick 生效。 */
    RUNNING,
    /** 正在停止。 */
    STOPPING,
    /** 已完全停止，资源已释放。 */
    STOPPED,
    /** 启动或运行失败。 */
    FAILED;

    /** 是否为终态（不可再迁出）。 */
    public boolean isTerminal() {
        return this == STOPPED || this == FAILED;
    }

    /** 是否已就绪（Tick 契约在此状态下生效）。 */
    public boolean isRunning() {
        return this == RUNNING;
    }

    /**
     * 校验从当前状态到目标状态的迁移是否合法。
     *
     * @throws IllegalStateTransitionException 迁移非法
     */
    public void requireTransitionTo(BootstrapState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
    }

    /**
     * 是否允许迁移到目标状态。
     *
     * <p>规则：
     * <ul>
     *   <li>终态不可迁出</li>
     *   <li>FAILED 只能由异常路径进入</li>
     *   <li>其余仅允许相邻正向迁移</li>
     * </ul>
     */
    public boolean canTransitionTo(BootstrapState target) {
        if (this.isTerminal()) {
            return false;
        }
        // FAILED 只能由异常路径进入，状态机本身不主动迁移
        if (target == FAILED) {
            return true;
        }
        // STOPPING 可从 RUNNING 或更早的阶段进入（启动中途失败也要能清理）
        if (target == STOPPING) {
            return true;
        }
        // 相邻正向迁移
        return ordinal() + 1 == target.ordinal();
    }

    /**
     * 非法状态迁移。
     */
    public static final class IllegalStateTransitionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final transient BootstrapState from;
        private final transient BootstrapState to;

        public IllegalStateTransitionException(BootstrapState from, BootstrapState to) {
            super("非法的 Minecraft 启动状态迁移: " + from + " → " + to);
            this.from = from;
            this.to = to;
        }

        public BootstrapState from() {
            return from;
        }

        public BootstrapState to() {
            return to;
        }
    }
}