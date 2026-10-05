package org.loader.runtime.minecraft.block;

import org.loader.api.registry.BlockSpec;
import org.loader.runtime.minecraft.SharedVersionGate;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 把 {@link BlockSpec} 翻译成 Minecraft 的 {@code BlockBehaviour.Properties}。
 *
 * <p>全部通过反射完成 —— integration 模块编译期不依赖 Minecraft（ADR 0005）。
 * 每个方法在 26.2 上都已实测存在；若某个方法在新版本消失，抛出的异常会明确
 * 指出是哪个属性无法映射，而不是让 Mod 作者看到 {@code NoSuchMethodError}。
 */
public final class BlockPropertiesBuilder {

    private static final String PROPERTIES_CLASS =
            "net.minecraft.world.level.block.state.BlockBehaviour$Properties";
    private static final String SOUND_TYPE_CLASS = "net.minecraft.world.level.block.SoundType";
    private static final String PUSH_REACTION_CLASS =
            "net.minecraft.world.level.material.PushReaction";

    private BlockPropertiesBuilder() {
    }

    /**
     * 依据规格构建 Properties 实例。
     *
     * @param spec   方块规格
     * @param modId  注册该方块的 Mod id，作为 {@code ResourceKey} 的命名空间
     * @throws BridgeMismatchException 若目标版本的 API 与预期不符
     */
    public static Object build(BlockSpec spec, String modId) {
        // 只需版本就位；游戏 bootstrap 会冻结注册表，方块必须在那之前造。
        SharedVersionGate.ensureVersionDetected();

        // 统一走 Reflect：游戏类由 MinecraftClassLoader 定义，不在平台 CL 上。
        Class<?> propsClass = org.loader.runtime.minecraft.reflect.Reflect.gameClass(
                PROPERTIES_CLASS);

        Object props = callStatic(propsClass, "of");

        // 26.2 新增的强制项：方块 id 必须在构造 Block 之前就绑定。
        // 缺失时 BlockBehaviour.<init> → Properties.effectiveDrops() 里的
        // Objects.requireNonNull(id) 会抛 NPE("Block id not set")。
        // 这是 26.2 相对旧版本的行为变更 —— 旧版本 id 由注册时反查得出。
        props = applyId(propsClass, props, modId, spec.path());

        // 遮挡与碰撞
        if (spec.hasNoCollision() || !spec.hasCollision()) {
            props = call(propsClass, props, "noCollision");
        }
        // 平面植物没有完整立方体外形
        if (spec.material() == BlockSpec.Material.PLANT
                || spec.material() == BlockSpec.Material.TORCH
                || spec.material() == BlockSpec.Material.BARRIER) {
            props = call(propsClass, props, "noOcclusion");
        }

        // 硬度：Minecraft 用 -1 表示「不可破坏」，用 0 表示「瞬间破坏」。
        // 两者语义完全相反，绝不能靠"跳过 strength()"来表达不可破坏 ——
        // 跳过会保留默认 0，也就是把「坚不可摧」悄悄变成「一戳就碎」。
        // 这正是平台方块硬度映射必须显式处理负值的原因。
        float hardness = spec.hardness();
        props = callWithFloat(propsClass, props, "strength", hardness < 0.0f ? -1.0f : hardness);

        // 随机刻：作物生长等需要
        if (spec.material() == BlockSpec.Material.PLANT
                || spec.hasNoCollision()
                || spec.isReplaceable()) {
            props = call(propsClass, props, "randomTicks");
        }

        // 发光度
        if (spec.lightLevel() > 0) {
            props = callWithLightLevel(propsClass, props, spec.lightLevel());
        }

        // 声音类型
        SoundTypeSound sound = SoundTypeSound.forMaterial(spec.material());
        if (sound != null) {
            props = applySound(propsClass, props, sound);
        }

        // 活塞行为
        props = applyPushReaction(propsClass, props, spec);

        return props;
    }

