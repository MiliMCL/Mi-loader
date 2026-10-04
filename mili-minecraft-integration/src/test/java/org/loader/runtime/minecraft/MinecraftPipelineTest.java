package org.loader.runtime.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minecraft pipeline tests. Require real MC JAR to run;
 * skip (assumptions) when artifact is unavailable.
 */
class MinecraftPipelineTest {

    private static final String MC_VERSION = "26.2";

    private File findMinecraftJar() {
        for (String p : new String[]{ "test_client", "../test_client" }) {
            File dir = new File(p);
            if (dir.isDirectory()) {
                File[] jars = dir.listFiles(f -> f.getName().endsWith(".jar") && f.getName().contains(MC_VERSION));
                if (jars != null && jars.length > 0) return jars[0];
            }
        }
        return null;
    }

    @Test
    @DisplayName("MC JAR resolvable & readable")
    void minecraftJarReadable() {
        File jar = findMinecraftJar();
        Assumptions.assumeTrue(jar != null);
        assertTrue(jar.exists());
        assertTrue(jar.length() > 1_000_000);
    }

    @Test
    @DisplayName("MC JAR is valid ZIP")
    void minecraftJarValidZip() throws Exception {
        File jar = findMinecraftJar();
        Assumptions.assumeTrue(jar != null);
        try (ZipFile zf = new ZipFile(jar)) {
            boolean ok = zf.getEntry("META-INF/MANIFEST.MF") != null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements() && !ok) {
                String name = en.nextElement().getName();
                if (name.startsWith("net/minecraft/")) ok = true;
            }
            assertTrue(ok, "MC JAR must contain MC classes");
        }
    }

    @Test
    @DisplayName("SHA-256 deterministic over MC JAR")
    void shaDeterministic() throws Exception {
        File jar = findMinecraftJar();
        Assumptions.assumeTrue(jar != null);
        String a = sha(jar), b = sha(jar);
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("Bundler / flat unpack produces class files")
    void unpackProducesClasses() throws Exception {
        File jar = findMinecraftJar();
        Assumptions.assumeTrue(jar != null);
        Path tmp = Files.createTempDirectory("mc-extract-");
        try {
            probeAndUnpack(jar, tmp.toFile());
            long count;
            try (Stream<Path> s = Files.walk(tmp)) {
                count = s.filter(p -> p.toString().endsWith(".class")).count();
            }
            assertTrue(count > 0, "extracted at least one class");
        } finally {
            // best-effort cleanup
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void probeAndUnpack(File jar, File target) throws Exception {
        target.mkdirs();
        try (ZipFile zf = new ZipFile(jar)) {
            // Find bundler inner JAR
            String prefix = "";
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("META-INF/versions/") && name.endsWith(".jar") && name.split("/").length >= 4) {
                    prefix = name.substring(0, name.lastIndexOf('/') + 1);
                    break;
                }
            }
            if (prefix.isEmpty()) {
                // flat JAR
                Enumeration<? extends ZipEntry> en2 = zf.entries();
                while (en2.hasMoreElements()) {
                    ZipEntry e = en2.nextElement();
                    if (e.isDirectory()) continue;
                    File out = new File(target, e.getName());
                    File pf = out.getParentFile();
                    if (pf != null) pf.mkdirs();
                    Files.copy(zf.getInputStream(e), out.toPath());
                }
            } else {
                String fp = prefix;
                ZipEntry inner = null;
                Enumeration<? extends ZipEntry> en3 = zf.entries();
                while (en3.hasMoreElements()) {
                    ZipEntry e = en3.nextElement();
                    if (e.getName().startsWith(fp) && e.getName().endsWith(".jar")) { inner = e; break; }
                }
                if (inner == null) return;
                File tmp = File.createTempFile("mc-", ".jar");
                tmp.deleteOnExit();
                Files.copy(zf.getInputStream(inner), tmp.toPath());
                try (ZipFile zf2 = new ZipFile(tmp)) {
                    Enumeration<? extends ZipEntry> en4 = zf2.entries();
                    while (en4.hasMoreElements()) {
                        ZipEntry e = en4.nextElement();
                        if (e.isDirectory()) continue;
                        String name = e.getName();
                        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                        File out = new File(target, name);
                        File pf = out.getParentFile();
                        if (pf != null) pf.mkdirs();
                        Files.copy(zf2.getInputStream(e), out.toPath());
                    }
                }
            }
        }
    }

    private static String sha(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[8192];
            int n; while ((n = in.read(buf)) > 0) d.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : d.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
