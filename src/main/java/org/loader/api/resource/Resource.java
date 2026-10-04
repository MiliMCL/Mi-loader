package org.loader.api.resource;

/**
 * A managed resource in the Runtime.
 * <p>
 * Every resource has a unique identifier, a defined lifetime, and a cleanup path.
 * Implementations must ensure that {@link #close()} is idempotent and safe to
 * call multiple times.
 *
 * <p>This facade delegates to the runtime {@code org.loader.runtime.kernel.Resource}.
 */
public interface Resource extends AutoCloseable {

    /**
     * Returns the unique identifier for this resource.
     */
    String id();

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
