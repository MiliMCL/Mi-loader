package org.loader.runtime.instance;

import org.loader.api.VersionInfo;
import org.loader.runtime.RuntimeEnvironment;
import org.loader.runtime.mod.ModManifest;

import java.util.*;

/**
 * Represents a single game instance with its own configuration, mod set, and runtime parameters.
 * <p>
 * Each instance is identified uniquely and carries metadata about the Minecraft version,
 * runtime version, ABI version, and the set of mods to be loaded.
 */
public class Instance {

    private final String instanceId;
    private final String minecraftVersion;
    private final String runtimeVersion;
    private final int abiVersion;
    private final RuntimeEnvironment environment;
    private final List<String> modIds;
    private final Map<String, String> configuration;
    private final String displayName;

    private Instance(Builder builder) {
        this.instanceId = Objects.requireNonNull(builder.instanceId, "instanceId must not be null");
        this.minecraftVersion = Objects.requireNonNull(builder.minecraftVersion, "minecraftVersion must not be null");
        this.runtimeVersion = Objects.requireNonNull(builder.runtimeVersion, "runtimeVersion must not be null");
        this.abiVersion = Objects.requireNonNull(builder.abiVersion, "abiVersion must not be null");
        this.environment = Objects.requireNonNull(builder.environment, "environment must not be null");
        this.modIds = List.copyOf(builder.modIds);
        this.configuration = Map.copyOf(builder.configuration);
        this.displayName = builder.displayName != null ? builder.displayName : builder.instanceId;
    }

    /**
     * Creates a builder for constructing an Instance.
     */
    public static Builder builder(String instanceId) {
        return new Builder(instanceId);
    }

    public String instanceId() {
        return instanceId;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public String runtimeVersion() {
        return runtimeVersion;
    }

    public int abiVersion() {
        return abiVersion;
    }

    public RuntimeEnvironment environment() {
        return environment;
    }

    public List<String> modIds() {
        return modIds;
    }

    public Map<String, String> configuration() {
        return configuration;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Returns the configuration value for a key, or empty if not present.
     */
    public Optional<String> config(String key) {
        return Optional.ofNullable(configuration.get(key));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Instance that)) return false;
        return instanceId.equals(that.instanceId);
    }

    @Override
    public int hashCode() {
        return instanceId.hashCode();
    }

    @Override
    public String toString() {
        return "Instance[" + instanceId + ", mc=" + minecraftVersion + ", env=" + environment + "]";
    }

    /**
     * Builder for constructing immutable Instance objects.
     */
    public static class Builder {
        private final String instanceId;
        private String minecraftVersion = VersionInfo.TARGET_MINECRAFT;
        private String runtimeVersion = VersionInfo.CURRENT_VERSION;
        private int abiVersion = VersionInfo.ABI_VERSION;
        private RuntimeEnvironment environment = RuntimeEnvironment.DEDICATED_SERVER;
        private final List<String> modIds = new ArrayList<>();
        private final Map<String, String> configuration = new LinkedHashMap<>();
        private String displayName;

        private Builder(String instanceId) {
            this.instanceId = instanceId;
        }

        public Builder minecraftVersion(String version) {
            this.minecraftVersion = version;
            return this;
        }

        public Builder runtimeVersion(String version) {
            this.runtimeVersion = version;
            return this;
        }

        public Builder abiVersion(int version) {
            this.abiVersion = version;
            return this;
        }

        public Builder environment(RuntimeEnvironment env) {
            this.environment = env;
            return this;
        }

        public Builder addMod(String modId) {
            this.modIds.add(modId);
            return this;
        }

        public Builder mods(Collection<String> modIds) {
            this.modIds.addAll(modIds);
            return this;
        }

        public Builder configuration(String key, String value) {
            this.configuration.put(key, value);
            return this;
        }

        public Builder displayName(String name) {
            this.displayName = name;
            return this;
        }

        public Instance build() {
            return new Instance(this);
        }
    }
}
