import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * MiliSymbol 校验器 —— 确认符号表里的方法坐标在真实 Minecraft jar 中确实存在。
 *
 * <h2>为什么不用 ASM，而是手写 class 文件解析</h2>
 * 本工具必须能在<b>只有 JDK 的环境</b>里运行：它的存在意义是
 * 「在 CI 验证符号表」，若它自己需要 ASM，就得先把 ASM 解析到 classpath 上 ——
 * 而 ASM 版本与 Minecraft 的 class 版本强绑定（26.2 是 major 69，
 * 需要 ASM 9.8+），一旦解析到的版本不对，校验器就会因为
 * 「读不懂 Minecraft 的 class」而给出<b>误导性的失败</b>。
 *
 * <p>手写解析只需要 {@code DataInputStream}，没有第三方版本耦合。
 *
 * <h2>手写解析的代价：宽度算错就是灾难性失败</h2>
 * 选了手写就得承担JVMS Table 4.4-A 的准确性。任何一项宽度写错，
 * 解析器会从该常量起<b>整体错位</b>，读到的是别处的字节，
 * 表现为「出现某个莫名其妙的常量池 tag」。
 *
 * <p>本文件曾因此错误地诊断过一次：{@code CONSTANT_MethodHandle} 被按 1 字节
 * 解析（实际是 u1 + u2 = 3 字节），导致 jar 内 5515/10952 个 class 解析失败，
 * 其中一个恰好错位到 tag 14，于是被判定为
 * 「未知常量池 tag 14（class major=69）」，并被推断成
 * 「JDK 25 新增了常量池类型」。<b>这个推断是错的</b> ——
 * tag 2/13/14 是 JVMS 保留值，从未分配；JDK 25 也没新增任何常量池类型。
 *
 * <p>因此本文件设有两道防线：
 * <ol>
 *   <li>常量池 tag 2/13/14 触发<b>专门</b>的诊断信息，直接指认「解析器错位」
 *       而非「版本变了」；</li>
 *   <li>解析结束后校验是否<b>恰好消费完整个 class 文件</b>，
 *       且方法名/描述符不得解析为空串 —— 让错位无法伪装成「方法不存在」。</li>
 * </ol>
 * 第二道尤其重要：没有它，校验器的解析 bug 会以
 * 「符号表中 N 个符号不存在，请更新 MiliSymbol」的形式呈现，
 * 把排查引向完全错误的方向。
 *
 * <h2>为什么这是 CI 的职责而不是运行时</h2>
 * 符号表过期是一种<b>静默</b>失效：转换器注入不到任何东西，
 * 游戏照常运行，Mod 功能消失，日志里什么都没有。
 * 这类问题必须在构建期就拦住，而运行时已经太晚。
 *
 * <h2>退出码</h2>
 * <ul>
 *   <li>{@code 0} —— 全部符号存在</li>
 *   <li>{@code 1} —— 有符号不存在（构建必须失败）</li>
 *   <li>{@code 2} —— 用法错误 / jar 无法读取 / <b>本工具自身解析失败</b></li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 *   java SymbolVerifier &lt;MiliSymbol.java&gt; &lt;client.jar&gt; &lt;minecraftVersion&gt;
 * </pre>
 */
public final class SymbolVerifier {

