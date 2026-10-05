package org.loader.runtime.transform.asm;

import org.loader.api.transform.InjectionPoint;
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
        this.injections = injections;
        this.applied = applied;
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
     * <p>当前注入的回调都是无参 {@code ()V}，因此不需要搬运栈或局部变量。
     */
    private void emitHead(MiliClassTransformer.MethodInjection injection) {
        pushCallback(injection);
        record(injection, "HEAD");
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

        // ── 1. BEFORE_INVOKE ────────────────────────────────────────
        // 此时实参在栈上、调用尚未发生。回调为 ()V，栈高度不变。
        for (MiliClassTransformer.MethodInjection injection : injections) {
            if (injection.point() == InjectionPoint.BEFORE_INVOKE
                    && matches(injection, callOwner, callName, callDescriptor,
                            opcode, myOrdinal)) {
                pushCallback(injection);
                record(injection, "BEFORE_INVOKE");
            }
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

        // ── 3. MODIFY_ARG（必须在调用发出之前） ─────────────────────
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

        // ── 4. 发出调用 ─────────────────────────────────────────────
        super.visitMethodInsn(finalOpcode, finalOwner, finalName,
                finalDescriptor, isInterface);

        if (redirect != null) {
            record(redirect, "REDIRECT " + callOwner + "#" + callName + callDescriptor
                    + " -> " + finalOwner + "#" + finalName + finalDescriptor);
        }

        // ── 5. AFTER_INVOKE ────────────────────────────────────────
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
        pushCallbackArguments(injection, descriptor);
        super.visitMethodInsn(Opcodes.INVOKESTATIC, owner,
                injection.replacementName() != null
                        ? injection.replacementName() : "onInject",
                descriptor, false);
    }

    /**
     * 为回调调用压入实参。
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
     */
    private void pushCallbackArguments(
            MiliClassTransformer.MethodInjection injection, String descriptor) {

        Type[] argTypes = Type.getArgumentTypes(descriptor);
        if (argTypes.length == 0) {
            return;
        }

        // 目标类/方法名以常量形式携带 —— 它们在转换期就完全确定。
        // 逐个分支而非反射：反射在字节码生成路径上没有价值，
        // 且这里只有两种可能形态。
        for (Type argType : argTypes) {
            if (CONTEXT_TYPE.getDescriptor().equals(argType.getDescriptor())) {
                super.visitLdcInsn(owner);
                super.visitLdcInsn(methodName);
                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                        Type.getInternalName(InjectionContextFactory.class),
                        "forMethod",
                        CONTEXT_FACTORY_DESCRIPTOR, false);
            } else {
                throw new org.loader.api.transform.TransformationException(
                        "不支持的回调参数类型 " + argType.getClassName() + ": "
                                + injection.point() + " by " + injection.transformerId() + "\n"
                                + "  回调描述符: " + descriptor + "\n"
                                + "  目前只支持无参回调或单个 InjectionContext 参数。\n"
                                + "  传入其他类型无法凭空构造 —— 塞默认值会让Mod"
                                + "收到看似合法实则错误的数据。",
                        injection.transformerId(), null);
            }
        }
    }

    /** {@code InjectionContext} 的类型 —— 唯一被支持的回调参数类型。 */
    private static final Type CONTEXT_TYPE =
            Type.getType(org.loader.api.transform.callback.InjectionContext.class);

    /**
     * {@code InjectionContextFactory.forMethod} 的描述符。
     *
     * <p><b>手写常量而非运行时推导</b>：本类位于「不能 import loader/minecraft」
     * 的 runtime 模块内，而工厂类就在同一模块 ——
     * 用 {@code Type.getInternalName} + {@code getMethod} 拼描述符
     * 会让「工厂类改了签名」变成运行期 {@code NoSuchMethodError}
     * 而非编译期错误。这里刻意把它写成常量并在
     * {@link InjectionContextFactory} 的 Javadoc 里标注同样的签名，
     * 两侧必须同时改。
     */
    private static final String CONTEXT_FACTORY_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)Lorg/loader/api/transform/callback/InjectionContext;";

    private void record(MiliClassTransformer.MethodInjection injection, String detail) {
        // 命中登记：方法体读完时据此判断「声明了却从未命中」。
        matched.add(injection);
        applied.add(new MiliClassTransformer.AppliedInjection(
                methodName, injection.point(), injection.transformerId(), detail));
    }
}