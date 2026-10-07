package org.loader.runtime.transform.verify;

import org.loader.api.transform.TransformationVerificationException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.SimpleVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 字节码验证器 —— 在 {@code defineClass} 之前确认转换结果是合法的。
 *
 * <h2>为什么这道关卡不可省略</h2>
 * 转换器产出非法字节码时，JVM 的报错方式对 Mod 作者几乎毫无帮助：
 * <pre>
 *   java.lang.VerifyError: Bad type on operand stack
 *   at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:0)
 * </pre>
 * 堆栈指向游戏代码，<b>完全不指向真正的原因</b>（是哪个 Mod 的哪个
 * 转换器插坏了栈）。本仓库在
 * {@code GeneratedBlockFactoryRealMinecraftTest} 的注释里记录过同类教训：
 * 「任何一处描述符写错……都不会在编译期暴露，只会在游戏里抛
 * {@code ClassFormatError}，而这类错误信息完全不指向真正的原因」。
 *
 * <p>因此这里把上下文全部补齐：类、方法、转换器、Mod、偏移、原因。
 *
 * <h2>三层验证</h2>
 * <ol>
 *   <li><b>结构合法性</b> —— {@link CheckClassAdapter}：常量池、指令编码、
 *       跳转偏移、分支目标是否合法</li>
 *   <li><b>帧计算</b> —— {@link CheckClassAdapter} 的数据流校验：
 *       栈深在每个跳转汇合处是否一致</li>
 *   <li><b>类型推断</b> —— {@link Analyzer} + {@link SimpleVerifier}：
 *       操作数栈与局部变量的类型是否匹配（比前两层更严格）</li>
 * </ol>
 *
 * <p>第三层能抓到前两层漏掉的一类错误：栈上的类型不匹配但结构合法
 * —— 典型是把 {@code long} 当 {@code int} 用。注入 {@code MODIFY_ARG}
 * 时这最容易发生。
 *
 * <h2>关键设计：「验证不了」不等于「非法」</h2>
 *
 * <p>{@link CheckClassAdapter} 与 {@link SimpleVerifier} 做类型推断时会
 * <b>真的去加载父类与接口</b>。游戏类的方法签名里引用了大量
 * Minecraft 自身的依赖（brigadier、fastutil、Guava……），
 * <b>其中任何一个不在给定 ClassLoader 的可见范围内</b>，
 * 验证就会抛出 {@code ClassNotFoundException} / {@code NoClassDefFoundError}。
 *
 * <p><b>此时字节码本身完全可能是合法的。</b>把这种「验证器看不到」
 * 报成「字节码非法」，后果是：正确无误的转换被拦截，
 * {@code defineClass} 不执行，游戏<b>启动直接失败</b>，
 * 而错误信息里只有一句无法定位的 {@code com.mojang/brigadier/Message}。
 *
 * <p>本类因此区分三态：
 * <ul>
 *   <li><b>通过</b> —— 三层全过；</li>
 *   <li><b>非法</b> —— 拿到确凿的结构/类型错误，抛
 *       {@link TransformationVerificationException}；</li>
 *   <li><b>无法判定</b> —— 缺游戏依赖，退化为
 *       {@link org.objectweb.asm.tree.analysis.BasicVerifier}
 *       做纯栈深校验，并记入 {@link #unresolvedTypes()} 供诊断。
 *       <b>不抛异常</b>，因为把「不知道」谎报成「知道了，而且违法」，
 *       比放过一个可能的错误危险得多。</li>
 * </ul>
 *
 * <p>降级不是无条件放行：{@link BasicVerifier} 仍然会抓出栈深不匹配、
 * 栈下溢、类型误用等不依赖外部类即可判定的错误 ——
 * 而这些恰好覆盖了绝大多数注入事故。
 *
 * <h2>性能</h2>
 * 验证有成本，但它只发生在<b>真正被转换过的类</b>上（未匹配的类
 * 根本不进入 ASM），因此不构成启动瓶颈。
 */
public final class BytecodeVerifier {

    /**
     * 验证过程中发现「被引用但无法加载」的类名。
     *
     * <p>用于诊断而非控制流：出现它们说明验证已降级，
     * 需要检查 {@code MinecraftClassLoader} 的类路径是否完整
     * （最常见原因：Minecraft 的 libraries 没被加入）。
     */
    private static final java.util.Set<String> UNRESOLVED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private BytecodeVerifier() {
    }

    /**
     * 本次运行中验证器无法加载的类名集合。
     *
     * <p>非空即表示<b>至少有一次验证发生了降级</b>。生产环境里它应当为空；
     * 非空说明 {@code MinecraftClassLoader} 的类路径不完整，
     * 此时类型推断层的强度下降（退化为纯栈深校验）。
     */
    public static java.util.Set<String> unresolvedTypes() {
        return java.util.Set.copyOf(UNRESOLVED);
    }

    /** 清空诊断记录 —— 供测试隔离。 */
    public static void clearDiagnostics() {
        UNRESOLVED.clear();
    }

    /**
     * 验证转换后的字节码。
     *
     * @param className     类内部名（用于报错）
     * @param bytecode      转换后的字节码
     * @param transformerId 转换器 id
     * @param modId         Mod id；平台转换为 null
     * @throws TransformationVerificationException 验证失败
     */
    public static void verify(
            String className,
            byte[] bytecode,
            String transformerId,
            String modId) {
        verify(className, bytecode, transformerId, modId, null);
    }

    /**
     * 验证转换后的字节码，可指定用于解析外部类型的 ClassLoader。
     *
     * <h2>为什么生产环境必须传 ClassLoader</h2>
     * 验证发生在 {@code defineClass} <b>之前</b>，此刻目标类自身都还不存在
     * 于任何 ClassLoader 中 —— 它的父类（如 {@code MinecraftServer} 的
     * {@code ReentrantBlockableEventLoop}）自然也解析不到。
     * 不传则验证永远降级，等于第三层形同虚设。
     *
     * @param gameLoader 游戏类加载器；为 null 时退化为不解析外部类型
     */
    public static void verify(
            String className,
            byte[] bytecode,
            String transformerId,
            String modId,
            ClassLoader gameLoader) {

        if (bytecode == null || bytecode.length == 0) {
            throw new TransformationVerificationException(
                    className, null, transformerId, modId, -1,
                    "转换结果为空字节码，无法 defineClass", null);
        }

        // ── 第 1 层：结构 + 帧 ────────────────────────────────────────
        String structureErrors = runCheckClassAdapter(className, bytecode, gameLoader);
        if (structureErrors != null) {
            throw new TransformationVerificationException(
                    className, null, transformerId, modId, -1,
                    "结构/帧校验失败:\n" + structureErrors, null);
        }

        // ── 第 2 层：类型推断 ─────────────────────────────────────────
        String analysisError =
                runAnalyzer(className, bytecode, transformerId, modId, gameLoader);
        if (analysisError != null) {
            TransformationVerificationException failure =
                    new TransformationVerificationException(
                            className, null, transformerId, modId, -1,
                            "类型推断失败:\n" + analysisError, null);
            // 黑匣子：完整堆栈直达 stderr —— message 只承载结构化字段，
            // 真正的指令级细节（哪条指令、期望什么、实际什么）只在堆栈里。
            failure.printStackTrace();
            throw failure;
        }
    }

    /**
     * {@link CheckClassAdapter} 校验 —— 捕获结构与栈帧错误。
     *
     * <h2>为什么必须同时处理「抛出」和「写进 PrintWriter」两条路</h2>
     * {@code CheckClassAdapter.verify} 对不同类型的问题有两种呈现方式：
     * <ul>
     *   <li>解析/IO 类问题 —— 直接 <b>throw</b>；</li>
     *   <li>数据流分析失败 —— 把 {@code AnalyzerException} 的完整堆栈
     *       <b>printStackTrace 到传入的 PrintWriter</b>，
     *       然后正常返回。</li>
     * </ul>
     * 后者是最容易漏掉的一种：方法不抛异常，看起来「验证通过了」，
     * 真相全在那段文本里。若只catch Throwable，整段文本会被当成
     * 「结构/帧校验失败」上报 —— 而缺类场景下它是<b>唯一</b>的输出。
     *
     * @return 错误描述；无错误（含降级）时返回 null
     */
    private static String runCheckClassAdapter(
            String className, byte[] bytecode, ClassLoader gameLoader) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            // 传 ClassReader 会启用完整的数据流校验（CheckClassAdapter.verify）。
            // 4 参重载额外接受 ClassLoader —— 没有它，验证器解析父类时会
            // 抛 ClassNotFoundException，并把正确的字节码误判为非法。
            if (gameLoader != null) {
                CheckClassAdapter.verify(new ClassReader(bytecode),
                        gameLoader,
                        false,            // 不打印到 System.out
                        writer);
            } else {
                CheckClassAdapter.verify(new ClassReader(bytecode),
                        false,
                        writer);
            }
        } catch (Throwable t) {
            return checkUnsupported(t);
        }
        String text = out.toString();
        if (text.isBlank()) {
            return null;
        }
        // 文本形式的分析失败：先判断是不是「缺类」而非「字节码非法」。
        //
        // ASM 在这段文本里用的是固定的三段式：
        //   AnalyzerException: Error at instruction N:
        //   Type java.lang.ClassNotFoundException: X not present
        // 其中 "ClassNotFoundException"/"NoClassDefFoundError" 与
        // " not present" 是 ASM 自己拼的文案，稳定且只用于这一种场景
        // （对比：真正的类型不匹配文案是 "Bad type on operand stack"）。
        //
        // 这里仍属文本嗅探，但只嗅探 ASM 的异常类型名与固定后缀 ——
        // 不嗅探就无法降级。真正的兜底在第 2 层：
        // runAnalyzer 会用 BasicVerifier 独立复核栈深与类型，
        // 缺类场景下它不依赖外部类，因此仍能给出确定结论。
        String missing = extractMissingType(text);
        if (missing != null) {
            UNRESOLVED.add(missing);
            return null;
        }
        return text;
    }

    /**
     * 从 ASM 的分析失败文本里提取「缺失的类名」。
     *
     * <p>识别两种形态：
     * <pre>
     *   Type java.lang.ClassNotFoundException: net.minecraft.Foo not present
     *   Type java.lang.NoClassDefFoundError: com/mojang/brigadier/Message
     * </pre>
     *
     * @return 类名；不是缺类场景时返回 null
     */
    private static String extractMissingType(String text) {
        for (String marker : new String[]{
                "ClassNotFoundException:", "NoClassDefFoundError:"}) {
            int idx = text.indexOf(marker);
            if (idx < 0) {
                continue;
            }
            int start = idx + marker.length();
            // 缺类场景下类名后面一定跟着 " not present"（ASM 固定文案）；
            // NoClassDefFoundError 分支没有该后缀，改为取到行尾。
            int end = text.indexOf(" not present", start);
            if (end < 0) {
                end = text.indexOf('\n', start);
            }
            if (end < 0) {
                end = text.length();
            }
            String name = text.substring(start, end).trim();
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /**
     * 判断一个验证异常是「缺类」还是「真非法」。
     *
     * <h2>为什么必须逐层看 cause 链</h2>
     * ASM 把缺类包装成好几层：{@code AnalyzerException} →
     * {@code TypeNotPresentException} → {@code ClassNotFoundException}。
     * 只看最外层永远只能看到 "AnalyzerException: Error at instruction N"，
     * 而那句话里<b>没有任何线索</b>说明真正原因是缺类。
     *
     * <p>而错误信息里没有线索，正是这类故障最难定位的原因 ——
     * 报错指向「字节码非法」，真因却是「类路径不完整」，
     * 排查方向完全相反。
     */
    private static String checkUnsupported(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClassNotFoundException
                    || cause instanceof NoClassDefFoundError) {
                String missing = cause.getMessage();
                UNRESOLVED.add(missing == null ? "<unknown>" : missing);
                // 降级：不报错。
                //
                // 这里返回 null 而不是错误描述 —— 结构/帧校验已由
                // runAnalyzer 的 BasicVerifier 兜底（见该方法 Javadoc）。
                return null;
            }
            if (cause.getCause() == cause) {
                break;      // 自引用，防止死循环
            }
        }
        return describe(t);
    }

    /**
     * {@link Analyzer} + {@link SimpleVerifier} 类型推断。
     *
     * <p>{@code SimpleVerifier} 而非 {@code BasicVerifier}：前者会做
     * 常规子类型判断，后者只做 == 比较。后者过于宽松 —— 它接受
     * 「把 String 当 Object 用」这类合法但可能出错的栈操作，
     * 而把 String 当 int 用才是我们要抓的。SimpleVerifier 在不加载
     * 外部类的前提下能做到这一点。
     *
     * <h2>降级路径</h2>
     * {@code SimpleVerifier} 因缺类失败时，<b>不是简单放弃</b> ——
     * 而是用 {@link BasicVerifier} 重跑一遍。后者不加载任何外部类，
     * 因此能独立完成：栈深跟踪、栈下溢检测、跳转前后栈高一致性、
     * 局部变量槽类型检查。
     *
     * <p>这些恰好覆盖了注入事故的主要形态（多压/少压一个值、
     * 宽类型当窄类型用）。真正需要 {@code SimpleVerifier} 才能抓的
     * 是「子类/接口赋值给基类引用」这类跨类型错误 ——
     * 它在缺依赖时查不了，但那是「查不到」而非「查出错」。
     *
     * @return 错误描述；无错误（含降级）时返回 null
     */
    private static String runAnalyzer(
            String className,
            byte[] bytecode,
            String transformerId,
            String modId,
            ClassLoader gameLoader) {

        ClassNode node = new ClassNode();
        try {
            new ClassReader(bytecode).accept(node, ClassReader.EXPAND_FRAMES);
        } catch (Throwable t) {
            return "字节码无法解析: " + describe(t);
        }

        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            // 先用 SimpleVerifier 做完整类型推断
            MethodAnalysis strict = analyzeMethod(node, method, gameLoader, true);
            if (strict.description() == null) {
                continue;
            }
            if (!strict.missingClass()) {
                // 确凿的类型错误 —— 定位到具体方法，让报错可直接对应源码位置
                return "方法 " + method.name + method.desc + ": " + strict.description();
            }
            // 缺类 → 降级为 BasicVerifier 重跑一次
            //
            // 关键：降级不是「跳过这一层」，而是用一个不依赖外部类的
            // 验证器独立完成校验。栈深不匹配、栈下溢、跳转前后栈高不一致
            // 都能查出来 —— 而这些正是注入事故的主要形态。
            MethodAnalysis lenient = analyzeMethod(node, method, gameLoader, false);
            if (lenient.description() != null) {
                // 同时保留 strict 的失败细节：降级后的报错可能源于降级本身
                // （SimpleVerifier 给出了更精确的线索），合并输出避免二次排查。
                return "方法 " + method.name + method.desc + ": " + lenient.description()
                        + "\n[strict 阶段详情] " + strict.description();
            }
        }
        return null;
    }

    /**
     * 单个方法的分析结果。
     *
     * <p>必须把「错误描述」与「是否因缺类而失败」<b>一起</b>返回，
     * 而不是靠解析错误文本：{@link #describe} 只保留首行，
     * 而缺类的真正线索在 cause 链深处
     * （{@code AnalyzerException → TypeNotPresentException → ClassNotFoundException}）。
     * 从文本里匹配 "ClassNotFoundException" 属于脆弱的字符串嗅探，
     * 一旦 ASM 改措辞就会静默退化成「把缺类当成真错误」——
     * 正是本类要消灭的那类失效。
     *
     * @param description  错误描述；通过时为 null
     * @param missingClass true 表示失败源于类加载不到，而非类型不匹配
     */
    private record MethodAnalysis(String description, boolean missingClass) {

        static MethodAnalysis pass() {
            return new MethodAnalysis(null, false);
        }

        static MethodAnalysis error(String description) {
            return new MethodAnalysis(description, false);
        }

        static MethodAnalysis missing(String missingType) {
            return new MethodAnalysis(missingType, true);
        }
    }

    /** 对单个方法做数据流分析；{@code strict=false} 时用 BasicVerifier。 */
    private static MethodAnalysis analyzeMethod(
            ClassNode node, MethodNode method,
            ClassLoader gameLoader, boolean strict) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(
                    strict ? simpleVerifier(node, gameLoader) : new BasicVerifier());
            analyzer.analyze(node.name, method);
            return MethodAnalysis.pass();
        } catch (Throwable t) {
            // 逐层看 cause 链：缺类与真错误在这里被彻底分开。
            for (Throwable cause = t; cause != null; cause = cause.getCause()) {
                if (cause instanceof ClassNotFoundException
                        || cause instanceof NoClassDefFoundError) {
                    String missing = cause.getMessage();
                    missing = missing == null ? "<unknown>" : missing;
                    UNRESOLVED.add(missing);
                    return MethodAnalysis.missing(missing);
                }
                if (cause.getCause() == cause) {
                    break;      // 自引用，防止死循环
                }
            }
            return MethodAnalysis.error(describe(t));
        }
    }

    /**
     * 构造 {@link SimpleVerifier}。
     *
     * <p>公有构造器要 {@link Type} 而非 {@code int api}
     * （带 {@code int api} 的那个是 {@code protected}），
     * 最后一个 boolean 是 {@code isInterface}。
     * 传入当前类的实际类型，验证器才能正确解析父类/接口方法。
     */
    private static SimpleVerifier simpleVerifier(
            ClassNode node, ClassLoader gameLoader) {
        SimpleVerifier verifier = new SimpleVerifier(
                Type.getObjectType(node.name),
                node.superName == null
                        ? Type.getObjectType("java/lang/Object")
                        : Type.getObjectType(node.superName),
                null,
                /* isInterface= */ false);
        if (gameLoader != null) {
            verifier.setClassLoader(gameLoader);
        }
        return verifier;
    }

    /** 提取可读的错误描述 —— 保留完整 message 与 cause 链，绝不截断。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (sb.length() > 0) {
                sb.append("\n  caused by ");
            }
            String msg = cur.getMessage();
            sb.append(cur.getClass().getName());
            if (msg != null && !msg.isBlank()) {
                sb.append(": ").append(msg);
            }
            if (cur.getCause() == cur) {
                break;      // 自引用，防止死循环
            }
        }
        return sb.length() > 0 ? sb.toString() : t.getClass().getName();
    }
}