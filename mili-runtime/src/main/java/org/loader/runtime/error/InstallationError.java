package org.loader.runtime.error;

/**
 * 安装失败 —— Minecraft 文件下载、SHA-1 校验、库/资源/原语安装出错。
 *
 * <p>安装器只负责「让环境就绪」，不涉及运行时调度、Mod API 或 tick。
 */
public class InstallationError extends ModularRuntimeException {

    private final long serialVersionUID = 1L;

    public InstallationError(String message) {
        super(message);
    }

    public InstallationError(String message, Throwable cause) {
        super(message, cause);
    }

    public InstallationError(String message, ErrorContext context) {
        super(message, context);
    }

    public InstallationError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }

    /** 带组件与阶段的便捷构造。 */
    public static InstallationError of(String component, String operation,
                                       String message, Throwable cause) {
        return new InstallationError(message,
                ErrorContext.builder(component)
                        .operation(operation)
                        .cause(cause)
                        .build(),
                cause);
    }
}