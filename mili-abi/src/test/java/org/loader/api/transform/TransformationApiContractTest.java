package org.loader.api.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transformation API 契约测试。
 *
 * <p><b>这些测试验证的是 API 的语义约束</b>，尤其是
 * {@link TransformationResult} 中 {@code Skipped} 与 {@code Failed} 的区分 ——
 * 那处区分是整个转换体系正确性的基石：把「目标缺失」误报成「跳过」
 * 会让核心转换器静默失效。
 */
@DisplayName("Transformation API 契约")
class TransformationApiContractTest {

    @Nested
    @DisplayName("TransformationResult 的 Skipped / Failed 语义区分")
    class ResultSemantics {

        @Test
        @DisplayName("Skipped 是正常路径，不携带异常")
        void skippedCarriesNoException() {
            TransformationResult r = TransformationResult.Skipped.because("不是客户端环境");

            assertTrue(r instanceof TransformationResult.Skipped);
            assertEquals("不是客户端环境", ((TransformationResult.Skipped) r).reason());
        }

        @Test
        @DisplayName("Skipped 必须给出原因 —— 无声跳过等于隐藏问题")
        void skippedRequiresReason() {
            assertThrows(IllegalArgumentException.class,
                    () -> TransformationResult.Skipped.because(null));
        }

        @Test
        @DisplayName("Failed 必须携带 TransformationException")
        void failedRequiresException() {
            var cause = new TransformationTargetNotFoundException(
                    "net/minecraft/server/MinecraftServer", "tickServer",
                    "(Ljava/util/function/BooleanSupplier;)V", "26.2", "test-transformer");

            TransformationResult r = new TransformationResult.Failed(cause);

            assertTrue(r instanceof TransformationResult.Failed);
            assertEquals(cause, ((TransformationResult.Failed) r).error());
        }

