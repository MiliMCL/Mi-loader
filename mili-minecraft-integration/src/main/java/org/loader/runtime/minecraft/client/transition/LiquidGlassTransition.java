package org.loader.runtime.minecraft.client.transition;

/**
 * 液态玻璃主菜单转场 —— 状态机与几何，<b>纯逻辑，零 Minecraft 依赖</b>。
 *
 * <h2>规格来源</h2>
 * {@code docs/design/LIQUID_GLASS_MENU_TRANSITION.md}（2026-10-24 设计定稿）。
 * 本类是规格中「锁定参数 + 几何定义」的可执行形式；接入层
 * （{@code MenuTransitionDispatch}）只负责反射与绘制，不持有任何动效决策。
 *
 * <h2>两阶段几何（与定稿小样逐帧一致）</h2>
 * <pre>
 * 阶段一（涌起，coverMillis）：
 *   p = easeInOutCubic(t / cover)
 *   R = (1 + (Rmax-1)·p) · (1 + 0.012·sin(t毫秒 / 90))   // 呼吸脉动
 *   圆盘 = 圆心(点击点) 半径 R
 * 阶段二（泻落，drainMillis）：
 *   q  = easeInQuad((t - cover) / drain)
 *   cy = oy + q·(2·Rmax + H + 40)                        // 圆心垂直下移
 *   圆盘 = 圆心(ox, cy) 半径 Rmax                         // 顶弧即液面
 *
 * 液面线（泻落期）：y(x) = cy − √(Rmax² − (x−ox)²) + 波动项
 *   波动项 = A·(0.8 + 0.2·sin(0.021x)) · sin(0.045x + 2π·t毫秒/210)
 * </pre>
 *
 * <h2>为什么时间由外部注入</h2>
 * {@link #sample(long)} 的入参是「经过的纳秒」而不是内部时钟 ——
 * 整个状态机因此是<b>时间的纯函数</b>，单元测试可以直接驱动任意时刻，
 * 不需要 sleep、不需要 mock 时钟。真实接入由渲染线程每次绘制前取
 * {@code System.nanoTime()} 差值传入。
 *
 * <h2>换屏信号的恰好一次语义</h2>
 * 涌起完成的边界上 {@code Frame.swapDue()} 只在<b>第一次采样</b>返回 true
 * （内部 {@code swapFired} 标记）。接入层据此保证「原版 onPress 恰好被
 * 调用一次」——漏调 = 玩家永远停在主菜单；重调 = 二次换屏。两者都是
 * 不可接受的失效，因此在状态机层用状态位而非调用方自觉来保证。
 */
public final class LiquidGlassTransition {

    /** 转阶段枚举。 */
    public enum Phase { COVER, DRAIN }

    /**
     * 单帧快照 —— 几何消费方（绘制层）需要的全部信息。
     *
     * @param phase   当前阶段
     * @param p       阶段内进度（0..1，已过缓动）
     * @param cx      圆盘圆心 X（恒等于点击点 X）
     * @param cy      圆盘圆心 Y（涌起期 = 点击点 Y；泻落期持续下移）
     * @param r       圆盘半径（含呼吸脉动；涌起期从 1 增长到 Rmax）
     * @param swapDue 是否应在本帧触发原版换屏（恰好一次）
     * @param done    动画已完整走完
     * @param expired 硬超时触发 —— 接入层必须强制收尾
     */
    public record Frame(Phase phase, double p, int cx, int cy, int r,
                        boolean swapDue, boolean done, boolean expired) {
    }

    /**
     * 定稿参数。系统属性可覆盖，读取时钳制到安全区间 ——
     * 配置写错（如 speed=50）不该变成一场癫痫测试。
     */
    public record Params(int coverMillis, int drainMillis, int waveAmplitudePx) {

        /** 设计定稿值（speed 1.0×）。 */
        public static final Params DEFAULT = new Params(300, 360, 6);

        /** 从系统属性构建；未设置时用定稿值。 */
        public static Params fromProperties() {
            float speed = clampFloat(prop("mili.menu.transition.speed", 1.0f), 0.6f, 1.4f);
            int cover = Math.round(clampFloat(
                    prop("mili.menu.transition.cover", DEFAULT.coverMillis), 120f, 800f) / speed);
            int drain = Math.round(clampFloat(
                    prop("mili.menu.transition.drain", DEFAULT.drainMillis), 120f, 1000f) / speed);
            int wave = clampInt(Math.round(prop(
                    "mili.menu.transition.wave", DEFAULT.waveAmplitudePx)), 2, 14);
            return new Params(cover, drain, wave);
        }

