package org.loader.runtime.minecraft.world;

import org.loader.api.world.BlockEntityView;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.BlockPos;
import org.loader.api.world.Direction;
import org.loader.api.world.WorldView;
import org.loader.runtime.minecraft.RegistrationPhase;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link WorldView} 的真实实现 —— 把游戏 {@code Level} 适配到 Mod 契约。
 *
 * <h2>为什么必须有这个类</h2>
 * <p>{@code MiliBlock} 的<b>每一个</b>回调签名里都带 {@code WorldView}，
 * 而它的 Javadoc 写明「这是 Mod 与游戏世界之间的<b>唯一</b>通道」。
 * 平台曾经一律传 {@code null} —— 于是 Mod 里任何一句
 * {@code world.getBlock(pos)} 都会 NPE，而<b>错误信息完全指向 Mod 自己</b>，
 * 不指向平台。作物方块这类需要读邻居、判断地面、把自己换掉的行为，
 * 没有 WorldView 根本无法实现。
 *
 * <h2>为什么用反射而不是生成类</h2>
 * <p>与 {@code GeneratedBlockFactory} 不同，方块<b>实例</b>必须是
 * {@code Block} 的子类才能进游戏注册表；而 WorldView 只是平台内部传给 Mod 的
 * 视图对象，Mod 拿到的是接口引用，游戏侧<b>从不需要看见它</b>。
 * 因此纯反射足够，不存在「必须 extends 某个类」的约束，也就不必生成字节码。
 *
 * <h2>坐标换算缓存</h2>
 * <p>{@link BlockPos} → 游戏 {@code BlockPos} 的换算每 tick 都在发生
 * （作物每 tick 读一次下方方块就是两次）。转换结果用一层小缓存兜住，
 * 避免每个方块每 tick 都反射构造一个新对象。
 *
 * <h2>失败策略</h2>
 * <p>任何反射点解析失败都抛 {@link BridgeMismatchException} 而不是返回 null：
 * 前者对 Mod 作者有意义（「平台该升级到哪个版本」），
 * 后者只会在 Mod 代码深处炸出 NPE。
 */
public final class ReflectiveWorldView implements WorldView {

    private static final String LEVEL = "net.minecraft.world.level.Level";
    private static final String BLOCK_POS = "net.minecraft.core.BlockPos";
    private static final String BLOCK_STATE = "net.minecraft.world.level.block.state.BlockState";
    private static final String BLOCK = "net.minecraft.world.level.block.Block";
    private static final String BLOCK_ENTITY = "net.minecraft.world.level.block.entity.BlockEntity";
    private static final String RESOURCE_KEY = "net.minecraft.resources.ResourceKey";
    private static final String IDENTIFIER = "net.minecraft.resources.Identifier";
    private static final String BUILTIN_REGISTRIES = "net.minecraft.core.registries.BuiltInRegistries";
    private static final String REGISTRY = "net.minecraft.core.Registry";
    private static final String SERVER_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("Mili/WorldView");
    private static final String PROGRESS_LISTENER = "net.minecraft.util.ProgressListener";

    /**
     * 游戏 {@code BlockPos} → ABI {@link BlockPos} 的反向缓存。
     * <p>用游戏对象的 {@code hashCode}（等价于坐标打包）做键，
     * 所以命中率高到值得做，失效也不会有任何正确性影响。
     */
    private static final ConcurrentMap<Integer, BlockPos> POS_CACHE = new ConcurrentHashMap<>();

    /** ABI {@link BlockPos} → 游戏 {@code BlockPos}。 */
    private static final ConcurrentMap<BlockPos, Object> MC_POS_CACHE = new ConcurrentHashMap<>();

    /**
     * 游戏 {@code Block} → {@link BlockHandle} 的缓存。
     *
     * <p>同一批方块每 tick 都会被读到（作物读下方方块 → 得到 farmLand 句柄），
     * 而句柄按值语义稳定，因此缓存是安全且收益明显的。
     */
    private static final ConcurrentMap<Object, BlockHandle> HANDLE_CACHE =
            new ConcurrentHashMap<>();

