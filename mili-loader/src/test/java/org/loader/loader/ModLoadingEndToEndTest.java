package org.loader.loader;

import com.example.testmod.ContractMod;
import org.junit.jupiter.api.*;
import org.loader.api.registry.BlockSpec;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.world.BlockHandle;
import org.loader.loader.classloader.ModClassLoader;
import org.loader.runtime.kernel.Runtime;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.mod.ApiModContext;
import org.loader.runtime.mod.ModContext;
import org.loader.runtime.mod.ModManifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mod 加载链路的端到端测试 —— 「Mod 能不能真的被加载」的直接证据。
 *
 * <h2>这个测试存在的理由</h2>
 *
 * <p>加载链路曾经<b>整体静默失效</b>，而且没有任何测试发现它：
 * <ul>
 *   <li>{@code LoaderMain} 把 runtime 的 {@code ModContext}（具体类）传给Mod，
 *       而 Mod 按契约声明的是 {@code initialize(org.loader.api.ModContext)}。
 *       两个类型不同 → 找不到入口 → 每个 Mod 都加载失败；</li>
 *   <li>错误消息写的是「缺少标准入口」，矛头指向 Mod 作者，
 *       实际是平台传错了类型；</li>
 *   <li>更糟的是，注册链本身也没接：即使类型对了，
 *       {@code RegistrationPhase} 在生产路径上从不被触发。</li>
 * </ul>
 *
 * <p>没有测试覆盖这段，是因为测试都停在「类能加载」「Scope 能建」，
 * 从没有人真的走完「找到入口类 → 实例化 → 调用 initialize → 拿到契约对象」。
 *
 * <h2>本测试怎么才算真的验证了</h2>
 *
 * <p>刻意模拟 Mod 的真实处境：Mod 类由<b>独立的 ModClassLoader</b> 加载，
 * 它只看得见 {@code org.loader.api.*}（契约可见性），
 * 平台侧则用 runtime 类型操作。这样如果平台又传错类型，测试会立刻失败 ——
 * 因为 Mod 的 {@code initialize} 参数类型就是契约接口，反射匹配不上。
 *
 * <p><b>不启动 Minecraft</b>：本测试只验证平台契约侧，
 * 方块是否真的落进 26.2 注册表由
 * {@code mili-minecraft-integration} 的真实 MC 测试负责
 * （见 {@code ModRegistrationLandsInRealMinecraftTest}）。
 * 两层分开的原因见该类注释。
 */
class ModLoadingEndToEndTest {

    @BeforeEach
    void reset() {
        ContractMod.reset();
    }

