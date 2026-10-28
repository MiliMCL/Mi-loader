package org.loader.api.event;

/**
 * 平台整体阶段事件。
 *
 * @param stage       当前阶段
 * @param platformVersion 平台版本（{@link org.loader.api.VersionInfo#CURRENT_VERSION}）
 * @param modCount    已加载的 Mod 数量；{@link PlatformStage#BOOTSTRAPPING} 时为 0
 */
public record PlatformEvent(PlatformStage stage, String platformVersion, int modCount) {

    public PlatformEvent {
        if (stage == null) {
            throw new IllegalArgumentException("stage 不能为 null");
        }
        if (platformVersion == null) {
            platformVersion = "";
        }
        if (modCount < 0) {
            modCount = 0;
        }
    }
}
