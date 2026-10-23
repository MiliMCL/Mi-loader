package org.loader.runtime.minecraft.client.screen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把生成的宿主类定义到<b>游戏类加载器之下</b>。
 *
 * <h2>为什么不直接调 {@code ClassLoader.defineClass}</h2>
 * 那是最自然的写法，但在 JDK 9+ 的模块系统下必然失败：
 * <pre>
 *   InaccessibleObjectException: Unable to make protected final Class
 *   ClassLoader.defineClass(...) accessible: module java.base does not
 *   "opens java.lang" to unnamed module
 * </pre>
 * 而这不是可以靠 {@code --add-opens} 绕过的问题 —— 要求每个玩家在启动
 * 脚本里加 JVM 参数，等于把平台的一个内部实现细节变成部署前提。
 * 更糟的是：失败发生在「点击按钮之后」，玩家看到的是「点了没反应」。
 *
 * <h2>做法</h2>
 * 建一个极小的 {@link ClassLoader} 子类，它的 parent 是 Minecraft
 * 的 ClassLoader。子类可以直接调用 {@code protected} 的
 * {@code defineClass} —— 这是 ClassLoader 的公开扩展点，不需要任何
 * 反射突破，模块系统也管不到。
 *
 * <pre>
 *   AppClassLoader (平台)
 *        └── MinecraftClassLoader (net.minecraft.*)
 *              └── ScreenHostLoader (生成的 Screen 子类)
 * </pre>
 *
 * <p>生成类里引用的 {@code ModListScreenDispatch} 位于平台 ClassLoader，
 * 沿父链可见；它继承的 {@code Screen} 位于 MinecraftClassLoader，
 * 也可见。两侧都成立，链接不会失败。
 *
 * <p>类只定义一次并缓存：同一个类被两个 ClassLoader 定义会产生
 * {@code ClassCastException}（看起来像「屏幕打不开」），而这种错误
 * 极难从堆栈里认出来。
 */
final class GameHostDefiner {

    private static final Map<String, Class<?>> CACHE = new ConcurrentHashMap<>();

    private GameHostDefiner() {
    }

    /**
     * @param game  游戏类加载器（必须非 null）
     * @param name  生成类的二进制名
     * @param bytes 字节码
     */
    static Class<?> define(ClassLoader game, String name, byte[] bytes) {
        String key = System.identityHashCode(game) + "/" + name;
        Class<?> existing = CACHE.get(key);
        if (existing != null) {
            return existing;
        }
        return CACHE.computeIfAbsent(key, k -> new HostLoader(game).define0(name, bytes));
    }

    private static final class HostLoader extends ClassLoader {

        HostLoader(ClassLoader parent) {
            super("mili-screen-host", parent);
        }

        Class<?> define0(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
