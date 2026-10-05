package org.loader.api.transform.target;

/**
 * 目标方法调用引用 —— 定位方法体内的某一次<b>调用指令</b>。
 *
 * <p>{@link TargetMethod} 定位的是方法<b>声明</b>；本类定位的是方法体内部的
 * 调用点。两者用途不同：{@code REDIRECT} / {@link
 * org.loader.api.transform.InjectionPoint#BEFORE_INVOKE} 需要本类。
 *
 * <h2>ordinal 为什么必要</h2>
 * 同一个方法体内经常有多次相同的调用：
 * <pre>
 * void f() {
 *     list.add(a);   // ordinal 0
 *     map.put(k, v); // 不同 owner，不计
 *     list.add(b);   // ordinal 1
 * }
 * </pre>
 * 只按 owner+name+descriptor 会同时命中两处，无法表达「在第二处之前插入」。
 * {@code ordinal} 从 0 开始，按字节码顺序计数<b>同签名</b>的调用。
 *
 * <h2>严格匹配</h2>
 * 五元组（owner / name / descriptor / opcode / ordinal）全部参与匹配。
 * 任一不符即不命中 —— <b>宁可匹配失败并报错，也不模糊命中</b>。
 * 模糊匹配会让注入落在意外的位置，且这类错误只在特定代码路径上暴露。
 *
 * <p><b>opcode 可选</b>：不指定时匹配该签名的任意调用指令（普通方法调用与
 * 构造器调用均可）。指定时精确匹配，用于区分
 * {@code INVOKESPECIAL}（构造器/私有方法）与 {@code INVOKEVIRTUAL}。
 */
public final class TargetInvocation {

    private final TargetMethod method;
    private final int ordinal;
    private final Integer opcode;

    private TargetInvocation(TargetMethod method, int ordinal, Integer opcode) {
        this.method = method;
        this.ordinal = ordinal;
        this.opcode = opcode;
    }

    /**
     * 定位某方法内第 ordinal 次调用给定方法。
     *
     * @param method   被调用的目标
     * @param ordinal  从 0 开始的序号
     */
    public static TargetInvocation at(TargetMethod method, int ordinal) {
        if (method == null) {
            throw new IllegalArgumentException("method 不能为 null");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException(
                    "ordinal 不能为负数，实际: " + ordinal);
        }
        return new TargetInvocation(method, ordinal, null);
    }

    /** 定位某方法内第一次调用给定方法。 */
    public static TargetInvocation first(TargetMethod method) {
        return at(method, 0);
    }

    /**
     * 限定只匹配特定 opcode 的调用。
     *
     * <p>用于区分 {@code INVOKESPECIAL} / {@code INVOKEVIRTUAL} /
     * {@code INVOKESTATIC} / {@code INVOKEINTERFACE}。
     *
     * @param opcode {@code Opcodes.INVOKE*} 常量值
     */
    public TargetInvocation withOpcode(int opcode) {
        return new TargetInvocation(method, ordinal, opcode);
    }

    /** 精确限定构造器调用（{@code INVOKESPECIAL}）。 */
    public TargetInvocation constructorOnly() {
        return withOpcode(183); // Opcodes.INVOKESPECIAL
    }

    public TargetMethod method() {
        return method;
    }

    public int ordinal() {
        return ordinal;
    }

    /** 限定的 opcode；未限定时为 null，表示匹配任意调用。 */
    public Integer opcode() {
        return opcode;
    }

    /**
     * 是否匹配给定 opcode。
     *
     * @param actual 实际遇到的 opcode 值
     */
    public boolean matchesOpcode(int actual) {
        return opcode == null || opcode == actual;
    }

    /** 冲突检测的主键分量 —— 与 {@link TargetMethod} 组合可唯一定位一个调用点。 */
    public String conflictKey() {
        return method + "#ordinal=" + ordinal + (opcode != null ? ",opcode=" + opcode : "");
    }

    @Override
    public String toString() {
        return method + "[#" + ordinal + (opcode != null ? ",opcode=" + opcode : "") + "]";
    }
}