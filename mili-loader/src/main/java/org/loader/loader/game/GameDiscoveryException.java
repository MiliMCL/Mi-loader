package org.loader.loader.game;

/**
 * Thrown when the game (Minecraft) cannot be located or loaded.
 */
public class GameDiscoveryException extends Exception {

    public GameDiscoveryException(String message) {
        super(message);
    }

    public GameDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
