package org.loader.runtime.minecraft.resource;

import org.loader.runtime.minecraft.reflect.Reflect;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 在游戏真正跑起来之后，把各 Mod 的资源注入资源管理器。
 *
 * <h2>为什么不用 TickBridge</h2>
 *
 * <p>上一版把注入挂在 {@code TickBridge.onTick(...)} 上，结果<b>从未执行</b>。
 * 两个独立原因叠加：
 * <ol>
 *   <li>调用时机太早：{@code EntryPointHook} 在游戏 main 之前调度，那时
 *       {@code activeContract} 必然为 null，而 {@code onTick} 对 null
 *       <b>静默 return</b> —— 不报错、不打印；</li>
 *   <li>更根本：{@code TickBridge} 在生产路径上<b>没有任何调用方</b>。
 *       {@code ReflectiveMinecraftTickSource.beginTick()} 是唯一能推进它的入口，
 *       而它需要一层注入层挂进游戏的 tick 循环 —— 那层东西还不存在。
 *       换句话说，整个 tick 桥目前是死代码。</li>
 * </ol>
 *
 * <p>所以这里<b>不碰 tick 桥</b>，改用一个平台自己的守护线程轮询
 * {@code Minecraft.getInstance()}。这与游戏是否暴露 tick 无关，
 * 只依赖「Minecraft 实例最终会存在」这一件必然成立的事。
 *
 * <h2>为什么轮询而不是事件</h2>
 *
 * <p>{@code ClientStartingEvent} 这类事件的触发时机由游戏决定，
 * 平台无法保证它晚于客户端实例构造。若它也在实例化前触发，
 * 就会重现与上一版完全相同的静默失败。轮询没有这个不确定性。
 *
 * <h2>失败不阻断启动</h2>
 *
 * <p>资源缺失的表现是「模型没贴图」，游戏本身完全可玩。所以注入失败
 * 只记警告，不抛异常 —— 一个 Mod 的资源问题不该让整个游戏起不来。
 * 平台其他环节（如方块/物品注册）失败仍会阻断，两者严重程度不同。
 */
public final class ModResourceInjectionScheduler {

    private static final Logger LOG =
            Logger.getLogger("Mili/ModResourceInjection");

    /** 轮询间隔。不用太长：Minecraft 实例通常在游戏 main 内几秒内就绪。 */
    private static final long POLL_INTERVAL_MS = 200L;
    /** 最多等多久。超时后放弃并给出明确诊断，避免线程永久挂着。 */
    private static final long TIMEOUT_MS = 120_000L;

    private static final Map<String, Path> PENDING = new LinkedHashMap<>();
    /** Mod → 它声明的方块路径（用于创建创造栏标签）。 */
    private static final Map<String, List<String>> PENDING_BLOCKS = new LinkedHashMap<>();
    private static final AtomicBoolean started = new AtomicBoolean();

    private ModResourceInjectionScheduler() {
    }

    /**
     * 登记一个待注入的 Mod。
     *
     * @param modId   Mod ID
     * @param jarPath 该 Mod 的 JAR 路径
     * @param blockIds该 Mod 声明的方块路径（用于创造栏标签）
     */
    public static void register(String modId, Path jarPath, List<String> blockIds) {
        if (modId == null || jarPath == null) {
            return;
        }
        synchronized (PENDING) {
            PENDING.put(modId, jarPath);
            if (blockIds != null && !blockIds.isEmpty()) {
                PENDING_BLOCKS.put(modId, List.copyOf(blockIds));
            }
        }
    }

    /**
     * 启动注入流程。幂等：重复调用只生效一次。
     *
     * <p>必须在调用游戏 main <b>之前</b>调用 —— 本方法只负责起线程，
     * 真正的注入在线程里等实例就绪后才发生。
     */
    public static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        int count;
        synchronized (PENDING) {
            count = PENDING.size();
        }
        if (count == 0) {
            return;
        }

