package org.loader.loader.transform;

import org.loader.api.transform.TransformationEnvironment;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.kernel.Scope;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类转换拦截器 —— 平台把转换能力接到 ClassLoader 上的唯一位置。
 *
 * <h2>为什么插在 findClass 之后、defineClass 之前</h2>
 * ClassLoader 的加载链是：
 * <pre>
 *   loadClass(name)
 *     └─ findClass(name)          ← URLClassLoader 从 jar 读出原始字节并 defineClass
 * </pre>
 *
 * <p>本平台的 {@code MinecraftClassLoader} 是 self-first 的：
 * Minecraft 与 Mojang 库优先本地加载。因此 {@code findClass}
 * 正是「读出 Minecraft 原始字节码」的那一步 —— 转换必须插在这里，
 * 而不是 {@code loadClass}：
 * <ul>
 *   <li>插在 {@code loadClass} 之前 → 会把转换施加到平台自己的类上
 *       （{@code org.loader.*}），那是错的；</li>
 *   <li>插在 {@code findClass} 之后 → 拿不到字节码；</li>
 *   <li>插在 {@code defineClass} 内部 → 那是 {@code URLClassLoader}
 *       的私有实现，{@code URLClassLoader} 没有提供「转换钩子」，
 *       只能靠继承并覆写，而覆写 {@code defineClass} 需要重写整个
 *       资源查找逻辑。</li>
 * </ul>
 *
 * <p>因此本类的作用是<b>读取字节码 → 交给流水线 → 再定义</b>。
 * 它把 {@code findClass} 拆成「读」与「定义」两步，用一次
 * {@code defineClass} 调用完成，不依赖 JDK 内部 API。
 *
 * <h2>为什么不用 JVMTI 或 {@code -javaagent}</h2>
 * 那两条路都需要在类加载<b>之前</b>接管，对本平台不合适：
 * <ul>
 *   <li>当前架构用 {@code URLClassLoader} 显式管理 Minecraft classpath，
 *       见 {@code MinecraftDiscovery}；</li>
 *   <li>Mod 热重载需要能丢弃某个 ClassLoader 定义的全部类，
 *       JVMTI 做不到；</li>
 *   <li>引入 agent 会让「平台自己跑测试」与「用户跑游戏」走两套路径 ——
 *       而两套路径的行为差异正是最难查的一类 bug。</li>
 * </ul>
 *
 * <h2>异常策略</h2>
 * 转换失败<b>必须</b>抛出，让类加载失败并让游戏在启动阶段报错。
 * 绝不能「转换失败就用原始字节码继续」—— 那等于把
 * {@code TransformationTargetNotFoundException} 重新变成静默失效，
 * 正是本系统存在的意义所要避免的事。
 */
public final class ClassTransformInterceptor {

    /** 读取字节码失败时的内部标记 —— 不对外暴露。 */
    private static final byte[] NOT_FOUND = new byte[0];

    private final TransformerPipeline pipeline;
    private final TransformationEnvironment environment;
    private final Scope platformScope;

    private final AtomicLong interceptedClasses = new AtomicLong();
    private final AtomicLong readFailures = new AtomicLong();

    public ClassTransformInterceptor(
            TransformerPipeline pipeline,
            TransformationEnvironment environment,
            Scope platformScope) {

        if (pipeline == null) {
            throw new IllegalStateException("ClassTransformInterceptor 必须有 pipeline");
        }
        if (environment == null) {
            throw new IllegalStateException(
                    "ClassTransformInterceptor 必须有 environment");
        }
        this.pipeline = pipeline;
        this.environment = environment;
        this.platformScope = platformScope;
    }

    /**
     * 读取并转换一个类的字节码。
     *
     * <p>供 ClassLoader 在 {@code findClass} 中调用。
     *
     * @param name        二进制类名（点分，如 {@code net.minecraft.server.MinecraftServer}）
     * @param loader      即将定义该类的 ClassLoader（用作缓存键）
     * @param rawStream   原始字节流；由调用方负责关闭
     * @return 转换后的字节码；无匹配转换器时返回原始字节
     * @throws IOException 读取失败
     * @throws org.loader.api.transform.TransformationException 转换失败
     */
    public byte[] transformFromStream(
            String name, ClassLoader loader, InputStream rawStream) throws IOException {

        byte[] original = readAllBytes(rawStream);
        if (original == NOT_FOUND || original.length == 0) {
            readFailures.incrementAndGet();
            throw new IOException("无法读取类字节码: " + name);
        }
        return transform(name, loader, original);
    }

    /**
     * 转换一段已读出的字节码。
     *
     * @param dottedName 二进制类名（点分）
     * @param loader     即将定义该类的 ClassLoader
     * @param original   原始字节码
     * @return 转换后的字节码（可能与 original 是同一数组）
     */
    public byte[] transform(String dottedName, ClassLoader loader, byte[] original) {
        if (dottedName == null || original == null) {
            throw new IllegalArgumentException("类名与字节码不能为 null");
        }
        String internalName = dottedName.replace('.', '/');

        // 平台自身的类一律不转换。理由：
        // 1) 它们不该被 Mod 改 —— 那会让平台行为依赖装了哪些 Mod；
        // 2) 转换平台类会引入对 Mod 类的引用，形成 ClassLoader 泄漏。
        if (internalName.startsWith("org/loader/")) {
            return original;
        }

        interceptedClasses.incrementAndGet();
        return pipeline.transform(internalName, original, environment, platformScope);
    }

    /**
     * 读取全部字节 —— 自实现而非用 {@code readAllBytes()}。
     *
     * <p>{@code InputStream.readAllBytes()} 要求流支持 mark/reset 才能复用，
     * 而 jar 的流不支持。这里每次都是新流，直接一次性读完即可。
     */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        if (in == null) {
            return NOT_FOUND;
        }
        java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public TransformerPipeline pipeline() {
        return pipeline;
    }

    public TransformationEnvironment environment() {
        return environment;
    }

    /** 已进入转换流程的类数量（不含被平台自有前缀短路的）。 */
    public long interceptedClassCount() {
        return interceptedClasses.get();
    }

    public long readFailureCount() {
        return readFailures.get();
    }

    public String diagnostics() {
        return "ClassTransformInterceptor[intercepted="
                + interceptedClasses.get()
                + ", readFailures=" + readFailures.get() + "]";
    }
}