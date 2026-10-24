package org.loader.runtime.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误目录与 MiliError 归一规则的契约测试。
 *
 * <p>与 {@code docs/ERROR_MODEL.md} 同步维护：目录条目即文档数据，
 * 这里保证目录的结构完整性、生产码全覆盖与类型映射一致性。
 */
class ErrorCatalogTest {

    @Test
    @DisplayName("目录中每个条目的字段都是完整可用的")
    void entriesAreWellFormed() {
        for (ErrorCatalog.Entry e : ErrorCatalog.all()) {
            assertTrue(e.code().matches("[A-Z][A-Z0-9_]+"), "码格式非法: " + e.code());
            assertFalse(e.title().isBlank(), "title 为空: " + e.code());
            assertFalse(e.cause().isBlank(), "cause 为空: " + e.code());
            assertFalse(e.location().isBlank(), "location 为空: " + e.code());
            assertFalse(e.fixes().isEmpty(), "fixes 为空: " + e.code());
            for (String fix : e.fixes()) {
                assertFalse(fix.isBlank(), "存在空建议: " + e.code());
            }
        }
    }

    @Test
    @DisplayName("生产代码实际抛出的 8 个 MOD_* 码全部收录")
    void productionCodesAreCovered() {
        // 来源：implementation-audit.md §0.2.11（grep 生产代码证据）
        for (String code : new String[]{
                "MOD_MANIFEST_INVALID", "MOD_ENTRYPOINT_INVALID", "MOD_ENTRYPOINT_FAILED",
                "MOD_PLATFORM_MISSING", "MOD_PLATFORM_MISMATCH", "MOD_ABI_MISMATCH",
                "MOD_MINECRAFT_MISMATCH", "MOD_UNKNOWN"}) {
            assertTrue(ErrorCatalog.contains(code), "缺少生产码: " + code);
        }
    }

    @Test
    @DisplayName("码不允许重复注册")
    void codesAreUnique() {
        assertEquals(ErrorCatalog.all().size(), ErrorCatalog.codes().size(),
                "存在重复码 —— Map 构建应保证唯一");
    }

    @Test
    @DisplayName("叶子错误类型都能归一到目录中存在的码")
    void everyErrorTypeMapsToCatalogEntry() {
        assertEquals("PLATFORM_BOOTSTRAP_FAILED",
                MiliError.codeOf(new BootstrapError("x")));
        assertEquals("CLASSLOADER_LOAD_FAILED",
                MiliError.codeOf(new ClassLoaderError("x", "Foo", null)));
        assertEquals("CAPABILITY_DENIED",
                MiliError.codeOf(new CapabilityError("x")));
        assertEquals("PERMISSION_DENIED",
                MiliError.codeOf(new PermissionError("x")));
        assertEquals("DEPENDENCY_ERROR",
                MiliError.codeOf(new DependencyError("x")));
        assertEquals("LIFECYCLE_INVALID_TRANSITION",
                MiliError.codeOf(new LifecycleError("x")));
        assertEquals("SCHEDULER_REJECTED",
                MiliError.codeOf(new SchedulerError("x")));
        assertEquals("TICK_FAILED",
                MiliError.codeOf(new TickError("x", 1L)));
        assertEquals("MC_INTEGRATION_FAILED",
                MiliError.codeOf(new MinecraftIntegrationError("x")));
        assertEquals("RESOURCE_UNAVAILABLE",
                MiliError.codeOf(new ResourceError("x")));
        assertEquals("CONFIG_INVALID",
                MiliError.codeOf(new ConfigurationError("x")));
        assertEquals("NETWORK_FAILED",
                MiliError.codeOf(new NetworkError("x")));
        assertEquals("INSTALL_FAILED",
                MiliError.codeOf(new InstallationError("x")));
    }

    @Test
    @DisplayName("ModLoadError 自带的码优先于类型映射")
    void modLoadErrorCarriesItsOwnCode() {
        assertEquals("MOD_ABI_MISMATCH",
                MiliError.codeOf(new ModLoadError("msg", "MOD_ABI_MISMATCH")));
        // 兜底构造器的码是 MOD_UNKNOWN，同样应在目录中
        assertEquals("MOD_UNKNOWN",
                MiliError.codeOf(new ModLoadError("msg")));
        assertTrue(ErrorCatalog.contains(
                MiliError.codeOf(new ModLoadError("msg"))));
    }

    @Test
    @DisplayName("cause 链上的错误码可以被找到")
    void causeChainIsWalked() {
        Exception wrapped = new IllegalStateException("ctx",
                new ModLoadError("root cause", "MOD_MANIFEST_INVALID"));
        assertEquals("MOD_MANIFEST_INVALID", MiliError.codeOf(wrapped));
    }

    @Test
    @DisplayName("无法归类时回落 PLATFORM_UNKNOWN 且 describe 不抛异常")
    void unknownFallsBackSafely() {
        assertEquals(MiliError.UNKNOWN, MiliError.codeOf(new NullPointerException()));
        assertEquals(MiliError.UNKNOWN, MiliError.codeOf(null));
        var entry = MiliError.describe(new NullPointerException());
        assertTrue(entry.isPresent());
        assertEquals(MiliError.UNKNOWN, entry.get().code());
        assertFalse(entry.get().fixes().isEmpty());
    }

    @Test
    @DisplayName("get 对空白/未知码返回空")
    void getRejectsBlankAndUnknown() {
        assertTrue(ErrorCatalog.get(null).isEmpty());
        assertTrue(ErrorCatalog.get("").isEmpty());
        assertTrue(ErrorCatalog.get("NOT_A_CODE").isEmpty());
    }
}
