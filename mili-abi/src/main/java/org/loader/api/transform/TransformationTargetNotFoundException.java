package org.loader.api.transform;

/**
 * 目标解析失败 —— 声明要转换的目标在目标类里不存在。
 *
 * <p><b>这个异常必须抛出，绝不能被静默吞掉。</b>
 *
 * <p>平台是严格版本对齐的：转换器声明 {@code minecraftVersion = "26.2"}，
 * 平台校验它与实际加载的版本完全一致。但<b>版本号相同不等于方法名相同</b> ——
 * Mojang 的快照版本仍可能调整方法命名。因此仅靠版本字符串无法保证目标存在。
 *
 * <p>若静默跳过，后果是：Mili Core 的 tick 转换器发现
 * {@code tickServer} 不存在，安静地不注入，游戏照常启动，玩家照常玩，
 * 只是 tick 数永远是 0，Mod 的所有 tick 回调永不触发，
 * 且没有任何一行错误日志。
 *
 * <p>这正是需求中「不要静默跳过关键 Mili Core Transformer」的原因。
 */
public final class TransformationTargetNotFoundException extends TransformationException {

    private static final long serialVersionUID = 1L;

    /** 目标类内部名（斜杠分隔），如 {@code net/minecraft/server/MinecraftServer}。 */
    private final String className;
    /** 目标方法名；字段目标时为字段名。 */
    private final String memberName;
    /** 目标描述符；无法确定时为 null。 */
    private final String descriptor;
    /** 使用的 Minecraft 版本。 */
    private final String minecraftVersion;

    public TransformationTargetNotFoundException(
            String className,
            String memberName,
            String descriptor,
            String minecraftVersion,
            String transformerId) {
        super(buildMessage(className, memberName, descriptor,
                minecraftVersion, transformerId), transformerId, null);
        this.className = className;
        this.memberName = memberName;
        this.descriptor = descriptor;
        this.minecraftVersion = minecraftVersion;
    }

    /**
     * 诊断文案 —— <b>必须包含 transformerId</b>。
     *
     * <p>「目标不存在」本身不说明<b>是谁</b>的目标不存在。启动日志里
     * 同时有几十个转换器跑过同一个类，缺了 transformerId 的消息会让排查者
     * 先去翻代码找「哪个转换器声明了 tickServer」——
     * 而答案本该直接写在异常消息里。
     */
    private static String buildMessage(
            String cls, String member, String desc,
            String mcVersion, String transformerId) {
        return "转换目标不存在:\n"
                + "  Class        : " + cls + "\n"
                + "  Member       : " + member + "\n"
                + "  Descriptor   : " + (desc == null ? "<未指定>" : desc) + "\n"
                + "  Minecraft    : " + mcVersion + "\n"
                + "  Transformer  : " + (transformerId == null ? "<未指定>" : transformerId) + "\n"
                + "转换器声明的目标在该版本中不存在。\n"
                + "  * 若目标确实改名 → 更新转换器与符号表\n"
                + "  * 若该转换器本就不适用于此类目标 → 返回 TransformationResult.Skipped\n"
                + "  * 绝不要在这里静默返回 Skipped";
    }

    /**
     * 「方法存在，但它内部没有声明的那次调用」专用工厂。
     *
     * <h2>为什么必须与「成员不存在」分开</h2>
     * {@link org.loader.api.transform.target.TargetMethod} 命中了
     * （方法确实在），但 {@link org.loader.api.transform.target.TargetInvocation}
     * 没命中 —— 方法体里没有那次调用，或 ordinal 超出实际次数。
     *
     * <p>两种失败的修法完全相反：
     * <ul>
     *   <li>成员不存在 → 符号表过期，要更新转换器；</li>
     *   <li>调用点不存在 → <b>符号表是对的</b>，是 Mod 声明错了
     *       （目标方法在 26.2 里被内联了、或调用点换了接收者）。</li>
     * </ul>
     * 混为同一句「目标不存在」会让排查者去查符号表 —— 查错方向。
     *
     * @param hostOwner   宿主类内部名
     * @param hostName    宿主方法名
     * @param hostDesc    宿主方法描述符
     * @param invocation  声明但未命中的调用点
     * @param transformerId 转换器 id
     */
    public static TransformationTargetNotFoundException forCallSite(
            String hostOwner,
            String hostName,
            String hostDesc,
            org.loader.api.transform.target.TargetInvocation invocation,
            String transformerId) {

        String called = invocation.method().name()
                + invocation.method().descriptor();
        String message = "调用点不存在:\n"
                + "  Host        : " + hostOwner + "#" + hostName + hostDesc + "\n"
                + "  Calls       : " + invocation.method().owner()
                + "#" + called + "\n"
                + "  Ordinal     : " + invocation.ordinal()
                + "（按同签名调用从 0 计数）\n"
                + "  Minecraft   : " + org.loader.api.VersionInfo.TARGET_MINECRAFT + "\n"
                + "  Transformer : "
                + (transformerId == null ? "<未指定>" : transformerId) + "\n"
                + "宿主方法存在，但方法体内没有匹配上述签名的调用"
                + "（或 ordinal 超出实际次数）。\n"
                + "  * 该方法在此版本被内联 / 优化掉了 → 换一个调用点\n"
                + "  * ordinal 写大了 → 按实际调用顺序修正\n"
                + "  * 签名写错 → 用 MiliSymbol 核对坐标\n"
                + "绝不要静默跳过：表现会是「转换成功但 Mod 逻辑一次都没执行」，"
                + "且没有任何错误日志。";
        return new TransformationTargetNotFoundException(message,
                hostOwner, hostName + hostDesc, hostDesc, transformerId,
                org.loader.api.VersionInfo.TARGET_MINECRAFT);
    }

    /**
     * 供 {@link #forCallSite} 使用的构造器 —— 允许自定义诊断文案。
     *
     * <p>多一个 {@code messageVersion} 参数只为与公有构造器区分 ——
     * 两者参数列表本就只能差在类型上，而这里的差异必须在签名层面体现。
     */
    private TransformationTargetNotFoundException(
            String message,
            String className,
            String memberName,
            String descriptor,
            String transformerId,
            String minecraftVersion) {
        super(message, transformerId, null);
        this.className = className;
        this.memberName = memberName;
        this.descriptor = descriptor;
        this.minecraftVersion = minecraftVersion;
    }

    public String className() {
        return className;
    }

    public String memberName() {
        return memberName;
    }

    public String descriptor() {
        return descriptor;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }
}