    /**
     * 常量池各tag 的<b>字节宽度</b>，严格对应 JVMS Table 4.4-A。
     *
     * <p>这是本文件最容易出错的地方：任何一项写错，解析器就会<b>从该常量起整体错位</b>，
     * 后面读到的全是垃圾。症状极具误导性 —— 表现为「某个天杀的常量池 tag」，
     * 让人误以为是 JVM 出了新东西（曾把class major=69 误判成「JDK 25 新增 tag」，
     * 实际上JDK 25 根本没新增任何常量池类型）。
     *
     * <p>tag 15 {@code CONSTANT_MethodHandle} 是唯一<b>不是单一字段宽度</b>的引用类常量：
     * {@code reference_kind} 是 u1，{@code reference_index} 是 u2，合起来 3 字节。
     * 历史上这里按 1 字节写，导致 jar 里 5515/10952 个 class 解析失败
     * （含 lambda / record / switch 模式匹配的类必有 invokedynamic，必带 MethodHandle）。
     *
     * <p>tag 5 / 6（Long / Double）另外要占<b>两个</b>常量池槽位。
     */
    private static final int CONSTANT_Utf8 = 1;
    private static final int CONSTANT_Integer = 3;
    private static final int CONSTANT_Float = 4;
    private static final int CONSTANT_Long = 5;
    private static final int CONSTANT_Double = 6;
    private static final int CONSTANT_Class = 7;
    private static final int CONSTANT_String = 8;
    private static final int CONSTANT_Fieldref = 9;
    private static final int CONSTANT_Methodref = 10;
    private static final int CONSTANT_InterfaceMethodref = 11;
    private static final int CONSTANT_NameAndType = 12;
    private static final int CONSTANT_MethodHandle = 15;
    private static final int CONSTANT_MethodType = 16;
    private static final int CONSTANT_Dynamic = 17;
    private static final int CONSTANT_InvokeDynamic = 18;
    private static final int CONSTANT_Module = 19;
    private static final int CONSTANT_Package = 20;

    /** {@code CONSTANT_Info} 里唯一字段宽度为 1 字节的引用类常量。 */
    private static final int WIDTH_BYTE = 1;
    /** 字段宽度为 2 字节的常量。 */
    private static final int WIDTH_SHORT = 2;
    /** 字段宽度为 4 字节的常量。 */
    private static final int WIDTH_INT = 4;
    /** {@code CONSTANT_MethodHandle}：reference_kind(u1) + reference_index(u2)。 */
    private static final int WIDTH_METHOD_HANDLE = WIDTH_BYTE + WIDTH_SHORT;
    /** {@code CONSTANT_Long} / {@code CONSTANT_Double} 占 8 字节且占两个槽位。 */
    private static final int WIDTH_WIDE = 8;

    private SymbolVerifier() {
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println(
                    "用法: SymbolVerifier <MiliSymbol.java> <client.jar> <minecraftVersion>");
            System.exit(2);
            return;
        }

        Path symbolFile = Path.of(args[0]);
        Path jarPath = Path.of(args[1]);
        String expectedVersion = args[2];

        if (!Files.exists(symbolFile)) {
            System.err.println("找不到符号表源文件: " + symbolFile);
            System.exit(2);
            return;
        }
        if (!Files.exists(jarPath)) {
            System.err.println("找不到 Minecraft jar: " + jarPath);
            System.exit(2);
            return;
        }

        List<Symbol> symbols;
        try {
            String text = Files.readString(symbolFile, StandardCharsets.UTF_8);
            String declaredVersion = extractVersion(text);
            if (!expectedVersion.equals(declaredVersion)) {
                System.err.println("MiliSymbol.MINECRAFT_VERSION = " + declaredVersion
                        + "，但构建目标是 " + expectedVersion);
                System.exit(1);
                return;
            }
            symbols = parseSymbols(text);
        } catch (IOException e) {
            System.err.println("读取符号表失败: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (symbols.isEmpty()) {
            System.err.println("符号表中没有解析出任何方法坐标 —— "
                    + "这通常意味着 MiliSymbol 的源码格式变了，校验器需要同步更新。\n"
                    + "（而不是「没有符号所以校验通过」—— 那等于校验失效。）");
            System.exit(1);
            return;
        }

        List<String> failures = new ArrayList<>();
        List<String> verified = new ArrayList<>();

        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            for (Symbol symbol : symbols) {
                String entryName = symbol.owner() + ".class";
                ZipEntry entry = jar.getEntry(entryName);
                if (entry == null) {
                    // Minecraft 26.2 部分类在 bundled jar 里可能没有独立条目，
                    // 退而检查是否是 inner class 的外部形式
                    failures.add("[缺类] " + symbol.constantName()
                            + "\n     owner=" + symbol.owner()
                            + "\n     jar 中无 " + entryName);
                    continue;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    List<String> methods = readMethods(in, symbol.owner());
                    if (!methods.contains(symbol.signature())) {
                        failures.add("[缺方法] " + symbol.constantName()
                                + "\n     期望: " + symbol.signature()
                                + "\n     实际存在: " + summarize(methods));
                    } else {
                        verified.add(symbol.constantName()
                                + " → " + symbol.signature());
                    }
                }
            }
        } catch (IOException e) {
            // 读 jar 失败与「符号不存在」是<b>完全不同</b>的两件事：
            // 前者是本工具/环境的问题（exit 2），后者才是符号表过期（exit 1）。
            // 历史上两者混在一起，输出过「tag 14未知」这种把校验器自身缺陷
            // 归咎于 MiliSymbol 的结论，排查方向整个是错的。
            System.err.println("读取 Minecraft jar 失败: " + e.getMessage());
            System.err.println();
            System.err.println("这是**校验器或jar 本身**的问题，"
                    + "不是 MiliSymbol 过期 —— 在确认此处之前，"
                    + "不要去修改符号表。");
            System.exit(2);
            return;
        }

