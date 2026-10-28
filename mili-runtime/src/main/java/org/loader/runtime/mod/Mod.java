package org.loader.runtime.mod;

import org.loader.runtime.kernel.*;

import java.util.*;

/**
 * Represents a loaded Mod instance.
 * <p>
 * A Mod is a managed execution unit with its own Scope.
 */
public class Mod implements Resource {

    private final ModManifest manifest;
    private final Scope modScope;
    private final ClassLoader classLoader;
    /** 模块名 → 子 Scope。见 {@link #modules()} 的幂等性说明。 */
    private final Map<String, Scope> moduleScopes = new java.util.concurrent.ConcurrentHashMap<>();

    public Mod(ModManifest manifest, Scope modScope, ClassLoader classLoader) {
        this.manifest = Objects.requireNonNull(manifest);
        this.modScope = Objects.requireNonNull(modScope);
        this.classLoader = classLoader != null ? classLoader : getClass().getClassLoader();
    }

    @Override
    public String id() {
        return manifest.id();
    }

    @Override
    public Scope owner() {
        return modScope.owner();
    }

    public ModManifest manifest() {
        return manifest;
    }

    public Scope scope() {
        return modScope;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public boolean isClosed() {
        return modScope.isStopped();
    }

    @Override
    public void close() {
        // Note: Do NOT call modScope.shutdown() here. The Scope owns this Mod
        // as a resource; shutting down the scope iterates resources and calls
        // close() on each. Calling shutdown from here would be infinite recursion.
        // Mod specific cleanup (e.g., releasing classloader resources) goes here.
    }

    /**
     * Returns whether this mod is a large mod with multiple modules.
     */
    public boolean isLargeMod() {
        return !manifest.modules().isEmpty();
    }

    /**
     * Returns child modules as scopes.
     *
     * <p><b>幂等</b>：同一个模块名每次返回<b>同一个</b> Scope 实例。
     * 旧实现每次都 {@code createChild(...)}，于是
     * <pre>
     *   ctx.modules();          // 创建 3 个子 Scope
     *   ctx.modules();          // 又创建 3 个（同名，覆盖前一批）
     * </pre>
     * 覆盖意味着前一批子 Scope 从此不可达却仍挂在父 Scope 的关闭链上 ——
     * 既泄漏，又会在关闭时对同一个模块执行两次清理。
     */
    public List<Scope> modules() {
        List<Scope> scopes = new ArrayList<>(manifest.modules().size());
        for (String name : manifest.modules()) {
            scopes.add(moduleScope(name));
        }
        return List.copyOf(scopes);
    }

    /**
     * 取（或创建）某个模块的子 Scope。
     *
     * @param moduleName 清单里声明的模块名
     * @throws IllegalArgumentException 模块未在清单中声明
     */
    public Scope moduleScope(String moduleName) {
        if (moduleName == null || !manifest.modules().contains(moduleName)) {
            throw new IllegalArgumentException(
                    "Mod '" + id() + "' 未声明模块: " + moduleName
                            + "（已声明: " + manifest.modules() + "）");
        }
        return moduleScopes.computeIfAbsent(moduleName,
                n -> modScope.createChild(id() + ":" + n));
    }

    @Override
    public String toString() {
        return "Mod[" + id() + " v" + manifest.version() + ", state=" + modScope.state() + "]";
    }
}
