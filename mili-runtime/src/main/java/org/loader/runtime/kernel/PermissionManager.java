package org.loader.runtime.kernel;

import org.loader.api.permission.Permission;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages permission grants.
 * <p>
 * Permissions are separate from lifecycle: lifecycle answers "how long may this exist",
 * permission answers "what may this do".
 *
 * <h2>默认策略：拒绝即默认</h2>
 * 旧实现没有装配任何策略，于是 {@code hasPermission} 恒为 {@code false} ——
 * 包括 {@code resource.read} 这种任何 Mod 都必须有的基础权限。
 * 结果是「权限系统存在，但没人能用」，而且因为没人调用它，
 * 这个缺陷一直没有暴露。
 *
 * <p>现在 {@link #installDefaults()} 装上三条基线策略：
 * <ul>
 *   <li>{@code resource.read} / {@code resource.write} —— 允许。
 *       读写自己的资源是 Mod 的固有能力，不构成权限边界。</li>
 *   <li>{@code intermod.communication} —— 允许。服务代理是显式 API，
 *       调用方必须知道对方类型，天然受限。</li>
 *   <li>其余全部（网络、原生库、四项字节码转换权、方法覆盖）—— <b>拒绝</b>。
 *       这些能力一旦被默认授予，平台对 Mod 的约束就只剩「自觉」。</li>
 * </ul>
 *
 * <p>放行方式：{@link #grant(Scope, String)} 或
 * {@link #grantAll(String)}（作用于此后创建的全部 Scope）。
 */
public class PermissionManager implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, PermissionPolicy> policies = new ConcurrentHashMap<>();

    /** 显式授予的权限（scopeId → 权限集合）。 */
    private final Map<String, Set<String>> grants = new ConcurrentHashMap<>();

    /** 全局放行的权限（不区分 Scope）。 */
    private final Set<String> globalGrants = ConcurrentHashMap.newKeySet();

    public PermissionManager(String id, Scope owner) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Scope owner() {
        return owner;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 安装默认策略。幂等。
     *
     * <p>只安装「没有显式策略」的权限 —— 已有的显式策略（用户配置或
     * 平台装配）优先，不会被默认值覆盖。
     */
    public void installDefaults() {
        registerDefault(Permission.RESOURCE_READ, true);
        registerDefault(Permission.RESOURCE_WRITE, true);
        registerDefault(Permission.INTERMOD_COMMUNICATION, true);

        // 高危能力默认拒绝。显式写出来（而不是靠「没策略就是 false」）
        // 是为了让默认策略本身可被审计 —— 读代码的人一眼能看到哪些是拒绝的。
        registerDefault(Permission.NETWORK_ACCESS, false);
        registerDefault(Permission.NATIVE_ACCESS, false);
        registerDefault(Permission.CAPABILITY_REGISTER, false);
        registerDefault(Permission.TRANSFORM_CLASS, false);
        registerDefault(Permission.TRANSFORM_OTHER_MODS, false);
        registerDefault(Permission.TRANSFORM_MINECRAFT, false);
        registerDefault(Permission.TRANSFORM_CORE, false);
        registerDefault(Permission.OVERWRITE_METHOD, false);
    }

    private void registerDefault(Permission permission, boolean allowed) {
        policies.putIfAbsent(permission.id(), (scope, perm) -> isGranted(scope, perm) || allowed);
    }

    /**
     * Registers a permission policy (overrides any default).
     */
    public void registerPolicy(String permission, PermissionPolicy policy) {
        if (closed.get()) {
            throw new IllegalStateException("PermissionManager is closed");
        }
        if (permission == null || permission.isBlank() || policy == null) {
            throw new IllegalArgumentException("permission 与 policy 均不能为空");
        }
        policies.put(permission, policy);
    }

    /**
     * 为某个 Scope 显式授予一项权限。
     */
    public void grant(Scope scope, String permission) {
        if (scope == null || permission == null || permission.isBlank()) {
            return;
        }
        grants.computeIfAbsent(scope.id(), k -> ConcurrentHashMap.newKeySet()).add(permission);
    }

    /**
     * 撤销某个 Scope 的显式授予（不影响全局放行）。
     */
    public boolean revoke(Scope scope, String permission) {
        if (scope == null || permission == null) {
            return false;
        }
        Set<String> set = grants.get(scope.id());
        return set != null && set.remove(permission);
    }

    /**
     * 全局放行一项权限 —— 作用于<b>此后</b>创建的全部 Scope。
     *
     * <p>供管理员配置使用（{@code mili.permissions.<id>=allow}）。
     */
    public void grantAll(String permission) {
        if (permission != null && !permission.isBlank()) {
            globalGrants.add(permission);
        }
    }

    /**
     * 某个 Scope 是否被显式授予了该权限（不考虑策略）。
     */
    public boolean isGranted(Scope scope, String permission) {
        if (permission == null) {
            return false;
        }
        if (globalGrants.contains(permission)) {
            return true;
        }
        if (scope == null) {
            return false;
        }
        Set<String> set = grants.get(scope.id());
        return set != null && set.contains(permission);
    }

    /**
     * Checks whether a scope has a specific permission.
     */
    public boolean hasPermission(Scope scope, String permission) {
        PermissionPolicy policy = policies.get(permission);
        if (policy == null) {
            // 未注册的权限一律拒绝 —— 这是「拒绝即默认」的字面实现。
            // 但显式授予仍然生效：管理员可以用 grantAll 打开一个
            // 平台还没内置的权限名。
            return isGranted(scope, permission);
        }
        return policy.evaluate(scope, permission);
    }

    /**
     * Validates access or throws {@link SecurityException}.
     */
    public void checkPermission(Scope scope, String permission) {
        if (!hasPermission(scope, permission)) {
            throw new SecurityException(
                    "Scope " + (scope == null ? "<null>" : scope.id())
                            + " lacks permission: " + permission);
        }
    }

    /**
     * 全部已注册的策略名（诊断用）。
     */
    public Set<String> registeredPermissions() {
        return Set.copyOf(policies.keySet());
    }

    @Override
    public void close() {
        closed.set(true);
        policies.clear();
        grants.clear();
        globalGrants.clear();
    }

    /**
     * 绑定到某个 Scope 的 {@link org.loader.api.permission.PermissionManager} 视图。
     *
     * <p>这是 Mod 侧唯一的权限入口：它不能注册策略（那等于自己给自己发权限），
     * 只能查询。
     */
    public org.loader.api.permission.PermissionManager viewFor(Scope scope) {
        return new ScopedPermissionView(scope);
    }

    private final class ScopedPermissionView implements org.loader.api.permission.PermissionManager {

        private final Scope scope;

        ScopedPermissionView(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void registerPolicy(String permission,
                                   org.loader.api.permission.PermissionManager.PermissionPolicy policy) {
            throw new UnsupportedOperationException(
                    "Mod 不能注册权限策略 —— 那等于自己给自己发权限。"
                            + "需要额外权限请在 mod.json 的 permissions 字段声明，"
                            + "由用户配置放行。");
        }

        @Override
        public boolean hasPermission(String permission) {
            return PermissionManager.this.hasPermission(scope, permission);
        }

        @Override
        public boolean isClosed() {
            return PermissionManager.this.isClosed() || (scope != null && scope.isStopped());
        }
    }

    /**
     * Policy for evaluating permissions.
     */
    @FunctionalInterface
    public interface PermissionPolicy {
        boolean evaluate(Scope scope, String permission);
    }
}
