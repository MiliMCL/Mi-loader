package org.loader.runtime.client;

import org.junit.jupiter.api.*;
import org.loader.runtime.kernel.*;
import org.loader.runtime.minecraft.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClientCapabilities - verifies that client-only capabilities
 * are denied on server environments and available on client environments.
 */
class ClientCapabilitiesTest {

    @Test
    void renderCapability_grantedOnClient() {
        Scope scope = new Scope("client-render-test", null);
        var token = ClientCapabilities.grantRenderCapability(scope, RuntimeEnvironment.CLIENT);
        assertTrue(token.isActive());

        ClientCapabilities.RenderService service = token.get();
        assertNotNull(service);
        assertEquals(RuntimeEnvironment.CLIENT, service.environment());
    }

    @Test
    void renderCapability_deniedOnServer() {
        Scope scope = new Scope("server-render-test", null);
        assertThrows(UnsupportedOperationException.class,
                () -> ClientCapabilities.grantRenderCapability(scope, RuntimeEnvironment.DEDICATED_SERVER));
    }

    @Test
    void renderCapability_deniedOnServerEnv() {
        Scope scope = new Scope("server-env-render-test", null);
        assertThrows(UnsupportedOperationException.class,
                () -> ClientCapabilities.grantRenderCapability(scope, RuntimeEnvironment.SERVER));
    }

    @Test
    void inputCapability_grantedOnClient() {
        Scope scope = new Scope("client-input-test", null);
        var token = ClientCapabilities.grantInputCapability(scope, RuntimeEnvironment.CLIENT);
        assertTrue(token.isActive());

        ClientCapabilities.InputService service = token.get();
        assertNotNull(service);
        assertEquals(RuntimeEnvironment.CLIENT, service.environment());
    }

    @Test
    void inputCapability_deniedOnServer() {
        Scope scope = new Scope("server-input-test", null);
        assertThrows(UnsupportedOperationException.class,
                () -> ClientCapabilities.grantInputCapability(scope, RuntimeEnvironment.DEDICATED_SERVER));
    }

    @Test
    void soundCapability_grantedOnClient() {
        Scope scope = new Scope("client-sound-test", null);
        var token = ClientCapabilities.grantSoundCapability(scope, RuntimeEnvironment.CLIENT);
        assertTrue(token.isActive());

        ClientCapabilities.SoundService service = token.get();
        assertNotNull(service);
        assertEquals(RuntimeEnvironment.CLIENT, service.environment());
    }

    @Test
    void soundCapability_deniedOnServer() {
        Scope scope = new Scope("server-sound-test", null);
        assertThrows(UnsupportedOperationException.class,
                () -> ClientCapabilities.grantSoundCapability(scope, RuntimeEnvironment.DEDICATED_SERVER));
    }

    @Test
    void renderService_callbacksWork() {
        Scope scope = new Scope("callback-test", null);
        var token = ClientCapabilities.grantRenderCapability(scope, RuntimeEnvironment.CLIENT);
        ClientCapabilities.RenderService service = token.get();

        List<Float> deltas = new ArrayList<>();
        ClientCapabilities.RenderCallback callback = deltas::add;
        service.registerRenderCallback(callback);

        assertEquals(60, service.currentFps());

        service.unregisterRenderCallback(callback);
        // No exception on unregister
    }

    @Test
    void renderCapability_nullScopeOrEnv_throws() {
        Scope scope = new Scope("null-test", null);
        assertThrows(NullPointerException.class,
                () -> ClientCapabilities.grantRenderCapability(null, RuntimeEnvironment.CLIENT));
        assertThrows(NullPointerException.class,
                () -> ClientCapabilities.grantRenderCapability(scope, null));
    }

    @Test
    void runtimeEnvironment_isClient_isServer() {
        assertTrue(RuntimeEnvironment.CLIENT.isClient());
        assertFalse(RuntimeEnvironment.CLIENT.isServer());

        assertFalse(RuntimeEnvironment.SERVER.isClient());
        assertTrue(RuntimeEnvironment.SERVER.isServer());

        assertFalse(RuntimeEnvironment.DEDICATED_SERVER.isClient());
        assertTrue(RuntimeEnvironment.DEDICATED_SERVER.isServer());
    }
}
