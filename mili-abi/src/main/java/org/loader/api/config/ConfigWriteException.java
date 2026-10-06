package org.loader.api.config;

/**
 * 配置写入磁盘失败。
 *
 * <p>独立于运行时异常：调用方可以选择提示用户「设置未能保存」，
 * 而不必把它当成程序错误处理。
 */
public class ConfigWriteException extends RuntimeException {

    public ConfigWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
