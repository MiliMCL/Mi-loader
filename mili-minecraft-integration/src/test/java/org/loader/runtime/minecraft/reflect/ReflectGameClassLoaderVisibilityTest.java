package org.loader.runtime.minecraft.reflect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游戏 ClassLoader 必须<b>跨线程</b>可见。
 *
 * <h2>为什么这是必须的</h2>
 *
 * <p>生产拓扑里，游戏跑在名为 {@code minecraft-main} 的独立线程上。
 * 而绑定层的很大一部分读者只在那条线程上活动：
 * {@code BehaviourDispatch.tick}（方块行为回调）、{@code CurrentWorld.current()}
 * （世界视图）、tick 里的任何反射访问。
 *
 * <p>所以游戏 ClassLoader 如果存成 ThreadLocal，主线程设的值对
 * {@code minecraft-main} 不可见 —— {@link Reflect#gameClassLoader()} 会在
 * 游戏运行期间悄悄回落到平台 ClassLoader，症状是
 * 「启动日志一切正常，进游戏就 ClassNotFoundException」。
 *
 * <p>本测试不依赖 Minecraft：用一个自定义 ClassLoader 当替身即可验证
 * 「另一个线程能不能看到它」。
 */
class ReflectGameClassLoaderVisibilityTest {

    @AfterEach
    void tearDown() {
        Reflect.useGameClassLoader(null);
    }

    @Test
    @DisplayName("游戏 ClassLoader 对其他线程可见（不是 ThreadLocal）")
    void gameClassLoaderIsVisibleFromOtherThreads() throws Exception {
        ClassLoader gameCl = new URLClassLoader("mili-game-stub",
                new URL[0], ReflectGameClassLoaderVisibilityTest.class.getClassLoader());

        Reflect.useGameClassLoader(gameCl);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ClassLoader> fromOtherThread = new AtomicReference<>();
        Thread t = new Thread(() -> {
            fromOtherThread.set(Reflect.gameClassLoader());
            done.countDown();
        }, "minecraft-main-stub");
        t.start();

        assertTrue(done.await(5, TimeUnit.SECONDS),
                "副线程 5s 内未执行完 Reflect.gameClassLoader()");
        assertSame(gameCl, fromOtherThread.get(),
                "游戏 ClassLoader 没有跨线程传播 —— ThreadLocal 语义会让"
                        + "minecraft-main 线程上的所有反射回落到平台 ClassLoader");
    }

    @Test
    @DisplayName("未设置时回落到平台 ClassLoader")
    void fallsBackToPlatformClassLoader() {
        assertSame(Reflect.class.getClassLoader(), Reflect.gameClassLoader(),
                "未安装游戏 CL 时应回落到平台 CL（IDE 直跑 / 纯契约测试场景）");
    }

    @Test
    @DisplayName("useGameClassLoader(null) 恢复默认")
    void resetRestoresDefault() {
        Reflect.useGameClassLoader(
                new URLClassLoader("stub", new URL[0], getClass().getClassLoader()));
        assertNotNull(Reflect.gameClassLoader());

        Reflect.useGameClassLoader(null);
        assertSame(Reflect.class.getClassLoader(), Reflect.gameClassLoader(),
                "传 null 应恢复为平台 CL，否则后续测试会读到上一个测试的 ClassLoader");
    }
}