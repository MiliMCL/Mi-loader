package org.loader.runtime.transform.engine;

import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationException;
import org.loader.api.transform.TransformationPhase;
import org.loader.api.transform.TransformationResult;
import org.loader.runtime.transform.cache.TransformationCache;
import org.loader.runtime.transform.conflict.ConflictLedger;
import org.loader.runtime.transform.debug.ClassDumper;
import org.loader.runtime.transform.debug.TransformationLogger;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.loader.runtime.security.AuditLog;
import org.loader.runtime.kernel.Scope;
import org.loader.runtime.transform.security.TransformationGuard;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 转换流水线 —— 整个 Transformation System 的执行引擎。
 *
 * <h2>数据流</h2>
 * <pre>
 * transform(className, original, classLoader)
 *   │
 *   ├─ 1. registry.matching(className)      零匹配 → 直接返回原始字节（热路径）
 *   ├─ 2. cache.get(classLoader, className)命中 → 直接返回（防重复转换）
 *   │
 *   ├─ 3. for each transformer（确定性顺序）:
 *   │      ├─ guard.checkTransform(...)          权限
 *   │      ├─ ledger.claim(...)                  冲突
 *   │      ├─ transformer.transform(context)     实际转换
 *   │      └─ 结果归约：Unchanged/Skipped 跳过，Transformed 更新当前字节，Failed 中断
 *   │
 *   ├─ 4. verifier.verify(...)              字节码合法性
 *   ├─ 5. audit.log(...)                     审计
 *   ├─ 6. dumper.dump(...)                   调试（默认关闭）
 *   └─ 7. cache.put(...)                     防重复
 *   └─ 返回最终字节
 * </pre>
 *
 * <h2>为什么失败必须中断整条链而不是「跳过继续」</h2>
 * 假设 Mod A 的转换失败了，平台跳过它继续执行 Mod B 的转换。最终得到
 * 一个「A 的逻辑不存在、B 的逻辑存在」的类。表现是<b>游戏正常运行、
 * A 的功能莫名其妙不起作用</b>。
 *
 * <p>而如果中断并抛出：用户在启动时就看到
 * 「ModA 的转换失败：目标方法 X 不存在」，直接定位。
 *
 * <p><b>本类默认采用中断策略</b>（fail-fast）。这是 ADR-0011 明确要求的：
 * 「目标不存在必须明确失败，绝不静默返回 Skipped」。
 *
 * <h2>为什么结果归约必须区分 Unchanged 与 Skipped</h2>
 * 两者对流水线都是「不改字节」，但语义不同：
 * <ul>
 *   <li>{@code Skipped} —— 这个转换器不关心这个类，正常；</li>
 *   <li>{@code Unchanged} —— 它处理了但决定不动；可能是它检查后发现
 *       这个类没有它关心的方法 —— <b>此时应当由转换器自己抛
 *       TargetNotFound，而不是返回 Unchanged</b>。</li>
 * </ul>
 * 流水线对两者一视同仁地继续，但 {@link TransformationLogger}
 * 会分别记录，让「Mod 说它改了但其实没改」这种降级可见。
 */
public final class TransformerPipeline {

    private final TransformerRegistry registry;
    private final TransformationCache cache;
    private final ConflictLedger ledger;
    private final TransformationLogger logger;
    private final ClassDumper dumper;
    private final AuditLog auditLog;
    private final boolean verifyEnabled;
    private final boolean overwriteEnabled;

    private final AtomicLong transformedClasses = new AtomicLong();
    private final AtomicLong skippedClasses = new AtomicLong();
    private final AtomicLong totalTransformerRuns = new AtomicLong();

