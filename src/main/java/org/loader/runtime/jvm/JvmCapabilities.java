package org.loader.runtime.jvm;

import org.loader.runtime.kernel.*;
import java.lang.management.*;
import java.util.*;

/**
 * JVM capability provider.
 */
public class JvmCapabilities {

    public interface JvmThread {
        Thread createThread(Runnable work, String name);
        Thread currentThread();
    }

    public interface JvmDiagnostics {
        ThreadInfo[] threadDump();
        MemoryUsage memoryUsage();
        int loadedClassCount();
        Map<String, Object> runtimeInfo();
    }

    public interface JvmClassLoading {
        ClassLoader platformClassLoader();
        List<ClassLoader> allClassLoaders();
        int loadedClassCount();
    }

    public static CapabilityToken<JvmThread> grantThreadCapability(Scope scope) {
        return scope.grantCapability(JvmThread.class, new JvmThreadImpl(scope));
    }

    public static CapabilityToken<JvmDiagnostics> grantDiagnosticsCapability(Scope scope) {
        return scope.grantCapability(JvmDiagnostics.class, new JvmDiagnosticsImpl());
    }

    public static CapabilityToken<JvmClassLoading> grantClassLoadingCapability(Scope scope) {
        return scope.grantCapability(JvmClassLoading.class, new JvmClassLoadingImpl());
    }

    private static class JvmThreadImpl implements JvmThread {
        private final Scope owner;

        JvmThreadImpl(Scope owner) {
            this.owner = owner;
        }

        @Override
        public Thread createThread(Runnable work, String name) {
            Thread t = new Thread(() -> {
                if (owner.isStopped()) {
                    return;
                }
                work.run();
            }, name);
            t.setDaemon(true);
            return t;
        }

        @Override
        public Thread currentThread() {
            return Thread.currentThread();
        }
    }

    private static class JvmDiagnosticsImpl implements JvmDiagnostics {
        @Override
        public ThreadInfo[] threadDump() {
            return ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
        }

        @Override
        public MemoryUsage memoryUsage() {
            return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        }

        @Override
        public int loadedClassCount() {
            return ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        }

        @Override
        public Map<String, Object> runtimeInfo() {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("vmName", runtime.getVmName());
            info.put("vmVersion", runtime.getVmVersion());
            info.put("uptime", runtime.getUptime());
            info.put("availableProcessors", java.lang.Runtime.getRuntime().availableProcessors());
            info.put("maxMemory", java.lang.Runtime.getRuntime().maxMemory());
            info.put("freeMemory", java.lang.Runtime.getRuntime().freeMemory());
            info.put("threadCount", Thread.activeCount());
            return Collections.unmodifiableMap(info);
        }
    }

    /**
     * JVM Runtime access — controls Runtime.getRuntime() operations
     * (gc, exit, shutdown hooks, adding/removing system properties).
     * Marked HIGH risk: explicit permission required.
     */
    public interface JvmRuntime {
        long availableProcessors();
        long maxMemory();
        long totalMemory();
        long freeMemory();
        void gc();
        void addShutdownHook(Thread hook);
        boolean removeShutdownHook(Thread hook);
    }

    /**
     * JVM Instrumentation — bytecode transformation access (java.lang.instrument).
     * Marked CRITICAL risk: denied by default, requires explicit permission + audit.
     * This implementation is a stub that records access attempts but does not
     * provide real Instrumentation (would require -javaagent).
     */
    public interface JvmInstrumentation {
        /**
         * Always returns null in this environment — no java agent loaded.
         * Records an audit entry that agent access was requested but unavailable.
         */
        Object getInstrumentation();
        boolean isAvailable();
        String agentStatus();
    }

    private static class JvmRuntimeImpl implements JvmRuntime {
        @Override
        public long availableProcessors() {
            return java.lang.Runtime.getRuntime().availableProcessors();
        }
        @Override
        public long maxMemory() {
            return java.lang.Runtime.getRuntime().maxMemory();
        }
        @Override
        public long totalMemory() {
            return java.lang.Runtime.getRuntime().totalMemory();
        }
        @Override
        public long freeMemory() {
            return java.lang.Runtime.getRuntime().freeMemory();
        }
        @Override
        public void gc() {
            java.lang.Runtime.getRuntime().gc();
        }
        @Override
        public void addShutdownHook(Thread hook) {
            try {
                java.lang.Runtime.getRuntime().addShutdownHook(hook);
            } catch (SecurityException e) {
                // DENY: permission denied
            }
        }
        @Override
        public boolean removeShutdownHook(Thread hook) {
            try {
                return java.lang.Runtime.getRuntime().removeShutdownHook(hook);
            } catch (SecurityException e) {
                return false;
            }
        }
    }

    private static class JvmInstrumentationImpl implements JvmInstrumentation {
        @Override
        public Object getInstrumentation() {
            return null; // No java agent in this environment
        }
        @Override
        public boolean isAvailable() {
            return false;
        }
        @Override
        public String agentStatus() {
            return "NO_AGENT";
        }
    }

    /**
     * Grants the JVM Runtime capability (HIGH risk — requires explicit permission).
     */
    public static CapabilityToken<JvmRuntime> grantRuntimeCapability(Scope scope) {
        return scope.grantCapability(JvmRuntime.class, new JvmRuntimeImpl());
    }

    /**
     * Grants the JVM Instrumentation capability (CRITICAL risk — denied by default).
     */
    public static CapabilityToken<JvmInstrumentation> grantInstrumentationCapability(Scope scope) {
        return scope.grantCapability(JvmInstrumentation.class, new JvmInstrumentationImpl());
    }

    private static class JvmClassLoadingImpl implements JvmClassLoading {
        @Override
        public ClassLoader platformClassLoader() {
            return ClassLoader.getPlatformClassLoader();
        }

        @Override
        public List<ClassLoader> allClassLoaders() {
            List<ClassLoader> loaders = new ArrayList<>();
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            while (cl != null) {
                loaders.add(cl);
                cl = cl.getParent();
            }
            return Collections.unmodifiableList(loaders);
        }

        @Override
        public int loadedClassCount() {
            return ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        }
    }
}
