package org.loader.api.permission;

/**
 * Standard permissions that may be granted to mods.
 * <p>
 * This enum defines the built-in permissions. Custom permissions can be
 * registered by the runtime as string-based permissions.
 */
public enum Permission {

    /**
     * Permission to read mods' own resource files.
     */
    RESOURCE_READ("resource.read"),

    /**
     * Permission to write to mods' own config/data directories.
     */
    RESOURCE_WRITE("resource.write"),

    /**
     * Permission to access the network (outbound connections).
     */
    NETWORK_ACCESS("network.access"),

    /**
     * Permission to execute native code or access native libraries.
     */
    NATIVE_ACCESS("native.access"),

    /**
     * Permission to register custom capabilities.
     */
    CAPABILITY_REGISTER("capability.register"),

    /**
     * Permission to access inter-mod communication channels.
     */
    INTERMOD_COMMUNICATION("intermod.communication"),

    // ── 字节码转换（ADR-0011） ──────────────────────────────────────────────
    //
    // 转换是高权限能力。分层理由：让「能改自己的类」「能改别的 Mod 的类」
    // 「能改 Minecraft」「能改平台核心」成为四个不同的授权，而不是一个
    // 全有或全无的开关。默认全部拒绝 —— Mod 不因为被加载就获得任何转换权。

    /**
     * Permission to transform classes within the mod's own jar.
     *
     * <p><b>最基础的转换权限</b>，也是普通 Mod 唯一可能被授予的转换权。
     * 仅允许转换自身 classpath 上的类，不涉及 Minecraft、不涉及平台。
     */
    TRANSFORM_CLASS("transform.class"),

    /**
     * Permission to transform classes belonging to other mods.
     *
     * <p>比 {@link #TRANSFORM_CLASS} 更强：可影响他人代码。
     * 持有者的失败会影响依赖它的其他 Mod。
     */
    TRANSFORM_OTHER_MODS("transform.other-mods"),

    /**
     * Permission to transform Minecraft classes.
     *
     * <p>需要核心 Mod。允许注入 / 重定向 Minecraft 方法，
     * 但仍<b>不能</b>覆盖 {@code phase = CORE} 的平台核心注入点。
     */
    TRANSFORM_MINECRAFT("transform.minecraft"),

    /**
     * Permission to transform platform core classes.
     *
     * <p><b>最高等级的转换权限。</b>平台自身保留，不授予任何 Mod ——
     * 它存在的意义是让平台自身的 Core 阶段转换走与 Mod 相同的检查路径，
     * 从而避免出现「平台特权，绕过一切校验」的盲区。
     */
    TRANSFORM_CORE("transform.core"),

    /**
     * Permission to overwrite entire method bodies.
     *
     * <p><b>默认禁用，且不与任何其他权限自动组合。</b>
     * 即便持有本权限，也<b>不能</b>覆盖 {@code phase = CORE}
     * 阶段已占用的目标（见 ADR-0011）。
     *
     * <p>Overwrite 会破坏模组兼容性与转换可组合性 —— 原始方法体被丢弃后，
     * 其他 Mod 声明的注入点会静默失效。因此它是最后手段。
     */
    OVERWRITE_METHOD("transform.overwrite");

    private final String id;

    Permission(String id) {
        this.id = id;
    }

    /**
     * Returns the string identifier for this permission.
     */
    public String id() {
        return id;
    }
}
