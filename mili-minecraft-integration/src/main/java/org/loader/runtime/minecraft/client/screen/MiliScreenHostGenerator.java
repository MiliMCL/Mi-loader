package org.loader.runtime.minecraft.client.screen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 运行时生成 {@code Screen} 子类 —— Mili 自定义屏幕的宿主。
 *
 * <h2>为什么要在运行时生成一个 Minecraft 子类</h2>
 * 集成模块与 Minecraft 零编译期耦合（模块约束），因此无法在源码里
 * {@code extends Screen}。但「自定义屏幕」又必须是一个 Screen 子类
 * （原版 {@code Gui.setScreen(Screen)} 只接受 Screen）。
 * 解法与平台的整体思路一致：源码层只写<b>分发器</b>（本包的
 * {@link ScreenHostDispatch}），字节码层用 ASM 生成一个最小宿主类，
 * 把生命周期回调转交给分发器。
 *
 * <h2>生成的类形态</h2>
 * <pre>
 * class MiliScreenHost extends net.minecraft.client.gui.screens.Screen {
 *     public MiliScreenHost(Component title) { super(title); }
 *     protected void init()    { ScreenHostDispatch.onInit(this); }
 *     public void onClose()    { ScreenHostDispatch.onClose(this); super.onClose(); }
 * }
 * </pre>
 * 生命周期之外的渲染完全复用原版 Screen（背景 + widget 列表），
 * 不重写 render —— 重写它就要追 {@code GuiGraphics} 的签名漂移，
 * 得不偿失。
 *
 * <h2>定义位置</h2>
 * 生成的类必须由<b>游戏类加载器</b>定义（其父类 Screen 只在那里可见）；
 * 而它引用的 {@code ScreenHostDispatch} 在父加载器（AppClassLoader），
 * 子加载器按委派模型能看到 —— 两侧都成立。定义由
 * {@link ScreenServiceBridge} 通过标准 {@code defineClass} 反射完成。
 */
final class MiliScreenHostGenerator implements Opcodes {

    static final String HOST_INTERNAL =
            "org/loader/runtime/minecraft/client/screen/MiliScreenHost";
    static final String HOST_BINARY =
            "org.loader.runtime.minecraft.client.screen.MiliScreenHost";

    static final String SCREEN_INTERNAL =
            "net/minecraft/client/gui/screens/Screen";
    static final String COMPONENT_INTERNAL =
            "net/minecraft/network/chat/Component";
    private static final String DISPATCH_INTERNAL =
            TypeInternal.of(ScreenHostDispatch.class);

    private MiliScreenHostGenerator() {
    }

    /** 生成宿主类字节码（无状态，可重复调用；结果由调用方缓存）。 */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                HOST_INTERNAL, null, SCREEN_INTERNAL, null);

        // public MiliScreenHost(Component title) { super(title); }
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + COMPONENT_INTERNAL + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "<init>",
                "(L" + COMPONENT_INTERNAL + ";)V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(2, 2);
        ctor.visitEnd();

        // protected void init() { ScreenHostDispatch.onInit(this); }
        //
        // 不调用 super：原版 Screen.init() 是空的，widget 全部由
        // 子类在 init 里构建 —— 我们构建 widget 的职责在分发器里。
        MethodVisitor init = cw.visitMethod(ACC_PROTECTED, "init", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onInit",
                "(L" + SCREEN_INTERNAL + ";)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public void onClose() {
        //     ScreenHostDispatch.onClose(this);   // 先通知（Esc 语义）
        //     super.onClose();                    // 再由原版关闭（setScreen(null)）
        // }
        MethodVisitor close = cw.visitMethod(ACC_PUBLIC, "onClose", "()V", null, null);
        close.visitCode();
        close.visitVarInsn(ALOAD, 0);
        close.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onClose",
                "(L" + SCREEN_INTERNAL + ";)V", false);
        close.visitVarInsn(ALOAD, 0);
        close.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "onClose", "()V", false);
        close.visitInsn(RETURN);
        close.visitMaxs(1, 1);
        close.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 极简内部名工具 —— 避免为此引入 ASM commons。 */
    private static final class TypeInternal {
        static String of(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    }
}
