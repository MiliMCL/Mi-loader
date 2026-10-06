package org.loader.api.input;

/**
 * 按键服务不可用 —— 无客户端输入系统或平台未装配。
 */
public class KeyBindingUnavailableException extends RuntimeException {

    public KeyBindingUnavailableException(String message) {
        super(message);
    }
}