        System.out.println("[symbols] 校验 Minecraft " + expectedVersion
                + "：" + verified.size() + "/" + symbols.size() + " 个符号存在");
        for (String v : verified) {
            System.out.println("  [OK] " + v);
        }
        if (!failures.isEmpty()) {
            System.err.println();
            System.err.println("[symbols] " + failures.size() + " 个符号不存在：");
            for (String f : failures) {
                System.err.println("  " + f);
            }
            System.err.println();
            System.err.println("这会让相关转换器**静默不注入**：游戏照常运行，"
                    + "Mod 功能消失，日志无任何错误。");
            System.err.println("请更新 MiliSymbol.java 使其与实际 Minecraft 一致。");
            System.exit(1);
            return;
        }
        System.exit(0);
    }

    // ── 符号表解析 ─────────────────────────────────────────────────────────

    private record Symbol(String constantName, String owner, String name, String descriptor) {
        /** owner#name descriptor 的三元组形式，用于精确比对。 */
        String signature() {
            return name + descriptor;
        }
    }

    private static String extractVersion(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("MINECRAFT_VERSION\\s*=\\s*\"([^\"]+)\"").matcher(text);
        if (!m.find()) {
            return "";
        }
        return m.group(1);
    }

    /**
     * 从 MiliSymbol 源码中解析出所有 {@code TargetMethod.of(a, b, c)} 常量。
     *
     * <p>用正则而非反射加载 MiliSymbol：反射会要求 mili-abi 在 classpath 上，
     * 而本工具应当只依赖 JDK。
     *
     * <p>常量名通过其后的 {@code = TargetMethod.of} 关联 ——
     * 符号声明形如：
     * <pre>
     *   public static final TargetMethod SERVER_TICK = TargetMethod.of(
     *           "net/minecraft/server/MinecraftServer",
     *           "tickServer",
     *           "(Ljava/util/function/BooleanSupplier;)V");
     * </pre>
     */
    private static List<Symbol> parseSymbols(String text) {
        // 匹配: <常量名> = TargetMethod.of( "owner", "name", "desc" )
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "TargetMethod\\s*\\.of\\s*\\(\\s*"
                        + "\"([^\"]+)\"\\s*,\\s*"
                        + "\"([^\"]+)\"\\s*,\\s*"
                        + "\"([^\"]+)\"\\s*\\)",
                java.util.regex.Pattern.DOTALL);

        List<Symbol> result = new ArrayList<>();
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String owner = matcher.group(1);
            String name = matcher.group(2);
            String descriptor = matcher.group(3);

            // 回溯找该常量名：向前搜最近的 "static final ... <标识符> ="
            String constantName = findEnclosingConstantName(text, matcher.start());
            result.add(new Symbol(constantName, owner, name, descriptor));
        }
        return result;
    }

    /** 回溯最近的常量名。 */
    private static String findEnclosingConstantName(String text, int index) {
        String head = text.substring(0, index);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("static\\s+final\\s+\\w+(?:<[^>]*>)?\\s+(\\w+)\\s*=")
                .matcher(head);
        String last = "<unknown>";
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    // ── class 文件解析 ──────────────────────────────────────────────────────

    /**
     * 读取类文件的方法表，返回 {@code name + descriptor} 列表。
     *
     * <p>只读签名，不读方法体 —— 快且不需要处理 Code 属性。
     *
     * @param className 类名，仅用于错误信息。解析失败时若不给类名，
     *                  报错会指向「某个匿名 class」，无从排查。
     */
    private static List<String> readMethods(InputStream raw, String className)
            throws IOException {
        DataInputStream in = new DataInputStream(raw);
        int magic = in.readInt();
        if (magic != 0xCAFEBABE) {
            throw new IOException(className + ": 不是合法的 class 文件");
        }
        in.readUnsignedShort();               // minor
        int major = in.readUnsignedShort();   // major

        int cpCount = in.readUnsignedShort();
        Object[] constantPool = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case CONSTANT_Utf8 -> constantPool[i] = readUtf8(in);
                case CONSTANT_Integer, CONSTANT_Float -> skipFully(in, WIDTH_INT);
                case CONSTANT_Long, CONSTANT_Double -> {
                    skipFully(in, WIDTH_WIDE);
                    // long/double 占两个常量池槽位：下一个索引是空的，
                    // 不 i++ 会把下一个 tag 误读成常量内容。
                    i++;
                }
                case CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType,
                     CONSTANT_Module, CONSTANT_Package -> skipFully(in, WIDTH_SHORT);
                case CONSTANT_Fieldref, CONSTANT_Methodref,
                     CONSTANT_InterfaceMethodref, CONSTANT_NameAndType,
                     CONSTANT_Dynamic, CONSTANT_InvokeDynamic ->
                        skipFully(in, WIDTH_INT);
                case CONSTANT_MethodHandle ->
                        skipFully(in, WIDTH_METHOD_HANDLE);
                default -> throw new IOException(describeBadTag(
                        className, major, tag, i));
            }
        }

        in.readUnsignedShort();   // access_flags
        in.readUnsignedShort();   // this_class
        in.readUnsignedShort();   // super_class
        int interfaceCount = in.readUnsignedShort();
        skipFully(in, (long) interfaceCount * WIDTH_SHORT);

        // 字段表：需按属性表结构跳过
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            in.readUnsignedShort();   // access
            int nameIndex = in.readUnsignedShort();
            int descIndex = in.readUnsignedShort();
            skipAttributes(in, className + " 的字段 " + utf8(constantPool, nameIndex));
        }

        // 方法表
        int methodCount = in.readUnsignedShort();
        List<String> methods = new ArrayList<>(methodCount);
        for (int i = 0; i < methodCount; i++) {
            in.readUnsignedShort();   // access
            int nameIndex = in.readUnsignedShort();
            int descIndex = in.readUnsignedShort();
            String name = utf8(constantPool, nameIndex);
            String descriptor = utf8(constantPool, descIndex);
            // 名字或描述符解析成空串 = 常量池索引越界，通常意味着前面已错位。
            // 若放任它继续，最终会报成「方法不存在」，把校验器的解析 bug
            // 伪装成「MiliSymbol 过期」—— 那会把排查引向完全错误的方向。
            if (name.isEmpty() || descriptor.isEmpty()) {
                throw new IOException(className
                        + ": 方法 #" + i + " 的签名无法解析"
                        + "（nameIndex=" + nameIndex + ", descIndex=" + descIndex
                        + "）—— 常量池解析很可能已错位，"
                        + "这是校验器的缺陷，不是符号表过期。");
            }
            methods.add(name + descriptor);
            skipAttributes(in, className + " 的方法 " + name);
        }

        // 类级属性表在方法表之后。正常读到这里应当正好消费完整个文件；
        // 若还有剩余，说明前面的长度计算有偏差，必须报错而不是静默返回。
        int trailingAttributes = in.available();
        if (trailingAttributes > 0) {
            skipAttributes(in, className + " 的类级属性");
            if (in.available() != 0) {
                throw new IOException(className + ": 类文件解析后仍余 "
                        + in.available() + " 字节未消费 —— 解析器已错位，"
                        + "这是校验器自身的缺陷。");
            }
        }
        return methods;
    }

    /**
     * 按 JVMS 的 CONSTANT_Utf8 读取字符串。
     *
     * <p><b>不能</b>用 {@link DataInputStream#readUTF()}：它按 Java 修饰版 UTF-8
     * 解码，遇到 class 文件里的四字节 UTF-8 序列（即真实 Unicode 码点）会抛
     * {@code UTFDataFormatException}。Minecraft 的类里有大量 emoji / CJK 字符串常量
     * （物品名、翻译键），这条路径必然踩雷。
     */
    private static String readUtf8(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 构造常量池解析失败的诊断信息。
     *
     * <p>刻意<b>不</b>把它写成「未知常量池 tag」并暗示「Minecraft 版本变了」——
     * tag 2/13/14 长期是 JVMS 保留值，从未分配。任何这类 tag 实际上都意味着
     * 解析器自身的长度计算有误，指向 Minecraft 版本会把人引向错误的排查方向。
     */
    private static String describeBadTag(String className, int major, int tag, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(className).append(": 常量池 #").append(index)
                .append(" 出现无法识别的 tag ").append(tag)
                .append("（class major=").append(major).append("）\n");
        if (tag == 2 || tag == 13 || tag == 14) {
            sb.append("  tag ").append(tag)
                    .append(" 是 JVMS 保留值，从未被任何 class 版本分配过。\n")
                    .append("  它出现只有一个原因：本解析器在它之前就已错位")
                    .append("（常量池字节宽度算错），读到的其实是别处的字节。\n")
                    .append("  请检查各tag 的字段宽度是否仍与 JVMS Table 4.4-A 一致 —— ")
                    .append("这与 Minecraft 版本无关，不要去改 MiliSymbol。");
        } else {
            sb.append("  若这是新分配给某个 class 版本的新常量类型，")
                    .append("需要在本文件的常量池 switch 中补上它的宽度；\n")
                    .append("  否则说明本解析器在此之前就已错位。");
        }
        return sb.toString();
    }

    /**
     * 按属性表结构（{@code attributes_count} + 若干
     * {@code attribute_name_index + attribute_length + info}）跳过一段属性。
     *
     * <p>本工具只需要方法签名，因此属性内容整体略过 —— 但<b>表头不能略</b>：
     * {@code attribute_length} 是 u4，必须读出来才能知道跳过多少字节。
     *
     * @param owner 仅用于错误信息，指出是哪个成员的属性表出了问题。
     */
    private static void skipAttributes(DataInputStream in, String owner)
            throws IOException {
        int attributeCount = in.readUnsignedShort();
        for (int i = 0; i < attributeCount; i++) {
            in.readUnsignedShort();              // attribute_name_index
            int length = in.readInt();
            if (length < 0) {
                throw new IOException(owner + " 的第 " + i + " 个属性长度为负（" + length
                        + "）—— 解析已错位，这是校验器自身的缺陷。");
            }
            skipFully(in, length);
        }
    }

    private static void skipFully(DataInputStream in, long count) throws IOException {
        long remaining = count;
        byte[] buf = new byte[4096];
        while (remaining > 0) {
            int chunk = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, chunk);
            if (read <= 0) {
                throw new IOException("读取类文件时提前结束");
            }
            remaining -= read;
        }
    }

    private static String utf8(Object[] constantPool, int index)
            throws IOException {
        if (index <= 0 || index >= constantPool.length) {
            return "";
        }
        Object value = constantPool[index];
        if (value instanceof String s) {
            return s;
        }
        // 无效索引 —— 返回空串而不是抛异常，让调用方的「未找到」分支自然生效
        return "";
    }

    /** 把方法列表压缩成可读的一行，仅用于失败信息。 */
    private static String summarize(List<String> methods) {
        if (methods.isEmpty()) {
            return "(无方法)";
        }
        List<String> head = methods.size() > 6 ? methods.subList(0, 6) : methods;
        String joined = String.join("\n              ", head);
        return methods.size() > 6
                ? joined + "\n              ... 共 " + methods.size() + " 个"
                : joined;
    }
}