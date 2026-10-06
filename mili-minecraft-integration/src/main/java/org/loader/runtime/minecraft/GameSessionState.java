package org.loader.runtime.minecraft;

import org.loader.runtime.minecraft.reflect.Reflect;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次「游戏实例」的启动状态，按游戏 ClassLoader 隔离。
 *
 * <h2>为什么状态不能是全局静态</h2>
 *
 * <p>被这些标志描述的事实，<b>全都是游戏 ClassLoader 的静态状态</b>：
 * <ul>
 *   <li>{@code Bootstrap.isBootstrapped} 是 {@code net.minecraft.server.Bootstrap}
 *       的静态字段 —— 同一个字段名，在两个 ClassLoader 下是两个互不相干的字段；</li>
 *   <li>注册表是否 {@code frozen} 是 {@code BuiltInRegistries.BLOCK} 的实例状态，
 *       而它同样由那个 ClassLoader 定义；</li>
 *   <li>{@code SharedConstants} 的版本探测结果同理。</li>
 * </ul>
 *
 * <p>所以「平台记一份全局标志」在只有一个游戏实例时恰好能工作 —— 这是巧合，
 * 不是设计。一旦同一 JVM 里出现第二个游戏实例（集成服务端 + 客户端、
 * 多实例、以及平台自己的测试），第二个实例就会看到第一个实例留下的
 * {@code COMPLETE = true}，于是
 * {@link RegistrationPhase#openRegistryWindow()} 永远抛
 * 「registration window is already closed」——
 * <b>症状是「Mod 方块一个都注册不进去」，且与 Mod 代码毫无关系</b>。
 *
 * <h2>隔离方式</h2>
 * <p>以 {@link Reflect#gameClassLoader()} 为键。生产拓扑下它就是
 * {@code MinecraftClassLoader}；未显式设置时回落为平台 ClassLoader
 * （游戏类与平台同 CL 的打包场景）。
 *
 * <p>键用弱引用：一批游戏 ClassLoader 被回收后，对应状态随之消失，
 * 不会因为平台跑过测试或重启过实例而堆积。
 *
 * <p><b>已知取舍</b>：{@code pending} 里的注册动作会间接引用游戏对象，
 * 而游戏对象会引用其 ClassLoader —— 也就是说如果这些动作在
 * {@link RegistrationPhase#runRegistrations()} 之前游戏 ClassLoader 就被丢弃，
 * 该条目不会立即被回收。实践中不会发生：注册动作要么被执行（列表被清空），
 * 要么游戏进程随之一起结束。
 */
final class GameSessionState {

    private static final Map<ClassLoader, GameSessionState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private GameSessionState() {
    }

    /**
 * 取当前游戏实例的状态。
     *
     * <p>键是 {@link Reflect#gameClassLoader()}：生产拓扑下它就是
     * {@code MinecraftClassLoader}，未显式设置时是平台 ClassLoader。
     *
     * <p><b>键必须全线程一致</b>，否则同一个游戏实例会在不同线程上
     * 拿到不同的状态对象 —— 例如主线程置位了 {@code versionDetected}，
     * 游戏线程却读到另一个实例的 {@code false}，于是版本被重复探测、
     * 甚至在冻结后又去开注册窗口。为此 {@code Reflect} 里的游戏
     * ClassLoader 必须是<b>静态</b>字段而非 ThreadLocal：游戏跑在
     * {@code minecraft-main} 线程上，ThreadLocal 会让那条线程看到
     * 平台 ClassLoader，进而看到一个完全空的状态。
     *
     * <p>仍不受支持的场景：同一JVM 内并发驱动多个游戏实例。真要支持，
     * 必须先把游戏 ClassLoader 从全局状态提升为显式参数。
     */
    static GameSessionState current() {
        ClassLoader key = Reflect.gameClassLoader();
        synchronized (STATES) {
            GameSessionState state = STATES.get(key);
            if (state == null) {
                state = new GameSessionState();
                STATES.put(key, state);
            }
            return state;
        }
    }

    /** 仅供测试：清空所有游戏实例的状态。 */
    static void resetAll() {
        synchronized (STATES) {
            STATES.clear();
        }
    }

    /** {@code SharedConstants.tryDetectVersion()} 是否已执行。 */
    final AtomicBoolean versionDetected = new AtomicBoolean(false);

    /** {@code Bootstrap.isBootstrapped} 是否已被置位。 */
    final AtomicBoolean flagSet = new AtomicBoolean(false);

    /** 注册表是否已 freeze（窗口已关闭）。 */
    final AtomicBoolean windowClosed = new AtomicBoolean(false);

    /** 游戏 bootstrap 是否已完成。 */
    final AtomicBoolean bootstrapDone = new AtomicBoolean(false);

    /** 上一次 bootstrap 失败的原因。 */
    final AtomicReference<Throwable> bootstrapFailure = new AtomicReference<>();

    /** Mod 登记的待执行注册动作，按登记顺序执行。 */
    final List<Runnable> pending = new CopyOnWriteArrayList<>();

    /** 保护 {@link #bootstrapDone} 的双检锁 —— 每实例一把，不跨实例争用。 */
    private final Object bootstrapLock = new Object();

    Object bootstrapLock() {
        return bootstrapLock;
    }
}