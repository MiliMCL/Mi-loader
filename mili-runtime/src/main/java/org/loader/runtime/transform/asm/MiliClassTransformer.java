package org.loader.runtime.transform.asm;

import org.loader.api.transform.InjectionPoint;
import org.loader.api.transform.target.TargetField;
import org.loader.api.transform.target.TargetInvocation;
import org.loader.api.transform.target.TargetMethod;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Mili 类转换器 —— 所有字节码修改的载体。
 *
 * <p><b>对 Mod 完全不可见。</b>Mod 看到的是
 * {@link org.loader.api.transform.annotation.MiliInject} 这样的注解；
 * 本类属于引擎内部（ADR-0011）。它的存在意义是让 ASM 停留在平台内。
 *
 * <h2>为什么用 {@link AdviceAdapter} 而不是裸 {@code MethodVisitor}</h2>
 * 裸 {@code MethodVisitor} 需要手工管理：局部变量槽分配、栈深跟踪、
 * 构造器中未初始化 this 的处理。{@code AdviceAdapter} 把这些全部封装，
 * 且提供 {@code onMethodEnter()} / {@code onMethodExit(int)} 两个语义
 * 精确的钩子 —— 正是 HEAD 与 RETURN 注入需要的。
 *
 * <p>特别地，<b>RETURN 注入的正确性依赖 {@code onMethodExit}</b>：
 * 它会对方法内<b>每一个</b>退出路径调用回调（每个 xRETURN、以及
 * ATHROW）。若改为「找最后一个 RETURN」，任何带 early return 的方法
 * （Java 中极常见）都会漏注入。
 *
 * <h2>帧计算</h2>
 * 本类接受外部提供的 {@link ClassWriter}，由调用方决定是否
 * {@code COMPUTE_FRAMES}。默认<b>建议开启</b>：注入指令会改变栈深，
 * 手工重算帧极易出错，而 {@code COMPUTE_FRAMES} 的代价可接受。
 */
public final class MiliClassTransformer extends ClassVisitor {

    private final String className;
    private final List<MethodInjection> injections;
    private final List<AppliedInjection> applied;

    /**
     * 一次具体的注入请求。
     *
     * <p><b>字段访问类注入点（{@link InjectionPoint#BEFORE_FIELD_ACCESS} 等）
     * 用 {@code field} 而非 {@code target}</b>：方法的描述符形如
     * {@code ()V}，字段的描述符形如 {@code I}，两者格式不同。
     * 若用一个字段承载两者，比较时会出现「拿 {@code ()V} 去比 {@code I}」
     * 这种<b>永远为 false</b>的匹配 —— 表现为「字段注入静默不生效」。
     */
    public record MethodInjection(
            TargetMethod target,
            TargetField field,
            InjectionPoint point,
            String callbackDescriptor,
            TargetInvocation invocation,
            int argIndex,
            String replacementOwner,
            String replacementName,
            String replacementDescriptor,
            boolean replacementStatic,
            String transformerId,
            int priority
    ) {

        /**
         * REDIRECT 的替换目标是否为静态方法。
         *
         * <p><b>必须显式声明，不能从描述符推断</b>：JVM 描述符只描述参数与
         * 返回类型，静态方法与实例方法的描述符完全相同。从描述符推断
         * 调用形式会让「无参实例方法」被误判为静态，生成结构合法但运行期
         * 抛 {@code IncompatibleClassChangeError} 的字节码 ——
         * 那类错误堆栈指向 Minecraft 内部调用点，几乎无法反推来源。
         *
         * <p>注册期能真正检查目标方法是否为 static；到字节码生成期
         * 这一信息已不可得，因此必须在此之前确定。
         */
        public boolean isReplacementStatic() {
            return replacementStatic;
        }

        /**
         * 是否作用于字段访问（而非方法调用）。
         *
         * <p>由注入点判定，而非由 {@code field != null} 推断 —— 后者会让
         * 「忘记填 field」的错误变成又一次静默失效。
         */
        public boolean isFieldInjection() {
            return switch (point) {
                case BEFORE_FIELD_ACCESS,
                     AFTER_FIELD_ACCESS,
                     BEFORE_FIELD_SET,
                     REPLACE_FIELD_ACCESS -> true;
                default -> false;
            };
        }

        /**
         * 回调的描述符；未显式指定时为无参无返回的 {@code ()V}。
         *
         * <p><b>MODIFY_ARG / MODIFY_RETURN 需要非 {@code ()V} 的描述符</b>：
         * 它们必须返回替换后的值/参数。返回 {@code ()V} 时
         * {@link InjectionMethodVisitor} 会明确报错而非静默丢弃原值。
         */
        public String effectiveCallbackDescriptor() {
            return callbackDescriptor == null || callbackDescriptor.isBlank()
                    ? "()V" : callbackDescriptor;
        }
    }

