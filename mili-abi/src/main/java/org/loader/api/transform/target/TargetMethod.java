package org.loader.api.transform.target;

/**
 * 目标方法引用 —— 类 + 方法名 + 描述符的三元组。
 *
 * <h2>为什么必须带描述符，不能只用方法名</h2>
 * JVM 允许方法重载。{@code tickServer} 这种名字在 Minecraft 里可能对应
 * 多个描述符，只按名字匹配会命中错误的那个 —— 而错误的注入点会生成
 * 看起来能跑、实际不执行的字节码。
 *
 * <h2>为什么不能靠手写描述符</h2>
 * 本次审计实测发现，{@code docs} 与需求文档里对同一方法的描述符有两处
 * 互相矛盾的写法：{@code tickServer()} vs 实际的
 * {@code tickServer(Ljava/util/function/BooleanSupplier;)V}。
 * <b>手写描述符必错</b>。因此生产代码里的坐标应当来自
 * {@link org.loader.api.transform.symbol.MiliSymbol} —— 它由 CI 从真实
 * Minecraft jar 生成并校验，而不是人写。
 *
 * @see org.loader.api.transform.symbol.MiliSymbol
 */
public final class TargetMethod {

    private final String owner;
    private final String name;
    private final String descriptor;

    private TargetMethod(String owner, String name, String descriptor) {
        this.owner = requireInternalName(owner, "owner");
        this.name = requireInternalName(name, "name");
        this.descriptor = requireDescriptor(descriptor);
    }

    /**
     * 构造目标方法引用。
     *
     * @param owner      所属类内部名，斜杠分隔
     * @param name       方法名
     * @param descriptor JVM 描述符，如 {@code (Ljava/util/function/BooleanSupplier;)V}
     * @throws IllegalArgumentException 任一参数格式非法
     */
    public static TargetMethod of(String owner, String name, String descriptor) {
        return new TargetMethod(owner, name, descriptor);
    }

    private static String requireInternalName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        return value.trim();
    }

    private static String requireDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("descriptor 不能为空");
        }
        String d = descriptor.trim();
        if (!d.startsWith("(") || !d.endsWith(")") && d.indexOf(')') < 0) {
            throw new IllegalArgumentException(
                    "descriptor 必须形如 (参数)返回类型，实际: " + descriptor);
        }
        if (d.indexOf(')') < 0) {
            throw new IllegalArgumentException(
                    "descriptor 缺少返回类型分隔符 ')': " + descriptor);
        }
        return d;
    }

    /** 所属类内部名。 */
    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    /** 所属类的点分名，供人类阅读。 */
    public String ownerDotted() {
        return owner.replace('/', '.');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetMethod other)) return false;
        return owner.equals(other.owner)
                && name.equals(other.name)
                && descriptor.equals(other.descriptor);
    }

    @Override
    public int hashCode() {
        return (owner.hashCode() * 31 + name.hashCode()) * 31 + descriptor.hashCode();
    }

    @Override
    public String toString() {
        return owner + "#" + name + descriptor;
    }
}