    /**
     * 绑定方块 id —— 26.2 起 {@code Properties.setId} 是构造 Block 的前置条件。
     *
     * <p>取值规则：
     * <ul>
     *   <li>{@code path} 形如 {@code crops/amaranth} → 命名空间取 modId；</li>
     *   <li>{@code path} 形如 {@code mymod:crops/amaranth} → 命名空间以 path 为准
     *       （显式指定优先于注册者）；</li>
     *   <li>modId 为空且 path 无命名空间 → 回落到 {@code minecraft}，
     *       保持"能创建出来"而不是"创建失败"。</li>
     * </ul>
     *
     * <p>失败时<b>不静默</b>：跳过 setId 得到的 Properties 会在下一步
     * 构造 Block 时抛一个完全不指向原因的 NPE("Block id not set")。
     */
    private static Object applyId(Class<?> propsClass, Object props,
                                  String modId, String path) {
        String namespace;
        String remaining = path == null ? "" : path.trim();

        int colon = remaining.indexOf(':');
        if (colon > 0) {
            namespace = remaining.substring(0, colon);
            remaining = remaining.substring(colon + 1);
        } else if (modId != null && !modId.isBlank()) {
            String m = modId.trim();
            namespace = m.contains(":") ? m.substring(0, m.indexOf(':')) : m;
        } else {
            namespace = "minecraft";
        }

        String normalizedPath = remaining.isEmpty() ? "block" : remaining;

        try {
            // 26.2 里 ResourceLocation 已更名为 net.minecraft.resources.Identifier。
            // 两个名字都试：新版本用 Identifier，旧版本回退。
            Class<?> identifierClass = tryGameClass(
                    "net.minecraft.resources.Identifier",
                    "net.minecraft.resources.ResourceLocation");
            if (identifierClass == null) {
                throw new BridgeMismatchException(
                        "Neither net.minecraft.resources.Identifier nor ResourceLocation"
                                + " exists; cannot bind a block id. Minecraft version mismatch?");
            }

            Object identifier = identifierClass
                    .getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, sanitizeNamespace(namespace), normalizedPath);

            Class<?> resourceKeyClass =
                    org.loader.runtime.minecraft.reflect.Reflect.gameClass(
                            "net.minecraft.resources.ResourceKey");
            Class<?> registryKeyClass = org.loader.runtime.minecraft.reflect.Reflect
                    .gameClass("net.minecraft.core.registries.Registries");

            // Registries.BLOCK : ResourceKey<Registry<Block>>
            Object blockRegistryKey = registryKeyClass.getField("BLOCK").get(null);

            // ResourceKey.create(RegistryKey, Identifier)
            Object blockKey = resourceKeyClass
                    .getMethod("create", resourceKeyClass, identifierClass)
                    .invoke(null, blockRegistryKey, identifier);

            return propsClass.getMethod("setId", resourceKeyClass).invoke(props, blockKey);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "Cannot bind block id '" + namespace + ":" + normalizedPath + "'."
                            + "\n  Properties.setId(ResourceKey<Block>) is required since"
                            + " Minecraft 26.2 — without it Block's constructor throws"
                            + " NPE(\"Block id not set\")."
                            + "\n  This is a binding-layer bug, not a mod bug.", e);
        }
    }

    /** 命名空间只允许 [a-z0-9_.-]，否则 Identifier 会抛 IllegalArgumentException。 */
    private static String sanitizeNamespace(String raw) {
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        return out.isEmpty() ? "minecraft" : out;
    }

    private static Class<?> tryGameClass(String... candidates) {
        for (String fqn : candidates) {
            try {
                return org.loader.runtime.minecraft.reflect.Reflect.gameClass(fqn);
            } catch (RuntimeException ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    private static Object applySound(Class<?> propsClass, Object props, SoundTypeSound sound) {
        try {
            Class<?> soundType = org.loader.runtime.minecraft.reflect.Reflect
                    .gameClass(SOUND_TYPE_CLASS);
            Object value = sound.resolve(soundType);
            if (value == null) {
                return props;
            }
            return propsType(propsClass).getMethod("sound", soundType).invoke(props, value);
        } catch (ReflectiveOperationException e) {
            // 声音类型名在版本间会变（如 GRASS -> GRASS_BLOCK）。声音不是关键属性，
            // 映射失败时保持默认，不阻断注册。
            return props;
        }
    }

    private static Object applyPushReaction(Class<?> propsClass, Object props, BlockSpec spec) {
        try {
            Class<?> reaction = org.loader.runtime.minecraft.reflect.Reflect
                    .gameClass(PUSH_REACTION_CLASS);
            String field = spec.isSolid() && !spec.hasNoCollision() ? "NORMAL" : "DESTROY";
            Object value = reaction.getField(field).get(null);
            return propsType(propsClass).getMethod("pushReaction", reaction).invoke(props, value);
        } catch (ReflectiveOperationException e) {
            return props;
        }
    }

    private static Class<?> propsType(Class<?> propsClass) {
        return propsClass;
    }

    private static Object callStatic(Class<?> cls, String name) {
        try {
            return cls.getMethod(name).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "BlockBehaviour.Properties." + name + "() is missing; "
                            + "Minecraft version mismatch?", e);
        }
    }

    private static Object call(Class<?> cls, Object instance, String name) {
        try {
            return cls.getMethod(name).invoke(instance);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "BlockBehaviour.Properties." + name + "() is missing; "
                            + "Minecraft version mismatch?", e);
        }
    }

    private static Object callWithFloat(Class<?> cls, Object instance, String name, float value) {
        try {
            return cls.getMethod(name, float.class).invoke(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "BlockBehaviour.Properties." + name + "(float) is missing; "
                            + "Minecraft version mismatch?", e);
        }
    }

    /**
     * 发光度需要 {@code ToIntFunction<BlockState>}。用一个动态代理把常量值
     * 包装成函数，避免生成额外类。
     *
     * <p>代理定义在 <b>bootstrap CL</b>（{@code null}）而非平台 CL：
     * {@code ToIntFunction} 是 JDK 接口，代理类放平台 CL 会让它多出一个
     * 不必要的定义者，且在 Mod 隔离拓扑下容易引入可见性问题。
     */
    private static Object callWithLightLevel(Class<?> cls, Object instance, int level) {
        try {
            Class<?> toIntFn = Class.forName("java.util.function.ToIntFunction", true, null);
            Object fn = java.lang.reflect.Proxy.newProxyInstance(
                    null,
                    new Class<?>[]{toIntFn},
                    (proxy, method, args) -> {
                        if ("applyAsInt".equals(method.getName())) {
                            return level;
                        }
                        return switch (method.getName()) {
                            case "toString" -> "MiliLightLevel(" + level + ")";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    });
            return cls.getMethod("lightLevel", toIntFn).invoke(instance, fn);
        } catch (ReflectiveOperationException e) {
            throw new BridgeMismatchException(
                    "BlockBehaviour.Properties.lightLevel(ToIntFunction) is missing; "
                            + "Minecraft version mismatch?", e);
        }
    }

    /**
     * Minecraft 声音类型字段名。
     * <p>26.2 使用下划线风格（如 {@code GRASS_BLOCK}），旧版本是
     * {@code GRASS}。两者都试，失败则用默认。
     */
    private enum SoundTypeSound {
        GRASS("GRASS_BLOCK", "GRASS"),
        STONE("STONE"),
        WOOD("WOOD"),
        SAND("SAND"),
        WOOL("WOOL"),
        GLASS("GLASS"),
        METAL("METAL"),
        GRAVEL("GRAVEL"),
        PLANT("PLANT"),
        CROP("CROP");

        private final String[] candidates;

        SoundTypeSound(String... candidates) {
            this.candidates = candidates;
        }

        /** 在目标版本里找到对应的 SoundType 常量；找不到返回 null。 */
        Object resolve(Class<?> soundType) {
            for (String name : candidates) {
                try {
                    return soundType.getField(name).get(null);
                } catch (ReflectiveOperationException ignored) {
                    // 试下一个候选名
                }
            }
            return null;
        }

        static SoundTypeSound forMaterial(BlockSpec.Material material) {
            return switch (material) {
                case PLANT -> PLANT;
                case LIQUID -> CROP;
                case TORCH -> WOOD;
                case FENCE, STAIRS, DOOR, TRAPDOOR -> WOOD;
                case SLAB, BUTTON, PRESSURE_PLATE, BED -> STONE;
                default -> null;
            };
        }
    }

    /**
     * 材质定义（贴图/模型）。Minecraft 的材质模型在 26.2 已改为
     * 数据驱动，这里只保留占位 —— 渲染层的绑定属于后续工作。
     */
    public static String describeTextures(BlockSpec spec) {
        Map<String, String> textures = spec.textures();
        if (textures.isEmpty()) {
            return "<none>";
        }
        StringBuilder sb = new StringBuilder();
        textures.forEach((slot, model) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(slot).append('=').append(model);
        });
        return sb.toString();
    }
}
