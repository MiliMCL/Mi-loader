package org.loader.runtime.minecraft.client.screen;

import org.loader.api.gui.ScreenListener;
import org.loader.api.gui.ScreenSpec;
import org.loader.api.gui.ScreenUnavailableException;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自定义屏幕分发器 —— 生命周期回调与 widget 构建的实际实现。
 *
 * <h2>调用来源</h2>
 * {@code onInit} / {@code onClose} 由生成的宿主类
 * {@code MiliScreenHost}（见 {@link MiliScreenHostGenerator}）调用，
 * 运行在游戏类加载器上下文里；{@code open} / {@code closeRequested}
 * 由 {@link ScreenServiceBridge} 调用。两侧都在客户端主线程。
 *
 * <h2>widget 构建（全部反射，坐标按 26.2 反编译源核实）</h2>
 * <ul>
 *   <li>Button → {@code Button.builder(Component, OnPress).bounds(x,y,w,h).build()}
 *       —— OnPress 用 Proxy 拦截，回调按 widget id 转给 listener</li>
 *   <li>TextField → {@code new EditBox(Font, x, y, w, h, Component)}
 *       + {@code setMaxLength/setValue/setResponder}</li>
 *   <li>Checkbox → {@code Checkbox.builder(Component, Font).pos(x,y)
 *       .selected(b).onValueChange(proxy).build()} —— OnValueChange
 *       是公开函数式接口，可代理，切换即时回调</li>
 *   <li>Label → {@code new StringWidget(Component, Font)} + setPosition</li>
 * </ul>
 *
 * <h2>异常策略</h2>
 * {@code onInit} / {@code onClose} 从游戏字节码里被调用，
 * <b>必须吞掉一切异常</b>（与 {@code TitleScreenDispatch} 同一规则）——
 * 屏幕构建失败记日志，绝不把异常抛进原版 GUI 调用栈。
 * {@code open} 是 Mod 主动调用的入口，失败以
 * {@link ScreenUnavailableException} 显式暴露 —— 那是 Mod 的错误，
 * 静默吞掉只会让它误以为屏幕已打开。
 */
