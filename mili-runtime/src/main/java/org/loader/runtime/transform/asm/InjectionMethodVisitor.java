package org.loader.runtime.transform.asm;

import org.loader.api.transform.InjectionPoint;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.util.List;

/**
 * 注入方法访问器 —— 单个方法内的全部注入落地处。
 *
 * <p>基于 {@link AdviceAdapter}，它保证了：
 * <ul>
 *   <li>局部变量槽的正确分配（{@link #newLocal}）；</li>
 *   <li>构造器中未初始化 {@code this} 的处理；</li>
 *   <li><b>{@link #onMethodEnter()} 只在真正的方法体开始处调用一次</b>；</li>
 *   <li><b>{@link #onMethodExit(int)} 对每一个退出路径调用一次</b> ——
 *       这是 RETURN 注入正确性的根本。</li>
 * </ul>
 *
 * <h2>RETURN 注入为什么必须用 onMethodExit</h2>
 * {@code onMethodExit} 会对方法内每个 xRETURN 与 ATHROW 触发回调。
 * 若改用「扫描指令列表、只在最后一个 RETURN 前插入」，任何带
 * early return 的方法都会漏注入 —— 而带 early return 的方法在
 * Minecraft 里极其常见（null 检查失败即 return 是标准写法）。
 * 更糟的是漏注入不报错，只是 Mod 的逻辑<b>在部分路径上不执行</b>。
 *
 * <h2>回调描述符约定</h2>
 * 注入的指令是 {@code INVOKESTATIC <callbackOwner>.<callbackName>(<args>)V}，
 * 参数由各注入点决定。本类只负责保证<b>栈平衡</b>：
 * 插入的指令必须在插入点前后保持操作数栈高度不变。
 */
final class InjectionMethodVisitor extends AdviceAdapter {

    private final String owner;
    private final String methodName;
    private final String methodDescriptor;
    /** 宿主方法的 access 标志 —— 判断静态性与是否能传 {@code this}。 */
    private final int methodAccess;
    private final List<MiliClassTransformer.MethodInjection> injections;
    private final List<MiliClassTransformer.AppliedInjection> applied;

    /**
     * 调用指令 ordinal 计数 —— <b>按签名分桶</b>。
     *
     * <p>契约（{@link TargetInvocation}）：ordinal「按字节码顺序计数
     * <b>同签名</b>的调用」。方法体内混着不同签名的调用时，
     * 全局序号与契约序号不一致，「第 N 次调用 X」会定位到错误的指令。
     *
     * <p>key 为 {@code owner#name#descriptor}。
     */
    private final java.util.Map<String, int[]> ordinalBySignature =
            new java.util.HashMap<>();

