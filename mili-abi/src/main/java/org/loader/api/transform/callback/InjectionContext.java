package org.loader.api.transform.callback;

/**
 * 注入上下文 —— 传给 Mod 回调方法的运行时信息。
 *
 * <h2>为什么回调必须接收一个上下文对象</h2>
 * 生成到 Minecraft 字节码里的调用只能是静态、无参或固定参数的
 * {@code INVOKESTATIC}。若直接引用 Mod 的实例方法，字节码就会对 Mod 类
 * 形成引用，导致：
 * <ul>
 *   <li>Minecraft 字节码持有 Mod 类引用 → Mod 的 ClassLoader 无法回收
 *       （这是典型的 ClassLoader 泄漏，本仓库已有
 *       {@code ClassLoaderLeakTest} 专门守这条线）；</li>
 *   <li>Mod 热重载后旧类仍被已定义的 MC 类引用，无法卸载。</li>
 * </ul>
 *
 * <p>因此注入的字节码<b>只引用平台自己的分发器</b>
 * （{@code org.loader.loader.transform.dispatch.*}），
 * 分发器内部再按注册表查到真正的 Mod 回调。生成的字节码对 Mod 类<b>零引用</b>。
 *
 * @see InjectionContextCallback
 */
public final class InjectionContext {

    private final String className;
    private final String methodName;
    private final String modId;
    private final long tickId;
    private final Object tickContract;
    private final ExecutionContext executionContext;
    private final Object runtime;
    private final Thread executingThread;

    public InjectionContext(
            String className,
            String methodName,
            String modId,
            long tickId,
            Object tickContract,
            ExecutionContext executionContext,
            Object runtime,
            Thread executingThread) {
        this.className = className;
        this.methodName = methodName;
        this.modId = modId;
        this.tickId = tickId;
        this.tickContract = tickContract;
        // 执行上下文永不为 null：调用方若不关心并行性，
        // 传入 null 会得到 ExecutionContext.single()。
        // 这样 Mod 侧可以无条件调用 context.executionContext().kind()，
        // 不必写 null 检查 —— 而漏写 null 检查是并行化那天最常见的崩溃源。
        this.executionContext = executionContext != null
                ? executionContext : ExecutionContext.single();
        this.runtime = runtime;
        this.executingThread = executingThread;
    }

    /** 被注入的方法所属类（点分名，供人类阅读）。 */
    public String className() {
        return className;
    }

    /** 被注入的方法名。 */
    public String methodName() {
        return methodName;
    }

    /** 拥有该回调的 Mod id。 */
    public String modId() {
        return modId;
    }

    /**
     * 当前 tick 序号；不在 tick 内时为 -1。
     *
     * <p>未来 Region Scheduler 下，同一 tick 内会有多个 region，
     * 届时需要额外的 region 标识 —— 那是 {@link #executionContext()} 的职责。
     */
    public long tickId() {
        return tickId;
    }

    /** 当前 tick 契约；不在 tick 内时为 null。类型在 runtime 侧，此处为 Object 以保持 ABI 零依赖。 */
    public Object tickContract() {
        return tickContract;
    }

    /** 是否处于 tick 内。 */
    public boolean inTick() {
        return tickId >= 0;
    }

    /**
     * 执行上下文 —— 标识本次回调运行在哪个执行单元。
     *
     * <p><b>永不为 null。</b>未显式指定时为
     * {@link ExecutionContext#single()}（当前阶段的唯一取值）。
     *
     * <p>它的存在意义是让 Mod 代码从第一天就面向
     * 「并行 tick 可能发生」编程。典型用法：
     * <pre>
     *   if (ctx.executionContext().isSingleThreaded()) {
     *       // 可以跳过同步 —— 但保留代码路径，平台并行化后自动失效
     *   }
     * </pre>
     * 而不是因为「现在是单线程」就假设回调一定在主线程。
     */
    public ExecutionContext executionContext() {
        return executionContext;
    }

    /**
     * 平台 Runtime 句柄。
     *
     * <p>类型在 runtime 侧，此处为 Object —— ABI 不能反向依赖 runtime。
     * Mod 若需使用，应自行向下转型到它所依赖的 ABI 侧类型。
     */
    public Object runtime() {
        return runtime;
    }

    /** 执行该回调的线程 —— 应当恒为 Minecraft 主线程。 */
    public Thread executingThread() {
        return executingThread;
    }

    @Override
    public String toString() {
        return "InjectionContext[" + className + "#" + methodName
                + ", mod=" + modId
                + ", tick=" + (inTick() ? tickId : "none")
                + ", exec=" + executionContext
                + ", thread=" + (executingThread != null ? executingThread.getName() : "?")
                + "]";
    }
}