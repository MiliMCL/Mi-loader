package org.loader.runtime.minecraft.transform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.loader.runtime.transform.verify.BytecodeVerifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link MiliCreativeTabLayoutTransformer} 对<b>真实 26.2 类</b>的转换测试。
 *
 * <h2>为什么必须用真实类</h2>
 * 目标方法是 private、带对象参数、且在原版里从未遇到哨兵列 —— 合成类
 * 能证明「注入发生了」，但证明不了「真实方法体能通过帧计算与验证」。
 * 这个转换器一旦失效，表现是「Mod 页签画到面板外」或「打开创造栏直接
 * AIOOBE」，都不是编译期能发现的问题。
 *
 * <p>jar 缺失时跳过（本地开发机不持有 Minecraft）；CI 会先下载。
 */
class MiliCreativeTabLayoutTransformerTest {

    private static final Path[] JAR_CANDIDATES = {
            Path.of("tools", "26.2", "26.2.jar"),
            Path.of("test_client", "26.2.jar"),
            Path.of("..", "tools", "26.2", "26.2.jar"),
            Path.of("..", "test_client", "26.2.jar"),
    };

    private static final String SCREEN =
            "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";

    private static Path minecraftJar;
    private static ClassLoader gameLoader;

    @BeforeAll
    static void locateMinecraft() {
        for (Path candidate : JAR_CANDIDATES) {
            if (Files.isRegularFile(candidate)) {
                minecraftJar = candidate.toAbsolutePath().normalize();
                break;
            }
        }
    }

    private static Path requireMinecraftJar() {
        assumeTrue(minecraftJar != null,
                "未找到真实 Minecraft jar（已查找 " + List.of(JAR_CANDIDATES) + "）。"
                        + "本测试刻意不使用 mock —— 需要真实 26.2 字节码验证帧计算。");
        return minecraftJar;
    }

    private static ClassLoader gameLoader() {
        if (gameLoader == null) {
            Path jar = requireMinecraftJar();
            try {
                gameLoader = new java.net.URLClassLoader(
                        "minecraft-26.2-creative-tab",
                        new java.net.URL[]{jar.toUri().toURL()},
                        // 父加载器用测试类加载器：转换后的类会调用平台分发器
                        // CreativeTabLayout，验证器必须能解析它。生产环境的
                        // MinecraftClassLoader 同样以平台加载器为父。
                        MiliCreativeTabLayoutTransformerTest.class.getClassLoader());
            } catch (java.net.MalformedURLException e) {
                throw new AssertionError("无法为 " + jar + " 构造 URLClassLoader", e);
            }
        }
        return gameLoader;
    }

    private static byte[] screenBytes() {
        Path jar = requireMinecraftJar();
        String entry = SCREEN + ".class";
        try (ZipFile zip = new ZipFile(jar.toFile());
             InputStream in = zip.getInputStream(zip.getEntry(entry))) {
            assertNotNull(in, "jar 中缺少 " + entry);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("无法从 " + jar + " 读取 " + entry, e);
        }
    }

    @Test
    @DisplayName("真实创造界面的 getTabX 被替换，extractTabButton 的 clamp 被重定向")
    void rewritesRealCreativeScreen() {
        byte[] transformed = new MiliCreativeTabLayoutTransformer()
                .rewrite(screenBytes());

        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);

        MethodNode getTabX = method(node, "getTabX");
        assertNotNull(getTabX, "转换后应保留 getTabX");
        assertEquals(1, countOpcode(getTabX, Opcodes.IRETURN),
                "getTabX 的方法体应被替换为单返回路径");
        assertEquals(1, countCalls(getTabX, MiliCreativeTabLayoutTransformer.DISPATCH_OWNER,
                        "tabX"),
                "getTabX 应委托给 CreativeTabLayout.tabX");
        assertTrue(callsWithDesc(getTabX, MiliCreativeTabLayoutTransformer.DISPATCH_OWNER,
                        "tabX", "(IIZ)I"),
                "tabX 的描述符必须是 (int,int,boolean)→int");

        MethodNode extract = method(node, "extractTabButton");
        assertNotNull(extract, "转换后应保留 extractTabButton");
        assertEquals(0, countCalls(extract, "net/minecraft/util/Mth", "clamp"),
                "extractTabButton 里的 Mth.clamp 应全部被重定向");
        assertTrue(countCalls(extract, MiliCreativeTabLayoutTransformer.DISPATCH_OWNER,
                        "clampSprite") >= 1,
                "应至少有一处调用 clampSprite");
        assertTrue(callsWithDesc(extract, MiliCreativeTabLayoutTransformer.DISPATCH_OWNER,
                        "clampSprite", "(III)I"),
                "clampSprite 的描述符必须与 Mth.clamp 一致");
    }

    @Test
    @DisplayName("转换后的真实类通过结构 + 数据流验证")
    void transformedClassVerifies() {
        byte[] transformed = new MiliCreativeTabLayoutTransformer()
                .rewrite(screenBytes());

        // 传入游戏类加载器：验证器要解析 GuiGraphicsExtractor / CreativeModeTab
        // 等类型；用平台加载器会把正确字节码误判为非法。
        BytecodeVerifier.verify(SCREEN, transformed,
                MiliCreativeTabLayoutTransformer.ID, null, gameLoader());
    }

    // ── ASM 辅助 ────────────────────────────────────────────────────────────

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static int countOpcode(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == opcode) {
                count++;
            }
        }
        return count;
    }

    private static int countCalls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static boolean callsWithDesc(MethodNode method, String owner,
                                         String name, String desc) {
        List<String> seen = new ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst();
             insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner) && call.name.equals(name)) {
                seen.add(call.desc);
            }
        }
        return seen.contains(desc);
    }
}
