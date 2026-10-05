package org.loader.api.transform.callback;

/**
 * 注入回调 —— Mod 注入方法的统一形态。
 *
 * <p>平台保证注入的字节码<b>只调用平台分发器</b>，分发器再转调本接口
 * 的实现。因此生成的 Minecraft 字节码对 Mod 类零引用，
 * {@code ModClassLoader} 可独立回收（见 ADR-0011 与
 * {@code ClassLoaderLeakTest}）。
 *
 * <h2>异常语义</h2>
 * 回调抛出的异常<b>不会逃逸进 Minecraft 的调用栈</b>。平台会：
 * <ol>
 *   <li>捕获并记录到所属 tick 契约（{@code TickContract.fail}）；</li>
 *   <li>写入审计日志，标明是哪个 Mod 的哪个回调；</li>
 *   <li>继续执行游戏逻辑。</li>
 * </ol>
 *
 * <p>理由：字节码注入的回调一旦让异常逃逸，会以
 * {@code VerifyError} 式的晦涩错误打断游戏主循环，且玩家无法判断是自己的
 * Mod 导致的。Mod 作者能拿到的诊断信息必须足够定位问题。
 *
 * @param <T> 回调自身的上下文类型，通常是 {@link InjectionContext}
 */
@FunctionalInterface
public interface InjectionCallback<T> {

    /**
     * 执行回调。
     *
     * @param context 注入上下文
     * @throws Throwable 允许抛出；平台会捕获、审计并隔离
     */
    void invoke(T context);
}