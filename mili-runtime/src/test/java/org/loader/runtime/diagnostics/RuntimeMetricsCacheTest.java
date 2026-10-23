package org.loader.runtime.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.api.diagnostics.PlatformMetrics;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimeMetrics} 的 250ms 合并窗口。
 *
 * <p>这条测试防的是「有人为了『更实时』把缓存删掉」：那会让每个渲染帧
 * 都遍历一次所有 Mod 的 ClassLoader，帧时间随 Mod 数量线性增长。
 * 合并窗口的语义是「同一帧内多次读取必须拿到同一个不可变快照」。
 */
class RuntimeMetricsCacheTest {

    private static RuntimeMetrics metrics() {
        return new RuntimeMetrics(() -> 1, Map::of, () -> 0L, () -> 0d);
    }

    @Test
    @DisplayName("窗口内重复调用返回同一实例")
    void withinWindowReturnsSameSnapshot() {
        RuntimeMetrics metrics = metrics();
        PlatformMetrics first = metrics.snapshot();
        PlatformMetrics second = metrics.snapshot();
        // 不做 sleep：两次调用之间的耗时远小于 250ms
        assertSame(first, second, "250ms 内应复用同一个快照");
    }

    @Test
    @DisplayName("sampleNow 绕过合并窗口")
    void sampleNowBypassesCache() {
        RuntimeMetrics metrics = metrics();
        PlatformMetrics cached = metrics.snapshot();
        PlatformMetrics fresh = metrics.sampleNow();
        assertNotSame(cached, fresh, "sampleNow 必须重新采样");
        assertTrue(fresh.uptimeMillis() >= cached.uptimeMillis());
    }

    @Test
    @DisplayName("超过窗口后重新采样")
    void afterTtlSamplesAgain() throws InterruptedException {
        RuntimeMetrics metrics = metrics();
        PlatformMetrics first = metrics.snapshot();
        Thread.sleep(300L);
        PlatformMetrics second = metrics.snapshot();
        assertNotSame(first, second, "超过 250ms 后应重新采样");
    }
}
