package org.loader.runtime.security;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Security AuditLog per SECURITY.md.
 */
class AuditLogTest {

    @Test
    void auditLog_recordsCapabilityGrant() {
        Scope scope = new Scope("audit-test", null);
        AuditLog log = new AuditLog("test-audit", scope);
        scope.registerResource(log);

        log.capabilityGranted(scope, "RenderService", "kernel");

        assertEquals(1, log.entries().size());
        AuditLog.AuditEntry entry = log.entries().get(0);
        assertEquals(AuditLog.AuditEvent.CAPABILITY_GRANTED, entry.event());
        assertTrue(entry.success());
        assertTrue(entry.detail().contains("RenderService"));
    }

    @Test
    void auditLog_recordsPermissionDecision() {
        Scope scope = new Scope("perm-audit", null);
        AuditLog log = new AuditLog("perm-audit-log", scope);
        scope.registerResource(log);

        log.permissionDecision(scope, "fs.read", true, "allow-all-policy");
        log.permissionDecision(scope, "fs.write", false, "deny-policy");

        assertEquals(2, log.entries().size());

        var denied = log.denied();
        assertEquals(1, denied.size());
        assertEquals("fs.write", denied.get(0).detail());
    }

    @Test
    void auditLog_filtersByTarget() {
        Scope scope1 = new Scope("target-1", null);
        Scope scope2 = new Scope("target-2", null);
        AuditLog log = new AuditLog("filter-test", scope1);
        scope1.registerResource(log);

        log.privilegedUse(scope1, "native.load", true);
        log.privilegedUse(scope2, "native.load", false);

        assertEquals(1, log.entriesFor("target-1").size());
        assertEquals(1, log.entriesFor("target-2").size());
    }

    @Test
    void auditLog_recordsEscalation() {
        Scope scope = new Scope("esc-test", null);
        AuditLog log = new AuditLog("esc-audit", scope);
        scope.registerResource(log);

        log.escalation(scope, "user-scope", "kernel-scope", false);

        assertEquals(1, log.entries().size());
        assertFalse(log.entries().get(0).success());
    }

    @Test
    void auditLog_doesNotRecordWhenClosed() {
        Scope scope = new Scope("closed-test", null);
        AuditLog log = new AuditLog("closed-audit", scope);
        scope.registerResource(log);
        log.close();

        log.capabilityGranted(scope, "test", "kernel");
        assertTrue(log.entries().isEmpty());
    }

    @Test
    void auditLog_clearRemovesAll() {
        Scope scope = new Scope("clear-test", null);
        AuditLog log = new AuditLog("clear-audit", scope);
        scope.registerResource(log);

        log.escalation(scope, "a", "b", false);
        log.capabilityRevoked(scope, "svc", "kernel");
        assertFalse(log.entries().isEmpty());

        log.clear();
        assertTrue(log.entries().isEmpty());
    }
}
