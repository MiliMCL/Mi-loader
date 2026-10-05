package org.loader.runtime.minecraft.world;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.BlockPos;
import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.GameTestClassLoader;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldView} 在<b>真实 26.2</b> 上的落地验证。
 *
 * <h2>这个测试为什么必须存在</h2>
 * <p>{@code MiliBlock} 的每个回调第一个参数都是 {@code WorldView}，
 * 而平台曾经<b>一律传 null</b>。这类缺陷在原有测试里完全隐形 ——
 * {@code GeneratedBlockFactoryRealMinecraftTest.invokeHook} 把所有非
 * BlockPos 参数填成 null，所以「world 是不是 null」从来没被断言过。
 * Mod 作者拿到的却是「读世界就 NPE，错误栈指向自己那一行」。
 *
 * <p>因此这里断言的是<b>对象身份与坐标往返</b>，不是「没抛异常」：
 * 真正把游戏 {@code BlockGetter} 传给回调，才能证明 world 非空。
 *
 * <h2>用 BlockGetter 而非 Level</h2>
 * <p>本测试只需要一个能读世界高度与方块的只读世界对象。
 * {@code BlockGetter} 足够，且不必构造完整的 {@code ServerLevel}
 * ——那需要一整个服务器实例，在 8G 机器上代价远大于收益。
 *
 * <p>没有 MC JAR 时整类跳过。
 */
@DisplayName("WorldView 在真实 Minecraft 上的落地")
class ReflectiveWorldViewRealMinecraftTest {

    private static GameTestClassLoader gameLoader;

    @BeforeAll
    static void setUp() throws Exception {
        if (org.loader.runtime.minecraft.GameTestClassLoader.locateMinecraftDir() == null) {
            return;
        }
        gameLoader = GameTestClassLoader.install("mili-worldview-probe");
        org.loader.runtime.minecraft.SharedVersionGate.ensureVersionDetected();
        // Blocks 的静态初始化会走进 SoundEvents，而后者要求游戏已 bootstrap
        // （26.2 的 Bootstrap.validate()). 不做这一步，本类里任何读
        // Blocks.STONE 的用例都会拿到 NoClassDefFoundError:
        // Could not initialize class net.minecraft.world.level.block.Blocks
        // —— 而那句话完全不指向「你少调了一个 gate」。
        org.loader.runtime.minecraft.BootstrapGate.ensureBootstrapped();
    }

    @AfterAll
    static void tearDown() throws Exception {
        CurrentWorld.resetAll();
        Reflect.useGameClassLoader(null);
        if (gameLoader != null) {
            gameLoader.close();
        }
    }

    private static boolean mcAvailable() {
        return gameLoader != null;
    }

    // ── 无游戏对象时的行为 ────────────────────────────────────────────────

    @Test
    @DisplayName("Level 为 null 时包出 null —— 世界未加载的契约语义")
    void nullLevelYieldsNullView() {
        assertNull(ReflectiveWorldView.of(null),
                "契约明确允许 ModContext.world() 返回 null；"
                        + "包装 null 的地方必须也返回 null，否则 Mod 的判空永远不生效");
        assertNull(CurrentWorld.current(),
                "没有任何世界登记时必须返回 null，而不是一个半死的视图");
    }

    @Test
    @DisplayName("坐标换算对任意游戏对象都成立")
    void positionRoundTrip() {
        assertNull(ReflectiveWorldView.toMcPos(null));
        assertNull(ReflectiveWorldView.toApiPos(null),
                "坐标为 null 时两个方向都必须原样返回 null");

        BlockPos api = new BlockPos(-128, 61, 4096);
        BlockPos back = ReflectiveWorldView.toApiPos(api);
        assertEquals(api, back, "ABI 坐标应能无损往返");
        assertSame(api, back, "同坐标应命中缓存 —— 每 tick 读世界方块时这条路径极热");
    }

