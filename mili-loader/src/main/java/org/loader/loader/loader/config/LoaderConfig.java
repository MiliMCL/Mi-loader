package org.loader.loader.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Loader configuration.
 * Defines where to find Minecraft, mods, libraries.
 */
public class LoaderConfig {

    private final Path gameDir;
    private final Path minecraftPath;
    private final Path modsPath;
    private final Path librariesPath;
    private final Path configPath;

    public LoaderConfig(Path gameDir, Path minecraftPath, Path modsPath, Path librariesPath, Path configPath) {
        this.gameDir = Objects.requireNonNull(gameDir);
        this.minecraftPath = minecraftPath != null ? minecraftPath : gameDir.resolve("server.jar");
        this.modsPath = modsPath != null ? modsPath : gameDir.resolve("mods");
        this.librariesPath = librariesPath != null ? librariesPath : gameDir.resolve("libraries");
        this.configPath = configPath != null ? configPath : gameDir.resolve("config");
    }

    public static LoaderConfig load(Path gameDir) {
        return new LoaderConfig(gameDir, null, null, null, null);
    }

    /**
     * Creates a default config for the given game directory.
     */
    public static LoaderConfig at(Path gameDir) {
        return load(gameDir);
    }

    public Path getGameDir() { return gameDir; }
    public Path getMinecraftPath() { return minecraftPath; }
    public Path getModsPath() { return modsPath; }
    public Path getLibrariesPath() { return librariesPath; }
    public Path getConfigPath() { return configPath; }
}
