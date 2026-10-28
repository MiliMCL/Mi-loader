package org.loader.runtime.kernel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scope 关闭协议。
 *
 * <p>这些断言守的是「关闭只发生一次」与「关闭顺序确定」——
 * 两条都属于「错了不一定立刻炸，但会留下悬挂线程/双重释放」的类型。
 */
class ScopeShutdownTest {

    @Test
    @DisplayName("并发 shutdown 只执行一次清理 —— 资源不会被关两次")
    void concurrentShutdownRunsCleanupOnce() throws Exception {
        Scope scope = new Scope("root", null);
        scope.driveToRunning();

        AtomicInteger closes = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            scope.registerResource(new Resource() {
                @Override
                public String id() {
                    return "r" + closes.get();
                }

                @Override
                public Scope owner() {
                    return scope;
                }

                @Override
                public boolean isClosed() {
                    return false;
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            });
        }

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                scope.shutdown();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        // 8 个资源，各关一次。旧实现在并发下会关 8×N 次。
        assertEquals(8, closes.get(), "每个资源必须且只能被关闭一次");
        assertEquals(LifecycleState.STOPPED, scope.state());
        assertTrue(scope.isStopped());
    }

    @Test
    @DisplayName("子 Scope 先于父 Scope 关闭")
    void childrenShutdownBeforeParent() {
        Scope parent = new Scope("parent", null);
        parent.driveToRunning();
        Scope child = parent.createChild("child");
        child.driveToRunning();

        List<String> order = new ArrayList<>();
        parent.registerResource(resource("parent-res", parent, () -> order.add("parent")));
        child.registerResource(resource("child-res", child, () -> order.add("child")));

        parent.shutdown();

        assertEquals(List.of("child", "parent"), order,
                "子 Scope 的资源必须先释放 —— 父 Scope 的资源可能被子 Scope 使用");
    }

    @Test
    @DisplayName("监听器抛异常不中断状态迁移，也不吞掉其他监听器")
    void listenerFailureDoesNotBlockOthers() {
        Scope scope = new Scope("root", null);
        scope.driveToRunning();

        List<String> seen = new ArrayList<>();
        scope.addListener((s, from, to) -> {
            throw new IllegalStateException("故意抛错");
        });
        scope.addListener((s, from, to) -> seen.add(from + "->" + to));

        scope.shutdown();

        // 第二个监听器仍然收到了两次迁移（RUNNING->STOPPING, STOPPING->STOPPED）
        assertEquals(2, seen.size(), "一个监听器抛错不得让其他监听器漏收事件");
        assertEquals(LifecycleState.STOPPED, scope.state());
    }

    @Test
    @DisplayName("driveToRunning 幂等，且能一次推进到底")
    void driveToRunningIsIdempotent() {
        Scope scope = new Scope("s", null);
        assertEquals(LifecycleState.RUNNING, scope.driveToRunning());
        assertEquals(LifecycleState.RUNNING, scope.driveToRunning());
        assertEquals(LifecycleState.RUNNING, scope.state());
    }

    @Test
    @DisplayName("removeListener 按身份移除 —— 移除后不再收到事件")
    void removeListenerByInstance() {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        AtomicInteger calls = new AtomicInteger();
        ScopeListener listener = (s, from, to) -> calls.incrementAndGet();

        scope.addListener(listener);
        scope.transitionTo(LifecycleState.STOPPING);
        assertEquals(1, calls.get());

        scope.removeListener(listener);
        scope.transitionTo(LifecycleState.STOPPED);
        assertEquals(1, calls.get(), "移除后不应再收到事件");
    }

    @Test
    @DisplayName("getResource(Class) 能按类型取出平台装入的组件")
    void getResourceByType() {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        MarkerResource res = new MarkerResource(scope);
        scope.registerResource(res);

        assertSame(res, scope.getResource(MarkerResource.class).orElseThrow(),
                "按类型查找必须命中已注册资源");
        assertTrue(scope.getResource(String.class).isEmpty(),
                "未注册的类型必须返回空，而不是抛异常");
    }

    /** 用于按类型查找的标记资源。 */
    private static final class MarkerResource implements Resource {
        private final Scope owner;

        MarkerResource(Scope owner) {
            this.owner = owner;
        }

        @Override
        public String id() {
            return "marker";
        }

        @Override
        public Scope owner() {
            return owner;
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("已关闭的 Scope 拒绝注册新资源")
    void stoppedScopeRejectsRegistration() {
        Scope scope = new Scope("s", null);
        scope.driveToRunning();
        scope.shutdown();

        assertThrows(IllegalStateException.class,
                () -> scope.registerResource(resource("late", scope, () -> {
                })));
        assertThrows(IllegalStateException.class, () -> scope.createChild("late-child"));
    }

    private static Resource resource(String id, Scope owner, Runnable onClose) {
        return new Resource() {
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
                return false;
            }

            @Override
            public void close() {
                onClose.run();
            }
        };
    }
}
