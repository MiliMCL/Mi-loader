package org.loader.api.service;

import java.util.Optional;
import java.util.Set;

/**
 * 跨 Mod 服务代理 —— Mod 之间唯一的显式协作通道。
 *
 * <h2>它解决什么问题</h2>
 * 在此之前，{@code ModContext.getCapability(Class)} 只能拿到<b>授予本 Mod 自己</b>
 * 的能力。也就是说 Mod A 无法向 Mod B 提供任何东西，Mod B 也无法发现
 * A 的存在 —— 多 Mod 协作只能靠「都订阅同一个平台事件」这种间接方式。
 *
 * <p>本接口补上这条通道：
 * <pre>{@code
 * // Mod A（提供方）
 * context.services().provide(EconomyApi.class, myEconomyImpl);
 *
 * // Mod B（消费方）
 * context.services().require(EconomyApi.class)
 *         .ifPresent(api -> api.transfer(from, to, 100));
 * }</pre>
 *
 * <h2>语义约束</h2>
 * <ul>
 *   <li><b>按类型匹配</b>，不按字符串名。同一个接口类型只允许一个提供方 ——
 *       两个 Mod 提供同一个服务是<b>加载期冲突</b>，不是「后注册的覆盖先注册的」。
 *       静默覆盖会让「我的实现为什么没生效」变成不可查的问题。</li>
 *   <li><b>提供方停止即失效</b>。{@link #discover} 在提供方 Scope 停止后
 *       返回空，不需要消费方做任何清理。</li>
 *   <li><b>不做隐式降级</b>：{@link #require} 在服务缺失时抛
 *       {@link ServiceUnavailableException}，而不是返回 null 让调用方
 *       在几百行之后 NPE。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * 全部方法线程安全。典型用法是在 {@code initialize()} 里 {@code provide}，
 * 在事件回调里 {@code discover} —— 后者每 tick 可能调用，因此实现必须
 * 是近似 O(1) 的查表，不能遍历。
 */
public interface ServiceBroker {

    /**
     * 发布一个服务。
     *
     * @param serviceType  服务接口类型（必须是非 final 的接口或类）
     * @param implementation 实现实例
     * @param <T>          服务类型
     * @throws IllegalStateException 该类型已被<b>其他</b> Mod 发布
     */
    <T> void provide(Class<T> serviceType, T implementation);

    /**
     * 撤销自己发布的某个服务。
     *
     * @return 是否确实撤销了一个由本 Mod 发布的服务
     */
    boolean withdraw(Class<?> serviceType);

    /**
     * 查找服务实现。
     *
     * @return 提供方仍在运行时返回实现，否则返回空
     */
    <T> Optional<T> discover(Class<T> serviceType);

    /**
     * 查找服务实现，缺失时抛异常。
     *
     * @throws ServiceUnavailableException 没有任何 Mod 提供该服务
     */
    default <T> T require(Class<T> serviceType) {
        return discover(serviceType).orElseThrow(() ->
                new ServiceUnavailableException(serviceType.getName(),
                        "没有 Mod 提供该服务。已注册的服务类型: "
                                + String.join(", ", publishedTypes().stream()
                                .map(Class::getName).toList())));
    }

    /**
     * 当前平台上有提供方的全部服务类型。
     */
    Set<Class<?>> publishedTypes();

    /**
     * 本 Mod 已发布的服务类型。
     */
    Set<Class<?>> providedByMe();
}
