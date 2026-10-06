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
        // Default: server.jar (vanilla server layout). The installer writes
        // <version>.jar instead, but MinecraftDiscovery falls back to scanning
        // the game dir for a JAR containing MC classes, so both layouts work.
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

    /**
     * 同 {@link #at(Path)}，但显式指定 Mod 目录。
     *
     * <p><b>为什么需要它</b>：分发包的布局是
     * <pre>
     *   mili-0.1.0-mc26.2/
     *     bin/     core/     mods/     game/26.2.jar
     * </pre>
     * Mod 在<b>分发根</b>的 {@code mods/}，而 Minecraft 在
     * {@code game/}。启动脚本把 {@code game/} 当作 gameDir 传进来 ——
     * 这是对的，Minecraft 只在 game/ 里。但 {@code gameDir.resolve("mods")}
     * 于是指向 {@code game/mods/}，一个从来不存在的目录。
     *
     * <p>后果不是报错，而是<b>零个Mod 被发现</b>：没有异常、没有警告，
     * 只是游戏照常启动、Mod 内容一片空白。这正是这套代码里反复要消灭的
     * 静默失效。
     */
    public static LoaderConfig at(Path gameDir, Path modsDir) {
        return new LoaderConfig(gameDir, null, modsDir, null, null);
    }

    public Path getGameDir() { return gameDir; }
    public Path getMinecraftPath() { return minecraftPath; }
    public Path getModsPath() { return modsPath; }
    public Path getLibrariesPath() { return librariesPath; }
    public Path getConfigPath() { return configPath; }
}
