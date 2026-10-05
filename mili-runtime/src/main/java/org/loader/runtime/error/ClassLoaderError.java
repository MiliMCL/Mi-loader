package org.loader.runtime.error;

/**
 * ClassLoader 失败 —— 类加载、可见性违规、资源隔离或重复类问题。
 *
 * <p>典型场景：Mod 试图访问 Loader 内部类（违反 {@code ClassVisibility} 契约）、
 * Minecraft 主类无法解析、ClassLoader 泄漏。
 */
public class ClassLoaderError extends ModularRuntimeException {

    private static final long serialVersionUID = 1L;

    /** 被拒绝访问的类名（若适用）。 */
    private final String className;

    public ClassLoaderError(String message) {
        this(message, (String) null, null);
    }

    public ClassLoaderError(String message, Throwable cause) {
        // 必须显式转型：ClassLoaderError 同时有 (String,String,Throwable) 与
        // (String,ErrorContext,Throwable) 两个三参构造器，传裸 null 会二义。
        this(message, (String) null, cause);
    }

    public ClassLoaderError(String message, String className, Throwable cause) {
        super(buildMessage(message, className),
                ErrorContext.builder("classloader")
                        .operation("load")
                        .detail("className", className)
                        .cause(cause)
                        .build(),
                cause);
        this.className = className;
    }

    public ClassLoaderError(String message, ErrorContext context, Throwable cause) {
        super(message, context, cause);
        this.className = null;
    }

    /** 被拒绝访问的类名；不适用时为 null。 */
    public String className() {
        return className;
    }

    private static String buildMessage(String message, String className) {
        if (className == null) {
            return message;
        }
        return message + " [class=" + className + "]";
    }
}