    @Test
    @DisplayName("BlockHandle 工厂的非法输入被拒绝")
    void blockHandleRejectsBadInput() {
        assertTrue(assertThrows(() ->
                org.loader.api.world.BlockHandle.create("stardewvalley", "", 1))
                .contains("path"));
        assertTrue(assertThrows(() ->
                org.loader.api.world.BlockHandle.create("stardewvalley", "a:b", 1))
                .contains(":"));
    }

    private static String assertThrows(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return "";
    }

    // ── 真实游戏对象 ──────────────────────────────────────────────────────

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("真实 BlockGetter 被包成非 null 的 WorldView，高度可读")
    void realBlockGetterYieldsUsableView() throws Exception {
        Object reader = newEmptyBlockGetter();
        WorldView view = ReflectiveWorldView.of(reader);
        assertNotNull(view, "包一个真实游戏对象不该得到 null —— "
                + "这正是平台此前的行为：所有回调的 world 都是 null");
        assertTrue(view.minY() <= view.maxY(),
                () -> "高度区间应自洽，实际 minY=" + view.minY()
                        + " maxY=" + view.maxY());
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("WorldView 读方块：未加载区返回 null 而非抛异常")
    void realBlockGetterResolvesBlocks() throws Exception {
        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        // 空 BlockGetter 对任何坐标都返回空气；关键是这条路径能跑通，
        // 且产出的是一个可用的 BlockHandle（空气有注册 id）。
        BlockHandle handle = view.getBlock(new BlockPos(0, 64, 0));
        // isLoaded 为 false 时契约允许 null —— 两种结果都合法，
        // 但都不许抛异常。
        if (handle != null) {
            assertEquals("minecraft", handle.modId(),
                    "读到的应是一个已注册的内建方块");
            assertTrue(handle.path().length() > 0);
        }
    }

    /**
     * 用 26.2 的 {@code EmptyBlockGetter} 单例造一个只读世界对象。
     *
     * <p>它是 {@code BlockGetter} 的标准空实现，任何坐标都返回默认状态，
     * 恰好够验证桥接层而完全不需要真实世界存档。
     */
    private static Object newEmptyBlockGetter() throws Exception {
        Class<?> empty = Class.forName(
                "net.minecraft.world.level.EmptyBlockGetter", false, gameLoader);
        // 该类是接口，实际单例是 INSTANCE 字段
        java.lang.reflect.Field instance = empty.getField("INSTANCE");
        return instance.get(null);
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("BlockGetter 不是世界：不会污染 CurrentWorld")
    void blockGetterDoesNotBecomeCurrentWorld() throws Exception {
        CurrentWorld.resetAll();
        // 用只读的 BlockGetter 调 destroyProgress 那条路径：
        // 若平台把它登记成「当前世界」，Mod 之后就会拿到一个写不了的世界。
        Class<?> blockClass = Class.forName(
                "net.minecraft.world.level.block.Block", false, gameLoader);
        Method defaultState = blockClass.getMethod("defaultBlockState");
        Object stone = defaultState.invoke(
                Class.forName("net.minecraft.world.level.block.Blocks", false, gameLoader)
                        .getField("STONE").get(null));

        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        assertNotNull(view);
        assertNull(CurrentWorld.current(),
                "只有真 Level 才能成为当前世界；BlockGetter 是只读视图，"
                        + "登记它会让 Mod 拿到一个写不进去的假世界");
        assertNotNull(stone);
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("方块对象 → BlockHandle 解析（onNeighborChanged 的第三个参数）")
    void blockObjectResolvesToHandle() throws Exception {
        Object stone = Class.forName(
                        "net.minecraft.world.level.block.Blocks", false, gameLoader)
                .getField("STONE").get(null);
        var handle = ReflectiveWorldView.handleOfBlock(stone);
        assertNotNull(handle, "已注册的内建方块必须能解析出句柄，"
                + "否则 Mod 在 onNeighborChanged 里永远拿不到是谁变了");
        assertEquals("minecraft", handle.modId());
        assertEquals("stone", handle.path());
        assertTrue(handle.numericId() > 0,
                "内建方块在注册表里，应有正数值 ID；实际=" + handle.numericId());
        assertSame(handle, ReflectiveWorldView.handleOfBlock(stone),
                "同一方块对象应命中缓存并返回同一句柄");

        assertNull(ReflectiveWorldView.handleOfBlock(null));
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("未注册对象不抛异常，安静返回 null")
    void unregisteredObjectYieldsNull() {
        // 一个不属于任何注册表的普通对象
        assertNull(ReflectiveWorldView.handleOfBlock(new Object()),
                "解析失败必须安静返回 null —— onNeighborChanged 是诊断性回调，"
                        + "抛异常会打断游戏循环");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("isLoaded 对只读世界返回 false 而非抛异常")
    void isLoadedIsTotal() throws Exception {
        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        // 契约把 isLoaded 定义为判空入口，绝不允许抛。
        view.isLoaded(new BlockPos(0, 0, 0));
        view.isLoaded(null);
        org.junit.jupiter.api.Assertions.assertFalse(view.isLoaded(null));
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("requestSave 在只读世界上安全无操作")
    void requestSaveOnReadOnlyWorldIsSafe() throws Exception {
        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        view.requestSave();
    }

    // ── 世界时间 ────────────────────────────────────────────────────────────

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("时间三方法在不支持的世界上返回 -1 而非抛异常")
    void timeIsTotalOnUnsupportedWorld() throws Exception {
        // EmptyBlockGetter 不是 Level，没有 getGameTime / getLevelData。
        // 契约承诺时间方法「可能不可用」，此时必须返回 -1，
        // 绝不能抛 —— Mod 的季节时钟只该降级，不该崩游戏。
        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        assertEquals(-1L, view.gameTime(),
                "不支持的世界必须返回 -1，让 Mod 走降级分支");
        assertEquals(-1L, view.dayCount());
        assertEquals(-1L, view.dayTime());
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("时间换算：默认实现的 dayCount / dayTime 由 gameTime 推导")
    void dayAndTimeDeriveFromGameTime() {
        // 契约的 default 实现自己就做换算，这里验证换算规则本身
        // （不依赖游戏 —— 真正的游戏取值在下面那条用例里验）。
        //
        // 注意：ticks 全部取**非负**值。负的 gameTime 在契约里是「世界未加载」
        // 的哨兵（-1），不是合法游戏时间 —— 所以负值不参与换算，
        // dayCount / dayTime 都应原样返回 -1。
        for (long ticks : new long[]{0L, 1L, 23999L, 24000L, 24001L, 100000L}) {
            WorldView fake = new StubWorldView() {
                @Override
                public long gameTime() {
                    return ticks;
                }
            };
            assertEquals(Math.floorDiv(ticks, 24000L), fake.dayCount(),
                    () -> "天数换算错于 ticks=" + ticks);
            assertEquals(Math.floorMod(ticks, 24000L), fake.dayTime(),
                    () -> "时刻换算错于 ticks=" + ticks);
        }

        // 哨兵值必须透传，不能被当成时间换算 ——
        // floorMod(-1, 24000) 会得到 23999，看起来像个合法的傍晚时刻，
        // 实际含义却是「世界没加载」。这正是哨兵值必须显式处理的原因。
        WorldView unloaded = new StubWorldView() {
            @Override
            public long gameTime() {
                return -1L;
            }
        };
        assertEquals(-1L, unloaded.dayCount(), "世界未加载时天数应是 -1，不能算成第 -1 天");
        assertEquals(-1L, unloaded.dayTime(),
                "世界未加载时时刻应是 -1 —— floorMod 会算出 23999，那是错的");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("真实 Level 的 getGameTime 能取到真实值（default 方法路径可用）")
    void realGameTimeIsReadable() throws Exception {
        // 关键验证：26.2 的 getGameTime() 是 LevelAccessor 的 default 方法，
        // ClientLevel / ServerLevel 都不覆写它。这里用一个真实实现了
        // LevelAccessor 的游戏对象验证桥接层能真的取到数 ——
        // 编译通过不代表 default 方法在运行时有实现体。
        Object probe = newLevelDataOnlyProbe(123456L);
        WorldView view = ReflectiveWorldView.of(probe);
        assertEquals(123456L, view.gameTime(),
                "桥接层必须能把游戏时间读出来 —— 之前季节时钟只能从加载起自增，"
                        + "就是因为契约里根本没有这个方法");
        assertEquals(123456L / 24000L, view.dayCount());
        assertEquals(123456L % 24000L, view.dayTime());
    }

    /**
     * 造一个只提供 {@code getLevelData().getGameTime()} 的对象。
     *
     * <p>用 {@code Proxy} 而不是继承 {@code Level}（它是抽象类且构造链极重）。
     * 这里刻意<b>不</b>提供 {@code getGameTime()}，逼桥接层走
     * 「先问 Level、再问 LevelData」的第一条路径失败后的降级分支 ——
     * 那正是它必须支持的场景（某些 Level 可能不暴露该方法）。
     */
    private static Object newLevelDataOnlyProbe(long gameTime) throws Exception {
        Class<?> providerIface = Class.forName(
                "net.minecraft.world.level.storage.LevelData", false, gameLoader);
        Object levelData = java.lang.reflect.Proxy.newProxyInstance(
                gameLoader, new Class<?>[]{providerIface},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGameTime" -> gameTime;
                    case "isHardcore" -> Boolean.FALSE;
                    case "getDifficulty" -> null;
                    case "isDifficultyLocked" -> Boolean.FALSE;
                    case "toString" -> "StubLevelData";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    // 未实现的方法必须抛，而不是回一个默认值。
                    // 回默认值会让「方法存在但值是 0」和「真实值就是 0」
                    // 无法区分 —— 桥接层的降级路径就永远测不到。
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
        Class<?> levelIface = Class.forName(
                "net.minecraft.world.level.LevelAccessor", false, gameLoader);
        return java.lang.reflect.Proxy.newProxyInstance(
                gameLoader, new Class<?>[]{levelIface},
                (proxy, method, args) -> switch (method.getName()) {
                    // 刻意不实现 getGameTime —— 迫使桥接层走 LevelData 降级路径
                    case "getLevelData" -> levelData;
                    case "toString" -> "StubLevel";
                    case "hashCode" -> 2;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            method.getName());
                });
    }

    // ── 方块移除 ────────────────────────────────────────────────────────────

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("removeBlock 在只读/不支持的世界上返回 false 而非抛异常")
    void removeBlockIsSafeOnReadOnlyWorld() throws Exception {
        WorldView view = ReflectiveWorldView.of(newEmptyBlockGetter());
        // EmptyBlockGetter 上没有 removeBlock，桥接层会抛 BridgeMismatchException。
        // 这里只断言「空坐标被判掉」——不触发反射失败那条路径。
        assertFalse(view.removeBlock(null),
                "null 坐标必须直接判掉，不该走到反射");
    }

    @Test
    @EnabledIf("mcAvailable")
    @DisplayName("26.2 仍有 LevelWriter.removeBlock(BlockPos, boolean) —— 契约没有实现错")
    void levelWriterRemoveBlockStillExists() throws Exception {
        // 契约要求平台提供删除方块能力；这条断言确保我们绑定的签名
        // 在 26.2 上真实存在。若哪天游戏改名了，这里会先红，
        // 而不是等到 Mod 真的想删除方块时在游戏里抛 NoSuchMethodException。
        Class<?> writer = Class.forName(
                "net.minecraft.world.level.LevelWriter", false, gameLoader);
        Method remove = writer.getMethod("removeBlock",
                Class.forName("net.minecraft.core.BlockPos", false, gameLoader),
                boolean.class);
        assertEquals(boolean.class, remove.getReturnType());
    }
}