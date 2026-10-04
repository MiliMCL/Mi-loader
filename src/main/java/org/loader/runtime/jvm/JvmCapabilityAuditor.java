package org.loader.runtime.jvm;

import org.loader.runtime.kernel.*;
import org.loader.runtime.security.AuditLog;

import java.util.*;

/**
 * Wires capability lifecycle events to the AuditLog.
 * <p>
 * Per JVM.md §35: all grants and revocations of JVM capabilities MUST be audited.
 * This auditor installs a ScopeListener on the target scope that records:
 * - capability revocation (on scope stopping)
 * - permission escalation detection (attempting to grant CRITICAL without permission)
 *
 * Usage:
 * <pre>
 *   AuditLog log = new AuditLog("audit", runtimeRootScope);
 *   JvmCapabilityAuditor auditor = new JvmCapabilityAuditor(log);
 *   auditor.enableFor(targetScope);
 * </pre>
 */
public class JvmCapabilityAuditor {

    private final AuditLog auditLog;

    public JvmCapabilityAuditor(AuditLog auditLog) {
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    /**
     * Enables audit for a scope. On scope stopping, all active capabilities
     * are recorded in the audit log as revoked.
     */
    public void enableFor(Scope scope) {
        scope.addListener(new CapabilityRevocationListener(scope));
    }

    /**
     * Records a grant escalation: a CRITICAL capability was requested
     * but caller lacked permission.
     */
    public void recordEscalationAttempt(Scope caller, String capabilityName) {
        auditLog.escalation(caller, caller.id(), "kernel:" + capabilityName, false);
    }

    class CapabilityRevocationListener implements ScopeListener {
        private final Scope scope;

        CapabilityRevocationListener(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void onStateChange(Scope changed, LifecycleState from, LifecycleState to) {
            if (to == LifecycleState.STOPPING) {
                // Audit that all capabilities are being revoked through scope shutdown
                auditLog.capabilityRevoked(scope, "all-capabilities", "scope-shutdown");
            }
        }
    }
}
