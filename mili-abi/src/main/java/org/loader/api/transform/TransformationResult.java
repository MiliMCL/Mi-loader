package org.loader.api.transform;

/**
 * 单个 Transformer 对单个类的转换结果。
 *
 * <h2>为什么拆成四种而不是三种</h2>
 * 最初的草案只有 {@link #Unchanged()} / {@link #Transformed} / {@code Rejected(String)}，
 * 但 {@code Rejected} 同时被两种<b>互斥</b>的需求使用：
 *
 * <ul>
 *   <li>「目标类/方法不存在时必须明确失败，<b>不要静默跳过</b>」
 *       —— 否则 Mili Core 的 transformer 悄悄失效，游戏照常运行但
 *       tick 数不对；</li>
 *   <li>「没有 transformer 关心这个类就<b>直接 defineClass</b>」
 *       —— 绝大多数类本就不该被转换。</li>
 * </ul>
 *
 * <p>共用一个类型时二者会互相掩盖：Pipeline 无法区分「这个 transformer
 * 不关心这个类」（正常，继续）与「这个 transformer 关心但目标消失了」
 * （必须炸）。这类「静默失效比崩溃难查得多」的问题在本仓库有明确前科
 * ——见 {@code EntryPointHook.runModRegistrations()} 的注释：
 * 早期版本注册链在生产路径上是死代码，不报任何错，只是内容没进游戏。
 *
 * <p>因此明确区分：
 * <ul>
 *   <li>{@link #Skipped} — 声明式「不适用」，Pipeline 静默继续</li>
 *   <li>{@link #Failed} — 执行失败，Pipeline 按冲突策略处理</li>
 * </ul>
 */
public sealed interface TransformationResult {

    /**
     * 未改变字节码 —— 转换器看过这个类但决定不动它。
     *
     * <p>注意与 {@link #Skipped} 的区别：{@code Unchanged} 表示
     * 「我处理了它，但它不需要改」（例如该类没有目标方法），
     * {@code Skipped} 表示「这个类不在我的关注范围内」。
     */
    record Unchanged() implements TransformationResult {
        /** 共享实例 —— 无状态，可安全复用。 */
        public static final Unchanged INSTANCE = new Unchanged();
    }

    /**
     * 转换成功。
     *
     * @param bytecode 转换后的完整类字节。必须是完整的类文件，
     *                 而不是片段 —— Pipeline 会直接拿它去 {@code defineClass}。
     */
    record Transformed(byte[] bytecode) implements TransformationResult {

        public Transformed {
            if (bytecode == null || bytecode.length == 0) {
                throw new IllegalArgumentException("转换结果不能是空字节码");
            }
        }
    }

    /**
     * 声明式跳过 —— 该转换器不适用于当前目标。
     *
     * <p>这是<b>正常</b>路径，Pipeline 静默继续处理后续转换器，
     * 不记警告、不写审计失败。
     *
     * @param reason 人类可读的原因，仅用于诊断
     */
    record Skipped(String reason) implements TransformationResult {

        public Skipped {
            if (reason == null) {
                throw new IllegalArgumentException("Skipped 必须给出原因");
            }
        }

        /** 构造一个带固定原因的跳过结果。 */
        public static Skipped because(String reason) {
            return new Skipped(reason);
        }
    }

    /**
     * 转换失败 —— 声明了目标但执行过程中出错。
     *
     * <p><b>与 {@link #Skipped} 的选择是本 API 最重要的判断</b>：
     * <ul>
     *   <li>目标类里根本没有你要注入的方法 → 这是
     *       {@link TransformationTargetNotFoundException}，
     *       必须失败，绝不能返回 {@code Skipped}；</li>
     *   <li>你的转换器本来就不处理这类目标 → 返回 {@link #Skipped}。</li>
     * </ul>
     * 把前者误写成 {@code Skipped}，就是本仓库历史上最痛的那类 bug。
     *
     * @param error 失败原因
     */
    record Failed(TransformationException error) implements TransformationResult {

        public Failed {
            if (error == null) {
                throw new IllegalArgumentException("Failed 必须携带 TransformationException");
            }
        }
    }
}