package org.loader.runtime.minecraft.client.input;

import org.loader.api.input.KeyBinding;
import org.loader.api.input.KeyEventType;
import org.loader.api.input.KeyBindingSpec;
import org.loader.runtime.minecraft.reflect.Reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 按键绑定分发器 —— 原版 {@code KeyMapping} 体系与 ABI 的桥。
 *
 * <h2>工作方式</h2>
 * <ol>
 *   <li>{@code register}：用反射创建原版 {@code KeyMapping}
 *       （其构造器会自动注册进 {@code KeyMapping.ALL}），并把
 *       {@code Options.keyMappings} 数组扩充 —— 绑定因此出现在
 *       原版控制设置里，可被玩家重新绑定；</li>
 *   <li>{@code tick}：每个客户端 tick 轮询一次 ——
 *       {@code consumeClick()} 的剩余计数逐个派发为 PRESS，
 *       {@code isDown()} 的下降沿派发为 RELEASE。</li>
 * </ol>
 *
 * <h2>全部反射的坐标依据（26.2 反编译源）</h2>
 * <ul>
 *   <li>{@code KeyMapping(String, InputConstants.Type, int, Category)} ——
 *       构造时自动 {@code ALL.put(name, this)}</li>
 *   <li>{@code KeyMapping.Category} 是 {@code record Category(Identifier id)}
 *       —— 可反射构造任意模组分类</li>
 *   <li>{@code Identifier.fromNamespaceAndPath(String, String)}</li>
 *   <li>{@code Options.keyMappings} —— public final 数组，
 *       {@code Field.set} + setAccessible 可整体替换</li>
 *   <li>{@code KeyMapping.ALL} —— private static final Map，
 *       注销时从中移除</li>
 * </ul>
 *
 * <h2>轮询挂在哪</h2>
 * {@code Minecraft#tick}（符号 {@code MiliSymbol.CLIENT_TICK}）的 HEAD，
 * 由 {@code MiliClientTickTransformer} 注入 —— 与世界是否加载无关，
 * 主菜单里也会轮询（但 PRESS 事件本身只在游戏内产生，原版语义）。
 */
public final class KeyBindingDispatch {

    private static final Logger LOG = Logger.getLogger("Mili/Input");

    private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
    private static final String MC = "net.minecraft.client.Minecraft";

    /** 一个已注册的绑定 —— 可变类：按下状态随条目本身，注销不会漂移。 */
    private static final class Entry {
        final KeyBindingSpec spec;
        final Consumer<KeyEventType> handler;
        final Object keyMapping;
        volatile boolean down;

        Entry(KeyBindingSpec spec, Consumer<KeyEventType> handler, Object keyMapping) {
            this.spec = spec;
            this.handler = handler;
            this.keyMapping = keyMapping;
        }

        // 访问器与字段同名：本类由 record 重构而来，调用点沿用了
        // record 的访问器风格。保留访问器让调用点不必改成字段引用
        // —— 两种风格混用是又一处「看起来都能跑」的漂移源头。
        KeyBindingSpec spec() {
            return spec;
        }

        Consumer<KeyEventType> handler() {
            return handler;
        }

        Object keyMapping() {
            return keyMapping;
        }
    }

    private static final List<Entry> bindings = new CopyOnWriteArrayList<>();

    private KeyBindingDispatch() {
    }

    // ── 注册（由 KeyBindingServiceBridge 调用） ─────────────────────────────

    /**
     * 注册绑定并接入原版控件界面。失败抛异常 —— 注册是 Mod 的
     * 主动调用，显式失败优于静默无效。
     */
    public static KeyBinding register(KeyBindingSpec spec,
                                      Consumer<KeyEventType> handler) {
        Object mapping;
        try {
            mapping = createKeyMapping(spec);
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "创建 KeyMapping 失败（id=" + spec.id() + "）—— "
                            + "符号漂移或键码非法: " + t, t);
        }
        Entry entry = new Entry(spec, handler, mapping);
        bindings.add(entry);
        appendToOptions();

        LOG.fine(() -> "[Mili] 按键绑定已注册: " + spec.id() + " -> " + spec.defaultKey());

        return new KeyBinding() {
            @Override
            public String id() {
                return spec.id();
            }

            @Override
            public void unregister() {
                bindings.remove(entry);
                try {
                    removeFromVanillaRegistry(spec.id());
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "[Mili] 注销按键绑定时移除原版注册失败", t);
                }
            }
        };
    }

    // ── 轮询（由生成字节码在 Minecraft#tick 头部调用，必须吞异常） ──────────

    /** 已执行的客户端 tick 数。启动看门狗以此判断「游戏主循环是否真的跑起来了」。 */
    private static final java.util.concurrent.atomic.AtomicLong tickCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** 已执行的客户端 tick 数；游戏主循环从未启动时为 0。 */
    public static long tickCount() {
        return tickCount.get();
    }

    /** 每客户端 tick 一次。描述符固定 {@code ()V}。 */
    public static void tick() {
        try {
            tickCount.incrementAndGet();
            for (Entry entry : bindings) {
                poll(entry);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[Mili] 按键轮询失败", t);
        }
    }

    private static void poll(Entry entry) throws Exception {
        Class<?> mappingClass = Reflect.gameClass(KEY_MAPPING);
        Method consumeClick = mappingClass.getMethod("consumeClick");
        Method isDown = mappingClass.getMethod("isDown");

        // PRESS：原版点击计数 —— 可能积压多次，逐个派发。
        while ((boolean) consumeClick.invoke(entry.keyMapping)) {
            fireEvent(entry, KeyEventType.PRESS);
        }

        // RELEASE：isDown 下降沿。状态随条目本身，注销不会错位。
        boolean down = (boolean) isDown.invoke(entry.keyMapping);
        if (!down && entry.down) {
            fireEvent(entry, KeyEventType.RELEASE);
        }
        entry.down = down;
    }

    private static void fireEvent(Entry entry, KeyEventType type) {
        try {
            entry.handler().accept(type);
        } catch (Throwable t) {
            // Mod 回调的异常不允许逃逸进 Minecraft#tick。
            LOG.log(Level.WARNING, "[Mili] 按键回调抛出异常: " + entry.spec().id(), t);
        }
    }

    // ── 原版体系接入 ─────────────────────────────────────────────────────────

    /**
     * 创建原版 KeyMapping：
     * {@code new KeyMapping(name, InputConstants.Type.KEYSYM, glfw, new Category(id))}
     *
     * <p>构造器自动注册进 {@code KeyMapping.ALL} 并建立扫描映射 ——
     * 不需要（也不应该）再手动 put。
     */
    private static Object createKeyMapping(KeyBindingSpec spec) throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        Class<?> mappingClass = Reflect.gameClass(KEY_MAPPING);
        Class<?> typeClass = Class.forName(
                "com.mojang.blaze3d.platform.InputConstants$Type", true, game);
        Object keysym = Enum.valueOf(
                typeClass.asSubclass(Enum.class), "KEYSYM");

        // Category 是 record(Identifier id) —— 反射构造模组专属分类。
        Class<?> categoryClass = Class.forName(KEY_MAPPING + "$Category", true, game);
        Class<?> identifierClass = Reflect.gameClass(
                "net.minecraft.resources.Identifier");
        String namespace = spec.id().substring(0, spec.id().indexOf(':'));
        String path = spec.id().substring(spec.id().indexOf(':') + 1);
        Object identifier = identifierClass
                .getMethod("fromNamespaceAndPath", String.class, String.class)
                .invoke(null, namespace, path);
        Object category = categoryClass
                .getConstructor(identifierClass)
                .newInstance(identifier);

        return mappingClass
                .getConstructor(String.class, typeClass, int.class, categoryClass)
                .newInstance(spec.id(), keysym, spec.glfwKeyCode(), category);
    }

    /**
     * 把绑定追加进 {@code Options.keyMappings}（出现在控制设置里）。
     *
     * <p>{@code keyMappings} 是 public final 数组 —— 引用本身可替换
     * （{@code Field.set} 对实例 final 字段有效）。替换出的新数组
     * 是「原数组 + 全部未收录绑定」；每次注册都重建，注销后下次
     * 注册时自然收窄。
     */
    static void appendToOptions() {
        try {
            Object mc = minecraftInstance();
            if (mc == null) {
                return;
            }
            Object options = Reflect.instanceField(mc, MC, "options");
            Class<?> mappingClass = Reflect.gameClass(KEY_MAPPING);
            Field field = options.getClass().getField("keyMappings");
            field.setAccessible(true);
            Object[] current = (Object[]) field.get(options);

            // 只追加未收录的（重复注册防御 + 注销后收窄）
            java.util.List<Object> merged = new java.util.ArrayList<>();
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Object m : current) {
                merged.add(m);
                Object name = mappingClass.getMethod("getName").invoke(m);
                present.add(String.valueOf(name));
            }
            for (Entry entry : bindings) {
                if (present.add(entry.spec().id())) {
                    merged.add(entry.keyMapping());
                }
            }
            field.set(options, merged.toArray(
                    (Object[]) java.lang.reflect.Array
                            .newInstance(mappingClass, 0)));
        } catch (Throwable t) {
            LOG.log(Level.WARNING,
                    "[Mili] 按键绑定写入控制界面失败（功能不受影响）", t);
        }
    }

    /** 从原版 {@code KeyMapping.ALL} 移除注销的绑定。 */
    private static void removeFromVanillaRegistry(String id) throws Exception {
        Class<?> mappingClass = Reflect.gameClass(KEY_MAPPING);
        Field allField = mappingClass.getDeclaredField("ALL");
        allField.setAccessible(true);
        Object all = allField.get(null);
        if (all instanceof Map<?, ?> map) {
            map.remove(id);
        }
    }

    private static Object minecraftInstance() throws Exception {
        ClassLoader game = Reflect.gameClassLoader();
        if (game == null) {
            return null;
        }
        return Reflect.gameClass(MC).getMethod("getInstance").invoke(null);
    }

    /** 已注册绑定数（诊断）。 */
    public static int bindingCount() {
        return bindings.size();
    }
}
