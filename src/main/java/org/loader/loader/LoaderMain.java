package org.loader.loader;

import org.loader.loader.classloader.ModClassLoaderManager;
import org.loader.loader.config.LoaderConfig;
import org.loader.loader.discovery.ModDiscovery;
import org.loader.loader.game.GameProvider;
import org.loader.runtime.minecraft.RuntimeEnvironment;
import org.loader.runtime.mod.Mod;
import org.loader.runtime.mod.ModContext;
import org.loader.runtime.mod.ModManifest;

import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Mili Runtime Loader entry point.
 * Boot: discover game -> discover mods -> bootstrap Runtime -> init mods -> launch MC.
 */
public class LoaderMain {

    private final LoaderConfig config;
    private final org.loader.runtime.kernel.Runtime runtime;
    private final ModClassLoaderManager classLoaderManager;
    private final GameProvider gameProvider;
    private Path gameDir;
    private final List<Mod> loadedMods = new ArrayList<>();

    public LoaderMain(LoaderConfig config) {
        this.config = config;
        this.runtime = org.loader.runtime.kernel.Runtime.create("loader-runtime");
        this.classLoaderManager = new ModClassLoaderManager();
        this.gameProvider = discoverGameProvider();
    }

    private static GameProvider discoverGameProvider() {
        ServiceLoader<GameProvider> loader = ServiceLoader.load(GameProvider.class);
        return loader.stream()
                .map(ServiceLoader.Provider::get)
                .findFirst()
                .orElseGet(() -> new org.loader.loader.game.MinecraftGameProvider());
    }

    public static void main(String[] args) throws Exception {
        Path gd = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        LoaderConfig config = LoaderConfig.load(gd);
        LoaderMain m = new LoaderMain(config);
        m.gameDir = gd.toAbsolutePath().normalize();
        m.launch(args);
    }

    public void launch(String[] mcArgs) throws Exception {
        String[] passThrough = stripGameDirArg(mcArgs);
        List<Path> gameClasspath = gameProvider.locateGame(gameDir);
        LoaderConfig gameConfig = LoaderConfig.at(gameDir);
        ModDiscovery modDiscovery = ModDiscovery.scan(gameConfig);
        List<ModManifest> resolved = modDiscovery.resolveDependencies(modDiscovery.discover());

        runtime.start();

        // Create Mod objects with their own Scopes
        for (ModManifest manifest : resolved) {
            org.loader.runtime.kernel.Scope modScope = runtime.rootScope().createChild("mod:" + manifest.id());
            createModClassLoader(manifest, gameClasspath);
            var mcl = classLoaderManager.getModClassLoader(manifest.id());
            Mod mod = new Mod(manifest, modScope, mcl.getClassLoader());
            loadedMods.add(mod);
            // Register mod as resource of its scope
            modScope.registerResource(mod);
        }

        // Invoke entrypoints with full ModContext
        invokeModEntrypoints();

        try {
            gameProvider.launch(gameDir, runtime, classLoaderManager, gameClasspath, passThrough);
        } finally {
            shutdownMods();
            classLoaderManager.close();
            try { runtime.close(); } catch (Exception ignored) {}
        }
    }

    private void createModClassLoader(ModManifest mod, List<Path> gameClasspath) {
        URL[] urls = new URL[gameClasspath.size()];
        for (int i = 0; i < gameClasspath.size(); i++) {
            try { urls[i] = gameClasspath.get(i).toUri().toURL(); }
            catch (java.net.MalformedURLException e) { throw new RuntimeException(e); }
        }
        classLoaderManager.createModClassLoaderManifest(mod, Arrays.asList(urls), gameDir);
    }

    private void invokeModEntrypoints() {
        for (Mod mod : loadedMods) {
            String mainClass = mod.manifest().mainClass();
            if (mainClass == null || mainClass.isEmpty()) continue;

            try {
                var mcl = classLoaderManager.getModClassLoader(mod.id());
                if (mcl == null) continue;
                Class<?> clazz = mcl.loadModClass(mainClass);
                Object instance = clazz.getDeclaredConstructor().newInstance();
                ModContext ctx = new ModContext(mod);
                boolean invoked = false;

                // Try initialize(ModContext)
                for (var method : clazz.getMethods()) {
                    if ("initialize".equals(method.getName()) && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].equals(ModContext.class)) {
                        method.invoke(instance, ctx);
                        invoked = true;
                        break;
                    }
                }

                // Fallback to any interface param (ModLog compat)
                if (!invoked) {
                    for (var method : clazz.getMethods()) {
                        if ("initialize".equals(method.getName()) && method.getParameterCount() == 1
                                && method.getParameterTypes()[0].isInterface()
                                && !method.getParameterTypes()[0].equals(ModContext.class)) {
                            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                                    method.getParameterTypes()[0].getClassLoader(),
                                    new Class<?>[]{method.getParameterTypes()[0]},
                                    (p, m, a) -> {
                                        if ("info".equals(m.getName()) && a != null && a.length > 0)
                                            System.out.println("[Mod:" + mod.id() + "] " + a[0]);
                                        return null;
                                    });
                            method.invoke(instance, proxy);
                            invoked = true;
                            break;
                        }
                    }
                }

                if (!invoked) {
                    try { clazz.getMethod("initialize").invoke(instance); } catch (NoSuchMethodException ignored) {}
                }

                System.out.println("[Mili] Mod initialized: " + mod.id() + " -> " + mainClass);

            } catch (Exception e) {
                System.err.println("[Loader] Mod '" + mod.id() + "' FAILED: " + e.getMessage());
            }
        }
    }

    private void shutdownMods() {
        List<Mod> reverse = new ArrayList<>(loadedMods);
        Collections.reverse(reverse);
        for (Mod m : reverse) {
            try { m.scope().shutdown(); } catch (Exception ignored) {}
        }
    }

    private String[] stripGameDirArg(String[] args) {
        if (args == null || args.length <= 1) return new String[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        return rest;
    }

    public org.loader.runtime.kernel.Runtime runtime() { return runtime; }
    public List<Mod> getLoadedMods() { return Collections.unmodifiableList(loadedMods); }
}
