package org.loader.api.permission;

/**
 * Manages permission grants and evaluation.
 * <p>
 * Permissions are separate from lifecycle: lifecycle answers "how long may this exist",
 * permission answers "what may this do".
 */
public interface PermissionManager {

    /**
     * Registers a permission policy.
     *
     * @param permission the permission string identifier
     * @param policy     the policy for evaluating the permission
     */
    void registerPolicy(String permission, PermissionPolicy policy);

    /**
     * Checks whether the caller has a specific permission.
     *
     * @param permission the permission to check
     * @return {@code true} if the permission is granted
     */
    boolean hasPermission(String permission);

    /**
     * Checks whether the caller has a specific {@link Permission} enum value.
     *
     * @param permission the permission to check
     * @return {@code true} if the permission is granted
     */
    default boolean hasPermission(Permission permission) {
        return hasPermission(permission.id());
    }

    /**
     * Validates access or throws a {@link org.loader.api.exception.PermissionDeniedException}.
     *
     * @param permission the permission to validate
     * @throws org.loader.api.exception.PermissionDeniedException if the permission is not granted
     */
    default void checkPermission(String permission) {
        if (!hasPermission(permission)) {
            throw new org.loader.api.exception.PermissionDeniedException(
                    "Permission denied: " + permission);
        }
    }

    /**
     * Validates access for a {@link Permission} enum value.
     *
     * @param permission the permission to validate
     * @throws org.loader.api.exception.PermissionDeniedException if the permission is not granted
     */
    default void checkPermission(Permission permission) {
        checkPermission(permission.id());
    }

    /**
     * Returns whether this manager is closed.
     */
    boolean isClosed();

    /**
     * Policy for evaluating permissions.
     */
    @FunctionalInterface
    interface PermissionPolicy {
        boolean evaluate(String permission);
    }
}
