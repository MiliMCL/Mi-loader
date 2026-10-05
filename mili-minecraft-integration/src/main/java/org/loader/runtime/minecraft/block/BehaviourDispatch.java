package org.loader.runtime.minecraft.block;

import org.loader.api.world.BlockEntityView;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.MiliBlock;
import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.BootstrapGate;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
     * 行为派发中枢 —— 连接 Mod 实现与生成的方块类。
 *
 * <p><b>类加载器约束</b>：生成的方块类必须 {@code extends Block}，因此活在游戏
 * 的 ClassLoader 里；而 {@link MiliBlock} 实现活在 Mod 的 ClassLoader 里。两侧
 * 无法直接引用 —— 生成的字节码不认识 Mod 的任何类型。
 *
 * <p>解法是让生成代码只依赖 JDK 类型：它持有一个 {@code int} 句柄，调用本类
 * 的静态方法，并把游戏侧对象全部以 {@code Object} 传入。类型转换在这里完成。
 *
 * <p>这样游戏侧不需要认识 Mod 类，Mod 侧不需要认识游戏类，双方只共享
 * {@code int} 与 {@code Object}。
 *
 * <h2>「共享 JDK 类型」不只是方法签名</h2>
 * <p>生成类的 {@code behaviour} <b>字段类型也是 int</b>。这一点容易被忽略：
 * {@code Class.getDeclaredField} 会解析字段类型，且用<b>声明类</b>的
 * ClassLoader 去加载该类型。若字段声明成 {@code BehaviourDispatch$Handle}
 * （本类可见，但属于平台），游戏 CL 就必须能看到平台类才能读出这个字段 ——
 * 在测试里（游戏 CL 父加载器为 {@code null}）会直接抛
 * {@code NoClassDefFoundError: BehaviourDispatch$Handle}，
 * 而且报错位置在 {@code getDeclaredField} 这种完全看不出问题的地方。
 * int 的描述符 {@code I} 不引用任何类，生成类的链接因而完全不依赖平台可见性。
 *
 * <p>本类若要访问该字段，一律通过 {@link #behaviourField}，不要用
 * {@code getDeclaredField} 的返回类型做推断。
 */
public final class BehaviourDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/Behaviour");

    private static final Map<Integer, MiliBlock> BEHAVIOURS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private BehaviourDispatch() {
    }

    /**
     * 与 ClassLoader 无关的行为句柄。
     * <p>刻意只用 int：这是生成代码能安全持有的唯一跨 ClassLoader 类型。
     */
    public static final class Handle {
        private final int id;

        Handle(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        @Override
        public String toString() {
            return "MiliBehaviour#" + id;
        }
    }

    /**
     * 登记一个行为实现。
     *
     * @param behaviour Mod 提供的实现
     * @return 供生成代码使用的句柄
     */
    public static Handle register(MiliBlock behaviour, String debugName) {
        if (behaviour == null) {
            throw new IllegalArgumentException("behaviour must not be null");
        }
        int id = NEXT_ID.getAndIncrement();
        BEHAVIOURS.put(id, behaviour);
        LOG.fine(() -> "registered behaviour " + debugName + " -> #" + id);
        return new Handle(id);
    }

    /** 按句柄取回实现；已注销或未注册时返回 null。 */
    public static MiliBlock resolve(Handle handle) {
        return handle == null ? null : BEHAVIOURS.get(handle.id());
    }

    /**
     * 注销行为（Mod 卸载时调用）。
     * <p>生成的方块类可能仍会收到 tick —— 此时 resolve 返回 null，回调被跳过，
     * 而不是抛异常。这让 Mod 卸载后游戏仍能安全运行。
     */
    public static boolean unregister(Handle handle) {
        return handle != null && BEHAVIOURS.remove(handle.id()) != null;
    }

    /** 当前登记的行为数。诊断用。 */
    public static int count() {
        return BEHAVIOURS.size();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 以下静态方法由生成的字节码直接调用。
    // 签名中的参数类型必须是 JDK 类型 —— 生成代码只认识这些。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 把真实句柄写进生成实例的 behaviour 字段。
     * <p>由平台在实例化后调用 —— 生成类的构造器只能写常量。
     */
    public static void bind(Object generatedBlock, Handle handle) {
        Field field = behaviourField(generatedBlock);
        try {
            field.setInt(generatedBlock, handle == null ? 0 : handle.id());
        } catch (IllegalAccessException e) {
            throw new BridgeMismatchException(
                    "Cannot write the behaviour id into "
                            + generatedBlock.getClass().getName(), e);
        }
    }

    /**
     * 读出生成实例上的 behaviour id；未绑定返回 0。
     *
     * <p>刻意返回 {@code int} 而非 {@link Handle}：本方法运行在游戏侧，
     * 每 tick 都会被调用，不该为每个方块分配一个包装对象。
     */
    private static int behaviourIdOf(Object block) {
        if (block == null) {
            return 0;
        }
        try {
            return behaviourField(block).getInt(block);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Mod 已卸载、或生成类来自旧版本绑定层（字段还是 Handle 类型）。
            // 两种情况都表现为"这个方块没有行为"，安全跳过。
            return 0;
        }
    }

    /**
     * 取生成类的 {@code behaviour} 字段。
     *
     * <p>字段类型是 {@code int} —— 描述符 {@code I} 不引用任何类，
     * 因此这里解析字段不会触发任何跨 ClassLoader 的类加载。
     * 若生成器改成引用类型（本不该发生），这里会成为第一个失败点，
     * 而不是让整条注册链路在别处炸出 {@code NoClassDefFoundError}。
     */
    private static Field behaviourField(Object block) {
        try {
            Field f = block.getClass().getDeclaredField("behaviour");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Generated block class has no 'behaviour' field: "
                            + block.getClass().getName()
                            + "\n  GeneratedBlockFactory must declare an int field named"
                            + " 'behaviour' — a reference type here would make the game"
                            + " classloader resolve a platform class.", e);
        }
    }

    /**
     * 执行回调，吞掉一切异常。
     *
     * <p>Mod 的异常绝不能逃逸进 Minecraft 的 tick 循环 —— 那会导致世界状态
     * 不一致甚至崩溃。这里统一捕获并记录，让单个 Mod 的 bug 不会拖垮游戏。
     */
    private static boolean invoke(Object block, String callback,
                                  java.util.function.Consumer<MiliBlock> action) {
        int id = behaviourIdOf(block);
        if (id <= 0) {
            return false;
        }
        MiliBlock behaviour = BEHAVIOURS.get(id);
        if (behaviour == null) {
            return false;
        }
        try {
            action.accept(behaviour);
            return true;
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Mod behaviour #" + id + " threw in " + callback
                    + "; the game loop was protected.", t);
            return false;
        }
    }

    // ── 转换辅助：游戏对象 → ABI 值对象 ────────────────────────────────────

    private static org.loader.api.world.BlockPos toPos(Object mcPos) {
        if (mcPos == null) {
            return null;
        }
        return org.loader.runtime.minecraft.world.ReflectiveWorldView.toApiPos(mcPos);
    }

    private static java.util.Random toRandom(Object mcRandom) {
        if (mcRandom == null) {
            return null;
        }
        // RandomSource 与 java.util.Random 接口兼容，直接包装即可
        return (java.util.Random) mcRandom;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 生成代码的调用目标
    // ══════════════════════════════════════════════════════════════════════

    /**
     * {@code protected void tick(BlockState, ServerLevel, BlockPos, RandomSource)}。
     * <p>只有服务端会调这个方法 —— 26.2 把客户端/服务端 tick 分开了。
     */
    public static void tick(Object self, Object state, Object level, Object pos, Object random) {
        WorldView world = liveWorld(level);
        invoke(self, "tick", b -> b.onTick(world, toPos(pos), blockEntityView(world, pos)));
    }

    /** {@code protected void randomTick(BlockState, ServerLevel, BlockPos, RandomSource)} */
    public static void randomTick(Object self, Object state, Object level, Object pos, Object random) {
        WorldView world = liveWorld(level);
        invoke(self, "randomTick", b -> b.onRandomTick(world, toPos(pos), toRandom(random)));
    }

    /**
     * {@code protected void neighborChanged(BlockState, Level, BlockPos, Block, Orientation, boolean)}。
     * <p>26.2 比旧版本多了 {@code Orientation} 与 {@code boolean} 两个参数。
     *
     * <p>末位参数声明为 {@code Object} 而非 {@code boolean}：生成代码会把所有
     * 参数装箱后传入，若这里用原语，JVM 找不到匹配签名。
     */
    public static void neighborChanged(Object self, Object state, Object level,
                                       Object pos, Object fromBlock,
                                       Object orientation, Object movedByPiston) {
        WorldView world = liveWorld(level);
        BlockHandle from = fromHandle(world, fromBlock);
        invoke(self, "neighborChanged",
                b -> b.onNeighborChanged(world, toPos(pos), from));
    }

    /**
     * {@code protected void onPlace(BlockState, Level, BlockPos, BlockState, boolean)}。
     * <p>末位参数为 {@code Object}，理由同 {@link #neighborChanged}。
     */
    public static void onPlace(Object self, Object state, Object level,
                               Object pos, Object oldState, Object movedByPiston) {
        WorldView world = liveWorld(level);
        invoke(self, "onPlace", b -> b.onPlaced(world, toPos(pos)));
    }

    /**
     * {@code protected float getDestroyProgress(BlockState, Player, BlockGetter, BlockPos)}。
     * <p>返回 0 表示不特殊处理，由游戏用默认硬度计算。
     *
     * <p>第三个参数在游戏里是 {@code BlockGetter}（只读世界），契约的
     * {@code WorldView} 写接口多出几个方法，但 Mod 在「破坏进度」这个只读
     * 场景下不会用到写操作 —— 因此直接包成 WorldView 是安全的。
     */
    public static float destroyProgress(Object self, Object state, Object player,
                                        Object blockGetter, Object pos) {
        WorldView world = worldView(blockGetter);
        return (float) invokeFloat(self, "destroyProgress", b -> {
            if (b instanceof org.loader.api.world.MiliBlock mb) {
                return mb.destroyProgress(world, toPos(pos));
            }
            return 0.0f;
        });
    }

    private static float invokeFloat(Object block, String callback,
                                      java.util.function.Function<MiliBlock, Float> action) {
        int id = behaviourIdOf(block);
        if (id <= 0) {
            return 0.0f;
        }
        MiliBlock behaviour = BEHAVIOURS.get(id);
        if (behaviour == null) {
            return 0.0f;
        }
        try {
            return action.apply(behaviour);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Mod behaviour #" + id + " threw in " + callback, t);
            return 0.0f;
        }
    }

    /** {@code protected void attack(BlockState, Level, BlockPos, Player)} */
    public static void attack(Object self, Object state, Object level,
                              Object pos, Object player) {
        WorldView world = liveWorld(level);
        invoke(self, "attack", b -> b.onAttack(world, toPos(pos), player));
    }

    /**
     * {@code protected InteractionResult useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)}。
     * <p>26.2 把旧的 {@code use()} 拆成了 useWithoutItem / useItemOn 两个方法。
     */
    public static Object useWithoutItem(Object self, Object state, Object level,
                                        Object pos, Object player, Object hitResult) {
        WorldView world = liveWorld(level);
        return interactionResult(invokeBoolean(self, "useWithoutItem",
                b -> b.onUse(world, toPos(pos), player)));
    }

    /**
     * 把游戏 {@code Level} 包成契约的 {@link WorldView}。
     *
     * <p><b>这里曾是 {@code null}，是平台级的实现缺口。</b>
     * {@code MiliBlock} 的每个回调第一个参数都是 WorldView，其 Javadoc 写明
     * 「这是 Mod 与游戏世界之间的唯一通道」—— 传 null 等于让所有需要读邻居、
     * 判断地面、改写自身的方块行为无法实现，且失败点在 Mod 代码深处，
     * 错误信息完全不指向平台。
     *
     * <p>仍然允许返回 null：世界未加载时游戏不该调这些回调，真发生了
     * Mod 也必须按契约判空。这里的 null 是「确实没有世界」，
     * 不再是「平台懒得实现」。
     */
    private static WorldView worldView(Object level) {
        return org.loader.runtime.minecraft.world.ReflectiveWorldView.of(level);
    }

    /**
     * 处理携带真 {@code Level} 的回调：登记当前世界后再包视图。
     *
     * <p>游戏每次方块回调都自带 Level —— 这是「世界已加载」最可靠的信号。
     * 登记进 {@link CurrentWorld} 后，无参的 {@code ModContext.world()}
     * 也就能取到同一个视图，Mod 不必为了读世界而必须先放一个方块。
     *
     * <p><b>只对真 Level 调用。</b>{@code destroyProgress} 拿到的是
     * {@code BlockGetter}（只读、维度无关），把它登记成「当前世界」会让
     * {@code ModContext.world()} 返回一个能读不能写的假世界 ——
     * 症状是 Mod 写世界时静默失败，比直接抛异常更难查。
     */
    private static WorldView liveWorld(Object level) {
        org.loader.runtime.minecraft.world.CurrentWorld.publish(level);
        return org.loader.runtime.minecraft.world.ReflectiveWorldView.of(level);
    }

    /**
     * 读该位置的方块实体视图；无方块实体返回 null。
     *
     * <p>契约把它声明为可空参数（{@code BlockEntityView} 而非 Optional），
     * 因此 Mod 自己判空 —— 平台只负责「有就给，没有就 null」。
     */
    private static BlockEntityView blockEntityView(WorldView world, Object mcPos) {
        if (world == null || mcPos == null) {
            return null;
        }
        try {
            return world.getBlockEntity(
                        org.loader.runtime.minecraft.world.ReflectiveWorldView.toApiPos(mcPos))
                .orElse(null);
        } catch (RuntimeException e) {
            // 读方块实体失败不该让整次 tick 回调丢失。
            LOG.log(Level.FINE, "Cannot read block entity for a behaviour callback", e);
            return null;
        }
    }

    /** 把游戏 {@code Block} 对象换成句柄；无法解析时返回 null。 */
    private static BlockHandle fromHandle(WorldView world, Object mcBlock) {
        if (world == null || mcBlock == null) {
            return null;
        }
        return org.loader.runtime.minecraft.world.ReflectiveWorldView.handleOfBlock(mcBlock);
    }

    private static boolean invokeBoolean(Object block, String callback,
                                         java.util.function.Predicate<MiliBlock> action) {
        int id = behaviourIdOf(block);
        if (id <= 0) {
            return false;
        }
        MiliBlock behaviour = BEHAVIOURS.get(id);
        if (behaviour == null) {
            return false;
        }
        try {
            return action.test(behaviour);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, "Mod behaviour #" + id + " threw in " + callback, t);
            return false;
        }
    }

    private static Object interactionResult(boolean consumed) {
        if (!consumed) {
            return null;
        }
        try {
            Class<?> ir = Class.forName("net.minecraft.world.InteractionResult",
                    true, org.loader.runtime.minecraft.reflect.Reflect.gameClassLoader());
            Object success = ir.getField("SUCCESS").get(null);
            if (success != null) {
                return success;
            }
        } catch (ReflectiveOperationException e) {
            LOG.log(Level.WARNING, "Cannot resolve InteractionResult.SUCCESS", e);
        }
        return null;
    }

    /**
     * 启动前置检查：生成方块前必须先 bootstrap。
     * <p>把 {@link BootstrapGate} 绑进来，是为了让这个约束在代码里显式可见 ——
     * 未来若有人绕过本工厂直接生成类，至少能在这里看到原因。
     */
    static void assertBootstrapped() {
        if (!BootstrapGate.isBootstrapped()) {
            BootstrapGate.ensureBootstrapped();
        }
    }
}
