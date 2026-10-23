package org.loader.runtime.minecraft.transform;

import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.TransformationTargetNotFoundException;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.transform.engine.PipelineTransformers;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

/**
 * 创造栏页签布局转换器 —— 让 Mod 的独立物品页真正可见。
 *
 * <h2>它修的是什么</h2>
 * 26.2 原版创造栏只有 TOP / BOTTOM 两行、每行 7 个位置（列 0..6），
 * 已被原版页签占满。平台给每个 Mod 建了页签，但
 * {@code getTabX} 只会算 {@code 27 * column}：列 100 会画到面板外，
 * 玩家永远看不到；同时 {@code extractTabButton} 里的
 * {@code Mth.clamp(pos, 0, sprites.length)} 会返回 7 并抛数组越界
 * （原版列号 0..6 时上界永远取不到，所以这个 bug 一直潜伏着）。
 *
 * <h2>改动</h2>
 * <ol>
 *   <li>整体替换 {@code getTabX(CreativeModeTab)I} 的方法体：
 *       调用 {@code CreativeTabLayout.tabX(imageWidth, column, alignedRight)}。
 *       原版页签的结果逐字节保持一致；哨兵列（≥100）排到面板右侧。</li>
 *   <li>把 {@code extractTabButton} 内的 {@code Mth.clamp(III)I} 重定向到
 *       {@code CreativeTabLayout.clampSprite(III)I}，上界收到 {@code length - 1}。</li>
 * </ol>
 *
 * <p>为什么不用通用的 {@code MODIFY_RETURN}：{@code getTabX} 是 private
 * 实例方法，返回值回调的栈语义与参数捕获在通用引擎里需要额外约定；
 * 而这里只有一个方法、一个返回点，直接重写方法体更可读、可测试。
 * 方法体替换会丢弃原指令，{@code visitMaxs} 交给 {@code COMPUTE_MAXS}
 * 重算；不重算帧 —— 新方法体没有分支，不需要 StackMapTable。
 *
 * <p>目标类只在客户端存在 —— 服务端不会加载它，{@code matches} 只在
 * 精确类名上返回 true。
 */
public final class MiliCreativeTabLayoutTransformer implements MiliTransformer {

    /** 转换器 id —— 排序与审计主键，不可变更。 */
    public static final String ID = "mili-core-creative-tab-layout";

    /** 目标界面（客户端专属）。 */
    public static final String SCREEN =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

    /** 面板尺寸字段的声明类（{@code imageWidth} 在这里）。 */
    private static final String CONTAINER_SCREEN =
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";

    /** 页签类型。 */
    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";

    /** 平台布局分发器。 */
    public static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/client/creative/CreativeTabLayout";

