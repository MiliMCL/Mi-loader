package org.loader.runtime.minecraft.transform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.minecraft.client.screen.MenuTransitionDispatch;
import org.loader.runtime.minecraft.reflect.Reflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 液态玻璃转场转换器 —— 用<b>真实生成的字节码</b>验证注入形状与端到端行为。
 *
 * <h2>与 ClientUiTransformersTest 同一方法论</h2>
 * 合成与原版签名一致的假目标类 → 跑真实转换器 → 计数回调调用 →
 * defineClass 后实际调用。它不证明真实 Minecraft 26.2 上的端到端行为；
 * 那由 CI 的符号校验与真实 smoke test 负责。
 *
 * <h2>这里最值钱的三条断言</h2>
 * <ol>
 *   <li>{@code Screen#init(II)} 的合成版本带一个 <b>early return</b>：
 *       RETURN 注入必须命中<b>每一条</b>退出路径（回调计数 = 2）。</li>
 *   <li><b>桥接类定义先于注入</b>：转换器必须在注入渲染钩子前把真实生成的
 *       {@code MiliGlassBridge} 定义进「游戏 ClassLoader」—— 本测试用
 *       带 {@code definePlatformBridge} 的宿主加载器复刻生产契约，
 *       端到端调用走<b>真桥接类</b>（不是桩）。</li>
 *   <li><b>定义失败绝不注入</b>：宿主没有 definePlatformBridge 时
 *       transform 必须返回 Skipped —— 绝不允许埋一颗
 *       NoClassDefFoundError 地雷（2026-10-24 真机崩溃的直接根因）。</li>
 * </ol>
 */
class MiliMenuTransitionTransformerTest {

    // ── 合成目标类 ──────────────────────────────────────────────────────────

    /**
     * 与原版签名一致的 Screen：{@code init(II)V}（public final，带两条
     * 退出路径）+ {@code extractRenderStateWithTooltipAndSubtitles}（无
     * early return，返回值 void，占位实参 4 个槽位）。
     */
    private static byte[] screenClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.owner(), null,
                "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V",
                null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object",
                "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // init(int,int)V —— 先判一个恒假条件 early return，再走到方法尾 return
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MiliSymbol.SCREEN_INIT.name(),
                MiliSymbol.SCREEN_INIT.descriptor(), null, null);
        init.visitCode();
        init.visitInsn(Opcodes.ICONST_0);
        Label notEarly = new Label();
        init.visitJumpInsn(Opcodes.IFEQ, notEarly);
        init.visitInsn(Opcodes.RETURN);         // early return 路径
        init.visitLabel(notEarly);
        init.visitInsn(Opcodes.RETURN);         // 正常路径
        init.visitMaxs(1, 3);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.name(),
                MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.descriptor(), null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 5);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 空 GuiGraphicsExtractor 桩 —— 让桥接回调的描述符类型在测试 CL 可解析。 */
    private static byte[] graphicsClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "net/minecraft/client/gui/GuiGraphicsExtractor", null,
                "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ── 测试用「游戏 ClassLoader」宿主 ──────────────────────────────────────

    /**
     * 复刻 {@code MinecraftClassLoader#definePlatformBridge} 的公开契约：
     * 平台侧反射调用它把运行期生成的桥接类定义进「游戏 CL」本体。
     * 定义成功与否通过 {@link #bridgeDefined} 观测。
     *
     * <p>必须是 <b>public</b>：{@code ensureBridgeDefined} 用
     * {@code getMethod(...).invoke(...)} 调用它 —— 私有类的 public 方法
     * 反射调用会吃 IllegalAccessException，而生产环境的
     * MinecraftClassLoader 是 public 类，没有这道墙。
     */
    public static final class BridgeHostLoader extends ClassLoader {
        volatile boolean bridgeDefined;

        BridgeHostLoader() {
            super(MiliMenuTransitionTransformerTest.class.getClassLoader());
        }

        @SuppressWarnings("unused")
        public Class<?> definePlatformBridge(String name, byte[] bytes) {
            Class<?> c = defineClass(name, bytes, 0, bytes.length);
            bridgeDefined = true;
            return c;
        }
    }

    /** 没有 definePlatformBridge 的宿主 —— 复刻「定义失败」的生产降级场景。 */
    private static final class BridgelessHostLoader extends ClassLoader {
        BridgelessHostLoader() {
            super(MiliMenuTransitionTransformerTest.class.getClassLoader());
        }
    }

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                MiliMenuTransitionTransformerTest.class.getClassLoader(),
                MiliSymbol.MINECRAFT_VERSION,
                TransformationEnvironment.RuntimeEnvironmentValue.CLIENT,
                "0.1.0-test");
    }

    private static TransformationContext context(String owner, byte[] bytes) {
        return new TransformationContext(owner, bytes, env(),
                TransformationPhase.CORE, null, MiliMenuTransitionTransformer.ID);
    }

    private static int countCalls(byte[] bytes, String targetMethod,
                                  String targetDesc,
                                  String owner, String name, String desc) {
        AtomicInteger count = new AtomicInteger();
        new org.objectweb.asm.ClassReader(bytes).accept(
                new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.MethodVisitor visitMethod(
                            int access, String mName, String mDesc,
                            String sig, String[] ex) {
                        if (!targetMethod.equals(mName) || !targetDesc.equals(mDesc)) {
                            return null;
                        }
                        return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String cOwner,
                                                        String cName, String cDesc,
                                                        boolean itf) {
                                if (owner.equals(cOwner) && name.equals(cName)
                                        && desc.equals(cDesc)) {
                                    count.incrementAndGet();
                                }
                            }
                        };
                    }
                }, 0);
        return count.get();
    }

    private static String screenFqn() {
        return MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.owner().replace('/', '.');
    }

    @BeforeEach
    void resetBridgeState() {
        MenuTransitionDispatch.resetBridgeForTest();
        System.clearProperty("mili.menu.transition");
    }

    @AfterEach
    void restoreEnvironment() {
        Reflect.useGameClassLoader(null);
        MenuTransitionDispatch.resetBridgeForTest();
        System.clearProperty("mili.menu.transition");
    }

    // ── init RETURN 钩子 ────────────────────────────────────────────────────

    @Test
    @DisplayName("Screen#init(II) 的每条退出路径都注入 onScreenInitReturn（early return 不漏）")
    void initReturnInjectsEveryExitPath() throws Exception {
        BridgeHostLoader host = new BridgeHostLoader();
        Reflect.useGameClassLoader(host);
        MiliMenuTransitionTransformer transformer = new MiliMenuTransitionTransformer();
        byte[] transformed = ((TransformationResult.Transformed) transformer
                .transform(context(MiliSymbol.SCREEN_INIT.owner(), screenClass())))
                .bytecode();

        assertEquals(2, countCalls(transformed,
                MiliSymbol.SCREEN_INIT.name(),
                MiliSymbol.SCREEN_INIT.descriptor(),
                MiliMenuTransitionTransformer.DISPATCH_OWNER,
                MiliMenuTransitionTransformer.INIT_CALLBACK,
                "()V"),
                "两条退出路径必须各注入一次 —— 漏一条就是部分路径按钮失控");
        assertTrue(host.bridgeDefined,
                "转换期必须已把真实生成的桥接类定义进游戏 ClassLoader");
    }

    // ── Screen 渲染 RETURN 钩子（真实桥接类端到端） ─────────────────────────

    @Test
    @DisplayName("渲染出口注入一次桥接回调；真桥接类定义→解析→端到端空转不炸")
    void screenRenderInjectsBridgeCallback() throws Exception {
        BridgeHostLoader host = new BridgeHostLoader();
        Reflect.useGameClassLoader(host);
        MiliMenuTransitionTransformer transformer = new MiliMenuTransitionTransformer();
        byte[] transformed = ((TransformationResult.Transformed) transformer
                .transform(context(MiliSymbol.SCREEN_INIT.owner(), screenClass())))
                .bytecode();

        assertEquals(1, countCalls(transformed,
                MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.name(),
                MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.descriptor(),
                MiliMenuTransitionTransformer.BRIDGE_OWNER,
                MiliMenuTransitionTransformer.RENDER_CALLBACK,
                "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"),
                "渲染方法无 early return，应恰好注入一次");

        // 端到端：空 graphics 桩 + 转换后的 Screen 全部 define 进宿主
        // （真桥接类已在转换期由 ensureBridgeDefinedEager 定义进同一宿主），
        // 真实调用两个注入点 —— 回调链走到分发器（无会话，空转）必须安静返回。
        final class Definer extends ClassLoader {
            Definer() {
                super(host);
            }

            Class<?> define(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        Definer cl = new Definer();
        cl.define("net.minecraft.client.gui.GuiGraphicsExtractor", graphicsClass());
        Class<?> screen = cl.define(screenFqn(), transformed);

        Object instance = screen.getDeclaredConstructor().newInstance();
        screen.getMethod(MiliSymbol.SCREEN_INIT.name(), int.class, int.class)
                .invoke(instance, 426, 240);
        screen.getMethod(MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.name(),
                cl.loadClass("net.minecraft.client.gui.GuiGraphicsExtractor"),
                int.class, int.class, float.class)
                .invoke(instance, null, 1, 2, 3.0f);
        assertFalse(MenuTransitionDispatch.isActive(), "无会话时渲染回调必须保持空闲");
    }

    // ── 失败降级：绝不埋地雷 ────────────────────────────────────────────────

    @Test
    @DisplayName("桥接类定义失败 → 整体 Skipped，绝不注入渲染钩子")
    void bridgeDefineFailureSkipsTransformation() {
        Reflect.useGameClassLoader(new BridgelessHostLoader());
        MiliMenuTransitionTransformer transformer = new MiliMenuTransitionTransformer();
        TransformationResult result = transformer.transform(
                context(MiliSymbol.SCREEN_INIT.owner(), screenClass()));
        Assertions.assertInstanceOf(TransformationResult.Skipped.class, result,
                "定义失败时必须 Skipped —— 绝不允许注入解析必炸的 INVOKESTATIC");
    }

    // ── 开关与匹配 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("mili.menu.transition=false 时整体 Skipped，两个锚点都不注入")
    void disabledPropertySkipsTransformation() {
        System.setProperty("mili.menu.transition", "false");
        MiliMenuTransitionTransformer transformer = new MiliMenuTransitionTransformer();
        TransformationResult r = transformer.transform(
                context(MiliSymbol.SCREEN_INIT.owner(), screenClass()));
        Assertions.assertInstanceOf(TransformationResult.Skipped.class, r);
    }

    @Test
    @DisplayName("matches 只命中 Screen；TitleScreen 不再是锚点（管线覆盖语义）")
    void matchersAreExact() {
        MiliMenuTransitionTransformer transformer = new MiliMenuTransitionTransformer();
        assertTrue(transformer.matches(MiliSymbol.SCREEN_INIT.owner()));
        assertTrue(transformer.matches(MiliSymbol.SCREEN_EXTRACT_RENDER_STATE.owner()));
        assertFalse(transformer.matches(MiliSymbol.TITLE_SCREEN_INIT.owner()),
                "init 锚点已挪到 Screen 基类 —— TitleScreen 归 Mods 按钮转换器管");
        assertFalse(transformer.matches("net/minecraft/server/MinecraftServer"));
        assertFalse(transformer.matches("net/minecraft/client/Minecraft"));
        assertTrue(MiliSymbol.isClientOnly(MiliSymbol.SCREEN_EXTRACT_RENDER_STATE));
        assertTrue(MiliSymbol.isClientOnly(MiliSymbol.SCREEN_INIT));
    }
}
