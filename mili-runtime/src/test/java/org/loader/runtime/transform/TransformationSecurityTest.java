package org.loader.runtime.transform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.transform.TransformationPhase;
import org.loader.runtime.transform.security.TransformationGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转换权限守卫测试。
 *
 * <h2>为什么默认拒绝是关键</h2>
 * 平台已有完整的 Capability / Permission 体系（ADR-0011 决定复用它，
 * 而不是另造一套转换权限）。若转换权限默认允许，
 * 任何被加载的 Mod 都能改 Minecraft 字节码 ——
 * 那等价于「装了个 Mod 就获得了任意代码替换权」。
 *
 * <h2>这些测试为什么主要断言 ALLOWED / DENIED_OVERWRITE_DISABLED</h2>
 * 因为 {@code scope == null} 代表「平台自身转换」，平台对自己的转换
 * 当然是放行的。真正需要 Mod scope 才能到达的分支需要完整的
 * Capability 装配，属于 {@code CapabilityPermissionTest} 的覆盖范围；
 * 本类专注于<b>守卫逻辑本身</b>（阶段保护、Overwrite 双重限制）。
 */
class TransformationSecurityTest {

    // ── 常规转换 ───────────────────────────────────────────────────

    @Test
    @DisplayName("平台自身转换（scope 为 null）一律放行")
    void platformTransformationIsAllowed() {
        assertEquals(TransformationGuard.Decision.ALLOWED,
                TransformationGuard.checkTransform(null, "net/minecraft/server/MinecraftServer", true));
        assertEquals(TransformationGuard.Decision.ALLOWED,
                TransformationGuard.checkTransform(null, "some/mod/Class", false));
    }

    @Test
    @DisplayName("无 Scope 的 Mod 转换被拒绝 —— 默认拒绝")
    void modWithoutScopeIsDenied() {
        // 传一个未持有任何 capability 的 Scope 时，
        // has() 取不到 Permission 实例 → 拒绝。
        // 这正是「默认拒绝」的实现：拿不到授权就当没有授权。
        org.loader.runtime.kernel.Scope scope = new org.loader.runtime.kernel.Scope("test-mod", null);
        try {
            assertNotEquals(TransformationGuard.Decision.ALLOWED,
                    TransformationGuard.checkTransform(scope,
                            "net/minecraft/server/MinecraftServer", true),
                    "未授权的 Mod 必须被拒绝 —— 默认允许等于把任意代码替换权交给所有 Mod。");
        } finally {
            scope.close();
        }
    }

    // ── Overwrite 双重限制 ─────────────────────────────────────────

    @Test
    @DisplayName("Overwrite 在默认配置下被禁用 —— 必须显式 opt-in")
    void overwriteDisabledByDefault() {
        assertEquals(TransformationGuard.Decision.DENIED_OVERWRITE_DISABLED,
                TransformationGuard.checkOverwrite(null,
                        TransformationPhase.MOD, false));
    }

    @Test
    @DisplayName("平台也不能覆写 CORE 阶段的目标 —— 权限再大也不行")
    void overwriteCannotTargetProtectedPhaseEvenForPlatform() {
        // 这是双重限制中更重要的一半：
        // Core 承载 tick 正确性，原始方法体一旦被丢弃，
        // 其他 Mod 声明的注入点会静默失效。
        assertEquals(TransformationGuard.Decision.DENIED_PROTECTED_TARGET,
                TransformationGuard.checkOverwrite(null,
                        TransformationPhase.CORE, true));
    }

    @Test
    @DisplayName("非 CORE 阶段在显式启用后可被平台覆写")
    void platformMayOverwriteNonProtectedPhase() {
        assertEquals(TransformationGuard.Decision.ALLOWED,
                TransformationGuard.checkOverwrite(null,
                        TransformationPhase.MOD, true));
        assertEquals(TransformationGuard.Decision.ALLOWED,
                TransformationGuard.checkOverwrite(null,
                        TransformationPhase.LATE, true));
    }

    @Test
    @DisplayName("只有 CORE 受保护 —— 早期/延迟阶段都可覆写")
    void onlyCorePhaseIsProtected() {
        assertTrue(TransformationPhase.CORE.isProtected());
        for (TransformationPhase phase : TransformationPhase.values()) {
            if (phase != TransformationPhase.CORE) {
                assertTrue(!phase.isProtected(),
                        phase + " 不应受保护 —— 受保护阶段过多会让 Overwrite 形同虚设。");
            }
        }
    }

    @Test
    @DisplayName("未授权 Mod 无法 Overwrite")
    void unauthorizedModCannotOverwrite() {
        org.loader.runtime.kernel.Scope scope = new org.loader.runtime.kernel.Scope("weak-mod", null);
        try {
            assertEquals(TransformationGuard.Decision.DENIED_MISSING_PERMISSION,
                    TransformationGuard.checkOverwrite(scope,
                            TransformationPhase.MOD, true));
        } finally {
            scope.close();
        }
    }

    // ── 拒绝原因可读 ───────────────────────────────────────────────

    @Test
    @DisplayName("每种拒绝都有可读原因 —— 用户需要知道自己缺什么")
    void everyDecisionHasReadableReason() {
        for (TransformationGuard.Decision decision : TransformationGuard.Decision.values()) {
            String reason = TransformationGuard.describe(decision);
            assertTrue(reason != null && !reason.isBlank(),
                    decision + " 必须有可读原因");
        }
    }

    @Test
    @DisplayName("受保护目标的拒绝原因点名 CORE 阶段")
    void protectedTargetReasonMentionsCore() {
        assertTrue(TransformationGuard
                .describe(TransformationGuard.Decision.DENIED_PROTECTED_TARGET)
                .contains("CORE"));
    }

    @Test
    @DisplayName("Overwrite 禁用原因点明需要显式 opt-in")
    void overwriteDisabledReasonMentionsOptIn() {
        String reason = TransformationGuard
                .describe(TransformationGuard.Decision.DENIED_OVERWRITE_DISABLED);
        assertTrue(reason.contains("默认")
                        || reason.contains("opt-in"),
                "必须说明这是默认行为且需显式开启: " + reason);
    }

    // ── 阶段顺序 ───────────────────────────────────────────────────

    @Test
    @DisplayName("阶段权重严格递增，保证 Core 先于 Mod 执行")
    void phaseWeightsAreStrictlyIncreasing() {
        TransformationPhase[] order = {
                TransformationPhase.EARLY,
                TransformationPhase.CORE,
                TransformationPhase.MINECRAFT,
                TransformationPhase.MOD,
                TransformationPhase.LATE
        };
        for (int i = 1; i < order.length; i++) {
            assertTrue(order[i - 1].weight() < order[i].weight(),
                    order[i - 1] + " 的权重必须小于 " + order[i]
                            + " —— 否则 Core 可能被 Mod 抢先执行，"
                            + "Mod 会拿到一个尚未接线的 TickEngine。");
        }
    }
}