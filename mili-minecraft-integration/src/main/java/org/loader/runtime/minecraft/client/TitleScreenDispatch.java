package org.loader.runtime.minecraft.client;

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

    /** 已成功添加按钮的屏幕（弱引用，防 resize 重复 init 时重复添加）。 */
    private static final java.util.Map<Object, Boolean> instrumented =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static final java.util.concurrent.atomic.AtomicLong addCount =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong errorCount =
            new java.util.concurrent.atomic.AtomicLong();

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
        } catch (Throwable t) {
            errorCount.incrementAndGet();
            LOG.log(Level.WARNING, "[Mili] 主界面 Mods 按钮注入失败（不影响游戏）", t);
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
        if (instrumented.containsKey(screen)) {
            return; // resize 重入：同一实例已注入过
        }

        Class<?> screenClass = Reflect.gameClass(SCREEN);
        int width = screenClass.getField("width").getInt(screen);
        int height = screenClass.getField("height").getInt(screen);

        // 位置：Options 按钮所在行（h/4+168），位于其左侧 4px ——
        // 与主流 mod 平台（Mod Menu）的布局习惯一致。
        int x = width / 2 - 124;
        int y = height / 4 + 168;

        Object widget = buildModsButton(screenClass, x, y);

        // protected <T> T addRenderableWidget(T) —— 沿父类链查找
        Method add = findInherited(screenClass, "addRenderableWidget", 1);
        if (add == null) {
            throw new IllegalStateException(
                    "Screen.addRenderableWidget 在 26.2 中不存在 —— 符号漂移，"
                            + "请更新 MiliSymbol/TITLE_SCREEN 相关坐标");
        }
        add.setAccessible(true);
        add.invoke(screen, widget);

        instrumented.put(screen, Boolean.TRUE);
        addCount.incrementAndGet();
        LOG.fine(() -> "[Mili] Mods 按钮已添加到主界面");
    }

    /**
     * 构造 Mods 按钮：{@code Button.builder(Component.literal("Mods"), onPress)
     * .bounds(x, y, 20, 20).build()}。
     */
    private static Object buildModsButton(Class<?> screenClass, int x, int y)
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
                    openModList();
                    return null;
                });

        Method builder = buttonClass.getMethod("builder", componentClass, onPressClass);
        Object b = builder.invoke(null, label, onPress);
        b = builder.getReturnType()
                .getMethod("bounds", int.class, int.class, int.class, int.class)
                .invoke(b, x, y, 20, 20);
        return builder.getReturnType().getMethod("build").invoke(b);
    }

    // ── mod 列表展示 ────────────────────────────────────────────────────────

    /**
     * 打开 mod 列表 —— 用原版 {@code AlertScreen}（标题 + 多行文本 +
     * 返回按钮），文本按 {@code \n} 换行、超宽自动折行。
     */
    private static void openModList() {
        try {
            Object screen = currentScreen();
            Object gui = guiOf(screen);
            if (gui == null) {
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
