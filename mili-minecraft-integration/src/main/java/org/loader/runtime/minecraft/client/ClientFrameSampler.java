package org.loader.runtime.minecraft.client;

import org.loader.runtime.diagnostics.PerformanceMonitor;
import org.loader.runtime.kernel.Resource;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.client.input.KeyBindingDispatch;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端帧率采样器 —— 把原版的 FPS / 帧耗时喂给 {@link PerformanceMonitor}。
 *
 * <h2>为什么不注入渲染循环</h2>
 * 逐帧采样的代价不是「读两个字段」，而是「多一个字节码注入点」：
 * 每个注入点都要跟着 Minecraft 的方法签名走，一次版本升级就可能失效，
 * 而失效方式通常是启动期转换失败（游戏起不来），代价远高于收益。
 *
 * <p>原版自己维护着 {@code fps}（1 秒平滑）与 {@code frameTimeNs}
 * （上一帧耗时），都是 public 读取。客户端 tick 是 20Hz —— 对
 * 「TPS 20、FPS 60」的观察目标来说，20Hz 采样足以看出持续掉帧与
 * 尖峰，且成本是每 tick 两次反射调用。
 *
 * <h2>反射缓存</h2>
 * 三个 {@code Method} 在首次采样时解析一次；之后每次采样只是
 * {@code invoke}。不在每 tick 做 {@code getMethod} 查找。
 *
 * <h2>生命周期</h2>
 * 安装时把自身注册为 Scope 资源，并把 {@link #sample} 挂到客户端 tick
 * 钩子；Scope 关闭时移除钩子。钩子不持有 Minecraft 对象，只持有
 * monitor 引用 —— 因此不会把游戏实例钉在静态字段上。
 */
public final class ClientFrameSampler implements Resource {

    private static final String MC = "net.minecraft.client.Minecraft";

    private static volatile Method getInstanceMethod;
    private static volatile Method getFpsMethod;
    private static volatile Method getFrameTimeMethod;

    private final String id;
    private final Scope owner;
    private final PerformanceMonitor monitor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Runnable hook = this::sample;

    private ClientFrameSampler(String id, Scope owner, PerformanceMonitor monitor) {
        this.id = id;
        this.owner = owner;
        this.monitor = monitor;
    }

    /**
     * 安装采样器。重复安装是安全的：每次安装都会注册一个新的资源，
     * 由 Scope 统一关闭；生产路径只会调用一次。
     */
    public static Resource install(PerformanceMonitor monitor, Scope owner) {
        ClientFrameSampler sampler =
                new ClientFrameSampler(owner.id() + ":frame-sampler", owner, monitor);
        owner.registerResource(sampler);
        KeyBindingDispatch.addTickHook(sampler.hook);
        return sampler;
    }

    @Override
    public String id() {
        return id;
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
            KeyBindingDispatch.removeTickHook(hook);
            // 反射缓存指向游戏类加载器里的 Class；Scope 关闭后清掉，
            // 避免跨游戏实例（测试 / 热重启）继续持有旧的 Class 对象。
            getInstanceMethod = null;
            getFpsMethod = null;
            getFrameTimeMethod = null;
        }
    }

    // ── 采样 ───────────────────────────────────────────────────────────────

    private void sample() {
        if (closed.get()) {
            return;
        }
        try {
            Object mc = getInstance().invoke(null);
            if (mc == null) {
                return;
            }
            int fps = (int) fpsMethod().invoke(mc);
            long frameNanos = (long) frameTimeMethod().invoke(mc);
            monitor.recordFrame(fps, frameNanos);
        } catch (Throwable ignored) {
            // 采样失败不影响游戏：诊断是可选能力。
            // 这里刻意不打印日志 —— 每 tick 一次的失败日志会淹没真正的错误。
        }
    }

    private static Method getInstance() throws Exception {
        Method method = getInstanceMethod;
        if (method == null) {
            method = Reflect.gameClass(MC).getMethod("getInstance");
            getInstanceMethod = method;
        }
        return method;
    }

    private static Method fpsMethod() throws Exception {
        Method method = getFpsMethod;
        if (method == null) {
            method = Reflect.gameClass(MC).getMethod("getFps");
            getFpsMethod = method;
        }
        return method;
    }

    private static Method frameTimeMethod() throws Exception {
        Method method = getFrameTimeMethod;
        if (method == null) {
            method = Reflect.gameClass(MC).getMethod("getFrameTimeNs");
            getFrameTimeMethod = method;
        }
        return method;
    }
}
