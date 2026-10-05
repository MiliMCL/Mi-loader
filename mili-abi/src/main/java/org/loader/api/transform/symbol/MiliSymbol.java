package org.loader.api.transform.symbol;

import org.loader.api.transform.target.TargetMethod;

/**
 * Mili 符号 —— 稳定的逻辑名到 26.2 真实 JVM 坐标的映射。
 *
 * <h2>为什么需要这一层</h2>
 * Minecraft 的类名与方法名会随版本变化。若坐标散落硬编码在各转换器里，
 * 一次改名就会让所有转换器同时失效，而且每个都要单独排查。
 * 符号层把「我们想改 tickServer」与「26.2 里它叫
 * {@code net/minecraft/server/MinecraftServer#tickServer(BooleanSupplier)}」
 * 这两件事分开。
 *
 * <h2>当前范围：只做 26.2</h2>
 * 需求明确要求不要为「未来兼容」一次性实现复杂 mapping 系统。
 * 这里只有一张静态符号表，无 mapping 文件、无 obfuscation 解析、
 * 无自动生成管线。它解决的是<b>硬编码散落</b>问题，
 * 不是<b>跨版本兼容</b>问题 —— 后者是独立课题。
 *
 * <h2>坐标如何保证正确</h2>
 * <b>本表中的每个坐标都必须由 CI 从真实 Minecraft jar 提取校验</b>，
 * 仓库文档中对
 * 同一方法的描述符有两处互相矛盾的写法（{@code tickServer()} 与
 * {@code tickChildren(long)}），而真实值是
 * {@code tickServer(Ljava/util/function/BooleanSupplier;)V}。
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.3">JVMS §4.3 字段与方法</a>
 */
public final class MiliSymbol {

    private MiliSymbol() {
    }

    // ── 已知 Minecraft 版本 ────────────────────────────────────────────────

    /**
     * 本符号表对应的 Minecraft 版本。
     *
     * <p>与 {@link org.loader.api.VersionInfo#TARGET_MINECRAFT} 保持一致；
     * 不一致时构建期校验失败（见根项目 {@code verifyVersionConstants} 的同类机制）。
     */
    public static final String MINECRAFT_VERSION = "26.2";

    // ── 服务端 tick ────────────────────────────────────────────────────────

    /**
     * 服务端主 tick 入口 —— <b>平台 Tick 接入的首选锚点</b>。
     *
     * <p>坐标经实测确认（直接解析 26.2 class 文件常量池，非来自文档）：
     * <pre>
     * net.minecraft.server.MinecraftServer#tickServer(BooleanSupplier)V
     * </pre>
     *
     * <p><b>注意参数不是无参。</b>早期文档写作 {@code tickServer()}，
     * 实际签名带一个 {@code BooleanSupplier} 参数（MC 26.x 引入的
     * 「服务器是否卡住」标志）。按无参匹配会静默不命中。
     *
     * <p>{@code MinecraftServer} 继承自
     * {@code net.minecraft.util.thread.ReentrantBlockableEventLoop}，
     * 在服务器主循环中每 tick 调用一次。
     */
    public static final TargetMethod SERVER_TICK = TargetMethod.of(
            "net/minecraft/server/MinecraftServer",
            "tickServer",
            "(Ljava/util/function/BooleanSupplier;)V");

    /**
     * 服务端子 tick（世界 + 实体）—— 二级锚点。
     *
     * <p>实测坐标：
     * <pre>
     * net.minecraft.server.MinecraftServer#tickChildren(BooleanSupplier)V
     * </pre>
     *
     * <p><b>文档漂移记录</b>：{@code ReflectiveMinecraftTickSource} 的类注释
     * 称此方法签名为 {@code (long)}，实际为 {@code (BooleanSupplier)}。
     * 该注释需要修正。
     *
     * <p>用途：需要「只包住世界/实体 tick、不含服务器自身逻辑」时使用。
     * 注意它同样带 BooleanSupplier 参数。
     */
    public static final TargetMethod SERVER_TICK_CHILDREN = TargetMethod.of(
            "net/minecraft/server/MinecraftServer",
            "tickChildren",
            "(Ljava/util/function/BooleanSupplier;)V");

    // ── 客户端 tick ────────────────────────────────────────────────────────

    /**
     * 客户端本地世界 tick —— 单人游戏路径的锚点。
     *
     * <p>实测坐标：
     * <pre>
     * net.minecraft.client.multiplayer.ClientLevel#tick(BooleanSupplier)V
     * </pre>
     *
     * <p>仅在客户端环境可用；服务端环境下引用本符号会导致目标解析失败
     * （此时应返回 {@link org.loader.api.transform.TransformationResult#Skipped}，
     * 而非抛异常）。
     */
    public static final TargetMethod CLIENT_LEVEL_TICK = TargetMethod.of(
            "net/minecraft/client/multiplayer/ClientLevel",
            "tick",
            "(Ljava/util/function/BooleanSupplier;)V");

    // ── 引导 ───────────────────────────────────────────────────────────────

    /**
     * 客户端入口 {@code main}。
     *
     * <p>已由 {@code EntryPointHook.invokeMinecraftMain} 反射调用并验证可用：
     * <pre>
     * net.minecraft.client.main.Main#main(String[])V
     * </pre>
     */
    public static final TargetMethod CLIENT_MAIN = TargetMethod.of(
            "net/minecraft/client/main/Main",
            "main",
            "([Ljava/lang/String;)V");

    /**
     * 服务端入口 {@code main}。
     *
     * <pre>
     * net.minecraft.server.Main#main(String[])V
     * </pre>
     */
    public static final TargetMethod SERVER_MAIN = TargetMethod.of(
            "net/minecraft/server/Main",
            "main",
            "([Ljava/lang/String;)V");

    /**
     * 判断某符号是否属于客户端专属。
     *
     * <p>客户端符号在服务端环境下<b>必然</b>无法解析 —— 目标类不在
     * 服务端 classpath 上。转换器应据此提前返回
     * {@link org.loader.api.transform.TransformationResult#Skipped}，
     * 而不是让引擎抛「目标不存在」。
     */
    public static boolean isClientOnly(TargetMethod symbol) {
        return CLIENT_LEVEL_TICK.equals(symbol) || CLIENT_MAIN.equals(symbol);
    }
}