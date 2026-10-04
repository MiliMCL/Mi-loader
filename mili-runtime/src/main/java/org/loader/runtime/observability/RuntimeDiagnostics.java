package org.loader.runtime.observability;

import org.loader.runtime.kernel.*;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime diagnostics and observability.
 * <p>
 * Provides introspection of runtime state for debugging and testing.
 * Per AGENTS.md Invariant: "Tasks, Scope, Mods, Capability, Resource must be observable."
 */
public class RuntimeDiagnostics implements Resource {

    private final String id;
    private final Scope owner;
    private final Instant startTime = Instant.now();
    private final List<DiagnosticEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> metadata = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    public RuntimeDiagnostics(String id, Scope owner) {
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
        return closed;
    }

    /**
     * Records a diagnostic event.
     */
    public void recordEvent(String category, String message) {
        if (!closed) {
            events.add(new DiagnosticEvent(Instant.now(), category, message));
        }
    }

    /**
     * Sets metadata for diagnostics.
     */
    public void setMetadata(String key, String value) {
        metadata.put(key, value);
    }

    /**
     * Creates a full diagnostic snapshot of the runtime.
     */
    public DiagnosticSnapshot captureSnapshot() {
        return new DiagnosticSnapshot(
                Instant.now(),
                startTime,
                new LinkedHashMap<>(metadata),
                captureThreadInfo(),
                captureScopeTree(owner)
        );
    }

    /**
     * Checks for resource leaks.
     * Returns a list of detected issues.
     */
    public List<LeakIssue> detectLeaks() {
        List<LeakIssue> issues = new ArrayList<>();
        detectScopeLeaks(owner, issues);
        detectOrphanResources(issues);
        return Collections.unmodifiableList(issues);
    }

    private void detectScopeLeaks(Scope scope, List<LeakIssue> issues) {
        for (Scope child : scope.children()) {
            if (child.state() == LifecycleState.STOPPING) {
                issues.add(new LeakIssue(LeakIssue.Type.HALF_STOPPED_SCOPE,
                        "Scope in STOPPING state: " + child.id()));
            }
            detectScopeLeaks(child, issues);
        }
    }

    private void detectOrphanResources(List<LeakIssue> issues) {
        // Check global registry for orphans if available
    }

    private Map<String, Object> captureThreadInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        info.put("threadCount", threadBean.getThreadCount());
        info.put("peakThreadCount", threadBean.getPeakThreadCount());
        info.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        return info;
    }

    private ScopeInfo captureScopeTree(Scope scope) {
        List<ScopeInfo> children = new ArrayList<>();
        for (Scope child : scope.children()) {
            children.add(captureScopeTree(child));
        }
        return new ScopeInfo(scope.id(), scope.state(), Collections.unmodifiableList(children));
    }

    /**
     * Diagnostic snapshot - immutable view of runtime state.
     */
    public record DiagnosticSnapshot(
            Instant capturedAt,
            Instant runtimeStartedAt,
            Map<String, String> metadata,
            Map<String, Object> threadInfo,
            ScopeInfo rootScope
    ) {
        public long uptimeMillis() {
            return java.time.Duration.between(runtimeStartedAt, capturedAt).toMillis();
        }
    }

    /**
     * Scope tree info.
     */
    public record ScopeInfo(String id, LifecycleState state, List<ScopeInfo> children) {
        public int totalScopes() {
            return 1 + children.stream().mapToInt(ScopeInfo::totalScopes).sum();
        }
    }

    /**
     * Diagnostic event.
     */
    public record DiagnosticEvent(Instant timestamp, String category, String message) {
    }

    /**
     * Detected leak issue.
     */
    public record LeakIssue(Type type, String description) {
        public enum Type {
            HALF_STOPPED_SCOPE,
            ORPHAN_RESOURCE,
            LEAKED_THREAD,
            OPEN_SOCKET,
            STALE_CLASSLOADER
        }
    }

    @Override
    public void close() {
        closed = true;
        events.clear();
        metadata.clear();
    }
}
