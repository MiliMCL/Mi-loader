package org.loader.runtime.kernel;

/**
 * A managed resource in the Runtime.
 * <p>
 * Every resource has an owner (Scope), a defined lifetime, and a cleanup path.
 * Implementations must ensure that {@link #close()} is idempotent and safe to
 * call multiple times.
 */
public interface Resource extends AutoCloseable {

    /**
     * Returns the unique identifier for this resource.
     */
    String id();

    /**
     * Returns the owner scope of this resource.
     */
    Scope owner();

    /**
     * Returns whether this resource has been closed.
     */
    boolean isClosed();

    /**
     * Closes the resource, releasing any underlying state.
     * Must be idempotent.
     */
    @Override
    void close();
}
