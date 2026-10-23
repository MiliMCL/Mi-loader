package org.loader.runtime.minecraft.client.screen;

import org.loader.api.diagnostics.MetricsService;
import org.loader.api.diagnostics.PerformanceSnapshot;
import org.loader.api.diagnostics.PlatformMetrics;
import org.loader.api.gui.ScreenListener;
import org.loader.api.gui.ScreenSpec;
import org.loader.api.input.KeyBinding;
import org.loader.api.input.KeyBindingService;
import org.loader.api.input.KeyBindingSpec;
import org.loader.api.input.KeyEventType;
import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.client.input.KeyBindingDispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台内置的 K 键性能面板。
 *
 * <h2>为什么由平台提供，而不是某个 Mod</h2>
 * 「按 K 看性能」是排障入口，不该依赖玩家装了哪个演示 Mod。更实际的原因是
 * 生命周期：面板读的是 Runtime 的采样器，只有平台自己持有那份引用，
 * 才能保证「面板关闭 → 不再刷新」「Runtime 关闭 → 绑定注销」这两条闭环。
 *
 * <h2>面板只保留四行数据</h2>
 * <pre>
 *   TPS 20.0 / 19.9   FPS 61
 *   tick 1.2 / 3.4 / 12.0 ms   超时 0
 *   堆 182 / 4096 MiB (4%)   GC 12 / 340ms
 *   Mod 2  线程 38/52  类 1840
 * </pre>
 * 每一行都对应一个可采取行动的判断：TPS/FPS 看是否达标，tick 的均值/p95/峰值
 * 看卡顿形态，堆/GC 判断是否 GC 导致，线程/类数看是否泄漏。被删掉的是
 * 「启动次数」「累计运行秒数」「Mod 标题」这类不影响判断的内容。
 *
 * <h2>刷新成本</h2>
 * 文本每 200ms 刷新一次（{@code ScreenHostDispatch} 的节流），快照本身
 * 250ms 合并一次。也就是最多 4 次/秒，而不是每帧 —— 面板自己不能成为
 * 掉帧的原因。
 */
public final class PerformancePanel {

    private static final Logger LOG = Logger.getLogger("Mili/Perf");

    /** 面板 id —— 同时用于判断「当前显示的是不是这个面板」。 */
    static final String PANEL_ID = "mili:performance";

    /** 与原版一致的 GLFW 键码：K。 */
    private static final int KEY_K = 75;

    /** 快照合并窗口：4Hz。 */
    private static final long SNAPSHOT_TTL_NANOS = 250_000_000L;

    private static final AtomicInteger REGISTER_ATTEMPTS = new AtomicInteger();

    private static volatile PerformanceMonitor monitor;
    private static volatile KeyBinding binding;
    private static volatile PerformanceSnapshot cachedPerf;
    private static volatile long cachedAtNanos;

    private static final Runnable ENSURE_HOOK = PerformancePanel::ensureBinding;

    private PerformancePanel() {
    }

    /**
     * 安装面板：注册 K 绑定（延迟到首个客户端 tick，等 Minecraft 实例就绪），
     * 并把自身登记为 Scope 资源。
     */
    public static Resource install(PerformanceMonitor monitor, Scope owner) {
        PerformancePanel.monitor = monitor;
        REGISTER_ATTEMPTS.set(0);
        Registration registration = new Registration(owner, monitor);
        owner.registerResource(registration);
        KeyBindingDispatch.addTickHook(ENSURE_HOOK);
        return registration;
    }

    /**
     * 首个客户端 tick 才注册按键。
     *
     * <p>Mod 的按键在 {@code initialize()} 里注册（游戏 main 之前），
     * 那时 {@code Minecraft.getInstance()} 还是 null，绑定虽然能用，
     * 却不会出现在「控制」界面的按键列表里。平台自己的绑定没有这个
     * 时序限制，等实例就绪再注册，代价只是晚 50ms。
     */
    private static void ensureBinding() {
        if (binding != null) {
            KeyBindingDispatch.removeTickHook(ENSURE_HOOK);
            return;
        }
        if (REGISTER_ATTEMPTS.incrementAndGet() > 100) {
            // 5 秒还没成功 —— 不再重试，也不刷日志
            KeyBindingDispatch.removeTickHook(ENSURE_HOOK);
            LOG.warning("[Mili] 性能面板按键注册失败：客户端输入系统未就绪");
            return;
        }
        try {
            binding = KeyBindingService.register(
                    new KeyBindingSpec(PANEL_ID, "Mili 性能面板", "K"),
                    type -> {
                        if (type == KeyEventType.PRESS) {
                            toggle();
                        }
                    });
            KeyBindingDispatch.removeTickHook(ENSURE_HOOK);
            LOG.fine("[Mili] 性能面板：K 已注册");
        } catch (RuntimeException e) {
            if (REGISTER_ATTEMPTS.get() <= 1) {
                LOG.log(Level.FINE, "[Mili] 性能面板按键暂不可用（将继续重试）", e);
            }
        }
    }

