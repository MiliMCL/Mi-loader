package org.loader.runtime.minecraft.client.screen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 运行时生成「Mod 列表屏幕」宿主类 —— {@code MiliModListScreen extends Screen}。
 *
 * <h2>为什么必须生成字节码</h2>
 * 集成模块与 Minecraft 零编译期耦合（模块约束），源码里写不出
 * {@code extends Screen}。而 {@code Gui.setScreen(Screen)} 只接受 Screen，
 * 所以「一个能真正显示在游戏里的列表界面」必须由字节码生成。
 *
 * <h2>生成的类形态（对应 Minecraft 26.2 的实际签名）</h2>
 * <pre>
 * public final class MiliModListScreen extends Screen {
 *     public MiliModListScreen(Component title) { super(title); }
 *     protected void init() { ModListScreenDispatch.onInit(this); }
 *     public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
 *         super.extractRenderState(g, mx, my, a);   // 原版背景
 *         ModListScreenDispatch.render(this, g, mx, my, a);
 *     }
 *     public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
 *         if (ModListScreenDispatch.onMouseClicked(e)) return true;
 *         return super.mouseClicked(e, doubleClick);
 *     }
 *     public boolean mouseScrolled(double x, double y, double sx, double sy) { ... }
 *     public boolean keyPressed(KeyEvent e) { ... }
 *     public boolean charTyped(CharacterEvent e) { ... }
 *     public void onClose() { ModListScreenDispatch.onClosed(this); super.onClose(); }
 * }
 * </pre>
 *
 * <h2>把 Minecraft 类型降级成 Object 传参</h2>
 * 分发器的方法声明参数为 {@code Object}：{@code GuiGraphicsExtractor} /
 * {@code MouseButtonEvent} 等都是 Object 的子类，字节码里把真实类型引用
 * 传给 {@code (Ljava/lang/Object;)V} 是合法且可验证的。
 * 这样分发器源码不需要任何 Minecraft 类型，也就不需要为每个版本重写。
 *
 * <h2>帧计算</h2>
 * {@link ClassWriter#COMPUTE_FRAMES} 默认会用 {@code Class.forName} 解析
 * 公共父类 —— 而 Screen 只存在于 MinecraftClassLoader，平台类加载器看不见，
 * 会抛 {@code TypeNotPresentException}。本类的分支合并点两侧全是 int
 * （boolean），不需要真正的公共父类计算，因此直接返回 Object 即可，
 * 既不牺牲正确性也避开了跨加载器解析。
 */
final class MiliModListScreenGenerator implements Opcodes {

    static final String HOST_INTERNAL =
            "org/loader/runtime/minecraft/client/screen/MiliModListScreen";
    static final String HOST_BINARY =
            "org.loader.runtime.minecraft.client.screen.MiliModListScreen";

    private static final String SCREEN_INTERNAL = "net/minecraft/client/gui/screens/Screen";
    private static final String COMPONENT_INTERNAL = "net/minecraft/network/chat/Component";
    private static final String GRAPHICS_INTERNAL =
            "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String MOUSE_EVENT_INTERNAL =
            "net/minecraft/client/input/MouseButtonEvent";
    private static final String KEY_EVENT_INTERNAL =
            "net/minecraft/client/input/KeyEvent";
    private static final String CHAR_EVENT_INTERNAL =
            "net/minecraft/client/input/CharacterEvent";

    private static final String DISPATCH_INTERNAL = TypeInternal.of(ModListScreenDispatch.class);

    private MiliModListScreenGenerator() {
    }

    /** 生成宿主类字节码（无状态，结果由调用方缓存）。 */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // 见类注释「帧计算」：我们没有任何需要真实层级的分支。
                return "java/lang/Object";
            }
        };

        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                HOST_INTERNAL, null, SCREEN_INTERNAL, null);

        ctor(cw);
        initMethod(cw);
        renderMethod(cw);
        mouseClicked(cw);
        mouseScrolled(cw);
        keyPressed(cw);
        charTyped(cw);
        onClose(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    // ── 构造器 ──────────────────────────────────────────────────────────────

    private static void ctor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + COMPONENT_INTERNAL + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "<init>",
                "(L" + COMPONENT_INTERNAL + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    // ── init() ─────────────────────────────────────────────────────────────

    private static void initMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PROTECTED, "init", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onInit",
                "(Ljava/lang/Object;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    // ── extractRenderState(GuiGraphicsExtractor, int, int, float) ──────────

    private static void renderMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "extractRenderState",
                "(L" + GRAPHICS_INTERNAL + ";IIF)V", null, null);
        mv.visitCode();
        // super.extractRenderState(g, mx, my, a)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(FLOAD, 4);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "extractRenderState",
                "(L" + GRAPHICS_INTERNAL + ";IIF)V", false);
        // ModListScreenDispatch.render(this, g, mx, my, a)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(FLOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "render",
                "(Ljava/lang/Object;Ljava/lang/Object;IIF)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(5, 5);
        mv.visitEnd();
    }

    // ── mouseClicked(MouseButtonEvent, boolean) ─────────────────────────────

    private static void mouseClicked(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "mouseClicked",
                "(L" + MOUSE_EVENT_INTERNAL + ";Z)Z", null, null);
        mv.visitCode();
        Label fallback = new Label();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onMouseClicked",
                "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, fallback);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "mouseClicked",
                "(L" + MOUSE_EVENT_INTERNAL + ";Z)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    // ── mouseScrolled(double, double, double, double) ───────────────────────

    private static void mouseScrolled(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "mouseScrolled",
                "(DDDD)Z", null, null);
        mv.visitCode();
        Label fallback = new Label();

        for (int i = 1; i <= 7; i += 2) {
            mv.visitVarInsn(DLOAD, i);
        }
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onScroll", "(DDDD)Z", false);
        mv.visitJumpInsn(IFEQ, fallback);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        for (int i = 1; i <= 7; i += 2) {
            mv.visitVarInsn(DLOAD, i);
        }
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "mouseScrolled", "(DDDD)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(9, 9);
        mv.visitEnd();
    }

    // ── keyPressed(KeyEvent) ────────────────────────────────────────────────

    private static void keyPressed(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "keyPressed",
                "(L" + KEY_EVENT_INTERNAL + ";)Z", null, null);
        mv.visitCode();
        Label fallback = new Label();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onKeyPressed",
                "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, fallback);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "keyPressed",
                "(L" + KEY_EVENT_INTERNAL + ";)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    // ── charTyped(CharacterEvent) ───────────────────────────────────────────

    private static void charTyped(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "charTyped",
                "(L" + CHAR_EVENT_INTERNAL + ";)Z", null, null);
        mv.visitCode();
        Label fallback = new Label();

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onCharTyped",
                "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, fallback);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(fallback);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "charTyped",
                "(L" + CHAR_EVENT_INTERNAL + ";)Z", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    // ── onClose() ───────────────────────────────────────────────────────────

    private static void onClose(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "onClose", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onClosed",
                "(Ljava/lang/Object;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, SCREEN_INTERNAL, "onClose", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** 极简内部名工具 —— 避免为此引入 ASM commons。 */
    private static final class TypeInternal {
        static String of(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    }
}
