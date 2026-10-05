package org.loader.runtime.error;

/**
 * Tick 执行失败 —— 阶段推进违规、任务超时、deadline 错过或取消异常。
 *
 * <p>注意：单个 tick 内的任务异常<b>不</b>抛此异常，而是记录到
 * {@link org.loader.runtime.tick.TickContract#fail}，以保证 tick 循环
 * 不会被 Mod 的异常打断。只有引擎级违规（跨线程推进、已取消仍推进）才抛。
 */
public class TickError extends ModularRuntimeException {

    private static final long serialVersionUID = 1L;

    private final long tickId;

    public TickError(String message, long tickId) {
        super(buildMessage(message, tickId),
                ErrorContext.builder("tick-engine")
                        .operation("tick")
                        .detail("tickId", tickId)
                        .build(),
                null);
        this.tickId = tickId;
    }

    public TickError(String message, long tickId, Throwable cause) {
        super(buildMessage(message, tickId),
                ErrorContext.builder("tick-engine")
                        .operation("tick")
                        .detail("tickId", tickId)
                        .cause(cause)
                        .build(),
                cause);
        this.tickId = tickId;
    }

    public TickError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
        this.tickId = -1;
    }

    /** 出错的 tick 序号；不适用时为 -1。 */
    public long tickId() {
        return tickId;
    }

    private static String buildMessage(String message, long tickId) {
        return message + " [tickId=" + tickId + "]";
    }
}