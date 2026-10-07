package org.loader.runtime.minecraft.block;

import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

/**
 * 从 Mod 自己的资源里<b>推断</b>方块属性。
 *
 * <h2>为什么由 loader 推断而不是让 Mod 声明</h2>
 *
 * <p>Mili 的 {@code BlockSpec} 目前没有属性 API；而 Mod 的 blockstate
 * JSON（{@code assets/&lt;ns&gt;/blockstates/&lt;path&gt;.json}）本身就精确
 * 描述了方块必须有哪些属性 —— {@code "age=0"} 到 {@code "age=6"} 的变体
 * 键只有在注册的方块真的带 {@code age: 0..6} 属性时才有意义。
 * 缺了属性，26.2 的 {@code BlockStateModelLoader} 对每个变体都报
 * {@code Missing model for variant}，方块在游戏里没有模型。
 *
 * <p>于是注册方块时读本 blockstate：把变体键拆成 {@code 属性=值} 对，
 * 所有值都是整数 → {@code IntegerProperty.create(name, min, max)}；
 * 值恰好是 {@code true/false} → {@code BooleanProperty.create(name)}；
 * 其他（枚举/多属性组合里出现非整非布尔的值）→ 跳过该属性并记录，
 * 保持与旧版本完全相同的行为（没有属性），绝不让推断失败升级成注册失败。
 *
 * <p><b>推断只增不减</b>：没有任何 blockstate 时返回空列表，生成的方块
 * 与不推断时一模一样。这是对既有 Mod 的纯兼容增强。
 */
public final class BlockStatePropertyInference {

    private static final String INTEGER_PROPERTY_CLASS =
            "net.minecraft.world.level.block.state.properties.IntegerProperty";
    private static final String BOOLEAN_PROPERTY_CLASS =
            "net.minecraft.world.level.block.state.properties.BooleanProperty";

    private static final Logger LOG =
            Logger.getLogger("Mili/BlockStateInference");

    private BlockStatePropertyInference() {
    }

    /**
     * 推断 {@code <namespace>:<blockPath>} 方块的属性。
     *
     * @param modJar    Mod 的 jar 文件；null 或读不到 blockstate 时返回空列表
     * @param namespace 方块 id 的命名空间（通常是 modId）
     * @param blockPath 方块 id 的路径部分（可含 {@code /}，如 {@code crops/amaranth}）
     * @return 原版 {@code Property} 实例列表（可能为空）；<b>绝不抛异常</b>
     */
    public static List<Object> inferProperties(Path modJar, String namespace, String blockPath) {
        if (modJar == null || namespace == null || blockPath == null) {
            return List.of();
        }
        String entry = "assets/" + namespace + "/blockstates/" + blockPath + ".json";
        try {
            String json;
            try (ZipFile zip = new ZipFile(modJar.toFile())) {
                var ze = zip.getEntry(entry);
                if (ze == null) {
                    return List.of();
                }
                json = new String(zip.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
            }

            Map<String, Set<String>> values = parseVariantProperties(json);
            if (values.isEmpty()) {
                return List.of();
            }

            List<Object> props = new ArrayList<>();
            List<String> created = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : new TreeMap<>(values).entrySet()) {
                Object p = createProperty(e.getKey(), e.getValue());
                if (p != null) {
                    props.add(p);
                    created.add(e.getKey());
                } else {
                    skipped.add(e.getKey());
                }
            }
            if (!props.isEmpty()) {
                LOG.info("inferred blockstate properties " + describe(created)
                        + " for " + namespace + ":" + blockPath
                        + " from " + modJar.getFileName()
                        + (skipped.isEmpty() ? "" : " (skipped unsupported: " + describe(skipped) + ")"));
            }
            return props;
        } catch (Throwable t) {
            // 推断是纯增强：任何失败（jar 读不了、JSON 怪异、原版类改名）
            // 都只降级为「没有属性」，绝不打断方块注册。
            LOG.log(Level.FINE, "blockstate property inference skipped for "
                    + namespace + ":" + blockPath, t);
            return List.of();
        }
    }

    /**
     * 从 blockstate JSON 的 {@code variants} 对象里提取 {@code 属性=值} 键。
     *
     * <p>手写扫描器而非引入 JSON 库：integration 模块编译期不依赖任何
     * JSON 实现，而变体键的形态极其简单 —— 深度为 1 的带引号字符串，
     * 形如 {@code "age=0"} 或多属性 {@code "facing=north,lit=true"}。
     * 模型路径等深度 ≥2 的字符串一律跳过。
     */
    static Map<String, Set<String>> parseVariantProperties(String json) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        int v = json.indexOf("\"variants\"");
        if (v < 0) {
            return out; // multipart 等：无法按变体键推断，保持无属性
        }
        int open = json.indexOf('{', v);
        if (open < 0) {
            return out;
        }
        int depth = 0;
        for (int p = open; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
                continue;
            }
            if (c == '"' && depth == 1) {
                int close = json.indexOf('"', p + 1);
                if (close < 0) {
                    break; // 引号不配对：JSON 损坏，返回已收集的部分
                }
                String key = json.substring(p + 1, close);
                p = close;
                collectKey(out, key);
            }
        }
        return out;
    }

    private static void collectKey(Map<String, Set<String>> out, String key) {
        if (key.indexOf('=') <= 0) {
            return;
        }
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.computeIfAbsent(part.substring(0, eq).trim(), k -> new TreeSet<>())
                    .add(part.substring(eq + 1).trim());
        }
    }

    /** 整数范围 → IntegerProperty；true/false → BooleanProperty；其余返回 null。 */
    private static Object createProperty(String name, Set<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        boolean allInt = true;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (String v : values) {
            try {
                int n = Integer.parseInt(v);
                min = Math.min(min, n);
                max = Math.max(max, n);
            } catch (NumberFormatException e) {
                allInt = false;
                break;
            }
        }
        try {
            if (allInt) {
                Class<?> cls = Reflect.gameClass(INTEGER_PROPERTY_CLASS);
                return cls.getMethod("create", String.class, int.class, int.class)
                        .invoke(null, name, min, max);
            }
            if (values.equals(Set.of("true", "false"))) {
                Class<?> cls = Reflect.gameClass(BOOLEAN_PROPERTY_CLASS);
                return cls.getMethod("create", String.class).invoke(null, name);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "cannot create property '" + name + "' with values " + values, e);
            return null;
        }
        // 枚举属性需要真实的 enum class，无法从字符串值合成 —— 明确跳过。
        LOG.warning("blockstate property '" + name + "' has non-numeric values " + values
                + "; only integer/boolean properties can be inferred — skipping."
                + " The block will be registered without this property.");
        return null;
    }

    private static String describe(List<String> names) {
        return String.join(", ", names);
    }
}