        Thread t = new Thread(ModResourceInjectionScheduler::awaitAndInject,
                "mili-mod-resource-injection");
        // 守护线程：游戏退出时即便还没注入完也不阻止 JVM 结束
        t.setDaemon(true);
        t.start();
        LOG.info("Mod resource injection armed for " + count + " mod(s)");
    }

    /** 轮询等待 Minecraft 实例就绪，然后注入。运行在平台自己的守护线程上。 */
    private static void awaitAndInject() {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            // 【就绪判据必须是「字段已赋值」，不能只看实例非 null】
            //
            // Minecraft.getInstance() 返回静态字段 instance，它在构造【一开始】
            // 就被赋值了 —— 但 resourcePackRepository 这些 final 字段要到
            // 构造中后段才赋值。上一版只等 getInstance() 非 null，
            // 结果在构造中途就进去，拿到的是「实例存在但字段仍为 null」的半成品，
            // 于是报 "Cannot reach Minecraft.resourcePackRepository"。
            //
            // 证据（用户实测）：注入发生在 13:34:00，而游戏日志里
            // "Reloading ResourceManager" 在 13:34:02 —— 早了整整 2 秒。
            if (clientFullyConstructed()) {
                Object mc = currentMinecraft();
                if (mc != null) {
                    injectAll(mc);
                    return;
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Mod resource injection interrupted before Minecraft"
                        + " became ready");
                return;
            }
        }

        LOG.warning("Minecraft client was not fully constructed within "
                + (TIMEOUT_MS / 1000) + "s — skipping mod resource injection."
                + "\n  Mods will register their blocks and items, but their models,"
                + " textures and translations will be missing."
                + "\n  Readiness is judged by resourcePackRepository being assigned;"
                + " if this keeps appearing, the game likely exited early or is"
                + " running headless (resource injection is client-only).");
    }

    /**
     * Minecraft 客户端是否已构造完成。
     *
     * <p><b>判据是 {@code gui != null}，不是 {@code resourcePackRepository != null}</b>。
     *
     * <p>原因（用户实测 13:57那次）：以 repository 为判据时，注入在 14:10:58
     * 成功拿到了 repository，于是调用 {@code reloadResourcePacks()}，结果：
     * <pre>
     *   NullPointerException: Cannot invoke "net.minecraft.client.Gui.overlay()"
     *   because "this.gui" is null
     *     at Minecraft.reloadResourcePacks(Minecraft.java:1079)
     * </pre>
     * <p>{@code reloadResourcePacks} 内部第一件事就是读 {@code this.gui.overlay()}，
     * 而 {@code gui} 在构造器第 634 行才赋值 —— 晚于 {@code resourcePackRepository}
     * （第 434 行）。所以 repository 就绪时，gui 还没建好。
     *
     * <p>{@code gui} 是"客户端能安全重载资源"这条链路上最后一个被赋值的字段，
     * 拿它当判据才能保证 {@code reloadResourcePacks()} 不踩空。
     */
    private static boolean clientFullyConstructed() {
        Object mc = currentMinecraft();
        if (mc == null) {
            return false;
        }
        try {
            Class<?> minecraftClass =
                    Reflect.gameClass("net.minecraft.client.Minecraft");
            java.lang.reflect.Field gui = null;
            for (Class<?> c = minecraftClass; c != null && gui == null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getName().equals("gui")) {
                        gui = f;
                        break;
                    }
                }
            }
            if (gui == null) {
                return false;
            }
            gui.setAccessible(true);
            return gui.get(mc) != null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 执行实际注入。 */
    private static void injectAll(Object minecraft) {
        Map<String, Path> snapshot;
        Map<String, List<String>> blocks;
        synchronized (PENDING) {
            snapshot = new LinkedHashMap<>(PENDING);
            blocks = new LinkedHashMap<>(PENDING_BLOCKS);
            PENDING.clear();
            PENDING_BLOCKS.clear();
        }
        if (snapshot.isEmpty()) {
            return;
        }

        int ok = 0;
        int failed = 0;
        for (Map.Entry<String, Path> e : snapshot.entrySet()) {
            try {
                ModResourcePacks.inject(e.getKey(), e.getValue(), minecraft);
                ok++;
            } catch (RuntimeException ex) {
                failed++;
                LOG.log(Level.WARNING, "Resource injection failed for mod "
                        + e.getKey() + " (its models/textures will be missing)", ex);
            }
        }
        LOG.info("Mod resource injection finished: " + ok + " ok, "
                + failed + " failed, " + snapshot.size() + " total");
        if (ok > 0) {
            LOG.info("Mods with resources injected: " + String.join(", ", snapshot.keySet()));
        }

        // 创造栏标签不在这里创建 —— 它必须在注册窗口内完成
        // （CREATIVE_MODE_TAB 会被 BuiltInRegistries.bootStrap() 冻结），
        // 由 EntryPointHook.registerCreativeTabs() 在 ensureBootstrapped() 之前调用。
        if (!blocks.isEmpty()) {
            LOG.fine("Mods whose creative tabs were created during the registration"
                    + " window: " + blocks.keySet());
        }
    }

    /**
     * 取当前可用的 {@code Minecraft} 实例；未就绪返回 {@code null}。
     *
     * <p>用 {@code Minecraft.getInstance()}：它是 26.2 的公开静态方法，
     * 比反射私有字段更稳。
     */
    private static Object currentMinecraft() {
        try {
            Class<?> mc = Reflect.gameClass("net.minecraft.client.Minecraft");
            return mc.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 客户端尚未构造时 getInstance 可能抛 IllegalStateException，
            // 属正常情况，按"还没就绪"处理即可。
            return null;
        }
    }

    /** 诊断用：已登记但未注入的 Mod。 */
    public static Map<String, Path> pending() {
        synchronized (PENDING) {
            return Map.copyOf(PENDING);
        }
    }
}
