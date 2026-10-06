package org.loader.runtime.minecraft.client.screen;

import org.loader.api.gui.ScreenListener;
import org.loader.api.gui.ScreenSpec;
import org.loader.api.gui.ScreenUnavailableException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

            Object gui = guiOf(mc);
            gui.getClass().getMethod("setScreen", Reflect.gameClass(SCREEN))
                    .invoke(gui, screen);
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

            for (ScreenSpec.Widget widget : spec.widgets()) {
                Object vanilla = buildWidget(widget, mc, font, listener);
                if (vanilla != null) {
                    add.invoke(host, vanilla);
                }
            }
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

    private static void clearActive() {
        activeSpec = null;
        activeListener = null;
    }

    // ── widget 构建 ─────────────────────────────────────────────────────────

    private static Object buildWidget(ScreenSpec.Widget widget, Object mc,
                                      Object font, ScreenListener listener)
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
                    .invoke(btn, b.x(), b.y(), b.width(), b.height());
            return builder.getReturnType().getMethod("build").invoke(btn);
        }

        if (widget instanceof ScreenSpec.TextField tf) {
            Class<?> editBoxClass = Reflect.gameClass(EDIT_BOX);
            Object box = editBoxClass
                    .getConstructor(Reflect.gameClass("net.minecraft.client.gui.Font"),
                            int.class, int.class, int.class, int.class, componentClass)
                    .newInstance(font, tf.x(), tf.y(), tf.width(), tf.height(),
                            literal.invoke(null, ""));
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
                    .invoke(b, cb.x(), cb.y());
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
                    .invoke(label, l.x(), l.y());
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
                Method define = ClassLoader.class.getDeclaredMethod(
                        "defineClass", String.class, byte[].class, int.class, int.class);
                define.setAccessible(true);
                hostClass = (Class<?>) define.invoke(game,
                        MiliScreenHostGenerator.HOST_BINARY,
                        bytes, 0, bytes.length);
                LOG.fine("[Mili] MiliScreenHost 已定义到游戏类加载器");
            }
            return hostClass;
        }
    }

    // ── 反射辅助 ─────────────────────────────────────────────────────────────

    private static Object minecraftInstance() throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        if (game == null) {
            return null;
        }
        return Reflect.gameClass(MC).getMethod("getInstance").invoke(null);
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
}
