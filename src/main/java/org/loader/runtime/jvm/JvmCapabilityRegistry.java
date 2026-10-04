package org.loader.runtime.jvm;

import org.loader.runtime.kernel.*;
import org.loader.runtime.security.AuditLog;

import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registry for JVM capability definitions with risk-based enforcement.
 * <p>
 * Per JVM.md §35 and SECURITY.md:
 * - HIGH/CRITICAL JVM capabilities are DENY by default
 * - Grants require explicit permission
 * - All grants and revocations are audited
 * - Scope ownership enforces lifecycle-bound revocation
 *
 * <pre>
 * Policy flow:
 * request → checkRiskLevel → if HIGH/CRITICAL: require explicit permission
 *                         → if STANDARD: grant directly (still audited for JVM caps)
 * → audit log the decision → grant scope-bound token → on scope stop: revoke + audit
 * </pre>
 */
public class JvmCapabilityRegistry implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, JvmCapabilityDefinition<?>> definitions = new ConcurrentHashMap<>();
    private final AuditLog auditLog;

    public JvmCapabilityRegistry(String id, Scope owner, AuditLog auditLog) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        registerDefaults();
    }

    @Override
    public String id() { return id; }

    @Override
    public Scope owner() { return owner; }

    @Override
    public boolean isClosed() { return closed.get(); }

    /**
     * Registers default JVM capability definitions with their risk levels.
     */
    private void registerDefaults() {
        // Each factory uses the public grant* capability methods from JvmCapabilities
        // to create the implementation, since the impl classes are private to JvmCapabilities.
        register("jvm.thread", JvmCapabilities.JvmThread.class, JvmCapabilityRisk.HIGH,
                scope -> {
                    // Use reflection-free factory: return a minimal JvmThread impl
                    return new JvmCapabilities.JvmThread() {
                        @Override
                        public Thread createThread(Runnable work, String name) {
                            Thread t = new Thread(work, name);
                            t.setDaemon(true);
                            return t;
                        }
                        @Override
                        public Thread currentThread() { return Thread.currentThread(); }
                    };
                });

        register("jvm.classload", JvmCapabilities.JvmClassLoading.class, JvmCapabilityRisk.HIGH,
                scope -> new JvmCapabilities.JvmClassLoading() {
                    @Override
                    public ClassLoader platformClassLoader() { return ClassLoader.getPlatformClassLoader(); }
                    @Override
                    public List<ClassLoader> allClassLoaders() {
                        List<ClassLoader> loaders = new ArrayList<>();
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        while (cl != null) { loaders.add(cl); cl = cl.getParent(); }
                        return Collections.unmodifiableList(loaders);
                    }
                    @Override
                    public int loadedClassCount() {
                        return java.lang.management.ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
                    }
                });

        register("jvm.diagnostics", JvmCapabilities.JvmDiagnostics.class, JvmCapabilityRisk.STANDARD,
                scope -> new JvmCapabilities.JvmDiagnostics() {
                    @Override
                    public ThreadInfo[] threadDump() {
                        return java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
                    }
                    @Override
                    public java.lang.management.MemoryUsage memoryUsage() {
                        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    }
                    @Override
                    public int loadedClassCount() {
                        return java.lang.management.ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
                    }
                    @Override
                    public Map<String, Object> runtimeInfo() {
                        java.lang.management.RuntimeMXBean rt = java.lang.management.ManagementFactory.getRuntimeMXBean();
                        Map<String, Object> info = new java.util.LinkedHashMap<>();
                        info.put("vmName", rt.getVmName());
                        info.put("vmVersion", rt.getVmVersion());
                        info.put("uptime", rt.getUptime());
                        info.put("availableProcessors", java.lang.Runtime.getRuntime().availableProcessors());
                        info.put("maxMemory", java.lang.Runtime.getRuntime().maxMemory());
                        info.put("freeMemory", java.lang.Runtime.getRuntime().freeMemory());
                        info.put("threadCount", Thread.activeCount());
                        return Collections.unmodifiableMap(info);
                    }
                });

        register("jvm.runtime", JvmCapabilities.JvmRuntime.class, JvmCapabilityRisk.HIGH,
                scope -> new JvmCapabilities.JvmRuntime() {
                    public long availableProcessors() { return java.lang.Runtime.getRuntime().availableProcessors(); }
                    public long maxMemory() { return java.lang.Runtime.getRuntime().maxMemory(); }
                    public long totalMemory() { return java.lang.Runtime.getRuntime().totalMemory(); }
                    public long freeMemory() { return java.lang.Runtime.getRuntime().freeMemory(); }
                    public void gc() { java.lang.Runtime.getRuntime().gc(); }
                    public void addShutdownHook(Thread hook) {
                        try { java.lang.Runtime.getRuntime().addShutdownHook(hook); }
                        catch (SecurityException e) { /* deny */ }
                    }
                    public boolean removeShutdownHook(Thread hook) {
                        try { return java.lang.Runtime.getRuntime().removeShutdownHook(hook); }
                        catch (SecurityException e) { return false; }
                    }
                });

        register("jvm.instrumentation", JvmCapabilities.JvmInstrumentation.class, JvmCapabilityRisk.CRITICAL,
                scope -> new JvmCapabilities.JvmInstrumentation() {
                    public Object getInstrumentation() { return null; }
                    public boolean isAvailable() { return false; }
                    public String agentStatus() { return "NO_AGENT"; }
                });
    }

    /**
     * Registers a JVM capability definition.
     */
    public <T> void register(String name, Class<T> type, JvmCapabilityRisk risk, JvmCapabilityFactory<T> factory) {
        if (closed.get()) throw new IllegalStateException("JvmCapabilityRegistry is closed");
        definitions.put(name, new JvmCapabilityDefinition<>(name, type, risk, factory));
    }

    /**
     * Returns the risk level for a capability.
     */
    public JvmCapabilityRisk riskLevel(String capabilityName) {
        JvmCapabilityDefinition<?> def = definitions.get(capabilityName);
        return def != null ? def.risk() : null;
    }

    /**
     * Checks if a capability is HIGH or CRITICAL risk (DENY by default).
     */
    public boolean isHighRisk(String capabilityName) {
        JvmCapabilityRisk risk = riskLevel(capabilityName);
        return risk == JvmCapabilityRisk.HIGH || risk == JvmCapabilityRisk.CRITICAL;
    }

    /**
     * Attempts to grant a JVM capability to a target scope.
     * <p>
     * For HIGH/CRITICAL capabilities, the caller must have explicit permission.
     * DENY is the default if no permission policy allows it.
     *
     * @param capabilityName the JVM capability name
     * @param targetScope    the scope to grant to
     * @param permissionManager the permission manager to check policies
     * @return the granted token, or empty if denied
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<CapabilityToken<T>> grant(String capabilityName, Scope targetScope,
                                                    PermissionManager permissionManager) {
        if (closed.get()) {
            throw new IllegalStateException("JvmCapabilityRegistry is closed");
        }

        JvmCapabilityDefinition<T> def = (JvmCapabilityDefinition<T>) definitions.get(capabilityName);
        if (def == null) {
            auditLog.capabilityGranted(targetScope, capabilityName, "registry:unknown");
            return Optional.empty();
        }

        // DENY default for HIGH/CRITICAL
        if (def.risk() == JvmCapabilityRisk.HIGH || def.risk() == JvmCapabilityRisk.CRITICAL) {
            boolean allowed = permissionManager.hasPermission(targetScope, "jvm." + capabilityName);
            auditLog.permissionDecision(targetScope, "jvm." + capabilityName, allowed, "jvm-capability-policy");
            if (!allowed) {
                auditLog.capabilityGranted(targetScope, capabilityName, "registry:denied");
                return Optional.empty();
            }
        }

        // Grant the capability
        T implementation = def.factory().create(targetScope);
        CapabilityToken<T> token = targetScope.grantCapability(def.type(), implementation);
        auditLog.capabilityGranted(targetScope, capabilityName, "registry:granted");

        return Optional.of(token);
    }

    /**
     * Revokes a JVM capability from a scope (audited).
     */
    public void revoke(String capabilityName, Scope targetScope) {
        var cap = targetScope.getCapability(
                definitions.get(capabilityName) != null
                        ? definitions.get(capabilityName).type()
                        : null);
        cap.ifPresent(c -> {
            c.revoke();
            auditLog.capabilityRevoked(targetScope, capabilityName, "registry:revoked");
        });
    }

    /**
     * Returns all registered capability names.
     */
    public Set<String> registeredCapabilities() {
        return Collections.unmodifiableSet(definitions.keySet());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            definitions.clear();
        }
    }

    /**
     * Factory for creating JVM capability implementations.
     */
    @FunctionalInterface
    public interface JvmCapabilityFactory<T> {
        T create(Scope scope);
    }

    record JvmCapabilityDefinition<T>(
            String name,
            Class<T> type,
            JvmCapabilityRisk risk,
            JvmCapabilityFactory<T> factory
    ) {
    }
}
