package org.loader.runtime.minecraft.reflect;

/**
 * 反射桥接与目标 Minecraft 版本不匹配。
 *
 * <p>这是绑定层唯一的"预期内失败"类型。捕获它可以给用户可读的错误，
 * 而 {@code NoSuchMethodError} 之类只会让 Mod 作者困惑。
 */
public class BridgeMismatchException extends RuntimeException {

    public BridgeMismatchException(String message) {
        super(message);
    }

    public BridgeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
