package com.example;

import org.loader.api.Environment;
import org.loader.api.LifecycleState;
import org.loader.api.Logger;
import org.loader.api.Mod;
import org.loader.api.ModContext;
import org.loader.api.ModMetadata;
import org.loader.api.event.EventBus;
import org.loader.api.event.Subscription;
import org.loader.api.registry.BlockSpec;
import org.loader.api.registry.MinecraftRegistry;
import org.loader.api.world.BlockHandle;
import org.loader.api.world.WorldView;

/**
 * Mili 官方示例 Mod —— 只 import {@code org.loader.api.*}。
 *
 * <p>演示契约提供的完整能力：
 * <ul>
 *   <li>入口：{@code initialize(ModContext)}</li>
 *   <li>元数据、生命周期观察</li>
 *   <li>事件订阅与退订</li>
 *   <li>调度器（一次性 + 周期）</li>
 *   <li><b>注册方块</b> —— 平台会把它真正写进 Minecraft 的方块注册表</li>
 *   <li>世界访问（世界未加载时为 null，必须判空）</li>
 * </ul>
 *
 * <p>刻意不 import：runtime 内部（{@code org.loader.runtime.*}）、
 * loader 内部（{@code org.loader.loader.*}）、任何 Minecraft 类。
 * 这不是风格偏好 —— ModClassLoader 不允许访问它们，import 了也跑不起来。
 */
public class ExampleMod implements Mod {

    private Subscription startedSub;
    private Subscription tickSub;
    private BlockHandle amaranthCrop;

    @Override
    public void initialize(ModContext context) {
        ModMetadata meta = context.metadata();
        Logger log = context.logger();

        log.info("[" + meta.id() + "] Initializing v" + meta.version()
                + " (author: " + meta.author() + ")");
        log.info("  Environment: " + context.environment());
        log.info("  Entrypoint: " + meta.entrypoint());

        // ── 生命周期 ────────────────────────────────────────────────
        // 观察而非驱动：平台拥有生命周期，Mod 只读。
        context.lifecycle().addListener((from, to) ->
                log.info("  [lifecycle] " + from + " -> " + to));

        // ── 事件 ────────────────────────────────────────────────────
        EventBus events = context.events();

        startedSub = events.subscribe(String.class, msg ->
                log.info("[Event] " + msg));

        tickSub = events.subscribe(Long.class, tick -> {
            // 每 1000 次打一条，避免刷屏
            if (tick % 1000 == 0) {
                log.info("[Tick] #" + tick);
            }
        });

        // ── 调度器 ──────────────────────────────────────────────────
        context.scheduler().submit(() -> log.info("[Scheduler] one-shot task ran"));

        // 周期任务：每秒检查一次世界是否已加载
        context.scheduler().submitRepeating(() -> {
            WorldView world = context.world();
            if (world == null) {
                return; // 世界还没加载 —— 契约明确要求判空
            }
            log.info("[Scheduler] world loaded, Y range = "
                    + world.minY() + ".." + world.maxY());
        }, 1000L, 1000L);

        // ── 注册方块（本Mod 的核心） ────────────────────────────────
        registerContent(context, log);

        log.info("[" + meta.id() + "] Fully initialized, active=" + context.isActive());
    }

    /**
     * 注册本 Mod 的方块。
     *
     * <p><b>注册必须在 {@code initialize} 里完成</b>：契约规定注册表只在
     * 初始化阶段可写，之后 Minecraft 会冻结它。
     */
    private void registerContent(ModContext context, Logger log) {
        MinecraftRegistry registry = context.registry();
        if (registry == null) {
            log.warning("No Minecraft registry bound — running without game content");
            return;
        }

        // 一种作物方块
        amaranthCrop = registry.block(BlockSpec.builder("crops/amaranth")
                .material(BlockSpec.Material.PLANT)
                .noCollision()
                .replaceable()
                .hardness(0.0f)   // 0 = 一戳就碎；负值 = 不可破坏
                .requiresTool(false)
                .texture("all", "examplemod:block/amaranth_crop")
                .build());

        log.info("  Registered block: " + amaranthCrop.id());
        log.info("  Mod has " + registry.blocks().size() + " block(s) declared");

        // 查回来 —— 确认注册确实落地，而不是只记了个声明
        registry.findBlock("crops/amaranth").ifPresent(found ->
                log.info("  Verified: " + found.id() + " is in the registry"));

        log.info("  Registration still open: " + registry.isOpen());
    }

    /**
     * 游戏停止时的清理入口。
     *
     * <p>Mod 不需要（也不应该）自己起线程去停游戏；平台在停服前调用这个方法。
     */
    public void shutdown() {
        // 退订。契约的 Subscription 支持精确退订 —— 这在 Mod 停掉后
        // 监听器仍在跑的场景下很关键。
        if (tickSub != null) {
            tickSub.unsubscribe();
            tickSub = null;
        }
        if (startedSub != null) {
            startedSub.unsubscribe();
            startedSub = null;
        }
    }
}