    /** 已应用的注入记录（供冲突检测与审计）。 */
    public record AppliedInjection(
            String methodName,
            InjectionPoint point,
            String transformerId,
            String detail
    ) {
    }

    public MiliClassTransformer(
            ClassVisitor delegate,
            String className,
            List<MethodInjection> injections) {
        super(Opcodes.ASM9, delegate);
        this.className = className;
        this.injections = injections != null ? injections : List.of();
        this.applied = new ArrayList<>();
    }

    /** 本类实际应用的注入记录。 */
    public List<AppliedInjection> appliedInjections() {
        return List.copyOf(applied);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor,
            String signature, String[] exceptions) {

        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (delegate == null) {
            return null;
        }

        List<MethodInjection> targets = injectionsFor(name, descriptor);
        if (targets.isEmpty()) {
            // 无注入目标：原样返回，不包装 —— 避免无谓的开销与
            // COMPUTE_FRAMES 之外的行为变化。
            return delegate;
        }

        // ── OVERWRITE：丢弃原始方法体 ────────────────────────────────
        //
        // OVERWRITE 与其他注入点性质不同：它不是「往指令流里插东西」，
        // 而是「把指令流整个换掉」。因此必须在 visitor 层面短路 ——
        // AdviceAdapter 会忠实地把原始指令重放一遍，那样覆写就名不副实。
        List<MethodInjection> overwrites = new ArrayList<>();
        for (MethodInjection injection : targets) {
            if (injection.point() == InjectionPoint.OVERWRITE) {
                overwrites.add(injection);
            }
        }
        if (!overwrites.isEmpty()) {
            return applyOverwrite(delegate, access, name, descriptor,
                    overwrites, targets);
        }

        // 按优先级 + transformerId 排序，保证同一方法内多个注入的确定性
        List<MethodInjection> ordered = new ArrayList<>(targets);
        ordered.sort((a, b) -> {
            int p = Integer.compare(a.priority(), b.priority());
            return p != 0 ? p : a.transformerId().compareTo(b.transformerId());
        });

        return new InjectionMethodVisitor(delegate, access, name, descriptor,
                className, ordered, applied);
    }

