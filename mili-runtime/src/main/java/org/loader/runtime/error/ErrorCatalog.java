package org.loader.runtime.error;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Optional;

/**
 * 错误码目录 —— 平台全部标准化错误码的单一事实来源。
 *
 * <h2>为什么需要它</h2>
 * 审计（docs/engineering/implementation-audit.md §0.2.11）确认：仓库里实际
 * 使用的错误码只有 8 个 {@code MOD_*} 码，散落在 {@code ModDiscovery} 与
 * {@code LoaderMain} 的消息字符串里；其余 12 个叶子错误类只有类型、没有码。
 * 结果是两件本该简单的事做不了：
 * <ul>
 *   <li>用户拿着日志里的 {@code ErrorCode: MOD_ABI_MISMATCH} 想知道
 *       "这是什么、怎么修"，只能翻源码；</li>
 *   <li>CI 想按错误类别归档失败原因，没有可枚举的码空间。</li>
 * </ul>
 *
 * <p>本目录把每个码的 <b>标题、成因、典型定位、修复建议、文档链接</b>
 * 收拢为一条 {@link Entry}，供 {@code mili explain <CODE>} 与
 * {@link MiliError#describe(Throwable)} 消费。错误码命名沿用
 * {@code ModLoadError} Javadoc 声明的规范：{@code MOD_ / PLATFORM_ / ABI_ /
 * <组件>_} 前缀 + 大写下划线。
 *
 * <h2>条目的准入规则</h2>
 * <ul>
 *   <li>生产代码抛出过的码（8 个 {@code MOD_*}）必须收录；</li>
 *   <li>每个已有叶子错误类型对应一个码（类型→码的映射见 {@link MiliError}），
 *       没有码的异常在 {@code explain} 里也能被解释，而不是落到UNKNOWN；</li>
 *   <li>禁止收录生产代码从未产生过的"占位码"——目录是描述现实的，
 *       不是许愿池。</li>
 * </ul>
 *
 * <p>不可变。新增条目必须同步更新 {@code docs/ERROR_MODEL.md} 与测试
 * {@code ErrorCatalogTest}。
 */
public final class ErrorCatalog {

    /**
     * 一条错误码解释。
     *
     * @param code     标准化错误码，如 {@code "MOD_ABI_MISMATCH"}
     * @param title    一句话标题（中文，面向用户）
     * @param cause    常见成因
     * @param location 典型发生阶段/定位线索
     * @param fixes    修复建议，至少一条、按优先级排列
     * @param doc      可选文档链接（相对 docs/ 的路径）；无则传 {@code null}
     */
    public record Entry(
            String code,
            String title,
            String cause,
            String location,
            List<String> fixes,
            String doc
    ) {
        public Entry {
            fixes = List.copyOf(fixes);
        }
    }

    private static final Map<String, Entry> ENTRIES = build();