    /** 持有的游戏世界对象（{@code Level}，服务端为 {@code ServerLevel}）。 */
    private final Object level;

    private ReflectiveWorldView(Object level) {
        this.level = level;
    }

    /**
     * 包装一个游戏世界对象。
     *
     * @param level 游戏 {@code Level} 实例；null 视为世界未加载
     * @return 视图；level 为 null 时返回 {@code null}
     */
    public static WorldView of(Object level) {
        return level == null ? null : new ReflectiveWorldView(level);
    }

    /** 供诊断与平台内部使用：取回底层游戏对象。 */
    public Object levelObject() {
        return level;
    }

    // ── 坐标换算 ────────────────────────────────────────────────────────────

    /** ABI 坐标 → 游戏坐标。 */
    public static Object toMcPos(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        Object cached = MC_POS_CACHE.get(pos);
        if (cached != null) {
            return cached;
        }
        try {
            Object mc = Reflect.gameClass(BLOCK_POS)
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(pos.x(), pos.y(), pos.z());
            // 有界：坐标种类有限，且只在 Mod 真实读写世界时才增长。
            if (MC_POS_CACHE.size() < 8192) {
                MC_POS_CACHE.put(pos, mc);
            }
            return mc;
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot construct a Minecraft BlockPos from (" + pos.x() + ", "
                            + pos.y() + ", " + pos.z() + ")"
                            + "\n  Expected constructor: BlockPos(int, int, int)"
                            + "\n  This is a binding-layer bug, not a mod bug.", e);
        }
    }

