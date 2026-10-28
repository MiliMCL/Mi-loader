package org.loader.runtime.scheduler;

/**
 * 任务执行失败。
 *
 * <p>{@link TaskHandle#await()} 在任务体抛异常时抛出本异常，并保留
 * <b>任务 id</b> 与原始 cause。
 *
 * <h2>为什么不直接抛 {@code RuntimeException(cause)}</h2>
 * 旧实现 {@code throw new RuntimeException(e.getCause())} 丢掉了任务身份，
 * 调用方拿到一个 cause 后无法判断它是哪个任务失败的 —— 在「一个 Mod 提交了
 * 三个并行任务」的场景里，这等于没有任何信息。同时 {@code RuntimeException}
 * 太宽泛，调用方无法把「任务失败」与「自己代码的 bug」区分开。
 */
public class TaskFailureException extends RuntimeException {

    private final String taskId;

    public TaskFailureException(String taskId, Throwable cause) {
        super("Task failed: " + taskId
                + (cause != null ? " — " + cause.getClass().getName() : ""), cause);
        this.taskId = taskId;
    }

    /** 失败的任务 id。 */
    public String taskId() {
        return taskId;
    }
}
