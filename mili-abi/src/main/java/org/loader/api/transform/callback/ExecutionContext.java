package org.loader.api.transform.callback;

/**
 * 执行上下文 —— 标识「这次注入回调运行在哪个执行单元里」。
 *
 * <h2>为什么现在就要有它，而不是等到并行化那天再加</h2>
 * 这是本 API 唯一一处「为未来预留」的设计，而预留的成本远低于后补：
 *
 * <p>若Mod 作者在<b>今天</b>就按「回调可能运行在不同 region、不同线程」
 * 编程（写任何跨 tick 的可变状态时加同步、不假设回调顺序），
 * 那么平台未来引入 Region Scheduler 时，他们的行为<b>自动就是正确的</b>。
 *
 * <p>反之，若今天所有人都假设「回调一定在主线程、一定按注册顺序」，
 * 那么并行化的那天，所有这些 Mod 会同时出问题 ——
 * 而这类问题只在高负载、多区域场景下偶发，几乎无法复现。
 *
 * <p>更重要的是：<b>API 兼容性</b>。今天给
 * {@link InjectionContext#executionContext()} 一个有类型的值，
 * 明天它仍然是同一个类型；今天给 {@code Object}，明天改成具体类型时
 * 就是破坏性变更。
 *
 * <h2>当前阶段的取值</h2>
 * 本阶段为 single-thread 正确性，因此恒为
 * {@link #single(InjectionContext)} —— 一个全平台共享的 MAIN 单元。
 * 但 Mod 代码应当<b>始终</b>通过 {@link InjectionContext#executionContext()}
 * 读取，而不是缓存线程或假设主线程。
 */
public final class ExecutionContext {

    /**
     * 执行单元类型。
     *
     * <p>刻意做成封闭枚举而非字符串或 int：新增一种执行单元时，
     * 所有 {@code switch} 都会编译失败，强制 Mod 作者审视自己的假设 ——
     * 这正是我们希望在并行化前发生的事。
     */
    public enum Kind {
        /** 全局单一执行单元（当前阶段）。 */
        MAIN,
        /** 某个 region 的执行单元（未来 Region Scheduler）。 */
        REGION,
        /** 异步线程池（未来的异步任务执行）。 */
        ASYNC_POOL
    }

    private static final ExecutionContext MAIN_CONTEXT =
            new ExecutionContext(Kind.MAIN, -1, null);

    private final Kind kind;
    private final int regionId;
    private final Thread pinnedThread;

    private ExecutionContext(Kind kind, int regionId, Thread pinnedThread) {
        this.kind = kind;
        this.regionId = regionId;
        this.pinnedThread = pinnedThread;
    }

    /**
     * 单一执行单元 —— 当前阶段的唯一取值。
     *
     * @return 共享的 MAIN 上下文（不可变，可安全缓存）
     */
    public static ExecutionContext single() {
        return MAIN_CONTEXT;
    }

    /**
     * 某个 region 的执行单元。
     *
     * <p>当前阶段不会被平台调用，保留它是为了让
     * {@link Kind#REGION} 不成为「声明了但从无实例」的死枚举值 ——
     * 那种死值会诱使 Mod 作者写永远不会执行的分支。
     */
    public static ExecutionContext region(int regionId) {
        if (regionId < 0) {
            throw new IllegalArgumentException("regionId 不能为负: " + regionId);
        }
        return new ExecutionContext(Kind.REGION, regionId, null);
    }

    /**
     * 异步线程池的执行单元。
     *
     * @param pinnedThread 该单元固定使用的线程；null 表示由池自行调度
     */
    public static ExecutionContext asyncPool(Thread pinnedThread) {
        return new ExecutionContext(Kind.ASYNC_POOL, -1, pinnedThread);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 所属 region 序号；非 REGION 时为 -1。
     *
     * <p>Mod 可以用它做「同一 region 内的 tick 顺序有保证，
     * 跨 region 无保证」这类判断。
     */
    public int regionId() {
        return regionId;
    }

    /**
     * 该执行单元固定使用的线程；null 表示不固定。
     *
     * <p>非 null 时，Mod 可以安全地对该线程做线程局部优化。
     * 但<b>不应</b>依赖它来做正确性判断 —— 未来可能取消固定。
     */
    public Thread pinnedThread() {
        return pinnedThread;
    }

    /**
     * 当前实现是否为单一执行单元 —— 即「还不需要考虑并行」的情形。
     *
     * <p>Mod 可以用它临时关闭并发保护以降低开销，但<b>应当有开关</b>：
     * 平台并行化后这个方法会返回 false，保护会自动生效。
     */
    public boolean isSingleThreaded() {
        return kind == Kind.MAIN;
    }

    /**
     * 两个回调是否保证在同一执行单元内。
     *
     * <p>这是 Mod 判断「我能不能依赖两个回调之间的顺序/可见性」的权威依据。
     * 返回 true 意味着它们之间有 happens-before 关系；返回 false 时
     * Mod 必须自行同步。
     */
    public boolean sameUnit(ExecutionContext other) {
        if (other == null) {
            return false;
        }
        if (kind != other.kind) {
            return false;
        }
        return kind != Kind.REGION || regionId == other.regionId;
    }

    @Override
    public String toString() {
        if (kind == Kind.REGION) {
            return "ExecutionContext[REGION " + regionId + "]";
        }
        if (kind == Kind.ASYNC_POOL) {
            return "ExecutionContext[ASYNC_POOL"
                    + (pinnedThread != null ? " on " + pinnedThread.getName() : "")
                    + "]";
        }
        return "ExecutionContext[MAIN]";
    }
}