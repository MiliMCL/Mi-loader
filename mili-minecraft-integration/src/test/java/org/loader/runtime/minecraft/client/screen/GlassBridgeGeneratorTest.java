package org.loader.runtime.minecraft.client.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 玻璃桥接生成器 —— 验证运行期生成的桥接类形状正确、可定义、可调用。
 *
 * <h2>为什么它值得端到端验证</h2>
 * 桥接类是「Minecraft 类型 → Object 传参」的惟一通道，它的方法描述符
 * 必须与注入引擎生成的 {@code INVOKESTATIC} <b>逐字节一致</b>；实参装载
 * 顺序写错的表现不是编译失败，而是坐标错乱或首帧渲染时
 * {@code NoSuchMethodError}（堆栈指向 Minecraft，无法反推来源）。
 * 因此这里做两层验证：
 * <ol>
 *   <li><b>字节码形状</b>：onScreenRender 的指令序列必须精确是
 *       ALOAD 0 / ILOAD 1 / ILOAD 2 / FLOAD 3 / INVOKESTATIC 分发器 /
 *       RETURN —— 参数按位转发，不多不少；</li>
 *   <li><b>真实链接</b>：defineClass 后反射调用（graphics 传 null，
 *       分发器无会话空转），整条链路不得抛 LinkageError。</li>
 * </ol>
 *
 * <h2>为什么测试 CL 里要有一个假的 GuiGraphicsExtractor</h2>
 * {@code getDeclaredMethods()} 会急切解析方法描述符里的参数类型 ——
 * 桥接类签名引用 {@code net.minecraft.client.gui.GuiGraphicsExtractor}，
 * 测试环境没有它就会 {@code NoClassDefFoundError}。这是反射语义而非
 * 桥接类缺陷：JVM 只要「该名字可解析」即可，不需要真实实现。
 * 因此合成一个空壳类（与 ClientUiTransformersTest 合成假 TitleScreen
 * 同一方法论）。
 */
class GlassBridgeGeneratorTest {

    private static final String FAKE_GRAPHICS_INTERNAL =
            "net/minecraft/client/gui/GuiGraphicsExtractor";

    /** 空壳 GuiGraphicsExtractor —— 仅为描述符解析存在。 */
    private static byte[] emptyGraphicsClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                FAKE_GRAPHICS_INTERNAL, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 在同一 CL 中先定义空壳 graphics、再定义桥接类。 */
    private static Class<?> defineBridge() {
        byte[] graphics = emptyGraphicsClass();
        byte[] bridge = GlassBridgeGenerator.generate();
        final class Definer extends ClassLoader {
            Definer() {
                super(GlassBridgeGeneratorTest.class.getClassLoader());
            }

            Class<?> define() {
                defineClass(FAKE_GRAPHICS_INTERNAL.replace('/', '.'),
                        graphics, 0, graphics.length);
                return defineClass(GlassBridgeGenerator.BRIDGE_BINARY,
                        bridge, 0, bridge.length);
            }
        }
        return new Definer().define();
    }

    @Test
    @DisplayName("onScreenRender 描述符携带 MC 类型，参数按位转发给分发器")
    void bridgeShapeIsExact() throws Exception {
        Class<?> bridge = defineBridge();

        Method target = null;
        for (Method m : bridge.getDeclaredMethods()) {
            if (m.getName().equals("onScreenRender") && m.getParameterCount() == 4
                    && m.getParameterTypes()[1] == int.class
                    && m.getParameterTypes()[2] == int.class
                    && m.getParameterTypes()[3] == float.class) {
                target = m;
            }
        }
        assertNotNull(target, "必须存在 onScreenRender(_, int, int, float)");
        assertEquals(FAKE_GRAPHICS_INTERNAL.replace('/', '.'),
                target.getParameterTypes()[0].getName(),
                "首参类型必须逐字节是 GuiGraphicsExtractor");

        List<String> ops = new ArrayList<>();
        new ClassReader(GlassBridgeGenerator.generate()).accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name,
                                                     String descriptor, String sig,
                                                     String[] exceptions) {
                        if (!"onScreenRender".equals(name)) {
                            return null;
                        }
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitVarInsn(int opcode, int varIndex) {
                                ops.add("LOAD " + varIndex);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner,
                                                        String name2, String desc,
                                                        boolean itf) {
                                ops.add("CALL " + owner + "." + name2 + desc);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    ops.add("RETURN");
                                }
                            }
                        };
                    }
                }, 0);

        assertEquals(List.of(
                "LOAD 0", "LOAD 1", "LOAD 2", "LOAD 3",
                "CALL org/loader/runtime/minecraft/client/screen/MenuTransitionDispatch"
                        + ".onScreenRender(Ljava/lang/Object;IIF)V",
                "RETURN"), ops,
                "参数必须按位转发：graphics→Object，随后 int,int,float");
    }

    @Test
    @DisplayName("端到端：defineClass 后真实调用，分发器空闲空转不抛")
    void bridgeInvocableEndToEnd() throws Exception {
        Class<?> bridge = defineBridge();
        Method onScreenRender = null;
        for (Method m : bridge.getDeclaredMethods()) {
            if (m.getName().equals("onScreenRender")) {
                onScreenRender = m;
            }
        }
        assertNotNull(onScreenRender);
        onScreenRender.setAccessible(true);
        final Method m = onScreenRender;
        assertDoesNotThrow(() ->
                m.invoke(null, null, 7, 8, 9.0f));
    }
}
