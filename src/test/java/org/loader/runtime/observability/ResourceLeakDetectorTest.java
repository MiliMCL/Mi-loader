package org.loader.runtime.observability;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceLeakDetector.
 */
class ResourceLeakDetectorTest {

    @Test
    void cleanShutdown_noIssues() {
        Scope root = new Scope("root", null);
        ResourceLeakDetector detector = new ResourceLeakDetector(root);
        detector.setThreadTolerance(10);

        // Scope is not stopped, so expect an issue
        ResourceLeakDetector.LeakReport report = detector.verifyCleanShutdown();
        assertFalse(report.isClean());
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.SCOPE_NOT_STOPPED).size() > 0);

        // Now stop scope
        root.shutdown();
        report = detector.verifyCleanShutdown();
        // Scope stopped check should now pass
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.SCOPE_NOT_STOPPED).isEmpty());
    }

    @Test
    void detectsUnclosedResource() {
        Scope root = new Scope("res-test", null);
        // Register a resource that isn't closed
        TestResource res = new TestResource("leaky-resource");
        root.registerResource(res);

        ResourceLeakDetector detector = new ResourceLeakDetector(root);
        detector.setThreadTolerance(20);

        ResourceLeakDetector.LeakReport report = detector.verifyCleanShutdown();
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.UNCLOSED_RESOURCE).size() > 0);

        // Close resource, shut down
        res.close();
        root.shutdown();
        report = detector.verifyCleanShutdown();
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.UNCLOSED_RESOURCE).isEmpty());
    }

    @Test
    void detectsChildScopeLeak() {
        Scope root = new Scope("parent", null);
        Scope child = root.createChild("child");
        ResourceLeakDetector detector = new ResourceLeakDetector(root);
        detector.setThreadTolerance(20);

        // Both not stopped
        ResourceLeakDetector.LeakReport report = detector.verifyCleanShutdown();
        assertEquals(2, report.issuesOfType(ResourceLeakDetector.LeakType.SCOPE_NOT_STOPPED).size());

        // Stop both
        root.shutdown();
        report = detector.verifyCleanShutdown();
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.SCOPE_NOT_STOPPED).isEmpty());
    }

    @Test
    void report_toStringFormat() {
        Scope root = new Scope("fmt-test", null);
        ResourceLeakDetector detector = new ResourceLeakDetector(root);
        detector.setThreadTolerance(20);

        ResourceLeakDetector.LeakReport report = detector.verifyCleanShutdown();
        assertFalse(report.toString().isEmpty());
        assertTrue(report.toString().contains("issues"));

        root.shutdown();
        report = detector.verifyCleanShutdown();
        assertTrue(report.isClean());
        assertEquals("LeakReport[CLEAN]", report.toString());
    }

    @Test
    void threadTolerance_allowsSomeThreads() {
        Scope root = new Scope("thread-tol", null);
        ResourceLeakDetector detector = new ResourceLeakDetector(root);
        detector.setThreadTolerance(50); // high tolerance

        // Stop scope first
        root.shutdown();

        ResourceLeakDetector.LeakReport report = detector.verifyCleanShutdown();
        // All threads should be tolerated
        assertTrue(report.issuesOfType(ResourceLeakDetector.LeakType.STALE_THREAD).isEmpty());
    }

    /**
     * Simple test resource that tracks closed state.
     */
    private static class TestResource implements Resource {
        private final String id;
        private boolean closed = false;

        TestResource(String id) { this.id = id; }

        @Override public String id() { return id; }
        @Override public Scope owner() { return null; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; }
    }
}
