package org.loader.api.transform;

import org.loader.api.exception.MiliException;

/**
 * 转换体系的基础异常。
 *
 * <p>继承 {@link MiliException} 以接入既有异常体系，而不是新造一套 ——
 * 平台已有 {@code ModularRuntimeException} / {@code ErrorContext} 的错误模型，
 * 转换错误应当能沿同一条路径被捕获、分类与审计。
 */
public class TransformationException extends MiliException {

    private static final long serialVersionUID = 1L;

    /** 触发本次失败的转换器 id（若已知）。 */
    private final String transformerId;

    public TransformationException(String message) {
        this(message, null, null);
    }

    public TransformationException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public TransformationException(String message, String transformerId, Throwable cause) {
        super(message, cause);
        this.transformerId = transformerId;
    }

    /** 触发本次失败的转换器 id，可能为 null。 */
    public String transformerId() {
        return transformerId;
    }
}