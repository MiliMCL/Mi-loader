package org.loader.runtime.minecraft.transform;

import org.loader.runtime.minecraft.TickBridge;

import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tick 回调分发器 —— 生成到 Minecraft 字节码里的唯一调用目标。
 *
 * <h2>为什么生成字节码只引用本类</h2>
 * {@code MinecraftClassLoader} 定义的类会持有它引用的所有类的加载器约束。
 * 若注入的字节码直接调用 Mod 的静态方法：
 * <pre>
 *   MinecraftServer（由 MinecraftClassLoader 定义）
 *        └── 常量池引用 ModClass → Mod 的 ClassLoader 无法回收
 * </pre>
 * 这就是 ClassLoader 泄漏。本仓库已有 {@code ClassLoaderLeakTest} 守这条线。
 *
 * <p>因此注入的字节码只引用本类（由平台 AppClassLoader 定义），
 * 本类再通过持有 {@link TickBridge} 引用推进 tick 状态。
 * <b>Minecraft 字节码对 Mod 类零引用。</b>
 *
 * <h2>为什么所有异常都必须在这里吞掉</h2>
 * 本类的方法是从 {@code MinecraftServer#tickServer} 里调用的。
 * 若异常逃逸，会中断整个游戏主循环，且堆栈指向 Minecraft 内部 ——
 * 玩家与 Mod 作者都无法判断是自己的 Mod 导致的。
 *
 * <p>因此规则是：<b>捕获、记录到 tick 契约、继续执行游戏逻辑</b>。
 * 平台宁可让一次 tick 少做一件事，也不让游戏崩掉。
 *
 * <h2>静态状态的线程约束</h2>
 * 当前阶段只有 single-thread 正确性：{@link #activeBridge} 与
 * {@link #depth} 只在 Minecraft 主线程访问。
 * 未来 Region Scheduler 引入并行 tick 时，这里需要改为按 region 维度
 * 存储 —— 那时 {@link InjectionContext#executionContext()} 才有意义。
 */
public final class TickCallbackDispatch {

    /**
     * 注册为一个 Resource —— <b>不是可选的。</b>
     *
     * <p>本类持有静态的 {@link #activeBridge} 引用，而
     * {@code TickBridge} 持有 {@code TickEngine} 与 {@code Scope}。
     * 若不随 Scope 一起释放，静态字段会让整条对象图在游戏退出后
     * 仍可达 —— 那正是 {@code ClassLoaderLeakTest} 要守的那类泄漏，
     * 只不过泄漏源从「类引用」变成了「静态字段」。
     */
    private static final class Registration implements Resource {
        private final Scope owner;
        private volatile boolean closed;

        Registration(Scope owner) {
            this.owner = owner;
        }

        @Override
        public String id() {
            return "tick-callback-dispatch";
        }

        @Override
        public Scope owner() {
            return owner;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                // 卸载桥接：此后注入回调静默返回，不再推进任何 tick
                install(null);
            }
        }
    }

    /**
     * 当前生效的桥接。
     *
     * <p>用 volatile 而非裸静态字段：安装发生在游戏启动阶段
     * （可能与类加载并发），tick 读取发生在主线程。
     */
    private static volatile TickBridge activeBridge;

    /** 嵌套深度 —— 用于检测重复注入。 */
    private static final AtomicInteger depth = new AtomicInteger();

    private static final AtomicLong beginCount = new AtomicLong();
    private static final AtomicLong endCount = new AtomicLong();
    private static final AtomicLong errorCount = new AtomicLong();
    private static final AtomicReference<String> lastError = new AtomicReference<>();

    private TickCallbackDispatch() {
    }

    /**
     * 安装桥接 —— 由平台在游戏初始化完成后调用一次。
     *
     * @param bridge 要驱动的桥；传 null 表示卸载
     */
    public static void install(TickBridge bridge) {
        activeBridge = bridge;
        if (bridge != null) {
            depth.set(0);
            beginCount.set(0);
            endCount.set(0);
        }
    }

    /**
     * 把分发器注册为 Scope 资源，使其随 Scope 一同释放。
     *
     * <p>调用一次即可；重复调用是幂等的。
     *
     * @param owner 拥有分发器的 Scope（通常是 minecraftScope）
     * @return 已注册的资源，供诊断
     */
    public static Resource register(Scope owner) {
        Registration registration = new Registration(owner);
        owner.registerResource(registration);
        return registration;
    }

    /** 当前桥接；未安装时为 null。 */
    public static TickBridge bridge() {
        return activeBridge;
    }

    public static boolean isInstalled() {
        return activeBridge != null;
    }

    // ── 注入回调（由生成字节码调用） ────────────────────────────────────────

    /**
     * tick 开始 —— 注入在 {@code tickServer} 方法头。
     *
     * <p>描述符固定为 {@code ()V}：不消费任何参数、不返回值，
     * 因此插入位置前后栈高度不变。
     */
    public static void onTickBegin() {
        TickBridge bridge = activeBridge;
        if (bridge == null) {
            // 未安装 —— 游戏尚未初始化到可 tick 状态。
            // 这不是错误：Minecraft 启动过程中会先跑若干 tick。
            return;
        }
        try {
            // 深度检查：若同一个 tick 被 begin 两次，说明字节码被重复转换了。
            // 这是 TransformationCache 失效的直接证据，必须显式记录 ——
            // 它的表现是「tick 数翻倍但游戏正常」，极难察觉。
            int d = depth.incrementAndGet();
            if (d > 1) {
                errorCount.incrementAndGet();
                lastError.set("重复进入 tick（depth=" + d
                        + "）—— 字节码可能被重复转换。"
                        + "检查 TransformationCache 是否失效。");
                return;
            }
            beginCount.incrementAndGet();
            bridge.beginTick();
        } catch (Throwable t) {
            record(t, "onTickBegin");
            // 深度回退：begin 失败后必须归零，否则本 tick 之后
            // 所有 tick 都会被误判为「嵌套」而永久失效。
            depth.set(0);
        }
    }

    /**
     * tick 结束 —— 注入在 {@code tickServer} 的每个返回路径之前。
     *
     * <p>说明：{@code onMethodExit} 语义保证<b>每个</b> xRETURN 前都会触发，
     * 因此带 early return 的路径不会漏掉 —— 这是「异常退出路径不推进 tick
     * 状态」的唯一正确解法。
     */
    public static void onTickEnd() {
        TickBridge bridge = activeBridge;
        if (bridge == null) {
            return;
        }
        try {
            int d = depth.getAndSet(0);
            if (d == 0) {
                // 没有对应的 begin —— 例如 tick 中途抛异常导致状态错乱。
                // 不补调 endTick：那会推进一个不存在的契约。
                return;
            }
            endCount.incrementAndGet();
            bridge.endTick();
        } catch (Throwable t) {
            record(t, "onTickEnd");
        }
    }

    private static void record(Throwable t, String where) {
        errorCount.incrementAndGet();
        lastError.set(where + ": " + t.getClass().getName()
                + (t.getMessage() != null ? " — " + t.getMessage() : ""));
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    public static long beginCount() {
        return beginCount.get();
    }

    public static long endCount() {
        return endCount.get();
    }

    public static long errorCount() {
        return errorCount.get();
    }

    public static String lastError() {
        return lastError.get();
    }

    /**
     * begin/end 计数是否配平。
     *
     * <p>不配平意味着有 tick 没有正常结束 —— 通常是 tick 中途抛异常。
     * 暴露出来是为了让测试能断言这一点，而不是等到指标慢慢漂移。
     */
    public static boolean isBalanced() {
        return beginCount.get() == endCount.get();
    }

    public static String diagnostics() {
        return "TickCallbackDispatch[installed=" + (activeBridge != null)
                + ", begins=" + beginCount.get()
                + ", ends=" + endCount.get()
                + ", balanced=" + isBalanced()
                + ", errors=" + errorCount.get()
                + (lastError.get() != null ? ", lastError=" + lastError.get() : "")
                + "]";
    }

    /** 供测试重置计数（不卸载桥接）。 */
    static void resetCountersForTesting() {
        beginCount.set(0);
        endCount.set(0);
        errorCount.set(0);
        lastError.set(null);
        depth.set(0);
    }
}
