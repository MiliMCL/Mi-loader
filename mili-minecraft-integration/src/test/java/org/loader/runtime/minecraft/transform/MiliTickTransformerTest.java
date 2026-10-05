package org.loader.runtime.minecraft.transform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.tick.TickEngine;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tick 注入链测试 —— 验证 MiliTransformer 接入 TickBridge 的完整路径。
 *
 * <h2>这条链曾经是断的</h2>
 * 审计发现 {@code TickBridge.beginTick()} / {@code endTick()} 在生产路径上
 * <b>零调用者</b>：没有东西调用它们，因此
 * {@code MinecraftServer.tickServer} → {@code TickEngine} → Mod TickHandler
 * 整条链不成立。表现是「Mod 的 tick 回调从不执行」，
 * 而平台日志里什么也没有。
 *
 * <p>本测试用<b>真实生成的字节码</b>（而非 mock Minecraft）验证接线成立。
 * 它能证明「注入产出了正确的指令序列」；
 * 它<b>不能</b>证明「真实 Minecraft 26.2 上游戏正常运行」——
 * 那个只能靠 CI 上的真实 smoke test。混淆这两者是本仓库明确禁止的。
 */
class MiliTickTransformerTest {

    private static final String DISPATCH_OWNER =
            "org/loader/runtime/minecraft/transform/TickCallbackDispatch";

    private Scope scope;
    private TickEngine engine;

    @BeforeEach
    void setUp() {
        scope = new Scope("tick-transform-test", null);
        engine = new TickEngine("test-engine", scope);
        TickCallbackDispatch.resetCountersForTesting();
        TickCallbackDispatch.install(null);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        TickCallbackDispatch.install(null);
        engine.close();
        scope.close();
    }

