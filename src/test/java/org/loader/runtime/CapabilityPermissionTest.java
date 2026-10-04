package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.jvm.JvmCapabilities;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Capability and Permission subsystems.
 */
class CapabilityPermissionTest {

    @Test
    void capability_grantAndRetrieve() {
        Scope scope = new Scope("cap-test", null);
        scope.grantCapability(String.class, "capability-value");

        var token = scope.getCapability(String.class);
        assertTrue(token.isPresent());
        assertTrue(token.get().isActive());
    }

    @Test
    void capability_notGranted_returnsEmpty() {
        Scope scope = new Scope("no-cap-test", null);
        var token = scope.getCapability(Integer.class);
        assertTrue(token.isEmpty());
    }

    @Test
    void capability_revoke_makesInactive() {
        Scope scope = new Scope("revoke-test", null);
        CapabilityToken<String> token = scope.grantCapability(String.class, "value");
        assertTrue(token.isActive());

        token.revoke();
        assertFalse(token.isActive());

        // getCapability should not return revoked capabilities
        var retrieved = scope.getCapability(String.class);
        assertTrue(retrieved.isEmpty());
    }

    @Test
    void capability_afterScopeStop_becomesInactive() {
        Scope scope = new Scope("stop-test", null);
        CapabilityToken<String> token = scope.grantCapability(String.class, "value");
        assertTrue(token.isActive());

        scope.shutdown();
        assertFalse(token.isActive());
    }

    @Test
    void permission_grantAndCheck() {
        Scope ownerScope = new Scope("perm-owner", null);
        PermissionManager manager = new PermissionManager("test-perms", ownerScope);

        manager.registerPolicy("fs.read", (scope, perm) -> true);
        manager.registerPolicy("fs.write", (scope, perm) -> false);

        Scope scope = new Scope("perm-scope", null);
        assertTrue(manager.hasPermission(scope, "fs.read"));
        assertFalse(manager.hasPermission(scope, "fs.write"));
    }

    @Test
    void permission_unknownPermission_denied() {
        Scope ownerScope = new Scope("perm-owner", null);
        PermissionManager manager = new PermissionManager("test-perms", ownerScope);
        Scope scope = new Scope("perm-scope", null);

        assertFalse(manager.hasPermission(scope, "unknown.permission"));
    }

    @Test
    void permission_checkPermission_throwsWhenDenied() {
        Scope ownerScope = new Scope("perm-owner", null);
        PermissionManager manager = new PermissionManager("test-perms", ownerScope);
        manager.registerPolicy("dangerous.op", (scope, perm) -> false);

        Scope scope = new Scope("perm-scope", null);

        assertThrows(SecurityException.class,
                () -> manager.checkPermission(scope, "dangerous.op"));
    }

    @Test
    void permission_checkPermission_passesWhenGranted() {
        Scope ownerScope = new Scope("perm-owner", null);
        PermissionManager manager = new PermissionManager("test-perms", ownerScope);
        manager.registerPolicy("safe.op", (scope, perm) -> true);

        Scope scope = new Scope("perm-scope", null);

        assertDoesNotThrow(() -> manager.checkPermission(scope, "safe.op"));
    }

    @Test
    void capabilityManager_canGrant() {
        Scope ownerScope = new Scope("cap-owner", null);
        CapabilityManager manager = new CapabilityManager("test-caps", ownerScope);
        manager.registerCapability("test.service", String.class, type -> "implementation");

        assertTrue(manager.canGrant("test.service", String.class));
        assertFalse(manager.canGrant("unknown.service", String.class));
        assertFalse(manager.canGrant("test.service", Integer.class));
    }

    @Test
    void jvmCapabilities_diagnostics() {
        Scope scope = new Scope("jvm-diag", null);
        var token = JvmCapabilities.grantDiagnosticsCapability(scope);
        assertTrue(token.isActive());

        JvmCapabilities.JvmDiagnostics diagnostics = token.get();
        assertNotNull(diagnostics);

        var info = diagnostics.runtimeInfo();
        assertNotNull(info);
        assertTrue(info.containsKey("vmName"));
        assertTrue(info.containsKey("uptime"));
        assertTrue(info.containsKey("maxMemory"));
        assertTrue(info.get("availableProcessors") instanceof Integer);
    }

    @Test
    void jvmCapabilities_classLoading() {
        Scope scope = new Scope("jvm-cl", null);
        var token = JvmCapabilities.grantClassLoadingCapability(scope);
        assertTrue(token.isActive());

        JvmCapabilities.JvmClassLoading cl = token.get();
        assertNotNull(cl);

        assertTrue(cl.loadedClassCount() > 0);
        assertNotNull(cl.platformClassLoader());
        assertFalse(cl.allClassLoaders().isEmpty());
    }
}
