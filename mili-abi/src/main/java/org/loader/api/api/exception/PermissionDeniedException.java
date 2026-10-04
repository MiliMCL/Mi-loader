package org.loader.api.exception;

import org.loader.api.permission.Permission;

/**
 * Exception thrown when a permission check fails.
 */
public class PermissionDeniedException extends MiliException {

    private final String permission;

    /**
     * Creates a new PermissionDeniedException.
     *
     * @param message the error message
     */
    public PermissionDeniedException(String message) {
        super(message);
        this.permission = null;
    }

    /**
     * Creates a new PermissionDeniedException for a specific permission.
     *
     * @param permission the denied permission
     * @param message    the error message
     */
    public PermissionDeniedException(String permission, String message) {
        super(message);
        this.permission = permission;
    }

    /**
     * Creates a new PermissionDeniedException for a specific {@link Permission} enum value.
     *
     * @param permission the denied permission
     * @param message    the error message
     */
    public PermissionDeniedException(Permission permission, String message) {
        super(message);
        this.permission = permission.id();
    }

    /**
     * Returns the denied permission string, or {@code null} if unspecified.
     */
    public String permission() {
        return permission;
    }
}
