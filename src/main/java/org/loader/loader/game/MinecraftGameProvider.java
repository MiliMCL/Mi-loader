package org.loader.loader.game;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.hook.EntryPointHook;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.minecraft.RuntimeEnvironment;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Mili GameProvider for Minecraft 26.2 Client.
 *
 * <p>Launch strategy: <b>in-process</b> {@link URLClassLoader}.
 * <ul>
 *   <li>MC classes + mod classes loaded in same JVM via URLClassLoader</li>
 *   <li>Lifecycle bridged to Mili Runtime via {@link EntryPointHook}</li>
 *   <li>Mods use Mili-native APIs (Scope/Capability/Scheduler/EventBus)</li>
 * </ul>
 *
 * <p>Mili is NOT a Fabric port — this provider has no Knot/Mixin/LaunchWrapper concepts.
 * It uses Mili's own Runtime as the mod platform.
 */
public class MinecraftGameProvider implements GameProvider {

    private String detectedVersion;
    private MinecraftDiscovery.GameType gameType;
    private List<Path> lastClasspath;
    private RuntimeEnvironment detectedEnvironment;

    @Override
    public String getGameId() { return "minecraft"; }

    @Override
    public String getGameName() { return "Minecraft"; }

    @Override
    public String getRawGameVersion() {
        return detectedVersion != null ? detectedVersion : "unknown";
    }

    @Override
    public String getNormalizedGameVersion() { return getRawGameVersion(); }

    @Override
    public String getEntrypoint() {
        if (gameType == MinecraftDiscovery.GameType.CLIENT) {
            return "net.minecraft.client.main.Main";
        }
        return "net.minecraft.server.Main";
    }

    @Override
    public Path getLaunchDirectory() { return Path.of("."); }

    @Override
    public boolean isObfuscated() { return false; }

    @Override
    public List<Path> locateGame(Path gameDir) throws GameDiscoveryException {
        LoaderConfig config = LoaderConfig.at(gameDir);
        MinecraftDiscovery discovery = MinecraftDiscovery.scan(config);
        if (!discovery.found()) {
            throw new GameDiscoveryException(
                    "Minecraft JAR not found in " + gameDir);
        }
        gameType = discovery.getGameType();
        detectedVersion = discovery.getVersion();
        lastClasspath = discovery.getClasspath();
        detectedEnvironment = (gameType == MinecraftDiscovery.GameType.CLIENT)
                ? RuntimeEnvironment.CLIENT
                : RuntimeEnvironment.DEDICATED_SERVER;

        // Validate critical libraries are present
        List<String> libWarnings = org.loader.loader.discovery.LibraryResolver
                .validateClasspath(lastClasspath);
        if (!libWarnings.isEmpty()) {
            System.err.println("[Mili] Library warnings for Minecraft " + detectedVersion + ":");
            for (String w : libWarnings) {
                System.err.println("  WARNING: " + w);
            }
        }

        return lastClasspath;
    }

    /**
     * Launches Minecraft in-process via URLClassLoader.
     * <p>
     * MC runs in the same JVM as the loader. Runtime lifecycle bridging
     * is handled by EntryPointHook.
     */
    @Override
    public void launch(Path gameDir,
                       Runtime runtime,
                       ModClassLoaderManager classLoaderManager,
                       List<Path> gameClasspath,
                       String[] args) throws Exception {
        // Build URL array from classpath
        URL[] urls = new URL[gameClasspath.size()];
        for (int i = 0; i < gameClasspath.size(); i++) {
            urls[i] = gameClasspath.get(i).toUri().toURL();
        }

        // Use URLClassLoader (no bytecode transformation needed for Mili's clean design)
        URLClassLoader gameCL = new URLClassLoader(
                "minecraft-game", urls,
                MinecraftGameProvider.class.getClassLoader());

        // Install lifecycle hook
        EntryPointHook hook = new EntryPointHook();
        hook.install(gameCL, runtime, classLoaderManager, detectedEnvironment);

        // Invoke MC main in a dedicated thread
        hook.invokeMinecraftMain(gameCL, args != null ? args : new String[0]);
    }
}