    /**
     * 应用 {@link InjectionPoint#OVERWRITE} —— 丢弃原始方法体。
     *
     * <h2>生成的方法体是什么</h2>
     * 不是「空实现」，而是<b>参数转发</b>：
     * <pre>
     *   原方法: void tickServer(BooleanSupplier bs) { ...50 行游戏逻辑... }
     *   覆写后: void tickServer(BooleanSupplier bs) {
     *             INVOKESTATIC 平台分发器.onOverridden$tickServer(this, bs);
     *           }
     * </pre>
     * 转发的语义是<b>「原方法不再执行，由平台回调全权处理」</b>。
     * 平台分发器内部可以决定调用原实现（如果平台保留了它）还是彻底替换。
     *
     * <h2>为什么不复用原始方法体</h2>
     * 「保留原实现并可选调用」需要把原始方法体<b>重命名</b>为合成方法
     * （如 {@code tickServer$mili_original}）。技术上可行，但会带来两个
     * 难以察觉的问题：
     * <ul>
     *   <li>该合成方法仍可被反射找到 —— 某些 Mod 会遍历方法列表做注入，
     *       从而「注入了一个实际不会被调用的方法」，且不报错；</li>
     *   <li>它出现在方法列表里会污染 {@code getDeclaredMethods()} 的结果，
     *       影响依赖反射的模组（如某些映射/扫描类 Mod）。</li>
     * </ul>
     * 需求把 OVERWRITE 定义为「丢弃原始方法体，替换为回调实现」，
     * 因此这里不保留。需要「改前后都跑」的场景，正确工具是
     * {@link InjectionPoint#HEAD} + {@link InjectionPoint#RETURN}。
     *
     * <h2>为什么拒绝与其它注入共存</h2>
     * 若同时存在 {@code OVERWRITE} 与 {@code HEAD}，原始方法体已被丢弃，
     * HEAD 注入<b>永远不会执行</b>。而调用方不会得到任何提示 ——
     * 「Mod 声明了 tick 开头注入但从未执行」与「静默失效」无法区分。
     *
     * <p>因此这里<b>显式抛异常</b>。冲突检测（{@code ConflictLedger}）
     * 会在更早的预检阶段拦住它；走到这里说明检测有漏洞，
     * 宁可报错也不能生成一个「看起来注入成功、实际没跑」的类。
     */
    private MethodVisitor applyOverwrite(
            MethodVisitor delegate,
            int access,
            String name,
            String descriptor,
            List<MethodInjection> overwrites,
            List<MethodInjection> allTargets) {

        if (overwrites.size() > 1) {
            throw new org.loader.api.transform.TransformationConflictException(
                    className, name + descriptor,
                    describeAll(overwrites),
                    "同一方法上存在多个 OVERWRITE。OVERWRITE 是互斥注入点，"
                            + "且它会丢弃原始方法体 —— 同时应用两个等于随机丢弃其中一个。");
        }

        // 与非 OVERWRITE 注入共存 → 立即报错，绝不静默丢弃
        for (MethodInjection injection : allTargets) {
            if (injection.point() != InjectionPoint.OVERWRITE) {
                throw new org.loader.api.transform.TransformationException(
                        "OVERWRITE 不能与 " + injection.point() + " 共存: "
                                + className + "#" + name + descriptor + "\n"
                                + "  OVERWRITE 来自: " + injection.transformerId()
                                + "\n  冲突注入来自: "
                                + injection.transformerId() + " (" + injection.point() + ")\n"
                                + "原始方法体已被丢弃，" + injection.point()
                                + " 注入永远不会执行 —— 而不会有任何提示。\n"
                                + "请改用 HEAD + RETURN 组合。",
                        injection.transformerId(), null);
            }
        }

        MethodInjection overwrite = overwrites.get(0);
        // 先校验（描述符 / 字段误用），再写任何指令 ——
        // 校验必须发生在生成之前，否则半成品字节码会在 defineClass 时
        // 抛 VerifyError，而堆栈指向 Minecraft，看不出是哪个 Mod 的问题。
        validateOverwriteCallback(overwrite, name, descriptor);

        Type methodType = Type.getMethodType(descriptor);
        Type[] argTypes = methodType.getArgumentTypes();
        Type returnType = methodType.getReturnType();

        String callbackOwner = overwrite.replacementOwner();
        String callbackName = overwrite.replacementName();
        if (callbackOwner == null || callbackName == null) {
            throw new org.loader.api.transform.TransformationException(
                    "OVERWRITE 必须指定回调 owner 与 name: " + className + "#" + name,
                    overwrite.transformerId(), null);
        }
        // 已由 validateOverwriteCallback 保证与原方法一致，
        // 因此「回调签名 = 方法签名 + this（实例方法时）」，参数转发是恒等映射
        String cbDescriptor = overwrite.effectiveCallbackDescriptor();

        // 构造新方法体
        //
        // 用 GeneratorAdapter 而非裸 MethodVisitor：loadThis / loadArg /
        // invokeStatic / visitMaxs 都是 GeneratorAdapter 的方法
        // （它继承 LocalVariablesSorter，负责局部变量槽位分配与宽类型处理），
        // 直接写在 ClassVisitor 上不存在这些符号。
        //
        // 构造器用 (access, Method, delegate) 这一个重载：GeneratorAdapter
        // 会自行完成 visitCode / 参数槽位初始化 / max_stack 计算。
        GeneratorAdapter gen = new GeneratorAdapter(
                access, new org.objectweb.asm.commons.Method(name, descriptor), delegate);

        if ((access & Opcodes.ACC_STATIC) == 0) {
            gen.loadThis();
        }
        for (int i = 0; i < argTypes.length; i++) {
            gen.loadArg(i);
        }

        // GeneratorAdapter 的 invokeStatic/invokeVirtual 接受 Method 对象，
        // 不接受 (owner, name, descriptor) 三元组 —— 描述符已在校验阶段
        // 确认与原方法一致，这里直接复用。
        org.objectweb.asm.commons.Method callback =
                new org.objectweb.asm.commons.Method(
                        callbackName, cbDescriptor);

        // Type.getObjectType(String) 的参数是<b>内部名</b>（斜杠分隔），
        // 不是点分名 —— 它会把传入的字符串原样写进常量池的类名项。
        //
        // 曾经的 bug：这里写了 callbackOwner.replace('/', '.')，
        // 于是常量池里存的是
        //   org.loader.runtime.transform.OverwriteDispatch
        // 而 JVM 按斜杠形式解析类名 →
        //   defineClass 时报 ClassFormatError，或运行期 NoClassDefFoundError。
        //
        // 为什么它能一路绿灯跑到运行期：ClassReader 把它当普通字符串读出来，
        // 结构校验（CheckClassAdapter）只看「类名项是否合法 UTF8」，
        // 不检查「这个类是否真的存在」——
        // 于是一个指向不存在类的字节码可以完全合法。
        //
        // 而 SyntheticClassLoader 之类的测试若按斜杠匹配 owner，
        // 就会断言「回调调用数为 0」，把一个运行期崩溃伪装成
        // 「注入没生效」。这类缺陷必须在实现侧修，
        // 靠测试去猜「到底哪个分隔符对」是靠不住的。
        Type callbackType = Type.getObjectType(callbackOwner);

        if (overwrite.isReplacementStatic()) {
            //静态回调不接收 this —— 参数列表必须与方法参数一致（不含 this）
            if ((access & Opcodes.ACC_STATIC) != 0) {
                gen.invokeStatic(callbackType, callback);
            } else {
                throw new org.loader.api.transform.TransformationException(
                        "OVERWRITE 的目标方法是实例方法，回调不能声明为 static。\n"
                                + "  目标: " + className + "#" + name + descriptor + "\n"
                                + "  回调: " + callbackOwner + "." + callbackName
                                + cbDescriptor + "（声明为 static）\n"
                                + "实例方法的回调需要接收 this —— 转发的参数列表"
                                + "因此与实例方法一致。",
                        overwrite.transformerId(), null);
            }
        } else {
            if ((access & Opcodes.ACC_STATIC) == 0) {
                gen.invokeVirtual(callbackType, callback);
            } else {
                throw new org.loader.api.transform.TransformationException(
                        "OVERWRITE 的目标方法是静态方法，回调必须是静态方法。\n"
                                + "静态方法的回调无法接收 this。",
                        overwrite.transformerId(), null);
            }
        }

        // 返回：回调的返回值即方法的返回值
        if (returnType.getSort() == Type.VOID) {
            gen.visitInsn(Opcodes.RETURN);
        } else {
            // Type.getReturnType 已在上面校验过与回调一致，
            // 因此这里按 returnType 的 sort 分派 xRETURN 是正确的
            gen.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
        }

        // visitMaxs(0, 0) 在 COMPUTE_MAXS / COMPUTE_FRAMES 下由
        // ClassWriter 事后计算；GeneratorAdapter 会正确转发。
        gen.visitMaxs(0, 0);
        gen.visitEnd();

        applied.add(new AppliedInjection(name, InjectionPoint.OVERWRITE,
                overwrite.transformerId(),
                "原始方法体已丢弃，替换为回调 " + callbackOwner + "." + callbackName));

        return null;
    }