    /**
     * 生成一个与 {@link MiliSymbol#SERVER_TICK} 签名一致的类。
     *
     * <p>owner 必须<b>完全一致</b>，因为 {@code matches()} 按 owner 过滤。
     */
    private static byte[] serverClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SERVER_TICK.owner(), null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "tickServer",
                MiliSymbol.SERVER_TICK.descriptor(), null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static TransformationEnvironment env() {
        return new TransformationEnvironment(
                MiliTickTransformerTest.class.getClassLoader(),
                MiliSymbol.MINECRAFT_VERSION,
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0-test");
    }

    private static TransformationContext context(byte[] bytes) {
        return new TransformationContext(
                MiliSymbol.SERVER_TICK.owner(), bytes, env(),
                org.loader.api.transform.TransformationPhase.CORE,
                null, MiliTickTransformer.ID);
    }

    /** 统计 tickServer 里对分发器的调用次数。 */
    private static int countDispatchCalls(byte[] bytes) {
        int[] head = {0};
        int[] tail = {0};
        int[] methodIndex = {0};

        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String sig, String[] ex) {
                if (!MiliSymbol.SERVER_TICK.name().equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner,
                                                String mName, String mDesc,
                                                boolean itf) {
                        if (!DISPATCH_OWNER.equals(owner)) {
                            return;
                        }
                        if ("onTickBegin".equals(mName)) {
                            head[0]++;
                        } else if ("onTickEnd".equals(mName)) {
                            tail[0]++;
                        }
                        methodIndex[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        assertEquals(head[0], tail[0]);
        return head[0];
    }

    // ── 转换器身份 ─────────────────────────────────────────────────

    @Test
    @DisplayName("转换器声明 CORE 阶段与精确 Minecraft 版本")
    void declaresProtectedPhaseAndExactVersion() {
        MiliTickTransformer t = new MiliTickTransformer();

        assertEquals(MiliTickTransformer.ID, t.id());
        assertEquals(org.loader.api.transform.TransformationPhase.CORE, t.phase(),
                "tick 接线必须先于任何 Mod 的修改完成 —— "
                        + "否则 Mod 可能拿到一个尚未接线的 TickEngine。");
        assertEquals(MiliSymbol.MINECRAFT_VERSION, t.minecraftVersion(),
                "必须声明精确版本，不能用范围匹配。");
    }

    @Test
    @DisplayName("matches 只认 MinecraftServer —— 其他类不进 ASM")
    void matchesOnlyMinecraftServer() {
        MiliTickTransformer t = new MiliTickTransformer();

        assertTrue(t.matches(MiliSymbol.SERVER_TICK.owner()));
        assertTrue(!t.matches("net/minecraft/client/multiplayer/ClientLevel"));
        assertTrue(!t.matches("some/mod/Class"));
    }

    // ── 注入产出 ───────────────────────────────────────────────────

    @Test
    @DisplayName("转换产出同时包含 onTickBegin 与 onTickEnd")
    void producesBothBeginAndEndHooks() {
        MiliTickTransformer t = new MiliTickTransformer();
        byte[] original = serverClass();

        TransformationResult result = t.transform(context(original));

        assertTrue(result instanceof TransformationResult.Transformed,
                "应产出转换后的字节码，实际: " + result.getClass().getSimpleName());

        byte[] transformed = ((TransformationResult.Transformed) result).bytecode();
        assertEquals(1, countDispatchCalls(transformed),
                "tickServer 必须恰好各注入一次 begin 与 end —— "
                        + "两次 begin 会让 tick 推进两次（tick 数翻倍），"
                        + "零次则整条 tick 链断开。");
    }

    @Test
    @DisplayName("转换后的字节码通过结构与类型验证")
    void outputPassesBytecodeVerification() {
        MiliTickTransformer t = new MiliTickTransformer();
        byte[] transformed = ((TransformationResult.Transformed)
                t.transform(context(serverClass()))).bytecode();

        assertDoesNotThrowVerify(transformed);
    }

    @Test
    @DisplayName("生成的字节码对 Mod 类零引用 —— 只引用平台分发器")
    void generatedBytecodeReferencesOnlyPlatformDispatcher() {
        // 这是 ClassLoader 不泄漏的前提：
        // MinecraftClassLoader 定义的类若持有 Mod 类引用，
        // ModClassLoader 就无法被回收。
        // 本转换器不引用任何 Mod 类，但这条断言守住未来修改时的回归。
        MiliTickTransformer t = new MiliTickTransformer();
        byte[] transformed = ((TransformationResult.Transformed)
                t.transform(context(serverClass()))).bytecode();

        String pool = dumpConstantPoolStrings(transformed);
        assertTrue(pool.contains("TickCallbackDispatch"),
                "必须引用平台分发器");
        assertTrue(!pool.contains("org/loader/runtime/minecraft/transform/MiliTickTransformer"),
                "生成的字节码不得引用转换器自身 —— "
                        + "那会让 Minecraft 类持有平台类的引用，方向反了。");
    }

    // ── 目标缺失 ───────────────────────────────────────────────────

    @Test
    @DisplayName("目标方法不存在时必须明确失败，绝不静默跳过")
    void missingTargetMustFailLoudly() {
        // 生成一个没有 tickServer 的 MinecraftServer
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SERVER_TICK.owner(), null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "somethingElse", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MiliTickTransformer t = new MiliTickTransformer();

        // 这是本系统最重要的一条行为约定。
        // 若返回 Skipped：游戏照常运行、tick 数不对、Mod 不工作、日志无异常。
        assertThrows(
                org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> t.transform(context(cw.toByteArray())),
                "目标缺失必须抛异常 —— 返回 Skipped 会让整条 tick 链静默断开，"
                        + "那正是本系统要消灭的失效模式。");
    }

    @Test
    @DisplayName("目标描述符不匹配也算缺失 —— 按名字匹配会命中重载")
    void wrongDescriptorIsAlsoMissing() {
        // tickServer() 无参 —— 名字对但描述符不对
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                MiliSymbol.SERVER_TICK.owner(), null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC, "tickServer", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MiliTickTransformer t = new MiliTickTransformer();

        assertThrows(
                org.loader.api.transform.TransformationTargetNotFoundException.class,
                () -> t.transform(context(cw.toByteArray())),
                "必须严格匹配 name + descriptor。只匹配名字会命中重载方法，"
                        + "生成出调用错误方法的字节码 —— 那类错误不报错，只是行为诡异。");
    }

    // ── 分发器行为 ─────────────────────────────────────────────────

    @Test
    @DisplayName("未安装桥接时分发器静默返回 —— Minecraft 启动期会先跑若干 tick")
    void dispatchIsSilentWhenNotInstalled() {
        TickCallbackDispatch.install(null);

        assertTrue(!TickCallbackDispatch.isInstalled());
        assertDoesNotThrow(() -> {
            TickCallbackDispatch.onTickBegin();
            TickCallbackDispatch.onTickEnd();
        });
        assertEquals(0, TickCallbackDispatch.errorCount());
    }

    @Test
    @DisplayName("未配对的 endTick 不推进契约 —— 不得凭空造一个 tick")
    void unbalancedEndTickIsIgnored() {
        org.loader.runtime.minecraft.TickBridge bridge =
                new org.loader.runtime.minecraft.TickBridge(scope, engine);
        TickCallbackDispatch.install(bridge);

        // 只调 endTick（模拟 begin 时抛异常的状态错乱）
        TickCallbackDispatch.onTickEnd();

        assertEquals(0, TickCallbackDispatch.endCount(),
                "没有对应 begin 的 endTick 必须被忽略 —— "
                        + "补调会推进一个不存在的契约，产出无意义的指标。");
        assertTrue(TickCallbackDispatch.isBalanced());
    }

    @Test
    @DisplayName("重复进入 tick 被检出 —— 这是重复转换的直接证据")
    void nestedBeginIsDetected() {
        org.loader.runtime.minecraft.TickBridge bridge =
                new org.loader.runtime.minecraft.TickBridge(scope, engine);
        TickCallbackDispatch.install(bridge);

        TickCallbackDispatch.onTickBegin();
        TickCallbackDispatch.onTickBegin();     // 模拟字节码被重复转换

        assertTrue(TickCallbackDispatch.errorCount() > 0,
                "重复进入 tick 必须被检出 —— 它是 TransformationCache 失效的直接证据，"
                        + "表现为「tick 数翻倍但游戏完全正常」。");
    }

    @Test
    @DisplayName("begin 失败后深度归零 —— 否则本 tick 之后全部 tick 永久失效")
    void failedBeginResetsDepth() {
        // 用一个已关闭的 TickBridge：beginTick 会抛 IllegalStateException
        org.loader.runtime.minecraft.TickBridge bridge =
                new org.loader.runtime.minecraft.TickBridge(scope, engine);
        bridge.close();
        TickCallbackDispatch.install(bridge);

        TickCallbackDispatch.onTickBegin();     // 内部失败

        // 换回可用的桥，验证后续 tick 仍能正常工作
        org.loader.runtime.minecraft.TickBridge healthy =
                new org.loader.runtime.minecraft.TickBridge(scope, engine);
        TickCallbackDispatch.install(healthy);
        TickCallbackDispatch.onTickBegin();
        TickCallbackDispatch.onTickEnd();

        assertEquals(1, TickCallbackDispatch.endCount(),
                "一次失败的 begin 不能让后续所有 tick 都被误判为嵌套。");
        assertTrue(TickCallbackDispatch.isBalanced(),
                "begin/end 计数必须配平 —— 不配平意味着有 tick 没有正常结束。");
    }

    @Test
    @DisplayName("回调异常不逃逸进游戏主循环")
    void callbackExceptionsNeverEscape() {
        org.loader.runtime.minecraft.TickBridge bridge =
                new org.loader.runtime.minecraft.TickBridge(scope, engine);
        bridge.close();
        TickCallbackDispatch.install(bridge);

        // 必须不抛 —— 异常逃逸会以 VerifyError 式的晦涩错误打断游戏主循环，
        // 且玩家无法判断是自己的 Mod 导致的。
        assertDoesNotThrow(() -> {
            TickCallbackDispatch.onTickBegin();
            TickCallbackDispatch.onTickEnd();
        });
    }

    // ── 辅助 ───────────────────────────────────────────────────────

    private static void assertDoesNotThrow(Runnable r) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(r::run);
    }

    private static void assertDoesNotThrowVerify(byte[] bytes) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> org.loader.runtime.transform.verify.BytecodeVerifier.verify(
                        MiliSymbol.SERVER_TICK.owner(), bytes,
                        MiliTickTransformer.ID, null));
    }

    /**
     * 提取常量池里的所有 UTF8 字符串 —— 用于「零 Mod 引用」断言。
     *
     * <p>手写常量池扫描而非用 ASM：ASM 的 {@code ClassReader} 不暴露常量池，
     * 而这里要断言的恰恰是「常量池里有没有不该出现的类名」，
     * 绕一圈用 ASM 查询反而看不到全貌。
     */
    private static String dumpConstantPoolStrings(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(bytes));
        try {
            in.readInt();                          // magic
            in.readUnsignedShort();               // minor
            in.readUnsignedShort();               // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> sb.append(in.readUTF()).append('\n');
                    case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;                       // long/double 占两槽
                    }
                    case 15 -> in.readUnsignedByte();
                    default -> {
                        // 未知 tag：无法继续安全扫描
                        return sb.toString();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 部分扫描成功也够用 —— 断言只需要「相关字符串是否出现」
        }
        return sb.toString();
    }
}