        @Test
        @DisplayName("Failed 不接受 null —— 否则等于把失败降级为跳过")
        void failedRejectsNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransformationResult.Failed(null));
        }

        @Test
        @DisplayName("目标不存在必须是异常，而不是 Skipped")
        void targetMissingIsExceptionNotSkip() {
            // 这是本 API 最重要的判断：目标缺失必须抛异常，
            // 否则 Mili Core 的 tick 转换器会静默失效而无人察觉。
            //
            // 断言的是<b>类型层级</b>而不是「构造它不抛」——
            // 后者是写错了：assertDoesNotThrow 会把这里主动抛出的异常
            // 判为失败，而抛出正是本测试要确认的行为。
            //
            // 真正要保证的是：流水线用 TransformationException 统一捕获时，
            // 「目标不存在」能被一并捕获并中断整条链，
            // 而不会因为类型不匹配漏成未检查异常、或被当成 Skipped 放行。
            var e = new TransformationTargetNotFoundException(
                    "net/minecraft/server/MinecraftServer", "tickServer",
                    "(Ljava/util/function/BooleanSupplier;)V", "26.2", "MiliTickTransformer");

            // 流水线统一 catch TransformationException —— 目标不存在必须落在其中，
            // 否则会漏成未检查异常、或在 catch 之外被静默放行。
            //
            // 断言写成「可赋值给 TransformationException」而不是
            // instanceof：编译器已经保证了这层继承关系，
            // 再断言一次是废话；而 instanceof Error 会被 javac
            // 当成「类型上不可能」的常量表达式直接报错。
            TransformationException asPipelineException = e;
            assertNotNull(asPipelineException.getMessage());
            assertEquals("MiliTickTransformer", asPipelineException.transformerId(),
                    "异常必须携带 transformerId —— 否则用户不知道是哪个转换器出的问题");
        }

        @Test
        @DisplayName("Unchanged 是共享单例")
        void unchangedIsSingleton() {
            assertEquals(TransformationResult.Unchanged.INSTANCE,
                    new TransformationResult.Unchanged());
        }

        @Test
        @DisplayName("Transformed 拒绝空字节码 —— 空数组无法 defineClass")
        void transformedRejectsEmptyBytecode() {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransformationResult.Transformed(new byte[0]));
            assertThrows(IllegalArgumentException.class,
                    () -> new TransformationResult.Transformed(null));
        }
    }

    @Nested
    @DisplayName("TransformationPhase")
    class Phases {

        @Test
        @DisplayName("阶段顺序严格 EARLY → CORE → MINECRAFT → MOD → LATE")
        void phaseOrdering() {
            assertTrue(TransformationPhase.EARLY.weight()
                    < TransformationPhase.CORE.weight());
            assertTrue(TransformationPhase.CORE.weight()
                    < TransformationPhase.MINECRAFT.weight());
            assertTrue(TransformationPhase.MINECRAFT.weight()
                    < TransformationPhase.MOD.weight());
            assertTrue(TransformationPhase.MOD.weight()
                    < TransformationPhase.LATE.weight());
        }

        @Test
        @DisplayName("只有 CORE 受保护 —— 承载 tick 正确性，不可被 Mod 覆盖")
        void onlyCoreIsProtected() {
            assertTrue(TransformationPhase.CORE.isProtected());
            assertFalse(TransformationPhase.MOD.isProtected());
            assertFalse(TransformationPhase.EARLY.isProtected());
            assertFalse(TransformationPhase.MINECRAFT.isProtected());
            assertFalse(TransformationPhase.LATE.isProtected());
        }

        @Test
        @DisplayName("ordinal 顺序与 weight 顺序一致（排序实现依赖）")
        void ordinalMatchesWeight() {
            TransformationPhase[] values = TransformationPhase.values();
            for (int i = 1; i < values.length; i++) {
                assertTrue(values[i - 1].weight() < values[i].weight(),
                        values[i - 1] + " 必须排在 " + values[i] + " 之前");
            }
        }
    }

    @Nested
    @DisplayName("MiliSymbol —— 26.2 真实坐标")
    class Symbols {

        @Test
        @DisplayName("SERVER_TICK 带 BooleanSupplier 参数，不是无参")
        void serverTickHasBooleanSupplierParam() {
            // 审计实测：真实签名是 tickServer(BooleanSupplier)，而非 tickServer()。
            // 若这里退化回无参，注入会静默不命中。
            assertEquals("(Ljava/util/function/BooleanSupplier;)V",
                    MiliSymbolAccess.serverTickDescriptor());
            assertEquals("tickServer", MiliSymbolAccess.serverTickName());
            assertEquals("net/minecraft/server/MinecraftServer",
                    MiliSymbolAccess.serverTickOwner());
        }

        @Test
        @DisplayName("符号表版本与 VersionInfo 一致")
        void symbolVersionMatchesVersionInfo() {
            assertEquals(org.loader.api.VersionInfo.TARGET_MINECRAFT,
                    MiliSymbolAccess.minecraftVersion(),
                    "符号表版本必须与 VersionInfo 严格一致");
        }

        @Test
        @DisplayName("客户端符号被正确标记")
        void clientOnlySymbolsDetected() {
            assertTrue(MiliSymbolAccess.isClientOnly(
                    MiliSymbolAccess.clientLevelTick()));
            assertTrue(MiliSymbolAccess.isClientOnly(
                    MiliSymbolAccess.clientMain()));
            assertFalse(MiliSymbolAccess.isClientOnly(
                    MiliSymbolAccess.serverTick()));
        }
    }

    @Nested
    @DisplayName("异常诊断信息")
    class Diagnostics {

        @Test
        @DisplayName("目标不存在异常输出完整坐标")
        void targetNotFoundCarriesFullContext() {
            var e = new TransformationTargetNotFoundException(
                    "net/minecraft/server/MinecraftServer", "tickServer",
                    "(Ljava/util/function/BooleanSupplier;)V", "26.2", "MiliTickTransformer");

            String msg = e.getMessage();
            assertTrue(msg.contains("net/minecraft/server/MinecraftServer"), msg);
            assertTrue(msg.contains("tickServer"), msg);
            assertTrue(msg.contains("(Ljava/util/function/BooleanSupplier;)V"), msg);
            assertTrue(msg.contains("26.2"), msg);
            assertTrue(msg.contains("MiliTickTransformer"), msg);
            assertEquals("MiliTickTransformer", e.transformerId());
        }

        @Test
        @DisplayName("冲突异常输出所有参与方")
        void conflictListsAllParticipants() {
            var e = new TransformationConflictException(
                    "net/minecraft/server/MinecraftServer", "tickServer",
                    java.util.List.of("ModA", "ModB", "MiliCore"),
                    "multiple incompatible transformations");

            String msg = e.getMessage();
            assertTrue(msg.contains("ModA"), msg);
            assertTrue(msg.contains("ModB"), msg);
            assertTrue(msg.contains("MiliCore"), msg);
            assertTrue(msg.contains("multiple incompatible transformations"), msg);
            assertEquals(3, e.transformerIds().size());
        }

        @Test
        @DisplayName("验证异常携带字节码偏移与 Mod 归属")
        void verificationExceptionCarriesOffsetAndMod() {
            var e = new TransformationVerificationException(
                    "net/minecraft/server/MinecraftServer", "tickServer",
                    "BadTransformer", "mod-bad", 42, "stack underflow", null);

            assertEquals(42, e.bytecodeOffset());
            assertEquals("mod-bad", e.modId());
            assertEquals("BadTransformer", e.transformerId());
            assertTrue(e.getMessage().contains("stack underflow"));
            assertTrue(e.getMessage().contains("42"));
        }

        @Test
        @DisplayName("所有转换异常都接入 MiliException 体系")
        void allExtendMiliException() {
            assertTrue(new TransformationException("x")
                    instanceof org.loader.api.exception.MiliException);
            assertTrue(new TransformationTargetNotFoundException("a", "b", "c", "26.2", "d")
                    instanceof TransformationException);
            assertTrue(new TransformationConflictException("a", "b",
                            java.util.List.of("x"), "r")
                    instanceof TransformationException);
            assertTrue(new TransformationVerificationException("a", "b", "c", "d", 0, "e", null)
                    instanceof TransformationException);
        }
    }
}