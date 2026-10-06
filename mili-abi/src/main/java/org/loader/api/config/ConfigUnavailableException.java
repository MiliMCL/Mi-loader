package org.loader.api.config;

/**
 * 配置不可用 —— 平台未装配实现。
 */
public class ConfigUnavailableException extends RuntimeException {

    public ConfigUnavailableException(String message) {
        super(message);
    }
}