        private static float prop(String key, float dft) {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) {
                return dft;
            }
            try {
                return Float.parseFloat(v.trim());
            } catch (NumberFormatException e) {
                return dft;
            }
        }

        private static float clampFloat(float v, float lo, float hi) {
            return v < lo ? lo : Math.min(v, hi);
        }

        private static int clampInt(int v, int lo, int hi) {
            return v < lo ? lo : Math.min(v, hi);
        }
    }

    private final Params params;
    private final int ox;
    private final int oy;
    private final int width;
    private final int height;
    private final int rmax;
    private final long startNanos;

    private boolean swapFired;
    private boolean ended;

    private LiquidGlassTransition(Params params, long startNanos,
                                  int ox, int oy, int width, int height) {
        this.params = params;
        this.startNanos = startNanos;
        this.ox = ox;
        this.oy = oy;
        this.width = width;
        this.height = height;
        this.rmax = computeRmax(ox, oy, width, height);
    }

    /**
     * 启动一次转场。
     *
     * @param params     定稿参数（通常为 {@link Params#fromProperties()}）
     * @param startNanos 起始时刻（{@code System.nanoTime()}）
     * @param ox         点击点 X（GUI 逻辑坐标）
     * @param oy         点击点 Y
     * @param width      当前屏幕逻辑宽
     * @param height     当前屏幕逻辑高
     */
    public static LiquidGlassTransition start(Params params, long startNanos,
                                              int ox, int oy, int width, int height) {
        return new LiquidGlassTransition(params, startNanos, ox, oy, width, height);
    }

    /**
     * 采样当前帧。时间是纯函数：同一时刻重复采样得到相同几何，
     * 但 {@code swapDue}/{@code done} 等一次性信号只触发一次。
     *
     * @param nowNanos 当前时刻（{@code System.nanoTime()}）
     */
    public Frame sample(long nowNanos) {
        long elapsedMs = (nowNanos - startNanos) / 1_000_000L;
        long total = params.coverMillis() + params.drainMillis();
        // 硬超时：正常总时长的 2 倍 + 200ms 余量。超时意味着渲染节奏
        // 严重异常（卡顿/断点），宁可立刻收尾也不能把玩家困在动画里。
        if (elapsedMs > 2 * total + 200L) {
            ended = true;
            fireSwapIfNeeded();
            return new Frame(Phase.DRAIN, 1.0, ox, oy + 2 * rmax + height + 40,
                    rmax, false, true, true);
        }
        if (elapsedMs <= params.coverMillis()) {
            double p = easeInOutCubic((double) elapsedMs / params.coverMillis());
            return new Frame(Phase.COVER, p, ox, oy, coverRadius(nowNanos, p),
                    false, false, false);
        }
        // DRAIN：换屏信号在进入泻落的第一帧发出，且只发一次
        boolean swapDue = fireSwapIfNeeded();
        long t = elapsedMs - params.coverMillis();
        double q = easeInQuad((double) t / params.drainMillis());
        int cy = oy + (int) Math.round(q * (2.0 * rmax + height + 40));
        boolean done = elapsedMs >= total;
        if (done) {
            ended = true;
        }
        return new Frame(Phase.DRAIN, q, ox, cy, rmax, swapDue, done, false);
    }

    /** 动画是否已结束（done 或 expired 之后为 false）。 */
    public boolean isEnded() {
        return ended;
    }

    // ── 几何（绘制层按列消费） ──────────────────────────────────────────────

    /**
     * 圆盘在列 {@code x} 处的弦上端 Y（不含液面波动；弦外返回 {@code Integer.MIN_VALUE}）。
     * 涌起期用帧内 R（含呼吸），泻落期恒为 Rmax。
     */
    public int discTopY(Frame frame, int x) {
        return chord(frame, x, true);
    }

    /** 圆盘在列 {@code x} 处的弦下端 Y；弦外返回 {@code Integer.MAX_VALUE}。 */
    public int discBottomY(Frame frame, int x) {
        return chord(frame, x, false);
    }

    private int chord(Frame frame, int x, boolean top) {
        long dx = (long) x - frame.cx();
        long r = frame.r();
        if (Math.abs(dx) >= r) {
            return top ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        double half = Math.sqrt((double) r * r - (double) dx * dx);
        double y = top ? frame.cy() - half : frame.cy() + half;
        return (int) Math.round(y);
    }

    /**
     * 泻落期液面在列 {@code x} 处的 Y —— 圆盘顶弧叠加正弦波动。
     * 只有 DRAIN 阶段有意义；{@code nowNanos} 驱动时间项。
     */
    public int liquidY(Frame frame, int x, long nowNanos) {
        long dx = (long) x - frame.cx();
        long r = frame.r();
        double arc;
        if (Math.abs(dx) >= r) {
            arc = frame.cy();
        } else {
            arc = frame.cy() - Math.sqrt((double) r * r - (double) dx * dx);
        }
        double tMs = (nowNanos - startNanos) / 1_000_000.0;
        double amplitude = params.waveAmplitudePx() * (0.8 + 0.2 * Math.sin(0.021 * x));
        double wobble = amplitude * Math.sin(0.045 * x + 2.0 * Math.PI * tMs / 210.0);
        return (int) Math.round(arc + wobble);
    }

    /** 涌起期液珠数量（定稿：6 颗微珠贴圆盘边缘游走）。 */
    public int beadCount() {
        return 6;
    }

    /** 第 {@code i} 颗液珠的 X。 */
    public int beadX(Frame frame, int i, long nowNanos) {
        double tMs = (nowNanos - startNanos) / 1_000_000.0;
        double ang = i * 2.0 * Math.PI / beadCount() + tMs / 900.0;
        return (int) Math.round(frame.cx() + Math.cos(ang) * frame.r() * 0.99);
    }

    /** 第 {@code i} 颗液珠的 Y。 */
    public int beadY(Frame frame, int i, long nowNanos) {
        double tMs = (nowNanos - startNanos) / 1_000_000.0;
        double ang = i * 2.0 * Math.PI / beadCount() + tMs / 900.0;
        return (int) Math.round(frame.cy() + Math.sin(ang) * frame.r() * 0.99);
    }

    /** 第 {@code i} 颗液珠的半径（4..7px 呼吸）。 */
    public int beadRadius(Frame frame, int i, long nowNanos) {
        double tMs = (nowNanos - startNanos) / 1_000_000.0;
        return 4 + (int) Math.round(3 * Math.abs(Math.sin(i * 2.1 + tMs / 300.0)));
    }

    /** 涌起期玻璃底色的透明度因子（0..0.04，随进度线性升）。 */
    public double coverFillAlpha(Frame frame) {
        return 0.04 * frame.p();
    }

    /** 涌起期液体高光的透明度因子（0..0.20，随进度线性升）。 */
    public double coverLiquidAlpha(Frame frame) {
        return 0.20 * frame.p();
    }

    /** 泻落期液体高光的透明度因子（定稿 0.26）。 */
    public double drainLiquidAlpha() {
        return 0.26;
    }

    // ── 缓动与几何辅助（包级可见，供测试直接断言） ──────────────────────────

    static double easeInOutCubic(double t) {
        if (t <= 0.0) {
            return 0.0;
        }
        if (t >= 1.0) {
            return 1.0;
        }
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3) / 2.0;
    }

    static double easeInQuad(double t) {
        if (t <= 0.0) {
            return 0.0;
        }
        if (t >= 1.0) {
            return 1.0;
        }
        return t * t;
    }

    /** Rmax = 点击点到四角的最大距离 + 40（余量保证覆盖边缘锯齿）。 */
    static int computeRmax(int ox, int oy, int width, int height) {
        double max = 0;
        double[] corners = {0, 0, width, 0, 0, height, width, height};
        for (int i = 0; i < corners.length; i += 2) {
            double d = Math.hypot(corners[i] - ox, corners[i + 1] - oy);
            if (d > max) {
                max = d;
            }
        }
        return (int) Math.ceil(max) + 40;
    }

    private int coverRadius(long nowNanos, double p) {
        double grow = 1.0 + (rmax - 1) * p;
        double tMs = (nowNanos - startNanos) / 1_000_000.0;
        return (int) Math.round(grow * (1.0 + 0.012 * Math.sin(tMs / 90.0)));
    }

    private boolean fireSwapIfNeeded() {
        if (swapFired) {
            return false;
        }
        swapFired = true;
        return true;
    }
}
