package org.loader.api.transform;

/**
 * 类级转换上下文 —— 单个转换器处理单个类时可见的全部信息。
 *
 * <p>在一次类加载内<b>恒定不变</b>（同一次加载的多个转换器拿到相同实例），
 * 因此可以被安全缓存。运行时数据不在这里，见
 * {@link org.loader.api.transform.callback.InjectionContext}。
 *
 * <p>本类不可变。
 */
public final class TransformationContext {

    private final String className;
    private final byte[] originalBytes;
    private final TransformationEnvironment environment;
    private final TransformationPhase phase;
    private final String modId;
    private final String transformerId;

    public TransformationContext(
            String className,
            byte[] originalBytes,
            TransformationEnvironment environment,
            TransformationPhase phase,
            String modId,
            String transformerId) {
        this.className = requireNonNull(className, "className");
        this.originalBytes = requireNonNull(originalBytes, "originalBytes");
        this.environment = requireNonNull(environment, "environment");
        this.phase = requireNonNull(phase, "phase");
        this.modId = modId;
        this.transformerId = requireNonNull(transformerId, "transformerId");
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " 不能为 null");
        }
        return value;
    }

    /**
     * 目标类的内部名，斜杠分隔，如
     * {@code net/minecraft/server/MinecraftServer}。
     *
     * <p>使用 JVM 内部名而非 {@code net.minecraft.server.MinecraftServer}，
     * 是因为它可直接用于 ASM 的 {@code visit} / {@code visitMethodInsn}
     * —— 少一次转换，也少一类「两种写法混用」的错误。
     */
    public String className() {
        return className;
    }

    /**
     * 原始字节码。
     *
     * <p><b>不要修改这个数组。</b>它是 JVM 即将 {@code defineClass} 的内容，
     * 共享引用被就地修改会污染并发生行的其他转换器。
     * 需要修改时请先复制。
     */
    public byte[] originalBytes() {
        return originalBytes;
    }

    /** Pipeline 级环境。 */
    public TransformationEnvironment environment() {
        return environment;
    }

    /** 本次转换所处的阶段。 */
    public TransformationPhase phase() {
        return phase;
    }

    /**
     * 发起本次转换的 Mod id；平台自身转换为 {@code null}。
     *
     * <p>审计与权限判定使用。平台转换（{@code modId == null}）与 Mod
     * 转换（{@code modId != null}）在能力检查上走不同路径。
     */
    public String modId() {
        return modId;
    }

    /** 是否为平台自身的转换（而非 Mod 转换）。 */
    public boolean isPlatformTransformation() {
        return modId == null;
    }

    /** 当前转换器的 id —— 与 {@link MiliTransformer#id()} 一致。 */
    public String transformerId() {
        return transformerId;
    }

    /**
     * 派生一个仅改变 transformerId / modId 的副本。
     *
     * <p>Pipeline 复用同一份类级数据时用，避免为每个转换器重复构造。
     */
    public TransformationContext withIdentity(String newTransformerId, String newModId) {
        return new TransformationContext(className, originalBytes, environment,
                phase, newModId, newTransformerId);
    }
}