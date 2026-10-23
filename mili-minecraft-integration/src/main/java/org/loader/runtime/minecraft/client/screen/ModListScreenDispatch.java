package org.loader.runtime.minecraft.client.screen;

import org.loader.runtime.minecraft.reflect.Reflect;
import org.loader.runtime.modlist.ModEntry;
import org.loader.runtime.modlist.ModListSnapshot;
import org.loader.runtime.modlist.ModListSource;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mod 列表屏幕的<b>渲染与交互分发器</b> —— 平台的「Mod 菜单」。
 *
 * <h2>它解决的是什么</h2>
 * 之前的实现点击 Mods 按钮后弹一个原版 {@code AlertScreen}：一坨纯文本，
 * mod 一多就溢出屏幕、不能滚动、不能搜索、看不出谁加载失败。
 * 那不是「Mod 列表」，那是「一段日志」。
 *
 * <p>本类实现的界面有：
 * <ul>
 *   <li>可滚动列表（滚轮 + 方向键 + 拖动）</li>
 *   <li>实时搜索（直接键入过滤 id / 名称 / 描述）</li>
 *   <li>每行状态色条：绿色正常、红色异常、灰色未知</li>
 *   <li>底部状态栏：堆内存占用、运行时长、选中项完整描述</li>
 *   <li>选中高亮与悬停反馈</li>
 * </ul>
 *
 * <h2>为什么整屏自绘，不用原版 widget</h2>
 * 26.2 的 widget 体系（{@code AbstractWidget} → {@code GuiGraphicsExtractor}）
 * 每个版本都在动，而列表需要的只是「矩形 + 文字」两件事。
 * 自绘把依赖面收缩到 {@code fill} / {@code text} /
 * {@code enableScissor} 三个方法 —— 这三个从 1.6 到 26.2 语义没变过。
 * 用原版 Button 拼列表反而会引入 {@code Button.builder(...).bounds(...)}
 * 这类每版都可能改的链式 API。
 *
 * <h2>异常策略</h2>
 * {@code render} 与 {@code onInit} 都是从游戏渲染循环里被调用的。
 * <b>任何异常都必须吞掉</b>：一个 Mod 列表画不出来是小事，
 * 每帧抛异常把客户端拖垮是大事。异常计数进 {@link #errorCount()}，
 * 可在启动报告里看到。
 */
public final class ModListScreenDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/ModList");

    // ── 反射坐标（全部按 26.2 反编译源核实） ────────────────────────────────
    private static final String MC = "net.minecraft.client.Minecraft";
    private static final String SCREEN = "net.minecraft.client.gui.screens.Screen";
    private static final String COMPONENT = "net.minecraft.network.chat.Component";
    private static final String GRAPHICS =
            "net.minecraft.client.gui.GuiGraphicsExtractor";
    private static final String FONT = "net.minecraft.client.gui.Font";

    // ── 布局 ────────────────────────────────────────────────────────────────
    private static final int ROW_HEIGHT = 34;
    private static final int PANEL_PAD = 12;
    private static final int HEADER_HEIGHT = 30;
    private static final int SEARCH_HEIGHT = 18;
    private static final int FOOTER_HEIGHT = 18;
    private static final int SCROLLBAR_WIDTH = 4;

    // ── 颜色（ARGB） ────────────────────────────────────────────────────────
    private static final int C_PANEL = 0xF00E1116;
    private static final int C_PANEL_EDGE = 0xFF2E3A4A;
    private static final int C_SHADOW = 0x70000000;
    private static final int C_TITLE = 0xFFFFFFFF;
    private static final int C_TEXT = 0xFFD6DEE8;
    private static final int C_MUTED = 0xFF7C8798;
    private static final int C_ACCENT = 0xFF54A6E8;
    private static final int C_OK = 0xFF57C97A;
    private static final int C_BAD = 0xFFE0645F;
    private static final int C_WARN = 0xFFD9A441;
    private static final int C_ROW_HOVER = 0x24FFFFFF;
    private static final int C_ROW_SEL = 0x3A54A6E8;
    private static final int C_SEARCH_BG = 0xFF171C24;
    private static final int C_SEARCH_EDGE = 0xFF33404F;
    private static final int C_SCROLL_TRACK = 0x30FFFFFF;
    private static final int C_SCROLL_THUMB = 0x90A8B6C8;

    // ── 状态 ────────────────────────────────────────────────────────────────

    private static volatile ModListSource source = ModListSource.EMPTY;

    /** 当前屏幕实例（弱引用：屏幕被游戏丢弃后不该被平台吊住）。 */
    private static WeakReference<Object> activeScreen = new WeakReference<>(null);

    private static volatile ModListSnapshot snapshot = ModListSnapshot.empty();

    /**
     * 底栏右侧文本 —— 在快照赋值时算一次。
     *
     * <p>这里原本每帧调用 {@code heapText() + uptimeText()}：前者两次
     * 字符串拼接，后者一次 {@code String.format}。三者都在渲染线程上，
     * 60fps 下每秒产生 180 个以上的短命对象，全部由 Young GC 买单。
     * 快照在屏幕生命周期内不变，这串文本也不变 —— 没有理由每帧重算。
     */
    private static String footerRight = "";

    /** 过滤后的行索引（指向 snapshot.mods()）。 */
    private static int[] visible = new int[0];

    private static final StringBuilder query = new StringBuilder();

    private static int scroll;
    private static int selected = -1;
    private static int hovered = -1;
    private static boolean searchFocused = true;
    private static long openedAtNanos;

    private static long errorCount;
    private static long renderCount;

    private ModListScreenDispatch() {
    }

    // ── 装配 ────────────────────────────────────────────────────────────────

    /**
     * 装配数据源。必须在主界面之前调用（loader 启动期）。
     *
     * @param modListSource 快照来源；null 视为空
     */
    public static void setSource(ModListSource modListSource) {
        source = modListSource != null ? modListSource : ModListSource.EMPTY;
    }

    /**
     * 打开 Mod 列表。由主界面的 Mods 按钮调用，必须运行在客户端主线程。
     *
     * @return 是否成功打开
     */
    public static boolean open() {
        try {
            Object mc = minecraftInstance();
            if (mc == null) {
                LOG.warning("[Mili] 打开 Mod 列表失败：Minecraft 实例尚未就绪");
                return false;
            }
            Object gui = guiOf(mc);
            if (gui == null) {
                LOG.warning("[Mili] 打开 Mod 列表失败：取不到 Gui");
                return false;
            }

            // 快照在「按下按钮」这一刻拍一次 —— 界面生命周期内不再变化
            snapshot = source.snapshot();
            if (snapshot == null) {
                snapshot = ModListSnapshot.empty();
            }
            footerRight = "Heap " + snapshot.heapText()
                    + "  -  " + snapshot.uptimeText();
            query.setLength(0);
            // 验收用：预置搜索词，让截图能证明「过滤真的生效」。
            // 不带这个属性时是正常路径（空搜索 = 全量列表）。
            String preset = System.getProperty("mili.selftest.search");
            if (preset != null && !preset.isBlank()) {
                query.append(preset);
            }
            scroll = 0;
            selected = -1;
            hovered = -1;
            searchFocused = true;
            openedAtNanos = System.nanoTime();
            firstRenderNanos = 0L;
            captured = false;
            refilter();

            Object title = Reflect.gameClass(COMPONENT)
                    .getMethod("literal", String.class)
                    .invoke(null, "Mili Mods");
            Object screen = hostClass()
                    .getConstructor(Reflect.gameClass(COMPONENT))
                    .newInstance(title);
            activeScreen = new WeakReference<>(screen);

            gui.getClass().getMethod("setScreen", Reflect.gameClass(SCREEN))
                    .invoke(gui, screen);

            LOG.info("[Mili] Mod 列表已打开 (" + snapshot.mods().size() + " mods)");
            return true;
        } catch (Throwable t) {
            errorCount++;
            LOG.log(Level.WARNING, "[Mili] 打开 Mod 列表失败", t);
            return false;
        }
    }

    /** 是否已打开（诊断用）。 */
    public static boolean isShowing() {
        Object screen = activeScreen.get();
        if (screen == null) {
            return false;
        }
        try {
            Object mc = minecraftInstance();
            Object gui = guiOf(mc);
            if (gui == null) {
                return false;
            }
            Object current = gui.getClass().getMethod("screen").invoke(gui);
            return current == screen;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 生命周期回调（由生成的 MiliModListScreen 调用） ─────────────────────

    /** {@code init()} —— 屏幕首次构建。描述符固定 {@code (Object)V}。 */
    public static void onInit(Object host) {
        try {
            activeScreen = new WeakReference<>(host);
            refilter();
        } catch (Throwable t) {
            errorCount++;
            LOG.log(Level.WARNING, "[Mili] Mod 列表初始化失败", t);
        }
    }

    /** {@code extractRenderState} 之后的自绘阶段。 */
    public static void render(Object host, Object graphics,
                             int mouseX, int mouseY, float partialTick) {
        renderCount++;
        try {
            Layout l = layout(host);
            if (l == null) {
                return;
            }
            Object font = fontOf(host);
            if (font == null) {
                return;
            }
            hovered = l.rowAt(mouseY, scroll);
            drawFrame(graphics, font, l);
            drawHeader(graphics, font, l);
            drawSearch(graphics, font, l);
            drawRows(graphics, font, l, mouseX, mouseY);
            drawScrollbar(graphics, l);
            drawFooter(graphics, font, l);
            maybeCaptureSelfTest();
        } catch (Throwable t) {
            errorCount++;
            // 每帧都可能失败 —— 只在第一次打完整堆栈，之后降级为计数
            if (errorCount == 1) {
                LOG.log(Level.WARNING, "[Mili] Mod 列表渲染失败（后续静默计数）", t);
            }
        }
    }

    /**
     * 自动化验收：渲染稳定后让<b>游戏自己</b>截一张图。
     *
     * <h2>为什么不用外部截屏工具</h2>
     * 通过 GDI 抓屏拿到的往往是桌面壁纸而不是游戏画面 —— GL/D3D 的
     * 前台缓冲在 DWM 合成之后并不存在于 GDI 可见的位图里，
     * 远程会话下尤其如此。而 {@code Screenshot.grab} 直接读游戏的
     * RenderTarget，是唯一「拍到的就是玩家看到的」的路径。
     *
     * <p>延迟 1.5 秒再拍：面板刚出现时字体图集、滚动条位置、
     * 首帧布局可能还没稳定。第一帧的截图很容易变成一张「只有背景」的
     * 图，然后被误判成「界面没渲染」。
     */
    private static void maybeCaptureSelfTest() {
        if (!SELF_TEST_SCREENSHOT || captured) {
            return;
        }
        long now = System.nanoTime();
        if (firstRenderNanos == 0L) {
            firstRenderNanos = now;
            return;
        }
        // 启动遮罩（资源 reload 的 LoadingOverlay）还在时不要拍：
        // 它会盖住整个界面，拍下来是一张「UI 没渲染」的假证据。
        if (invokeNoArg(guiOf(minecraftInstance()), "overlay") != null) {
            firstRenderNanos = now;
            return;
        }
        if (now - firstRenderNanos < 1_500_000_000L) {
            return;
        }
        captured = true;
        try {
            Object mc = minecraftInstance();
            if (mc == null) {
                return;
            }
            Class<?> screenshot = Reflect.gameClass("net.minecraft.client.Screenshot");
            screenshot.getMethod("grab", Reflect.gameClass(MC), boolean.class)
                    .invoke(null, mc, false);
            LOG.info("[Mili] self-test: 已请求游戏截图（screenshots/）"
                    + " renders=" + renderCount + " errors=" + errorCount
                    + " visible=" + visible.length);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] self-test 截图失败", t);
        }
    }

    private static final boolean SELF_TEST_SCREENSHOT =
            Boolean.getBoolean("mili.selftest.screenshot");
    private static volatile long firstRenderNanos;
    private static volatile boolean captured;

    /** {@code mouseClicked(MouseButtonEvent, boolean)}。 */
    public static boolean onMouseClicked(Object event) {
        try {
            double mx = ((Number) event.getClass().getMethod("x").invoke(event)).doubleValue();
            double my = ((Number) event.getClass().getMethod("y").invoke(event)).doubleValue();
            int button = ((Number) event.getClass().getMethod("button").invoke(event)).intValue();
            if (button != 0) {
                return false;
            }
            Object screen = activeScreen.get();
            Layout l = layout(screen);
            if (l == null) {
                return false;
            }

            // 关闭按钮
            if (l.inClose(mx, my)) {
                close(screen);
                return true;
            }
            // 搜索框
            if (l.inSearch(mx, my)) {
                searchFocused = true;
                return true;
            }
            // 列表区
            if (l.inList(mx, my)) {
                int row = l.rowAt((int) my, scroll);
                if (row >= 0 && row < visible.length) {
                    selected = (selected == row) ? -1 : row;
                }
                searchFocused = false;
                return true;
            }
            return false;
        } catch (Throwable t) {
            errorCount++;
            LOG.log(Level.FINE, "[Mili] Mod 列表点击处理失败", t);
            return false;
        }
    }

    /** {@code mouseScrolled(double, double, double, double)}。 */
    public static boolean onScroll(double mouseX, double mouseY,
                                   double scrollX, double scrollY) {
        try {
            Object screen = activeScreen.get();
            Layout l = layout(screen);
            if (l == null || !l.inList(mouseX, mouseY)) {
                return false;
            }
            int max = maxScroll(l);
            if (max <= 0) {
                return false;
            }
            scroll = clamp(scroll - (int) Math.round(scrollY * ROW_HEIGHT), 0, max);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code keyPressed(KeyEvent)}。GLFW 键码：257=Enter 259=Backspace 256=Esc 264/265=↓/↑。 */
    public static boolean onKeyPressed(Object event) {
        try {
            int key = ((Number) event.getClass().getMethod("key").invoke(event)).intValue();
            Object screen = activeScreen.get();
            Layout l = layout(screen);

            if (key == 256) { // ESC
                close(screen);
                return true;
            }
            if (key == 259) { // BACKSPACE
                if (query.length() > 0) {
                    query.setLength(query.length() - 1);
                    refilter();
                    scroll = 0;
                }
                return true;
            }
            if (key == 257) { // ENTER
                if (selected >= 0 && selected < visible.length) {
                    selected = -1;
                    return true;
                }
                return false;
            }
            if (key == 265 || key == 264) { // UP / DOWN
                if (visible.length == 0) {
                    return true;
                }
                int next = selected < 0 ? 0 : selected + (key == 265 ? -1 : 1);
                selected = clamp(next, 0, visible.length - 1);
                ensureVisible(l);
                return true;
            }
            if (key == 266 || key == 267) { // PAGE UP / PAGE DOWN
                int page = Math.max(1, listRows(l));
                int next = selected < 0 ? 0 : selected + (key == 266 ? -page : page);
                selected = clamp(next, 0, Math.max(0, visible.length - 1));
                ensureVisible(l);
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code charTyped(CharacterEvent)}。 */
    public static boolean onCharTyped(Object event) {
        try {
            int cp = ((Number) event.getClass().getMethod("codepoint").invoke(event)).intValue();
            if (cp < 32 || cp == 127) {
                return false;
            }
            if (query.length() >= 48) {
                return true;
            }
            query.appendCodePoint(cp);
            refilter();
            scroll = 0;
            searchFocused = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** {@code onClose()} —— 屏幕被 Esc 或外部 setScreen 关闭。 */
    public static void onClosed(Object host) {
        activeScreen = new WeakReference<>(null);
        hovered = -1;
    }

    // ── 布局 ────────────────────────────────────────────────────────────────

    /**
     * 计算当前屏幕的几何布局。
     *
     * <p>纯函数，除了读 {@code width/height} 字段。抽出来是为了让
     * 「面板超出屏幕」「列表高度为负」这类边界能被单测直接覆盖 ——
     * 靠肉眼在游戏里试各种分辨率是不可靠的。
     */
    static Layout layout(Object screen) {
        if (screen == null) {
            return null;
        }
        try {
            Class<?> screenClass = Reflect.gameClass(SCREEN);
            int width = screenClass.getField("width").getInt(screen);
            int height = screenClass.getField("height").getInt(screen);
            return Layout.of(width, height);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 几何布局值对象。坐标已含内边距，调用方直接使用。
     */
    record Layout(int width, int height,
                  int panelX, int panelY, int panelW, int panelH,
                  int listX, int listY, int listW, int listH,
                  int searchX, int searchY, int searchW, int searchH,
                  int closeX, int closeY, int closeSize,
                  int footerY) {

        static Layout of(int width, int height) {
            int panelW = Math.min(460, Math.max(180, width - 40));
            int panelH = Math.min(400, Math.max(150, height - 40));
            int panelX = (width - panelW) / 2;
            int panelY = (height - panelH) / 2;

            int innerX = panelX + PANEL_PAD;
            int innerW = panelW - PANEL_PAD * 2;

            int searchY = panelY + PANEL_PAD + HEADER_HEIGHT;
            int listY = searchY + SEARCH_HEIGHT + 8;
            int footerY = panelY + panelH - PANEL_PAD - FOOTER_HEIGHT;
            int listH = Math.max(20, footerY - 8 - listY);

            int closeSize = 12;
            return new Layout(width, height,
                    panelX, panelY, panelW, panelH,
                    innerX, listY, innerW, listH,
                    innerX, searchY, innerW, SEARCH_HEIGHT,
                    panelX + panelW - PANEL_PAD - closeSize,
                    panelY + PANEL_PAD + 2, closeSize,
                    footerY);
        }

        boolean inClose(double x, double y) {
            return x >= closeX && x <= closeX + closeSize
                    && y >= closeY && y <= closeY + closeSize;
        }

        boolean inSearch(double x, double y) {
            return x >= searchX && x <= searchX + searchW
                    && y >= searchY && y <= searchY + searchH;
        }

        boolean inList(double x, double y) {
            return x >= listX && x <= listX + listW
                    && y >= listY && y <= listY + listH;
        }

        /**
         * 鼠标所在行的<b>下标</b>；不在列表矩形内返回 -1。
         *
         * <p>刻意只做几何换算，不检查「是否超出可见条目数」——
         * 后者依赖 {@code visible} 这份可变状态，让这个函数无法脱离
         * 界面上下文测试。调用方（点击处理）负责边界检查：
         * {@code row >= 0 && row < visible.length}。
         *
         * <p>这个划分是被一次真实的测试失败逼出来的：原来的实现
         * 在无 Mod 时对列表内任意坐标都返回 -1，于是「行命中」的
         * 单测根本无法在不启动界面的前提下通过 —— 而需要启动整个
         * Minecraft 才能验证的坐标换算，实际上等于没测。
         */
        int rowAt(int mouseY, int scroll) {
            if (mouseY < listY || mouseY > listY + listH) {
                return -1;
            }
            return Math.max(0, (mouseY - listY + scroll) / ROW_HEIGHT);
        }
    }

    private static int listRows(Layout l) {
        return Math.max(1, l.listH() / ROW_HEIGHT);
    }

    private static int contentHeight() {
        return visible.length * ROW_HEIGHT;
    }

    private static int maxScroll(Layout l) {
        return Math.max(0, contentHeight() - l.listH());
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────

    private static void drawFrame(Object g, Object font, Layout l) {
        // 投影
        fill(g, l.panelX() + 3, l.panelY() + 3,
                l.panelX() + l.panelW() + 3, l.panelY() + l.panelH() + 3, C_SHADOW);
        // 面板
        fill(g, l.panelX(), l.panelY(),
                l.panelX() + l.panelW(), l.panelY() + l.panelH(), C_PANEL);

        // 边框（四条 1px）
        fill(g, l.panelX(), l.panelY(),
                l.panelX() + l.panelW(), l.panelY() + 1, C_PANEL_EDGE);
        fill(g, l.panelX(), l.panelY() + l.panelH() - 1,
                l.panelX() + l.panelW(), l.panelY() + l.panelH(), C_PANEL_EDGE);
        fill(g, l.panelX(), l.panelY(),
                l.panelX() + 1, l.panelY() + l.panelH(), C_PANEL_EDGE);
        fill(g, l.panelX() + l.panelW() - 1, l.panelY(),
                l.panelX() + l.panelW(), l.panelY() + l.panelH(), C_PANEL_EDGE);
    }

    private static void drawHeader(Object g, Object font, Layout l) {
        int x = l.panelX() + PANEL_PAD;
        int y = l.panelY() + PANEL_PAD;
        text(g, font, "Mili Mods", x, y, C_TITLE);

        int count = visible.length;
        String badge = count + " / " + snapshot.mods().size();
        int badgeW = fontWidth(font, badge);
        text(g, font, badge, x + l.panelW() - PANEL_PAD * 2
                - l.closeSize() - 8 - badgeW, y, C_MUTED);

        // 关闭按钮 —— 两条对角线，避免字体缺字形
        int cx = l.closeX();
        int cy = l.closeY();
        int cs = l.closeSize();
        for (int i = 0; i < cs; i++) {
            fill(g, cx + i, cy + i, cx + i + 1, cy + i + 1, C_MUTED);
            fill(g, cx + cs - 1 - i, cy + i, cx + cs - i, cy + i + 1, C_MUTED);
        }

        // 标题下的分隔线
        int lineY = l.panelY() + PANEL_PAD + HEADER_HEIGHT - 6;
        fill(g, x, lineY, x + l.panelW() - PANEL_PAD * 2, lineY + 1, 0xFF232C38);
    }

    private static void drawSearch(Object g, Object font, Layout l) {
        fill(g, l.searchX(), l.searchY(),
                l.searchX() + l.searchW(), l.searchY() + l.searchH(), C_SEARCH_BG);
        int edge = searchFocused ? C_ACCENT : C_SEARCH_EDGE;
        fill(g, l.searchX(), l.searchY(),
                l.searchX() + l.searchW(), l.searchY() + 1, edge);
        fill(g, l.searchX(), l.searchY() + l.searchH() - 1,
                l.searchX() + l.searchW(), l.searchY() + l.searchH(), edge);
        fill(g, l.searchX(), l.searchY(),
                l.searchX() + 1, l.searchY() + l.searchH(), edge);
        fill(g, l.searchX() + l.searchW() - 1, l.searchY(),
                l.searchX() + l.searchW(), l.searchY() + l.searchH(), edge);

        String text = query.length() == 0 ? "Search mods..." : query.toString();
        int color = query.length() == 0 ? C_MUTED : C_TEXT;
        text(g, font, ellipsize(font, text, l.searchW() - 10),
                l.searchX() + 5, l.searchY() + 5, color);

        // 光标：只在聚焦且为空/末尾时画
        if (searchFocused) {
            String visibleQuery = query.length() == 0 ? "" : query.toString();
            int cursorX = l.searchX() + 5 + fontWidth(font, visibleQuery);
            fill(g, cursorX + 1, l.searchY() + 4,
                    cursorX + 2, l.searchY() + l.searchH() - 4, C_ACCENT);
        }
    }

    private static void drawRows(Object g, Object font, Layout l, int mouseX, int mouseY) {
        // 裁剪：滚动内容不能画到列表区域外面
        scissor(g, l.listX(), l.listY(), l.listX() + l.listW(), l.listY() + l.listH());
        try {
            if (visible.length == 0) {
                String empty = query.length() > 0
                        ? "No mod matches \"" + query + "\""
                        : "No mods loaded";
                text(g, font, ellipsize(font, empty, l.listW() - 8),
                        l.listX() + 4, l.listY() + 8, C_MUTED);
                return;
            }

            int first = Math.max(0, scroll / ROW_HEIGHT);
            int last = Math.min(visible.length - 1,
                    (scroll + l.listH()) / ROW_HEIGHT + 1);

            for (int i = first; i <= last; i++) {
                int y = l.listY() + i * ROW_HEIGHT - scroll;
                boolean isSelected = i == selected;
                boolean isHovered = i == hovered;

                if (isSelected) {
                    fill(g, l.listX(), y, l.listX() + l.listW(), y + ROW_HEIGHT - 2,
                            C_ROW_SEL);
                } else if (isHovered) {
                    fill(g, l.listX(), y, l.listX() + l.listW(), y + ROW_HEIGHT - 2,
                            C_ROW_HOVER);
                }

                ModEntry entry = snapshot.mods().get(visible[i]);
                int stateColor = stateColor(entry.state());
                // 左侧状态条
                fill(g, l.listX(), y, l.listX() + 3, y + ROW_HEIGHT - 2, stateColor);

                int textX = l.listX() + 9;
                text(g, font, ellipsize(font, entry.title(), l.listW() - 60),
                        textX, y + 5, isSelected ? C_TITLE : C_TEXT);

                // 右侧状态标签（右对齐）
                String state = entry.state();
                int stateW = fontWidth(font, state);
                text(g, font, state,
                        l.listX() + l.listW() - stateW - 6, y + 5, stateColor);

                // 第二行：来源 + 类数（描述留给选中后的底栏，避免行内挤压）
                String sub = entry.source().isEmpty()
                        ? entry.loadedClasses() + " classes"
                        : entry.source() + " · " + entry.loadedClasses() + " classes";
                text(g, font, ellipsize(font, sub, l.listW() - 20),
                        textX, y + 18, C_MUTED);

                // 行分隔线
                fill(g, l.listX() + 6, y + ROW_HEIGHT - 1,
                        l.listX() + l.listW() - 6, y + ROW_HEIGHT - 1, 0x18FFFFFF);
            }
        } finally {
            unscissor(g);
        }
    }

    private static void drawScrollbar(Object g, Layout l) {
        int max = maxScroll(l);
        if (max <= 0) {
            return;
        }
        int trackX = l.listX() + l.listW() - SCROLLBAR_WIDTH;
        int trackY = l.listY();
        int trackH = l.listH();
        fill(g, trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, C_SCROLL_TRACK);

        int thumbH = Math.max(16, trackH * trackH / Math.max(1, contentHeight()));
        int thumbY = trackY + (trackH - thumbH) * scroll / max;
        fill(g, trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, C_SCROLL_THUMB);
    }

    private static void drawFooter(Object g, Object font, Layout l) {
        int x = l.panelX() + PANEL_PAD;
        int y = l.footerY();

        // 选中项优先显示完整描述 —— 这是「详情」的最轻实现
        String left;
        int color;
        if (selected >= 0 && selected < visible.length) {
            ModEntry entry = snapshot.mods().get(visible[selected]);
            String desc = entry.description().isEmpty() ? "(no description)" : entry.description();
            String author = entry.author().isEmpty() ? "" : "  ·  by " + entry.author();
            left = desc + author;
            color = C_TEXT;
        } else {
            left = "Scroll - type to search - click to select - ESC to close";
            color = C_MUTED;
        }

        // 右侧内存与运行时长（快照时已算好，不在这里拼字符串）
        String right = footerRight;
        int rightW = fontWidth(font, right);
        int leftMax = l.panelW() - PANEL_PAD * 2 - rightW - 10;
        text(g, font, ellipsize(font, left, Math.max(20, leftMax)), x, y, color);
        text(g, font, right, x + l.panelW() - PANEL_PAD * 2 - rightW, y, C_MUTED);
    }

    private static int stateColor(String state) {
        if (state == null) {
            return C_MUTED;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "ACTIVE", "INITIALIZING" -> C_OK;
            case "SUSPENDED", "FAILED", "UNLOADED" -> C_BAD;
            case "LOADING" -> C_WARN;
            default -> C_MUTED;
        };
    }

    // ── 过滤 / 滚动 ─────────────────────────────────────────────────────────

    private static void refilter() {
        List<ModEntry> mods = snapshot.mods();
        String q = query.toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            int[] all = new int[mods.size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            visible = all;
            return;
        }

        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < mods.size(); i++) {
            ModEntry e = mods.get(i);
            if (e.id().toLowerCase(Locale.ROOT).contains(q)
                    || e.name().toLowerCase(Locale.ROOT).contains(q)
                    || e.description().toLowerCase(Locale.ROOT).contains(q)
                    || e.author().toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(i);
            }
        }
        int[] out = new int[hits.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hits.get(i);
        }
        visible = out;
        if (selected >= visible.length) {
            selected = -1;
        }
    }

    private static void ensureVisible(Layout l) {
        if (l == null || selected < 0) {
            return;
        }
        int top = selected * ROW_HEIGHT;
        int bottom = top + ROW_HEIGHT;
        if (top < scroll) {
            scroll = top;
        } else if (bottom > scroll + l.listH()) {
            scroll = bottom - l.listH();
        }
        scroll = clamp(scroll, 0, maxScroll(l));
    }

    // ── 关闭 ────────────────────────────────────────────────────────────────

    private static void close(Object screen) {
        try {
            Object mc = minecraftInstance();
            Object gui = guiOf(mc);
            if (gui == null) {
                return;
            }
            // 优先走原版 onClose()：它会打点、回调 removed()，语义完整
            if (screen != null) {
                screen.getClass().getMethod("onClose").invoke(screen);
            } else {
                gui.getClass().getMethod("setScreen", Reflect.gameClass(SCREEN))
                        .invoke(gui, (Object) null);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "[Mili] 关闭 Mod 列表失败", t);
        }
    }

    // ── 反射辅助（带缓存） ──────────────────────────────────────────────────

    private static volatile Method mFill;
    private static volatile Method mText;
    private static volatile Method mScissor;
    private static volatile Method mUnscissor;
    private static volatile Method mFontWidth;
    private static volatile Field fFont;

    private static void fill(Object g, int x0, int y0, int x1, int y1, int color) {
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
                        .getMethod("fill", int.class, int.class, int.class, int.class, int.class);
                mFill = m;
            }
            m.invoke(g, x0, y0, x1, y1, color);
        } catch (Throwable t) {
            // 静默：单条线画不出来不该毁掉整屏
            errorCount++;
        }
    }

    private static void text(Object g, Object font, String s, int x, int y, int color) {
        if (s == null || s.isEmpty()) {
            return;
        }
        try {
            Method m = mText;
            if (m == null) {
                m = Reflect.gameClass(GRAPHICS)
                        .getMethod("text", Reflect.gameClass(FONT), String.class,
                                int.class, int.class, int.class);
                mText = m;
            }
            m.invoke(g, font, s, x, y, color);
        } catch (Throwable t) {
            errorCount++;
        }
    }

    private static int fontWidth(Object font, String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        // 宽度缓存 —— 渲染热路径上最贵的单次调用。
        //
        // Font.width 走的是字形表 + 反射调用，而列表每帧对每个可见行
        // 调用 2~3 次（标题、副标题、省略号测量）。20 行 × 60fps 就是
        // 每秒数千次。字符串内容在两次快照之间是不变的，所以这里
        // 命中率极高。
        //
        // 容量上限 512 并在超限时整体清空：这是一个纯缓存，清空的代价
        // 只是下一帧慢一点点，而无限增长会变成真正的内存泄漏。
        // 只在渲染线程访问，因此不需要同步 —— 加了锁反而让缓存比
        // 它优化的对象更贵。
        Integer hit = widthCache.get(s);
        if (hit != null) {
            return hit;
        }
        int width;
        try {
            Method m = mFontWidth;
            if (m == null) {
                m = Reflect.gameClass(FONT).getMethod("width", String.class);
                mFontWidth = m;
            }
            width = (int) m.invoke(font, s);
        } catch (Throwable t) {
            // 退化为等宽估算 —— 布局会略偏，但不至于不可用。
            // 刻意不写入缓存：失败值和真实值混在一起会污染后续渲染。
            return s.length() * 6;
        }
        if (widthCache.size() >= 512) {
            widthCache.clear();
        }
        widthCache.put(s, width);
        return width;
    }

    /**
     * 文本宽度缓存。见 {@link #fontWidth} 的注释。
     *
     * <p>用 {@link java.util.HashMap} 而不是 ConcurrentHashMap：唯一的
     * 访问者是客户端渲染线程，而 CHM 的每次 get 都要处理 volatile 语义，
     * 在这里是纯开销。
     */
    private static final java.util.Map<String, Integer> widthCache =
            new java.util.HashMap<>(256);

    private static void scissor(Object g, int x0, int y0, int x1, int y1) {
        try {
            Method m = mScissor;
            if (m == null) {
                m = Reflect.gameClass(GRAPHICS)
                        .getMethod("enableScissor", int.class, int.class, int.class, int.class);
                mScissor = m;
            }
            m.invoke(g, x0, y0, x1, y1);
        } catch (Throwable t) {
            errorCount++;
        }
    }

    private static void unscissor(Object g) {
        try {
            Method m = mUnscissor;
            if (m == null) {
                m = Reflect.gameClass(GRAPHICS).getMethod("disableScissor");
                mUnscissor = m;
            }
            m.invoke(g);
        } catch (Throwable t) {
            errorCount++;
        }
    }

    private static Object fontOf(Object screen) {
        try {
            Field f = fFont;
            if (f == null) {
                f = Reflect.gameClass(SCREEN).getDeclaredField("font");
                try {
                    f.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // 模块系统拒绝时仍可能可用（游戏类加载器未模块化）
                }
                fFont = f;
            }
            return f.get(screen);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object minecraftInstance() {
        try {
            ClassLoader game = Reflect.gameClassLoader();
            if (game == null) {
                return null;
            }
            return Reflect.gameClass(MC).getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object guiOf(Object mc) {
        if (mc == null) {
            return null;
        }
        try {
            return Reflect.instanceField(mc, MC, "gui");
        } catch (Throwable t) {
            return null;
        }
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

    // ── 文本工具 ────────────────────────────────────────────────────────────

    /** 按像素宽度截断并追加省略号。 */
    static String ellipsize(Object font, String s, int maxWidth) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0) {
            return "";
        }
        if (fontWidth(font, s) <= maxWidth) {
            return s;
        }

        // 截断结果按「文本 + 预算宽度」缓存。
        //
        // 二分查找每次都会 substring 出 5~6 个中间串，而行文本在两次
        // 快照之间不变 —— 不缓存的话，20 行 × 60fps 每秒会产生近万个
        // 短命字符串，正好落在渲染线程上。
        String key = maxWidth + "\u0000" + s;
        String cached = ellipsisCache.get(key);
        if (cached != null) {
            return cached;
        }

        String ellipsis = "...";
        int ellipsisW = fontWidth(font, ellipsis);
        if (ellipsisW >= maxWidth) {
            return ellipsis;
        }
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (fontWidth(font, s.substring(0, mid)) + ellipsisW <= maxWidth) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        String result = s.substring(0, lo) + ellipsis;
        ellipsisCache.put(key, result);
        return result;
    }

    /** 截断缓存上限 —— 纯缓存，超限淘汰最旧条目。 */
    private static final int ELLIPSIS_CACHE_LIMIT = 256;
    private static final Map<String, String> ellipsisCache =
            new LinkedHashMap<>(ELLIPSIS_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > ELLIPSIS_CACHE_LIMIT;
                }
            };

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    // ── 宿主类定义 ──────────────────────────────────────────────────────────

    private static volatile Class<?> hostClass;

    private static Class<?> hostClass() throws Exception {
        Class<?> cached = hostClass;
        if (cached != null) {
            return cached;
        }
        synchronized (ModListScreenDispatch.class) {
            if (hostClass == null) {
                byte[] bytes = MiliModListScreenGenerator.generate();
                ClassLoader game = Reflect.gameClassLoader();
                // 通过 ClassLoader 子类定义，而不是反射调用
                // ClassLoader.defineClass —— 后者在 JDK 9+ 的模块系统下
                // 必然抛 InaccessibleObjectException。见 GameHostDefiner。
                hostClass = GameHostDefiner.define(game,
                        MiliModListScreenGenerator.HOST_BINARY, bytes);
                LOG.fine("[Mili] MiliModListScreen 已定义到游戏类加载器");
            }
            return hostClass;
        }
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    public static long errorCount() {
        return errorCount;
    }

    public static long renderCount() {
        return renderCount;
    }

    public static int visibleCount() {
        return visible.length;
    }

    public static String diagnostics() {
        return "ModListScreenDispatch[renders=" + renderCount
                + ", errors=" + errorCount
                + ", mods=" + snapshot.mods().size()
                + ", visible=" + visible.length + "]";
    }
}
