package org.loader.api;

/**
 * Mili Platform 唯一版本源常量。
 * <p>
 * 这些值与 gradle.properties 保持一致：
 * <ul>
 *     <li>miliPlatformVersion = {@link #CURRENT_VERSION} ("0.1.0")</li>
 *     <li>miliAbiVersion = {@link #ABI_VERSION} (1)</li>
 *     <li>minecraftVersion = {@link #TARGET_MINECRAFT} ("26.2")</li>
 *     <li>javaVersion = {@link #TARGET_JAVA} (25)</li>
 * </ul>
 * <p>
 * ABI 使用整数 (1, 2, 3, ...)，Platform 版本使用语义化 ("0.1.0", "0.2.0")。
 * 不允许同时存在 1 / 1.0 / 1.0.0 多种写法。
 */
public final class VersionInfo {

    private VersionInfo() {
    }

    /** 当前 Mili Platform / Runtime 实现版本。 */
    public static final String CURRENT_VERSION = "0.1.0";

    /** ABI 版本 —— Mod 编译和验证的目标契约版本。整数，从 1 递增。 */
    public static final int ABI_VERSION = 1;

    /** 目标 Java 主版本。 */
    public static final int TARGET_JAVA = 25;

    /** 此平台Release 支持的 Minecraft 版本。 */
    public static final String TARGET_MINECRAFT = "26.2";

    /** Mili Platform Version + Minecraft Version 组成稳定 ID。 */
    public static final String PLATFORM_ID = "mili-" + CURRENT_VERSION + "-mc" + TARGET_MINECRAFT;
}