    /**
     * 校验 OVERWRITE 的回调签名。
     *
     * <p>必须在生成任何指令之前完成 —— 字节码一旦写了一半才发现签名不对，
     * 产出的类会以 {@code VerifyError} 的形式在 {@code defineClass} 时炸掉，
     * 而堆栈指向 Minecraft，完全看不出是哪个 Mod 的哪个覆写出了问题。
     */
    private void validateOverwriteCallback(
            MethodInjection overwrite, String name, String descriptor) {
        if (overwrite.field() != null) {
            throw new org.loader.api.transform.TransformationException(
                    "OVERWRITE 不能同时指定字段目标: " + className + "#" + name,
                    overwrite.transformerId(), null);
        }
        String cb = overwrite.effectiveCallbackDescriptor();
        if (!cb.equals(descriptor)) {
            throw new org.loader.api.transform.TransformationException(
                    "OVERWRITE 的回调签名必须与原方法完全一致。\n"
                            + "  原方法: " + descriptor + "\n"
                            + "  回调:   " + cb + "\n"
                            + "  覆写丢弃了原方法体，回调是唯一实现 —— "
                            + "签名不一致无法做参数转发。",
                    overwrite.transformerId(), null);
        }
    }

    /** 冲突异常需要转换器 id 列表 —— 它会写入异常的 transformerIds 字段供审计查询。 */
    private static List<String> describeAll(List<MethodInjection> injections) {
        List<String> ids = new ArrayList<>(injections.size());
        for (MethodInjection injection : injections) {
            ids.add(injection.transformerId());
        }
        return ids;
    }

