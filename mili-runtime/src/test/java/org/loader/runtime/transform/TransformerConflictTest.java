package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.TransformationConflictException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.conflict.ConflictLedger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冲突检测测试。
 *
 * <h2>为什么冲突必须显式报出，而不是「后来者胜出」</h2>
 * 若平台默默让后注册的转换器覆盖先前的，结果就取决于
 * <b>Mod 的目录扫描顺序</b>。这类bug 的特征是：
 * <ul>
 *   <li>不可复现（顺序可能随文件系统变化）；</li>
 *   <li>无法二分定位（问题不在任何一方代码里）；</li>
 *   <li>平台日志里什么也没有。</li>
 * </ul>
 *
 * <p>这比直接抛异常糟糕得多：抛异常至少让用户在启动时
 * 看到明确的「ModA 与 ModB 冲突」。
 */
class TransformerConflictTest {

    private static final TargetMethod TARGET = TargetMethod.of(
            "net/minecraft/server/MinecraftServer",
            "tickServer",
            "(Ljava/util/function/BooleanSupplier;)V");

    private static ConflictLedger.Claim claim(
            String transformerId, String modId, InjectionPoint point,
            TransformationPhase phase, Integer ordinal) {

        return new ConflictLedger.Claim(
                transformerId, modId, TARGET, point,
                ordinal == null ? null : TargetInvocation.at(TARGET, ordinal),
                phase);
    }

    // ── 互斥点 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("同一 ordinal 的两个 REDIRECT 构成冲突")
    void twoRedirectsAtSameOrdinalConflict() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("mod-a:redirect", "mod-a",
                InjectionPoint.REDIRECT, TransformationPhase.MOD, 0));

        TransformationConflictException ex = assertThrows(
                TransformationConflictException.class,
                () -> ledger.claim(claim("mod-b:redirect", "mod-b",
                        InjectionPoint.REDIRECT, TransformationPhase.MOD, 0)));

        assertTrue(ex.getMessage().contains("mod-a:redirect"),
                "冲突信息必须点名冲突双方: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("mod-b:redirect"));
    }

    @Test
    @DisplayName("不同 ordinal 的 REDIRECT 不冲突")
    void redirectsAtDifferentOrdinalsAreFine() {
        ConflictLedger ledger = new ConflictLedger();
        assertDoesNotThrow(() -> {
            ledger.claim(claim("mod-a", "mod-a",
                    InjectionPoint.REDIRECT, TransformationPhase.MOD, 0));
            ledger.claim(claim("mod-b", "mod-b",
                    InjectionPoint.REDIRECT, TransformationPhase.MOD, 1));
        });
        assertEquals(2, ledger.allClaims().size());
    }

    @Test
    @DisplayName("OVERWRITE 与已登记的互斥声明冲突 —— 原始方法体会被丢弃")
    void overwriteConflictsWithRegisteredClaims() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("mod-a:modify_arg", "mod-a",
                InjectionPoint.MODIFY_ARG, TransformationPhase.MOD, 0));

        assertThrows(TransformationConflictException.class,
                () -> ledger.claim(claim("mod-b:overwrite", "mod-b",
                        InjectionPoint.OVERWRITE, TransformationPhase.MOD, 0)));
    }

    @Test
    @DisplayName("同一转换器的 OVERWRITE 重复声明被拒")
    void duplicateOverwriteIsRejected() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("mod-a", "mod-a",
                InjectionPoint.OVERWRITE, TransformationPhase.MOD, 0));

        assertThrows(TransformationConflictException.class,
                () -> ledger.claim(claim("mod-a", "mod-a",
                        InjectionPoint.OVERWRITE, TransformationPhase.MOD, 0)));
    }

    @Test
    @DisplayName("Mod 阶段不能覆盖 CORE 阶段已占用的注入点")
    void modPhaseCannotOverrideCoreClaim() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("core:redirect", null,
                InjectionPoint.REDIRECT, TransformationPhase.CORE, 0));

        TransformationConflictException ex = assertThrows(
                TransformationConflictException.class,
                () -> ledger.claim(claim("mod:redirect", "mod-x",
                        InjectionPoint.REDIRECT, TransformationPhase.MOD, 0)));

        assertTrue(ex.getMessage().contains("CORE"),
                "冲突信息应说明是受保护的核心注入点: " + ex.getMessage());
    }

    @Test
    @DisplayName("同一转换器重复声明同一位置属于自身错误")
    void duplicateClaimBySameTransformerConflicts() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("mod-a:redirect", "mod-a",
                InjectionPoint.REDIRECT, TransformationPhase.MOD, 0));

        assertThrows(TransformationConflictException.class,
                () -> ledger.claim(claim("mod-a:redirect", "mod-a",
                        InjectionPoint.REDIRECT, TransformationPhase.MOD, 0)));
    }

    // ── 可组合点 ───────────────────────────────────────────────────

    @Test
    @DisplayName("多个 HEAD 不冲突 —— 都应执行")
    void multipleHeadsDoNotConflict() {
        ConflictLedger ledger = new ConflictLedger();
        assertDoesNotThrow(() -> {
            ledger.claim(claim("mod-a", "mod-a",
                    InjectionPoint.HEAD, TransformationPhase.MOD, null));
            ledger.claim(claim("mod-b", "mod-b",
                    InjectionPoint.HEAD, TransformationPhase.MOD, null));
            ledger.claim(claim("mod-c", "mod-c",
                    InjectionPoint.HEAD, TransformationPhase.MOD, null));
        });

        // HEAD 不入账本（无冲突风险，也无需记录）
        assertTrue(ledger.allClaims().isEmpty());
    }

    @Test
    @DisplayName("多个 RETURN 不冲突")
    void multipleReturnsDoNotConflict() {
        ConflictLedger ledger = new ConflictLedger();
        assertDoesNotThrow(() -> {
            ledger.claim(claim("mod-a", "mod-a",
                    InjectionPoint.RETURN, TransformationPhase.MOD, null));
            ledger.claim(claim("mod-b", "mod-b",
                    InjectionPoint.RETURN, TransformationPhase.MOD, null));
        });
    }

    // ── 预演 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("dryRun 在独立账本上预演，不修改自身状态")
    void dryRunProbesWithoutMutating() {
        ConflictLedger ledger = new ConflictLedger();

        List<ConflictLedger.Claim> incoming = List.of(
                claim("mod-x", "mod-x",
                        InjectionPoint.REDIRECT, TransformationPhase.MOD, 0));

        List<String> problems = ledger.dryRun(incoming);
        assertTrue(problems.isEmpty(),
                "空账本上预演不应报冲突");

        // 自身状态必须未变
        assertTrue(ledger.allClaims().isEmpty());

        // 再次预演同一批 —— 结果必须相同（说明确实无副作用）
        assertEquals(problems, ledger.dryRun(incoming));
    }

    @Test
    @DisplayName("dryRun 能检出与既有声明的冲突")
    void dryRunDetectsConflictWithExisting() {
        ConflictLedger ledger = new ConflictLedger();
        ledger.claim(claim("mod-a:redirect", "mod-a",
                InjectionPoint.REDIRECT, TransformationPhase.MOD, 0));

        List<String> problems = ledger.dryRun(List.of(
                claim("mod-b:redirect", "mod-b",
                        InjectionPoint.REDIRECT, TransformationPhase.MOD, 0)));

        assertEquals(1, problems.size(),
                "预演应检出与既有声明的冲突 —— "
                        + "Mod 安装阶段就告知用户，比等到启动时才炸好得多。");
    }
}