public final class ScreenHostDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/Screen");

    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String BUTTON = "net.minecraft.client.gui.components.Button";
    private static final String EDIT_BOX = "net.minecraft.client.gui.components.EditBox";
    private static final String STRING_WIDGET =
            "net.minecraft.client.gui.components.StringWidget";
    private static final String CHECKBOX =
            "net.minecraft.client.gui.components.Checkbox";
    private static final String MC = "net.minecraft.client.Minecraft";
    private static final String COMPONENT = "net.minecraft.network.chat.Component";

    /** 当前活动的屏幕数据与监听器 —— 单屏幕，open 即替换。 */
    private static volatile ScreenSpec activeSpec;
    private static volatile ScreenListener activeListener;

    /**
     * 实时文本供应商（widget id → 文本）。
     *
     * <p>平台内部使用（性能面板）。没有扩展成 ABI，是因为「每个 widget 都
     * 挂一个 Supplier」会让 {@code ScreenSpec} 从纯数据变成行为容器；
     * 等有第二个真实用例时再决定要不要开放。
     */
    private static volatile Map<String, Supplier<String>> activeLive = Map.of();

    /** 当前屏幕上需要刷新的文本 widget。 */
    private static volatile List<LiveEntry> liveWidgets = List.of();
    private static volatile long lastLiveRefreshNanos;

    /** 实时文本刷新间隔：5Hz，高于面板 4Hz 的快照节奏。 */
    private static final long LIVE_REFRESH_NANOS = 200_000_000L;

    /** 宿主类的 Class 对象缓存（由游戏类加载器定义，只做一次）。 */
    private static volatile Class<?> hostClass;

    private ScreenHostDispatch() {
    }

    // ── 打开 / 关闭（由 ScreenServiceBridge 调用） ─────────────────────────

    /**
     * 打开屏幕。要求客户端主线程；环境不满足时抛
     * {@link ScreenUnavailableException}。
     */
    static void open(ScreenSpec spec, ScreenListener listener) {
        open(spec, listener, Map.of());
    }

    static void open(ScreenSpec spec, ScreenListener listener,
                     Map<String, Supplier<String>> liveText) {
        Object mc = minecraftInstance();
        if (mc == null) {
            throw new ScreenUnavailableException(
                    "Minecraft 实例尚不存在 —— 客户端尚未启动完成。");
        }
        if (!isSameThread(mc)) {
            throw new ScreenUnavailableException(
                    "ScreenService.open() 必须在客户端主线程调用"
                            + "（在注入回调 / 按键事件里调用即可满足）。当前线程: "
                            + Thread.currentThread().getName());
        }
        try {
            Object title = Reflect.gameClass(COMPONENT)
                    .getMethod("literal", String.class)
                    .invoke(null, spec.title());
            Class<?> host = hostClass();
            Object screen = host
                    .getConstructor(Reflect.gameClass(COMPONENT))
                    .newInstance(title);

            activeSpec = spec;
            activeListener = listener;
            activeLive = liveText != null ? liveText : Map.of();

            Object gui = guiOf(mc);
            try {
                gui.getClass().getMethod("setScreen", Reflect.gameClass(SCREEN))
                        .invoke(gui, screen);
            } catch (Throwable t) {
                // 屏幕没能真正显示 → 回滚激活状态。
                //
                // 不回滚的后果不是「这次失败」，而是「之后永远失败」：
                // isShowing() 会一直返回 true，Mod 里那句
                // `if (ScreenService.isShowing()) return;` 于是永久短路，
                // 玩家再按 K 就什么也不会发生 —— 而日志里只有第一次的异常。
                clearActive();
                throw t;
            }
        } catch (ScreenUnavailableException e) {
            throw e;
        } catch (Throwable t) {
            // open 是 Mod 的主动调用 —— 失败必须显式，不能吞。
            throw new ScreenUnavailableException(
                    "打开屏幕失败: " + spec.id() + " —— " + t, t);
        }
    }

    /** Mod 主动关闭（“完成”按钮路径）：回调 onClose(false) 后清屏。 */
    static void closeRequested() {
        ScreenListener listener = activeListener;
        try {
            if (listener != null) {
                listener.onClose(false);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 屏幕监听器 onClose(false) 抛出异常", t);
        }
        clearActive();
        try {
            Object gui = guiOf(minecraftInstance());
            if (gui != null) {
                gui.getClass().getMethod("setScreen", Reflect.gameClass(SCREEN))
                        .invoke(gui, (Object) null);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 关闭屏幕失败", t);
        }
    }

    static boolean isShowing() {
        return activeSpec != null;
    }

    // ── 宿主生命周期回调（由生成的 MiliScreenHost 调用，必须吞异常） ────────

    /** 宿主 init —— 按当前 spec 构建 widget。描述符固定 {@code (Screen)V}。 */
    public static void onInit(Object host) {
        try {
            ScreenSpec spec = activeSpec;
            ScreenListener listener = activeListener;
            if (spec == null || listener == null) {
                return; // 被外部屏幕顶掉后残留的 init —— 无事可做
            }
            Object mc = minecraftInstance();
            if (mc == null) {
                return;
            }
            Object font = Reflect.instanceField(mc, MC, "font");
            Method add = findInherited(host.getClass(), "addRenderableWidget", 1);
            add.setAccessible(true);

            // 实时文本：只对 spec 中声明过的 label 生效。方法只解析一次，
            // 不在每次刷新时做 getMethod 查找。
            Map<String, Supplier<String>> live = activeLive;
            List<LiveEntry> entries = new ArrayList<>();
            Method setMessage = null;
            Method literal = null;
            Method setMaxWidth = null;
            if (!live.isEmpty()) {
                try {
                    Class<?> componentClass = Reflect.gameClass(COMPONENT);
                    Class<?> widgetClass = Reflect.gameClass(STRING_WIDGET);
                    setMessage = widgetClass.getMethod("setMessage", componentClass);
                    // StringWidget 的宽度在构造时按初始文本固定；不放开
                    // maxWidth 的话，数字从 "9.9" 变成 "10.0" 时最后一个
                    // 字符会被裁掉 —— 一个只在数值跨位数时出现的显示 bug。
                    setMaxWidth = widgetClass.getMethod("setMaxWidth", int.class);
                    literal = componentClass.getMethod("literal", String.class);
                } catch (Throwable t) {
                    // 实时刷新不可用不该让整个界面构建失败 —— 静态文本仍然有价值
                    LOG.log(Level.FINE, "[Mili] 实时文本不可用，降级为静态标签", t);
                    live = Map.of();
                }
            }

            // ── 内容区居中 ────────────────────────────────────────────────
            //
            // ScreenSpec 的坐标是「内容区坐标」，文档承诺「居中由平台负责」。
            // 而旧实现直接把 widget.x() 当屏幕坐标用 —— 于是声明 size(260,170)
            // 的界面会贴在左上角，和 spec 里写的布局完全对不上。
            //
            // 这个 bug 不会报错，只会让所有 Mod 的自定义界面都长歪，
            // 然后每个 Mod 作者自己加偏移去凑 —— 五份补救代码，五种偏移量。
            int screenWidth = 0;
            int screenHeight = 0;
            try {
                Class<?> screenClass = Reflect.gameClass(SCREEN);
                screenWidth = screenClass.getField("width").getInt(host);
                screenHeight = screenClass.getField("height").getInt(host);
            } catch (ReflectiveOperationException ignored) {
                // 取不到尺寸就按 0 偏移处理，界面仍可用
            }
            int ox = Math.max(0, (screenWidth - spec.width()) / 2);
            int oy = Math.max(0, (screenHeight - spec.height()) / 2);

            for (ScreenSpec.Widget widget : spec.widgets()) {
                Object vanilla = buildWidget(widget, mc, font, listener, ox, oy);
                if (vanilla != null) {
                    add.invoke(host, vanilla);
                    if (setMessage != null && widget instanceof ScreenSpec.Label label) {
                        Supplier<String> supplier = live.get(label.id());
                        if (supplier != null) {
                            if (setMaxWidth != null) {
                                setMaxWidth.invoke(vanilla, 4096);
                            }
                            entries.add(new LiveEntry(vanilla, supplier, label.text(),
                                    setMessage, literal));
                        }
                    }
                }
            }
            liveWidgets = entries;
            lastLiveRefreshNanos = 0L;
            listener.onOpen();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 自定义屏幕构建失败（不影响游戏）", t);
        }
    }

    /**
     * 宿主 onClose —— Esc / 原版路径。语义：{@code onClose(true)}。
     * Mod 主动关闭走 {@link #closeRequested()}（{@code onClose(false)}），
     * 两条路径不会叠加。
     */
    public static void onClose(Object host) {
        ScreenListener listener = activeListener;
        clearActive();
        try {
            if (listener != null) {
                listener.onClose(true);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 屏幕监听器 onClose(true) 抛出异常", t);
        }
    }

    /**
     * 宿主 {@code tick()} —— 刷新实时文本。由生成的宿主每个客户端 tick 调用。
     *
     * <p>节流到 5Hz，且只在文本真的变化时调用 {@code setMessage}：
     * 每帧重建 Component 会让「看性能」本身产生垃圾，恰好污染它要观测的
     * 帧时间。
     */
    public static void onTick(Object host) {
        try {
            List<LiveEntry> entries = liveWidgets;
            if (entries.isEmpty()) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastLiveRefreshNanos < LIVE_REFRESH_NANOS) {
                return;
            }
            lastLiveRefreshNanos = now;
            for (int i = 0; i < entries.size(); i++) {
                LiveEntry entry = entries.get(i);
                String text;
                try {
                    text = entry.supplier.get();
                } catch (Throwable t) {
                    // 单个 supplier 失败不影响其余行
                    continue;
                }
                if (text == null) {
                    text = "";
                }
                if (text.equals(entry.last)) {
                    continue;
                }
                Object component = entry.literal.invoke(null, text);
                entry.setMessage.invoke(entry.widget, component);
                entry.last = text;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[Mili] 实时文本刷新失败", t);
        }
    }

    /** 是否正在显示指定 id 的屏幕。 */
    static boolean isShowing(String specId) {
        ScreenSpec spec = activeSpec;
        return spec != null && spec.id().equals(specId);
    }

    /**
     * 当前是否有<b>任何</b>屏幕打开（包括原版的标题/设置/暂停界面）。
     *
     * <p>用于阻止诊断面板覆盖在原版界面上：原版 {@code Screen.onClose()}
     * 的默认行为是 {@code setScreen(null)}，如果从标题界面打开再关闭，
     * 玩家会落到一个没有菜单的空屏幕。诊断面板只在游戏内（无界面时）打开。
     */
    static boolean isAnyScreenOpen() {
        try {
            Object gui = guiOf(minecraftInstance());
            if (gui == null) {
                return false;
            }
            Object screen = gui.getClass().getMethod("screen").invoke(gui);
            return screen != null;
        } catch (Throwable t) {
            // 方法漂移时保守认为「有界面」—— 少开一次面板好过把玩家卡在空屏幕
            return true;
        }
    }

    /** 显示着指定屏幕时<b>只断开引用</b>，不触碰游戏状态（关停路径）。 */
    static void clearIfShowing(String specId) {
        if (isShowing(specId)) {
            clearActive();
        }
    }

    /**
     * 宿主 {@code isPauseScreen()} —— 由 spec 决定。
     *
     * <p>默认 {@code true}（与原版一致）；诊断面板声明 {@code pauseGame(false)}，
     * 否则单人游戏会在打开面板的瞬间停止 integrated server tick，
     * TPS 读数变成 0 —— 观测行为改变了被观测对象。
     */
    public static boolean isPauseScreen() {
        ScreenSpec spec = activeSpec;
        // spec 为 null 意味着屏幕正在关闭（clearActive 先于 setScreen(null)）。
        // 这时返回 false，避免「关面板的瞬间暂停一 tick」—— 那会让刚采到的
        // TPS 窗口多一个无意义的超时。
        return spec != null && spec.pauseGame();
    }

    /** 宿主 {@code isInGameUi()} —— HUD 模式使用半透明渐变背景。 */
    public static boolean isInGameUi() {
        ScreenSpec spec = activeSpec;
        return spec != null && spec.hudMode();
    }

    private static void clearActive() {
        activeSpec = null;
        activeListener = null;
        activeLive = Map.of();
        liveWidgets = List.of();
    }

    /**
     * 把「清空活动屏幕」登记为 Scope 资源。
     *
     * <p>关闭时不回调 {@code onClose}：Runtime 关停不是玩家取消操作，
     * 调用 Mod 的回调只会让它在已经停掉的 Scope 上做最后一次写入。
     * 这里要做的是<b>断开引用</b>，让 Mod 的监听器与 ClassLoader 可以回收。
     */
    public static Resource lifecycle(Scope owner) {
        return new Lifecycle(owner);
    }

    private static final class Lifecycle implements Resource {
        private final Scope owner;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Lifecycle(Scope owner) {
            this.owner = owner;
        }

        @Override
        public String id() {
            return "screen-host-dispatch";
        }

        @Override
        public Scope owner() {
            return owner;
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                clearActive();
            }
        }
    }

    // ── widget 构建 ─────────────────────────────────────────────────────────

    private static Object buildWidget(ScreenSpec.Widget widget, Object mc,
                                      Object font, ScreenListener listener,
                                      int ox, int oy)
            throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        Class<?> componentClass = Reflect.gameClass(COMPONENT);
        Method literal = componentClass.getMethod("literal", String.class);

        if (widget instanceof ScreenSpec.Button b) {
            Class<?> buttonClass = Reflect.gameClass(BUTTON);
            Class<?> onPressClass = Class.forName(BUTTON + "$OnPress", true, game);
            String id = b.id();
            Object onPress = Proxy.newProxyInstance(game,
                    new Class<?>[]{onPressClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "Mili Button " + id;
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        fireEvent(() -> listener.onClick(id));
                        return null;
                    });
            Method builder = buttonClass.getMethod("builder", componentClass, onPressClass);
            Object btn = builder.invoke(null, literal.invoke(null, b.label()), onPress);
            btn = builder.getReturnType()
                    .getMethod("bounds", int.class, int.class, int.class, int.class)
                    .invoke(btn, b.x() + ox, b.y() + oy, b.width(), b.height());
            return builder.getReturnType().getMethod("build").invoke(btn);
        }

        if (widget instanceof ScreenSpec.TextField tf) {
            Class<?> editBoxClass = Reflect.gameClass(EDIT_BOX);
            Object box = editBoxClass
                    .getConstructor(Reflect.gameClass("net.minecraft.client.gui.Font"),
                            int.class, int.class, int.class, int.class, componentClass)
                    .newInstance(font, tf.x() + ox, tf.y() + oy,
                            tf.width(), tf.height(), literal.invoke(null, ""));
            editBoxClass.getMethod("setMaxLength", int.class)
                    .invoke(box, tf.maxLength());
            if (tf.initial() != null && !tf.initial().isEmpty()) {
                editBoxClass.getMethod("setValue", String.class)
                        .invoke(box, tf.initial());
            }
            String id = tf.id();
            Class<?> consumerClass = Class.forName("java.util.function.Consumer");
            Object responder = Proxy.newProxyInstance(game,
                    new Class<?>[]{consumerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "Mili EditBox " + id;
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        String value = args[0] != null ? args[0].toString() : "";
                        fireEvent(() -> listener.onTextChange(id, value));
                        return null;
                    });
            editBoxClass.getMethod("setResponder", consumerClass)
                    .invoke(box, responder);
            return box;
        }

        if (widget instanceof ScreenSpec.Checkbox cb) {
            Class<?> checkboxClass = Reflect.gameClass(CHECKBOX);
            Class<?> onValueChangeClass =
                    Class.forName(CHECKBOX + "$OnValueChange", true, game);
            String id = cb.id();
            Object onValueChange = Proxy.newProxyInstance(game,
                    new Class<?>[]{onValueChangeClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "Mili Checkbox " + id;
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        boolean checked = args.length > 1 && args[1] instanceof Boolean b
                                && b;
                        fireEvent(() -> listener.onToggle(id, checked));
                        return null;
                    });
            Method builder = checkboxClass.getMethod("builder", componentClass,
                    Reflect.gameClass("net.minecraft.client.gui.Font"));
            Object b = builder.invoke(null, literal.invoke(null, cb.label()), font);
            Class<?> builderClass = builder.getReturnType();
            b = builderClass.getMethod("pos", int.class, int.class)
                    .invoke(b, cb.x() + ox, cb.y() + oy);
            b = builderClass.getMethod("selected", boolean.class)
                    .invoke(b, cb.initial());
            b = builderClass.getMethod("onValueChange", onValueChangeClass)
                    .invoke(b, onValueChange);
            return builderClass.getMethod("build").invoke(b);
        }

        if (widget instanceof ScreenSpec.Label l) {
            // StringWidget(Component, Font) 自按文本宽度取尺寸，再挪到目标位置。
            Class<?> stringWidgetClass = Reflect.gameClass(STRING_WIDGET);
            Object label = stringWidgetClass
                    .getConstructor(componentClass,
                            Reflect.gameClass("net.minecraft.client.gui.Font"))
                    .newInstance(literal.invoke(null, l.text()), font);
            findInherited(stringWidgetClass, "setPosition", 2)
                    .invoke(label, l.x() + ox, l.y() + oy);
            return label;
        }

        // 未知 widget 类型 —— 未来的 ABI 扩展在旧平台上运行时会走到这里。
        // 跳过并记日志（屏幕其余部分照常），而不是炸掉整个 init。
        LOG.warning("[Mili] 未知的 widget 类型: " + widget.getClass().getName());
        return null;
    }

    /** 事件回调统一入口：Mod 监听器的异常不允许逃逸进原版 GUI 栈。 */
    private static void fireEvent(Runnable event) {
        try {
            event.run();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 屏幕事件回调抛出异常", t);
        }
    }

    // ── 宿主类定义 ───────────────────────────────────────────────────────────

    /**
     * 取（必要时生成并定义）宿主类。
     *
     * <p>通过标准 {@code ClassLoader.defineClass} 反射在游戏类加载器上
     * 定义 —— 宿主类的父类 Screen 只在游戏类加载器可见。
     */
    private static Class<?> hostClass() throws Exception {
        Class<?> cached = hostClass;
        if (cached != null) {
            return cached;
        }
        synchronized (ScreenHostDispatch.class) {
            if (hostClass == null) {
                byte[] bytes = MiliScreenHostGenerator.generate();
                ClassLoader game = Reflect.gameClassLoader();
                // 走 ClassLoader 子类而不是反射调 defineClass：
                // JDK 9+ 模块系统会拒绝 setAccessible，症状是
                // 「Mod 调 open() 后什么也没发生」。见 GameHostDefiner。
                hostClass = GameHostDefiner.define(game,
                        MiliScreenHostGenerator.HOST_BINARY, bytes);
                LOG.fine("[Mili] MiliScreenHost 已定义到游戏类加载器");
            }
            return hostClass;
        }
    }

    // ── 反射辅助 ─────────────────────────────────────────────────────────────

    private static Object minecraftInstance() {
        ClassLoader game = Reflect.gameClassLoader();
        if (game == null) {
            return null;
        }
        try {
            return Reflect.gameClass(MC).getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            // 方法漂移属于平台 bug —— 伪装成 null 会让调用方误判
            // 「客户端尚未启动」，错误被埋进错误的处理路径。
            throw new IllegalStateException(
                    "反射调用 Minecraft.getInstance() 失败", e);
        }
    }

    private static boolean isSameThread(Object mc) {
        try {
            Method m = mc.getClass().getMethod("isSameThread");
            return (boolean) m.invoke(mc);
        } catch (Throwable t) {
            // 方法漂移时不阻断 —— 客户端上下文里调用天然满足主线程要求。
            return true;
        }
    }

    private static Object guiOf(Object minecraft) throws Exception {
        if (minecraft == null) {
            return null;
        }
        return Reflect.instanceField(minecraft, MC, "gui");
    }

    /**
     * 沿父类链<b>与接口</b>查找按名声明的方法。
     *
     * <p>接口不能省：{@code setPosition} 声明在 {@code LayoutElement}
     * 的 default 方法上（{@code AbstractWidget} 未覆写）—— 只走
     * {@code getSuperclass()} 的写法会漏掉它，然后以 NPE 的形式
     * 表现为「标签没放对位置」这种无从排查的症状。
     */
    private static Method findInherited(Class<?> cls, String name, int paramCount) {
        java.util.Deque<Class<?>> queue = new java.util.ArrayDeque<>();
        queue.add(cls);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            if (c.getSuperclass() != null) {
                queue.add(c.getSuperclass());
            }
            queue.addAll(java.util.Arrays.asList(c.getInterfaces()));
        }
        return null;
    }

    // ── 实时文本条目 ─────────────────────────────────────────────────────────

    /** 一个需要按 supplier 刷新的文本 widget。 */
    private static final class LiveEntry {
        final Object widget;
        final Supplier<String> supplier;
        final Method setMessage;
        final Method literal;
        String last;

        LiveEntry(Object widget, Supplier<String> supplier, String initial,
                  Method setMessage, Method literal) {
            this.widget = widget;
            this.supplier = supplier;
            this.setMessage = setMessage;
            this.literal = literal;
            this.last = initial != null ? initial : "";
        }
    }
}