    /** 游戏坐标 → ABI 坐标；null 进 null 出。 */
    public static BlockPos toApiPos(Object mcPos) {
        if (mcPos == null) {
            return null;
        }
        // 已经是 ABI 类型就原样返回。这一条不是"多余的安全检查"：
        // 测试与平台内部都会把已经转好的 BlockPos 再传回来，
        // 而 ABI 的 BlockPos 只有 x()/y()/z()，没有 getX() ——
        // 少这一行会得到一句完全误导的
        // "Cannot read coordinates from a Minecraft position object:
        //  org.loader.api.world.BlockPos"。
        if (mcPos instanceof BlockPos already) {
            return already;
        }
        try {
            int x = (Integer) mcPos.getClass().getMethod("getX").invoke(mcPos);
            int y = (Integer) mcPos.getClass().getMethod("getY").invoke(mcPos);
            int z = (Integer) mcPos.getClass().getMethod("getZ").invoke(mcPos);
            return cachedPos(x, y, z);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot read coordinates from a Minecraft position object: "
                            + mcPos.getClass().getName()
                            + "\n  Expected methods: getX() / getY() / getZ()", e);
        }
    }

    private static BlockPos cachedPos(int x, int y, int z) {
        int key = (x * 31 + y) * 31 + z;
        BlockPos hit = POS_CACHE.get(key);
        if (hit != null && hit.x() == x && hit.y() == y && hit.z() == z) {
            return hit;
        }
        BlockPos made = new BlockPos(x, y, z);
        if (POS_CACHE.size() < 8192) {
            POS_CACHE.put(key, made);
        }
        return made;
    }

    // ── WorldView ───────────────────────────────────────────────────────────

    @Override
    public BlockHandle getBlock(BlockPos pos) {
        if (pos == null || !isLoaded(pos)) {
            return null;
        }
        try {
            Object state = level.getClass()
                    .getMethod("getBlockState", Reflect.gameClass(BLOCK_POS))
                    .invoke(level, toMcPos(pos));
            return handleOf(state);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "WorldView.getBlock failed: Level.getBlockState(BlockPos) not found."
                            + "\n  Real cause: " + e, e);
        }
    }

    /**
     * 把游戏 {@code BlockState} 转成 {@link BlockHandle}。
     *
     * <p>路径：{@code BlockState.getBlock()} → {@code Registry.getKey(Block)}
     * → {@code ResourceKey.identifier()} → {@code Identifier} 的
     * {@code getNamespace()} + {@code getPath()}。
     *
     * <p>三者都在 26.2 上逐一核验过：
     * {@code BlockStateBase.getBlock()}、
     * {@code Registry.getKey(Object)}、
     * {@code ResourceKey.identifier()}。
     */
    private BlockHandle handleOf(Object blockState) throws ReflectiveOperationException {
        if (blockState == null) {
            return null;
        }
        Class<?> blockClass = Reflect.gameClass(BLOCK);
        Object block = blockClass.getMethod("getBlock").invoke(blockState);
        return handleOfBlock(block);
    }

    /**
     * 把游戏 {@code Block} 对象直接换成句柄。
     *
     * <p>{@code MiliBlock.onNeighborChanged} 的第三个参数就是这个 ——
     * 游戏传进来的是「哪个方块变了」，但契约要求的是
     * {@link BlockHandle}（Mod 侧不可变的注册标识）。这一步转换与位置无关，
     * 因此单独提供入口。
     *
     * @param mcBlock 游戏 {@code Block} 实例；null 返回 null
     * @return 句柄；无法解析（未注册 / 反射失配）返回 null
     */
    public static BlockHandle handleOfBlock(Object mcBlock) {
        if (mcBlock == null) {
            return null;
        }
        // 必须先校验类型。26.2 的方块注册表是 DefaultedRegistry，
        // 它的 getKey(任意对象) 对**不在表里的对象**返回默认方块（空气）的 key，
        // 而不是 null —— 于是任何随便一个 Object 都会被解析成
        // "minecraft:air"，Mod 会拿到一个完全编造的句柄。
        // 这类缺陷没有任何报错，只是静默给错答案。
        if (!Reflect.gameClass(BLOCK).isInstance(mcBlock)) {
            return null;
        }
        BlockHandle hit = HANDLE_CACHE.get(mcBlock);
        if (hit != null) {
            return hit;
        }
        try {
            // 先走安全路径再触碰注册表类：Mod 在 initialize() 里调世界查询
            // 同样会触发 BuiltInRegistries 的类初始化，而失败后不可重试。
            // 详见 RegistrationPhase.ensureRegistriesReadable()。
            RegistrationPhase.ensureRegistriesReadable();
            Object registry = Reflect.staticField(BUILTIN_REGISTRIES, "BLOCK");
            // 26.2 的 Registry.getKey(T) 直接返回 Identifier，
            // 不是旧版本的 ResourceKey —— 这一点逐条 javap 核验过。
            Object identifier = Reflect.gameClass(REGISTRY)
                    .getMethod("getKey", Object.class).invoke(registry, mcBlock);
            if (identifier == null) {
                return null;
            }
            String namespace = (String) identifier.getClass()
                    .getMethod("getNamespace").invoke(identifier);
            String path = (String) identifier.getClass()
                    .getMethod("getPath").invoke(identifier);
            int numericId = numericIdOf(registry, Reflect.gameClass(REGISTRY), mcBlock);
            BlockHandle handle = BlockHandle.create(namespace, path, numericId);
            if (HANDLE_CACHE.size() < 4096) {
                HANDLE_CACHE.put(mcBlock, handle);
            }
            return handle;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 这条路径服务于「邻居变了是谁变了」这种诊断性回调，
            // 失败不该打断游戏；返回 null 让 Mod 按契约判空。
            return null;
        }
    }

    private static int numericIdOf(Object registry, Class<?> registryClass, Object block) {
        try {
            Object id = registryClass.getMethod("getId", Object.class).invoke(registry, block);
            return id instanceof Number n ? n.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 数值 ID 是非稳定标识，契约允许 -1；取不到不该让整个读取失败。
            return -1;
        }
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockHandle block) {
        if (pos == null || block == null || !isLoaded(pos)) {
            return false;
        }
        try {
            Object mcPos = toMcPos(pos);
            Object state = defaultStateOf(block);
            if (state == null) {
                return false;
            }
            // flags = 3 = 通知邻居 + 触发客户端同步。
            // 契约的 setBlock 语义是「写入并让周围看到变化」，
            // 只用 flag 2 会让相邻方块的 update 形状不触发。
            Method setBlock = level.getClass().getMethod(
                    "setBlock", Reflect.gameClass(BLOCK_POS),
                    Reflect.gameClass(BLOCK_STATE), int.class);
            Object result = setBlock.invoke(level, mcPos, state, 3);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "WorldView.setBlock failed: expected"
                            + " Level.setBlock(BlockPos, BlockState, int)."
                            + "\n  Real cause: " + e, e);
        }
    }

    /** 按句柄取方块的默认状态；句柄在游戏注册表里找不到时返回 null。 */
    private Object defaultStateOf(BlockHandle block) throws ReflectiveOperationException {
        Object resolved = resolveBlock(block);
        if (resolved == null) {
            return null;
        }
        return resolved.getClass().getMethod("defaultBlockState").invoke(resolved);
    }

    /** modId:path → 游戏 {@code Block} 实例；未注册返回 null。 */
    private Object resolveBlock(BlockHandle handle) throws ReflectiveOperationException {
        RegistrationPhase.ensureRegistriesReadable();
        Object registry = Reflect.staticField(BUILTIN_REGISTRIES, "BLOCK");
        Object identifier = identifier(handle.modId(), handle.path());
        Object key = Reflect.gameClass(RESOURCE_KEY)
                .getMethod("create", Reflect.gameClass(REGISTRY), Reflect.gameClass(IDENTIFIER))
                .invoke(null, blockRegistryKey(), identifier);
        return Reflect.gameClass(REGISTRY)
                .getMethod("getValue", Reflect.gameClass(RESOURCE_KEY))
                .invoke(registry, key);
    }

    private static Object blockRegistryKey() throws ReflectiveOperationException {
        Class<?> registries = Reflect.gameClass("net.minecraft.core.registries.Registries");
        return registries.getField("BLOCK").get(null);
    }

    /** 构造 {@code Identifier}；26.2 里它已从 ResourceLocation 改名回来。 */
    static Object identifier(String namespace, String path)
            throws ReflectiveOperationException {
        return Reflect.gameClass(IDENTIFIER)
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
    }

    @Override
    public Optional<BlockEntityView> getBlockEntity(BlockPos pos) {
        if (pos == null || !isLoaded(pos)) {
            return Optional.empty();
        }
        try {
            Object be = level.getClass()
                    .getMethod("getBlockEntity", Reflect.gameClass(BLOCK_POS))
                    .invoke(level, toMcPos(pos));
            if (be == null) {
                return Optional.empty();
            }
            BlockHandle owner = getBlock(pos);
            return Optional.of(new ReflectiveBlockEntityView(be, pos, owner));
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "WorldView.getBlockEntity failed:"
                            + " Level.getBlockEntity(BlockPos) not found."
                            + "\n  Real cause: " + e, e);
        }
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        try {
            Object result = level.getClass()
                    .getMethod("isLoaded", Reflect.gameClass(BLOCK_POS))
                    .invoke(level, toMcPos(pos));
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            // isLoaded 是契约承诺的「判空」入口，绝不能抛。
            // 解析不了就保守回答 false，让 Mod 走它自己的 null 分支。
            return false;
        }
    }

    @Override
    public int minY() {
        return heightAccessor("getMinY", -64);
    }

    @Override
    public int maxY() {
        return heightAccessor("getMaxY", 320);
    }

    /**
     * 读高度相关的方法。
     *
     * <p><b>为什么必须落到具体类上反射。</b>26.2 里
     * {@code getMinY()} 是 {@code LevelHeightAccessor} 的抽象方法，
     * 但 {@code getMaxY()} 是该接口的 <b>default</b> 方法
     * （实为 {@code getMinY() + getHeight()}）。若按接口
     * {@code getMethod("getMaxY")} 去找，拿到的是接口上的 Method，
     * 对具体实现调用能work；而某些只读实现（如 {@code EmptyBlockGetter}）
     * 根本没实现它 —— 此时按接口调用会走到 default 实现，
     * 结果 {@code minY=0, height=0} 得出 {@code maxY=-1}，
     * 高度区间直接倒挂。
     *
     * <p>因此策略是：先按接口找，找不到就用
     * {@code minY + height} 自己算 —— 后者对所有实现都成立，
     * 因为 26.2 的 {@code getHeight()} 是唯一权威定义。
     */
    private int heightAccessor(String name, int fallback) {
        // maxY 没有权威实现，一律用 minY + height 推导
        if ("getMaxY".equals(name)) {
            int minY = heightAccessor("getMinY", Integer.MIN_VALUE);
            int height = heightAccessor("getHeight", Integer.MIN_VALUE);
            if (minY != Integer.MIN_VALUE && height != Integer.MIN_VALUE) {
                return minY + height;
            }
            return fallback;
        }
        try {
            Method m = Reflect.gameClass("net.minecraft.world.level.LevelHeightAccessor")
                    .getMethod(name);
            Object value = m.invoke(level);
            return value instanceof Integer i ? i : fallback;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fallback;
        }
    }

    @Override
    public void requestSave() {
        // 契约明确：「不保证立即落盘，只请求一次保存」。
        // 26.2 的 save 只在 ServerLevel 上，且需要 ProgressListener；
        // 客户端世界没有这个方法 —— 那正是契约说「不保证」的情形。
        if (!isServerLevel()) {
            return;
        }
        try {
            Method save = Reflect.gameClass(SERVER_LEVEL)
                    .getMethod("save", Reflect.gameClass(PROGRESS_LISTENER),
                            boolean.class, boolean.class);
            save.invoke(level, null, false, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 保存是尽力而为：失败只记日志，不影响游戏循环。
            LOG.log(java.util.logging.Level.WARNING,
                    "requestSave() could not reach ServerLevel.save", e);
        }
    }

    private boolean isServerLevel() {
        try {
            return Reflect.gameClass(SERVER_LEVEL).isInstance(level);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── 世界时间 ────────────────────────────────────────────────────────────

    /**
     * 读世界总 tick 数。
     *
     * <p><b>为什么逐级降级而不是直接调某个方法。</b>26.2 里
     * {@code getGameTime()} 声明在 {@code LevelAccessor} 上且是
     * {@code default} 方法，{@code ClientLevel} 与 {@code ServerLevel} 都
     * <b>没有覆写它</b>。它的默认实现（已用 {@code javap -c} 逐条核验字节码）
     * 是：
     * <pre>
     *   getLevelData().getGameTime()
     * </pre>
     * 而 {@code LevelData.getGameTime()} 是抽象方法，两端各有实现。因此
     * 三条路径都通向同一份权威数据，按代价从低到高依次尝试即可。
     *
     * <p>这条路径不经过 26.2 新增的 {@code WorldClocks} 注册表 ——
     * {@code Level.getOverworldClockTime()} 依赖 dimension type 的时钟配置，
     * 跨维度行为不一致，Mod 契约不应建立在它上面。
     *
     * @return 游戏总 tick；全部路径失败返回 -1
     */
    @Override
    public long gameTime() {
        // 路径 1：LevelAccessor 的 default 方法（两端 Level 均未覆写）
        try {
            Object value = level.getClass().getMethod("getGameTime").invoke(level);
            if (value instanceof Long l) {
                return l;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 落到路径 2
        }
        // 路径 2：直接问世界数据
        try {
            Object data = level.getClass().getMethod("getLevelData").invoke(level);
            if (data != null) {
                Object value = data.getClass().getMethod("getGameTime").invoke(data);
                if (value instanceof Long l) {
                    return l;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 两条都不通：世界未加载，或 26.2 改了时间 API
        }
        return -1L;
    }

    /**
     * 已过去的天数与日内时刻<b>刻意不覆写</b>。
     *
     * <p>契约的 {@code default} 实现已按 {@link WorldView#TICKS_PER_DAY} 从
     * {@link #gameTime()} 推导（见 WorldView#dayCount / dayTime）。
     * 这里若再写一份，两处换算规则就会各自漂移 —— 契约改了平台没改，
     * 症状是 Mod 在不同实现上算出不同的日历，且没有任何报错。
     * 只覆写唯一无法推导的原语 {@code gameTime()}，其余白拿。
     *
     * <p>顺带说明 {@code dayCount} 为何是推导而非读存档字段：26.2 把「天」
     * 从世界数据里移走了 —— {@code LevelData} 只剩 {@code getGameTime()}，
     * 且 {@code Level} 上<b>没有</b> {@code getDayTime()}（逐条 javap 核验：
     * Level / LevelReader / LevelAccessor / BlockAndLevelAccessor
     * 上 dayTime 命中数均为 0）。从 gameTime 推导还能正确响应
     * {@code /time set}，那正是 Mod 日历想要的语义。
     */

    // ── 方块移除 ────────────────────────────────────────────────────────────

    /**
     * 移除方块：换成空气并清掉它的方块实体。
     *
     * <p>用 {@code LevelWriter.removeBlock(BlockPos, boolean)} 而不是
     * {@code setBlock(pos, air)}：后者只换方块状态，<b>不清方块实体</b>，
     * 会在存档里留下挂在空气上的孤儿数据。两者语义不同。
     *
     * <p>26.2 已用 javap 核验：{@code removeBlock} 声明在
     * {@code LevelWriter} 上，签名 {@code removeBlock(BlockPos, boolean)}。
     */
    @Override
    public boolean removeBlock(BlockPos pos) {
        if (pos == null || !isLoaded(pos)) {
            return false;
        }
        try {
            Object mcPos = toMcPos(pos);
            // 第二个参数 move = false：不要把方块实体里的物品掉出来。
            // Mod 主动删除（如作物枯萎）不该凭空喷出一地物品。
            Object result = level.getClass()
                    .getMethod("removeBlock", Reflect.gameClass(BLOCK_POS), boolean.class)
                    .invoke(level, mcPos, false);
            if (Boolean.TRUE.equals(result)) {
                // 清掉平台侧为该方块实体缓存的数据槽，否则它会一直挂到
                // WeakHashMap 回收为止，且下次同位置新建的方块实体
                // 可能读到上一任的数据。
                try {
                    Object be = level.getClass()
                            .getMethod("getBlockEntity", Reflect.gameClass(BLOCK_POS))
                            .invoke(level, mcPos);
                    ReflectiveBlockEntityView.forget(be);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // 方块实体已被游戏自行移除，缓存清理失败不影响删除结果。
                }
                return true;
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "WorldView.removeBlock failed: expected"
                            + " LevelWriter.removeBlock(BlockPos, boolean)."
                            + "\n  Real cause: " + e, e);
        }
    }

    // ── 供 BehaviourDispatch 使用的额外能力 ────────────────────────────────

    /**
     * 读出该世界对应的游戏 {@code Level} 对象。
     *
     * <p>{@code BehaviourDispatch} 的回调里已经有一个 {@code level} 参数
     * （{@code Object}），但它<b>不该</b>直接透传给 Mod —— 契约禁止 Mod 触碰
     * 任何 Minecraft 类型。这里是受控出口：平台自己用它换 WorldView。
     */
    public static WorldView wrap(Object level) {
        return of(level);
    }

    /** 相邻方向的一步，供 {@link Direction} 桥接使用。 */
    public List<BlockPos> neighbours(BlockPos pos) {
        List<BlockPos> out = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            out.add(pos.relative(d));
        }
        return out;
    }

    @Override
    public String toString() {
        return "WorldView(" + level.getClass().getSimpleName() + ")";
    }
}