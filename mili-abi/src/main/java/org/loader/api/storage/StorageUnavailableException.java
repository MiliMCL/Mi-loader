package org.loader.api.storage;

/**
 * 存储服务不可用 —— 平台尚未装配 {@link StorageService}。
 *
 * <p>继承 {@code IllegalStateException} 而不是受检异常：这是
 * 「平台没有启动到那个阶段」的编程错误，不是运行时环境问题。
 * Mod 在 {@code initialize()} 里就能拿到存储，正常情况下永远不会看到它。
 */
public class StorageUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public StorageUnavailableException(String message) {
        super(message);
    }

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
