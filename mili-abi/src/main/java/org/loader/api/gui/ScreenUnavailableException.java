package org.loader.api.gui;

/**
 * 屏幕服务不可用 —— 环境没有客户端渲染器，或平台尚未启动到客户端阶段。
 *
 * <p>它是 {@link RuntimeException}：对「在服务器上调 open()」这类
 * 编程错误，让调用栈直接炸在错误现场比强迫 Mod 到处 try-catch 更有用。
 */
public class ScreenUnavailableException extends RuntimeException {

    public ScreenUnavailableException(String message) {
        super(message);
    }
}