    private static final String GET_TAB_X_DESC =
            "(L" + TAB + ";)I";
    private static final String EXTRACT_TAB_DESC =
            "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIL" + TAB + ";)V";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    @Override
    public TransformationPhase phase() {
        return TransformationPhase.CORE;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean matches(String className) {
        return SCREEN.equals(className);
    }

    @Override
    public TransformationResult transform(TransformationContext context) {
        byte[] original = context.originalBytes();

        // 目标方法必须存在。找不到就抛 —— 静默跳过会让 Mod 页签画到面板外，
        // 而日志里没有任何线索。
        if (!PipelineTransformers.containsMethod(original, "getTabX", GET_TAB_X_DESC)) {
            throw new TransformationTargetNotFoundException(
                    SCREEN, "getTabX", GET_TAB_X_DESC,
                    context.environment().minecraftVersion(), id());
        }
        if (!PipelineTransformers.containsMethod(original, "extractTabButton",
                EXTRACT_TAB_DESC)) {
            throw new TransformationTargetNotFoundException(
                    SCREEN, "extractTabButton", EXTRACT_TAB_DESC,
                    context.environment().minecraftVersion(), id());
        }

        return new TransformationResult.Transformed(rewrite(original));
    }

    /** 供测试直接调用 —— 不依赖完整的 {@link TransformationContext}。 */
    byte[] rewrite(byte[] original) {
        ClassReader reader = new ClassReader(original);
        // 只用 COMPUTE_MAXS，不重算帧：
        //  - getTabX 被替换成无分支直线代码，原帧被丢弃后不需要新帧
        //    （无跳转目标就没有 StackMapTable 要求）；
        //  - extractTabButton 只把 (III)I 的静态调用换成同描述符的静态调用，
        //    栈类型不变，原帧仍然成立。
        // COMPUTE_FRAMES 需要为类型合并解析 Minecraft 类，而目标类此刻尚未
        // defineClass，容易把正确的类算坏 —— 这里刻意不用。
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        boolean[] applied = new boolean[2]; // [0]=getTabX 已替换, [1]=clamp 已重定向

        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null) {
                    return null;
                }
                if ("getTabX".equals(name) && GET_TAB_X_DESC.equals(descriptor)) {
                    applied[0] = true;
                    return new ReplaceGetTabX(delegate);
                }
                if ("extractTabButton".equals(name) && EXTRACT_TAB_DESC.equals(descriptor)) {
                    return new ClampRedirect(delegate, applied);
                }
                return delegate;
            }
        };

        reader.accept(visitor, 0);

        if (!applied[0]) {
            throw new TransformationException(
                    "getTabX 方法体未被替换 —— 描述符与真实类不一致。"
                            + "\n  期望: " + GET_TAB_X_DESC
                            + "\n  目标: " + SCREEN,
                    id(), null);
        }
        if (!applied[1]) {
            throw new TransformationException(
                    "extractTabButton 中未找到 Mth.clamp(III)I —— 哨兵列会触发"
                            + "原版的数组越界（clamp 上界写的是 length 而不是 length-1）。"
                            + "\n  目标: " + SCREEN,
                    id(), null);
        }
        return writer.toByteArray();
    }

    // ── getTabX 方法体替换 ─────────────────────────────────────────────────

    /**
     * 丢弃原方法体，改为：
     * <pre>
     *   return CreativeTabLayout.tabX(this.imageWidth, tab.column(), tab.isAlignedRight());
     * </pre>
     *
     * <p>{@code MethodVisitor(ASM9, null)} 的所有 visitXxx 默认是空操作，
     * 因此原始指令不会写进输出；我们只在 {@code visitCode} 里向
     * {@code target} 写新指令。{@code visitMaxs/visitEnd} 仍需转发。
     */
    private static final class ReplaceGetTabX extends MethodVisitor {

        private final MethodVisitor target;

        ReplaceGetTabX(MethodVisitor target) {
            super(Opcodes.ASM9, null);
            this.target = target;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, 0);
            target.visitFieldInsn(Opcodes.GETFIELD, CONTAINER_SCREEN,
                    "imageWidth", "I");
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAB,
                    "column", "()I", false);
            target.visitVarInsn(Opcodes.ALOAD, 1);
            target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAB,
                    "isAlignedRight", "()Z", false);
            target.visitMethodInsn(Opcodes.INVOKESTATIC, DISPATCH_OWNER,
                    "tabX", "(IIZ)I", false);
            target.visitInsn(Opcodes.IRETURN);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // COMPUTE_MAXS 会重算；这里给 0 即可。
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public void visitParameter(String name, int access) {
            target.visitParameter(name, access);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return target.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            target.visitAttribute(attribute);
        }
    }

    // ── extractTabButton 的 clamp 重定向 ───────────────────────────────────

    /**
     * 把 {@code Mth.clamp(pos, 0, sprites.length)} 换成
     * {@code CreativeTabLayout.clampSprite(pos, 0, sprites.length)}。
     * 描述符同为 {@code (III)I}，栈高度不变。
     */
    private static final class ClampRedirect extends MethodVisitor {

        private final boolean[] applied;

        ClampRedirect(MethodVisitor delegate, boolean[] applied) {
            super(Opcodes.ASM9, delegate);
            this.applied = applied;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESTATIC
                    && "net/minecraft/util/Mth".equals(owner)
                    && "clamp".equals(name)
                    && "(III)I".equals(descriptor)) {
                applied[1] = true;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, DISPATCH_OWNER,
                        "clampSprite", "(III)I", false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    @Override
    public String toString() {
        return "MiliCreativeTabLayoutTransformer[" + ID + " → " + SCREEN + "]";
    }
}
