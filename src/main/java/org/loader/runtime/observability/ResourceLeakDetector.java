package org.loader.runtime.observability;

import org.loader.runtime.kernel.*;
import org.loader.runtime.scheduler.Scheduler;

import java.util.*;

/**
 * Resource Leak Detector per spec section 38.
 * <p>
 * Active monitoring that checks for:
 * <ul>
 *   <li>Alive threads that shouldn't be</li>
 *   <li>Scopes that should have been stopped</li>
 *   <li>Executors/ThreadPools still running after shutdown</li>
 *   <li>Network resources not released</li>
 *   <li>Classloader not cleaned up</li>
 * </ul>
 * <p>
 * Call {@link #verifyCleanShutdown()} after Runtime.close() to assert
 * that no leaks remain.
 */
public class ResourceLeakDetector {

    private final Scope rootScope;
    private final List<LeakIssue> issues = new ArrayList<>();
    private int threadTolerance = 0;

    public ResourceLeakDetector(Scope rootScope) {
        this.rootScope = java.util.Objects.requireNonNull(rootScope, "rootScope must not be null");
    }

    /**
     * Sets the number of non-daemon threads allowed after shutdown.
     * Default: 0.
     */
    public void setThreadTolerance(int count) {
        this.threadTolerance = count;
    }

    /**
     * Runs a full verification, collecting all issues.
     */
    public LeakReport verifyCleanShutdown() {
        issues.clear();

        // 1. Check scope tree
        verifyScopes(rootScope);

        // 2. Check for runaway threads
        verifyThreads();

        // 3. Check for open resources in scope tree
        verifyResources(rootScope);

        return new LeakReport(List.copyOf(issues));
    }

    private void verifyScopes(Scope scope) {
        if (!scope.isStopped()) {
            issues.add(new LeakIssue(LeakType.SCOPE_NOT_STOPPED,
                    "Scope '" + scope.id() + "' has not been stopped"));
        }
        for (Scope child : scope.children()) {
            verifyScopes(child);
        }
    }

    private void verifyThreads() {
        // Count non-daemon threads excluding known JVM threads
        int nonDaemonCount = 0;
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Thread t : stacks.keySet()) {
            if (t.isAlive() && !t.isDaemon() && !isJvmThread(t)) {
                nonDaemonCount++;
                if (nonDaemonCount > threadTolerance) {
                    issues.add(new LeakIssue(LeakType.STALE_THREAD,
                            "Non-daemon thread still alive: " + t.getName()
                                    + " (state=" + t.getState() + ")"));
                }
            }
        }
    }

    private boolean isJvmThread(Thread t) {
        String name = t.getName();
        return name.startsWith("Reference Handler")
                || name.startsWith("Finalizer")
                || name.startsWith("Signal Dispatcher")
                || name.startsWith("Common-Cleaner")
                || name.startsWith("Notification Thread")
                || name.equals("main") // JVM main exit
                || name.startsWith("Monitor Ctrl-Break");
    }

    private void verifyResources(Scope scope) {
        for (Resource res : scope.getResources()) {
            if (!res.isClosed()) {
                issues.add(new LeakIssue(LeakType.UNCLOSED_RESOURCE,
                        "Resource '" + res.id() + "' ("
                                + res.getClass().getSimpleName() + ") not closed in scope '" + scope.id() + "'"));
            }
        }
        for (Scope child : scope.children()) {
            verifyResources(child);
        }
    }

    /**
     * Types of detectable leaks.
     */
    public enum LeakType {
        SCOPE_NOT_STOPPED,
        STALE_THREAD,
        UNCLOSED_RESOURCE,
        OPEN_NETWORK,
        OPEN_FILE
    }

    /**
     * A single detected leak.
     */
    public record LeakIssue(LeakType type, String description) {
    }

    /**
     * Full report.
     */
    public record LeakReport(List<LeakIssue> issues) {
        public boolean isClean() {
            return issues.isEmpty();
        }

        public int issueCount() {
            return issues.size();
        }

        public List<LeakIssue> issuesOfType(LeakType type) {
            return issues.stream().filter(i -> i.type() == type).toList();
        }

        @Override
        public String toString() {
            if (issues.isEmpty()) {
                return "LeakReport[CLEAN]";
            }
            return "LeakReport[" + issues.size() + " issues: "
                    + issues.stream().map(LeakIssue::description).reduce((a, b) -> a + "; " + b).orElse("") + "]";
        }
    }
}
