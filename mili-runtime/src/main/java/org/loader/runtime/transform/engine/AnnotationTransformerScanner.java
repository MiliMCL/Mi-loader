package org.loader.runtime.transform.engine;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.api.transform.annotation.MiliInject;
import org.loader.api.transform.callback.InjectionContext;
import org.loader.api.transform.symbol.MiliSymbol;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.loader.runtime.transform.asm.MiliClassTransformer;
import org.objectweb.asm.Type;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 声明式转换器扫描器 —— 把注解合成为 {@link MiliTransformer}。
 *
 * <h2>这个类存在的理由：让 Mod 的编译依赖里没有 ASM</h2>
 * ADR-0011 的硬约束是「ASM 是平台内部实现细节」。若 Mod 要做字节码转换
 * 就必须自己引入 ASM，那么平台就失去了对转换的校验、权限判定与冲突检测
 * —— Mod 可以直接改任意字节码而不留痕迹。
 *
 * <p>因此 Mod 写的是<b>注解</b>：
 * <pre>
 * &#64;MiliTransformer(target = "minecraft.server.tick")
 * public final class MyHooks {
 *     &#64;MiliInject(at = InjectionPoint.HEAD)
 *     public static void onTick(InjectionContext ctx) {
 *         // ...
 *     }
 * }
 * </pre>
 * 平台在加载期扫描这些注解，把它们合成为内部 {@code MiliTransformer}。
 * <b>Mod 的 classpath 里只有 mili-abi，永远没有 ASM。</b>
 *
 * <h2>为什么校验必须发生在加载期，而不是生成字节码时</h2>
 * 字节码生成阶段的报错形态是灾难性的：
 * <pre>
 *   java.lang.VerifyError: Bad type on operand stack
 *     at net.minecraft.server.MinecraftServer.tickServer(MinecraftServer.java:0)
 * </pre>
 * 堆栈指向游戏代码，完全看不出是哪个 Mod 的哪个回调签名写错了。
 * 而签名错误（少一个 {@code static}、参数类型不对、{@code void} 回调
 * 用在 {@code MODIFY_RETURN} 上）全都是<b>纯反射可判定</b>的 ——
 * 所以这里全部前置校验，让错误以
 * 「MyMod 的 onTick 回调签名非法：...」的形式在加载期出现。
 *
 * <h2>符号名的存在理由</h2>
 * {@code @MiliTransformer(target = "minecraft.server.tick")} 是字符串而非
 * {@code TargetMethod} 引用，因为 ABI 里不允许出现任何 Minecraft 坐标 ——
 * 否则 ABI 就与游戏版本绑定了。解析在 runtime 侧完成。
 */
public final class AnnotationTransformerScanner {

    /**
     * 逻辑符号名 → 真实坐标。
     *
     * <p><b>刻意不维护第二份符号表。</b>早期版本在这里硬编码了一份
     * {@code Map<String, TargetMethod>}，结果是同一个符号在两处定义 ——
     * 改了一处忘了另一处，转换器就会注入到错误的坐标上，且不报错。
     * 现在统一走 {@link org.loader.api.transform.symbol.MiliMapping}。
     *
     * <p>这里的 key 是<b>便于书写的短名</b>（如 {@code minecraft.server.tick}），
     * 而 {@link MiliMapping} 用完整坐标作 key。两者共存的前提是
     * 下面的解析在加载期执行（只发生一次），不在热路径上。
     */
    private static final Map<String, TargetMethod> SYMBOLS = Map.of(
            "minecraft.server.tick", MiliSymbol.SERVER_TICK,
            "minecraft.server.tick_children", MiliSymbol.SERVER_TICK_CHILDREN,
            "minecraft.client.level_tick", MiliSymbol.CLIENT_LEVEL_TICK,
            "minecraft.client.tick", MiliSymbol.CLIENT_TICK,
            "minecraft.client.brand", MiliSymbol.CLIENT_BRAND,
            "minecraft.client.title_screen_init", MiliSymbol.TITLE_SCREEN_INIT,
            "minecraft.client.main", MiliSymbol.CLIENT_MAIN,
            "minecraft.server.main", MiliSymbol.SERVER_MAIN);

