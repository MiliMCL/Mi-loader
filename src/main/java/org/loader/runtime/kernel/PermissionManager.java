package org.loader.runtime.kernel;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages permission grants.
 * <p>
 * Permissions are separate from lifecycle: lifecycle answers "how long may this exist",
 * permission answers "what may this do".
 */
public class PermissionManager implements Resource {

    private final String id;
    private final Scope owner;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, PermissionPolicy> policies = new ConcurrentHashMap<>();

    public PermissionManager(String id, Scope owner) {
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
        return closed.get();
    }

    /**
     * Registers a permission policy.
     */
    public void registerPolicy(String permission, PermissionPolicy policy) {
        if (closed.get()) {
            throw new IllegalStateException("PermissionManager is closed");
        }
        policies.put(permission, policy);
    }

    /**
     * Checks whether a scope has a specific permission.
     */
    public boolean hasPermission(Scope scope, String permission) {
        PermissionPolicy policy = policies.get(permission);
        if (policy == null) {
            return false;
        }
        return policy.evaluate(scope, permission);
    }

    /**
     * Validates access or throws SecurityException.
     */
    public void checkPermission(Scope scope, String permission) {
        if (!hasPermission(scope, permission)) {
            throw new SecurityException(
                    "Scope " + scope.id() + " lacks permission: " + permission);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        policies.clear();
    }

    /**
     * Policy for evaluating permissions.
     */
    @FunctionalInterface
    public interface PermissionPolicy {
        boolean evaluate(Scope scope, String permission);
    }
}
