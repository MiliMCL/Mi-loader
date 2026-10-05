package org.loader.runtime.transform.security;

import org.loader.api.permission.Permission;
import org.loader.runtime.kernel.CapabilityToken;
import org.loader.runtime.kernel.Scope;

/**
 * 转换权限守卫 —— 把 Transformation 的能力判定接到既有权限体系上。
 *
 * <h2>为什么复用而不是另造一套权限</h2>
 * 平台已有 {@link org.loader.runtime.kernel.PermissionManager} 与
 * {@code Permission} 枚举、{@code CapabilityManager}、{@code AuditLog}。
 * 另造一套「转换权限」会造成：同一主体在两套系统里有两份互不同步的授权，
 * 且审计时无法回答「这个 Mod 到底有没有转换权」。
 *
 * <p>本类只做一件事：<b>把转换需求翻译成既有权限查询</b>。
 *
 * <h2>能力分层（ADR-0011）</h2>
 * <table border="1">
 *   <caption>能力与权限对应</caption>
 *   <tr><th>主体</th><th>所需权限</th></tr>
 *   <tr><td>平台自身转换</td><td>{@code TRANSFORM_CORE}（平台内部授予）</td></tr>
 *   <tr><td>普通 Mod 转换自己的类</td><td>{@code TRANSFORM_CLASS}</td></tr>
 *   <tr><td>核心 Mod 转换 Minecraft</td><td>{@code TRANSFORM_MINECRAFT}</td></tr>
 *   <tr><td>修改他人 Mod 的类</td><td>{@code TRANSFORM_OTHER_MODS}</td></tr>
 *   <tr><td>覆写方法体</td><td>{@code OVERWRITE_METHOD}（额外且独立）</td></tr>
 * </table>
 *
 * <p><b>默认拒绝</b>：Mod 不因为被加载就获得任何转换权限。
 *
 * <h2>Overwrite 的双重限制</h2>
 * 即便持有 {@code OVERWRITE_METHOD}，也<b>不能</b>覆写
 * {@link org.loader.api.transform.TransformationPhase#CORE} 阶段占用的目标。
 * 原因见 {@code Permission.OVERWRITE_METHOD} 的说明：Core 承载 tick 正确性，
 * 原始方法体一旦被丢弃，其他 Mod 声明的注入点会静默失效。
 */
public final class TransformationGuard {

    private TransformationGuard() {
    }

    /** 转换决策。 */
    public enum Decision {
        /** 允许。 */
        ALLOWED,
        /** 拒绝 —— 缺少权限。 */
        DENIED_MISSING_PERMISSION,
        /** 拒绝 —— Mod 试图覆写受保护目标。 */
        DENIED_PROTECTED_TARGET,
        /** 拒绝 —— Overwrite 需要显式 opt-in。 */
        DENIED_OVERWRITE_DISABLED
    }

    /**
     * 常规转换的权限判定。
     *
     * @param scope          发起转换的 Mod scope；平台自身转换传 null
     * @param targetClassName 目标类内部名
     * @param isMinecraftClass 目标是否属于 Minecraft
     */
    public static Decision checkTransform(
            Scope scope,
            String targetClassName,
            boolean isMinecraftClass) {

        if (scope == null) {
            // 平台自身转换 —— 由调用方保证来自平台内部代码路径。
            return Decision.ALLOWED;
        }
        if (isMinecraftClass) {
            return has(scope, Permission.TRANSFORM_MINECRAFT)
                    ? Decision.ALLOWED : Decision.DENIED_MISSING_PERMISSION;
        }
        // 非 Minecraft 类：默认只允许改自己的包
        return has(scope, Permission.TRANSFORM_CLASS)
                ? Decision.ALLOWED : Decision.DENIED_MISSING_PERMISSION;
    }

    /**
     * Overwrite 判定 —— 双重限制。
     *
     * @param scope          发起方 scope；平台自身传 null
     * @param targetPhase    目标已被哪个阶段占用
     * @param overwriteEnabled 平台配置是否允许 Overwrite（默认 false）
     */
    public static Decision checkOverwrite(
            Scope scope,
            org.loader.api.transform.TransformationPhase targetPhase,
            boolean overwriteEnabled) {

        if (!overwriteEnabled) {
            return Decision.DENIED_OVERWRITE_DISABLED;
        }
        if (scope == null) {
            // 平台自身仍受 Core 保护约束。
            return targetPhase != null && targetPhase.isProtected()
                    ? Decision.DENIED_PROTECTED_TARGET : Decision.ALLOWED;
        }
        // Mod 侧：先看权限
        if (!has(scope, Permission.OVERWRITE_METHOD)) {
            return Decision.DENIED_MISSING_PERMISSION;
        }
        // 再看目标是否受保护 —— 权限再大也不能覆写 Core
        if (targetPhase != null && targetPhase.isProtected()) {
            return Decision.DENIED_PROTECTED_TARGET;
        }
        return Decision.ALLOWED;
    }

    /** 人类可读的拒绝原因。 */
    public static String describe(Decision decision) {
        return switch (decision) {
            case ALLOWED -> "允许";
            case DENIED_MISSING_PERMISSION ->
                    "缺少转换权限（需要 transform.minecraft / transform.class / "
                            + "transform.overwrite 之一）";
            case DENIED_PROTECTED_TARGET ->
                    "目标是受保护的核心注入点（CORE 阶段），任何主体都不可覆写";
            case DENIED_OVERWRITE_DISABLED ->
                    "Overwrite 在平台配置中被禁用（默认禁用，需显式 opt-in）";
        };
    }

    /**
     * 权限查询。
     *
     * <p>Scope 的 capability 是「能力对象」而非「权限标志集」——
     * {@code getCapability} 返回 {@code Optional<CapabilityToken<T>>}，
     * 表示该 Scope 是否持有某个类型的实例。因此这里查的是
     * 「该 Scope 是否持有 {@link Permission} 实例」，其值即授予的权限。
     *
     * <p>取不到时按<b>拒绝</b>处理 —— 默认安全。
     */
    private static boolean has(Scope scope, Permission permission) {
        if (scope == null || permission == null) {
            return false;
        }
        try {
            return scope.getCapability(Permission.class)
                    .filter(CapabilityToken::isActive)
                    .map(token -> token.get() == permission)
                    .orElse(false);
        } catch (Throwable ignored) {
            // Capability 机制不可用（Scope 已关闭等）时按拒绝处理。
            return false;
        }
    }
}