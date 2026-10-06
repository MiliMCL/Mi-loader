package org.loader.runtime.minecraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 回归：<b>任何触碰注册表类的地方，都必须先置 bootstrap 标志</b>。
 *
 * <h2>这个测试在防什么</h2>
 *
 * <p>26.2 的 {@code BuiltInRegistries.<clinit>} 会调
 * {@code Bootstrap.checkBootstrapCalled()}，要求
 * {@code isBootstrapped == true}，否则抛
 * {@code IllegalArgumentException: Not bootstrapped}。
 *
 * <p>致命之处在于 <b>JVM 对初始化失败的类不会重试</b>：该类被永久标记为
 * {@code Erroneous}，此后任何访问都直接抛
 * {@code NoClassDefFoundError: Could not initialize class ...}。
 *
 * <p>真实事故（用户实测）：
 * <pre>
 *   Mod.initialize() → registry().findBlock(...)   ← 纯只读意图
 *     → 首次触碰 BuiltInRegistries → 类初始化失败 → 毒化
 *   Mod 因为异常被catch 住，"成功" 打印出 Ready. 3 crops, 3 seed items.
 *   随后平台 openRegistryWindow() → NoClassDefFoundError
 *     → "refusing to start Minecraft without it"
 * </pre>
 *
 * <p>错误信息完全指不到「某个 Mod 早先只查了一下」这个真正原因，
 * 排查成本极高。所以这里用静态检查把规矩钉死：
 * <b>源码里每一处读 {@code BuiltInRegistries} 静态字段的地方，
 * 都必须先调用 {@code ensureRegistriesReadable()}。</b>
 */
class RegistryClassInitOrderTest {

    /** 所有会读注册表静态字段的位置所属的源文件目录。 */
    private static final String[] SOURCES = {
            "block/BlockRegistrar.java",
            "item/ItemRegistrar.java",
            "world/ReflectiveWorldView.java",
            "RegistrationPhase.java",
    };

    /**
     * 找出所有「读 BuiltInRegistries 静态字段」的代码位置。
     *
     * <p>匹配的是 {@code Reflect.staticField(...)} 且参数里出现
     * {@code BUILTIN} —— 这正是所有注册表读取的共同形态。
     */
    private static void assertNoUnguardedRegistryAccess(String sourcePath) throws Exception {
        String src = readSource(sourcePath);

        // 逐行扫描：每个触碰点向前看一小段窗口，
        // 必须能找到 ensureRegistriesReadable() 或它所在方法的调用。
        //
        // 窗口取 8 行而不是 1 行：保护调用与触碰点之间常夹着解释性注释
        // （说明为什么要先置标志），只看紧邻上一行会误报。
        String[] lines = src.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.contains("Reflect.staticField(")) {
                continue;
            }
            boolean guarded = false;
            for (int back = 0; back <= 8 && i - back >= 0; back++) {
                if (lines[i - back].contains("ensureRegistriesReadable()")) {
                    guarded = true;
                    break;
                }
            }
            // openRegistryWindow 内部的触碰本身就是「置位+触碰」，
            // 由 flagSet 的 CAS 保证只执行一次，不需要再调自己。
            if (!guarded && inOpenRegistryWindow(lines, i)) {
                guarded = true;
            }