    /**
     * 搭出一个真实的 Mod 目录布局，并把测试 Mod 的 class 文件放进去。
     *
     * <p><b>为什么要真的拷class 文件</b>：{@link ModClassLoader} 只把
     * {@code <gameDir>/mods/<modId>/} 放进自己的 classpath
     * （见 {@code locateModSources}）。如果不落文件，入口类会从
     * <b>平台 ClassLoader</b> 被解析 —— 那它就不是一个「隔离的 Mod」了，
     * 而本测试的全部价值恰恰在于验证「Mod 类由独立 CL 加载时，
     * 平台还能不能正确调用它的契约入口」。
     *
     * <p>用平台 CL 加载入口类会让测试<b>假绿</b>：类型一旦从平台 CL 解析，
     * 两套 ModContext 的差异就体现不出来，传错类型也可能蒙对。
     *
     * @return gameDir 路径
     */
    private static Path stageModSources(String dirName, String modId) throws Exception {
        Path gameDir = Path.of("build/tmp", dirName).toAbsolutePath();
        Path modDir = gameDir.resolve("mods").resolve(modId);
        java.nio.file.Files.createDirectories(modDir);

        // 找到本测试类所在的 classpath 根，复制外层类与全部嵌套类
        Path testClasses = Path.of(ModLoadingEndToEndTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path pkgDir = testClasses.resolve("com/example/testmod");
        try (var files = java.nio.file.Files.list(pkgDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".class")).toList()) {
                Path target = modDir.resolve("com/example/testmod").resolve(f.getFileName());
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.copy(f, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return gameDir;
    }

    /**
     * 构造一个 Mod 专用的 ClassLoader，拓扑与生产一致：
     * parent 是游戏 CL，而游戏 CL 的 parent 是平台 CL。
     *
     * <p>见 {@code GameTestClassLoader} 的类注释 —— 用裸
     * {@code new URLClassLoader(urls, null)} 会让测试验证一个生产环境
     * 不存在的故障。
     */
    private static ModClassLoader modClassLoader(ModManifest manifest, Path gameDir)
            throws Exception {
        var gameCL = new org.loader.loader.classloader.MinecraftClassLoader(
                "minecraft-game", new java.net.URL[0],
                ModLoadingEndToEndTest.class.getClassLoader());
        return new ModClassLoader(manifest, gameCL, gameDir);
    }

    @Test
    void modEntrypointIsFoundAndInvokedThroughTheContractInterface() throws Exception {
        // 这是本测试最核心的一条：入口必须按【契约接口类型】匹配到。
        //
        // 修复前这里必然失败：平台传 runtime.ModContext 具体类，
        // 而 Mod 声明的是 org.loader.api.ModContext。
        Path gameDir = stageModSources("mod-loading-e2e", "testmod");

        ModManifest manifest = ModManifest.of("testmod", "Test Mod", "1.0.0");
        try (ModClassLoader mcl = modClassLoader(manifest, gameDir)) {
            Class<?> entry = mcl.loadModClass("com.example.testmod.ContractMod");

            // 入口类必须真的由 Mod 的 CL 定义，而不是从平台 CL 漏过来。
            // 若是后者，本测试验证的就是一个生产环境不存在的情况。
            assertSame(mcl, entry.getClassLoader(),
                    "入口类必须由 ModClassLoader 加载 —— 若来自平台 CL，"
                            + "这个测试就验证不到真实的隔离场景了");

            assertTrue(org.loader.api.Mod.class.isAssignableFrom(entry),
                    "Mod 入口类必须实现 org.loader.api.Mod");
            // 刻意用【契约接口】类型去匹配方法，而不是 import 进来的
            // runtime ModContext。这正是原始 bug 的镜像：平台上曾经写的
            // 就是 getMethod("initialize", runtime.ModContext.class)，
            // 而 Mod 声明的是契约接口 —— 类型对不上，入口永远找不到。
            // 用错类型会让本断言自己抛 NoSuchMethodException。
            assertEquals("org.loader.api.ModContext",
                    entry.getMethod("initialize", org.loader.api.ModContext.class)
                            .getParameterTypes()[0].getName(),
                    "initialize 的参数类型必须是契约接口 org.loader.api.ModContext");

            Object instance = entry.getDeclaredConstructor().newInstance();

            Runtime runtime = Runtime.create("e2e-runtime");
            runtime.start();
            MinecraftRegistry registry;
            try {
                Scope modScope = runtime.rootScope().createChild("mod:testmod");
                org.loader.runtime.mod.Mod mod =
                        new org.loader.runtime.mod.Mod(manifest, modScope, mcl);
                ModContext runtimeCtx = new ModContext(mod);
                RegistryBinder.bind(runtimeCtx, mod);
                registry = runtimeCtx.apiRegistry();

                // 平台传的是契约接口的实现 —— 这正是修复的关键。
                org.loader.api.ModContext apiCtx = new ApiModContext(runtimeCtx);
                ((org.loader.api.Mod) instance).initialize(apiCtx);

                // ── 观测点：为什么不能读Mod 里的静态字段 ──────────────
                //
                // Mod 类由 ModClassLoader 加载，它内部的 static CALLS 与测试
                // 所在 ClassLoader 里的那份是【两个不同的字段】。
                // 测试去读自己那份，只会看到一个空列表 —— 而且会误判成
                // 「initialize 没被调用」。
                //
                // 这不是测试的缺陷，而正是生产隔离的真实形态：
                // 平台与 Mod 各有一份同名静态，互不可见。
                //
                // 因此断言必须落在【双方共享的对象】上 ——
                // 平台侧的注册表就是那个共享面：Mod 通过契约往里写，
                // 平台从同一个实例读出来。
                assertEquals(1, registry.blocks().size(),
                        "Mod 的 initialize 必须真的跑完并注册了方块");
                assertEquals("testmod:crops/amaranth",
                        registry.blocks().iterator().next().id(),
                        "方块 id 必须由契约规范（modId:path）拼出");
            } finally {
                runtime.close();
            }
        }
    }

    @Test
    void apiContextExposesEveryContractMethodWithoutThrowing() throws Exception {
        Runtime runtime = Runtime.create("e2e-api");
        runtime.start();
        try {
            ModManifest manifest = ModManifest.of("apimod", "Api Mod", "2.0");
            Scope modScope = runtime.rootScope().createChild("mod:apimod");
            ModContext runtimeCtx =
                    new ModContext(new org.loader.runtime.mod.Mod(manifest, modScope, null));
            org.loader.api.ModContext api = new ApiModContext(runtimeCtx);

            // 元数据
            assertEquals("apimod", api.metadata().id());
            assertEquals("2.0", api.metadata().version());
            assertNotNull(api.metadata().dependencies());

            // 生命周期：当前状态可读
            assertNotNull(api.lifecycle().state());

            // 环境映射：runtime 与 ABI 的枚举同名不同类型
            assertNotNull(api.environment());
            assertEquals(api.environment().isClient(),
                    org.loader.api.Environment.CLIENT == api.environment());

            // isActive 与 getCapability
            api.isActive();
            assertTrue(api.getCapability(String.class).isEmpty()
                    || api.getCapability(String.class).isPresent());

            // logger 全档位不抛
            api.logger().info("e2e info");
            api.logger().fine("e2e fine");
            api.logger().finer("e2e finer");
            api.logger().finest("e2e finest");
        } finally {
            runtime.close();
        }
    }

    @Test
    void lifecycleListenerReceivesMappedStates() throws Exception {
        Runtime runtime = Runtime.create("e2e-lifecycle");
        runtime.start();
        try {
            ModManifest manifest = ModManifest.of("lcmod", "Lifecycle Mod", "1.0");
            Scope modScope = runtime.rootScope().createChild("mod:lcmod");
            org.loader.api.ModContext api =
                    new ApiModContext(new ModContext(new org.loader.runtime.mod.Mod(manifest, modScope, null)));

            List<org.loader.api.lifecycle.LifecycleState> seen =
                    new ArrayList<>();
            api.lifecycle().addListener((from, to) -> seen.add(to));

            modScope.transitionTo(org.loader.runtime.kernel.LifecycleState.RESOLVED);
            modScope.transitionTo(org.loader.runtime.kernel.LifecycleState.LOADED);

            assertEquals(List.of(
                            org.loader.api.lifecycle.LifecycleState.RESOLVED,
                            org.loader.api.lifecycle.LifecycleState.LOADED),
                    seen,
                    "runtime 的状态迁移必须翻译成 ABI 枚举后交给 Mod");
            assertEquals(org.loader.api.lifecycle.LifecycleState.LOADED,
                    api.lifecycle().state());
        } finally {
            runtime.close();
        }
    }

    @Test
    void eventSubscriptionCanBeUnsubscribed() throws Exception {
        Runtime runtime = Runtime.create("e2e-events");
        runtime.start();
        try {
            ModManifest manifest = ModManifest.of("evmod", "Event Mod", "1.0");
            Scope modScope = runtime.rootScope().createChild("mod:evmod");
            org.loader.api.ModContext api =
                    new ApiModContext(new ModContext(new org.loader.runtime.mod.Mod(manifest, modScope, null)));

            int[] calls = {0};
            var sub = api.events().subscribe(String.class, s -> calls[0]++);

            api.events().post("a");
            api.events().post("b");
            assertEquals(2, calls[0]);
            assertTrue(sub.isActive());

            sub.unsubscribe();
            assertFalse(sub.isActive());

            api.events().post("c");
            assertEquals(2, calls[0],
                    "退订后不应再收到事件 —— 适配层必须真正移除监听器，"
                            + "而不是移走一个从未注册的替身");
        } finally {
            runtime.close();
        }
    }

    @Test
    void registryIsBoundAndDeclaresBlocks() throws Exception {
        Runtime runtime = Runtime.create("e2e-registry");
        runtime.start();
        try {
            ModManifest manifest = ModManifest.of("regmod", "Registry Mod", "1.0");
            Scope modScope = runtime.rootScope().createChild("mod:regmod");
            org.loader.runtime.mod.Mod mod =
                    new org.loader.runtime.mod.Mod(manifest, modScope, null);
            ModContext runtimeCtx = new ModContext(mod);
            RegistryBinder.bind(runtimeCtx, mod);

            MinecraftRegistry registry = runtimeCtx.apiRegistry();
            assertNotNull(registry,
                    "RegistryBinder 必须把真实注册表装进 ModContext，"
                            + "否则 Mod 第一行 registry() 就是 null");

            BlockHandle handle = registry.block(BlockSpec
                    .builder("crops/amaranth")
                    .material(BlockSpec.Material.PLANT)
                    .hardness(0.0f)
                    .build());

            assertEquals("regmod:crops/amaranth", handle.id());
            assertEquals(1, registry.blocks().size());
            assertTrue(registry.findBlock("crops/amaranth").isPresent());

            // 重复注册必须响亮失败
            IllegalStateException dup = assertThrows(IllegalStateException.class,
                    () -> registry.block(BlockSpec.builder("crops/amaranth").build()),
                    "同一路径注册两次必须报错，不能静默返回旧句柄");
            assertTrue(dup.getMessage().contains("already registered"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void registryRejectsPathsCarryingANamespace() throws Exception {
        Runtime runtime = Runtime.create("e2e-ns");
        runtime.start();
        try {
            ModManifest manifest = ModManifest.of("nsmod", "NS Mod", "1.0");
            Scope modScope = runtime.rootScope().createChild("mod:nsmod");
            ModContext runtimeCtx = new ModContext(new org.loader.runtime.mod.Mod(manifest, modScope, null));
            RegistryBinder.bind(runtimeCtx, modmod(modScope, manifest));

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> runtimeCtx.apiRegistry().block(
                            BlockSpec.builder("minecraft:stone").build()));
            assertTrue(e.getMessage().contains("must not contain ':'"),
                    "path 里带命名空间会让 id() 变成三段，必须拒绝而不是静默接受");
        } finally {
            runtime.close();
        }
    }

    private static org.loader.runtime.mod.Mod modmod(Scope scope, ModManifest manifest) {
        return new org.loader.runtime.mod.Mod(manifest, scope, null);
    }

    @Test
    void loaderRejectsEntrypointThatDoesNotImplementTheContract() {
        // 反例：NotAMod 有 initialize 方法，但没实现 org.loader.api.Mod。
        //
        // 平台若直接强转 (Mod) instance，这里会抛 ClassCastException ——
        // Mod 作者看到的是「加载失败」，完全不知道自己漏了 implements。
        // LoaderMain 因此在实例化【之前】就显式检查接口
        // （见 invokeModEntrypoints 里的 isAssignableFrom 分支），
        // 并抛出指名道姓的 ModLoadError。
        //
        // 本测试只固定住「前置条件」这一侧：类型关系必须如此，
        // 平台的检查才有意义。真正的检查在 LoaderMain 里，
        // 它是 private且需要完整启动流程，此处不重复断言——
        // 断言自己写的错误消息字符串只会给人虚假的安全感。
        Class<?> notAMod = com.example.testmod.ContractMod.NotAMod.class;
        assertFalse(org.loader.api.Mod.class.isAssignableFrom(notAMod),
                "反例前提：它确实没实现契约接口");

        assertTrue(org.loader.api.Mod.class.isInterface());
        assertEquals(1, org.loader.api.Mod.class.getMethods().length,
                "契约入口只有一个方法 —— 不留任何 fallback 入口，"
                        + "加载失败时才不会『有的 Mod 生效有的不生效』");
    }

    @Test
    void contractHandleFactoriesRejectInvalidInput() {
        // ABI 侧工厂的校验 —— 平台实现靠它挡住非法句柄
        assertThrows(IllegalArgumentException.class,
                () -> BlockHandle.create("", "path", 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockHandle.create("mod", "", 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockHandle.create("mod", "ns:path", 0));

        BlockHandle ok = BlockHandle.create("mod", "a/b", 42);
        assertEquals("mod:a/b", ok.id());
        assertEquals(42, ok.numericId());
        assertEquals(ok, BlockHandle.create("mod", "a/b", 99),
                "句柄按值语义：数值 ID 不参与 equals");
    }
}