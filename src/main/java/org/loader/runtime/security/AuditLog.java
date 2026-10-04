package org.loader.runtime.security;

import org.loader.runtime.kernel.Scope;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Security audit log per SECURITY.md: records capability requests,
 * permission decisions, escalations, revocations, and privileged API use.
 * <p>
 * The AuditLog is a Resource owned by a Scope, so it is cleaned up
 * automatically when the scope stops.
 */
public class AuditLog implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final Queue<AuditEntry> entries = new ConcurrentLinkedQueue<>();
    private volatile boolean closed = false;
    private final int maxEntries;

    public AuditLog(String id, Scope owner) {
        this(id, owner, 10_000);
    }

    public AuditLog(String id, Scope owner, int maxEntries) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.maxEntries = maxEntries;
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
     * Records a capability grant event.
     */
    public void capabilityGranted(Scope target, String capabilityType, String grantedBy) {
        record(AuditEvent.CAPABILITY_GRANTED, target, capabilityType,
                "granted by " + grantedBy, true);
    }

    /**
     * Records a capability revoke event.
     */
    public void capabilityRevoked(Scope target, String capabilityType, String revokedBy) {
        record(AuditEvent.CAPABILITY_REVOKED, target, capabilityType,
                "revoked by " + revokedBy, true);
    }

    /**
     * Records a permission check decision.
     */
    public void permissionDecision(Scope target, String permission, boolean allowed, String policy) {
        record(AuditEvent.PERMISSION_DECISION, target, permission,
                (allowed ? "ALLOWED" : "DENIED") + " via " + policy, allowed);
    }

    /**
     * Records a privilege escalation attempt.
     */
    public void escalation(Scope target, String from, String to, boolean success) {
        record(AuditEvent.ESCALATION, target, from + " to " + to,
                success ? "SUCCESS" : "BLOCKED", success);
    }

    /**
     * Records privileged API use.
     */
    public void privilegedUse(Scope target, String api, boolean allowed) {
        record(AuditEvent.PRIVILEGED_USE, target, api,
                allowed ? "ALLOWED" : "DENIED", allowed);
    }

    private void record(AuditEvent event, Scope target, String detail, String outcome, boolean success) {
        if (closed) return;
        AuditEntry entry = new AuditEntry(
                System.nanoTime(),
                Thread.currentThread().getName(),
                event,
                target != null ? target.id() : "kernel",
                detail,
                outcome,
                success
        );
        entries.add(entry);
        // Trim if exceeds max
        while (entries.size() > maxEntries) {
            entries.poll();
        }
    }

    /**
     * Returns all audit entries (immutable snapshot).
     */
    public List<AuditEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Returns entries for a specific target scope.
     */
    public List<AuditEntry> entriesFor(String scopeId) {
        return entries.stream()
                .filter(e -> e.targetScopeId().equals(scopeId))
                .toList();
    }

    /**
     * Returns failed/denied entries.
     */
    public List<AuditEntry> denied() {
        return entries.stream()
                .filter(e -> !e.success())
                .toList();
    }

    /**
     * Clears all entries.
     */
    public void clear() {
        entries.clear();
    }

    @Override
    public void close() {
        closed = true;
        entries.clear();
    }

    public enum AuditEvent {
        CAPABILITY_GRANTED,
        CAPABILITY_REVOKED,
        PERMISSION_DECISION,
        ESCALATION,
        PRIVILEGED_USE
    }

    public record AuditEntry(
            long timestampNanos,
            String threadName,
            AuditEvent event,
            String targetScopeId,
            String detail,
            String outcome,
            boolean success
    ) {
        @Override
        public String toString() {
            return String.format("[%s] %s %s target=%s detail=%s outcome=%s",
                    threadName,
                    event,
                    targetScopeId,
                    detail,
                    outcome);
        }
    }
}