    /** 筛选作用于指定方法的注入。 */
    private List<MethodInjection> injectionsFor(String name, String descriptor) {
        List<MethodInjection> result = new ArrayList<>();
        for (MethodInjection injection : injections) {
            if (injection.isFieldInjection()) {
                // 字段注入不按方法名/方法描述符筛选 —— 它的锚点是字段三元组，
                // 由 InjectionMethodVisitor.matchesField 在遇到该字段指令时判定。
                result.add(injection);
                continue;
            }
            TargetMethod target = injection.target();
            if (target == null) {
                continue;
            }
            if (target.name().equals(name) && target.descriptor().equals(descriptor)) {
                result.add(injection);
            }
        }
        return result;
    }

    /**
     * 静态工厂：由原始字节码与转换计划构建一个已应用转换的 ClassNode。
     *
     * <p>便于 Pipeline 使用：读 → 改 → 写。
     *
     * @param original    原始字节码
     * @param className   类内部名
     * @param injections  注入计划
     * @return 转换后的字节码
     */
    public static byte[] apply(
            byte[] original,
            String className,
            List<MethodInjection> injections) {

        ClassReader reader = new ClassReader(original);
        // COMPUTE_FRAMES：注入会改变栈深，重算帧比手工维护可靠得多。
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);

        reader.accept(new MiliClassTransformer(writer, className, injections),
                ClassReader.EXPAND_FRAMES);

        return writer.toByteArray();
    }
}