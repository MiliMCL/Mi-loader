package org.loader.api.service;

/**
 * 请求的服务没有任何提供方。
 *
 * <p>继承 {@link IllegalStateException} 而不是受检异常：服务缺失在
 * Mili 里属于<b>装配错误</b>（Mod 的依赖声明漏了，或提供方 Mod 没装），
 * 这类错误应该在第一次调用处就炸掉，而不是要求调用方在每个调用点
 * 写 try/catch 把问题一路往后传。
 */
public class ServiceUnavailableException extends IllegalStateException {

    private final String serviceType;

    public ServiceUnavailableException(String serviceType, String message) {
        super("Service unavailable: " + serviceType + " —— " + message);
        this.serviceType = serviceType;
    }

    /** 缺失的服务类型全名。 */
    public String serviceType() {
        return serviceType;
    }
}
