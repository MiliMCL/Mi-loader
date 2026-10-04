package org.loader.api.resource;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages resource loading (configs, assets, data packs) for a mod.
 * <p>
 * All filesystem access is capability-controlled. Path traversal attempts are rejected.
 */
public interface ResourceManager extends Resource {

    /**
     * Returns the base path for resource resolution.
     */
    Path basePath();

    /**
     * Resolves a resource path relative to the base path.
     *
     * @param resourcePath the relative path to resolve
     * @return the absolute path
     */
    Path resolve(String resourcePath);

    /**
     * Reads all bytes from a resource.
     *
     * @param resourcePath the relative path to the resource
     * @return the resource content as bytes
     * @throws IOException if the resource cannot be read
     */
    byte[] readAllBytes(String resourcePath) throws IOException;

    /**
     * Reads a resource as a String.
     *
     * @param resourcePath the relative path to the resource
     * @return the resource content as a string
     * @throws IOException if the resource cannot be read
     */
    String readString(String resourcePath) throws IOException;

    /**
     * Returns whether a resource exists.
     *
     * @param resourcePath the relative path to the resource
     * @return {@code true} if the resource exists
     */
    boolean exists(String resourcePath);
}
