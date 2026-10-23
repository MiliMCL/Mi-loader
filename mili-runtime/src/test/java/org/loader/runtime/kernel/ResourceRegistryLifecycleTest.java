package org.loader.runtime.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ResourceRegistry} 的关闭语义。
 *
 * <p>历史缺陷：{@code close()} 只清空 Map，一个资源都没关。它不会让任何
 * 测试变红（没有异常），只会让 ZIP 句柄、线程、类加载器在进程末尾仍未释放。
 * 这里用「关闭顺序」把语义钉死：后注册的先关，且必须真的关。
 */
class ResourceRegistryLifecycleTest {

    private static final class FakeResource implements Resource {
        private final String id;
        private final Scope owner;
        private final List<String> log;
        private final boolean fail;
        private boolean closed;

        FakeResource(String id, Scope owner, List<String> log, boolean fail) {
            this.id = id;
            this.owner = owner;
            this.log = log;
            this.fail = fail;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Scope owner() {
            return owner;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            log.add(id);
            if (fail) {
                throw new IllegalStateException("boom: " + id);
            }
        }
    }

    @Test
    @DisplayName("close 逆序关闭全部资源，而不是只清空登记表")
    void closesInReverseOrder() {
        Scope root = new Scope("root", null);
        ResourceRegistry registry = new ResourceRegistry("registry", root);
        List<String> log = new ArrayList<>();

        registry.register(new FakeResource("a", root, log, false));
        registry.register(new FakeResource("b", root, log, false));
        registry.register(new FakeResource("c", root, log, false));

        registry.close();

        assertEquals(List.of("c", "b", "a"), log, "后注册的资源应最先释放");
        assertTrue(registry.isClosed());
        assertFalse(registry.isOpen());
        assertEquals(0, registry.size());
    }

    @Test
    @DisplayName("重复 close 不会重复释放")
    void closeIsIdempotent() {
        Scope root = new Scope("root", null);
        ResourceRegistry registry = new ResourceRegistry("registry", root);
        List<String> log = new ArrayList<>();
        registry.register(new FakeResource("a", root, log, false));

        registry.close();
        registry.close();

        assertEquals(List.of("a"), log);
    }

    @Test
    @DisplayName("单个资源失败不阻断其余资源，失败原因被聚合")
    void failuresAreAggregated() {
        Scope root = new Scope("root", null);
        ResourceRegistry registry = new ResourceRegistry("registry", root);
        List<String> log = new ArrayList<>();
        registry.register(new FakeResource("a", root, log, false));
        registry.register(new FakeResource("bad", root, log, true));
        registry.register(new FakeResource("c", root, log, false));

        ScopeShutdownException failure =
                assertThrows(ScopeShutdownException.class, registry::close);

        assertEquals(List.of("c", "bad", "a"), log, "失败之后仍要继续释放其余资源");
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("bad"));
    }

    @Test
    @DisplayName("关闭后拒绝新登记 —— 否则新资源永远不会被释放")
    void registerAfterCloseThrows() {
        Scope root = new Scope("root", null);
        ResourceRegistry registry = new ResourceRegistry("registry", root);
        registry.close();

        assertThrows(IllegalStateException.class,
                () -> registry.register(new FakeResource("late", root, new ArrayList<>(), false)));
    }

    @Test
    @DisplayName("按属主查询资源")
    void findsResourcesByOwner() {
        Scope root = new Scope("root", null);
        Scope child = root.createChild("child");
        ResourceRegistry registry = new ResourceRegistry("registry", root);
        registry.register(new FakeResource("a", root, new ArrayList<>(), false));
        registry.register(new FakeResource("b", child, new ArrayList<>(), false));

        assertEquals(1, registry.getResourcesByOwner(child).size());
        assertEquals("b", registry.getResourcesByOwner(child).get(0).id());
    }
}
