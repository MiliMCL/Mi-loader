package org.loader.runtime.tick;

/**
 * Tick 引擎的<b>迟绑定</b>容器。
 *
 * <h2>为什么需要迟绑定</h2>
 * Mod 的 {@code initialize()} 发生在 {@code MinecraftBootstrap} 创建之前：
 * <pre>
 *   LoaderMain.invokeModEntrypoints()      ← Mod 在这里拿 ctx.tick()
 *        ↓
 *   MinecraftGameProvider.launch()
 *        ↓
 *   EntryPointHook.install() → new MinecraftBootstrap(...) → new TickEngine(...)
 * </pre>
 * 也就是说 Mod 拿到 {@code ModContext} 时，tick 引擎<b>还不存在</b>。
 *
 * <p>如果这时把 {@code null} 固化进 {@code ModContext}，那么每个在
 * {@code initialize()} 里调用 {@code ctx.tick()} 的 Mod 都会永久拿到一个
 * 「不可用」的实现 —— 即使几百毫秒后引擎就绪了。
 *
 * <p>因此这里只持有一个可变的引擎引用：调用方随时取最新的，
 * 引擎装配后立刻生效，不需要任何 Mod 重新获取 context。
 */
public final class TickEngineHolder {

    private volatile TickEngine engine;

    /** 装配引擎；传 {@code null} 表示卸载（引擎随平台关闭）。 */
    public void set(TickEngine engine) {
        this.engine = engine;
    }

    /** 当前引擎；尚未装配时为 {@code null}。 */
    public TickEngine get() {
        return engine;
    }

    /** 是否已装配且未关闭。 */
    public boolean isReady() {
        TickEngine e = engine;
        return e != null && !e.isClosed();
    }
}
