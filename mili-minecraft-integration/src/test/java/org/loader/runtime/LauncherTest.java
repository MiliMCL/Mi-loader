package org.loader.runtime;

import org.junit.jupiter.api.*;
import org.loader.api.VersionInfo;
import org.loader.runtime.launcher.DevLauncher;
import org.loader.runtime.RuntimeEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DevLauncher and the runtime VersionInfo record.
 */
class LauncherTest {

    @Test
    void devLauncher_startAndStop() {
        DevLauncher launcher = DevLauncher.server();
        launcher.start();

        assertTrue(launcher.bootstrap().isRunning());
        assertEquals(RuntimeEnvironment.DEDICATED_SERVER, launcher.environment());

        launcher.stop();
        assertFalse(launcher.bootstrap().isRunning());
    }

    @Test
    void devLauncher_client() {
        DevLauncher launcher = DevLauncher.client();

        assertEquals(RuntimeEnvironment.CLIENT, launcher.environment());
        assertTrue(launcher.environment().isClient());
        assertFalse(launcher.environment().isServer());
    }

    @Test
    void devLauncher_runTickLoop() {
        DevLauncher launcher = DevLauncher.server();
        launcher.start();

        launcher.runTickLoop(10);

        assertEquals(10L, launcher.bootstrap().tickBridge().currentTick());

        launcher.stop();
    }

    @Test
    void versionInfo_current() {
        org.loader.runtime.util.RuntimeVersionInfo info = org.loader.runtime.util.RuntimeVersionInfo.current();

        assertNotNull(info);
        assertEquals("mili-platform", info.project());
        assertEquals(VersionInfo.CURRENT_VERSION, info.version());
        assertEquals(VersionInfo.ABI_VERSION, info.abiVersion());
        assertEquals("25", info.targetJava());
        assertNotNull(info.toString());
    }
}