    /** K 的语义：已显示则关闭；显示着别的界面则不动；否则打开。 */
    static void toggle() {
        if (ScreenHostDispatch.isShowing(PANEL_ID)) {
            ScreenHostDispatch.closeRequested();
            return;
        }
        // 原版界面（标题/设置/暂停）之上不打开：关闭时 setScreen(null)
        // 会让玩家落在一个没有菜单的空屏幕。诊断面板只服务游戏内。
        if (ScreenHostDispatch.isAnyScreenOpen()) {
            return;
        }
        open();
    }

    private static void open() {
        PerformanceMonitor m = monitor;
        if (m == null) {
            return;
        }
        try {
            ScreenSpec spec = ScreenSpec.builder(PANEL_ID, "Mili 性能 · Esc 关闭")
                    .size(280, 90)
                    // 诊断面板不能暂停游戏：暂停后 integrated server 停止 tick，
                    // TPS 会掉到 0 —— 观察者把被观察对象停掉了。
                    .pauseGame(false)
                    .hudMode(true)
                    .label("l1", line1(perf()), 10, 8)
                    .label("l2", line2(perf()), 10, 24)
                    .label("l3", line3(MetricsService.current()), 10, 40)
                    .label("l4", line4(MetricsService.current()), 10, 56)
                    .label("hint", "Esc 关闭   ·   K 打开", 10, 74)
                    .build();

            Map<String, Supplier<String>> live = new LinkedHashMap<>();
            live.put("l1", () -> line1(perf()));
            live.put("l2", () -> line2(perf()));
            live.put("l3", () -> line3(MetricsService.current()));
            live.put("l4", () -> line4(MetricsService.current()));

            ScreenHostDispatch.open(spec, new ScreenListener() {
                @Override
                public void onOpen() {
                    LOG.fine("[Mili] 性能面板已打开");
                }

                @Override
                public void onClose(boolean cancelled) {
                    LOG.fine("[Mili] 性能面板已关闭 (cancelled=" + cancelled + ")");
                    cachedPerf = null;
                }
            }, live);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "[Mili] 打开性能面板失败", e);
        }
    }

    // ── 文本 ───────────────────────────────────────────────────────────────

    private static String line1(PerformanceSnapshot p) {
        String fps = p.hasFrameData() ? String.valueOf(p.fps()) : "—";
        return "TPS " + p.tpsText() + "   FPS " + fps;
    }

    private static String line2(PerformanceSnapshot p) {
        String tick = p.hasTickData() ? p.tickText() + " ms" : "—";
        return "tick " + tick + "   超时 " + p.deadlineText();
    }

    private static String line3(PlatformMetrics m) {
        return "堆 " + m.heapText() + "   GC " + m.gcText();
    }

    private static String line4(PlatformMetrics m) {
        return "Mod " + m.modCount()
                + "  线程 " + m.threadText()
                + "  类 " + m.loadedClasses();
    }

    /** 4Hz 合并的快照 —— 四个标签共用一次采样。 */
    private static PerformanceSnapshot perf() {
        long now = System.nanoTime();
        PerformanceSnapshot p = cachedPerf;
        if (p != null && now - cachedAtNanos < SNAPSHOT_TTL_NANOS) {
            return p;
        }
        PerformanceMonitor m = monitor;
        p = m != null ? m.snapshot() : PerformanceSnapshot.empty();
        cachedPerf = p;
        cachedAtNanos = now;
        return p;
    }

    // ── 生命周期 ───────────────────────────────────────────────────────────

    private static final class Registration implements Resource {
        private final Scope owner;
        private final PerformanceMonitor installedMonitor;
        private volatile boolean closed;

        Registration(Scope owner, PerformanceMonitor installedMonitor) {
            this.owner = owner;
            this.installedMonitor = installedMonitor;
        }

        @Override
        public String id() {
            return "mili-performance-panel";
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
            if (closed) {
                return;
            }
            closed = true;
            KeyBindingDispatch.removeTickHook(ENSURE_HOOK);
            KeyBinding current = binding;
            binding = null;
            if (current != null) {
                try {
                    current.unregister();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "[Mili] 注销性能面板按键失败", e);
                }
            }
            ScreenHostDispatch.clearIfShowing(PANEL_ID);
            cachedPerf = null;
            // 只清掉「自己的」引用：多个 Runtime 并存时，后关闭的实例
            // 不该把仍在运行的那个实例的 monitor 置空。
            if (PerformancePanel.monitor == installedMonitor) {
                PerformancePanel.monitor = null;
            }
        }
    }
}
