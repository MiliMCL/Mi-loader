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
 * <p>手写解析只需要 {@code DataInputStream}，没有任何版本耦合 ——
 * 它只按 JVM 规范读常量池和方法表，对 class 版本完全不敏感。
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
 *   <li>{@code 2} —— 用法错误 / jar 无法读取</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>
 *   java SymbolVerifier &lt;MiliSymbol.java&gt; &lt;client.jar&gt; &lt;minecraftVersion&gt;
 * </pre>
 */
public final class SymbolVerifier {

    // ── 常量池 tag ─────────────────────────────────────────────────────────
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

    /** 方法表里的占位字节 —— 解析字段时需按类型大小跳过。 */
    private static final int CONSTANT_MethodPlaceholder = -2;

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
                    List<String> methods = readMethods(in);
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
            System.err.println("读取 Minecraft jar 失败: " + e.getMessage());
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
     */
    private static List<String> readMethods(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        int magic = in.readInt();
        if (magic != 0xCAFEBABE) {
            throw new IOException("不是合法的 class 文件");
        }
        in.readUnsignedShort();               // minor
        int major = in.readUnsignedShort();   // major

        int cpCount = in.readUnsignedShort();
        Object[] constantPool = new Object[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case CONSTANT_Utf8 -> constantPool[i] = in.readUTF();
                case CONSTANT_Integer, CONSTANT_Float -> in.readInt();
                case CONSTANT_Long, CONSTANT_Double -> {
                    in.readLong();
                    // long/double 占两个常量池槽位
                    i++;
                }
                case CONSTANT_Class, CONSTANT_String, CONSTANT_MethodType,
                     CONSTANT_Module, CONSTANT_Package -> in.readUnsignedShort();
                case CONSTANT_Fieldref, CONSTANT_Methodref,
                     CONSTANT_InterfaceMethodref, CONSTANT_NameAndType,
                     CONSTANT_Dynamic, CONSTANT_InvokeDynamic ->
                        in.readInt();
                case CONSTANT_MethodHandle -> in.readUnsignedByte();
                default -> throw new IOException(
                        "未知常量池 tag " + tag + "（class major=" + major + "）");
            }
        }

        in.readUnsignedShort();   // access_flags
        in.readUnsignedShort();   // this_class
        in.readUnsignedShort();   // super_class
        int interfaceCount = in.readUnsignedShort();
        skipFully(in, interfaceCount * 2);

        // 字段表：需按描述符跳过属性
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            in.readUnsignedShort();   // access
            int nameIndex = in.readUnsignedShort();
            int descIndex = in.readUnsignedShort();
            skipAttributes(in, utf8(constantPool, descIndex));
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
            methods.add(name + descriptor);
            skipAttributes(in, descriptor);
        }
        return methods;
    }

    /** 按描述符跳过一个成员的属性表。 */
    private static void skipAttributes(DataInputStream in, String descriptor)
            throws IOException {
        int attributeCount = in.readUnsignedShort();
        for (int i = 0; i < attributeCount; i++) {
            in.readUnsignedShort();              // attribute_name_index
            int length = in.readInt();
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