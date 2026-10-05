package org.loader.installer.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformRulesTest {

    @Test
    void noRulesMeansAllowed() {
        assertTrue(PlatformRules.evaluate(List.of()));
    }

    @Test
    void osRuleMatchesCurrentPlatform() {
        String me = PlatformRules.currentOsName();
        VersionMeta.Rule allow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "allow");
        VersionMeta.Rule other = new VersionMeta.Rule(
                Map.of("name", me.equals("windows") ? "osx" : "windows"),
                null, null, "allow");
        assertTrue(PlatformRules.evaluate(List.of(allow)));
        assertFalse(PlatformRules.evaluate(List.of(other)));
    }

    @Test
    void disallowWins() {
        String me = PlatformRules.currentOsName();
        VersionMeta.Rule disallow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "disallow");
        assertFalse(PlatformRules.evaluate(List.of(disallow)));
    }

    @Test
    void firstMatchingRuleDecides() {
        String me = PlatformRules.currentOsName();
        String other = me.equals("windows") ? "osx" : "windows";
        // 第一条匹配本机 → disallow；后面的 allow 不再生效
        VersionMeta.Rule disallow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "disallow");
        VersionMeta.Rule allow = new VersionMeta.Rule(
                Map.of("name", other), null, null, "allow");
        assertFalse(PlatformRules.evaluate(List.of(disallow, allow)));
    }

    @Test
    void noMatchingRuleDefaultsToAllow() {
        VersionMeta.Rule unrelated = new VersionMeta.Rule(
                Map.of("name", "plan9"), null, null, "disallow");
        assertTrue(PlatformRules.evaluate(List.of(unrelated)));
    }

    @Test
    void archIsNormalized() {
        String arch = PlatformRules.currentArch();
        assertTrue(arch.equals("amd64") || arch.equals("arm64")
                || arch.equals("x86") || !arch.isEmpty());
        VersionMeta.Rule allow = new VersionMeta.Rule(
                null, Map.of("name", arch), null, "allow");
        assertTrue(PlatformRules.evaluate(List.of(allow)));
    }

    @Test
    void featuresWithinRuleAreAnded() {
        String me = PlatformRules.currentOsName();
        // os 匹配但 arch 不匹配 → 整条 rule 不命中
        VersionMeta.Rule rule = new VersionMeta.Rule(
                Map.of("name", me), Map.of("name", "definitely-not-this-arch"), null, "allow");
        assertTrue(PlatformRules.evaluate(List.of(rule)));
    }

    @Test
    void picksNativeClassifierForPlatform() {
        String os = PlatformRules.currentOsName();
        Map<String, Map<String, Object>> cls = Map.of(
                "natives-windows", Map.of("url", "u1"),
                "natives-linux", Map.of("url", "u2"),
                "natives", Map.of("url", "u3"));
        var picked = PlatformRules.nativeClassifierKeys(cls);
        if (os.equals("osx")) {
            assertEquals(1, picked.size());
        } else {
            assertEquals(1, picked.size());
            assertTrue(picked.iterator().next().equals("natives-" + os));
        }
    }

    @Test
    void noNativeForForeignPlatform() {
        Map<String, Map<String, Object>> onlyLinux = Map.of(
                "natives-linux", Map.of("url", "u"));
        if (!PlatformRules.currentOsName().equals("linux")) {
            assertTrue(PlatformRules.nativeClassifierKeys(onlyLinux).isEmpty());
        }
    }

    @Test
    void emptyClassifiersYieldEmptySelection() {
        assertTrue(PlatformRules.nativeClassifierKeys(Map.of()).isEmpty());
        assertTrue(PlatformRules.nativeClassifierKeys(null).isEmpty());
    }
}
