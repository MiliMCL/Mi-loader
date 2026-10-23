package org.loader.runtime.minecraft.client;

import org.loader.runtime.minecraft.client.screen.ModListScreenDispatch;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 主界面分发器 —— 在原版 TitleScreen 上注入「Mods」按钮。
 *
 * <h2>它修的是什么问题</h2>
 * 平台此前没有任何途径让玩家看到「加载了哪些 mod」：
 * F3 只显示版本，主界面没有列表入口。本类配合
 * {@code MiliTitleScreenTransformer}（HEAD 注入到
 * {@code TitleScreen#init}）向主菜单添加一个 Mods 按钮，
 * 点击后用<b>原版</b> {@code AlertScreen} 展示已加载的 mod 列表 ——
 * 不引入任何自定义 Screen 类，避免与游戏 GUI 体系耦合。
 *
 * <h2>为什么全部用反射</h2>
 * 本模块与 Minecraft 零编译期耦合（见 build.gradle.kts 的说明）。
 * 本类由平台 AppClassLoader 定义，Minecraft 类只存在于
 * MinecraftClassLoader 中 —— 父 CL 看不见子 CL，因此只能通过
 * {@link Reflect} 持有的游戏 ClassLoader 反射访问。
 * 所有坐标均按 26.2 反编译源核实：
 * <ul>
 *   <li>{@code Minecraft.getInstance()} —— 静态访问器</li>
 *   <li>{@code Minecraft.gui} —— public final 字段（{@code Gui}）</li>
 *   <li>{@code Gui.screen()} / {@code Gui.setScreen(Screen)}</li>
 *   <li>{@code Screen.width} / {@code Screen.height} —— public 字段</li>
 *   <li>{@code Screen#addRenderableWidget(T)} —— protected，反射可见</li>
 *   <li>{@code Button.builder(Component, OnPress)} + {@code bounds/build}</li>
 *   <li>{@code AlertScreen(Runnable, Component, Component)}</li>
 * </ul>
 *
 * <h2>回调描述符约束</h2>
 * 注入引擎对 HEAD 回调只支持无参 {@code ()V}（或 InjectionContext），
 * 因此<b>拿不到 TitleScreen 实例</b>。解法：init 由
 * {@code Gui.setScreen} 触发，此刻 {@code gui.screen()} 已经是正在
 * 初始化的 TitleScreen —— 从 {@code Minecraft.getInstance()} 侧取即可。
 *
 * <h2>异常策略</h2>
 * 与 {@code TickCallbackDispatch} 同一规则：本类的方法是从游戏
 * {@code init()} 里调用的，<b>任何异常都必须吞掉</b> ——
 * 列表按钮失败不该炸掉主菜单。失败记录到日志与诊断计数。
 */
public final class TitleScreenDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/TitleScreen");

    private static final String TITLE_SCREEN = "net.minecraft.client.gui.screens.TitleScreen";
    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String BUTTON = "net.minecraft.client.gui.components.Button";

    /**
     * mod 列表来源 —— 由 loader 侧（拥有 ModClassLoaderManager 的一方）
     * 在游戏启动前设置。列表项为人类可读的一行描述（如
     * {@code "mymod (mymod-1.0.jar)"}）。
     */
    private static volatile Supplier<List<String>> modListProvider = List::of;

    /**
     * 我们添加过的按钮 widget 集合（弱引用）。
     *
     * <h2>为什么不是「这个 screen 注入过」</h2>
     * 一开始用的是 {@code Set<Screen> instrumented} + 「注入过就跳过」。
     * 这是个彻底的错误，因为它假设「screen 的 children 集合一旦填充就不会被清空」。
     *
     * <p>而 26.2 的 {@code Screen.repositionElements()} 就是
     * {@code rebuildWidgets()} —— 先 {@code clearWidgets()} 再 {@code init()}。
     * 窗口 resize、资源重载、分辨率变化都会走这条路。于是：
     * <pre>
     *   第一次 init  → 添加按钮，记录 "这个 screen 注入过"
     *   resize       → clearWidgets() 清空一切 → init() 再次被调用
     *                → 我们因为"注入过"直接 return → 按钮永久消失
     * </pre>
     * 症状是「改一下窗口大小，Mods 按钮就没了」，而日志里那句
     * 「按钮已添加」还停留在第一次的记录上，排查方向被彻底带偏。
     *
     * <p>正确判据是<b>按钮本身还在不在 children 里</b>，而不是
     * 「这个 screen 有没有被处理过」。清除后重新添加，才是幂等的正确定义。
     */
    private static final java.util.Map<Object, Boolean> installedWidgets =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final java.util.concurrent.atomic.AtomicLong addCount =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong errorCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** self-test 只跑一次（主界面可能因 resize 多次 init）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean selfTestPending =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    /**
     * 上一次成功添加的按钮边界（GUI 逻辑坐标）。
     *
     * <p>self-test 的点击路径需要它：要模拟「点在按钮上」，就必须知道
     * 按钮到底在哪。把它记下来而不是在测试里重算一遍 —— 重算等于把
     * 同一套布局公式抄两遍，某一处改了另一处不会跟着改。
     */
    private static volatile int[] lastButtonBounds = null;

    private TitleScreenDispatch() {
    }

    /**
     * 设置 mod 列表来源。必须在主界面显示之前调用（loader 启动期）。
     *
     * @param provider 返回每行描述；null 视为空列表
     */
    public static void setModListProvider(Supplier<List<String>> provider) {
        modListProvider = provider != null ? provider : List::of;
    }

    // ── 注入回调（由生成字节码调用） ────────────────────────────────────────

    /**
     * TitleScreen#init 方法头回调。描述符固定为 {@code ()V}。
     *
     * <p>幂等：同一屏幕实例重复 init（窗口 resize）不会重复添加按钮。
     */
    public static void onTitleScreenInit() {
        try {
            installModsButton();
            maybeRunSelfTest();
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 主界面 Mods 按钮注入失败（不影响游戏）", t);
        }
    }

    /**
     * 自动化验收入口：{@code -Dmili.selftest.modlist=true} 时，
     * 主界面初始化完成后自动打开一次 Mod 列表。
     *
     * <h2>为什么需要它</h2>
     * 「点击按钮能打开列表」这条需求，如果只靠人眼验收，就永远无法
     * 进 CI —— 而进不了 CI 的验收项，下一次改动就会被悄悄破坏。
     * 这个开关让渲染路径、数据装配、屏幕切换三件事可以在无人值守的
     * 情况下跑一遍，截图留证。
     *
     * <p>它<b>不</b>替代真实点击测试：这里走的是
     * {@code ModListScreenDispatch.open()}，绕过了按钮的 OnPress 回调。
     * 两条路径都要有，缺一条只能证明一半。
     */
    private static void maybeRunSelfTest() {
        // 三个入口任一开启就进入自检：
        //   modlist   —— 直接打开 Mod 列表
        //   click     —— 模拟点击 Mods 按钮（走游戏输入链）
        //   screen    —— 打开一个声明式 ScreenSpec 界面
        //
        // 原来只认 modlist，导致单独写 -Dmili.selftest.screen=true 时
        // 整个自检被静默跳过 —— 而「静默跳过」正是这套自检要防的东西。
        if (!Boolean.getBoolean("mili.selftest.modlist")
                && !Boolean.getBoolean("mili.selftest.click")
                && !Boolean.getBoolean("mili.selftest.screen")) {
            return;
        }
        if (!selfTestPending.compareAndSet(true, false)) {
            return;
        }

        // 必须等到<b>启动遮罩彻底消失</b>之后才能打开列表。
        //
        // 时序（实测）：主界面第一次 init 时，Mod 资源注入还没完成；
        // 注入会触发一次 resource reload，reload 会盖上一层
        // LoadingOverlay（红色 Mojang 画面 + 进度条）。如果此刻打开
        // 列表并截图，拍到的是被遮罩盖住的界面 —— 一张看起来
        // 「UI 没渲染」的图，比没有图更容易误导人。
        //
        // 判据：Gui.overlay() == null 且连续 1.2 秒成立。
        Thread waiter = new Thread(() -> {
            try {
                long stableSince = 0L;
                long deadline = System.currentTimeMillis() + 90_000L;
                while (System.currentTimeMillis() < deadline) {
                    Object mc = minecraftInstance();
                    Object gui = guiOf(mc);
                    Object overlay = invokeNoArg(gui, "overlay");
                    if (overlay == null && gui != null) {
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
                Object mc = minecraftInstance();
                if (mc == null) {
                    return;
                }
                Runnable open = () -> {
                    if (Boolean.getBoolean("mili.selftest.screen")) {
                        LOG.info("[Mili] self-test: 打开自定义 ScreenSpec 界面");
                        openSelftestScreen();
                        return;
                    }
                    if (Boolean.getBoolean("mili.selftest.click")) {
                        // 走游戏的输入分发链：screen.mouseClicked -> widget 命中
                        // -> Button.onPress -> openModList。除了「事件来自 GLFW
                        // 而不是代码」这一点，与真实点击完全相同。
                        //
                        // 这条路径存在的理由：光调 open() 只能证明「界面能画」，
                        // 证明不了「按钮接上了」。而「按钮接没接上」正是用户
                        // 最直接的验收标准。
                        LOG.info("[Mili] self-test: 模拟点击 Mods 按钮");
                        if (simulateModsButtonClick()) {
                            return;
                        }
                        LOG.warning("[Mili] self-test: 模拟点击未命中，退回直接打开");
                    } else {
                        LOG.info("[Mili] self-test: 自动打开 Mod 列表");
                    }
                    openModList();
                };
                java.lang.reflect.Method execute = null;
                for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                    if (m.getName().equals("execute") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Runnable.class) {
                        execute = m;
                        break;
                    }
                }
                if (execute != null) {
                    execute.invoke(mc, open);
                } else {
                    open.run();
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "[Mili] self-test 打开 Mod 列表失败", t);
            }
        }, "mili-selftest");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** 反射调用无参方法；失败返回 null。 */
    private static Object invokeNoArg(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 验收用：打开一个「什么控件都有」的声明式界面。
     *
     * <p>存在的意义是把 {@code ScreenService.open} 这条链路单独拎出来跑：
     * Mod 按 K 打开自己界面走的正是这条路（{@code ScreenSpec} →
     * {@code MiliScreenHost} → 原版 widget）。之前它从未在真实客户端里
     * 被完整执行过 —— 因为输入系统的装配时序是错的，Mod 连注册按键都失败。
     */
    private static void openSelftestScreen() {
        try {
            org.loader.api.gui.ScreenSpec spec =
                    org.loader.api.gui.ScreenSpec.builder("mili:selftest", "Selftest Screen")
                            .size(260, 170)
                            .label("title", "Custom screens are alive.", 12, 10)
                            .textField("name", "", 12, 32, 236, 20)
                            .checkbox("flag", "A checkbox", 12, 60, true)
                            .button("ok", "OK", 12, 134, 236, 20)
                            .build();

            org.loader.api.gui.ScreenService.open(spec,
                    new org.loader.api.gui.ScreenListener() {
                        @Override
                        public void onOpen() {
                            LOG.info("[Mili] self-test: 自定义屏幕已打开");
                            scheduleScreenshot("自定义屏幕");
                        }

                        @Override
                        public void onClick(String widgetId) {
                            LOG.info("[Mili] self-test: 控件点击 " + widgetId);
                            org.loader.api.gui.ScreenService.closeCurrent();
                        }

                        @Override
                        public void onClose(boolean cancelled) {
                            LOG.info("[Mili] self-test: 自定义屏幕关闭 cancelled=" + cancelled);
                        }
                    });
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] self-test 打开自定义屏幕失败", t);
        }
    }

    /**
     * 延迟 2 秒让游戏自己截一张图。
     *
     * <p>为什么必须由游戏自己截：GL/D3D 的前台缓冲在 DWM 合成之后
     * 并不存在于 GDI 可见的位图里，外部截屏工具拿到的往往是桌面壁纸。
     *
     * <p>延迟是为了让字体图集与首帧布局稳定 —— 第一帧的截图经常是一张
     * 「只有背景」的图，然后被误判成「界面没渲染」。
     */
    private static void scheduleScreenshot(String tag) {
        if (!Boolean.getBoolean("mili.selftest.screenshot")) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000L);
                Object mc = minecraftInstance();
                if (mc == null) {
                    return;
                }
                // 必须在渲染线程截图：Screenshot.takeScreenshot 会创建 GPU buffer，
                // 在别的线程调用直接抛
                //   IllegalStateException: Rendersystem called from wrong thread
                java.lang.reflect.Method execute = null;
                for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
                    if (m.getName().equals("execute") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Runnable.class) {
                        execute = m;
                        break;
                    }
                }
                Runnable grab = () -> {
                    try {
                        Reflect.gameClass("net.minecraft.client.Screenshot")
                                .getMethod("grab",
                                        Reflect.gameClass("net.minecraft.client.Minecraft"),
                                        boolean.class)
                                .invoke(null, mc, false);
                        LOG.info("[Mili] self-test: 已请求截图 (" + tag + ")，见 screenshots/");
                    } catch (Throwable e) {
                        LOG.log(Level.WARNING, "[Mili] self-test 截图失败", e);
                    }
                };
                if (execute != null) {
                    execute.invoke(mc, grab);
                } else {
                    LOG.warning("[Mili] self-test: 找不到 Minecraft.execute，跳过截图");
                }
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "[Mili] self-test 截图调度失败", e);
            }
        }, "mili-selftest-shot");
        t.setDaemon(true);
        t.start();
    }

    /** 诊断：把 screen 的 children 及其边界拼成一行日志。 */
    private static String describeChildren(Object screen) {
        try {
            java.lang.reflect.Method children = screen.getClass().getMethod("children");
            Object list = children.invoke(screen);
            if (!(list instanceof java.util.List<?> items)) {
                return "<?>";
            }
            StringBuilder sb = new StringBuilder("[").append(items.size()).append("]");
            for (Object w : items) {
                String name = w.getClass().getSimpleName();
                boolean ours = installedWidgets.containsKey(w);
                try {
                    int x = (int) w.getClass().getMethod("getX").invoke(w);
                    int y = (int) w.getClass().getMethod("getY").invoke(w);
                    int width = (int) w.getClass().getMethod("getWidth").invoke(w);
                    int height = (int) w.getClass().getMethod("getHeight").invoke(w);
                    sb.append(" ").append(name)
                            .append(ours ? "*" : "")
                            .append("(").append(x).append(",").append(y)
                            .append(" ").append(width).append("x").append(height).append(")");
                } catch (ReflectiveOperationException ignored) {
                    sb.append(" ").append(name).append(ours ? "*" : "");
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<error:" + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 屏幕上是否已经有我们添加的按钮。
     *
     * <p>判据是 widget 身份，不是 screen 身份 —— 见 {@code installedWidgets}
     * 的注释：{@code clearWidgets()} 会把 widget 清掉，而 screen 实例不变。
     */
    private static boolean alreadyHasModsButton(Object screen) {
        try {
            Object list = screen.getClass().getMethod("children").invoke(screen);
            if (list instanceof java.util.List<?> items) {
                for (Object w : items) {
                    if (installedWidgets.containsKey(w)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 取不到 children 时按「没有」处理：宁可多添加一次，
            // 也不要因为反射失败让按钮彻底消失。
        }
        return false;
    }

    /**
     * 在 Mods 按钮中心合成一次鼠标点击，交给游戏的输入分发链处理。
     *
     * <p>调用的是 {@code Screen.mouseClicked(MouseButtonEvent, boolean)} ——
     * 原版 GUI 的事件入口，它会遍历 children 做命中测试，命中后调用
     * {@code Button.onPress}。也就是说，除事件来源外，这条路径与玩家
     * 真实点击完全一致：坐标换算、命中判定、按钮回调、屏幕切换全部走到。
     *
     * @return 点击后当前屏幕是否真的变了（即列表打开了）
     */
    private static boolean simulateModsButtonClick() {
        try {
            Object screen = currentScreen();
            if (screen == null) {
                return false;
            }
            int[] b = lastButtonBounds;
            if (b == null) {
                LOG.warning("[Mili] self-test: 没有按钮边界记录");
                return false;
            }
            double cx = b[0] + b[2] / 2.0;
            double cy = b[1] + b[3] / 2.0;

            ClassLoader game = Reflect.gameClassLoader();
            Class<?> infoClass = Class.forName(
                    "net.minecraft.client.input.MouseButtonInfo", true, game);
            Class<?> eventClass = Class.forName(
                    "net.minecraft.client.input.MouseButtonEvent", true, game);

            Object info = infoClass.getConstructor(int.class, int.class)
                    .newInstance(0, 0);
            Object event = eventClass
                    .getConstructor(double.class, double.class, infoClass)
                    .newInstance(cx, cy, info);

            java.lang.reflect.Method click = null;
            for (Class<?> c = screen.getClass(); c != null && click == null;
                 c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("mouseClicked") && m.getParameterCount() == 2
                            && m.getParameterTypes()[0] == eventClass) {
                        click = m;
                        break;
                    }
                }
            }
            if (click == null) {
                return false;
            }
            click.setAccessible(true);
            Object handled = click.invoke(screen, event, false);
            LOG.info("[Mili] self-test: 点击 (" + (int) cx + "," + (int) cy
                    + ") handled=" + handled
                    + " method=" + click.getDeclaringClass().getSimpleName()
                    + "." + click.getName()
                    + " children=" + describeChildren(screen));

            // 屏幕对象被换掉 = setScreen 生效 = 列表打开了
            Object after = currentScreen();
            boolean changed = after != null && after != screen;
            LOG.info("[Mili] self-test: 点击后屏幕="
                    + (after == null ? "null" : after.getClass().getSimpleName())
                    + " changed=" + changed);
            return changed;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] self-test 模拟点击失败", t);
            return false;
        }
    }

    // ── 按钮安装 ────────────────────────────────────────────────────────────

    private static void installModsButton() throws Exception {
        Object screen = currentScreen();
        if (screen == null) {
            return; // 游戏启动早期可能尚无 screen
        }
        Class<?> titleClass = Reflect.gameClass(TITLE_SCREEN);
        if (!titleClass.isInstance(screen)) {
            return; // 只处理原版主界面
        }
        if (alreadyHasModsButton(screen)) {
            return; // 同一屏的重复 init（未清空 children）—— 不重复添加
        }

        Class<?> screenClass = Reflect.gameClass(SCREEN);
        int width = screenClass.getField("width").getInt(screen);
        int height = screenClass.getField("height").getInt(screen);

        // ── 位置：右上角固定锚点 ────────────────────────────────────────
        //
        // 旧实现在拿「选项按钮那一行」推算坐标：x = w/2-124,
        // y = h/4+168。在 854×480 的窗口（GUI 逻辑尺寸 426×240）下，
        // 算出来是 (89, 228) —— 按钮底部 248 > 240，被裁在屏幕外只露出
        // 一小条。玩家看到的是「主界面没有 Mods 按钮」，而日志里明明
        // 打印了「按钮已添加」。**坐标越界**和**按钮没装上**在日志里
        // 长得一模一样，这是最坏的失效形态。
        //
        // 右上角不依赖任何原版布局：标题文字居中、版权在底部，
        // 右上角在任何分辨率下都是空的。
        int bw = 64;
        int bh = 20;
        int x = width - bw - 6;
        int y = 6;

        Object widget = buildModsButton(screenClass, x, y, bw, bh);

        // protected <T> T addRenderableWidget(T) —— 沿父类链查找
        Method add = findInherited(screenClass, "addRenderableWidget", 1);
        if (add == null) {
            throw new IllegalStateException(
                    "Screen.addRenderableWidget 在 26.2 中不存在 —— 符号漂移，"
                            + "请更新 MiliSymbol/TITLE_SCREEN 相关坐标");
        }
        add.setAccessible(true);
        add.invoke(screen, widget);

        lastButtonBounds = new int[]{x, y, bw, bh};
        installedWidgets.put(widget, Boolean.TRUE);
        addCount.incrementAndGet();
        // INFO：按钮是否装上直接决定「点不动」问题能否定位 ——
        // 按钮存在但点击无反应 与 按钮根本不存在 是完全不同的故障。
        LOG.info("[Mili] Mods 按钮已添加到主界面 (" + x + "," + y + ")");
    }

    /**
     * 构造 Mods 按钮：{@code Button.builder(Component.literal("Mods"), onPress)
     * .bounds(x, y, w, h).build()}。
     */
    private static Object buildModsButton(Class<?> screenClass, int x, int y,
                                          int w, int h)
            throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        Class<?> buttonClass = Reflect.gameClass(BUTTON);
        Class<?> componentClass = Reflect.gameClass("net.minecraft.network.chat.Component");
        Class<?> onPressClass = Class.forName(BUTTON + "$OnPress", true, game);

        Object label = componentClass
                .getMethod("literal", String.class)
                .invoke(null, "Mods");

        Object onPress = Proxy.newProxyInstance(game, new Class<?>[]{onPressClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "Mili Mods Button";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    // INFO：用户点了按钮却没反应时，先确认回调到底
                    // 有没有被游戏调到 —— 没有这行，「点了没反应」与
                    // 「回调炸了」在日志里无法区分。
                    LOG.info("[Mili] Mods 按钮被点击: " + method.getName());
                    openModList();
                    return null;
                });

        Method builder = buttonClass.getMethod("builder", componentClass, onPressClass);
        Object b = builder.invoke(null, label, onPress);
        b = builder.getReturnType()
                .getMethod("bounds", int.class, int.class, int.class, int.class)
                .invoke(b, x, y, w, h);
        return builder.getReturnType().getMethod("build").invoke(b);
    }

    // ── mod 列表展示 ────────────────────────────────────────────────────────

    /**
     * 打开 mod 列表。
     *
     * <h2>两条路径，不是两条退路</h2>
     * 主路径是 {@link ModListScreenDispatch} —— 一个真正可滚动、可搜索、
     * 带状态色的自绘列表界面。旧实现用原版 {@code AlertScreen} 塞一坨
     * 纯文本，mod 一多就溢出屏幕且不可交互。
     *
     * <p>保留 AlertScreen 作为<b>降级</b>是有意的：新界面依赖
     * {@code GuiGraphicsExtractor} 的 {@code fill/text/enableScissor}
     * 三个坐标，任何一个在未来的 26.x 补丁里漂移，症状都会是
     * 「点了按钮什么都没有」。宁可退化成一段丑文本，也不能让玩家
     * 面对一个点了没反应的按钮 —— 后者无法区分「界面崩了」和
     * 「按钮没接上」，是最难排查的失效形态。
     */
    private static void openModList() {
        if (ModListScreenDispatch.open()) {
            return;
        }
        LOG.warning("[Mili] 自绘 mod 列表不可用，降级到 AlertScreen");
        openModListFallback();
    }

    /** 降级路径：原版 AlertScreen，纯文本。 */
    private static void openModListFallback() {
        try {
            Object screen = currentScreen();
            Object gui = guiOf(screen);
            if (gui == null) {
                LOG.warning("[Mili] 打开 mod 列表: 当前无 screen 或取不到 gui，放弃");
                return;
            }

            StringBuilder msg = new StringBuilder();
            msg.append(org.loader.api.VersionInfo.PLATFORM_ID).append('\n');
            List<String> mods = modListProvider.get();
            msg.append("Loaded mods: ").append(mods.size()).append('\n');
            if (mods.isEmpty()) {
                msg.append("  (none)").append('\n');
            } else {
                for (String m : mods) {
                    msg.append("  - ").append(m).append('\n');
                }
            }

            Class<?> componentClass =
                    Reflect.gameClass("net.minecraft.network.chat.Component");
            Object title = componentClass.getMethod("literal", String.class)
                    .invoke(null, "Mili Loader");
            Object body = componentClass.getMethod("literal", String.class)
                    .invoke(null, msg.toString());

            Class<?> alertClass = Reflect.gameClass(
                    "net.minecraft.client.gui.screens.AlertScreen");
            Object alert = alertClass
                    .getConstructor(Runnable.class, componentClass, componentClass)
                    .newInstance((Runnable) () -> showScreen(gui, screen),
                            title, body);

            showScreen(gui, alert);
            LOG.info("[Mili] mod 列表已展示 (" + mods.size() + " mods)");
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 打开 mod 列表失败", t);
        }
    }

    // ── 反射辅助（坐标均已按 26.2 反编译源核实） ────────────────────────────

    /** {@code Minecraft.getInstance()}；游戏未启动时返回 null。 */
    private static Object minecraftInstance() throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        if (game == null) {
            return null;
        }
        Class<?> mcClass = Reflect.gameClass("net.minecraft.client.Minecraft");
        return mcClass.getMethod("getInstance").invoke(null);
    }

    /** {@code minecraft.gui.screen()} —— 当前屏幕；取不到时返回 null。 */
    private static Object currentScreen() throws Exception {
        Object gui = guiOf(minecraftInstance());
        if (gui == null) {
            return null;
        }
        return gui.getClass().getMethod("screen").invoke(gui);
    }

    /** {@code minecraft.gui} —— public final 字段。 */
    private static Object guiOf(Object minecraft) throws Exception {
        if (minecraft == null) {
            return null;
        }
        return Reflect.instanceField(minecraft,
                "net.minecraft.client.Minecraft", "gui");
    }

    /** {@code gui.setScreen(screen)}。 */
    private static void showScreen(Object gui, Object screen) {
        try {
            Class<?> screenClass = Reflect.gameClass(SCREEN);
            gui.getClass().getMethod("setScreen", screenClass).invoke(gui, screen);
        } catch (ReflectiveOperationException e) {
            // setScreen 符号漂移属于平台 bug —— 带着原因炸在调用栈上，
            // 而不是伪装成「屏幕没打开」。收窄声明还让本方法可以
            // 直接出现在 Runnable lambda 里（run() 不允许 checked 异常）。
            throw new IllegalStateException("反射调用 setScreen 失败", e);
        }
    }

    /** 沿父类链查找按名声明的方法（覆盖 protected 泛型方法）。 */
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

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 已添加按钮的次数。 */
    public static long addButtonCount() {
        return addCount.get();
    }

    /** 累计失败次数（注入与打开列表）。 */
    public static long errorCount() {
        return errorCount.get();
    }

    /** 当前 mod 列表内容快照（诊断用）。 */
    public static List<String> currentModList() {
        List<String> mods = modListProvider.get();
        return mods != null ? List.copyOf(mods) : List.of();
    }

    public static String diagnostics() {
        return "TitleScreenDispatch[buttons=" + addCount.get()
                + ", errors=" + errorCount.get()
                + ", mods=" + currentModList().size() + "]";
    }
}
