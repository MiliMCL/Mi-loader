package org.loader.runtime.minecraft.block;

import org.loader.runtime.minecraft.BootstrapGate;
import org.loader.runtime.minecraft.reflect.BridgeMismatchException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生成 Minecraft 方块子类，实现「行为接口 + 委托」。
 *
 * <p><b>为什么必须生成字节码</b>：平台的三条约束叠加后，Mod 无法自己写
 * {@code extends Block} ——
 * <ol>
 *   <li>Mod 的 ClassLoader 不加载游戏类，{@code extends Block} 会
 *       {@code NoClassDefFoundError}；</li>
 *   <li>纯反射无法创建"继承自某个类的实例"；</li>
 *   <li>不使用 Mixin / LaunchWrapper。</li>
 * </ol>
 * 但游戏注册表只接受 {@code Block} 实例。所以在运行时合成一个真正的
 * {@code Block} 子类，其覆写方法体只做一件事 —— 把调用转交给
 * {@link BehaviourDispatch}。
 *
 * <h2>三条实测约束（26.2，改动前请先读）</h2>
 * <ol>
 *   <li>必须先 {@link BootstrapGate#ensureBootstrapped()}，否则
 *       {@code Properties.of()} 抛 {@code Not bootstrapped}；</li>
 *   <li><b>不能用 {@code ClassLoader.defineClass}</b> —— JDK 模块系统封锁，
 *       需要 {@code --add-opens java.base/java.lang}，而用户双击启动不会有该参数。
 *       改用 {@link MethodHandles#privateLookupIn} + {@code Lookup.defineClass}，
 *       实测无需任何启动参数；</li>
 *   <li><b>生成类必须与 lookup 类同包</b>，否则抛
 *       {@code not in same package as lookup class}。lookup 来自 {@code Block}，
 *       因此生成类必须放进 {@code net.minecraft.world.level.block}。</li>
 * </ol>
 */
public final class GeneratedBlockFactory {

    private static final String BLOCK_CLASS = "net.minecraft.world.level.block.Block";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    /** 对象类型的 JVM 描述符 —— 必须含首尾的 L 与 ; */
    private static final String PROPERTIES_INTERNAL =
            "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";

    /** 生成类的包 —— 必须是 Block 所在的包，否则 Lookup.defineClass 会拒绝。 */
    private static final String GENERATED_PACKAGE = "net/minecraft/world/level/block/";
    private static final String GENERATED_PREFIX = "MiliBlock$";

    /**
     * {@code behaviour} 字段的类型 —— <b>必须是 int，不能是 Handle</b>。
     *
     * <p>这不是风格偏好，是硬约束。{@code Class.getDeclaredField} 会<b>解析</b>
     * 字段类型，且用的是<b>声明类</b>的 ClassLoader 去加载。生成类活在游戏
     * ClassLoader 里，若字段声明成平台类型
     * {@code Lorg/loader/runtime/minecraft/block/BehaviourDispatch$Handle;}，
     * 那么游戏 CL 必须能看到平台类才能读出这个字段 ——
     * 一旦游戏 CL 的父加载器不指向平台（测试里就是 {@code null} 父），
     * 立刻抛 {@code NoClassDefFoundError: BehaviourDispatch$Handle}，
     * 而且它出现在 {@code getDeclaredField} 这种完全无辜的位置上。
     *
     * <p>int 是唯一能安全跨 ClassLoader 持有的类型：描述符 {@code I} 不引用任何类，
     * 因此生成类的加载与链接<b>完全不依赖平台可见性</b>。
     * 这正是 {@link BehaviourDispatch.Handle} 存在的意义 ——
     * 它把 int 包装成可读对象给平台自己用，绝不给生成类看。
     */
    private static final String BEHAVIOUR_FIELD = "behaviour";
    private static final String HANDLE_FIELD_DESC = "I";

    private static final AtomicLong COUNTER = new AtomicLong();

    private GeneratedBlockFactory() {
    }

    /**
     * 生成一个方块实例并绑定其行为。
     *
     * @param properties 已配置好的 {@code BlockBehaviour.Properties} 实例
     * @param handle     行为句柄；可为 null 表示无行为的纯数据方块
     * @param debugName  诊断用名称
     * @return {@code Block} 实例
     * @throws BridgeMismatchException 若生成类无法定义或实例化
     *
     * <p><b>造出来≠ 注册成功。</b>26.2 的 {@code Block.<init>} 会把自己登记进
     * {@code unregisteredIntrusiveHolders}，只有 {@code Registry.register(...)}
     * 会把它移走；而 {@code MappedRegistry.freeze()} 在该Map 非空时直接抛
     * {@code Some intrusive holders were not registered}，<b>整个游戏起不来</b>。
     *
     * <p>因此：本方法只负责"造"，调用方<b>必须</b>紧接着把它注册进
     * {@link BlockRegistrar}。平台会在关闭注册窗口前检查这一点并报错
     * （见 {@code RegistrationPhase.closeRegistryWindow()}）。
     */
    public static Object createBlock(Object properties,
                                     BehaviourDispatch.Handle handle,
                                     String debugName) {
        // 窗口关着就不能造 Block —— 提前给出可理解的错误。
        org.loader.runtime.minecraft.RegistrationPhase.openRegistryWindow();
        // 只探测版本，不做游戏 bootstrap —— bootstrap 会冻结方块注册表，
        // 而方块必须在那之前造出来。详见 RegistrationPhase 的类注释。
        org.loader.runtime.minecraft.SharedVersionGate.ensureVersionDetected();

        Class<?> blockClass = loadGameClass(BLOCK_CLASS);
        Class<?> propertiesClass = loadGameClass(
                "net.minecraft.world.level.block.state.BlockBehaviour$Properties");

        Class<?> generated = generate(blockClass, debugName);

        Object block;
        try {
            Constructor<?> ctor = generated.getDeclaredConstructor(propertiesClass);
            ctor.setAccessible(true);
            block = ctor.newInstance(properties);
        } catch (NoSuchMethodException noCtor) {
            // 签名层面就不匹配：生成器与 26.2 的 Block 构造器脱节。
            throw new BridgeMismatchException(
                    "Generated block " + debugName + " has no (Properties) constructor."
                            + "\n  Block in this Minecraft build declares: "
                            + describeBlockConstructors(blockClass)
                            + "\n  This is a binding-layer bug, not a mod bug.", noCtor);
        } catch (ReflectiveOperationException e) {
            // 关键：Constructor.newInstance 会把父类构造器抛出的异常包进
            // InvocationTargetException。若直接把它当 cause 抛出，最终看到的
            // 只有 "InvocationTargetException" + null cause —— 真实原因（父类
            // 构造失败）被完全掩盖。这里必须一路剥到最内层。
            Throwable real = unwrapInstantiationFailure(e);
            throw new BridgeMismatchException(
                    "Cannot instantiate generated block " + debugName
                            + " on Minecraft " + BootstrapGate.currentGameVersion() + "."
                            + "\n  Generated class: " + generated.getName()
                            + "\n  Constructor:     (" + propertiesClass.getName() + ")V"
                            + "\n  Real failure:    " + describe(real), real);
        }

        if (handle != null) {
            BehaviourDispatch.bind(block, handle);
        }
        return block;
    }

    /**
     * 剥掉反射包装，还原实例化真正失败的原因。
     *
     * <p>{@code Constructor.newInstance} 会把构造器体内抛出的异常塞进
     * {@link java.lang.reflect.InvocationTargetException}；而我们生成的构造器
     * 只做两件事（调 super、置 null），所以失败几乎必然来自
     * {@code Block} 的父类构造器。把包装层原样抛出，诊断信息就只剩
     * {@code InvocationTargetException} 且 cause 为 null，等于什么都没说。
     */
    private static Throwable unwrapInstantiationFailure(Throwable t) {
        Throwable cur = t;
        // 循环而非 if：Block 的构造链有多层（Block → BlockBehaviour），
        // 每一层都可能再包一层 InvocationTargetException。
        while (cur instanceof java.lang.reflect.InvocationTargetException
                || cur instanceof java.lang.reflect.UndeclaredThrowableException
                || cur instanceof ExceptionInInitializerError) {
            Throwable inner = cur.getCause();
            if (inner == null || inner == cur) {
                break;
            }
            cur = inner;
        }
        return cur;
    }

    /** 把异常链渲染成单行文本，供异常消息携带（cause 里只有第一层）。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (Throwable c = t; c != null && depth < 8; c = c.getCause()) {
            if (depth > 0) {
                sb.append("\n            caused by: ");
            }
            sb.append(c.getClass().getName());
            if (c.getMessage() != null) {
                sb.append(": ").append(c.getMessage());
            }
            if (c.getCause() == c) {
                break;
            }
            depth++;
        }
        return sb.toString();
    }

    /** 列出 Block 的实际构造器签名 —— 签名不匹配时这是最有价值的一行。 */
    private static String describeBlockConstructors(Class<?> blockClass) {
        StringBuilder sb = new StringBuilder("[");
        Constructor<?>[] ctors = blockClass.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("(");
            Class<?>[] ps = ctors[i].getParameterTypes();
            for (int j = 0; j < ps.length; j++) {
                if (j > 0) {
                    sb.append(", ");
                }
                sb.append(ps[j].getSimpleName());
            }
            sb.append(")");
        }
        return sb.append("]").toString();
    }

    private static Class<?> loadGameClass(String fqn) {        try {
            // 统一走 Reflect：游戏类由 MinecraftClassLoader 定义，不在平台 CL 上。
            return org.loader.runtime.minecraft.reflect.Reflect.gameClass(fqn);
        } catch (RuntimeException e) {
            throw new BridgeMismatchException(
                    "Minecraft class not found: " + fqn
                            + "\n  The platform JAR must be loaded by the same classloader "
                            + "as the game, or the game classes must be on the classpath.", e);
        }
    }

    /**
     * 合成 {@code extends Block} 的子类。
     * <p>生成的类只有：一个 {@code behaviour} 字段、一个转发构造器、以及若干
     * 覆写的行为钩子。
     */
    private static Class<?> generate(Class<?> blockClass, String debugName) {
        String internalName = GENERATED_PACKAGE + GENERATED_PREFIX + COUNTER.incrementAndGet();
        String superName = blockClass.getName().replace('.', '/');

        // COMPUTE_MAXS only: every generated method body is straight-line
        // delegation with no branches, so stack map frames are unnecessary.
        // COMPUTE_FRAMES would need a resolve-class pass we do not want.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, superName, null);

        // 生成类的源文件属性，让崩溃栈可读
        cw.visitSource("MiliBlock.java", null);

        // private int behaviour;   ← int，不是 Handle。理由见 HANDLE_FIELD_DESC。
        cw.visitField(Opcodes.ACC_PRIVATE, BEHAVIOUR_FIELD, HANDLE_FIELD_DESC, null, null)
                .visitEnd();

        emitConstructor(cw, internalName, superName);

        // 覆写行为钩子 —— 签名取自 26.2 实测
        emitVoidHook(cw, internalName, "tick", new String[]{
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Lnet/minecraft/server/level/ServerLevel;",
                "Lnet/minecraft/core/BlockPos;",
                "Lnet/minecraft/util/RandomSource;"},
                "tick");

        emitVoidHook(cw, internalName, "randomTick", new String[]{
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Lnet/minecraft/server/level/ServerLevel;",
                "Lnet/minecraft/core/BlockPos;",
                "Lnet/minecraft/util/RandomSource;"},
                "randomTick");

        // 26.2 的 neighborChanged 多了 Orientation 与 boolean
        emitVoidHook(cw, internalName, "neighborChanged", new String[]{
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Lnet/minecraft/world/level/Level;",
                "Lnet/minecraft/core/BlockPos;",
                "Lnet/minecraft/world/level/block/Block;",
                "Lnet/minecraft/world/level/redstone/Orientation;",
                "Z"}, "neighborChanged");

        emitVoidHook(cw, internalName, "onPlace", new String[]{
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Lnet/minecraft/world/level/Level;",
                "Lnet/minecraft/core/BlockPos;",
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Z"}, "onPlace");

        emitVoidHook(cw, internalName, "attack", new String[]{
                "Lnet/minecraft/world/level/block/state/BlockState;",
                "Lnet/minecraft/world/level/Level;",
                "Lnet/minecraft/core/BlockPos;",
                "Lnet/minecraft/world/entity/player/Player;"}, "attack");

        emitDestroyProgress(cw, internalName);
        emitUseWithoutItem(cw, internalName);

        cw.visitEnd();
        byte[] bytes = cw.toByteArray();
        try {
            return defineInGamePackage(internalName, bytes, blockClass, debugName);
        } catch (RuntimeException e) {
            // ClassFormatError 只说"有方法非法"，不说是哪个。逐个钩子单独生成
            // 一次，定位到确切名字再抛出去 —— 否则 Mod 作者无从下手。
            String culprit = bisectFailingHook(blockClass, debugName);
            throw new BridgeMismatchException(
                    "Generated block for " + debugName + " is invalid"
                            + (culprit != null ? " (hook '" + culprit + "')" : "")
                            + " on Minecraft " + BootstrapGate.currentGameVersion()
                            + ". This is a binding-layer bug, not a mod bug.", e);
        }
    }

    /** 逐个钩子单独生成，返回第一个失败者的名字；全部通过则返回 null。 */
    private static String bisectFailingHook(Class<?> blockClass, String debugName) {
        String superName = superNameOf(blockClass);
        String[][] probes = {
                {"tick", "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/util/RandomSource;"},
                {"randomTick", "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/util/RandomSource;"},
                {"neighborChanged", "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/Block;"
                        + "Lnet/minecraft/world/level/redstone/Orientation;Z"},
                {"onPlace", "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;Z"},
                {"attack", "Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/player/Player;"},
        };
        for (String[] probe : probes) {
            try {
                ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                String probeName = GENERATED_PACKAGE + "Probe$" + COUNTER.incrementAndGet();
                cw2.visit(Opcodes.V21,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                        probeName, null, superName, null);
                cw2.visitField(Opcodes.ACC_PRIVATE, BEHAVIOUR_FIELD, HANDLE_FIELD_DESC, null, null)
                        .visitEnd();
                emitConstructor(cw2, probeName, superName);
                emitVoidHook(cw2, probeName, probe[0], splitParamTypes(probe[1]), probe[0]);
                cw2.visitEnd();
                defineInGamePackage(probeName, cw2.toByteArray(), blockClass, debugName);
            } catch (RuntimeException ex) {
                return probe[0];
            }
        }
        return null;
    }

    private static String superNameOf(Class<?> blockClass) {
        return blockClass.getName().replace('.', '/');
    }

    /** 把 {@code Lfoo;A;} 形式拆成各参数描述符数组。 */
    private static String[] splitParamTypes(String joined) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < joined.length()) {
            int start = i;
            if (joined.charAt(i) == 'L') {
                while (joined.charAt(i) != ';') {
                    i++;
                }
                i++;
            } else {
                i++;
            }
            out.add(joined.substring(start, i));
        }
        return out.toArray(new String[0]);
    }

    /** {@code (Properties)V} — 只调 super，其余交给平台后置绑定。 */
    private static void emitConstructor(ClassWriter cw, String internalName, String superName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(" + PROPERTIES_INTERNAL + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>",
                "(" + PROPERTIES_INTERNAL + ")V", false);
        // behaviour = 0（无效句柄）；真实 id 由 BehaviourDispatch.bind 在实例化后写入
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, BEHAVIOUR_FIELD, HANDLE_FIELD_DESC);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * 生成形如
     * {@code BehaviourDispatch.tick(this, state, level, pos, random)} 的转发方法。
     *
     * <p>所有参数以 {@code Object} 传入 —— 生成代码不认识游戏类型，类型转换
     * 全部在 {@link BehaviourDispatch} 内完成。
     */
    private static void emitVoidHook(ClassWriter cw, String internalName,
                                     String mcMethod, String[] paramTypes, String dispatch) {
        String methodDesc = "(" + String.join("", paramTypes) + ")V";
        String callDesc = objectSignature(paramTypes.length + 1) + "V";
        if (System.getProperty("mili.debugBytecode") != null) {
            System.out.println("[gen] " + mcMethod
                    + "\n     methodDesc=" + methodDesc
                    + "\n     callDesc  =" + callDesc
                    + "\n     dispatch  =" + dispatch);
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, mcMethod,
                methodDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); // this
        for (int i = 0; i < paramTypes.length; i++) {
            loadAndBox(mv, paramTypes[i], i + 1);
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                dispatchInternal(), dispatch, callDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code getDestroyProgress} 返回 float。 */
    private static void emitDestroyProgress(ClassWriter cw, String internalName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "getDestroyProgress",
                "(Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/level/BlockGetter;"
                        + "Lnet/minecraft/core/BlockPos;)F", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        loadAndBox(mv, "Lnet/minecraft/world/level/block/state/BlockState;", 1);
        loadAndBox(mv, "Lnet/minecraft/world/entity/player/Player;", 2);
        loadAndBox(mv, "Lnet/minecraft/world/level/BlockGetter;", 3);
        loadAndBox(mv, "Lnet/minecraft/core/BlockPos;", 4);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, dispatchInternal(), "destroyProgress",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;)F", false);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code useWithoutItem} 返回 {@code InteractionResult}。
     * <p>26.2 把它与 {@code useItemOn} 拆开了 —— 这是与旧版本最容易踩的差异。
     */
    private static void emitUseWithoutItem(ClassWriter cw, String internalName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "useWithoutItem",
                "(Lnet/minecraft/world/level/block/state/BlockState;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/core/BlockPos;"
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lnet/minecraft/world/phys/BlockHitResult;)"
                        + "Lnet/minecraft/world/InteractionResult;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        loadAndBox(mv, "Lnet/minecraft/world/level/block/state/BlockState;", 1);
        loadAndBox(mv, "Lnet/minecraft/world/level/Level;", 2);
        loadAndBox(mv, "Lnet/minecraft/core/BlockPos;", 3);
        loadAndBox(mv, "Lnet/minecraft/world/entity/player/Player;", 4);
        loadAndBox(mv, "Lnet/minecraft/world/phys/BlockHitResult;", 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, dispatchInternal(), "useWithoutItem",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                        + "Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** 按参数描述符装载局部变量，必要时装箱。 */
    private static void loadAndBox(MethodVisitor mv, String descriptor, int localIndex) {
        if ("Z".equals(descriptor)) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
            return;
        }
        if ("I".equals(descriptor)) {
            mv.visitVarInsn(Opcodes.ILOAD, localIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
            return;
        }
        if ("J".equals(descriptor)) {
            mv.visitVarInsn(Opcodes.LLOAD, localIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
            return;
        }
        if ("F".equals(descriptor)) {
            mv.visitVarInsn(Opcodes.FLOAD, localIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false);
            return;
        }
        if ("D".equals(descriptor)) {
            mv.visitVarInsn(Opcodes.DLOAD, localIndex);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false);
            return;
        }
        // 引用类型：局部变量槽可能含 null，故用 ALOAD
        mv.visitVarInsn(Opcodes.ALOAD, localIndex);
    }

    /**
     * 构造只含参数列表的描述符片段，形如
     * {@code (Ljava/lang/Object;Ljava/lang/Object;)}。
     * <p><b>注意</b>：返回类型需由调用方追加（{@code + "V"} 或 {@code + "F"}）。
     * 忘记追加会让 ASM 在解析时越界，而错误信息只报索引位置、不指出原因。
     *
     * <p><b>曾经踩过的坑</b>：早期实现把 {@code ;} 当成"参数分隔符"而不是
     * "每个引用类型描述符的结尾"，于是最后一个参数写成了
     * {@code Ljava/lang/Object}（缺分号）。这种描述符在
     * {@code ClassWriter} 里不报错，要到类加载时才抛
     * {@code ClassFormatError: Invalid method descriptor}，而错误信息完全
     * 不指向真正的原因。因此每个参数都必须自带 {@code ;}。
     */
    private static String objectSignature(int count) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < count; i++) {
            sb.append("Ljava/lang/Object;");
        }
        return sb.append(')').toString();
    }

    private static String dispatchInternal() {
        return BehaviourDispatch.class.getName().replace('.', '/');
    }

    /**
     * 用 {@link MethodHandles#privateLookupIn} 把生成类定义到游戏包内。
     *
     * <p>这是唯一无需 {@code --add-opens} 的可行路径：{@code ClassLoader.defineClass}
     * 被模块系统封锁，而 {@code Lookup#defineClass} 只需要访问
     * {@code java.base/java.lang.invoke} —— 实测在 JDK 25 上可用。
     */
    private static Class<?> defineInGamePackage(String internalName, byte[] bytes,
                                                Class<?> blockClass, String debugName) {
        try {
            Method privateLookupIn = MethodHandles.class.getDeclaredMethod(
                    "privateLookupIn", Class.class, MethodHandles.Lookup.class);
            privateLookupIn.setAccessible(true);
            Object lookup = privateLookupIn.invoke(
                    null, blockClass, MethodHandles.lookup());

            Method defineClass = lookup.getClass()
                    .getDeclaredMethod("defineClass", byte[].class);
            defineClass.setAccessible(true);
            return (Class<?>) defineClass.invoke(lookup, (Object) bytes);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException it
                    && it.getCause() != null) ? it.getCause() : e;
            throw new BridgeMismatchException(
                    "Cannot define generated block class " + internalName
                            + " for " + debugName
                            + ".\n  Cause: " + cause
                            + "\n  Lookup#defineClass requires the generated class to live in "
                            + "the same package as Block (" + GENERATED_PACKAGE + ").", cause);
        }
    }

    /**
     * 自检：确认生成机制在当前 JVM 与当前 Minecraft 版本上真的可用。
     * <p>CI 与诊断用。若这一步失败，后续所有注册都会失败 —— 与其让 Mod 作者
     * 看到一堆 {@code NoClassDefFoundError}，不如早点报清楚。
     *
     * <p><b>刻意不构造方块、不碰注册表。</b>一个"检查是否可用"的方法绝不能
     * 有副作用：
     * <ul>
     *   <li>构造 {@code Block} 会往 {@code unregisteredIntrusiveHolders} 留下
     *       条目，而 {@code freeze()} 见到非空就抛
     *       {@code Some intrusive holders were not registered} —— 一次自检
     *       就能让整个游戏永久起不来；</li>
     *   <li>它依赖注册窗口仍开着，而游戏启动后窗口必然关闭。绑定层完全正常
     *       的情况下，自检却报「窗口已关闭」—— 这是比不检查更坏的结果。</li>
     * </ul>
     * 真正想验证的是「字节码能生成、类能定义」这两件事，而它们只需要
     * {@code defineClass}，不需要实例化。
     *
     * @return 自检通过时返回空字符串，失败时返回诊断信息
     */
    public static String selfCheck() {
        try {
            Class<?> blockClass = loadGameClass(BLOCK_CLASS);
            if (java.lang.reflect.Modifier.isFinal(blockClass.getModifiers())) {
                return "Block is final — cannot subclass. Minecraft version mismatch?";
            }
            // 只生成并定义类，不实例化 —— 见上方关于副作用的说明。
            Class<?> generated = generate(blockClass, "selfCheck");
            if (generated == null || !blockClass.isAssignableFrom(generated)) {
                return "generated class " + (generated == null ? "null" : generated.getName())
                        + " does not extend Block";
            }
            return "";
        } catch (Throwable t) {
            // 剥到最内层：否则这里只会显示 "BridgeMismatchException"
            // 而看不到真正的 ClassFormatError 之类。
            return "binding layer self-check failed: " + describe(unwrapInstantiationFailure(t));
        }
    }
}
