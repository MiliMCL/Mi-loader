package org.loader.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.loader.discovery.ModDiscovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数回归：平台必须把 {@code --gameDir} 与 {@code --mili-mods}
 * 正确地传递下去。
 *
 * <h2>这个测试在防什么</h2>
 *
 * <p><b>bug 1：资源（背景图 / 多语言 / 音效）全部丢失。</b>
 * {@code args[0]} 是 gameDir，但只有平台看得见它 —— Minecraft 自己收不到。
 * Minecraft 在拿不到 {@code --gameDir} 时会把<b>当前工作目录</b>当游戏目录，
 * 而启动脚本是在 {@code bin/} 里执行 java 的，于是：
 *
 * <pre>
 *   资源索引  bin/assets/indexes/32.json   ← 不存在
 *   真实位置  game/assets/indexes/32.json  ← 资源确实下载在这里
 * </pre>
 *
 * <p>索引读不到 → 界面背景图、多语言（lang）、全部音效一起消失。
 * 表面看是「资源没下载」，实际是<b>游戏在错误的目录里找资源</b>。
 *
 * <p><b>bug 2：Mod 一个都不加载。</b>
 * gameDir 是 {@code <dist>/game}，而 Mod 在 {@code <dist>/mods}。
 * 平台若回退到 {@code gameDir/mods}就会去看一个从不存在的目录，
 * {@link ModDiscovery} 不抛异常也不打警告，只是返回空列表 ——
 * 游戏照常启动，玩家看到的是一个「没装任何 Mod」的干净世界。
 *
 * @see LoaderMain#parseArgs(String[])
 */
class LaunchArgPassthroughTest {

    @Test
    @DisplayName("gameDir 会被注入为 --gameDir 传给 Minecraft（资源/存档依赖它）")
    void gameDirIsForwardedAsGameDirOption(@TempDir Path tmp) {
        Path gameDir = tmp.resolve("game");
        var opts = LoaderMain.parseArgs(new String[]{gameDir.toString()});

        List<String> mc = List.of(opts.mcArgs());
        int i = mc.indexOf("--gameDir");
        assertTrue(i >= 0,
                "Minecraft 参数里必须有 --gameDir，否则它退回当前工作目录(bin/)，"
                        + "资源索引/背景图/多语言/音效会全部丢失。实际参数: " + mc);
        assertEquals(gameDir.toAbsolutePath().normalize().toString(), mc.get(i + 1),
                "--gameDir 必须指向平台实际使用的 game 目录");
    }

    @Test
    @DisplayName("调用方已显式指定 --gameDir 时不重复注入")
    void explicitGameDirIsNotDuplicated(@TempDir Path tmp) {
        Path gameDir = tmp.resolve("game");
        Path explicitDir = tmp.resolve("elsewhere");
        var opts = LoaderMain.parseArgs(new String[]{
                gameDir.toString(), "--gameDir", explicitDir.toString()});

        long count = List.of(opts.mcArgs()).stream().filter("--gameDir"::equals).count();
        assertEquals(1, count, "--gameDir 不应被注入两次（后者会覆盖前者的语义）");
    }

    @Test
    @DisplayName("启动脚本传入的 --mili-mods 生效，Mod 从 <dist>/mods 被发现")
    void miliModsOptionSelectsDistributionModsDir(@TempDir Path tmp) throws Exception {
        Path gameDir = Files.createDirectories(tmp.resolve("game"));
        Path modsDir = Files.createDirectories(tmp.resolve("mods"));
        Path jar = modsDir.resolve("demo.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("META-INF/mod.json"));
            out.write("""
                    {"id":"demo","version":"1.0.0","entrypoint":"demo.Main",
                     "mili":{"platform":"0.1.0","abi":1,"minecraft":"26.2"}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }

        // 模拟启动脚本：java -jar platform.jar <dist>/game --mili-mods <dist>/mods ...
        var opts = LoaderMain.parseArgs(new String[]{
                gameDir.toString(), "--mili-mods", modsDir.toString()});

        assertEquals(modsDir.toAbsolutePath().normalize(), opts.modsDir(),
                "--mili-mods 指定的目录必须被原样采用");

        var found = ModDiscovery.scan(
                org.loader.loader.config.LoaderConfig.at(opts.gameDir(), opts.modsDir()))
                .discover();
        assertEquals(1, found.size(),
                "Mod 应从 " + modsDir + " 被发现 —— 若这里为 0，游戏会正常启动但没有 Mod");
        assertEquals("demo", found.get(0).id());
    }

    @Test
    @DisplayName("未传 --mili-mods 时回退到 gameDir/mods（保持既有布局兼容）")
    void fallsBackToGameDirMods(@TempDir Path tmp) {
        Path gameDir = tmp.resolve("game");
        var opts = LoaderMain.parseArgs(new String[]{gameDir.toString()});
        assertEquals(gameDir.toAbsolutePath().normalize().resolve("mods"), opts.modsDir());
    }

    @Test
    @DisplayName("--mili-mods 不会被当成 Minecraft 参数转发（否则 joptsimple 直接拒绝启动）")
    void platformOptionIsStrippedFromMcArgs(@TempDir Path tmp) {
        var opts = LoaderMain.parseArgs(new String[]{
                tmp.resolve("game").toString(),
                "--mili-mods", tmp.resolve("mods").toString()});

        assertFalse(List.of(opts.mcArgs()).contains("--mili-mods"),
                "平台自己的选项必须留在平台侧；转给 Minecraft 会被 joptsimple "
                        + "判为 UnrecognizedOptionException 而拒绝启动");
    }
}