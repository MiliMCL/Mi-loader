package org.loader.runtime.minecraft.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端 UI 注入链测试 —— 品牌替换与主界面 Mods 按钮。
 *
 * <h2>这条链在生产中曾经是死的</h2>
 * 转换管线从未在生产启动路径上装配（见 MinecraftGameProvider 的根因
 * 记录），两个转换器的注入一次都没执行过：F3 显示原版客户端、
 * 主界面没有 mod 列表入口，且日志无任何报错。
 *
 * <p>本测试用<b>真实生成的字节码</b>验证「注入产出了正确的指令序列，
 * 且转换后的类真的按预期执行」——包括把转换后的字节码 defineClass
 * 后实际调用。它不证明真实 Minecraft 26.2 上的端到端行为；那由 CI
 * 的符号校验与真实 smoke test 负责。
 */
class ClientUiTransformersTest {

    // ── 合成目标类生成 ──────────────────────────────────────────────────────

    /** 与原版语义一致的 ClientBrandRetriever：恒返回 "vanilla"。 */
    private static byte[] brandClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.CLIENT_BRAND.owner(), null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VANILLA_NAME", "Ljava/lang/String;", null, "vanilla").visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                MiliSymbol.CLIENT_BRAND.name(),
                MiliSymbol.CLIENT_BRAND.descriptor(), null, null);
        mv.visitCode();
        mv.visitLdcInsn("vanilla");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 与原版签名一致的 TitleScreen：init()V 空实现。 */
    private static byte[] titleScreenClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.TITLE_SCREEN_INIT.owner(), null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED,
                MiliSymbol.TITLE_SCREEN_INIT.name(),
                MiliSymbol.TITLE_SCREEN_INIT.descriptor(), null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                ClientUiTransformersTest.class.getClassLoader(),
                MiliSymbol.MINECRAFT_VERSION,
                TransformationEnvironment.RuntimeEnvironmentValue.CLIENT,
                "0.1.0-test");
    }

    private static TransformationContext context(String owner, byte[] bytes, String id) {
        return new TransformationContext(
                owner, bytes, env(), TransformationPhase.CORE, null, id);
    }

    /** 统计目标方法里对 (owner, name, desc) 的静态调用次数。 */
    private static int countCalls(byte[] bytes, String targetMethod,
                                  String owner, String name, String desc) {
        AtomicInteger count = new AtomicInteger();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String mName, String mDesc,
                                             String sig, String[] ex) {
                if (!targetMethod.equals(mName)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
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

    /** 在独立 ClassLoader 中定义并直接返回该类（模拟 MinecraftClassLoader 的产物）。 */
    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(ClientUiTransformersTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }

    // ── 品牌替换 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("品牌转换器把 getClientModName 的返回值替换为 Mili-loader")
    void brandTransformerReplacesReturn() throws Exception {
        MiliClientBrandTransformer transformer = new MiliClientBrandTransformer();
        byte[] transformed = ((TransformationResult.Transformed) transformer
                .transform(context(MiliSymbol.CLIENT_BRAND.owner(), brandClass(),
                        MiliClientBrandTransformer.ID)))
                .bytecode();

        assertEquals(1, countCalls(transformed,
                MiliSymbol.CLIENT_BRAND.name(),
                MiliClientBrandTransformer.DISPATCH_OWNER,
                MiliClientBrandTransformer.CALLBACK,
                MiliClientBrandTransformer.CALLBACK_DESCRIPTOR),
                "应恰好注入一次 MODIFY_RETURN 回调");

        // 端到端：defineClass 后实际调用 —— 必须返回 Mili 而非 vanilla
        Class<?> c = define(MiliSymbol.CLIENT_BRAND.owner().replace('/', '.'), transformed);
        assertEquals("Mili-loader",
                c.getMethod("getClientModName").invoke(null),
                "转换后的 getClientModName 必须返回 Mili 品牌");
    }

    @Test
    @DisplayName("品牌转换器对目标缺失抛 TargetNotFound，绝不静默跳过")
    void brandTransformerFailsLoudlyOnMissingTarget() {
        MiliClientBrandTransformer transformer = new MiliClientBrandTransformer();
        byte[] wrong = titleScreenClass(); // 没有 getClientModName
        var ctx = context(MiliSymbol.CLIENT_BRAND.owner(), wrong,
                MiliClientBrandTransformer.ID);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> transformer.transform(ctx));
    }

    // ── 主界面注入 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("主界面转换器在 init 头部注入一次 onTitleScreenInit")
    void titleScreenTransformerInjectsHeadCallback() throws Exception {
        MiliTitleScreenTransformer transformer = new MiliTitleScreenTransformer();
        byte[] transformed = ((TransformationResult.Transformed) transformer
                .transform(context(MiliSymbol.TITLE_SCREEN_INIT.owner(), titleScreenClass(),
                        MiliTitleScreenTransformer.ID)))
                .bytecode();

        assertEquals(1, countCalls(transformed,
                MiliSymbol.TITLE_SCREEN_INIT.name(),
                MiliTitleScreenTransformer.DISPATCH_OWNER,
                MiliTitleScreenTransformer.CALLBACK,
                "()V"),
                "应恰好注入一次 HEAD 回调");

        // 端到端：回调在本测试环境无 Minecraft，必须被分发器吞掉而非抛出
        Class<?> c = define(
                MiliSymbol.TITLE_SCREEN_INIT.owner().replace('/', '.'), transformed);
        c.getDeclaredMethod("init").setAccessible(true);
        c.getDeclaredConstructor().newInstance(); // 类初始化不炸
        // 调用 init 本身：onTitleScreenInit 内部失败必须静默（无 MC 环境）
        Object instance = c.getDeclaredConstructor().newInstance();
        c.getDeclaredMethod("init").invoke(instance);
    }

    // ── 匹配与符号一致性 ────────────────────────────────────────────────────

    @Test
    @DisplayName("两个转换器只匹配各自的 owner，符号均为客户端专属")
    void matchersAndClientOnlyFlags() {
        MiliClientBrandTransformer brand = new MiliClientBrandTransformer();
        MiliTitleScreenTransformer title = new MiliTitleScreenTransformer();

        assertTrue(brand.matches(MiliSymbol.CLIENT_BRAND.owner()));
        assertFalse(brand.matches(MiliSymbol.TITLE_SCREEN_INIT.owner()));
        assertTrue(title.matches(MiliSymbol.TITLE_SCREEN_INIT.owner()));
        assertFalse(title.matches("net/minecraft/server/MinecraftServer"));

        assertTrue(MiliSymbol.isClientOnly(MiliSymbol.CLIENT_BRAND));
        assertTrue(MiliSymbol.isClientOnly(MiliSymbol.TITLE_SCREEN_INIT));
    }
}