    /**
     * 已实际命中「至少一条指令」的注入。
     *
     * <p>用于方法体结束时的「声明了却没命中」检测。
     */
    private final java.util.Set<MiliClassTransformer.MethodInjection> matched =
            java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<>());

    InjectionMethodVisitor(
            MethodVisitor delegate,
            int access,
            String name,
            String descriptor,
            String owner,
            List<MiliClassTransformer.MethodInjection> injections,
            List<MiliClassTransformer.AppliedInjection> applied) {
        super(Opcodes.ASM9, delegate, access, name, descriptor);
        this.owner = owner;
        this.methodName = name;
        this.methodDescriptor = descriptor;
        this.methodAccess = access;
        this.injections = injections;
        this.applied = applied;

        // 可取消性在注册期就该拦下；这里再防御一次 —— 直接构造
        // MethodInjection（绕过扫描器）的调用方不受扫描器校验保护。
        // 实参捕获的支持范围同理。
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.cancellable()
                    && injection.point() != InjectionPoint.HEAD
                    && injection.point() != InjectionPoint.BEFORE_INVOKE) {
                throw new org.loader.api.transform.TransformationException(
                        "可取消注入仅支持 HEAD / BEFORE_INVOKE，实际: "
                                + injection.point() + " by " + injection.transformerId(),
                        injection.transformerId(), null);
            }
            if (captureArgCount(injection.effectiveCallbackDescriptor()) > 0
                    && !supportsArgCapture(injection.point())) {
                throw new org.loader.api.transform.TransformationException(
                        "实参捕获仅支持 HEAD / RETURN / MODIFY_RETURN，实际: "
                                + injection.point() + " by " + injection.transformerId()
                                + "\n  回调描述符: "
                                + injection.effectiveCallbackDescriptor()
                                + "\n  调用点类注入锚定方法体内的调用，宿主实参"
                                + "与栈上暂存的调用实参混在一起，参数序号有歧义。",
                        injection.transformerId(), null);
            }
        }
    }

    /**
     * 取出本次调用在其签名桶内的序号，并递增该桶。
     *
     * <p>必须在每条 {@code visitMethodInsn} 开头调用一次，
     * 且<b>无论是否命中注入都要调用</b> —— 否则序号会因注入与否而漂移，
     * 同一个类的转换结果取决于「哪些注入先被处理」，
     * 那是无法复现的顺序依赖。
     */
    private int nextOrdinal(String owner, String name, String descriptor) {
        String key = owner + '#' + name + '#' + descriptor;
        int[] counter = ordinalBySignature.computeIfAbsent(key, k -> new int[1]);
        return counter[0]++;
    }

    @Override
    protected void onMethodEnter() {
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() == InjectionPoint.HEAD) {
                emitHead(injection);
            }
        }
    }

    @Override
    protected void onMethodExit(int opcode) {
        // 正常返回路径：处理 RETURN 注入与 MODIFY_RETURN。
        //
        // 两者都必须走这里 —— onMethodExit 对每个 xRETURN 触发一次，
        // 这是「RETURN 注入不漏 early return」的根本保证。
        if (opcode == ATHROW) {
            // 异常路径：不追加回调。METHOD_RETURN 语义指正常返回。
            return;
        }

        Type returnType = Type.getReturnType(methodDescriptor);

        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() == InjectionPoint.MODIFY_RETURN) {
                emitModifyReturn(injection, returnType);
                record(injection, "MODIFY_RETURN");
            }
        }

        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() == InjectionPoint.RETURN) {
                emitReturn(injection, returnType);
                record(injection, "RETURN");
            }
        }
    }

    // ── HEAD / RETURN ────────────────────────────────────────────────

    /**
     * 方法入口注入。
     *
     * <p>可取消形式：回调返回后检查 {@code isCancelled()}，取消则以
     * 默认值立即返回 —— 方法体的其余部分不执行。分支结构保证了
     * 可达性：{@code IFEQ} 直接跳到「取消路径返回之后的正常入口」，
     * 原方法体不会变成不可达代码。
     */
    private void emitHead(MiliClassTransformer.MethodInjection injection) {
        if (injection.cancellable()) {
            Type returnType = Type.getReturnType(methodDescriptor);
            emitCancellableCallback(injection);
            org.objectweb.asm.Label continueLabel = new org.objectweb.asm.Label();
            visitJumpInsn(Opcodes.IFEQ, continueLabel);
            pushDefaultValue(returnType);
            visitInsn(returnType.getSort() == Type.VOID
                    ? Opcodes.RETURN : properReturnOpcode(returnType));
            visitLabel(continueLabel);
            record(injection, "HEAD(cancellable)");
        } else {
            pushCallback(injection);
            record(injection, "HEAD");
        }
    }

    /** 按返回类型选择正确的返回指令。 */
    private static int properReturnOpcode(Type returnType) {
        return switch (returnType.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.IRETURN;
            case Type.LONG -> Opcodes.LRETURN;
            case Type.FLOAT -> Opcodes.FRETURN;
            case Type.DOUBLE -> Opcodes.DRETURN;
            default -> Opcodes.ARETURN;
        };
    }

    /**
     * 按返回类型压入默认值（取消路径的返回值）。
     *
     * <p>与 JVM 语义一致：引用 → {@code null}，数值/布尔 → {@code 0}，
     * long/float/double → 对应零常量。void 不压任何东西。
     */
    private void pushDefaultValue(Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> { }
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT ->
                    visitInsn(Opcodes.ICONST_0);
            case Type.LONG -> visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> visitInsn(Opcodes.DCONST_0);
            default -> visitInsn(Opcodes.ACONST_NULL);
        }
    }

    /**
     * 发出一个可取消回调：创建上下文（存入局部变量）→ 调用回调
     * → 加载上下文 → 读取取消标志。
     *
     * <p>栈效果：回调为 void（注册期已强制），栈高度不变；
     * 结束后栈顶是 {@code int} 型取消标志，调用方必须立即消费。
     *
     * <h2>为什么不能委托 {@link #pushCallback}（曾经的 AIOOBE 根因）</h2>
     * {@code pushCallback} 会把「构造 ctx」与「调用回调」一起发出。
     * 可取消回调是 {@code (ctx)V} —— 回调消费掉栈上的 ctx 后栈已空，
     * 随后的 {@code ASTORE} 就在从<b>空栈</b>弹值。ASM 的帧模拟对空栈
     * {@code pop} 打 {@code STACK_KIND} 前缀标记，该标记进入局部变量后，
     * 在分支合并处被解析为 {@code inputStack[-1]} —— 转换期直接抛
     * {@code ArrayIndexOutOfBoundsException}（而非运行期 VerifyError），
     * 堆栈指向 {@code Frame.getConcreteOutputType}，与生成代码的形状
     * 完全对不上。正确顺序是：ctx 先入槽，回调以槽内副本为实参调用，
     * 再重读槽内 ctx 查询取消标志。
     */
    private void emitCancellableCallback(MiliClassTransformer.MethodInjection injection) {
        String callbackOwner = injection.replacementOwner();
        if (callbackOwner == null) {
            throw new org.loader.api.transform.TransformationException(
                    "可取消注入缺少回调目标（replacementOwner 为 null）: "
                            + injection.point() + " by " + injection.transformerId(),
                    injection.transformerId(), null);
        }
        String callbackDescriptor = injection.effectiveCallbackDescriptor();
        Type ctxType = CONTEXT_TYPE;
        int slot = newLocal(ctxType);

        // 1. 只构造 ctx（emitContextArgument 按 cancellable 选择
        //    forCancellableMethod）并存入局部变量 —— 此处绝不调用回调。
        emitContextArgument(injection);
        storeLocal(slot, ctxType);

        // 2. ctx 副本入栈（+ 捕获实参），调用 void 回调 —— 副本被消费，栈清空。
        loadLocal(slot, ctxType);
        pushCallbackArguments(injection, callbackDescriptor, true);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, callbackOwner,
                injection.replacementName() != null
                        ? injection.replacementName() : "onInject",
                callbackDescriptor, false);

        // 3. 重新加载槽内 ctx 读取取消标志 —— 栈顶留下 int，调用方立即消费。
        loadLocal(slot, ctxType);
        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(org.loader.api.transform.callback.InjectionContext.class),
                "isCancelled", "()Z", false);
    }

    /**
     * 方法返回注入。
     *
     * <p><b>关键顺序问题</b>：若回调需要访问返回值，返回值此时在栈顶。
     * 但当前回调签名为 {@code ()V} —— 不消费任何值。为保持栈平衡，
     * 对有返回值的非 void 方法，插入回调前必须把返回值挪开，
     * 插入后再挪回来；否则 {@code V} 回调会留下一个悬空值，
     * 直接导致 {@code VerifyError}。
     */
    private void emitReturn(MiliClassTransformer.MethodInjection injection, Type returnType) {
        if (returnType.getSort() == Type.VOID) {
            // void：栈上没有返回值，直接插入
            pushCallback(injection);
        } else {
            // 非 void：暂存 → 回调 → 取回，保证栈高度不变。
            int slot = newLocal(returnType);
            storeLocal(slot, returnType);
            pushCallback(injection);
            loadLocal(slot, returnType);
        }
    }

    /**
     * {@link InjectionPoint#MODIFY_RETURN} —— 改写返回值。
     *
     * <p>语义：原返回值 → 回调替换 → 返回新值。
     *
     * <p><b>void 方法不适用</b>。此处若遇到 void 直接抛异常而非静默跳过 ——
     * 平台在注册阶段就应拒绝这类声明，走到此处说明注册校验有漏洞，
     * 而静默跳过会让「Mod 以为改写了返回值、实际什么都没发生」，
     * 且没有任何错误提示。
     *
     * <h2>为什么必须校验回调返回类型</h2>
     * 栈顶是原返回值。若回调返回 {@code ()V}，把回调插进去后栈上
     * <b>什么值都没有</b>，而紧接着的 {@code xRETURN} 需要一个值 ——
     * 这不是「不生效」，而是直接 {@code VerifyError}，且堆栈指向
     * Minecraft 代码。因此必须在生成期就拒绝。
     *
     * @param returnType 方法返回类型
     */
    private void emitModifyReturn(MiliClassTransformer.MethodInjection injection,
                                  Type returnType) {
        if (returnType.getSort() == Type.VOID) {
            throw new org.loader.api.transform.TransformationException(
                    "MODIFY_RETURN 不能用于 void 方法 " + owner + "#" + methodName,
                    injection.transformerId(), null);
        }
        String cbDescriptor = injection.effectiveCallbackDescriptor();
        if (!Type.getReturnType(cbDescriptor).equals(returnType)) {
            throw new org.loader.api.transform.TransformationException(
                    "MODIFY_RETURN 要求回调返回方法返回类型 " + returnType
                            + "，但声明的回调描述符是 " + cbDescriptor + "\n"
                            + "  方法: " + owner + "#" + methodName + methodDescriptor
                            + "\n  若回调为 ()V，插入后栈上无返回值，"
                            + "紧随其后的 xRETURN 会抛 VerifyError。",
                    injection.transformerId(), null);
        }
        // 栈顶当前是原返回值。回调把它作为参数消费并产出新值，
        // 栈高度净变化为 0：原值 1 个 → 新值 1 个。
        pushCallback(injection);
    }

    // ── 调用点注入（BEFORE_INVOKE / AFTER_INVOKE / REDIRECT / MODIFY_ARG） ──

    /**
     * 一个调用点上的全部处理。
     *
     * <h2>顺序是强制的，不是风格选择</h2>
     * {@code BEFORE_INVOKE} → {@code MODIFY_ARG} → 调用 → {@code AFTER_INVOKE}
     * 相邻步骤之间不能调换：
     * <ul>
     *   <li>MODIFY_ARG <b>必须在调用指令发出之前</b>完成。它的工作是
     *       「把已压在栈上的实参暂存再重放」；若调用已发出，暂存到的就是
     *       返回值而不是实参 —— 生成出结构合法、语义完全错误的字节码，
     *       且校验器抓不到（栈深仍然平衡）。</li>
     *   <li>AFTER_INVOKE 必须在调用之后，且要用<b>实际发出的</b>描述符判断
     *       返回类型 —— REDIRECT 可能改变返回类型（典型：原 {@code ()V}
     *       改为返回 {@code I}）。用原描述符会让栈平衡算错，
     *       把返回值当成不存在而直接插入回调顶掉它。</li>
     * </ul>
     */
    @Override
    public void visitMethodInsn(int opcode, String callOwner, String callName,
                                String callDescriptor, boolean isInterface) {

        int myOrdinal = nextOrdinal(callOwner, callName, callDescriptor);

        // ── 1. 普通与非普通 BEFORE_INVOKE 分桶 ─────────────────────
        java.util.List<MiliClassTransformer.MethodInjection> beforeCancellable =
                new java.util.ArrayList<>();
        java.util.List<MiliClassTransformer.MethodInjection> beforePlain =
                new java.util.ArrayList<>();
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.BEFORE_INVOKE
                    || !matches(injection, callOwner, callName, callDescriptor,
                            opcode, myOrdinal)) {
                continue;
            }
            (injection.cancellable() ? beforeCancellable : beforePlain)
                    .add(injection);
        }

        // 普通 BEFORE：实参仍在栈上、调用尚未发生，回调 ()V 栈高不变。
        for (MiliClassTransformer.MethodInjection injection : beforePlain) {
            pushCallback(injection);
            record(injection, "BEFORE_INVOKE");
        }

        // ── 2. REDIRECT 决策（先算出最终形式，不立即发出） ──────────
        // 必须先确定：MODIFY_ARG 要按最终描述符校验参数，
        // AFTER_INVOKE 要按最终描述符判断返回类型。
        MiliClassTransformer.MethodInjection redirect = null;
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.REDIRECT) {
                continue;
            }
            if (!matches(injection, callOwner, callName, callDescriptor,
                    opcode, myOrdinal)) {
                continue;
            }
            if (redirect != null) {
                // 两个 REDIRECT 命中同一点 —— 冲突检测应已在 Pipeline 拦住。
                // 走到这里说明检测有漏洞，必须炸而不是静默取一个。
                throw new org.loader.api.transform.TransformationConflictException(
                        owner, methodName,
                        java.util.List.of(redirect.transformerId(),
                                injection.transformerId()),
                        "同一调用点被多次 REDIRECT（冲突检测未拦截）");
            }
            redirect = injection;
        }

        int finalOpcode;
        String finalOwner;
        String finalName;
        String finalDescriptor;
        if (redirect != null) {
            finalOpcode = injectionBytecodeOpcode(opcode, isInterface,
                    redirect.isReplacementStatic());
            finalOwner = redirect.replacementOwner();
            finalName = redirect.replacementName();
            finalDescriptor = redirect.replacementDescriptor();
            if (finalOwner == null || finalDescriptor == null) {
                throw new org.loader.api.transform.TransformationException(
                        "REDIRECT 缺少替换目标（owner 或 descriptor 为 null）: "
                                + callOwner + "#" + callName,
                        redirect.transformerId(), null);
            }
        } else {
            finalOpcode = opcode;
            finalOwner = callOwner;
            finalName = callName;
            finalDescriptor = callDescriptor;
        }

        // ── 3. 可取消 BEFORE_INVOKE：暂存实参 → 回调 → 取消判定 ────
        if (!beforeCancellable.isEmpty()) {
            // 与 MODIFY_ARG 互斥：两者都要「暂存全部实参再重放」，
            // 叠放会双重暂存，生成结构合法但语义错误的字节码。
            for (MiliClassTransformer.MethodInjection injection : injections) {
                if (injection.point() == InjectionPoint.MODIFY_ARG
                        && matches(injection, callOwner, callName, callDescriptor,
                                opcode, myOrdinal)) {
                    throw new org.loader.api.transform.TransformationException(
                            "同一调用点不能同时声明可取消 BEFORE_INVOKE 与 MODIFY_ARG"
                                    + "（双重暂存会生成语义错误的字节码）: "
                                    + finalOwner + "#" + finalName,
                            injection.transformerId(), null);
                }
            }
            emitCancellableBeforeInvoke(beforeCancellable, redirect,
                    finalOpcode, finalOwner, finalName,
                    finalDescriptor, isInterface);
        } else {
            // ── 3'. MODIFY_ARG（必须在调用发出之前） ────────────────
            for (MiliClassTransformer.MethodInjection injection : injections) {
                if (injection.point() != InjectionPoint.MODIFY_ARG) {
                    continue;
                }
                if (!matches(injection, callOwner, callName, callDescriptor,
                        opcode, myOrdinal)) {
                    continue;
                }
                modifyArgument(injection, finalDescriptor);
                record(injection, "MODIFY_ARG idx=" + injection.argIndex()
                        + " of " + finalOwner + "#" + finalName + finalDescriptor);
            }

            // ── 4. 发出调用 ─────────────────────────────────────────
            super.visitMethodInsn(finalOpcode, finalOwner, finalName,
                    finalDescriptor, isInterface);

            if (redirect != null) {
                record(redirect, "REDIRECT " + callOwner + "#" + callName
                        + callDescriptor + " -> " + finalOwner + "#"
                        + finalName + finalDescriptor);
            }
        }

        // ── 5. AFTER_INVOKE ────────────────────────────────────────
        // 取消路径同样会走到这里：栈顶是被跳过调用的默认返回值。
        // 这是刻意的语义 —— 「调用没有发生」本身就是 AFTER 段需要
        // 能感知的事实，而栈平衡不允许我们跳过后又凭空造值。
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.AFTER_INVOKE) {
                continue;
            }
            if (!matches(injection, callOwner, callName, callDescriptor,
                    opcode, myOrdinal)) {
                continue;
            }
            Type retType = Type.getReturnType(finalDescriptor);
            if (retType.getSort() == Type.VOID) {
                pushCallback(injection);
            } else {
                int slot = newLocal(retType);
                storeLocal(slot, retType);
                pushCallback(injection);
                loadLocal(slot, retType);
            }
            record(injection, "AFTER_INVOKE " + finalOwner + "#" + finalName);
        }
    }

    /**
     * 可取消 {@code BEFORE_INVOKE} 的完整指令结构。
     *
     * <pre>
     *   倒序暂存全部实参（含接收者）
     *   cb1: 创建可取消 ctx → 调用 → 读 isCancelled；IFNE → cancelled
     *   cb2: （同上，链式）
     *   未取消：重放实参 → 发出（可能已被 REDIRECT 的）调用 → GOTO after
     *   cancelled:
     *     压入被跳过调用的默认返回值
     *   after:
     *     （AFTER_INVOKE 由此后的公共代码处理）
     * </pre>
     *
     * <h2>为什么与 MODIFY_ARG 互斥</h2>
     * 两者都要做「暂存全部实参再重放」。叠放会双重暂存：
     * 第一轮重放后栈上已是回调产物，第二轮暂存到的是错误数据 ——
     * 生成的字节码结构合法（栈深平衡），语义完全错误。
     * 与其生成静默损坏的代码，不如在生成期拒绝组合。
     *
     * <h2>取消后的 AFTER_INVOKE</h2>
     * 取消时压入的是默认返回值而非真实结果。AFTER 段的处理程序
     * 无法区分两者 —— 这是文档化语义：取消即「假装调用以默认值完成」。
     */
    private void emitCancellableBeforeInvoke(
            java.util.List<MiliClassTransformer.MethodInjection> cancellable,
            MiliClassTransformer.MethodInjection redirect,
            int finalOpcode, String finalOwner,
            String finalName, String finalDescriptor, boolean isInterface) {

        Type[] argTypes = Type.getArgumentTypes(finalDescriptor);
        boolean instanceCall = finalOpcode != Opcodes.INVOKESTATIC;

        // ── 1. 倒序暂存：先实参，最后接收者 ─────────────────────────
        int[] argSlots = new int[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            argSlots[i] = newLocal(argTypes[i]);
            storeLocal(argSlots[i], argTypes[i]);
        }
        int recvSlot = -1;
        if (instanceCall) {
            recvSlot = newLocal(Type.getType(Object.class));
            storeLocal(recvSlot, Type.getType(Object.class));
        }

        // ── 2. 链式可取消回调 ────────────────────────────────────────
        // 每个回调结束后立即读取其取消标志：任一回调取消即短路。
        Label cancelledLabel = new Label();
        Label afterLabel = new Label();
        for (MiliClassTransformer.MethodInjection injection : cancellable) {
            emitCancellableCallback(injection);
            visitJumpInsn(Opcodes.IFNE, cancelledLabel);
        }

        // ── 3. 未取消：重放并发出调用 ────────────────────────────────
        if (instanceCall) {
            loadLocal(recvSlot, Type.getType(Object.class));
        }
        for (int i = 0; i < argTypes.length; i++) {
            loadLocal(argSlots[i], argTypes[i]);
        }
        super.visitMethodInsn(finalOpcode, finalOwner, finalName,
                finalDescriptor, isInterface);
        if (redirect != null) {
            record(redirect, "REDIRECT (cancellable) " + finalOwner
                    + "#" + finalName + finalDescriptor);
        }
        visitJumpInsn(Opcodes.GOTO, afterLabel);

        // ── 4. 取消路径：默认返回值 ──────────────────────────────────
        visitLabel(cancelledLabel);
        pushDefaultValue(Type.getReturnType(finalDescriptor));
        visitLabel(afterLabel);

        for (MiliClassTransformer.MethodInjection injection : cancellable) {
            record(injection, "BEFORE_INVOKE(cancellable) " + finalOwner
                    + "#" + finalName);
        }
    }

    /**
     * 方法体读完 —— 检查「声明了调用点却从未命中」。
     *
     * <h2>为什么这是第三种必须报错的失效模式</h2>
     * 调用点类注入（{@code BEFORE_INVOKE} / {@code AFTER_INVOKE} /
     * {@code REDIRECT} / {@code MODIFY_ARG}）的匹配发生在
     * {@link #visitMethodInsn} 里。若方法体内<b>根本没有</b>目标调用，
     * 匹配只会返回 false —— 不抛异常、不产出字节码、日志无痕。
     *
     * <p>表现是：Mod 声明「在所有 {@code entity.die()} 之前插一刀」，
     * 转换成功、验证通过、游戏正常运行，
     * <b>而 Mod 的逻辑一次都没执行</b>。这与「Mod 写了 bug」无法区分，
     * 且比抛异常难查一个数量级。
     *
     * <h2>为什么用 visitMaxs 作为检查点</h2>
     * {@code visitMaxs} 是 ASM 保证「该方法所有指令都已访问完」的回调
     * （{@code Code} 属性里 {@code max_stack}/{@code max_locals} 紧随指令之后）。
     * 在这里判定「全部指令已见过」，不会漏掉分支路径上的指令 ——
     * 字节码里没有「路径」，只有线性指令序列。
     *
     * <h2>只对「声明了 invocation」的注入要求命中</h2>
     * {@code HEAD} / {@code RETURN} / 字段类注入的锚点是方法或字段本身，
     * 不依赖具体调用点。它们由 {@code onMethodEnter} /
     * {@code onMethodExit} 或字段指令触发，不适用本检查。
     */
    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.invocation() == null) {
                continue;
            }
            if (matched.contains(injection)) {
                continue;
            }
            throw org.loader.api.transform.TransformationTargetNotFoundException.forCallSite(
                    owner, methodName, methodDescriptor,
                    injection.invocation(), injection.transformerId());
        }
        // MODIFY_CONSTANT 的锚点是方法体内的常量 —— 与调用点同类：
        // 常量不存在时匹配永远失败且无任何痕迹，必须在生成期报错。
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() == InjectionPoint.MODIFY_CONSTANT
                    && injection.constant() != null
                    && !matched.contains(injection)) {
                throw new org.loader.api.transform.TransformationException(
                        "MODIFY_CONSTANT 声明的常量 \"" + injection.constant()
                                + "\" 在方法 " + owner + "#" + methodName
                                + methodDescriptor + " 中不存在。\n"
                                + "  与其静默不生效，不如加载期失败 —— 症状写明原因。",
                        injection.transformerId(), null);
            }
        }
        super.visitMaxs(maxStack, maxLocals);
    }

    @Override
    public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
                               String fieldDescriptor) {

        // 字段指令在「之前」的栈形态与在「之后」完全不同，
        // 且四种 opcode 互不相同。这是本类最容易写错的地方，
        // 因此用一个显式的小模型描述，而不是靠 if 猜。
        //
        //   opcode      before(栈,底→顶)          after(栈,底→顶)
        //   GETFIELD    [objref]                   [objref, value]
        //   PUTFIELD    [objref, value]            []
        //   GETSTATIC   []                         [value]
        //   PUTSTATIC   [value]                    []
        //
        // 结论：before 栈上的操作数个数
        //   instance 读 → 1（只有 objref，没有 value！）
        //   instance 写 → 2
        //   static 读   → 0   ← 此处最容易错：GETSTATIC 之前栈是空的
        //   static 写   → 1
        boolean isRead = opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC;
        boolean isInstance = opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD;

        // ── BEFORE_FIELD_ACCESS / BEFORE_FIELD_SET ──────────────────
        for (MiliClassTransformer.MethodInjection injection : injections) {
            InjectionPoint point = injection.point();
            boolean beforeSet = point == InjectionPoint.BEFORE_FIELD_SET;
            boolean beforeAccess = point == InjectionPoint.BEFORE_FIELD_ACCESS;
            if (!beforeSet && !beforeAccess) {
                continue;
            }
            // BEFORE_FIELD_SET 只关心写；BEFORE_FIELD_ACCESS 两种都算
            if (beforeSet && isRead) {
                continue;
            }
            if (!matchesField(injection, fieldOwner, fieldName, fieldDescriptor, opcode)) {
                continue;
            }
            int[] slots = stashFieldOperands(isInstance, isRead, fieldDescriptor);
            pushCallback(injection);
            reloadFieldOperands(slots, isInstance, isRead, fieldDescriptor);
            record(injection, beforeSet ? "BEFORE_FIELD_SET" : "BEFORE_FIELD_ACCESS");
        }

        // ── REPLACE_FIELD_ACCESS ─────────────────────────────────────
        boolean replaced = false;
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.REPLACE_FIELD_ACCESS) {
                continue;
            }
            if (!matchesField(injection, fieldOwner, fieldName, fieldDescriptor, opcode)) {
                continue;
            }
            if (replaced) {
                throw new org.loader.api.transform.TransformationConflictException(
                        owner, methodName,
                        java.util.List.of(injection.transformerId()),
                        "同一字段访问被多次 REPLACE（冲突检测未拦截）");
            }
            super.visitFieldInsn(opcode,
                    injection.replacementOwner() != null
                            ? injection.replacementOwner() : fieldOwner,
                    injection.replacementName() != null
                            ? injection.replacementName() : fieldName,
                    injection.replacementDescriptor() != null
                            ? injection.replacementDescriptor() : fieldDescriptor);
            replaced = true;
            record(injection, "REPLACE_FIELD_ACCESS " + fieldOwner + "#" + fieldName);
        }

        if (!replaced) {
            super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDescriptor);
        }

        // ── AFTER_FIELD_ACCESS ───────────────────────────────────────
        // 读操作之后栈顶是字段值；写操作之后栈是空的。
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.AFTER_FIELD_ACCESS) {
                continue;
            }
            if (!matchesField(injection, fieldOwner, fieldName, fieldDescriptor, opcode)) {
                continue;
            }
            if (isRead) {
                Type fieldType = Type.getType(fieldDescriptor);
                int slot = newLocal(fieldType);
                storeLocal(slot, fieldType);
                pushCallback(injection);
                loadLocal(slot, fieldType);
            } else {
                // PUTFIELD / PUTSTATIC 之后没有值，直接插入
                pushCallback(injection);
            }
            record(injection, "AFTER_FIELD_ACCESS");
        }
    }

    // ── MODIFY_CONSTANT ──────────────────────────────────────────────

    /**
     * 常量替换 —— 与 {@code Mixin}@ModifyConstant 同语义。
     *
     * <p>匹配规则：按回调返回类型解析声明的常量字符串，
     * 只替换<b>同类型且等值</b>的 {@code LDC}；方法体内所有匹配处
     * 都会被替换。类字面量（{@code Type}/{@code Handle}）不参与匹配。
     *
     * <p>栈效果：原 LDC 压入一个 {@code T}，回调也返回一个 {@code T}
     * —— 替换前后栈高度与类型完全一致，无需额外平衡。
     */
    @Override
    public void visitLdcInsn(Object value) {
        MiliClassTransformer.MethodInjection patch = matchConstant(value);
        if (patch == null) {
            super.visitLdcInsn(value);
            return;
        }
        pushCallback(patch);
        record(patch, "MODIFY_CONSTANT " + patch.constant());
    }

    /** 找到与该 LDC 值匹配的 MODIFY_CONSTANT 注入；无则 null。 */
    private MiliClassTransformer.MethodInjection matchConstant(Object value) {
        if (!(value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof String)) {
            return null;    // 类字面量 / MethodType 等不参与匹配
        }
        MiliClassTransformer.MethodInjection found = null;
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() != InjectionPoint.MODIFY_CONSTANT) {
                continue;
            }
            Object expected = parseConstant(injection);
            if (expected == null) {
                continue;
            }
            boolean hit;
            if (expected instanceof Boolean) {
                // 布尔常量在字节码里是 Integer 1/0
                hit = value instanceof Integer i
                        && (expected.equals(Boolean.TRUE) ? i == 1 : i == 0);
            } else {
                hit = expected.equals(value);
            }
            if (!hit) {
                continue;
            }
            if (found != null) {
                // 两个 MODIFY_CONSTANT 命中同一常量：链式替换的先后顺序
                // 取决于列表序 —— 那是加载顺序依赖，绝不允许。
                throw new org.loader.api.transform.TransformationConflictException(
                        owner, methodName,
                        java.util.List.of(found.transformerId(),
                                injection.transformerId()),
                        "同一常量 \"" + injection.constant() + "\" 被多次 MODIFY_CONSTANT");
            }
            found = injection;
        }
        return found;
    }

    /**
     * 按回调返回类型解析声明的常量。
     *
     * <p>解析失败抛 {@code TransformationException} —— 声明的常量
     * 「1,000」解析不出 int，若静默跳过就是又一次永不生效。
     */
    private Object parseConstant(MiliClassTransformer.MethodInjection injection) {
        String raw = injection.constant();
        if (raw == null || raw.isBlank()) {
            throw new org.loader.api.transform.TransformationException(
                    "MODIFY_CONSTANT 未声明 constant 值: " + injection.transformerId(),
                    injection.transformerId(), null);
        }
        Type ret = Type.getReturnType(injection.effectiveCallbackDescriptor());
        try {
            return switch (ret.getSort()) {
                case Type.INT -> Integer.parseInt(raw);
                case Type.LONG -> Long.parseLong(raw);
                case Type.FLOAT -> Float.parseFloat(raw);
                case Type.DOUBLE -> Double.parseDouble(raw);
                case Type.BOOLEAN -> Boolean.parseBoolean(raw);
                default -> {
                    if (Type.getReturnType(injection.effectiveCallbackDescriptor())
                            .getDescriptor().equals("Ljava/lang/String;")) {
                        yield raw;
                    }
                    throw new org.loader.api.transform.TransformationException(
                            "MODIFY_CONSTANT 的回调返回类型必须是 int/long/float/"
                                    + "double/boolean/String，实际为 " + ret.getClassName()
                                    + ": " + injection.transformerId(),
                            injection.transformerId(), null);
                }
            };
        } catch (NumberFormatException e) {
            throw new org.loader.api.transform.TransformationException(
                    "MODIFY_CONSTANT 声明的常量 \"" + raw + "\" 无法按回调返回类型 "
                            + ret.getClassName() + " 解析: " + injection.transformerId(),
                    injection.transformerId(), null);
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /**
     * 修改调用实参。
     *
     * <h2>为什么这需要「全部实参暂存 — 逐个重放」而不是局部替换</h2>
     * 在 {@code visitMethodInsn} 被调用的那一刻，<b>所有实参都已在操作数栈上</b>，
     * 且顺序是 {@code arg0 在最底下、argN 在栈顶}。要替换其中一个，必须：
     * <ol>
     *   <li>把所有实参倒序存入局部变量（倒序是因为栈只能从顶弹出）；</li>
     *   <li>按正序重新压栈 —— 在压到目标位置时改为「调用回调，取其返回值」。</li>
     * </ol>
     *
     * <p><b>没有捷径。</b>常见错误是「把栈顶的值 POP 掉再换成新值」——
     * 但目标实参通常不在栈顶，pop 掉的是最后一个实参。结果是
     * 参数<b>顺序错乱</b>而非编译错误：Minecraft 收到类型正确但语义错误的值，
     * 行为诡异且无法定位。
     *
     * <h2>槽位宽度的陷阱</h2>
     * {@code long} 与 {@code double} 占<b>两个</b>局部变量槽。用
     * {@code Type.getSize()} 而非 {@code newLocal} 的返回值做算术会算错偏移。
     * 本实现统一用 {@code Type#getSize()}，并在压栈时用
     * {@code loadLocal} / {@code storeLocal} 让 ASM 处理宽类型的读写。
     *
     * <h2>回调必须返回值</h2>
     * 修改参数的回调描述符必须是 {@code ()<目标类型>}。
     * 若声明为 {@code ()V}，原值会被丢弃且没有新值 —— 那不是 ModifyArg 的语义。
     * 此时<b>显式报错</b>而非静默通过。
     *
     * @param callDescriptor 目标调用的描述符
     */
    private void modifyArgument(MiliClassTransformer.MethodInjection injection,
                                String callDescriptor) {
        Type[] argTypes = Type.getArgumentTypes(callDescriptor);
        int idx = injection.argIndex();
        if (idx < 0 || idx >= argTypes.length) {
            throw new org.loader.api.transform.TransformationTargetNotFoundException(
                    owner, methodName, callDescriptor,
                    org.loader.api.VersionInfo.TARGET_MINECRAFT,
                    injection.transformerId());
        }

        Type targetType = argTypes[idx];
        String cbDescriptor = injection.effectiveCallbackDescriptor();
        if (!Type.getReturnType(cbDescriptor).equals(targetType)) {
            throw new org.loader.api.transform.TransformationException(
                    "MODIFY_ARG 要求回调返回被修改参数的类型 " + targetType
                            + "，但声明的回调描述符是 " + cbDescriptor + "\n"
                            + "  方法: " + owner + "#" + methodName + methodDescriptor
                            + "\n  原因: ()V 回调无法提供新值。"
                            + "平台不会静默丢弃原参数 —— 那会让 Mod 误以为参数已被修改。\n"
                            + "  详见 docs/design/TRANSFORMATION_ENGINE.md §4.4",
                    injection.transformerId(), null);
        }

        // ── 1. 倒序暂存全部实参 ────────────────────────────────────
        int[] slots = new int[argTypes.length];
        for (int i = argTypes.length - 1; i >= 0; i--) {
            slots[i] = newLocal(argTypes[i]);
            storeLocal(slots[i], argTypes[i]);
        }

        // ── 2. 正序重放；目标位置改为调用回调 ────────────────────────
        for (int i = 0; i < argTypes.length; i++) {
            if (i == idx) {
                pushCallback(injection);   // 描述符已校验为 ()<目标类型>
            } else {
                loadLocal(slots[i], argTypes[i]);
            }
        }
    }

/**
     * 把字段指令<b>之前</b>栈上的操作数挪到局部变量。
     *
     * <p>必须严格按四种 opcode 的实际栈形态处理：
     * <ul>
     *   <li>{@code GETFIELD}  —只有 objref，<b>没有 value</b>；</li>
     *   <li>{@code PUTFIELD}  —objref 与 value 都有（objref 在下）；</li>
     *   <li>{@code GETSTATIC} —<b>栈是空的</b>；</li>
     *   <li>{@code PUTSTATIC} —只有 value。</li>
     * </ul>
     *
     * <p><b>GETSTATIC 是最容易写错的一个</b>：它在「之前」不压任何东西，
     * 若照抄 PUTSTATIC 的写法去 {@code storeLocal} 一个值，
     * 弹出的就是调用方自己的实参 —— 生成出结构合法但语义完全错误的字节码。
     * 这类错误不会被 CheckClassAdapter 抓到（栈深仍然平衡），
     * 只会表现为「游戏行为诡异」。
     *
     * @return 局部变量槽；长度与实际暂存的操作数个数一致
     */
    private int[] stashFieldOperands(boolean isInstance, boolean isRead,
                                     String fieldDescriptor) {
        Type fieldType = Type.getType(fieldDescriptor);
        int count = isInstance ? (isRead ? 1 : 2) : (isRead ? 0 : 1);
        int[] slots = new int[count];

        // 倒序弹出：栈顶在前。
        if (isInstance) {
            if (!isRead) {
                // PUTFIELD：顶是 value，其下是 objref
                slots[1] = newLocal(fieldType);
                storeLocal(slots[1], fieldType);
            }
            // 无论读写，objref 都在 value 之下
            slots[0] = newLocal(Type.getType(Object.class));
            storeLocal(slots[0], Type.getType(Object.class));
        } else if (!isRead) {
            // PUTSTATIC：顶是 value
            slots[0] = newLocal(fieldType);
            storeLocal(slots[0], fieldType);
        }
        // GETSTATIC：count == 0，不做任何事
        return slots;
    }

    /**
     * 按与 {@link #stashFieldOperands} 完全相同的顺序把操作数装回栈。
     *
     * <p><b>顺序必须一致</b>：暂存时是「objref 在下、value 在上」，
     * 装回也必须如此，否则字段指令会拿到反序的操作数 ——
     * 对 {@code PUTFIELD} 而言就是「把字段值写进 objref 指向的对象」的反面，
     * 即<b>写到了错误的对象上</b>。
     */
    private void reloadFieldOperands(int[] slots, boolean isInstance, boolean isRead,
                                     String fieldDescriptor) {
        Type fieldType = Type.getType(fieldDescriptor);
        if (isInstance) {
            loadLocal(slots[0], Type.getType(Object.class));   // objref 在下
            if (!isRead) {
                loadLocal(slots[1], fieldType);                // value 在上
            }
        } else if (!isRead) {
            loadLocal(slots[0], fieldType);
        }
        // GETSTATIC：无操作数可装回
    }

    /**
     * 判断调用点是否命中某条注入。
     *
     * <h2>比对对象必须是 {@code invocation.method()}，不是 {@code target()}</h2>
     * 两者含义完全不同：
     * <ul>
     *   <li>{@code target()} —— <b>宿主方法</b>（声明，即「我要改哪个方法」）。
     *       它在 {@link MiliClassTransformer#injectionsFor} 层面筛选注入时用；</li>
     *   <li>{@code invocation().method()} —— <b>被调用的目标</b>
     *       （「我要改方法里的哪一次调用」）。</li>
     * </ul>
     *
     * <p>拿宿主方法去比对调用指令的 owner/name/descriptor，
     * 结果永远是 false —— 而症状是<b>静默不生效</b>：
     * 转换成功、字节码合法、验证通过，只是 REDIRECT 从未发生。
     * 这正是本仓库反复记录要消灭的失效模式。
     *
     * <h2>ordinal 必须按签名分组计数</h2>
     * 契约（{@link TargetInvocation}）：「ordinal 从 0 开始，
     * <b>按字节码顺序计数同签名的调用</b>」。若对方法内所有调用统一计数，
     * 那么「第 2 次调用 {@code list.add}」的实际序号会被中间夹着的
     * {@code map.put} 顶高，ordinal 2 就永远匹配不到目标。
     *
     * <p>因此本类按 {@code owner#name#desc} 分桶计数，
     * 桶内序号才与契约一致。
     */
    private boolean matches(MiliClassTransformer.MethodInjection injection,
                            String callOwner, String callName,
                            String callDescriptor, int opcode, int ordinal) {

        var invocation = injection.invocation();
        if (invocation == null) {
            // 未声明调用点 → 该注入不针对任何具体调用，不命中。
            //
            // 注意不能退化为「只要 target 匹配就命中」：target 是宿主方法，
            // 用它比对调用指令必然 false，而用「忽略 invocation 直接放行」
            // 又会让 BEFORE_INVOKE 变成「对方法内所有调用都注入」——
            // 从「永不生效」翻到「到处生效」，两个都是静默失效。
            return false;
        }

        var called = invocation.method();
        if (!called.owner().equals(callOwner)
                || !called.name().equals(callName)
                || !called.descriptor().equals(callDescriptor)) {
            return false;
        }
        if (invocation.ordinal() != ordinal) {
            return false;
        }
        return invocation.matchesOpcode(opcode);
    }

    /** 判断字段访问是否命中某条注入。 */
    private boolean matchesField(MiliClassTransformer.MethodInjection injection,
                                 String fieldOwner, String fieldName,
                                 String fieldDescriptor, int opcode) {
        var f = injection.field();
        if (f == null) {
            // 没有填 TargetField —— 这是调用方的错误。
            // 抛异常而非返回 false：返回 false 会让字段注入「看起来注册了
            // 但从不生效」，而用户完全无从察觉。
            throw new org.loader.api.transform.TransformationException(
                    "字段注入点 " + injection.point() + " 缺少 TargetField: "
                            + injection.transformerId(),
                    injection.transformerId(), null);
        }
        if (!f.owner().equals(fieldOwner) || !f.name().equals(fieldName)) {
            return false;
        }
        // 字段描述符格式（I / Ljava/lang/String; / [I）与方法描述符不同，
        // 因此必须用 TargetField 承载，不能复用 TargetMethod.descriptor()。
        if (!f.descriptor().equals(fieldDescriptor)) {
            return false;
        }
        // BEFORE_FIELD_SET 只对写操作生效，GET 类只对读操作生效。
        boolean isWrite = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
        if (injection.point() == InjectionPoint.BEFORE_FIELD_SET && !isWrite) {
            return false;
        }
        return true;
    }

    /**
     * REDIRECT 后的指令操作码。
     *
     * <h2>为什么不能直接沿用原操作码</h2>
     * 沿用 {@code INVOKEVIRTUAL} 去调用一个 static 方法，字节码<b>结构合法</b>
     * （校验器抓不到），但运行期直接 {@code IncompatibleClassChangeError}。
     * 而这个错误发生在游戏运行中、堆栈指向 Minecraft 内部调用点，
     * 极难反推到「某个 Mod 的某个 REDIRECT 写错了」。
     *
     * <h2>为什么靠描述符推断是不可行的</h2>
     * 描述符只描述参数与返回类型，<b>不携带 static 信息</b> ——
     * 静态方法与实例方法的描述符格式完全相同。
     * 任何「看起来像静态」的启发式（比如参数个数比对）都会在
     * 「无参实例方法」上给出错误答案。
     *
     * <p>因此替换目标的调用形式必须由注册期<b>显式声明</b>
     * （{@link MiliClassTransformer.MethodInjection#replacementStatic()}）。
     * 注册期能真正检查目标方法是否为 static；
     * 到字节码生成期这个信息已经不可得了。
     */
    private int injectionBytecodeOpcode(
            int originalOpcode, boolean isInterface, boolean replacementStatic) {
        if (replacementStatic) {
            return Opcodes.INVOKESTATIC;
        }
        if (originalOpcode == Opcodes.INVOKESTATIC) {
            // 静态调用被重定向到实例方法：调用形式保持静态，
            // 语义由替换目标的签名负责（静态方法不能调实例方法，
            // 因此注册期会校验这一点）。
            return Opcodes.INVOKESTATIC;
        }
        return isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
    }

    /**
     * 发出回调调用。
     *
     * <p>描述符来自 {@link MiliClassTransformer.MethodInjection#effectiveCallbackDescriptor()}，
     * 而非 {@code replacementDescriptor()} ——
     * 后者是 REDIRECT 的替换目标描述符，与「回调签名」是两件事。
     * 早期版本混用了这两个字段，导致 MODIFY_ARG 拿到的描述符是
     * {@code ()V}，参数被静默丢弃。
     *
     * <h2>回调参数必须由本方法压栈 —— 这是曾经缺失的一环</h2>
     * 声明式回调允许接收一个 {@link org.loader.api.transform.callback.InjectionContext}：
     * <pre>
     *   &#64;MiliInject(at = InjectionPoint.HEAD)
     *   public static void onTick(InjectionContext ctx) { ... }
     * </pre>
     * 注入生成的是 {@code INVOKESTATIC callback(ctx)}，而 {@code ctx}
     * 在字节码里并不存在。早期版本无条件发出
     * {@code INVOKESTATIC <描述符>}而<b>从不压任何参数</b>，
     * 于是在方法入口（栈为空）插入需要 1 个参数的调用 ——
     * 产出的是栈下溢的非法字节码。
     *
     * <p>症状极具欺骗性：{@code BytecodeVerifier} 报
     * {@code AnalyzerException: Error at instruction 0: Cannot pop operand
     * off an empty stack}，而指令 0看起来完全正常。这是本仓库
     * 最难定位的一类失效 —— 报错指向指令序号，真正的原因
     * （缺一个参数）却在三行之外。
     *
     * @see InjectionContextFactory
     */
    private void pushCallback(MiliClassTransformer.MethodInjection injection) {
        String owner = injection.replacementOwner();
        if (owner == null) {
            throw new org.loader.api.transform.TransformationException(
                    "注入缺少回调目标（replacementOwner 为 null）: "
                            + injection.point() + " by " + injection.transformerId(),
                    injection.transformerId(), null);
        }
        String descriptor = injection.effectiveCallbackDescriptor();
        pushCallbackArguments(injection, descriptor, false);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner,
                injection.replacementName() != null
                        ? injection.replacementName() : "onInject",
                descriptor, false);
    }

    /**
     * 为回调调用压入实参。
     *
     * <h2>两种参数成分</h2>
     * 回调签名的参数按固定顺序由两种成分构成：
     * <ol>
     *   <li><b>{@link org.loader.api.transform.callback.InjectionContext}</b>
     *       （可选，至多一个，必须第一位）—— 平台凭空构造：
     *       静态信息 + 宿主实例；</li>
     *   <li><b>实参捕获参数</b>（可选，任意多个）—— 从<b>宿主方法的
     *       局部变量槽</b>发射 {@code xLOAD}，按位与宿主实参严格对应。</li>
     * </ol>
     *
     * <p>例如回调 {@code (ctx, int a, int b)} 在宿主
     * {@code int add(int, int)} 上：先发射 ctx，再从槽 0/1（static 宿主）
     * 加载两个实参。
     *
     * <h2>为什么不能「只支持无参回调」绕开这个问题</h2>
     * ABI 明确允许 {@code (InjectionContext)} 形态（见
     * {@code MiliInject} 与扫描器的参数校验），
     * 且这是 Mod 获取执行上下文的<b>唯一</b>途径。
     * 若引擎静默降级为「传 null」，Mod 侧
     * {@code ctx.executionContext()} 就会在生产环境抛 NPE ——
     * 而测试环境（null 上下文）永远测不出来。
     *
     * <h2>为什么未知参数类型必须报错而非塞默认值</h2>
     * 塞默认值（如 0 / null）会让 Mod 收到<b>看起来合法但完全错误</b>的
     * 数据：坐标变成 0、tickId 变成 0。Mod 会基于这些值做出错误决策，
     * 且没有任何错误提示。宁可加载失败并说清原因。
     *
     * @param contextAlreadyOnStack ctx 已由调用方压栈时传 true
     *        （可取消路径先把 ctx 存槽再重读，此时只补发射捕获实参）
     */
    private void pushCallbackArguments(
            MiliClassTransformer.MethodInjection injection, String descriptor,
            boolean contextAlreadyOnStack) {

        Type[] cbArgTypes = Type.getArgumentTypes(descriptor);
        if (cbArgTypes.length == 0) {
            return;
        }

        boolean hasContext = CONTEXT_TYPE.getDescriptor()
                .equals(cbArgTypes[0].getDescriptor());
        if (hasContext && !contextAlreadyOnStack) {
            emitContextArgument(injection);
        }

        int captureCount = cbArgTypes.length - (hasContext ? 1 : 0);
        if (captureCount > 0) {
            emitCapturedArguments(injection, cbArgTypes,
                    hasContext ? 1 : 0, captureCount);
        }
    }

    /**
     * 发射 {@code InjectionContext} 实参 —— 平台凭空构造的那一种。
     *
     * <p>目标类/方法名以常量形式携带 —— 它们在转换期就完全确定。
     * 逐个分支而非反射：反射在字节码生成路径上没有价值，
     * 且这里只有两种可能形态。
     */
    private void emitContextArgument(MiliClassTransformer.MethodInjection injection) {
        super.visitLdcInsn(owner);
        super.visitLdcInsn(methodName);
        // 宿主实例（this）—— 仅实例方法可传；构造器上的
        // 未初始化 this 传给外部方法是 VerifyError，传 null。
        boolean canPassThis = (methodAccess & Opcodes.ACC_STATIC) == 0
                && !methodName.equals("<init>")
                && !methodName.equals("<clinit>");
        if (canPassThis) {
            super.visitVarInsn(Opcodes.ALOAD, 0);
        } else {
            super.visitInsn(Opcodes.ACONST_NULL);
        }
        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                Type.getInternalName(InjectionContextFactory.class),
                injection.cancellable()
                        ? "forCancellableMethod" : "forMethodWithTarget",
                CONTEXT_FACTORY_DESCRIPTOR, false);
    }

    /**
     * 发射实参捕获参数 —— 从宿主方法的局部变量槽 {@code xLOAD}。
     *
     * <h2>槽位布局（本方法正确性的核心）</h2>
     * JVM 规定：实例方法的 {@code this} 占槽 0，实参从槽 1 开始；
     * static 方法实参从槽 0 开始；{@code long}/{@code double}
     * <b>占两个槽</b>。因此第 i 个实参的槽位 = 基础偏移 + 前面所有
     * 实参的 {@code Type#getSize()} 之和。用 1 做宽类型推进必然错位
     * —— 与 {@code modifyArgument} 记录的是同一类陷阱。
     *
     * <p>加载指令由 {@code Type.getOpcode(ILOAD)} 按实参类型分派：
     * 整数族 → {@code ILOAD}（局部变量槽里的 boolean/byte/short/char
     * 本来就是 int）、{@code J}/{@code F}/{@code D} → 对应宽加载、
     * 引用 → {@code ALOAD}。
     *
     * <h2>防御性逐位校验</h2>
     * 描述符相等性在这里再验一次：绕过扫描器、直接构造
     * {@code MethodInjection} 的调用方不受扫描器保护。校验发生在
     * 生成期 —— 错一个类型就抛 {@code TransformationException}，
     * 而不是产出 {@code VerifyError} 指向 Minecraft。
     *
     * <h2>读取语义（文档化）</h2>
     * 捕获读取的是<b>触发时刻</b>局部变量槽的当前值：
     * <ul>
     *   <li>{@code HEAD} —— 方法体尚未执行，即入口值；</li>
     *   <li>{@code RETURN} / {@code MODIFY_RETURN} —— 方法体已执行，
     *       若方法体重写过参数槽（{@code a = ...}），读到的是
     *       <b>重写后的当前值</b>，不是入口值。参数槽不被 javac
     *       复用给其他局部变量（参数作用域是整个方法体），
     *       因此该值总是良定义的。</li>
     * </ul>
     */
    private void emitCapturedArguments(
            MiliClassTransformer.MethodInjection injection,
            Type[] cbArgTypes, int captureStart, int captureCount) {

        if (!supportsArgCapture(injection.point())) {
            throw new org.loader.api.transform.TransformationException(
                    "回调声明了实参捕获参数，但注入点 " + injection.point()
                            + " 不支持（仅 HEAD / RETURN / MODIFY_RETURN）: "
                            + injection.transformerId(),
                    injection.transformerId(), null);
        }

        Type[] hostArgs = Type.getArgumentTypes(methodDescriptor);
        if (captureCount > hostArgs.length) {
            throw new org.loader.api.transform.TransformationException(
                    "回调声明了 " + captureCount + " 个捕获参数，但宿主方法 "
                            + owner + "#" + methodName + " 只有 "
                            + hostArgs.length + " 个实参。\n"
                            + "  回调描述符: " + injection.effectiveCallbackDescriptor()
                            + "\n  宿主描述符: " + methodDescriptor,
                    injection.transformerId(), null);
        }

        // 实例方法 this 占槽 0；static 从 0 开始。
        int slot = (methodAccess & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < captureCount; i++) {
            Type expected = hostArgs[i];
            Type declared = cbArgTypes[captureStart + i];
            if (!declared.getDescriptor().equals(expected.getDescriptor())) {
                throw new org.loader.api.transform.TransformationException(
                        "回调的捕获参数 #" + (i + 1) + " 与宿主方法实参不匹配。\n"
                                + "  期望（宿主第 " + i + " 个实参）: "
                                + expected.getDescriptor() + "\n"
                                + "  实际声明: " + declared.getDescriptor() + "\n"
                                + "  宿主方法: " + owner + "#" + methodName
                                + methodDescriptor + "\n"
                                + "  回调: " + injection.effectiveCallbackDescriptor()
                                + " by " + injection.transformerId() + "\n"
                                + "捕获按位严格相等：不允许装箱（int ≠ java.lang.Integer）、"
                                + "不允许子类。",
                        injection.transformerId(), null);
            }
            super.visitVarInsn(expected.getOpcode(Opcodes.ILOAD), slot);
            slot += expected.getSize();     // long/double 占双槽
        }
    }

    /** 该注入点是否支持实参捕获（与扫描器的判定一致）。 */
    private static boolean supportsArgCapture(InjectionPoint point) {
        return point == InjectionPoint.HEAD
                || point == InjectionPoint.RETURN
                || point == InjectionPoint.MODIFY_RETURN;
    }

    /**
     * 回调描述符中实参捕获参数的个数：
     * 首位是 {@code InjectionContext} 时为「参数总数 − 1」，否则为参数总数。
     */
    private static int captureArgCount(String callbackDescriptor) {
        Type[] types = Type.getArgumentTypes(callbackDescriptor);
        if (types.length > 0 && CONTEXT_TYPE.getDescriptor()
                .equals(types[0].getDescriptor())) {
            return types.length - 1;
        }
        return types.length;
    }

    /**
     * {@code InjectionContext} 的类型 —— 唯一被支持的回调参数类型。
     */
    private static final Type CONTEXT_TYPE =
            Type.getType(org.loader.api.transform.callback.InjectionContext.class);

    /**
     * 上下文工厂描述符 —— 三参形态（携带宿主实例）。
     *
     * <p><b>手写常量而非运行时推导</b>：本类位于「不能 import loader/minecraft」
     * 的 runtime 模块内。改 {@code InjectionContextFactory} 的签名时
     * 必须同步这里 —— 见其 Javadoc 的同一标注。
     */
    private static final String CONTEXT_FACTORY_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lorg/loader/api/transform/callback/InjectionContext;";

    private void record(MiliClassTransformer.MethodInjection injection, String detail) {
        // 命中登记：方法体读完时据此判断「声明了却从未命中」。
        matched.add(injection);
        applied.add(new MiliClassTransformer.AppliedInjection(
                methodName, injection.point(), injection.transformerId(), detail));
    }
}