package org.loader.loader.game;

import org.loader.loader.discovery.LibraryResolver;
import org.loader.loader.classloader.MinecraftClassLoader;
import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.MinecraftDiscovery;
import org.loader.loader.hook.EntryPointHook;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.kernel.Runtime;

import java.net.URL;
import java.nio.file.Path;
import java.util.List;

/**
 * Mili GameProvider for Minecraft 26.2。
 *
 * <p><b>启动策略</b>：进程内唯一 {@link MinecraftClassLoader}。
 * <ul>
 *   <li>该 CL 是全平台<b>唯一</b>定义 {@code net.minecraft.*} 的地方</li>
 *   <li>所有 ModClassLoader 以它为 parent —— MC 类全局同源</li>
 *   <li>生命周期桥接到 Mili Runtime（见 {@link EntryPointHook}）</li>
 * </ul>
 *
 * <p>Mili 不是 Fabric 移植：无 Knot / Mixin / LaunchWrapper 概念。
 */
public class MinecraftGameProvider implements GameProvider {

    private String detectedVersion;
    private MinecraftDiscovery.GameType gameType;
    private List<Path> lastClasspath;
    private RuntimeEnvironment detectedEnvironment;

    /** 全局唯一的 Minecraft ClassLoader。 */
    private MinecraftClassLoader gameClassLoader;

    @Override
    public String getGameId() {
        return "minecraft";
    }

    @Override
    public String getGameName() {
        return "Minecraft";
    }

    @Override
    public String getRawGameVersion() {
        return detectedVersion != null ? detectedVersion : "unknown";
    }

    @Override
    public String getNormalizedGameVersion() {
        return getRawGameVersion();
    }

    @Override
    public String getEntrypoint() {
        if (gameType == MinecraftDiscovery.GameType.CLIENT) {
            return "net.minecraft.client.main.Main";
        }
        return "net.minecraft.server.Main";
    }

    @Override
    public Path getLaunchDirectory() {
        return Path.of(".");
    }

    @Override
    public boolean isObfuscated() {
        return false;
    }

    @Override
    public RuntimeEnvironment getEnvironment() {
        return detectedEnvironment != null ? detectedEnvironment : RuntimeEnvironment.CLIENT;
    }

    /**
     * 唯一定义 Minecraft 类的 ClassLoader。在 Mod 加载之前必须先建立。
     */
    public MinecraftClassLoader gameClassLoader() {
        return gameClassLoader;
    }

    @Override
    public List<Path> locateGame(Path gameDir) throws GameDiscoveryException {
        LoaderConfig config = LoaderConfig.at(gameDir);
        MinecraftDiscovery discovery = MinecraftDiscovery.scan(config);
        if (!discovery.found()) {
            throw new GameDiscoveryException("Minecraft JAR not found in " + gameDir);
        }
        gameType = discovery.getGameType();
        detectedVersion = discovery.getVersion();
        lastClasspath = discovery.getClasspath();
        detectedEnvironment = (gameType == MinecraftDiscovery.GameType.CLIENT)
                ? RuntimeEnvironment.CLIENT
                : RuntimeEnvironment.DEDICATED_SERVER;

        List<String> libWarnings = LibraryResolver.validateClasspath(lastClasspath);
        for (String w : libWarnings) {
            System.err.println("[Mili] Library warning: " + w);
        }
        return lastClasspath;
    }

    /**
     * 创建 MinecraftClassLoader。必须在任何 Mod ClassLoader 之前调用，
     * 因为后者以其为 parent。
     */
    public MinecraftClassLoader createGameClassLoader(List<Path> gameClasspath)
            throws GameDiscoveryException {
        URL[] urls = new URL[gameClasspath.size()];
        for (int i = 0; i < gameClasspath.size(); i++) {
            try {
                urls[i] = gameClasspath.get(i).toUri().toURL();
            } catch (Exception e) {
                throw new GameDiscoveryException("无法构造 classpath URL: " + e.getMessage());
            }
        }
        gameClassLoader = new MinecraftClassLoader("minecraft-game", urls,
                getClass().getClassLoader());

        // 硬校验：主类必须真的能解析，不能只看文件存在
        String mainClass = getEntrypoint();
        if (!gameClassLoader.canLoad(mainClass)) {
            throw new GameDiscoveryException(
                    "Minecraft 主类无法解析: " + mainClass
                            + "。请确认 gameDir 中的 Minecraft 完整且版本匹配 ("
                            + getRawGameVersion() + ")");
        }
        return gameClassLoader;
    }

    /**
     * 启动 Minecraft。
     *
     * <p>此时 Mod 已经加载完毕（{@code classLoaderManager} 已就绪），
     * MinecraftClassLoader 也已创建。本方法只负责调用游戏 main 并桥接生命周期。
     */
    @Override
    public void launch(Path gameDir,
                       Runtime runtime,
                       ModClassLoaderManager classLoaderManager,
                       List<Path> gameClasspath,
                       String[] args) throws Exception {
        if (gameClassLoader == null) {
            createGameClassLoader(gameClasspath);
        }

        // 注册为 bootstrap 的外部资源 —— 失败/停止时随之释放
        EntryPointHook hook = new EntryPointHook();
        hook.install(gameClassLoader, runtime, classLoaderManager, detectedEnvironment);
        hook.invokeMinecraftMain(gameClassLoader, args != null ? args : new String[0]);
    }
}