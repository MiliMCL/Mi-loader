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
 *     protected void init()               { ScreenHostDispatch.onInit(this); }
 *     public void tick()                   { ScreenHostDispatch.onTick(this); }
 *     public boolean isPauseScreen()       { return ScreenHostDispatch.isPauseScreen(); }
 *     public boolean isInGameUi()          { return ScreenHostDispatch.isInGameUi(); }
 *     public void onClose()                { ScreenHostDispatch.onClose(this); super.onClose(); }
 * }
 * </pre>
 * 生命周期之外的渲染完全复用原版 Screen（背景 + widget 列表），
 * 不重写 render —— 重写它就要追 {@code GuiGraphics} 的签名漂移，得不偿失。
 * 暂停语义与背景风格由 {@code ScreenSpec} 的两个布尔标志决定。
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
        //
        // 【描述符必须与分发器一致】分发器把宿主参数声明为 Object
        // （它不能引用 Minecraft 类型），这里若按 Screen 发调用，
        // 运行时会抛
        //   NoSuchMethodError: void ScreenHostDispatch.onInit(Screen)
        // 而异常点在生成的类里，堆栈只有一行 "MiliScreenHost.init(Unknown Source)"，
        // 极难反推。这正是「按 K 打开界面 → 游戏直接退出」的根因。
        MethodVisitor init = cw.visitMethod(ACC_PROTECTED, "init", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onInit",
                "(Ljava/lang/Object;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        // public void tick() { ScreenHostDispatch.onTick(this); }
        //
        // 屏幕生命周期里唯一「每客户端 tick 一次」的钩子。实时文本
        // （性能面板的 TPS/FPS 行）在这里刷新；没有重写 render，
        // 因为 render 的签名随 GUI 体系漂移，而 tick 的签名从 1.13 起未变。
        MethodVisitor tick = cw.visitMethod(ACC_PUBLIC, "tick", "()V", null, null);
        tick.visitCode();
        tick.visitVarInsn(ALOAD, 0);
        tick.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onTick",
                "(Ljava/lang/Object;)V", false);
        tick.visitInsn(RETURN);
        tick.visitMaxs(1, 1);
        tick.visitEnd();

        // public boolean isPauseScreen() { return ScreenHostDispatch.isPauseScreen(); }
        //
        // 原版 Screen 默认返回 true。若不覆写，任何 Mili 自定义界面都会让
        // 单人游戏暂停 —— 对配置界面没问题，对性能面板则是致命语义：
        // 暂停 → integrated server 停止 tick → TPS 读数为 0。
        MethodVisitor pause = cw.visitMethod(ACC_PUBLIC, "isPauseScreen", "()Z", null, null);
        pause.visitCode();
        pause.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "isPauseScreen", "()Z", false);
        pause.visitInsn(IRETURN);
        pause.visitMaxs(1, 1);
        pause.visitEnd();

        // public boolean isInGameUi() { return ScreenHostDispatch.isInGameUi(); }
        //
        // true 时背景是半透明渐变而不是模糊/全景底图 —— HUD 模式。
        MethodVisitor hud = cw.visitMethod(ACC_PUBLIC, "isInGameUi", "()Z", null, null);
        hud.visitCode();
        hud.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "isInGameUi", "()Z", false);
        hud.visitInsn(IRETURN);
        hud.visitMaxs(1, 1);
        hud.visitEnd();

        // public void onClose() {
        //     ScreenHostDispatch.onClose(this);   // 先通知（Esc 语义）
        //     super.onClose();                    // 再由原版关闭（setScreen(null)）
        // }
        MethodVisitor close = cw.visitMethod(ACC_PUBLIC, "onClose", "()V", null, null);
        close.visitCode();
        close.visitVarInsn(ALOAD, 0);
        close.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onClose",
                "(Ljava/lang/Object;)V", false);
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
