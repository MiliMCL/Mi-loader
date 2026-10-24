package org.loader.installer.install;

import java.util.Objects;

/**
 * 安装过程中一个可诊断的问题：<b>结构化</b>的阶段、资源标识、根因。
 *
 * <p><b>为什么不用字符串</b>：历史实现把「是否安装成功」判定为
 * {@code warnings.stream().noneMatch(w -> w.startsWith("Failed to install library"))}。
 * 这种写法的问题是双向的：
 * <ul>
 *   <li><b>漏报</b> —— 任何不以此前缀开头的失败（资源下载失败、natives 解压失败）
 *       都会被判成成功，安装报告写 {@code "ok": true}，CLI 退出码 0；</li>
 *   <li><b>误报</b> —— 只要有人改写文案（加空格、换语言），判定立刻失效，
 *       而且没有任何测试会红。</li>
 * </ul>
 * 因此成功与否只能由结构化字段推导，绝不能读日志文本。
 *
 * @param severity   严重级别；{@link Severity#ERROR} 参与最终 {@code ok} 判定
 * @param stage      发生阶段
 * @param resourceId 资源标识（库 Maven 坐标、资源对象 sha1、索引 id、URL 等）
 * @param message    人类可读的原因
 * @param cause      原始异常；可为 null
 */
public record InstallIssue(Severity severity,
                           Stage stage,
                           String resourceId,
                           String message,
                           Throwable cause) {

    /** 严重级别。 */
    public enum Severity {
        /** 不阻断安装；必须由显式策略判定为可选（见各类 {@code OPTIONAL_*} 说明）。 */
        WARNING,
        /** 必需资源失败：整体安装结果必须为失败。 */
        ERROR
    }

    /** 安装阶段。 */
    public enum Stage {
        METADATA("版本元数据"),
        CLIENT_JAR("客户端 JAR"),
        LOGGING_CONFIG("日志配置"),
        LIBRARY("依赖库"),
        NATIVE("native 解压"),
        ASSET_INDEX("资源索引"),
        ASSET_OBJECT("资源对象");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public InstallIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(stage, "stage");
        resourceId = resourceId == null ? "" : resourceId;
        message = message == null ? "" : message;
    }

    public static InstallIssue error(Stage stage, String resourceId, String message) {
        return new InstallIssue(Severity.ERROR, stage, resourceId, message, null);
    }

    public static InstallIssue error(Stage stage, String resourceId, String message, Throwable cause) {
        return new InstallIssue(Severity.ERROR, stage, resourceId, message, cause);
    }

    public static InstallIssue warning(Stage stage, String resourceId, String message) {
        return new InstallIssue(Severity.WARNING, stage, resourceId, message, null);
    }

    public static InstallIssue warning(Stage stage, String resourceId, String message, Throwable cause) {
        return new InstallIssue(Severity.WARNING, stage, resourceId, message, cause);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** 单行人类可读描述（仅用于日志/CLI 展示，<b>不得</b>用于判定成功与否）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(stage.label());
        if (!resourceId.isEmpty()) {
            sb.append(' ').append(resourceId);
        }
        sb.append(" — ").append(message);
        if (cause != null && cause.getMessage() != null
                && !message.contains(cause.getMessage())) {
            sb.append("（").append(cause.getClass().getSimpleName())
                    .append(": ").append(cause.getMessage()).append("）");
        }
        return sb.toString();
    }
}
