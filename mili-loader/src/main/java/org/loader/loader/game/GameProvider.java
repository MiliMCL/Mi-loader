package org.loader.loader.game;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.RuntimeEnvironment;

import java.nio.file.Path;
import java.util.List;

/**
 * Service Provider Interface for game (Minecraft) discovery and launch.
 * <p>
 * The loader does not hardcode the game entry point. Instead, a GameProvider
 * implementation is discovered (via SPI) that knows how to:
 * <ol>
 *   <li>Locate the game JAR</li>
 *   <li>Enumerate required libraries</li>
 *   <li>Invoke the game's main class under loader control (in-process)</li>
 * </ol>
 *
 * <p>This decouples the loader from Minecraft specifics and allows
 * alternative game implementations (e.g. test harnesses) to plug in.
 *
 * @see MinecraftGameProvider
 */
public interface GameProvider {

    /**
     * Returns the unique game identifier (e.g. "minecraft").
     */
    String getGameId();

    /**
     * Returns the human-readable game name.
     */
    String getGameName();

    /**
     * Returns the raw game version string (e.g. "26.2").
     */
    String getRawGameVersion();

    /**
     * Returns the normalized version string suitable for semantic comparison.
     */
    String getNormalizedGameVersion();

    /**
     * Returns the fully-qualified main class name to invoke.
     * For Minecraft client: "net.minecraft.client.main.Main"
     * For Minecraft server: "net.minecraft.server.Main"
     */
    String getEntrypoint();

    /**
     * Returns the launch directory (game root).
     */
    Path getLaunchDirectory();

    /**
     * Returns true if game JARs are obfuscated and need mapping resolution.
     */
    boolean isObfuscated();

    /**
     * Returns the detected runtime environment.
     * Default: CLIENT.
     */
    default RuntimeEnvironment getEnvironment() {
        return RuntimeEnvironment.CLIENT;
    }

    /**
     * Locates the game JAR and libraries.
     *
     * @param gameDir the game root directory
     * @return list of all classpath entries (game JAR first, then libraries)
     * @throws GameDiscoveryException if the game cannot be found
     */
    List<Path> locateGame(Path gameDir) throws GameDiscoveryException;

    /**
     * Invokes the game's main method in-process via TransformingClassLoader.
     * The game runs in the same JVM as the loader, enabling lifecycle bridging.
     *
     * @param gameDir            the game directory
     * @param runtime            the pre-started Mili Runtime
     * @param classLoaderManager the manager holding mod classloaders
     * @param gameClasspath      the full game classpath from locateGame()
     * @param args               command-line arguments to pass through
     * @throws Exception if game launch fails
     */
    void launch(Path gameDir,
                Runtime runtime,
                ModClassLoaderManager classLoaderManager,
                List<Path> gameClasspath,
                String[] args) throws Exception;
}
