package org.loader.api.transform.target;

/**
 * 目标字段引用 —— 类 + 字段名 + 描述符的三元组。
 *
 * <p>与 {@link TargetMethod} 同理，描述符不可省：JVM 允许不同类型的字段
 * 同名，只按名字匹配会命中错误的字段。
 *
 * <p>用于 {@link org.loader.api.transform.InjectionPoint#BEFORE_FIELD_ACCESS} /
 * {@link org.loader.api.transform.InjectionPoint#AFTER_FIELD_ACCESS} /
 * {@link org.loader.api.transform.InjectionPoint#REPLACE_FIELD_ACCESS}。
 */
public final class TargetField {

    private final String owner;
    private final String name;
    private final String descriptor;

    private TargetField(String owner, String name, String descriptor) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (descriptor == null || descriptor.isBlank()) {
            throw new IllegalArgumentException("descriptor 不能为空");
        }
        this.owner = owner.trim();
        this.name = name.trim();
        this.descriptor = descriptor.trim();
    }

    /**
     * 构造字段引用。
     *
     * @param owner      所属类内部名，斜杠分隔
     * @param name       字段名
     * @param descriptor JVM 描述符，如 {@code I}、{@code Z}、{@code Ljava/lang/String;}
     */
    public static TargetField of(String owner, String name, String descriptor) {
        return new TargetField(owner, name, descriptor);
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public String ownerDotted() {
        return owner.replace('/', '.');
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TargetField other)) return false;
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
        return owner + "#" + name + ":" + descriptor;
    }
}