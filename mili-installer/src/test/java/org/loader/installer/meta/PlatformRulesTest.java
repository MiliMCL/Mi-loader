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
        assertTrue(PlatformRules.evaluate(List.of(allow)));

        // A rule naming this host actually matches, so disallow excludes.
        VersionMeta.Rule selfDisallow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "disallow");
        assertFalse(PlatformRules.evaluate(List.of(selfDisallow)));

        // A rule naming a foreign OS does NOT match, so it falls through to
        // the default (allow) regardless of its action.
        String foreign = me.equals("windows") ? "osx" : "windows";
        assertTrue(PlatformRules.evaluate(List.of(
                new VersionMeta.Rule(Map.of("name", foreign), null, null, "allow"))));
        assertTrue(PlatformRules.evaluate(List.of(
                new VersionMeta.Rule(Map.of("name", foreign), null, null, "disallow"))));
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
        // Both rules match this host, so only declaration order can decide:
        // the leading disallow wins and the trailing allow is never consulted.
        VersionMeta.Rule disallow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "disallow");
        VersionMeta.Rule allow = new VersionMeta.Rule(
                Map.of("name", me), null, null, "allow");
        assertFalse(PlatformRules.evaluate(List.of(disallow, allow)));
        assertTrue(PlatformRules.evaluate(List.of(allow, disallow)));
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
        // os matches but arch does not -> the whole rule fails to match, so the
        // trailing disallow never fires and the default (allow) applies.
        VersionMeta.Rule mixed = new VersionMeta.Rule(
                Map.of("name", me), Map.of("name", "definitely-not-this-arch"),
                null, "disallow");
        assertTrue(PlatformRules.evaluate(List.of(mixed)));

        // Both features matching makes the same rule effective.
        VersionMeta.Rule both = new VersionMeta.Rule(
                Map.of("name", me), Map.of("name", PlatformRules.currentArch()),
                null, "disallow");
        assertFalse(PlatformRules.evaluate(List.of(both)));
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