    public TransformerPipeline(Builder builder) {
        this.registry = builder.registry;
        this.cache = builder.cache;
        this.ledger = builder.ledger;
        this.logger = builder.logger;
        this.dumper = builder.dumper;
        this.auditLog = builder.auditLog;
        this.verifyEnabled = builder.verifyEnabled;
        this.overwriteEnabled = builder.overwriteEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 流水线主体 —— 对单个类执行全部适用转换。
     *
     * <p><b>这是 ClassLoader 的唯一插入点</b>，位于
     * {@code findClass} 读出原始字节之后、{@code defineClass} 之前。
     *
     * @param className   类内部名（斜杠分隔）
     * @param original    原始字节码
     * @param environment 转换环境
     * @param ownerScope 发起方 Scope（权限判定用）；平台自身传 null
     * @return 最终字节码 —— 可能就是 {@code original} 本身（未变化）
     */
    public byte[] transform(
            String className,
            byte[] original,
            TransformationEnvironment environment,
            Scope ownerScope) {

        if (original == null || original.length == 0) {
            throw new TransformationException(
                    "无法转换空字节码: " + className, null, null);
        }
        if (className == null) {
            throw new TransformationException("className 不能为 null", null, null);
        }

        ClassLoader classLoader = environment.classLoader();
        List<MiliTransformer> applicable = registry.matching(className);

        // ── 热路径短路 1：没有任何转换器关心这个类 ──────────────────────
        // 这一条覆盖了 Minecraft 26.2 里约 1 万个类中的绝大多数。
        // 它们完全不进入 ASM，也不做任何缓存写入。
        if (applicable.isEmpty()) {
            skippedClasses.incrementAndGet();
            return original;
        }

        // ── 热路径短路 2：已经转换过 ───────────────────────────────────
        // 防重复转换。重复注入的表现是「tick 数翻倍」且无任何报错，
        // 比不注入更难查。
        byte[] cached = cache.get(classLoader, className);
        if (cached != null) {
            logger.cacheHit(className);
            return cached;
        }

        // 注册表必须已封闭，否则「同一类两次加载得到不同结果」
        StringBuilder trace = logger.beginTrace(className, applicable.size());

        byte[] current = original;
        TransformationContext baseContext = new TransformationContext(
                className, original, environment,
                // 阶段取该类上第一个转换器的阶段；逐个转换器会用 withIdentity 派生
                phaseOf(applicable.get(0)),
                null, applicable.get(0).id());

        for (MiliTransformer transformer : applicable) {
            String transformerId = transformer.id();
            totalTransformerRuns.incrementAndGet();

            // ── 权限 ──────────────────────────────────────────────
            TransformationGuard.Decision decision = TransformationGuard.checkTransform(
                    ownerScope, className, isMinecraftClass(className));
            if (decision != TransformationGuard.Decision.ALLOWED) {
                TransformationGuard.Decision overwriteCheck =
                        TransformationGuard.checkOverwrite(
                                ownerScope, transformer.phase(), overwriteEnabled);
                // Overwrite 在默认配置下被拒绝是正常的（默认禁止）；
                // 只有当本转换器确实要改受保护目标时才升级为失败。
                if (overwriteCheck == TransformationGuard.Decision.DENIED_PROTECTED_TARGET) {
                    throw new TransformationException(
                            "转换被拒绝: " + transformerId + " → " + className + "\n"
                                    + "原因: " + TransformationGuard.describe(decision),
                            transformerId, null);
                }
            }
            auditPermission(ownerScope, transformerId, className, decision);

            // ── 执行 ──────────────────────────────────────────────
            TransformationContext context = baseContext.withIdentity(
                    transformerId, modIdOf(ownerScope));

            TransformationResult result;
            try {
                result = transformer.transform(context);
            } catch (TransformationException e) {
                // 转换器主动抛出（典型：目标不存在）。原样上抛，保留完整诊断。
                recordFailure(transformerId, className, e.getMessage());
                auditFailure(ownerScope, transformerId, className, e.getMessage());
                throw e;
            } catch (Throwable t) {
                // 转换器内部崩溃 —— 包装成 TransformationException，
                // 带上转换器 id 与类名，否则用户看到的是一个无主异常。
                TransformationException wrapped = new TransformationException(
                        "转换器 " + transformerId + " 处理 " + className + " 时崩溃: "
                                + t.getClass().getSimpleName()
                                + (t.getMessage() != null ? ": " + t.getMessage() : ""),
                        transformerId, t);
                recordFailure(transformerId, className, wrapped.getMessage());
                auditFailure(ownerScope, transformerId, className, wrapped.getMessage());
                throw wrapped;
            }

            // ── 结果归约 ──────────────────────────────────────────
            switch (result) {
                case TransformationResult.Transformed t -> {
                    byte[] produced = t.bytecode();
                    // 每个转换器输出后立即验证 —— 不等到最后。
                    // 若等到最后，出错时无法判断是哪个转换器破坏了字节码。
                    if (verifyEnabled) {
                        // 传入 environment.classLoader()（即 MinecraftClassLoader）——
                        // 验证器做类型推断时要真的加载父类，而验证发生在
                        // defineClass 之前，目标类自身尚不存在，
                        // 只有游戏 ClassLoader 能解析它的父类。
                        BytecodeVerifier.verify(className, produced,
                                transformerId, modIdOf(ownerScope),
                                environment.classLoader());
                    }
                    current = produced;
                    logger.transformed(className, transformerId,
                            current.length - original.length);
                }
                case TransformationResult.Unchanged ignored ->
                        logger.unchanged(className, transformerId);
                case TransformationResult.Skipped ignored ->
                        logger.skipped(className, transformerId);
                case TransformationResult.Failed failed -> {
                    // Failed 与抛异常同义：显式失败。
                    // 绝不降级为「继续下一个转换器」。
                    recordFailure(transformerId, className,
                            failed.error().getMessage());
                    auditFailure(ownerScope, transformerId, className,
                            failed.error().getMessage());
                    throw failed.error();
                }
            }
        }

        // ── 内容未变化 ──────────────────────────────────────────────
        //
        // 判定用<b>内容比较</b>而非引用比较。这一点很关键：
        //
        //   转换器完全可以返回「内容与原始相同、但已是新数组」的字节码 ——
        //   例如「读进来 → ClassReader/ClassWriter 空转一遍 → 交回去」。
        //   那是完全合法的实现（重新编码会规范化常量池顺序）。
        //
        // <b>未变化时同样要写缓存</b>，否则会出现这一串失效：
        //
        //   第 1 次加载该类 → 转换器跑 → 返回原始字节（未变化）
        //                → 不写缓存
        //   第 2 次加载同一类 → 缓存未命中 → <b>整条转换链重跑</b>
        //   第 3 次 → 又跑一遍 ……
        //
        // 转换器被重复执行这件事本身就可能是错的：它可能有副作用
        // （注册回调、累加计数、申请资源）。而它的字节码输出没变，
        // 所以从「字节码是否改变」这个角度<b>永远看不出来</b>。
        //
        // 缓存的对象是「这个类在这个 ClassLoader 上已被本流水线处理过」
        // 这个事实，而不是「字节码变没变」——
        // 混为一谈会让「无操作转换器」每次都重跑。
        //
        // 缓存成本：一份原始字节的引用（不复制内容），
        // 换来的是「同一个类只会被本流水线处理一次」这条硬保证。
        if (java.util.Arrays.equals(current, original)) {
            logger.endTrace(trace, className, false);
            cache.put(classLoader, className, original);
            skippedClasses.incrementAndGet();
            return original;
        }

        // ── 最终验证 ──────────────────────────────────────────────
        // 每个转换器输出时已验证过；这里再验证一次是为了捕获
        // 「最后一个转换器破坏了前面所有转换器的成果」这种情况 ——
        // 实践中不会发生，但代价只是每个类一次，且能兜住
        // 「某转换器返回了别人的字节码」这类逻辑错误。
        if (verifyEnabled) {
            BytecodeVerifier.verify(className, current, "<pipeline-final>", null,
                    environment.classLoader());
        }

        cache.put(classLoader, className, current);
        transformedClasses.incrementAndGet();

        dumper.dump(className, original, current);
        logger.endTrace(trace, className, true);

        auditSuccess(ownerScope, className, applicable.size());
        return current;
    }

    /**
     * 判断是否为 Minecraft 类。
     *
     * <p>Minecraft 26.2 的类分布在 {@code net.minecraft.*} 与
     * {@code com.mojang.*} 两个根包下。两者都需要
     * {@code TRANSFORM_MINECRAFT} 权限 —— Mojang 库同样是游戏的一部分，
     * 改它们的风险与改游戏代码一致。
     */
    private static boolean isMinecraftClass(String internalName) {
        return internalName.startsWith("net/minecraft/")
                || internalName.startsWith("com/mojang/");
    }

    private static TransformationPhase phaseOf(MiliTransformer t) {
        TransformationPhase p = t.phase();
        return p != null ? p : TransformationPhase.MOD;
    }

    private static String modIdOf(Scope scope) {
        return scope != null ? scope.id() : null;
    }

    private void recordFailure(String transformerId, String className, String reason) {
        logger.failure(className, transformerId, reason);
    }

    private void auditPermission(
            Scope scope, String transformerId, String className,
            TransformationGuard.Decision decision) {
        if (auditLog == null) {
            return;
        }
        auditLog.permissionDecision(scope, "transform:" + transformerId,
                decision == TransformationGuard.Decision.ALLOWED,
                "TransformationGuard");
    }

    private void auditFailure(Scope scope, String transformerId,
                              String className, String reason) {
        if (auditLog == null) {
            return;
        }
        auditLog.privilegedUse(scope, "transform:" + transformerId, false);
    }

    private void auditSuccess(Scope scope, String className, int transformerCount) {
        if (auditLog == null) {
            return;
        }
        auditLog.privilegedUse(scope, "transform:" + className, true);
    }

    // ── 诊断 ────────────────────────────────────────────────────────────────

    /** 冲突账本 —— 供 Mod 安装阶段的预检复用。 */
    public ConflictLedger ledger() {
        return ledger;
    }

    public TransformerRegistry registry() {
        return registry;
    }

    public TransformationCache cache() {
        return cache;
    }

    /** 已实际改变字节码的类数量。 */
    public long transformedClassCount() {
        return transformedClasses.get();
    }

    /** 被短路（无匹配转换器）的类数量。 */
    public long skippedClassCount() {
        return skippedClasses.get();
    }

    /** 转换器执行总次数。 */
    public long transformerRunCount() {
        return totalTransformerRuns.get();
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("TransformerPipeline\n");
        sb.append("  transformedClasses=").append(transformedClasses.get()).append('\n');
        sb.append("  skippedClasses=").append(skippedClasses.get()).append('\n');
        sb.append("  transformerRuns=").append(totalTransformerRuns.get()).append('\n');
        sb.append("  verifyEnabled=").append(verifyEnabled).append('\n');
        sb.append("  overwriteEnabled=").append(overwriteEnabled).append('\n');
        sb.append("  ").append(cache.diagnostics()).append('\n');
        sb.append("  ").append(ledger.getClass().getSimpleName())
                .append("[claims=").append(ledger.allClaims().size()).append("]\n");
        if (logger.isEnabled()) {
            sb.append("  ").append(logger.diagnostics()).append('\n');
        }
        if (dumper.isEnabled()) {
            sb.append("  ").append(dumper.diagnostics()).append('\n');
        }
        return sb.toString();
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    /**
     * 流水线构造器。
     *
     * <p>用Builder 而非多参构造器，是因为参数已有 8 个，且大部分有默认值 ——
     * 多参构造器会让「只想改一个开关」的调用点写出一长串 null。
     */
    public static final class Builder {
        private TransformerRegistry registry;
        private TransformationCache cache = new TransformationCache();
        private ConflictLedger ledger = new ConflictLedger();
        private TransformationLogger logger = TransformationLogger.disabled();
        private ClassDumper dumper = ClassDumper.disabled();
        private AuditLog auditLog;
        private boolean verifyEnabled = true;
        private boolean overwriteEnabled = false;

        public Builder registry(TransformerRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder cache(TransformationCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder ledger(ConflictLedger ledger) {
            this.ledger = ledger;
            return this;
        }

        public Builder logger(TransformationLogger logger) {
            this.logger = logger;
            return this;
        }

        public Builder dumper(ClassDumper dumper) {
            this.dumper = dumper;
            return this;
        }

        public Builder auditLog(AuditLog auditLog) {
            this.auditLog = auditLog;
            return this;
        }

        /**
         * 是否执行字节码验证 —— <b>生产环境应保持 true</b>。
         *
         * <p>关闭它意味着把错误从「启动时明确报错」换成
         * 「游戏运行中抛 VerifyError 且堆栈指向 Minecraft 代码」。
         */
        public Builder verifyEnabled(boolean verifyEnabled) {
            this.verifyEnabled = verifyEnabled;
            return this;
        }

        /**
         * 是否允许 {@code OVERWRITE} —— <b>默认 false</b>。
         *
         * <p>见 ADR-0011：Overwrite 会丢弃原始方法体，让其他 Mod 的注入
         * 静默失效。它必须是显式 opt-in 的。
         */
        public Builder overwriteEnabled(boolean overwriteEnabled) {
            this.overwriteEnabled = overwriteEnabled;
            return this;
        }

        public TransformerPipeline build() {
            if (registry == null) {
                throw new IllegalStateException("TransformerPipeline 必须有 registry");
            }
            return new TransformerPipeline(this);
        }
    }
}