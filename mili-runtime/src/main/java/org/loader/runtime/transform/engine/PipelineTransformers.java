package org.loader.runtime.transform.engine;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.target.TargetField;
import org.loader.api.transform.target.TargetMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 目标存在性断言 —— 在生成字节码前确认锚点真实存在。
 *
 * <h2>为什么这是整个系统里最重要的一段代码</h2>
 * 字节码注入有一种独特的失败模式：<b>目标不存在时不报错，只是没注入</b>。
 * 结果是游戏照常运行、Mod 的功能悄然消失、日志里什么都没有。
 *
 * <p>本仓库对这类问题有明确的前科：
 * <ul>
 *   <li>早期注册链在生产路径上是死代码，不报任何错；</li>
 *   <li>TickBridge 的 beginTick/endTick 在生产路径上零调用者；</li>
 *   <li>任何一处描述符写错都不会在编译期暴露，只会在游戏里抛
 *       {@code ClassFormatError}，而错误信息完全不指向真正的原因。</li>
 * </ul>
 *
 * <p>而 <b>Minecraft 跨版本改名、改描述符是常态</b>。
 * 一个写错的 descriptor 在开发机上（版本恰好匹配）工作正常，
 * 换到下一个 Mojang 快照就静默失效。
 *
 * <p>因此规则是硬的：<b>声明了目标就必须确认它存在，不存在就抛
 * {@link org.loader.api.transform.TransformationTargetNotFoundException}</b>。
 * 没有第三条路。
 *
 * <h2>为什么用 ClassReader 扫一遍而不是维护一张符号表</h2>
 * 扫一遍字节码约需几十微秒，且<b>只在真正匹配的类上执行</b>。
 * 换来的是「断言与实际字节码同源」—— 不存在「符号表过期」这种失效模式。
 * 用一张手工维护的符号表做校验，恰恰会退化成需要被校验的东西。
 */
public final class PipelineTransformers {

    private PipelineTransformers() {
    }

    /**
     * 断言目标方法存在。
     *
     * @param original   类字节码
     * @param method     目标方法
     * @param transformerId 用于异常信息
     * @param minecraftVersion 用于异常信息
     * @throws org.loader.api.transform.TransformationTargetNotFoundException 不存在
     */
    public static void requireMethod(
            byte[] original, TargetMethod method, String transformerId,
            String minecraftVersion) {
        if (!containsMethod(original, method.name(), method.descriptor())) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    method.owner(), method.name(), method.descriptor(),
                    minecraftVersion, transformerId);
        }
    }

    /**
     * 类中是否含指定方法。
     *
     * <p><b>严格匹配 name + descriptor</b>：只匹配方法名是不够的 ——
     * Minecraft 里大量方法重载（{@code tick()} 与 {@code tick(BooleanSupplier)}），
     * 按名字匹配会命中错误的那个，生成出调用错误方法的字节码。
     * 这类错误同样不会报错，只是行为诡异。
     */
    public static boolean containsMethod(
            byte[] classBytes, String methodName, String descriptor) {

        if (classBytes == null || methodName == null || descriptor == null) {
            return false;
        }
        final boolean[] found = {false};
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String desc,
                        String signature, String[] exceptions) {
                    if (found[0]) {
                        return null;
                    }
                    if (methodName.equals(name) && descriptor.equals(desc)) {
                        found[0] = true;
                        return null;
                    }
                    // 不需要方法体：只关心签名是否存在
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            // 字节码本身不可读 —— 这不是「目标不存在」，而是更严重的问题，
            // 交给调用方的验证环节报错更有信息量。这里返回 false。
            return false;
        }
        return found[0];
    }

    /**
     * 类中是否含指定字段。
     *
     * <p>同样严格匹配 name + descriptor：{@code field} 与 {@code fieldI}
     * 是两个不同的字段，只按名字匹配会命中错误的那个。
     */
    public static boolean containsField(
            byte[] classBytes, TargetField field) {

        if (classBytes == null || field == null) {
            return false;
        }
        final boolean[] found = {false};
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(
                        int access, String name, String desc,
                        String signature, Object value) {
                    if (field.name().equals(name)
                            && field.descriptor().equals(desc)) {
                        found[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                    | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            return false;
        }
        return found[0];
    }

    /**
     * 断言目标字段存在。
     *
     * @param injection 注入声明，用于取 transformerId 与注入点
     */
    public static void requireField(
            byte[] original,
            TargetField field,
            InjectionPoint point,
            String transformerId,
            String minecraftVersion) {

        if (!containsField(original, field)) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    field.owner(), field.name() + " [" + point + "]",
                    field.descriptor(), minecraftVersion, transformerId);
        }
    }
}