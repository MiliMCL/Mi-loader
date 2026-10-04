package org.loader.runtime.jvm;

/**
 * Risk levels for JVM capabilities.
 * HIGH and CRITICAL capabilities require explicit permission grant and are audited.
 * DENY is the default for HIGH/CRITICAL (per JVM.md section 35 and SECURITY.md).
 */
public enum JvmCapabilityRisk {
    /**
     * Standard capability — granted on request. No special audit.
     */
    STANDARD,

    /**
     * High-risk capability — default DENY, requires explicit permission.
     * All grants and revocations are audited.
     * Includes: jvm.thread, jvm.classload, jvm.runtime
     */
    HIGH,

    /**
     * Critical capability — default DENY, requires explicit permission + owner scope.
     * All grants, revocations, and escalations are audited.
     * Includes: jvm.instrumentation, native
     */
    CRITICAL
}
