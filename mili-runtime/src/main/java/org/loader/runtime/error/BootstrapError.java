package org.loader.runtime.error;

/**
 * 启动流程失败 —— Minecraft 发现、运行环境准备、Mod 加载、引导等阶段出错。
 *
 * <p>对应 {@link org.loader.runtime.minecraft.BootstrapState} 的 FAILED 迁移。
 */
public class BootstrapError extends ModularRuntimeException {

    private static final long serialVersionUID = 1L;

    public BootstrapError(String message) {
        super(message);
    }

    public BootstrapError(String message, Throwable cause) {
        super(message, cause);
    }

    public BootstrapError(String message, ErrorContext context) {
        super(message, context);
    }

    public BootstrapError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}