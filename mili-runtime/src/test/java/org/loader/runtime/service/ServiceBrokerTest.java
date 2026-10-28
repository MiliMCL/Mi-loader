package org.loader.runtime.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.service.ServiceBroker;
import org.loader.api.service.ServiceUnavailableException;
import org.loader.runtime.kernel.Scope;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨 Mod 服务代理。
 *
 * <p>核心断言：<b>两个 Mod 提供同一接口必须在发布时失败</b>，
 * 而不是后注册的静默覆盖先注册的。覆盖的后果是「我的实现为什么没生效」
 * 变成一个不可查的问题。
 */
class ServiceBrokerTest {

    interface EconomyApi {
        String name();
    }

    interface ChatApi {
        void say(String msg);
    }

    @Test
    @DisplayName("提供 → 发现 → 按类型拿到同一实例")
    void provideAndDiscover() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");

        EconomyApi impl = () -> "a-economy";
        broker.forScope(modA).provide(EconomyApi.class, impl);

        assertSame(impl, broker.forScope(modA).discover(EconomyApi.class).orElseThrow());
    }

    @Test
    @DisplayName("跨 Mod 可见 —— B 能发现 A 提供的服务")
    void crossModDiscovery() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");
        Scope modB = root.createChild("mod:b");

        broker.forScope(modA).provide(EconomyApi.class, () -> "a");
        ServiceBroker asB = broker.forScope(modB);

        assertTrue(asB.discover(EconomyApi.class).isPresent(),
                "服务发现是全平台语义 —— B 必须能看到 A 提供的服务");
        assertTrue(asB.providedByMe().isEmpty(),
                "B 自己没有提供任何服务");
        assertEquals(1, asB.publishedTypes().size());
    }

    @Test
    @DisplayName("同一接口被两个 Mod 提供 —— 显式失败并指出双方")
    void duplicateProvideFailsLoudly() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");
        Scope modB = root.createChild("mod:b");

        broker.forScope(modA).provide(EconomyApi.class, () -> "a");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> broker.forScope(modB).provide(EconomyApi.class, () -> "b"));
        assertTrue(e.getMessage().contains("mod:a"), "错误信息必须指出已有提供方");
        assertTrue(e.getMessage().contains("mod:b"), "错误信息必须指出本次提供方");
    }

    @Test
    @DisplayName("同一 Mod 重复发布视为替换自己的实现")
    void sameModCanReplaceOwnService() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");
        ServiceBroker asA = broker.forScope(modA);

        asA.provide(EconomyApi.class, () -> "v1");
        asA.provide(EconomyApi.class, () -> "v2");

        assertEquals("v2", asA.discover(EconomyApi.class).orElseThrow().name());
    }

    @Test
    @DisplayName("提供方 Scope 停止后服务自动失效，且不能撤销别人的服务")
    void providerStopInvalidatesService() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");
        Scope modB = root.createChild("mod:b");

        broker.forScope(modA).provide(EconomyApi.class, () -> "a");

        assertFalse(broker.forScope(modB).withdraw(EconomyApi.class),
                "B 不能撤销 A 的服务");
        assertTrue(broker.forScope(modB).discover(EconomyApi.class).isPresent());

        modA.shutdown();

        assertTrue(broker.forScope(modB).discover(EconomyApi.class).isEmpty(),
                "提供方停止后服务必须不可见");
    }

    @Test
    @DisplayName("require 缺失时抛出带服务名与已注册列表的异常")
    void requireThrowsWithContext() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");
        broker.forScope(modA).provide(EconomyApi.class, () -> "a");

        ServiceUnavailableException e = assertThrows(ServiceUnavailableException.class,
                () -> broker.forScope(modA).require(ChatApi.class));
        assertTrue(e.getMessage().contains(ChatApi.class.getName()));
        assertTrue(e.getMessage().contains(EconomyApi.class.getName()),
                "错误信息要列出已注册的服务类型，便于定位拼写/依赖错误");
    }

    @Test
    @DisplayName("实现类型不匹配时立即拒绝（绕过泛型的裸类型调用）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void typeMismatchRejected() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");

        // 用裸 Class 绕过编译期泛型检查 —— 这正是反射/脚本桥接会走到的路径
        assertThrows(IllegalArgumentException.class,
                () -> provideRaw(broker.forScope(modA), EconomyApi.class, new Object()));
    }

    /** 以裸类型调用 {@code provide}，模拟反射桥接的调用形态。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void provideRaw(ServiceBroker broker, Class type, Object implementation) {
        broker.provide(type, implementation);
    }

    @Test
    @DisplayName("null 实现被拒绝")
    void nullImplementationRejected() {
        Scope root = new Scope("root", null);
        root.driveToRunning();
        ServiceBrokerImpl broker = new ServiceBrokerImpl(root);
        Scope modA = root.createChild("mod:a");

        assertThrows(NullPointerException.class,
                () -> broker.forScope(modA).provide(EconomyApi.class, null));
    }
}
