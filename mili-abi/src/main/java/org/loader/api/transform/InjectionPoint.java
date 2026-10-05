package org.loader.api.transform;

/**
 * 注入点 —— 指定转换相对于目标方法<b>指令流</b>的位置。
 *
 * <p><b>这是 Mili 表面 API，不是 ASM 的包装</b>。Mod 只会看到这些枚举常量，
 * 永远不会看到 {@code InsnList} 或 {@code AbstractInsnNode}。底层如何实现
 * （{@code AdviceAdapter} 还是别的）是引擎的内部细节。
 *
 * <h2>为什么不能只提供 HEAD 和 RETURN</h2>
 * 「在调用 {@code foo()} 之前/之后执行」是模组最常见的需求，而只提供
 * HEAD/RETURN 会强迫 Mod 猜测调用位置。{@link #BEFORE_INVOKE} /
 * {@link #AFTER_INVOKE} 让 Mod 按 owner + name + descriptor 精确锚定，
 * 而 {@code ordinal} 用于区分同一方法内的多次相同调用。
 *
 * <h2>组合规则</h2>
 * <ul>
 *   <li>{@link #HEAD} / {@link #RETURN} 作用于方法本身，声明在方法级注解上</li>
 *   <li>{@link #BEFORE_INVOKE} / {@link #AFTER_INVOKE} 需要配
 *       {@link org.loader.api.transform.target.TargetInvocation}</li>
 *   <li>字段访问用 {@link #BEFORE_FIELD_ACCESS} /
 *       {@link #AFTER_FIELD_ACCESS} / {@link #REPLACE_FIELD_ACCESS}，
 *       配 {@link org.loader.api.transform.target.TargetField}</li>
 * </ul>
 *
 * <p><b>多个 transformer 注入到同一个位置是允许的</b>（HEAD/RETURN 可组合），
 * 只有语义互斥的注入（{@link #REDIRECT}、{@link #REPLACE_FIELD_ACCESS}、
 * {@link #OVERWRITE}）才触发冲突检测。
 */
public enum InjectionPoint {

    /**
     * 方法入口 —— 在任何原始指令之前执行。
     *
     * <p>典型用途：tick 接线、权限检查、状态初始化。
     *
     * <pre>
     * 原始：
     *   tickServer(BooleanSupplier) { ... }
     * 转换后：
     *   tickServer(BooleanSupplier) { TickDispatch.onTickEnter(); ... }
     * </pre>
     */
    HEAD,

    /**
     * 方法返回 —— 在<b>每一个</b>返回指令之前执行。
     *
     * <p><b>实现约束</b>：必须遍历方法内所有退出路径（{@code RETURN}、
     * {@code IRETURN} … {@code ARETURN}、以及可能存在的 {@code ATHROW}），
     * 只处理最后一个是错误实现 —— 大量 Java 方法有多个 early return。
     *
     * <p>对 {@code void} 方法同样适用。
     */
    RETURN,

    /**
     * 调用之前 —— 在目标方法调用指令之前执行。
     *
     * <p>需要配 {@code TargetInvocation} 定位被调用的方法，
     * 并可用 {@code ordinal} 区分同方法内的多次相同调用。
     */
    BEFORE_INVOKE,

    /**
     * 调用之后 —— 在目标方法调用指令之后执行。
     *
     * <p>语义细节：被调用方法的返回值此时仍在操作数栈上。因此回调的
     * 描述符必须能消费该返回值，否则栈会失衡。引擎会拒绝不匹配的情况
     * 而不是生成非法字节码。
     */
    AFTER_INVOKE,

    /**
     * 字段读取之前 —— 在 {@code GETFIELD} / {@code GETSTATIC} 之前执行。
     */
    BEFORE_FIELD_ACCESS,

    /**
     * 字段写入之前 —— 在 {@code PUTFIELD} / {@code PUTSTATIC} 之前执行。
     *
     * <p>此时值仍在栈上，字段所属对象也仍在栈上。
     */
    BEFORE_FIELD_SET,

    /**
     * 字段访问之后 —— 在字段读写指令之后执行。
     */
    AFTER_FIELD_ACCESS,

    /**
     * 替换字段访问 —— 把对某字段的读写重定向到另一个字段。
     *
     * <p><b>互斥</b>：同一 (类, 方法, 字段, 指令) 上只能存在一个
     * {@code REPLACE_FIELD_ACCESS}，否则抛
     * {@link TransformationConflictException}。
     */
    REPLACE_FIELD_ACCESS,

    /**
     * 替换方法调用 —— 把对某方法的调用重定向到另一个方法。
     *
     * <p><b>互斥</b>：同一目标只能存在一个 {@code REDIRECT}。
     *
     * <p><b>栈语义硬约束</b>：替代方法的描述符必须与原方法
     * <i>在栈层面完全等价</i>（参数个数、类型与顺序、返回类型）。
     * 引擎会严格校验；无法安全替换时抛
     * {@link TransformationException}，绝不生成非法字节码。
     */
    REDIRECT,

    /**
     * 修改参数 —— 把调用实参替换为回调计算出的值。
     *
     * <p>需要 {@code index} 指定第几个参数（从 0 开始）。
     *
     * <p>必须正确处理 primitive、wide（long/double）、引用类型与
     * 装箱/拆箱。引擎负责临时局部变量槽的分配。
     */
    MODIFY_ARG,

    /**
     * 修改返回值 —— 用回调的计算结果替换方法的返回值。
     *
     * <p>对 {@code void} 方法不适用，声明时会被拒绝。
     */
    MODIFY_RETURN,

    /**
     * 整体覆写 —— 丢弃原始方法体，替换为回调实现。
     *
     * <p><b>默认禁用。</b>启用需同时满足：
     * <ol>
     *   <li>持有 {@code OVERWRITE_METHOD} 能力；</li>
     *   <li>目标未被 {@link TransformationPhase#CORE} 阶段占用。</li>
     * </ol>
     *
     * <p>覆写会破坏模组兼容性与转换可组合性（见 ADR-0011），因此它是
     * 最后手段，不是常规工具。
     */
    OVERWRITE
}