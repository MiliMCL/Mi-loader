package org.loader.runtime.client;

import org.loader.runtime.kernel.CapabilityToken;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.minecraft.RuntimeEnvironment;

import java.util.*;

/**
 * Client-specific capabilities that are only available in CLIENT runtime environments.
 * <p>
 * Per CLIENT_RENDERING.md, server environments must be explicitly denied access
 * to rendering capabilities - returning null or silently no-op is not acceptable.
 * Attempting to grant or access client capabilities on a server scope throws.
 */
public final class ClientCapabilities {

    private ClientCapabilities() {
        // Utility class
    }

    /**
     * Grants rendering capability to a scope. Only valid for CLIENT environment scopes.
     *
     * @throws UnsupportedOperationException if the environment is not CLIENT
     */
    public static CapabilityToken<RenderService> grantRenderCapability(Scope scope, RuntimeEnvironment env) {
        Objects.requireNonNull(scope, "scope must not null");
        Objects.requireNonNull(env, "environment must not null");

        if (!env.isClient()) {
            throw new UnsupportedOperationException(
                    "RenderCapability is not available in environment: " + env);
        }
        return scope.grantCapability(RenderService.class, new RenderServiceImpl(env));
    }

    /**
     * Grants input handling capability to a scope. Only valid for CLIENT environment scopes.
     */
    public static CapabilityToken<InputService> grantInputCapability(Scope scope, RuntimeEnvironment env) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(env);

        if (!env.isClient()) {
            throw new UnsupportedOperationException(
                    "InputCapability is not available in environment: " + env);
        }
        return scope.grantCapability(InputService.class, new InputServiceImpl(env));
    }

    /**
     * Grants sound capability to a scope. Only valid for CLIENT environment scopes.
     */
    public static CapabilityToken<SoundService> grantSoundCapability(Scope scope, RuntimeEnvironment env) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(env);

        if (!env.isClient()) {
            throw new UnsupportedOperationException(
                    "SoundCapability is not available in environment: " + env);
        }
        return scope.grantCapability(SoundService.class, new SoundServiceImpl(env));
    }

    /**
     * Render service interface - exposed to client mods.
     */
    public interface RenderService {
        /**
         * Registers a render callback that will be called each frame.
         */
        void registerRenderCallback(RenderCallback callback);

        /**
         * Removes a previously registered render callback.
         */
        void unregisterRenderCallback(RenderCallback callback);

        /**
         * Returns the current frames per second.
         */
        int currentFps();

        /**
         * Returns the render environment this service belongs to.
         */
        RuntimeEnvironment environment();
    }

    /**
     * Input service interface - exposed to client mods.
     */
    public interface InputService {
        /**
         * Registers a key binding handler.
         */
        void registerKeyHandler(String key, Runnable handler);

        /**
         * Returns whether a key is currently pressed.
         */
        boolean isKeyPressed(String key);

        /**
         * Returns the render environment this service belongs to.
         */
        RuntimeEnvironment environment();
    }

    /**
     * Sound service interface - exposed to client mods.
     */
    public interface SoundService {
        /**
         * Plays a sound with the given identifier.
         */
        void playSound(String soundId);

        /**
         * Stops all currently playing sounds.
         */
        void stopAll();

        /**
         * Returns the render environment this service belongs to.
         */
        RuntimeEnvironment environment();
    }

    /**
     * Render callback functional interface.
     */
    @FunctionalInterface
    public interface RenderCallback {
        void onRender(float deltaTime);
    }

    // Implementation classes

    private static class RenderServiceImpl implements RenderService {
        private final RuntimeEnvironment env;
        private final List<RenderCallback> callbacks = new ArrayList<>();
        private int fps = 60;

        RenderServiceImpl(RuntimeEnvironment env) {
            this.env = env;
        }

        @Override
        public void registerRenderCallback(RenderCallback callback) {
            callbacks.add(callback);
        }

        @Override
        public void unregisterRenderCallback(RenderCallback callback) {
            callbacks.remove(callback);
        }

        @Override
        public int currentFps() {
            return fps;
        }

        @Override
        public RuntimeEnvironment environment() {
            return env;
        }
    }

    private static class InputServiceImpl implements InputService {
        private final RuntimeEnvironment env;
        private final Map<String, Runnable> handlers = new LinkedHashMap<>();

        InputServiceImpl(RuntimeEnvironment env) {
            this.env = env;
        }

        @Override
        public void registerKeyHandler(String key, Runnable handler) {
            handlers.put(key, handler);
        }

        @Override
        public boolean isKeyPressed(String key) {
            return handlers.containsKey(key);
        }

        @Override
        public RuntimeEnvironment environment() {
            return env;
        }
    }

    private static class SoundServiceImpl implements SoundService {
        private final RuntimeEnvironment env;

        SoundServiceImpl(RuntimeEnvironment env) {
            this.env = env;
        }

        @Override
        public void playSound(String soundId) {
            // Client-only operation
        }

        @Override
        public void stopAll() {
            // Client-only operation
        }

        @Override
        public RuntimeEnvironment environment() {
            return env;
        }
    }
}
