package org.loader.runtime.error;

/**
 * Errors related to mod loading, unloading, or initialization.
 * Replaces the raw {@link org.loader.runtime.mod.ModLoadException} with
 * a typed, contextual error per ERROR_MODEL.md.
 * <p>
 * 每个 {@code ModLoadError} 都携带一个标准化的错误码，可用于诊断、CI 失败分类、
 * 自动生成 Release Notes。错误码命名规范: MOD_ / PLATFORM_ / ABI_ 前缀。
 */
public class ModLoadError extends ModularRuntimeException {

    /** 标准化错误码，例如 {@code "MOD_PLATFORM_MISMATCH"}。 */
    private final String errorCode;

    public ModLoadError(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ModLoadError(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ModLoadError(String message) {
        super(message);
        this.errorCode = "MOD_UNKNOWN";
    }

    public ModLoadError(String message, ErrorContext context) {
        super(message, context, context != null ? context.cause() : null);
        this.errorCode = "MOD_UNKNOWN";
    }

    public ModLoadError(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "MOD_UNKNOWN";
    }

    public ModLoadError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
        this.errorCode = "MOD_UNKNOWN";
    }

    /** 返回该错误的标准错误码。 */
    public String errorCode() {
        return errorCode;
    }
}
