package org.loader.api.transform;

/**
 * 字节码验证失败 —— 转换器产出了非法字节码。
 *
 * <p><b>这个异常存在的理由：字节码 bug 的错误信息极不指向真正的原因。</b>
 * 一个描述符写错或栈帧失衡，在游戏里表现为
 * {@code VerifyError} 或 {@code ClassFormatError}，堆栈指向 JVM 校验器，
 * 完全看不出是哪个 Mod 的哪个转换器干的。本仓库在
 * {@code GeneratedBlockFactoryRealMinecraftTest} 的注释里记录过同类教训：
 * 「任何一处描述符写错……都不会在编译期暴露，只会在游戏里抛
 * ClassFormatError，而这类错误信息完全不指向真正的原因」。
 *
 * <p>因此平台在 {@code defineClass} 之前强制验证，并把上下文全部带上：
 * 类、方法、转换器、Mod、字节码偏移、具体原因。
 */
public final class TransformationVerificationException extends TransformationException {

    private static final long serialVersionUID = 1L;

    private final String className;
    private final String methodName;
    private final String modId;
    private final int bytecodeOffset;
    private final String verificationReason;

    public TransformationVerificationException(
            String className,
            String methodName,
            String transformerId,
            String modId,
            int bytecodeOffset,
            String verificationReason,
            Throwable cause) {
        super(buildMessage(className, methodName, transformerId, modId,
                        bytecodeOffset, verificationReason),
                transformerId, cause);
        this.className = className;
        this.methodName = methodName;
        this.modId = modId;
        this.bytecodeOffset = bytecodeOffset;
        this.verificationReason = verificationReason;
    }

    private static String buildMessage(String cls, String method, String transformerId,
                                      String modId, int offset, String reason) {
        return "字节码验证失败:\n"
                + "  Class     : " + cls + "\n"
                + "  Method    : " + (method == null ? "<class level>" : method) + "\n"
                + "  Transformer: " + transformerId + "\n"
                + "  Mod       : " + (modId == null ? "<platform>" : modId) + "\n"
                + "  Offset    : " + offset + "\n"
                + "  Reason    : " + reason + "\n"
                + "转换器产出的字节码不合法，已阻止 defineClass。";
    }

    public String className() {
        return className;
    }

    public String methodName() {
        return methodName;
    }

    public String modId() {
        return modId;
    }

    /** 出错指令在方法内的字节偏移；无法确定时为 -1。 */
    public int bytecodeOffset() {
        return bytecodeOffset;
    }

    public String verificationReason() {
        return verificationReason;
    }
}