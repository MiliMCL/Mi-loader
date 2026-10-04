package org.loader.runtime.util;

import org.loader.runtime.kernel.Scope;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Logger bound to a Mod scope.
 * <p>
 * Output includes the mod name, scope id, and timestamp.
 */
public class ModLogger {

    private final String name;
    private final Scope scope;
    private Level level = Level.INFO;

    public ModLogger(String name, Scope scope) {
        this.name = name;
        this.scope = scope;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public Level getLevel() {
        return level;
    }

    public void info(String message) {
        log(Level.INFO, message, null);
    }

    public void warning(String message) {
        log(Level.WARNING, message, null);
    }

    public void severe(String message) {
        log(Level.SEVERE, message, null);
    }

    public void severe(String message, Throwable throwable) {
        log(Level.SEVERE, message, throwable);
    }

    public void fine(String message) {
        log(Level.FINE, message, null);
    }

    public void finer(String message) {
        log(Level.FINER, message, null);
    }

    public void finest(String message) {
        log(Level.FINEST, message, null);
    }

    public boolean isLoggable(Level checkLevel) {
        return checkLevel.intValue() >= level.intValue();
    }

    public void log(Level logLevel, String message, Throwable throwable) {
        if (scope.isStopped()) {
            return;
        }
        if (!isLoggable(logLevel)) {
            return;
        }

        String formatted = String.format("[%s] [%s] [%s] [%s] %s",
                Instant.now(), logLevel.getName(), name, scope.id(), message);

        if (throwable != null) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            formatted += "\n" + sw;
        }

        if (logLevel.intValue() >= Level.WARNING.intValue()) {
            System.err.println(formatted);
        } else {
            System.out.println(formatted);
        }
    }
}
