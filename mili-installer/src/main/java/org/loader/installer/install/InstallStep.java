package org.loader.installer.install;

/**
 * 单个安装步骤的<b>结构化</b>状态。
 *
 * <p>安装报告不能只有一串警告文本：调用方需要知道「哪一步发生了什么」。
 * 步骤状态与 {@link InstallIssue} 是两套互补信息 ——
 * 步骤回答「这一步整体如何」，issue 回答「具体哪个资源因为什么失败」。
 *
 * @param stage  阶段
 * @param status 状态
 * @param detail 附加说明（计数量、跳过原因等），可为空串
 */
public record InstallStep(InstallIssue.Stage stage, Status status, String detail) {

    /** 步骤状态。 */
    public enum Status {
        /** 全部必需资源就绪。 */
        OK,
        /** 用户显式跳过（{@code --skip-*}），不算失败但安装不完整。 */
        SKIPPED,
        /** 存在非必需资源失败（例如客户端日志配置），不阻断安装。 */
        DEGRADED,
        /** 存在必需资源失败；整体安装必须判为失败。 */
        FAILED
    }

    public InstallStep {
        detail = detail == null ? "" : detail;
    }

    public static InstallStep ok(InstallIssue.Stage stage, String detail) {
        return new InstallStep(stage, Status.OK, detail);
    }

    public static InstallStep skipped(InstallIssue.Stage stage, String detail) {
        return new InstallStep(stage, Status.SKIPPED, detail);
    }

    public static InstallStep degraded(InstallIssue.Stage stage, String detail) {
        return new InstallStep(stage, Status.DEGRADED, detail);
    }

    public static InstallStep failed(InstallIssue.Stage stage, String detail) {
        return new InstallStep(stage, Status.FAILED, detail);
    }
}
