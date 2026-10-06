package org.loader.loader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module boundary checks runnable on any machine (not just GitHub Actions).
 *
 * <ul>
 *   <li>LoaderMain entry point must not import minecraft-bridge internals
 *       ({@code org.loader.runtime.minecraft.*}). The loader talks to the
 *       minecraft bridge only via the {@code GameProvider} service
 *       interface.</li>
 *   <li>Loader source stays within the narrow runtime public surface
 *       (ModContext, Mod, ModManifest, ModLoadError,
 *       RuntimeEnvironment).</li>
 *   <li>No loader Java source line contains an absolute dev-machine path
 *       such as {@code E:\loader} or {@code C:\Users\...}.</li>
 * </ul>
 *
 * The remaining cross-module boundaries (mili-abi zero-dep, runtime not
 * importing minecraft-integration, etc.) are enforced statically in
 * {@code .github/workflows/ci.yml}'s {@code module-boundary-check} job.
 */
class ApiBoundaryTest {

    /** Narrow runtime surface the loader is allowed to depend on. Any other
     *  package rooted at {@code org.loader.runtime.} must come in via a
     *  wildcard import or be added here deliberately.
     *
     *  <p><b>Phase 2/3 note</b>: the loader legitimately needs three more
     *  surfaces now that the ClassLoader topology and the bootstrap state
     *  machine are real:
     *  <ul>
     *    <li>{@code org.loader.runtime.minecraft.BootstrapState} — the state
     *        machine the hook must advance. Only the enum, not the bridge
     *        internals.</li>
     *    <li>{@code org.loader.runtime.tick.TickContract} — the tick execution
     *        envelope, needed to expose the active contract to injectors.</li>
     *    <li>{@code org.loader.runtime.scheduler} — task handles.</li>
     *  </ul>
     *  {@code LoaderMain} is still forbidden from importing the bridge at all
     *  (enforced separately below); these are for the hook/provider layer. */
    private static final List<String> ALLOWED_RUNTIME_PREFIXES = List.of(
            "import org.loader.runtime.RuntimeEnvironment;",
            "import org.loader.runtime.error.",
            "import org.loader.runtime.mod.",
            "import org.loader.runtime.kernel.Runtime;",
            "import org.loader.runtime.kernel.Scope;",
            "import org.loader.runtime.tick.TickContract;",
            "import org.loader.runtime.scheduler.",
            // ClassTransformInterceptor 是「类加载 → 字节码转换」的唯一接缝。
            // 它按定义必须持有 TransformerPipeline —— 否则转换链无法被调用，
            // 整条 Transformation System 就是死代码。
            //
            // 这条白名单不放松 loader 的整体边界：白名单是<b>具体类</b>而非包，
            // 因此 loader 仍然不能碰 registry / verifier / ASM 等内部件。
            // 边界真正要防的是「loader 自行实现转换逻辑」，
            // 而不是「loader 调用平台提供的转换入口」。
            "import org.loader.runtime.transform.engine.TransformerPipeline;",
            // MinecraftGameProvider 需要 TransformerRegistry 构建注册表并登记
            // 平台自身的转换器（TransformerPipeline 由它构建后持有）。与上一条
            // 同类：白名单是具体类 —— loader 仍不能碰 ASM / verifier 等内部件。
            "import org.loader.runtime.transform.engine.TransformerRegistry;"
    );

    /** Path to loader's src/main/java. Gradle runs subproject tests with
     *  {@code user.dir} = the subproject directory. */
    private static Path loaderSrcDir() {
        Path cwd = Paths.get(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
        if (cwd.getFileName().toString().equals("mili-loader")) {
            return cwd.resolve("src/main/java");
        }
        // Loaded tests launched from the workspace root.
        return cwd.resolve("mili-loader/src/main/java");
    }

    @Test
    void loaderMainMustNotImportMinecraftBridge() throws IOException {
        Path main = loaderSrcDir().resolve("org/loader/loader/loader/LoaderMain.java");
        Path mainAlt = loaderSrcDir().resolve("org/loader/loader/LoaderMain.java");
        Path target = Files.exists(main) ? main : mainAlt;
        assertTrue(Files.exists(target),
                "LoaderMain.java not found at " + main + " or " + mainAlt);

        List<String> violations = new ArrayList<>();
        for (String raw : Files.readAllLines(target)) {
            String t = raw.trim();
            if (t.startsWith("import ") && !t.contains("static ")
                    && t.startsWith("import org.loader.runtime.minecraft.")) {
                violations.add(target.getFileName() + "  " + t);
            }
        }
        assertTrue(violations.isEmpty(),
                "LoaderMain must not import minecraft bridge packages. Violations:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    void loaderSourceStaysWithinAllowedRuntimeSurface() throws IOException {
        Path loaderSrc = loaderSrcDir();
        assertTrue(Files.isDirectory(loaderSrc),
                "Loader source dir not found: " + loaderSrc);

        List<String> violations = new ArrayList<>();
        Files.walkFileTree(loaderSrc, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("import ") || line.contains("static ")) continue;

                    // Fine if not in org.loader.runtime at all.
                    if (!line.startsWith("import org.loader.runtime.")) continue;
                    // Wildcard import is an explicit opt-in.
                    if (line.startsWith("import org.loader.runtime.*")) continue;
                    // minecraft-bridge imports are enforced separately (CI job +
                    // loaderMainMustNotImportMinecraftBridge) — don't double-flag here.
                    if (line.startsWith("import org.loader.runtime.minecraft.")) continue;

                    boolean allowed = false;
                    for (String p : ALLOWED_RUNTIME_PREFIXES) {
                        if (line.startsWith(p)) { allowed = true; break; }
                    }
                    if (!allowed) {
                        violations.add(file.getFileName() + ":" + (i + 1)
                                + "  " + line);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(violations.isEmpty(),
                "Loader source imports a runtime type outside the allowed surface. "
                        + "Add the prefix to ALLOWED_RUNTIME_PREFIXES in "
                        + "ApiBoundaryTest if this is intentional. Violations:\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    void loaderJavaSourceContainsNoHardcodedDevPaths() throws IOException {
        Path loaderSrc = loaderSrcDir();
        assertTrue(Files.isDirectory(loaderSrc),
                "Loader source dir not found: " + loaderSrc);

        List<String> violations = new ArrayList<>();
        Files.walkFileTree(loaderSrc, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) return FileVisitResult.CONTINUE;
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains("E:\\loader") || line.contains("E:/loader")
                            || line.contains("C:\\Users")
                            || line.contains("C:/Users")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//")
                                || trimmed.startsWith("*")
                                || trimmed.startsWith("/*")) {
                            continue;
                        }
                        violations.add(file.getFileName() + ":" + (i + 1)
                                + "  " + trimmed);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertTrue(violations.isEmpty(),
                "Loader source contains hardcoded dev-machine paths. Violations:\n  "
                        + String.join("\n  ", violations));
    }
}
