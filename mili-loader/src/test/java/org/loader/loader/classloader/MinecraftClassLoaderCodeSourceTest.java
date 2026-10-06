package org.loader.loader.classloader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.api.transform.MiliTransformer;
import org.loader.api.transform.TransformationContext;
import org.loader.api.transform.TransformationEnvironment;
import org.loader.api.transform.TransformationResult;
import org.loader.loader.transform.ClassTransformInterceptor;
import org.loader.runtime.transform.engine.TransformerPipeline;
import org.loader.runtime.transform.engine.TransformerRegistry;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 拦截路径定义的类必须携带与 {@code super.findClass} 完全一致的
 * CodeSource —— 这是 {@code SecurityException: signer information
 * does not match} 的回归测试。
 *
 * <h2>生产事故还原</h2>
 * Mojang 客户端 jar 是签名 jar。若拦截路径用裸
 * {@code defineClass(name, bytes, off, len)}，定义出的类没有任何证书；
 * 而拦截器装配前（Mod initialize()、canLoad）由 {@code super.findClass}
 * 定义的类带 Mojang 条目证书。同一个 {@code net.minecraft.*} 包里两类
 * 证书混装，JDK 的包级一致性校验直接抛 SecurityException —— 游戏启动
 * 即炸，且炸点随类加载顺序漂移（CreativeModeTab$Row / FeatureFlag /
 * Item$Properties / RegistryFixedCodec……）。
 *
 * <p>本测试用未签名 jar 无法复现「证书不同」（两侧都是 null），但能
 * 钉死 CodeSource 的<b>结构一致性</b>：拦截路径与标准路径的 location
 * 必须同源、必须同时存在。签名 jar 下两者共享同一条目证书来源
 * （JarURLConnection.getCertificates 与 URLClassLoader 同源），结构一致
 * 即证书一致。
 */
class MinecraftClassLoaderCodeSourceTest {

    private static final String TARGET_DOTTED = "net.minecraft.server.MinecraftServer";
    private static final String TARGET_SLASH = "net/minecraft/server/MinecraftServer";
    private static final String PLAIN_DOTTED = "net.minecraft.server.GameRules";
    private static final String PLAIN_SLASH = "net/minecraft/server/GameRules";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("拦截路径与标准路径的 CodeSource 必须完全一致")
    void interceptorPathUsesStandardCodeSource() throws Exception {
        Path jar = buildGameJar();
        URL jarUrl = jar.toUri().toURL();
        ClassLoader parent = MinecraftClassLoaderCodeSourceTest.class.getClassLoader();

        // ── 拦截路径：装了转换器，被匹配类与未匹配类都走拦截定义 ──
        MinecraftClassLoader hooked = new MinecraftClassLoader(
                "minecraft-game-hooked", new URL[]{jarUrl}, parent);
        try {
            hooked.setInterceptor(new ClassTransformInterceptor(
                    pipeline(), env(parent), null));

            Class<?> transformed = hooked.loadClass(TARGET_DOTTED);
            Class<?> plain = hooked.loadClass(PLAIN_DOTTED);

            CodeSource csTransformed = transformed.getProtectionDomain().getCodeSource();
            CodeSource csPlain = plain.getProtectionDomain().getCodeSource();

            assertNotNull(csTransformed,
                    "被转换的类必须有 CodeSource。裸 defineClass 产生 null 域 —— "
                            + "与签名 jar 的标准路径类同包混装即 SecurityException。");
            assertNotNull(csPlain,
                    "未被转换的类（经拦截器放行）也必须有 CodeSource。");

            // ── 标准路径参照系：无拦截器，走 super.findClass ──
            MinecraftClassLoader vanilla = new MinecraftClassLoader(
                    "minecraft-game-vanilla", new URL[]{jarUrl}, parent);
            try {
                Class<?> reference = vanilla.loadClass(TARGET_DOTTED);
                CodeSource csReference = reference.getProtectionDomain().getCodeSource();

                assertNotNull(csReference, "标准路径必然有 CodeSource（URLClassLoader 语义）");
                assertEquals(csReference.getLocation(), csTransformed.getLocation(),
                        "拦截路径的 CodeSource location 必须与标准路径同源（jar 本身的 URL）。"
                                + "不一致会让同包类的保护域漂移。");
                assertEquals(csReference.getLocation(), csPlain.getLocation(),
                        "未匹配类的 CodeSource 也必须与标准路径同源。");
                assertNull(csReference.getCertificates(),
                        "未签名 jar 的参照证书为 null（已签名 jar 下两者同取条目证书）");
                assertNull(csTransformed.getCertificates(),
                        "未签名 jar 下拦截路径证书也必须为 null —— 与参照系一致");
                assertNull(csPlain.getCertificates(),
                        "未匹配类同理");
            } finally {
                vanilla.close();
            }
        } finally {
            hooked.close();
        }
    }

    // ── 测试构件 ────────────────────────────────────────────────────

    /** 打一个含两个类的游戏 jar（未签名 —— 与测试目的一致）。 */
    private Path buildGameJar() throws Exception {
        Path jar = tmp.resolve("game.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(TARGET_SLASH + ".class"));
            out.write(serverClassBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry(PLAIN_SLASH + ".class"));
            out.write(plainClassBytes());
            out.closeEntry();
        }
        return jar;
    }

    private static byte[] serverClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TARGET_SLASH, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tickServer",
                "(Ljava/util/function/BooleanSupplier;)V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] plainClassBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                PLAIN_SLASH, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 只匹配目标类、原样返回字节码 —— 让类走「拦截定义」路径。 */
    private static MiliTransformer passthroughProbe() {
        return new MiliTransformer() {
            @Override
            public String id() {
                return "codesource-probe";
            }

            @Override
            public String minecraftVersion() {
                return "26.2";
            }

            @Override
            public boolean matches(String className) {
                return TARGET_DOTTED.equals(className);
            }

            @Override
            public TransformationResult transform(TransformationContext context) {
                return new TransformationResult.Transformed(context.originalBytes());
            }
        };
    }

    private static TransformerPipeline pipeline() {
        TransformerRegistry registry = new TransformerRegistry("26.2");
        registry.register(passthroughProbe(), null);
        registry.seal();
        return TransformerPipeline.builder().registry(registry).build();
    }

    private static TransformationEnvironment env(ClassLoader loader) {
        return new TransformationEnvironment(
                loader, "26.2",
                TransformationEnvironment.RuntimeEnvironmentValue.DEDICATED_SERVER,
                "0.1.0-test");
    }
}
