package org.loader.runtime.minecraft.client.screen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 运行时生成「液态玻璃渲染桥接类」—— {@code MiliGlassBridge}。
 *
 * <h2>它为什么必须存在</h2>
 * 渲染钩子注入在 {@code Screen#extractRenderStateWithTooltipAndSubtitles}
 * 的 RETURN 上，需要捕获宿主实参 {@code (GuiGraphicsExtractor, int, int, float)}。
 * 捕获要求回调描述符与宿主实参<b>逐位严格相等</b>，因此回调方法签名里
 * 必须出现 {@code Lnet/minecraft/client/gui/GuiGraphicsExtractor;} ——
 * 而平台源码与 Minecraft 零编译期耦合，写不出这个类型。
 *
 * <p>唯一出路：平台在运行期生成一个桥接类的字节码（本类），把
 * Minecraft 类型在<b>字节码层面</b>降级为 {@code Object} 传给平台分发器
 * —— 与 {@link MiliModListScreenGenerator}「把 Minecraft 类型降级成
 * Object 传参」是同一手法、同一理由。
 *
 * <h2>生成的类形态</h2>
 * <pre>
 * public final class MiliGlassBridge {
 *     public MiliGlassBridge() {}
 *     public static void onScreenRender(GuiGraphicsExtractor g, int mx, int my, float a) {
 *         MenuTransitionDispatch.onScreenRender((Object) g, mx, my, a);
 *     }
 * }
 * </pre>
 *
 * <h2>定义位置（关键）</h2>
 * 桥接类被被转换的 {@code Screen}（定义于 MinecraftClassLoader）以
 * {@code INVOKESTATIC} 引用，解析沿 Screen 自己的加载器进行 —— 因此它
 * 必须经 {@code MinecraftClassLoader#definePlatformBridge} 定义到
 * <b>MinecraftClassLoader 本体</b>，而不是 {@link GameHostDefiner} 的
 * 子加载器（子加载器对父不可见）。见 MinecraftClassLoader 该方法的注释。
 */
final class GlassBridgeGenerator implements Opcodes {

    static final String BRIDGE_INTERNAL =
            "org/loader/runtime/minecraft/client/screen/MiliGlassBridge";
    static final String BRIDGE_BINARY =
            "org.loader.runtime.minecraft.client.screen.MiliGlassBridge";

    private static final String GRAPHICS_INTERNAL =
            "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String DISPATCH_INTERNAL = TypeInternal.of(MenuTransitionDispatch.class);

    private GlassBridgeGenerator() {
    }

    /** 生成桥接类字节码（无状态；定义由调用方负责并缓存）。 */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                BRIDGE_INTERNAL, null, "java/lang/Object", null);

        ctor(cw);
        onScreenRender(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void ctor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** {@code onScreenRender(GuiGraphicsExtractor, int, int, float)V}。 */
    private static void onScreenRender(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onScreenRender",
                "(L" + GRAPHICS_INTERNAL + ";IIF)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(FLOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, DISPATCH_INTERNAL, "onScreenRender",
                "(Ljava/lang/Object;IIF)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    /** 极简内部名工具 —— 与 MiliModListScreenGenerator 同款，避免引入 ASM commons。 */
    private static final class TypeInternal {
        static String of(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    }
}
