package org.loader.runtime.error;

import java.util.Map;
import java.util.Optional;

/**
 * 错误模型门面：把任意 {@link Throwable} 归一到标准错误码与目录条目。
 *
 * <h2>解决什么问题</h2>
 * 审计确认（docs/engineering/implementation-audit.md §0.2.11）：8 个
 * {@code MOD_*} 码只挂在 {@link ModLoadError} 上，其余 12 个叶子错误类型
 * 只有类型没有码；而 {@code ErrorContext} 的 Javadoc 早已引用了一个不存在的
 * {@code MiliError} —— 这正是本类：统一"异常 → 错误码 → 解释"的查询入口，
 * 同时让既有 Javadoc 的引用不再是死链。
 *
 * <h2>归一规则（顺序即优先级）</h2>
 * <ol>
 *   <li>{@link ModLoadError} 直接取其 {@link ModLoadError#errorCode()}；</li>
 *   <li>其他叶子错误类型按 {@link #TYPE_CODES} 的映射取码；</li>
 *   <li>沿 cause 链向上找，直到栈底；</li>
 *   <li>都未命中则回落 {@code PLATFORM_UNKNOWN}。</li>
 * </ol>
 *
 * <p>本类只做<b>查询与解释</b>，绝不包装或替换调用方异常——错误处理的
 * 控制流仍归各抛出点所有。
 */
public final class MiliError {

    /** 目录之外的兜底码：无法归类到任何已知码时使用。 */
    public static final String UNKNOWN = "PLATFORM_UNKNOWN";

    /**
     * 叶子错误类型 → 标准错误码。
     *
     * <p>映射必须与 {@link ErrorCatalog} 的条目一一对应（测试保证）。
     * {@link ModLoadError} 不在此表——它自带码。
     */
    private static final Map<Class<? extends Throwable>, String> TYPE_CODES = Map.ofEntries(
            Map.entry(BootstrapError.class, "PLATFORM_BOOTSTRAP_FAILED"),
            Map.entry(ClassLoaderError.class, "CLASSLOADER_LOAD_FAILED"),
            Map.entry(CapabilityError.class, "CAPABILITY_DENIED"),
            Map.entry(PermissionError.class, "PERMISSION_DENIED"),
            Map.entry(DependencyError.class, "DEPENDENCY_ERROR"),
            Map.entry(LifecycleError.class, "LIFECYCLE_INVALID_TRANSITION"),
            Map.entry(SchedulerError.class, "SCHEDULER_REJECTED"),
            Map.entry(TickError.class, "TICK_FAILED"),
            Map.entry(MinecraftIntegrationError.class, "MC_INTEGRATION_FAILED"),
            Map.entry(ResourceError.class, "RESOURCE_UNAVAILABLE"),
            Map.entry(ConfigurationError.class, "CONFIG_INVALID"),
            Map.entry(NetworkError.class, "NETWORK_FAILED"),
            Map.entry(InstallationError.class, "INSTALL_FAILED")
    );

    private MiliError() {
    }

    /**
     * 返回异常对应的标准错误码。规则见类注释；永不返回 null。
     */
    public static String codeOf(Throwable t) {
        if (t == null) return UNKNOWN;
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 16) {
            if (cur instanceof ModLoadError mle) {
                String code = mle.errorCode();
                if (code != null && !code.isBlank()) return code;
            }
            String byType = TYPE_CODES.get(cur.getClass());
            if (byType != null) return byType;
            cur = cur.getCause();
        }
        return UNKNOWN;
    }

    /**
     * 返回异常在目录中的解释条目；目录未收录时回落到 PLATFORM_UNKNOWN
     * 的条目（目录不收录该兜底码，因此返回的条目按需构造）。
     */
    public static Optional<ErrorCatalog.Entry> describe(Throwable t) {
        String code = codeOf(t);
        return ErrorCatalog.get(code).or(() -> Optional.of(unknownEntry()));
    }

    /** PLATFORM_UNKNOWN 的解释条目（不在目录中，按需构造）。 */
    public static ErrorCatalog.Entry unknownEntry() {
        return new ErrorCatalog.Entry(
                UNKNOWN,
                "未分类的平台错误",
                "异常不属于任何已登记的错误类型，也未携带标准错误码。",
                "任意阶段。",
                java.util.List.of(
                        "以日志消息与堆栈为准定位问题",
                        "若可稳定复现，欢迎向维护者报告以补充专用错误码"
                ),
                null);
    }
}
