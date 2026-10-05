package org.loader.api.transform.symbol;

import org.loader.api.transform.target.TargetField;
import org.loader.api.transform.target.TargetMethod;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 映射层 —— 逻辑符号名到真实 JVM 坐标的解析与证据链。
 *
 * <h2>这一层与 {@link MiliSymbol} 的分工</h2>
 * {@link MiliSymbol} 是<b>静态符号表</b>：编译期就确定的一组坐标常量。
 * 它解决的问题是「不要把 {@code (Ljava/util/function/BooleanSupplier;)V}
 * 这种字符串散落硬编码在各转换器里」。
 *
 * <p>本类解决的是符号表<b>自身</b>的问题：
 * <ul>
 *   <li><b>如何按名字解析</b>（Mod 的注解里只能写字符串，因为 ABI
 *       不允许出现 Minecraft 坐标）；</li>
 *   <li><b>如何发现符号表已经过期</b>。</li>
 * </ul>
 *
 * <h2>为什么符号表过期是本平台最危险的失效</h2>
 * 转换器注入不到任何东西时<b>不会报错</b>。游戏照常运行、Mod 的功能
 * 悄悄消失、日志里什么都没有。本仓库反复记录过同类问题：
 * 注册链在生产路径上是死代码、{@code TickBridge} 零调用者、
 * 文档里对 {@code tickServer} 的描述符有两处互相矛盾的写法。
 *
 * <p>因此本类提供两样东西：
 * <ol>
 *   <li><b>严格解析</b> —— 未注册的符号名抛异常，不返回 null；</li>
 *   <li><b>可校验的枚举</b> —— {@link #entries()} 暴露全部符号，
 *       CI 拿真实 Minecraft jar 逐条比对（见 {@code SymbolVerifier}）。
 *       符号表与真实字节码同源校验，不存在「表过期了没人知道」。</li>
 * </ol>
 *
 * <h2>关于 obfuscation 映射</h2>
 * <b>本类不解析 Mojang 官方映射文件，也不处理 obfuscation 名称。</b>
 * 需求明确要求不要为「未来兼容」一次性实现复杂 mapping 系统。
 *
 * <p>理由是具体的：Minecraft 的正式发行版<b>不做混淆</b> ——
 * {@code net/minecraft/server/MinecraftServer#tickServer} 就是运行时的
 * 真实名称，官方映射与运行时名称一致。真正需要 obf 映射的只有
 * 开发环境下的 dev jar，而平台不在开发环境运行。
 *
 * <p>所以当前的映射是<b>恒等映射</b>：official name == runtime name。
 * 本类的价值不在于转换，而在于<b>把「这是恒等映射」这个事实固定下来
 * 并可验证</b> —— 若未来真的引入了混淆版本，这个类就是唯一需要改的地方，
 * 且它的存在会让那次改动的影响面一目了然。
 *
 * @see MiliSymbol 静态符号表
 */
public final class MiliMapping {

    private MiliMapping() {
    }

    /**
     * 一个映射条目 —— 官方名、运行时名与坐标。
     *
     * <p>刻意把三者的关系显式记录下来（而不是假设相等），
     * 因为 {@code docs/minecraft-26.x/MAPPINGS.md} 要求建立
     * {@code mapping → actual class → bytecode} 证据链：
     * 当符号失效时，需要能回答「官方名是什么、运行时名是什么、
     * 我们当时认为的坐标是什么」。
     */
    public record Entry(
            String symbol,
            String officialOwner,
            String officialName,
            String officialDescriptor,
            String runtimeOwner,
            String runtimeName,
            String runtimeDescriptor
    ) {

        /** 是否为恒等映射（官方名与运行时名完全一致）。 */
        public boolean isIdentity() {
            return officialOwner.equals(runtimeOwner)
                    && officialName.equals(runtimeName)
                    && officialDescriptor.equals(runtimeDescriptor);
        }

        /** 解析为目标方法引用。 */
        public TargetMethod toTargetMethod() {
            return TargetMethod.of(runtimeOwner, runtimeName, runtimeDescriptor);
        }

        /** 解析为目标字段引用。 */
        public TargetField toTargetField() {
            return TargetField.of(runtimeOwner, runtimeName, runtimeDescriptor);
        }

        /**
         * 一致性检查。
         *
         * <p><b>只在非恒等映射时才有意义</b>：若官方名与运行时名不同，
         * 说明该 Minecraft 版本<b>做了混淆</b> —— 而本平台不做 obf
         * 映射（Minecraft 正式版不混淆），此时必须显式失败而不是
         * 悄悄用官方名去注入（那必然注入不到，且不报错）。
         *
         * @throws IllegalStateException 官方名与运行时名不一致
         */
        public void requireIdentity() {
            if (!isIdentity()) {
                throw new IllegalStateException(
                        "映射非恒等，说明该 Minecraft 版本存在混淆：\n"
                                + "  符号: " + symbol + "\n"
                                + "  官方: " + officialOwner + "#" + officialName
                                + officialDescriptor + "\n"
                                + "  运行时: " + runtimeOwner + "#" + runtimeName
                                + runtimeDescriptor + "\n"
                                + "Mili 不支持混淆名称（Minecraft 正式版不混淆）。"
                                + "若运行的是 dev jar，请改用正式版客户端。");
            }
        }
    }

    /**
     * 逻辑符号名 → 映射条目。
     *
     * <p>用 {@link TreeMap} 而非 {@code HashMap}：诊断输出需要稳定顺序，
     * 否则同一个错误在不同机器上打印出不同的条目顺序，diff 变得 noisy。
     */
    private static final Map<String, Entry> ENTRIES = buildEntries();

    private static Map<String, Entry> buildEntries() {
        Map<String, Entry> map = new TreeMap<>();
        for (TargetMethod symbol : new TargetMethod[]{
                MiliSymbol.SERVER_TICK,
                MiliSymbol.SERVER_TICK_CHILDREN,
                MiliSymbol.CLIENT_LEVEL_TICK,
                MiliSymbol.CLIENT_MAIN,
                MiliSymbol.SERVER_MAIN}) {
            // 符号名取类的 toString()：它包含完整坐标，便于排查时对照
            String name = symbol.toString();
            map.put(name, new Entry(name,
                    symbol.owner(), symbol.name(), symbol.descriptor(),
                    symbol.owner(), symbol.name(), symbol.descriptor()));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 按符号名解析为真实坐标。
     *
     * @param symbolName 符号名，如 {@code "net/minecraft/server/MinecraftServer#tickServer(Ljava/util/function/BooleanSupplier;)V"}
     * @return 目标方法引用
     * @throws UnknownSymbolException 符号未注册
     */
    public static TargetMethod resolveMethod(String symbolName) {
        Entry entry = require(symbolName);
        entry.requireIdentity();
        return entry.toTargetMethod();
    }

    /**
     * 按符号名解析为字段坐标。
     *
     * <p>与 {@link #resolveMethod} 分开而非用同一入口：方法描述符形如
     * {@code ()V}，字段描述符形如 {@code I}。若用一个入口，
     * 会出现「拿 {@code ()V} 去比 {@code I}」这种<b>永远为 false</b>的
     * 匹配 —— 表现为字段注入静默失效。
     */
    public static TargetField resolveField(String symbolName) {
        Entry entry = require(symbolName);
        entry.requireIdentity();
        return entry.toTargetField();
    }

    /**
     * 取映射条目。
     *
     * @throws UnknownSymbolException 符号未注册
     */
    public static Entry entry(String symbolName) {
        return require(symbolName);
    }

    private static Entry require(String symbolName) {
        if (symbolName == null || symbolName.isBlank()) {
            throw new UnknownSymbolException(
                    String.valueOf(symbolName),
                    "符号名不能为空。可用符号: " + String.join(", ", ENTRIES.keySet()));
        }
        Entry entry = ENTRIES.get(symbolName.trim());
        if (entry == null) {
            // 明确失败而非返回 null —— 符号写错若静默跳过，
            // 表现就是「Mod 加载成功但功能永远不生效」。
            throw new UnknownSymbolException(symbolName,
                    "未注册的符号名。可用符号:\n  "
                            + String.join("\n  ", ENTRIES.keySet())
                            + "\n符号名写错不会有任何其他症状 —— 转换器静默不生效。");
        }
        return entry;
    }

    /** 全部映射条目（按符号名排序，顺序稳定）。 */
    public static Set<Entry> entries() {
        // ENTRIES 是 TreeMap，values() 本身已按符号名有序。
        // 这里只做不可变包装 —— 诊断输出需要稳定顺序，
        // 否则同一个错误在不同机器上打印出不同的条目顺序。
        return Collections.unmodifiableSet(
                new java.util.LinkedHashSet<>(ENTRIES.values()));
    }

    /** 已注册的符号名（排序后）。 */
    public static Set<String> symbolNames() {
        return ENTRIES.keySet();
    }

    /**
     * 本映射表对应的 Minecraft 版本。
     *
     * <p>与 {@link MiliSymbol#MINECRAFT_VERSION} 同源。
     */
    public static String minecraftVersion() {
        return MiliSymbol.MINECRAFT_VERSION;
    }

    /**
     * 符号未注册。
     *
     * <p>独立于 {@link org.loader.api.transform.TransformationException}
     * —— 它是<b>配置期</b>错误（Mod 声明写错），发生在任何字节码被
     * 转换之前，而不是转换过程中的失败。
     */
    public static final class UnknownSymbolException extends IllegalArgumentException {

        private final String symbol;

        UnknownSymbolException(String symbol, String message) {
            super(message);
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }
}