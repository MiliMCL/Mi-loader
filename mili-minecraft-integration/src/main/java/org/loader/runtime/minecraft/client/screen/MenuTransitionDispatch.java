package org.loader.runtime.minecraft.client.screen;

import org.loader.runtime.minecraft.client.transition.LiquidGlassTransition;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主菜单液态玻璃转场分发器 —— 接管「单人游戏 / 多人游戏」按钮，
 * 在原版换屏前后播放转场动画。
 *
 * <h2>规格与参数</h2>
 * 全部动效参数与几何见
 * {@code docs/design/LIQUID_GLASS_MENU_TRANSITION.md}（2026-10-24 定稿）；
 * 状态机在 {@link LiquidGlassTransition}（纯逻辑，本类不持任何动效决策）。
 *
 * <h2>接入点（两个 RETURN 注入，均在 Screen 上）</h2>
 * <ul>
 *   <li>{@code Screen#init(II)} RETURN —— {@code public final}、由
 *       setScreen/resize 路径调用，先跑子类 {@code init()}（按钮创建）
 *       再返回；对全部屏幕触发，回调内按 {@code TitleScreen} 实例过滤。
 *       在这里把原版按钮<b>替换</b>为包装版（见下）。由
 *       {@code MiliMenuTransitionTransformer} 注入。（历史注记：
 *       原锚点 TitleScreen#init 与 Mods 按钮转换器同方法冲突，
 *       管线「最后者覆盖」语义把它静默丢弃 —— 2026-10-24 热修挪到这里。）</li>
 *   <li>{@code Screen#extractRenderStateWithTooltipAndSubtitles} RETURN ——
 *       每帧在整屏（含 tooltip）画完之后触发，转场激活时把玻璃画在最上层。
 *       回调是生成的桥接类 {@code MiliGlassBridge}（描述符必须携带
 *       Minecraft 类型，见 {@link GlassBridgeGenerator}），它把参数降级为
 *       {@code Object} 转进 {@link #onScreenRender}。</li>
 * </ul>
 *
 * <h2>桥接类的定义时机：转换期，先于注入</h2>
 * 注入的 INVOKESTATIC 在首次执行时解析目标类；26.2 的 {@code LoadingOverlay}
 * 在淡入/淡出阶段会直接调用当前屏幕的提取方法 —— 渲染钩子早在主菜单
 * 出现之前就会执行。因此桥接类由转换器在<b>转换期</b>（Screen 类定义之前）
 * 通过 {@link #ensureBridgeDefinedEager()} 定义；定义失败时转换器拒绝注入，
 * 绝不让游戏带着解析必炸的调用点运行。init 回调里的定义调用退化为幂等兜底。
 *
 * <h2>按钮接管的形态</h2>
 * 原版按钮的 {@code onPress} 字段是 {@code protected final} —— JDK 25 下
 * final 字段不可写，也不该写。做法是<b>重建</b>：反射读出原按钮的
 * message / active / onPress / bounds，用原版 {@code Button.builder} 造一个
 * 同位置、同文案、同回调语义的新按钮，OnPress 用动态代理包装：
 * 转场未激活时原样转发原回调（行为与原版一致），激活期吞掉连点，
 * 涌起完成时再触发真正的换屏。换入用 {@code removeWidget} +
 * {@code addRenderableWidget}，与 {@code TitleScreenDispatch} 的 Mods 按钮
 * 同一套手法。窗口 resize 会重跑 init：clearWidgets 之后一切重来，
 * 幂等性由「原版按钮天然重建」保证，无需记忆状态。
 *
 * <h2>为什么渲染钩子选 Screen 基类而不是 Overlay</h2>
 * 26.2 的 {@code Gui.extractRenderState} 对 overlay 与 screen 是
 * <b>if / else if 互斥渲染</b> —— 挂 Overlay 的瞬间旧屏就不再绘制，
 * 玻璃后面只剩背景。要让「旧屏透在玻璃外、新屏透在玻璃内」，
 * 唯一的办法是把绘制塞进屏幕自己的渲染通道之后：
 * {@code extractRenderStateWithTooltipAndSubtitles} 是 {@code public final}、
 * 只在 {@code Screen} 定义一次、无 early return —— 对它 RETURN 注入，
 * 每帧恰好触发一次，且转换 Screen 一个类即覆盖全部屏幕。
 *
 * <h2>异常策略</h2>
 * 两个入口都从游戏渲染/初始化路径被调用，<b>任何异常都必须吞掉</b>。
 * 渲染异常的降级是「立即完成换屏 + 结束动画」—— 玩家最多损失一段动画，
 * 绝不能被动画困在主菜单。失败计数进 {@link #errorCount()}。
 */
public final class MenuTransitionDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/MenuTransition");

    // ── 反射坐标（全部按 26.2 反编译源核实） ────────────────────────────────
    private static final String MC = "net.minecraft.client.Minecraft";
    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String TITLE_SCREEN = "net.minecraft.client.gui.screens.TitleScreen";
    private static final String BUTTON = "net.minecraft.client.gui.components.Button";
    private static final String GUI_EVENT_LISTENER =
            "net.minecraft.client.gui.components.events.GuiEventListener";
    private static final String GRAPHICS =
            "net.minecraft.client.gui.GuiGraphicsExtractor";

    // ── 定稿色（RGB；alpha 由状态机的透明度因子逐帧合成） ───────────────────
    private static final int GLASS_RGB = 0xDCE8FC;
    private static final int LIQUID_RGB = 0xEAF3FC;
    private static final int WHITE = 0xFFFFFF;
    /** 玻璃描边 alpha（定稿 0.28）。 */
    private static final int RIM_ALPHA = 71;
    /** 泻落期玻璃底色 alpha（定稿 0.04）。 */
    private static final int DRAIN_GLASS_ALPHA = 10;
    /** 液面带半高（定稿小样里约 6px 厚的连续液带）。 */
    private static final int BAND_HALF = 3;
    /** 竖条步进（px）：圆盘与液面按 2px 列填充。 */
    private static final int STRIDE = 2;

    /** 原版按钮几何（TitleScreen#createNormalMenuOptions，26.2 已核实）。 */
    private static final int BUTTON_W = 200;
    private static final int BUTTON_H = 20;
    private static final int BUTTON_X_OFFSET = -100;

    // ── 状态 ────────────────────────────────────────────────────────────────

    /** 我们重建过的按钮（弱引用，判据与 TitleScreenDispatch 相同：widget 身份）。 */
    private static final WeakHashMap<Object, Boolean> installedButtons =
            new WeakHashMap<>();

    /** 当前转场会话；null = 空闲。volatile：渲染回调与按钮回调同线程，防御性保留。 */
    private static volatile LiquidGlassTransition session;
    /** 涌起完成时该跑的原版换屏动作（原按钮的 OnPress 调用）。 */
    private static volatile Runnable pendingSwap;
    private static volatile boolean swapInvoked;
    /** 会话开始时的屏幕逻辑尺寸 —— resize 到中途不追新，硬超时兜底。 */
    private static volatile int sessionWidth;
    private static volatile int sessionHeight;
    /** selftest 用：单人按钮中心（接管时记录）。 */
    private static volatile int[] singleplayerCenter;

    private static final AtomicLong errorCount = new AtomicLong();
    private static final AtomicLong transitionCount = new AtomicLong();
    private static final AtomicBoolean selfTestPending = new AtomicBoolean(true);

    private static volatile boolean bridgeReady;
    private static volatile boolean bridgeFailed;

    private static volatile Method mFill;
    private static volatile Method mFillGradient;
    /** 缓存的 {@code Button$OnPress.onPress(Button)} —— 见 {@link #invokeOriginal}。 */
    private static volatile Method mOnPress;

    private MenuTransitionDispatch() {
    }

    /** 总开关：{@code -Dmili.menu.transition}（默认开）。每次接管时现读，支持运行期关闭。 */
    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("mili.menu.transition", "true"));
    }

    // ── 注入回调 1：Screen#init(II) RETURN ─────────────────────────────────

    /**
     * {@code Screen#init(II)} 每个返回路径都会调用（描述符固定 {@code ()V}），
     * 对全部屏幕触发；此处按 {@code TitleScreen} 实例过滤，其余屏幕空转。
     * init 由 setScreen/resize 路径调用，且方法体先跑子类 {@code init()}
     * （按钮创建）再返回 —— 回调触发时原版按钮已存在。
     */
    public static void onScreenInitReturn() {
        if (!enabled()) {
            return;
        }
        if (!ensureBridgeDefinedEager()) {
            return;
        }
        try {
            Object screen = currentScreen();
            if (screen != null) {
                takeoverButtons(screen);
                maybeRunSelfTest();
            }
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 主菜单按钮接管失败（转场不可用，游戏不受影响）", t);
        }
    }

    // ── 注入回调 2：Screen#extractRenderStateWithTooltipAndSubtitles RETURN ──

    /**
     * 每帧整屏绘制完成后的回调（由生成的桥接类转发，参数已降级为 Object）。
     * 空闲时只有一次 volatile 读 —— 全部屏幕、每一帧都走这里，必须便宜。
     */
    public static void onScreenRender(Object graphics, int mouseX, int mouseY,
                                      float partialTick) {
        LiquidGlassTransition s = session;
        if (s == null || graphics == null) {
            return;
        }
        try {
            LiquidGlassTransition.Frame f = s.sample(System.nanoTime());
            // swapDue 是状态机的恰好一次信号；expired 是硬超时 ——
            // 两者都必须保证「原版换屏最终被调用一次」，绝不能把玩家
            // 困在主菜单（漏调）或换两次屏（重调）。
            if ((f.swapDue() || f.expired()) && !swapInvoked) {
                swapInvoked = true;
                Runnable swap = pendingSwap;
                pendingSwap = null;
                if (swap != null) {
                    swap.run();
                    // 这条 INFO 是「换屏真的发生了」的唯一正面证据 ——
                    // invokeOriginal 的失败只进 stderr（WARNING），而它一旦
                    // 失败玩家就停在主菜单。平台日志里能看到这行，才能把
                    // 「进不去游戏」这类投诉与转场链路切割开。
                    LOG.info("[Mili] 转场交棒：原版换屏已执行");
                }
            }
            if (f.done() || f.expired()) {
                endSession(s);
                return;
            }
            draw(graphics, s, f, System.nanoTime());
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 转场渲染异常，降级为瞬时切换", t);
            forceFinish();
        }
    }

    // ── 按钮接管 ────────────────────────────────────────────────────────────

    private static void takeoverButtons(Object screen) throws Exception {
        Class<?> titleClass = Reflect.gameClass(TITLE_SCREEN);
        if (!titleClass.isInstance(screen)) {
            return;
        }
        Class<?> screenClass = Reflect.gameClass(SCREEN);
        int w = screenClass.getField("width").getInt(screen);
        int h = screenClass.getField("height").getInt(screen);
        // TitleScreen#createNormalMenuOptions：单人 (w/2-100, h/4+48)、
        // 多人 (w/2-100, h/4+48+24)，均 200x20。demo 模式没有这两个按钮
        // —— 找不到就静默返回，与原版行为一致。
        int[][] targets = {
                {w / 2 + BUTTON_X_OFFSET, h / 4 + 48},
                {w / 2 + BUTTON_X_OFFSET, h / 4 + 48 + 24}
        };
        @SuppressWarnings("unchecked")
        List<Object> children = (List<Object>) screen.getClass()
                .getMethod("children").invoke(screen);
        // 为什么用快照遍历：动作里的 replaceButton 会 removeWidget /
        // addRenderableWidget —— 两者都直接增删 children 本体。在原列表上
        // 边遍历边删，替换完第一个按钮迭代器立即 ConcurrentModificationException，
        // 第二个按钮（多人游戏）永远接管不上（2026-10-28 真机自测连续 7 次 CME）。
        forEachSnapshot(children, widget -> {
            if (!Reflect.gameClass(BUTTON).isInstance(widget)
                    || installedButtons.containsKey(widget)) {
                return;
            }
            int x;
            int y;
            int bw;
            int bh;
            try {
                x = (int) invokeNoArg(widget, "getX");
                y = (int) invokeNoArg(widget, "getY");
                bw = (int) invokeNoArg(widget, "getWidth");
                bh = (int) invokeNoArg(widget, "getHeight");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (int[] target : targets) {
                if (x == target[0] && y == target[1]
                        && bw == BUTTON_W && bh == BUTTON_H) {
                    try {
                        replaceButton(screen, widget, y == targets[0][1]);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
            }
        });
    }

    /**
     * 快照遍历：迭代器挂在 {@code children} 的副本上，动作里对原列表的
     * 增删（removeWidget / addRenderableWidget）不会触发 CME。
     * 包级可见供回归测试直接验证「遍历中修改原列表」的契约。
     */
    static void forEachSnapshot(List<Object> children,
                                java.util.function.Consumer<Object> action) {
        for (Object widget : new ArrayList<>(children)) {
            action.accept(widget);
        }
    }

    /**
     * 用包装版按钮替换原版按钮：读出 message / active / onPress / bounds，
     * 重建一个同位按钮，OnPress 走动态代理。
     */
    private static void replaceButton(Object screen, Object oldButton,
                                      boolean isSingleplayer) throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        Class<?> buttonClass = Reflect.gameClass(BUTTON);
        Class<?> screenClass = Reflect.gameClass(SCREEN);
        Class<?> listenerClass = Reflect.gameClass(GUI_EVENT_LISTENER);
        Class<?> componentClass = Reflect.gameClass("net.minecraft.network.chat.Component");
        Class<?> onPressClass = Class.forName(BUTTON + "$OnPress", true, game);

        // final 字段只读不写 —— JDK 25 下写 final 本就不可行
        Field onPressField = buttonClass.getDeclaredField("onPress");
        onPressField.setAccessible(true);
        Object originalOnPress = onPressField.get(oldButton);

        Object message = oldButton.getClass().getMethod("getMessage").invoke(oldButton);
        // AbstractWidget.active 是 public 字段（26.2 已核实）—— 多人按钮
        // 被封禁时 active=false，必须原样保留，否则会给被封禁玩家开门。
        boolean active = buttonClass.getField("active").getBoolean(oldButton);
        int x = (int) invokeNoArg(oldButton, "getX");
        int y = (int) invokeNoArg(oldButton, "getY");
        int bw = (int) invokeNoArg(oldButton, "getWidth");
        int bh = (int) invokeNoArg(oldButton, "getHeight");

        Object onPress = Proxy.newProxyInstance(game, new Class<?>[]{onPressClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "Mili Transition Button";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    handleMenuPress(args != null && args.length > 0 ? args[0] : null,
                            originalOnPress);
                    return null;
                });

        Object builder = buttonClass
                .getMethod("builder", componentClass, onPressClass)
                .invoke(null, message, onPress);
        builder = builder.getClass()
                .getMethod("bounds", int.class, int.class, int.class, int.class)
                .invoke(builder, x, y, bw, bh);
        Object newButton = builder.getClass().getMethod("build").invoke(builder);
        buttonClass.getField("active").setBoolean(newButton, active);

        Method remove = screenClass.getDeclaredMethod("removeWidget", listenerClass);
        remove.setAccessible(true);
        remove.invoke(screen, oldButton);
        Method add = findInherited(screenClass, "addRenderableWidget", 1);
        add.setAccessible(true);
        add.invoke(screen, newButton);

        installedButtons.put(newButton, Boolean.TRUE);
        if (isSingleplayer) {
            singleplayerCenter = new int[]{x + bw / 2, y + bh / 2};
        }
        LOG.fine("[Mili] 转场按钮已接管 (" + x + "," + y + " " + bw + "x" + bh + ")");
    }

    /** 按钮被点：总开关关闭 → 原样转发；转场进行中 → 吞掉（定稿 §0 连点策略）；空闲 → 启动会话。 */
    private static void handleMenuPress(Object button, Object originalOnPress) {
        if (!enabled()) {
            invokeOriginal(originalOnPress, button);
            return;
        }
        if (session != null) {
            // 转场激活期吞掉连点：转发会让原版 setScreen 在覆盖中途发生，
            // 玻璃几何与屏幕内容错位 —— 正是规格明确要防的失效形态。
            return;
        }
        try {
            int[] wh = screenSize();
            int[] center = widgetCenter(button);
            if (wh == null || center == null) {
                invokeOriginal(originalOnPress, button);
                return;
            }
            session = LiquidGlassTransition.start(
                    LiquidGlassTransition.Params.fromProperties(),
                    System.nanoTime(), center[0], center[1], wh[0], wh[1]);
            sessionWidth = wh[0];
            sessionHeight = wh[1];
            swapInvoked = false;
            pendingSwap = () -> invokeOriginal(originalOnPress, button);
            transitionCount.incrementAndGet();
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 转场启动失败，退回瞬时切换", t);
            invokeOriginal(originalOnPress, button);
        }
    }

    /**
     * 触发原版换屏回调。
     *
     * <p><b>为什么必须从接口 {@code Button$OnPress} 解析 Method</b>：
     * 原版 OnPress 实例是 LambdaMetafactory 生成的 <b>hidden class</b>
     * （JEP 371）。在 hidden class 上 {@code getClass().getMethod(...)} 得到的
     * Method 其声明类就是 hidden class 本身，而 hidden class 的成员对定义
     * 之外的一切类都不可访问 —— <b>连 public 修饰也照样抛
     * {@code IllegalAccessException}</b>。2026-10-28 真机自测证实：涌起完成后
     * 原版换屏从未执行，玩家被困在主菜单，每次点击都是白点（点单人、点多人
     * 都只见转场动画，界面纹丝不动）。
     * 从公开接口解析则声明类是 public 接口，跨 ClassLoader 的访问检查通过。
     */
    static void invokeOriginal(Object onPress, Object button) {
        try {
            onPressMethod().invoke(onPress, button);
        } catch (Throwable t) {
            // 原版回调自身失败按原版语义向上抛是不可能的（反射包装）——
            // 记录后吞掉。到这里动画已结束或从未开始，界面已切走。
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 原版按钮回调执行失败", t);
        }
    }

    private static Method onPressMethod() throws Exception {
        Method m = mOnPress;
        if (m == null) {
            m = Class.forName(BUTTON + "$OnPress", true, Reflect.gameClassLoader())
                    .getMethod("onPress", Reflect.gameClass(BUTTON));
            // 防御性 setAccessible：声明类是 public 接口，正常无需豁免；
            // 但游戏若运行在具名模块里，显式豁免可省去一次跨模块检查。
            m.setAccessible(true);
            mOnPress = m;
        }
        return m;
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────

    private static void draw(Object g, LiquidGlassTransition s,
                             LiquidGlassTransition.Frame f, long now) throws Exception {
        int w = sessionWidth;
        int h = sessionHeight;
        if (f.phase() == LiquidGlassTransition.Phase.COVER) {
            // 玻璃盘用上亮下暗的垂直渐变替代平涂：平涂 alpha 0.04 在游戏
            // 背景上几乎不可见，渐变才读得出「玻璃的厚度」。
            int glassAlpha = (int) Math.round(s.coverFillAlpha(f) * 255.0);
            int topAlpha = Math.min(255, (int) Math.round(glassAlpha * 3.2));
            int bottomAlpha = Math.max(1, glassAlpha / 2);
            drawDiscGradient(g, s, f, w, h,
                    argb(topAlpha, GLASS_RGB), argb(bottomAlpha, GLASS_RGB));
            int rimAlpha = (int) Math.round(RIM_ALPHA * f.p());
            drawRim(g, s, f, w, h, argb(rimAlpha, WHITE));
            int liquidAlpha = (int) Math.round(s.coverLiquidAlpha(f) * 255.0);
            drawBeads(g, s, f, now, argb(liquidAlpha, LIQUID_RGB));
        } else {
            int glassAlpha = DRAIN_GLASS_ALPHA;
            drawDiscGradient(g, s, f, w, h,
                    argb(Math.min(255, glassAlpha * 3), GLASS_RGB),
                    argb(glassAlpha, GLASS_RGB));
            // 液面随泻落进度淡出 —— 旧实现恒定 0.26 直到最后一帧戛然而止，
            // 收尾观感是「闪断」；随 (1-q) 衰减后液面是滑出画面而不是消失。
            double fade = 1.0 - f.p();
            int liquidAlpha = (int) Math.round(s.drainLiquidAlpha() * 255.0 * fade);
            int rimAlpha = (int) Math.round(RIM_ALPHA * fade);
            if (liquidAlpha > 0 || rimAlpha > 0) {
                drawLiquidBand(g, s, f, now, w, argb(liquidAlpha, LIQUID_RGB),
                        argb(rimAlpha, WHITE));
            }
        }
    }

    /** 圆盘：逐 2px 列做垂直渐变填充（顶亮底暗）。约 250 列、250 次 fillGradient。 */
    private static void drawDiscGradient(Object g, LiquidGlassTransition s,
                                         LiquidGlassTransition.Frame f, int w, int h,
                                         int topArgb, int bottomArgb) {
        int x0 = Math.max(0, f.cx() - f.r());
        int x1 = Math.min(w - 1, f.cx() + f.r());
        for (int x = x0; x <= x1; x += STRIDE) {
            int top = s.discTopY(f, x);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            int bottom = Math.min(s.discBottomY(f, x) + 1, h);
            int y0 = Math.max(0, top);
            if (bottom <= y0) {
                continue;
            }
            fillGradient(g, x, y0, Math.min(x + STRIDE, w), bottom,
                    topArgb, bottomArgb);
        }
    }

    /** 圆盘描边：每列在弦的两端画 1px。 */
    private static void drawRim(Object g, LiquidGlassTransition s,
                                LiquidGlassTransition.Frame f, int w, int h, int argb) {
        int x0 = Math.max(0, f.cx() - f.r());
        int x1 = Math.min(w - 1, f.cx() + f.r());
        for (int x = x0; x <= x1; x += STRIDE) {
            int top = s.discTopY(f, x);
            if (top == Integer.MIN_VALUE) {
                continue;
            }
            int bottom = s.discBottomY(f, x);
            fill(g, x, Math.max(0, top), Math.min(x + STRIDE, w), Math.max(0, top) + 1, argb);
            fill(g, x, Math.min(h - 1, bottom), Math.min(x + STRIDE, w),
                    Math.min(h, bottom + 1), argb);
        }
    }

    /** 涌起期：6 颗微珠贴圆盘边缘游走（定稿形态）。 */
    private static void drawBeads(Object g, LiquidGlassTransition s,
                                  LiquidGlassTransition.Frame f, long now, int argb) {
        if (argb >>> 24 == 0) {
            return;
        }
        for (int i = 0; i < s.beadCount(); i++) {
            int bx = s.beadX(f, i, now);
            int by = s.beadY(f, i, now);
            int r = s.beadRadius(f, i, now);
            for (int dy = -r; dy <= r; dy++) {
                int half = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
                fill(g, bx - half, by + dy, bx + half + 1, by + dy + 1, argb);
            }
        }
    }

    /** 泻落期：液面带贴圆盘顶弧逐列填充 + 1px 表面高光 —— 一条连续波纹。 */
    private static void drawLiquidBand(Object g, LiquidGlassTransition s,
                                       LiquidGlassTransition.Frame f, long now, int w,
                                       int liquid, int rim) {
        for (int x = 0; x < w; x += STRIDE) {
            int y = s.liquidY(f, x, now);
            fill(g, x, y - BAND_HALF, Math.min(x + STRIDE, w), y + BAND_HALF + 1, liquid);
            fill(g, x, y, Math.min(x + STRIDE, w), y + 1, rim);
        }
    }

    private static int argb(int alpha, int rgb) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    /** 反射 fillGradient —— 缓存 Method；失败策略与 {@link #fill} 相同。 */
    private static void fillGradient(Object g, int x0, int y0, int x1, int y1,
                                     int col1, int col2) {
        try {
            Method m = mFillGradient;
            if (m == null) {
                m = Reflect.gameClass(GRAPHICS)
                        .getMethod("fillGradient", int.class, int.class, int.class,
                                int.class, int.class, int.class);
                mFillGradient = m;
            }
            m.invoke(g, x0, y0, x1, y1, col1, col2);
        } catch (Throwable t) {
            errorCount.incrementAndGet();
        }
    }

    /** 反射 fill —— 缓存 Method；单条失败只计数（与 ModListScreenDispatch 同策略）。 */
    private static void fill(Object g, int x0, int y0, int x1, int y1, int argb) {
        try {
            if (x0 > x1) {
                int t = x0;
                x0 = x1;
                x1 = t;
            }
            if (y0 > y1) {
                int t = y0;
                y0 = y1;
                y1 = t;
            }
            Method m = mFill;
            if (m == null) {
                m = Reflect.gameClass(GRAPHICS)
                        .getMethod("fill", int.class, int.class, int.class,
                                int.class, int.class);
                mFill = m;
            }
            m.invoke(g, x0, y0, x1, y1, argb);
        } catch (Throwable t) {
            errorCount.incrementAndGet();
        }
    }

    // ── 会话控制 ────────────────────────────────────────────────────────────

    private static void endSession(LiquidGlassTransition s) {
        if (session == s) {
            session = null;
            pendingSwap = null;
        }
    }

    /** 渲染异常的降级出口：立刻完成换屏（若还没换）并结束动画。 */
    private static void forceFinish() {
        LiquidGlassTransition s = session;
        if (s == null) {
            return;
        }
        if (!swapInvoked) {
            swapInvoked = true;
            Runnable swap = pendingSwap;
            pendingSwap = null;
            if (swap != null) {
                swap.run();
            }
        }
        session = null;
    }

    // ── 桥接类定义 ──────────────────────────────────────────────────────────

    /**
     * 转换期入口：把桥接类定义到 MinecraftClassLoader 本体（幂等），
     * 成功返回 {@code true}。转换器在<b>注入渲染钩子之前</b>调用本方法
     * —— 失败时转换器拒绝注入，游戏绝不携带解析必炸的调用点运行。
     *
     * <p>反射调用 {@code definePlatformBridge} 而不直接 import：
     * 集成模块对 mili-loader 没有编译期依赖（依赖方向 loader → integration），
     * 方法缺失时抛异常由本方法降级为 {@code false}。
     */
    public static boolean ensureBridgeDefinedEager() {
        if (bridgeReady) {
            return true;
        }
        if (bridgeFailed) {
            return false;
        }
        try {
            ensureBridgeDefined();
            return bridgeReady;
        } catch (Throwable t) {
            bridgeFailed = true;
            errorCount.incrementAndGet();
            LOG.log(Level.SEVERE, "[Mili] 玻璃桥接类定义失败，主菜单转场已停用（游戏不受影响）", t);
            return false;
        }
    }

    /**
     * 把桥接类定义到 MinecraftClassLoader 本体（幂等）。
     *
     * <p>反射调用 {@code definePlatformBridge} 而不直接 import：
     * 集成模块对 mili-loader 没有编译期依赖（依赖方向 loader → integration），
     * 方法缺失时抛异常由调用方降级。失败意味着渲染钩子无法安全工作，
     * 调用方会整体停用转场。
     */
    private static void ensureBridgeDefined() throws Exception {
        if (bridgeReady) {
            return;
        }
        synchronized (MenuTransitionDispatch.class) {
            if (bridgeReady) {
                return;
            }
            ClassLoader game = Reflect.gameClassLoader();
            Method define = game.getClass()
                    .getMethod("definePlatformBridge", String.class, byte[].class);
            define.invoke(game, GlassBridgeGenerator.BRIDGE_BINARY,
                    GlassBridgeGenerator.generate());
            bridgeReady = true;
            LOG.fine("[Mili] MiliGlassBridge 已定义进游戏类加载器");
        }
    }

    // ── 反射辅助 ────────────────────────────────────────────────────────────

    private static Object minecraftInstance() throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        if (game == null) {
            return null;
        }
        return Reflect.gameClass(MC).getMethod("getInstance").invoke(null);
    }

    private static Object currentScreen() throws Exception {
        Object mc = minecraftInstance();
        if (mc == null) {
            return null;
        }
        Object gui = Reflect.instanceField(mc, MC, "gui");
        if (gui == null) {
            return null;
        }
        return gui.getClass().getMethod("screen").invoke(gui);
    }

    private static Object currentOverlay() throws Exception {
        Object mc = minecraftInstance();
        if (mc == null) {
            return null;
        }
        Object gui = Reflect.instanceField(mc, MC, "gui");
        if (gui == null) {
            return null;
        }
        return gui.getClass().getMethod("overlay").invoke(gui);
    }

    /** 当前屏幕的逻辑尺寸；取不到时回退 426x240（原版默认窗口的 GUI 逻辑尺寸）。 */
    private static int[] screenSize() {
        try {
            Object screen = currentScreen();
            if (screen == null) {
                return new int[]{426, 240};
            }
            Class<?> screenClass = Reflect.gameClass(SCREEN);
            return new int[]{
                    screenClass.getField("width").getInt(screen),
                    screenClass.getField("height").getInt(screen)
            };
        } catch (Throwable t) {
            return new int[]{426, 240};
        }
    }

    private static int[] widgetCenter(Object widget) {
        try {
            int x = (int) invokeNoArg(widget, "getX");
            int y = (int) invokeNoArg(widget, "getY");
            int w = (int) invokeNoArg(widget, "getWidth");
            int h = (int) invokeNoArg(widget, "getHeight");
            return new int[]{x + w / 2, y + h / 2};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    /** 沿父类链查找按名声明的方法（覆盖 protected 泛型方法）—— 与 TitleScreenDispatch 同款。 */
    private static Method findInherited(Class<?> cls, String name, int paramCount) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
        }
        return null;
    }

    // ── 自测（-Dmili.selftest.transition=true） ─────────────────────────────

    /**
     * 自动验收：主界面稳定后模拟点击「单人游戏」，在涌起完成前后各截一帧。
     *
     * <p>与 {@code TitleScreenDispatch} 的 selftest 同一判据 —— 必须等启动
     * 遮罩（LoadingOverlay）彻底消失且稳定 1.2 秒后才动手，否则拍到的是
     * 被遮罩盖住的画面。截图走 {@code Screenshot.grab}（游戏自己截，
     * 外部 GDI 抓屏在 DWM 合成后拿不到前台缓冲）。
     */
    private static void maybeRunSelfTest() {
        if (!Boolean.getBoolean("mili.selftest.transition")) {
            return;
        }
        if (!selfTestPending.compareAndSet(true, false)) {
            return;
        }
        Thread waiter = new Thread(() -> {
            try {
                long stableSince = 0L;
                long deadline = System.currentTimeMillis() + 90_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (currentOverlaySafe() == null) {
                        if (stableSince == 0L) {
                            stableSince = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - stableSince > 1200L) {
                            break;
                        }
                    } else {
                        stableSince = 0L;
                    }
                    Thread.sleep(100L);
                }
                if (!simulateSingleplayerClick()) {
                    LOG.warning("[Mili] self-test: 模拟点击单人按钮未命中");
                    return;
                }
                scheduleShot(250L, "transition-cover");
                scheduleShot(900L, "transition-list");
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "[Mili] self-test 执行失败", t);
            }
        }, "mili-transition-selftest");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static Object currentOverlaySafe() {
        try {
            return currentOverlay();
        } catch (Throwable t) {
            return Boolean.TRUE;    // 非 null：视为「还没稳定」
        }
    }

    /** 在单人按钮中心合成一次真实输入链点击；返回屏幕是否真的变了。 */
    private static boolean simulateSingleplayerClick() {
        try {
            int[] c = singleplayerCenter;
            Object screen = currentScreen();
            if (c == null || screen == null) {
                return false;
            }
            ClassLoader game = Reflect.gameClassLoader();
            Class<?> infoClass = Class.forName(
                    "net.minecraft.client.input.MouseButtonInfo", true, game);
            Class<?> eventClass = Class.forName(
                    "net.minecraft.client.input.MouseButtonEvent", true, game);
            Object info = infoClass.getConstructor(int.class, int.class)
                    .newInstance(0, 0);
            Object event = eventClass
                    .getConstructor(double.class, double.class, infoClass)
                    .newInstance((double) c[0], (double) c[1], info);
            Method click = findInherited(screen.getClass(), "mouseClicked", 2);
            if (click == null) {
                return false;
            }
            click.setAccessible(true);
            Object handled = click.invoke(screen, event, false);
            LOG.info("[Mili] self-test: 点击 (" + c[0] + "," + c[1] + ") handled=" + handled);
            return Boolean.TRUE.equals(handled);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] self-test 模拟点击失败", t);
            return false;
        }
    }

    /** 延迟后让游戏在渲染线程自己截图 —— 与 TitleScreenDispatch 同款。 */
    private static void scheduleShot(long delayMillis, String tag) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                Object mc = minecraftInstance();
                if (mc == null) {
                    return;
                }
                Runnable grab = () -> {
                    try {
                        Reflect.gameClass("net.minecraft.client.Screenshot")
                                .getMethod("grab", Reflect.gameClass(MC), boolean.class)
                                .invoke(null, mc, false);
                        LOG.info("[Mili] self-test: 已请求截图 (" + tag + ")，见 screenshots/");
                    } catch (Throwable e) {
                        LOG.log(Level.WARNING, "[Mili] self-test 截图失败", e);
                    }
                };
                executeOnMainThread(mc, grab);
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "[Mili] self-test 截图调度失败", e);
            }
        }, "mili-transition-shot");
        t.setDaemon(true);
        t.start();
    }

    /** {@code Minecraft.execute(Runnable)} —— 渲染/GPU 操作必须回主线程。 */
    private static void executeOnMainThread(Object mc, Runnable task) {
        try {
            for (Method m : mc.getClass().getMethods()) {
                if (m.getName().equals("execute") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == Runnable.class) {
                    m.invoke(mc, task);
                    return;
                }
            }
            task.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 主线程任务调度失败", t);
        }
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 累计转场次数。 */
    public static long transitionCount() {
        return transitionCount.get();
    }

    /** 累计失败次数。 */
    public static long errorCount() {
        return errorCount.get();
    }

    /** 转场是否处于激活状态（诊断/测试用）。 */
    public static boolean isActive() {
        return session != null;
    }

    /**
     * 仅供测试：复位桥接定义状态（bridgeReady/bridgeFailed）。
     * 两者是静态字段，跨测试用例会串味 —— 每个用例结束必须复位。
     */
    public static void resetBridgeForTest() {
        synchronized (MenuTransitionDispatch.class) {
            bridgeReady = false;
            bridgeFailed = false;
        }
    }

    public static String diagnostics() {
        return "MenuTransitionDispatch[transitions=" + transitionCount.get()
                + ", errors=" + errorCount.get()
                + ", active=" + (session != null)
                + ", bridge=" + (bridgeReady ? "ok" : bridgeFailed ? "failed" : "pending")
                + "]";
    }
}