            if (!guarded) {
                throw new AssertionError(String.format(
                        "%s:%d 直接触碰了注册表静态字段却没有先调用 "
                                + "ensureRegistriesReadable():%n    %s%n%n"
                                + "这会让该处成为「首次失败」的一环，类初始化失败不可重试，"
                                + "之后平台开窗必然抛 NoClassDefFoundError。",
                        sourcePath, i + 1, line));
            }
        }
    }

    /** 触碰点是否位于 {@code openRegistryWindow()} 内部（那是合法的一处）。 */
    private static boolean inOpenRegistryWindow(String[] lines, int index) {
        for (int i = index; i >= 0 && i > index - 60; i--) {
            if (lines[i].contains("public static void openRegistryWindow()")) {
                return true;
            }
            if (lines[i].contains("public static void closeRegistryWindow()")) {
                return false;
            }
        }
        return false;
    }

    private static String readSource(String relative) throws Exception {
        // 测试运行时 CWD 是模块目录（mili-minecraft-integration/）
        java.nio.file.Path p = java.nio.file.Path.of("src/main/java/org/loader/runtime/minecraft",
                relative);
        if (!java.nio.file.Files.exists(p)) {
            // IDE 可能把 CWD 设在仓库根
            p = java.nio.file.Path.of("mili-minecraft-integration",
                    "src/main/java/org/loader/runtime/minecraft").resolve(relative);
        }
        if (!java.nio.file.Files.exists(p)) {
            throw new AssertionError("找不到源文件: " + relative
                    + "（CWD=" + System.getProperty("user.dir") + "）");
        }
        return java.nio.file.Files.readString(p);
    }

    @Test
    @DisplayName("BlockRegistrar：读注册表前先置bootstrap 标志")
    void blockRegistrarIsGuarded() throws Exception {
        assertNoUnguardedRegistryAccess("block/BlockRegistrar.java");
    }

    @Test
    @DisplayName("ItemRegistrar：读注册表前先置 bootstrap 标志")
    void itemRegistrarIsGuarded() throws Exception {
        assertNoUnguardedRegistryAccess("item/ItemRegistrar.java");
    }

    @Test
    @DisplayName("ReflectiveWorldView：读注册表前先置 bootstrap 标志")
    void worldViewIsGuarded() throws Exception {
        assertNoUnguardedRegistryAccess("world/ReflectiveWorldView.java");
    }

    @Test
    @DisplayName("RegistrationPhase：注册表触碰点受保护")
    void registrationPhaseIsGuarded() throws Exception {
        assertNoUnguardedRegistryAccess("RegistrationPhase.java");
    }

    @Test
    @DisplayName("ensureRegistriesReadable() 已存在且为 public static")
    void safeEntryPointExists() throws Exception {
        Class<?> phase = Class.forName(
                "org.loader.runtime.minecraft.RegistrationPhase");
        Method m = phase.getMethod("ensureRegistriesReadable");
        assertTrue(Modifier.isPublic(m.getModifiers()),
                "ensureRegistriesReadable 必须是 public —— 各 registrar 与世界查询都要用它");
        assertTrue(Modifier.isStatic(m.getModifiers()),
                "必须是 static —— 状态由 GameSessionState 按实例管理");
    }

    @Test
    @DisplayName("openRegistryWindow 仍会在触碰注册表前先置标志")
    void openRegistryWindowSetsFlagBeforeTouchingRegistry() throws Exception {
        // 这是唯一合法的「先置位、后触碰」顺序，必须由源码顺序保证：
        // 标志置位（flagSet CAS + 写 Bootstrap.isBootstrapped）
        // 必须出现在 staticField(BUILTIN, ...) 之前。
        String src = readSource("RegistrationPhase.java");
        int openIdx = src.indexOf("public static void openRegistryWindow()");
        assertTrue(openIdx >= 0, "找不到 openRegistryWindow()");

        int body = src.indexOf('{', openIdx);
        // 该方法体内的两处标志
        int flagWrite = src.indexOf("flag.setBoolean(null, true)", body);
        int registryTouch = src.indexOf("staticField(BUILTIN", body);
        // closeRegistryWindow 是下一个方法，搜索范围不能越过它
        int closeIdx = src.indexOf("public static void closeRegistryWindow()", body);
        assertTrue(flagWrite >= 0 && flagWrite < closeIdx,
                "openRegistryWindow 内应写入 Bootstrap.isBootstrapped");
        assertTrue(registryTouch >= 0 && registryTouch < closeIdx,
                "openRegistryWindow 内应触碰注册表类以完成初始化");
        assertTrue(flagWrite < registryTouch,
                "顺序必须是「先置 Bootstrap.isBootstrapped，再触碰 BuiltInRegistries」——"
                        + "反过来 <clinit> 会抛 Not bootstrapped，并把该类永久标记为"
                        + " Erroneous（初始化失败不可重试），"
                        + "表现为后续 NoClassDefFoundError: Could not initialize class。"
                        + "当前 flagWrite=" + flagWrite + ", registryTouch=" + registryTouch);
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
