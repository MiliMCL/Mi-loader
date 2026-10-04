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
     *  wildcard import or be added here deliberately. */
    private static final List<String> ALLOWED_RUNTIME_PREFIXES = List.of(
            "import org.loader.runtime.RuntimeEnvironment;",
            "import org.loader.runtime.error.",
            "import org.loader.runtime.mod.",
            "import org.loader.runtime.kernel.Runtime;",
            "import org.loader.runtime.kernel.Scope;"
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
