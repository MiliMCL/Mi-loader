package org.loader.runtime.service;

import org.loader.runtime.kernel.Scope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages resource loading (configs, assets, data packs).
 * <p>
 * All filesystem access is capability-controlled.
 */
public class ResourceManager implements org.loader.runtime.kernel.Resource {

    private final String id;
    private final Scope owner;
    private final Path basePath;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ResourceManager(String id, Scope owner, Path basePath) {
        this.id = Objects.requireNonNull(id);
        this.owner = Objects.requireNonNull(owner);
        this.basePath = Objects.requireNonNull(basePath);
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

    public Path basePath() {
        return basePath;
    }

    /**
     * Resolves a resource path relative to the base path.
     */
    public Path resolve(String resourcePath) {
        return basePath.resolve(resourcePath).normalize();
    }

    /**
     * Opens an input stream for reading a resource.
     */
    public InputStream openStream(String resourcePath) throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("ResourceManager is closed");
        }
        Path path = resolve(resourcePath);
        if (!path.startsWith(basePath)) {
            throw new SecurityException("Path traversal attempt: " + resourcePath);
        }
        return Files.newInputStream(path);
    }

    /**
     * Reads all bytes from a resource.
     */
    public byte[] readAllBytes(String resourcePath) throws IOException {
        try (InputStream is = openStream(resourcePath)) {
            return is.readAllBytes();
        }
    }

    /**
     * Reads a resource as a String.
     */
    public String readString(String resourcePath) throws IOException {
        return new String(readAllBytes(resourcePath));
    }

    /**
     * Returns whether a resource exists.
     */
    public boolean exists(String resourcePath) {
        return Files.exists(resolve(resourcePath));
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
