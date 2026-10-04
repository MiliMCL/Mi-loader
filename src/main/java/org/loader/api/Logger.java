package org.loader.api;

/**
 * Logger interface for mods.
 * <p>
 * Provides leveled logging output scoped to a mod.
 */
public interface Logger {

    /**
     * Logs an informational message.
     */
    void info(String message);

    /**
     * Logs a warning message.
     */
    void warning(String message);

    /**
     * Logs a severe/error message.
     */
    void severe(String message);

    /**
     * Logs a severe/error message with a throwable.
     */
    void severe(String message, Throwable throwable);

    /**
     * Logs a fine (debug) message.
     */
    void fine(String message);

    /**
     * Logs a finer (verbose debug) message.
     */
    void finer(String message);

    /**
     * Logs a finest (trace) message.
     */
    void finest(String message);
}