    private ErrorCatalog() {
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new LinkedHashMap<>();

        // ── 生产代码实际抛出的 8 个码（按加载流程顺序排列）──────────────
        m.put("MOD_MANIFEST_INVALID", new Entry(
                "MOD_MANIFEST_INVALID",
                "mod.json 缺失、损坏或缺少必填字段",
                "Mod JAR 内没有 mod.json / META-INF/mod.json，JSON 语法错误，或缺少必填的 id 字段。",
                "启动早期的 Mod 发现阶段（ModDiscovery.parseManifest）。",
                List.of(
                        "检查该 JAR 根目录或 META-INF/ 下是否存在 mod.json，且为合法 JSON",
                        "确认 mod.json 含非空的 id 字段（仅允许小写字母、数字、下划线、连字符）",
                        "用 `mili doctor` 定位是哪个文件解析失败"
                ),
                "docs/MOD_DEVELOPMENT.md"));

        m.put("MOD_PLATFORM_MISSING", new Entry(
                "MOD_PLATFORM_MISSING",
                "mod.json 未声明 mili 平台绑定",
                "mod.json 缺少 mili{platform,abi,minecraft} 三元组，平台拒绝加载未绑定版本的 Mod。",
                "版本校验阶段（LoaderMain.validateVersionBindings / ModManifest.validateVersionBinding）。",
                List.of(
                        "在 mod.json 中补全 mili 块：platform=0.1.0、abi=1、minecraft=26.2",
                        "三元组必须与所用 Mili Platform 版本完全一致，无兼容区间"
                ),
                "docs/PLATFORM_VERSIONING.md"));

        m.put("MOD_PLATFORM_MISMATCH", new Entry(
                "MOD_PLATFORM_MISMATCH",
                "Mod 声明的平台版本与当前 Loader 不一致",
                "mod.json 的 mili.platform 与 Loader 实际版本（0.1.0）不相等。",
                "版本校验阶段。",
                List.of(
                        "将 mod.json 的 mili.platform 改为当前 Loader 版本",
                        "或换用与该 Mod 匹配的 Loader 版本（三元组严格锁定，不做降级兼容）"
                ),
                "docs/PLATFORM_VERSIONING.md"));

        m.put("MOD_ABI_MISMATCH", new Entry(
                "MOD_ABI_MISMATCH",
                "Mod 编译所用的 ABI 契约版本与当前不一致",
                "mod.json 的 mili.abi 与平台 ABI_VERSION（1）不相等；Mod 需要用对应版本的 mili-abi 重新编译。",
                "版本校验阶段。",
                List.of(
                        "用与当前平台相同 ABI 版本的 mili-abi JAR 重新编译 Mod",
                        "ABI 破坏性变更遵循过渡期策略，见 ABI 文档的版本语义章节"
                ),
                "docs/ABI.md"));

        m.put("MOD_MINECRAFT_MISMATCH", new Entry(
                "MOD_MINECRAFT_MISMATCH",
                "Mod 声明的 Minecraft 版本与当前不一致",
                "mod.json 的 mili.minecraft 与平台目标版本（26.2）不相等。",
                "版本校验阶段。",
                List.of(
                        "确认 Mod 是为 Minecraft 26.2 构建的，必要时改 mili.minecraft 为 26.2",
                        "跨 MC 版本运行需要对应版本的集成层，当前平台只支持 26.2"
                ),
                "docs/PLATFORM_VERSIONING.md"));

        m.put("MOD_ENTRYPOINT_INVALID", new Entry(
                "MOD_ENTRYPOINT_INVALID",
                "Mod 入口类无法用作 Mili Mod",
                "entrypoint 为空、类不存在、未实现 org.loader.api.Mod 接口，或是抽象类。",
                "Mod 初始化阶段（LoaderMain.invokeModEntrypoints）。",
                List.of(
                        "确认 mod.json 的 entrypoint 指向实现了 org.loader.api.Mod 的公共具体类",
                        "检查 JAR 里确实打包了该类（`jar tf <mod>.jar | grep <类名>`）",
                        "入口类必须有公共无参构造器"
                ),
                "docs/MOD_DEVELOPMENT.md"));

        m.put("MOD_ENTRYPOINT_FAILED", new Entry(
                "MOD_ENTRYPOINT_FAILED",
                "Mod 入口初始化时抛出异常",
                "Mod 的 initialize() 执行期间抛出了未捕获异常，该 Mod 被隔离，其余 Mod 继续加载。",
                "Mod 初始化阶段。",
                List.of(
                        "查看日志中该异常的完整堆栈（[Mod: <id>] 段落）",
                        "在 Mod 的 initialize() 中自行捕获可预期异常并降级",
                        "修复后重新安装 Mod JAR"
                ),
                "docs/MOD_DEVELOPMENT.md"));

        m.put("MOD_UNKNOWN", new Entry(
                "MOD_UNKNOWN",
                "Mod 加载失败（未分类）",
                "该失败没有被赋予具体错误码——通常是旧代码路径或未覆盖的异常形态。",
                "任意 Mod 加载阶段。",
                List.of(
                        "以日志消息与堆栈为准定位问题",
                        "若可稳定复现，欢迎向维护者报告以补充专用错误码"
                ),
                null));

        // ── 叶子错误类型 → 码（映射关系见 MiliError.TYPE_CODES）─────────
        m.put("PLATFORM_BOOTSTRAP_FAILED", new Entry(
                "PLATFORM_BOOTSTRAP_FAILED",
                "平台引导阶段失败",
                "Runtime 内核创建、Scope 树或生命周期管理器启动失败。",
                "Loader 引导（Runtime.create / Runtime.start）。",
                List.of(
                        "查看 mili-platform.log 中失败点前后的完整日志",
                        "确认 Java 版本 >= 25（低版本 JVM 会以不明确的方式失败）"
                ),
                "docs/history/ARCHITECTURE.md"));

        m.put("CLASSLOADER_LOAD_FAILED", new Entry(
                "CLASSLOADER_LOAD_FAILED",
                "类加载失败",
                "目标类不在可见性契约允许的范围内、字节码缺失，或定义过程抛错。",
                "Mod 类首次解析（ModClassLoader.loadClass / ClassVisibility.resolve）。",
                List.of(
                        "若消息含「禁止访问内部类」：该类在 ClassVisibility 黑名单中，Mod 不允许依赖平台内部",
                        "确认类确实打包在 Mod JAR 中且全限定名拼写正确"
                ),
                "docs/architecture/CLASSLOADER.md"));

        m.put("CAPABILITY_DENIED", new Entry(
                "CAPABILITY_DENIED",
                "能力声明与消费不匹配",
                "请求的能力未被任何提供者以兼容类型注册，或提供者 Scope 已终止。",
                "能力匹配/授予阶段（CapabilityManager / Scope.grantCapability）。",
                List.of(
                        "确认提供方 Mod 已注册同名且类型兼容的能力",
                        "能力匹配使用类型可赋值判定；类型不一致视为不同能力"
                ),
                "docs/decisions/0006-capability-and-permission-separation.md"));

        m.put("PERMISSION_DENIED", new Entry(
                "PERMISSION_DENIED",
                "操作被权限系统拒绝",
                "当前 Scope 未被授予所需权限；默认拒绝是权限系统的基线语义。",
                "运行时权限检查（PermissionManager.checkPermission）。",
                List.of(
                        "在 Mod 权限声明中显式申请该权限",
                        "管理员可通过覆盖默认配置收紧或放宽策略"
                ),
                "docs/design/SECURITY.md"));

        m.put("DEPENDENCY_ERROR", new Entry(
                "DEPENDENCY_ERROR",
                "依赖解析失败",
                "初始化顺序图中出现缺失或非法的依赖边。",
                "依赖解析（DependencyResolver）。",
                List.of(
                        "用 `mili doctor --graph` 查看依赖图与冲突链",
                        "修正 mod.json 的 dependencies 声明"
                ),
                null));

        m.put("LIFECYCLE_INVALID_TRANSITION", new Entry(
                "LIFECYCLE_INVALID_TRANSITION",
                "非法的生命周期状态迁移",
                "Scope 状态机只允许严格单向迁移，回退或跳级都会被拒绝。",
                "Scope.transitionTo / LifecycleState.canTransitionTo。",
                List.of(
                        "检查代码是否试图把已 STOPPED/FAILED 的 Scope 重新投入使用",
                        "重试语义应通过 unload 后重新 load 实现，而非状态回溯"
                ),
                null));

        m.put("SCHEDULER_REJECTED", new Entry(
                "SCHEDULER_REJECTED",
                "调度器拒绝任务",
                "Scope 已停止/关闭时提交任务，或队列拒绝策略触发。",
                "Scheduler.submit 系列。",
                List.of(
                        "确认提交任务时 Scope 仍处于允许新工作的状态（LifecycleState.allowsNewWork）",
                        "在 Scope 关闭回调中不要再提交新任务"
                ),
                null));

        m.put("TICK_FAILED", new Entry(
                "TICK_FAILED",
                "Tick 引擎阶段失败",
                "tick 阶段监听器或任务抛错；单个任务失败不会抛出此错误，会记入 TickContract.fail。",
                "TickEngine 阶段推进。",
                List.of(
                        "查看 TickContract 的 error 与 suppressed 链",
                        "用 `TickWatchdog` 报告定位卡住的归属 Mod"
                ),
                "docs/architecture/TICK_ENGINE.md"));

        m.put("MC_INTEGRATION_FAILED", new Entry(
                "MC_INTEGRATION_FAILED",
                "Minecraft 桥接点解析失败",
                "反射桥接在目标 Minecraft 版本中找不到声明的字段/方法（签名漂移），或集成层注入失败。",
                "反射桥接（Reflect）与字节码注入。",
                List.of(
                        "确认使用的集成层与 Minecraft 版本匹配（三元组锁定）",
                        "运行 `verifyTransformationSymbols`（CI 已内置）核对符号表"
                ),
                "docs/design/TRANSFORMATION_ENGINE.md"));

        m.put("RESOURCE_UNAVAILABLE", new Entry(
                "RESOURCE_UNAVAILABLE",
                "资源不可用",
                "资源流打开失败，或资源所属 Scope 已终止。",
                "ResourceManager.openStream / Resource.close。",
                List.of(
                        "确认资源路径位于本 Mod 的 JAR/basePath 内（跨 Mod 资源必须走 ABI 接口）",
                        "路径含 `..` 会被拒绝——这是刻意的路径穿越防护"
                ),
                null));

        m.put("CONFIG_INVALID", new Entry(
                "CONFIG_INVALID",
                "配置不可用或写入失败",
                "配置文件损坏、不可读，或写入时底层 IO 失败。",
                "ModConfig / PropertiesConfigStore。",
                List.of(
                        "备份并删除损坏的配置文件后重试（平台会重建默认配置）",
                        "确认磁盘可写、路径未被占用"
                ),
                null));

        m.put("NETWORK_FAILED", new Entry(
                "NETWORK_FAILED",
                "网络操作失败",
                "连接建立或数据交换失败；权限系统默认拒绝 Mod 的任意网络访问。",
                "NetworkClient / NetworkServer。",
                List.of(
                        "确认 Mod 已声明并获授网络权限",
                        "确认目标地址可达且未被本机策略拦截"
                ),
                "docs/design/SECURITY.md"));

        m.put("INSTALL_FAILED", new Entry(
                "INSTALL_FAILED",
                "安装过程失败",
                "Minecraft 客户端/库/资源安装阶段失败（下载校验失败、磁盘不足、元数据解析失败等）。",
                "Installer（InstallOrchestrator），报告见 logs/install-report.json。",
                List.of(
                        "查看 logs/install-report.json 的 warnings 与 ok 字段",
                        "重试下载（下载器带 SHA-1 校验与 .part 原子替换，幂等）"
                ),
                null));

        m.put("GAME_NOT_FOUND", new Entry(
                "GAME_NOT_FOUND",
                "未找到可用的 Minecraft",
                "gameDir 下不存在可识别的客户端/服务端 JAR（自动安装也失败时）。",
                "游戏定位阶段（MinecraftDiscovery / MinecraftGameProvider.locateGame）。",
                List.of(
                        "确认 gameDir 正确（分发包中为 <dist>/game）",
                        "手动运行安装器，或检查 logs/install-report.json"
                ),
                null));

        return Collections.unmodifiableMap(m);
    }

    /** 全部条目，按加载流程顺序排列。 */
    public static List<Entry> all() {
        return List.copyOf(ENTRIES.values());
    }

    /** 按码查询。 */
    public static Optional<Entry> get(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        return Optional.ofNullable(ENTRIES.get(code));
    }

    /** 目录中是否收录了该码。 */
    public static boolean contains(String code) {
        return code != null && ENTRIES.containsKey(code);
    }

    /** 收录的码集合（顺序与 {@link #all()} 一致）。 */
    public static List<String> codes() {
        return List.copyOf(ENTRIES.keySet());
    }
}