    /**
     * 校验：短名表必须与 {@link MiliMapping} 覆盖同一批坐标。
     *
     * <p>两份符号表一旦漂移，最典型的症状是「Mod 声明的符号能加载，
     * 但注入目标与平台内部用的不是同一个方法」—— 而这不报错。
     *
     * <p>因此这个断言在 CI 与测试中都会跑。
     *
     * <p>可见性说明：必须是 {@code public} —— 测试类位于
     * {@code org.loader.runtime.transform} 包，而本类在
     * {@code .engine} 子包，包级可见性跨包不可见。
     * 这条自检必须能被测试直接调用，否则「CI 会跑」这句话是空的。
     */
    public static void assertConsistentWithMapping() {
        java.util.Set<String> mapped = org.loader.api.transform.symbol.MiliMapping
                .entries().stream()
                .map(org.loader.api.transform.symbol.MiliMapping.Entry::toTargetMethod)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Set<String> local = SYMBOLS.values().stream()
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());

        if (!mapped.equals(local)) {
            java.util.Set<String> onlyMapped = new java.util.TreeSet<>(mapped);
            onlyMapped.removeAll(local);
            java.util.Set<String> onlyLocal = new java.util.TreeSet<>(local);
            onlyLocal.removeAll(mapped);

            throw new IllegalStateException(
                    "扫描器符号表与 MiliMapping 不一致。\n"
                            + "  仅在 MiliMapping 中: " + onlyMapped + "\n"
                            + "  仅在扫描器中: " + onlyLocal + "\n"
                            + "两份符号表漂移会让「Mod 声明的目标」与「平台使用的目标」"
                            + "不一致 —— 表现为注入到错误方法，且不报错。");
        }
    }

    private AnnotationTransformerScanner() {
    }

    /**
     * 由注解声明的转换器。
     *
     * <p>一个 {@code @MiliTransformer} 类可以包含多个 {@code @MiliInject}
     * 方法，它们合成为<b>一个</b>转换器 —— 因为它们作用于同一个目标类，
     * 且必须作为一个整体参与冲突检测（否则同一 Mod 的两个回调会被当成
     * 两个独立转换器，冲突检测会漏掉它们之间的互斥关系）。
     */
    public static final class AnnotatedTransformer implements MiliTransformer {

        private final String id;
        private final String minecraftVersion;
        private final TransformationPhase phase;
        private final int priority;
        private final TargetMethod target;
        private final String modId;
        private final List<CallbackSpec> callbacks;

        AnnotatedTransformer(String id, String minecraftVersion,
                             TransformationPhase phase, int priority,
                             TargetMethod target, String modId,
                             List<CallbackSpec> callbacks) {
            this.id = id;
            this.minecraftVersion = minecraftVersion;
            this.phase = phase;
            this.priority = priority;
            this.target = target;
            this.modId = modId;
            this.callbacks = List.copyOf(callbacks);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String minecraftVersion() {
            return minecraftVersion;
        }

        @Override
        public TransformationPhase phase() {
            return phase;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean matches(String className) {
            return target.owner().equals(className);
        }

        /** 本转换器覆盖的目标类 —— 供调用方建立精确索引。 */
        public String targetClassName() {
            return target.owner();
        }

        public List<CallbackSpec> callbacks() {
            return callbacks;
        }

        @Override
        public TransformationResult transform(TransformationContext context) {
            byte[] original = context.originalBytes();

            // 目标必须存在 —— 找不到就抛，绝不返回 Skipped。
            // 否则 Mod 会「加载成功但功能不生效」，且没有任何错误提示。
            //
            // 字段注入跳过此断言：字段目标的校验由 InjectionMethodVisitor
            // 在遇到字段指令时完成（字段注入的锚点不在方法签名上）。
            for (CallbackSpec spec : callbacks) {
                if (!isFieldInjection(spec.point())) {
                    PipelineTransformers.requireMethod(original,
                            spec.methodTarget(), id, minecraftVersion);
                }
            }

            List<MiliClassTransformer.MethodInjection> injections = new ArrayList<>();
            for (CallbackSpec spec : callbacks) {
                injections.add(spec.toInjection(id, priority));
            }

            byte[] transformed = MiliClassTransformer.apply(
                    original, context.className(), injections);
            return new TransformationResult.Transformed(transformed);
        }

        @Override
        public String toString() {
            return "AnnotatedTransformer[" + id + " → " + target
                    + ", callbacks=" + callbacks.size() + "]";
        }
    }

    /** 一个被 {@code @MiliInject} 标记的回调方法。 */
    public record CallbackSpec(
            Method method,
            InjectionPoint point,
            TargetMethod methodTarget,
            org.loader.api.transform.target.TargetField field,
            TargetInvocation invocation,
            int argIndex,
            String callbackDescriptor,
            int priority,
            boolean propagateException,
            boolean cancellable,
            String constant
    ) {

        /** 转成 ASM 层需要的注入描述。 */
        MiliClassTransformer.MethodInjection toInjection(String transformerId, int defPriority) {
            return new MiliClassTransformer.MethodInjection(
                    methodTarget,
                    field,
                    point,
                    callbackDescriptor,
                    invocation,
                    argIndex,
                    // 回调的 owner/name 来自被注解的方法本身
                    internalName(method.getDeclaringClass()),
                    method.getName(),
                    callbackDescriptor,
                    true,           // 声明式回调一律要求 static
                    transformerId + "#" + method.getName(),
                    priority != Integer.MIN_VALUE ? priority : defPriority,
                    cancellable,
                    constant);
        }

        private static String internalName(Class<?> type) {
            return type.getName().replace('.', '/');
        }
    }

    /**
     * 扫描一个类，合成转换器；无转换声明时返回 {@code null}。
     *
     * @param hookClass     被 {@code @MiliTransformer} 标记的类
     * @param minecraftVersion 平台 Minecraft 版本
     * @param modId         所属 Mod；平台自身传 null
     * @return 合成的转换器；无声明时 null
     */
    public static AnnotatedTransformer scan(
            Class<?> hookClass, String minecraftVersion, String modId) {

        org.loader.api.transform.annotation.MiliTransformer annotation =
                hookClass.getAnnotation(
                        org.loader.api.transform.annotation.MiliTransformer.class);
        if (annotation == null) {
            return null;
        }

        // 每次 scan 都校验一次：两份符号表漂移是最隐蔽的失效，
        // 而 scan 只在 Mod 加载期发生（每个类一次），开销可忽略。
        assertConsistentWithMapping();

        // ── 符号解析 ────────────────────────────────────────────────
        String symbolName = annotation.target();
        if (symbolName == null || symbolName.isBlank()) {
            throw new TransformationException(
                    describe(hookClass, modId) + " 未声明 target 符号名。\n"
                            + "可用符号: " + String.join(", ", SYMBOLS.keySet()),
                    null, null);
        }
        TargetMethod target = SYMBOLS.get(symbolName);
        if (target == null) {
            // 明确失败：符号名写错时若静默跳过，表现就是
            // 「Mod 加载成功但功能永远不生效」。
            throw new TransformationException(
                    describe(hookClass, modId) + " 声明了未知符号 \"" + symbolName + "\"\n"
                            + "可用符号: " + String.join(", ", SYMBOLS.keySet()) + "\n"
                            + "符号名写错不会有任何其他症状 —— 转换器静默不生效。",
                    null, null);
        }

        String version = annotation.minecraftVersion().isBlank()
                ? minecraftVersion : annotation.minecraftVersion();

        // ── 收集回调 ────────────────────────────────────────────────
        List<CallbackSpec> callbacks = new ArrayList<>();
        for (Method method : hookClass.getDeclaredMethods()) {
            MiliInject inject = method.getAnnotation(MiliInject.class);
            if (inject == null) {
                continue;
            }
            callbacks.add(buildSpec(hookClass, method, inject, target, modId));
        }

        if (callbacks.isEmpty()) {
            throw new TransformationException(
                    describe(hookClass, modId) + " 被标记为 @MiliTransformer，"
                            + "但类中没有任何 @MiliInject 方法。\n"
                            + "它会匹配到目标类却什么都不做 —— "
                            + "与「转换失败」无法区分。",
                    null, null);
        }

        // 确定性排序：同 id 的转换器内部也必须顺序稳定
        callbacks.sort((a, b) -> {
            int p = Integer.compare(a.priority(), b.priority());
            return p != 0 ? p : a.method().getName().compareTo(b.method().getName());
        });

        return new AnnotatedTransformer(
                idFor(hookClass, modId), version, annotation.phase(),
                annotation.priority(), target, modId, callbacks);
    }

    /**
     * 转换器 id。
     *
     * <p>格式 {@code modId#ClassName} —— 全局唯一且可读。
     * 冲突检测与审计都以它为主键。
     */
    private static String idFor(Class<?> hookClass, String modId) {
        return (modId != null ? modId : "platform") + "#" + hookClass.getSimpleName();
    }

    private static String describe(Class<?> hookClass, String modId) {
        return "转换声明 " + hookClass.getName()
                + (modId != null ? "（Mod: " + modId + "）" : "（平台）");
    }

    /** 构建并校验一个回调描述。 */
    private static CallbackSpec buildSpec(
            Class<?> hookClass, Method method, MiliInject inject,
            TargetMethod defaultTarget, String modId) {

        String where = describe(hookClass, modId) + " 的回调 " + method.getName();

        // ── 静态性 ────────────────────────────────────────────────
        // 生成的是 INVOKESTATIC。若回调是实例方法，生成的字节码无法链接。
        // 这类错误必须在此拦下 —— 否则 defineClass 时才炸，且指向 Minecraft。
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new TransformationException(
                    where + " 必须声明为 static。\n"
                            + "注入生成的是 INVOKESTATIC 调用，实例方法无法链接。",
                    null, null);
        }

        // ── 参数形态：[InjectionContext] + 目标实参前缀捕获 ────────
        //
        // 回调签名由两种成分按固定顺序构成：
        //   1. 至多一个 InjectionContext（执行上下文，可选）；
        //   2. 零个或多个「实参捕获」参数 —— 必须与目标方法的实参
        //      从第 0 位起逐位严格相等（构成前缀）。
        //
        // 例如目标 tickServer(BooleanSupplier) 的合法回调形态：
        //   ()V   (ctx)   (BooleanSupplier)   (ctx, BooleanSupplier)
        //
        // 捕获由签名推断，没有注解属性 —— 这是对 ABI 稳定的刻意选择：
        // 未来扩展捕获能力（如命名捕获）时新增注解属性即非破坏性变更。
        InjectionPoint point = inject.at();
        Class<?>[] params = method.getParameterTypes();

        int captureStart = 0;
        if (params.length > 0 && params[0].equals(InjectionContext.class)) {
            captureStart = 1;
        }
        int captureCount = params.length - captureStart;
        if (captureCount > 0) {
            validateArgCapture(where, point, params, captureStart, captureCount,
                    defaultTarget);
        }

        // ── 返回类型 ──────────────────────────────────────────────
        Class<?> returnType = method.getReturnType();

        if (point == InjectionPoint.MODIFY_RETURN) {
            // MODIFY_RETURN 必须产出值：void 回调会让栈上没有返回值，
            // 紧随的 xRETURN 直接 VerifyError。
            if (returnType == void.class) {
                throw new TransformationException(
                        where + " 使用 MODIFY_RETURN 但返回 void。\n"
                                + "覆写返回值必须产出新值 —— void 回调会让 xRETURN 抛 VerifyError，"
                                + "而堆栈指向 Minecraft。",
                        null, null);
            }
        } else if (point == InjectionPoint.MODIFY_ARG) {
            if (returnType == void.class) {
                throw new TransformationException(
                        where + " 使用 MODIFY_ARG 但返回 void。\n"
                                + "修改实参必须返回替换后的值。",
                        null, null);
            }
        } else if (point != InjectionPoint.MODIFY_CONSTANT && returnType != void.class) {
            // 其余注入点插入的是「执行一个副作用」的回调，
            // 返回值会让栈失衡（多出一个值无处消费）。
            // MODIFY_CONSTANT 例外：它必须返回替换后的常量值（上文已校验类型）。
            throw new TransformationException(
                    where + " 在 " + point + " 注入点必须返回 void，实际为 "
                            + returnType.getSimpleName() + "。\n"
                            + "只有 MODIFY_RETURN / MODIFY_ARG / MODIFY_CONSTANT 允许非 void 返回值。",
                    null, null);
        }

        // ── 可取消性与常量修改的前置校验 ─────────────────────────
        boolean cancellable = inject.cancellable();
        String constant = inject.constant();

        if (cancellable
                && point != InjectionPoint.HEAD
                && point != InjectionPoint.BEFORE_INVOKE) {
            throw new TransformationException(
                    where + " 声明 cancellable = true，但 " + point
                            + " 不支持取消。\n"
                            + "只有 HEAD（取消整个方法）与 BEFORE_INVOKE（跳过被锚定调用）"
                            + "有可定义的取消语义；其余注入点声明取消会在生成期"
                            + "产生无法定义的字节码。",
                    null, null);
        }

        if (point == InjectionPoint.MODIFY_CONSTANT) {
            if (constant.isBlank()) {
                throw new TransformationException(
                        where + " 使用 MODIFY_CONSTANT 但未声明 constant。\n"
                                + "未声明常量的常量修改会匹配零个 LDC，"
                                + "即「加载成功但永不生效」。",
                        null, null);
            }
            if (returnType == void.class) {
                throw new TransformationException(
                        where + " 使用 MODIFY_CONSTANT 但返回 void。\n"
                                + "常量替换必须产出新值。",
                        null, null);
            }
            if (returnType != int.class && returnType != long.class
                    && returnType != float.class && returnType != double.class
                    && returnType != boolean.class
                    && returnType != String.class) {
                throw new TransformationException(
                        where + " 的 MODIFY_CONSTANT 回调返回类型非法: "
                                + returnType.getName() + "。\n"
                                + "仅支持 int/long/float/double/boolean/String。",
                        null, null);
            }
        } else if (!constant.isBlank()) {
            throw new TransformationException(
                    where + " 声明了 constant 但注入点是 " + point + "。\n"
                            + "constant 仅在 MODIFY_CONSTANT 下有效 ——"
                            + "声明在别处只会让人误以为它起作用。",
                    null, null);
        }

        // ── 目标定位 ──────────────────────────────────────────────
        org.loader.api.transform.target.TargetField field = null;
        TargetMethod methodTarget = defaultTarget;
        TargetInvocation invocation = null;

        switch (point) {
            case BEFORE_FIELD_ACCESS, AFTER_FIELD_ACCESS,
                 BEFORE_FIELD_SET, REPLACE_FIELD_ACCESS -> {
                if (inject.field().isBlank()) {
                    throw new TransformationException(
                            where + " 使用 " + point + " 但未指定 field。\n"
                                    + "格式: owner#name:descriptor",
                            null, null);
                }
                field = parseField(inject.field(), where);
            }
            case BEFORE_INVOKE, AFTER_INVOKE, REDIRECT -> {
                if (inject.target().isBlank()) {
                    throw new TransformationException(
                            where + " 使用 " + point + " 但未指定 target 调用坐标。\n"
                                    + "格式: owner#name(descriptor)",
                            null, null);
                }
                TargetMethod called = parseMethod(inject.target(), where);
                invocation = TargetInvocation.at(called, inject.ordinal());
                methodTarget = defaultTarget;
            }
            case MODIFY_ARG -> {
                if (inject.argIndex() < 0) {
                    throw new TransformationException(
                            where + " 使用 MODIFY_ARG 但未指定 argIndex（从 0 开始）。",
                            null, null);
                }
                if (inject.target().isBlank()) {
                    throw new TransformationException(
                            where + " 使用 MODIFY_ARG 时必须指定 target —— "
                                    + "需要知道是哪个调用的第几个实参。",
                            null, null);
                }
                TargetMethod called = parseMethod(inject.target(), where);
                invocation = TargetInvocation.at(called, inject.ordinal());
                methodTarget = defaultTarget;
            }
            default -> {
                // HEAD / RETURN / MODIFY_RETURN / OVERWRITE 作用于方法本身，
                // 用类级 target 作为锚点。
            }
        }

        // 回调在字节码中的描述符：由真实反射签名推导，绝不手写
        String callbackDescriptor = describeCallback(params, returnType);

        return new CallbackSpec(method, point, methodTarget, field, invocation,
                inject.argIndex(), callbackDescriptor,
                inject.priority(), inject.propagateException(),
                cancellable, constant.isBlank() ? null : constant);
    }

    /**
     * 实参捕获是否被该注入点支持。
     *
     * <p>只有锚定<b>宿主方法本身</b>的注入点（{@code HEAD} /
     * {@code RETURN} / {@code MODIFY_RETURN}）才允许捕获 —— 它们的
     * 触发时机对「宿主局部变量槽里的实参」有定义良好的读取语义。
     *
     * <p>调用点类注入（{@code BEFORE_INVOKE} 等）锚定的是方法体内的
     * 某次调用：触发时宿主实参躺在局部变量槽里、被锚定调用的实参
     * 躺在操作数栈上，两者的「参数序号」会撞车 —— 与其让
     * {@code (int a)} 到底捕获哪个产生歧义，不如直接拒绝。
     */
    private static boolean allowsArgCapture(InjectionPoint point) {
        return point == InjectionPoint.HEAD
                || point == InjectionPoint.RETURN
                || point == InjectionPoint.MODIFY_RETURN;
    }

    /**
     * 校验回调声明的实参捕获参数。
     *
     * <h2>按位严格相等，为什么连子类都不允许</h2>
     * 捕获的实现是「从宿主方法的局部变量槽发射 {@code xLOAD}」——
     * 槽里是什么就加载什么，没有任何转换。若允许子类
     * （目标声明 {@code Entity}、回调写 {@code LivingEntity}），
     * 加载出的值类型仍是 {@code Entity}，调用回调时
     * {@code checkcast} 由谁插入？插入则是隐式行为（Mixin 的泛型擦除
     * 就是这个坑）；不插入则运行期 {@code ClassCastException} 指向
     * Minecraft 调用点。因此规则只有一条：<b>描述符逐位相等</b>，
     * 装箱（{@code int} vs {@code Integer}）与子类统统拒绝。
     *
     * <h2>为什么必须是前缀</h2>
     * 捕获参数的槽位由<b>声明位置</b>决定（第 i 个捕获参数 = 目标
     * 第 i 个实参）。允许跳位（只声明第 2 个实参）会让回调签名失去
     * 自解释性 —— 读者无法从签名看出 {@code (int x)} 捕获的是谁。
     * 前缀规则让「签名即文档」成立。
     */
    private static void validateArgCapture(
            String where, InjectionPoint point, Class<?>[] params,
            int captureStart, int captureCount, TargetMethod target) {

        if (!allowsArgCapture(point)) {
            throw new TransformationException(
                    where + " 声明了 " + captureCount + " 个实参捕获参数，"
                            + "但注入点 " + point + " 不支持捕获。\n"
                            + "只有 HEAD / RETURN / MODIFY_RETURN 锚定宿主方法本身，"
                            + "对「宿主局部变量槽里的实参」有定义良好的读取时机；\n"
                            + "调用点类注入锚定的是方法体内的调用，宿主实参"
                            + "与栈上暂存的调用实参混在一起，参数序号有歧义。",
                    null, null);
        }

        Type[] targetArgTypes = Type.getArgumentTypes(target.descriptor());

        if (captureCount > targetArgTypes.length) {
            throw new TransformationException(
                    where + " 声明了 " + captureCount + " 个捕获参数，"
                            + "但目标方法只有 " + targetArgTypes.length + " 个实参。\n"
                            + "  目标方法: " + target,
                    null, null);
        }

        for (int i = 0; i < captureCount; i++) {
            Class<?> declared = params[captureStart + i];
            if (declared.equals(InjectionContext.class)) {
                throw new TransformationException(
                        where + " 的第 " + (captureStart + i + 1)
                                + " 个参数是 InjectionContext，但它只能作为第一个参数。\n"
                                + "捕获参数必须从目标方法的第 0 个实参开始连续声明。",
                        null, null);
            }
            String declaredDesc = typeDescriptor(declared);
            String expectedDesc = targetArgTypes[i].getDescriptor();
            if (!declaredDesc.equals(expectedDesc)) {
                throw new TransformationException(
                        where + " 的捕获参数 #" + (i + 1)
                                + "（回调第 " + (captureStart + i + 1) + " 个参数）"
                                + "与目标方法实参不匹配。\n"
                                + "  期望（目标第 " + i + " 个实参）: " + expectedDesc + "\n"
                                + "  实际声明: " + declaredDesc
                                + "（" + declared.getName() + "）\n"
                                + "  目标方法: " + target + "\n"
                                + "捕获按位严格相等：不允许装箱（int ≠ java.lang.Integer）、"
                                + "不允许子类，且必须从第 0 个实参起连续声明（前缀）。",
                        null, null);
            }
        }
    }

    /**
     * 由反射签名推导回调的 JVM 描述符。
     *
     * <p><b>推导而非手写</b> —— 手写描述符是本仓库反复记录的 bug 来源：
     * 文档里对同一方法曾有两处互相矛盾的描述符写法。手写必错。
     */
    private static String describeCallback(Class<?>[] params, Class<?> returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            sb.append(typeDescriptor(p));
        }
        sb.append(')').append(typeDescriptor(returnType));
        return sb.toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    /** 解析 {@code owner#name:descriptor}。 */
    private static org.loader.api.transform.target.TargetField parseField(
            String spec, String where) {
        try {
            int hash = spec.lastIndexOf('#');
            int colon = spec.indexOf(':', hash);
            if (hash <= 0 || colon < 0) {
                throw new TransformationException(
                        where + " 的 field 格式非法: \"" + spec + "\"\n"
                                + "应为 owner#name:descriptor", null, null);
            }
            return org.loader.api.transform.target.TargetField.of(
                    spec.substring(0, hash),
                    spec.substring(hash + 1, colon),
                    spec.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new TransformationException(
                    where + " 的 field 解析失败: \"" + spec + "\" — " + e.getMessage(),
                    null, null);
        }
    }

    /** 可用符号名列表 —— 供错误信息与诊断输出。 */
    public static java.util.Set<String> knownSymbols() {
        return SYMBOLS.keySet();
    }

    /**
     * 判定某注入点是否作用于字段访问。
     *
     * <p><b>由注入点判定，不由「有没有填 field」判定</b>：后者会让
     * 「忘记填 field」的错误退化成又一次静默失效。
     */
    private static boolean isFieldInjection(InjectionPoint point) {
        return switch (point) {
            case BEFORE_FIELD_ACCESS, AFTER_FIELD_ACCESS,
                 BEFORE_FIELD_SET, REPLACE_FIELD_ACCESS -> true;
            default -> false;
        };
    }

    /**
     * 解析 {@code owner#name(descriptor)}。
     *
     * <p>形态与 {@link TargetMethod#toString()} 一致 —— 符号表的
     * {@code toString()} 输出可以直接粘进注解，减少转录错误。
     */
    private static TargetMethod parseMethod(String spec, String where) {
        int hash = spec.indexOf('#');
        int paren = spec.indexOf('(', hash);
        if (hash <= 0 || paren < 0 || !spec.endsWith(")")) {
            throw new TransformationException(
                    where + " 的 target 格式非法: \"" + spec + "\"\n"
                            + "应为 owner#name(descriptor)，例如\n"
                            + "  net/minecraft/world/level/Level#getBlockState"
                            + "(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    null, null);
        }
        return TargetMethod.of(
                spec.substring(0, hash),
                spec.substring(hash + 1, paren),
                spec.substring(paren));
    }
}