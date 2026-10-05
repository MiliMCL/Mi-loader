package org.loader.installer.meta;

import org.junit.jupiter.api.Test;
import org.loader.installer.json.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojangMetaClientTest {

    /** 裁剪自官方 26.2 version.json 的真实结构。 */
    private static final String SAMPLE = """
        {
          "id": "26.2",
          "releaseType": "release",
          "javaVersion": {"component": "java-runtime-epsilon", "majorVersion": "25"},
          "downloads": {
            "client": {
              "sha1": "2dc72797acbc1b63fc16a11c4ac393605f453754",
              "size": 39193383,
              "url": "https://piston-data.mojang.com/v1/objects/2dc727/client.jar"
            }
          },
          "assetIndex": {
            "id": "32",
            "sha1": "9a622c23d8ff76c4c44787c2e171c56feed1f786",
            "size": 586366,
            "totalSize": 480579086,
            "url": "https://piston-meta.mojang.com/v1/packages/9a62/32.json"
          },
          "logging": {
            "client": {
              "argument": "-Dlog4j.configurationFile=${path}",
              "file": {
                "sha1": "39384bd14c0606d812afec88d8aff595b2587dd9",
                "size": 1073,
                "url": "https://piston-data.mojang.com/x/client-1.21.2.xml"
              }
            }
          },
          "libraries": [
            {
              "name": "com.google.code.gson:gson:2.14.0",
              "downloads": {
                "artifact": {
                  "path": "com/google/code/gson/gson/2.14.0/gson-2.14.0.jar",
                  "sha1": "efc0e34ede4e3204eaefb84a00e55e8c86634382",
                  "size": 311000,
                  "url": "https://libraries.minecraft.net/gson.jar"
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.3",
              "downloads": {
                "artifact": {
                  "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar",
                  "sha1": "aaaaaaaa",
                  "size": 900000,
                  "url": "https://libraries.minecraft.net/lwjgl.jar"
                },
                "classifiers": {
                  "natives-windows": {
                    "sha1": "bbbbbbbb",
                    "url": "https://libraries.minecraft.net/lwjgl-natives-win.zip"
                  },
                  "natives-linux": {
                    "sha1": "cccccccc",
                    "url": "https://libraries.minecraft.net/lwjgl-natives-linux.zip"
                  }
                }
              },
              "rules": [
                {"action": "allow", "os": {"name": "windows"}},
                {"action": "allow", "os": {"name": "linux"}, "arch": {"name": "x86"}}
              ]
            },
            {
              "name": "javaobj-encoders:javaobj-encoders:0.4.0",
              "downloads": {
                "classifiers": {
                  "natives-macos": {
                    "sha1": "dddddddd",
                    "url": "https://libraries.minecraft.net/jobj-mac.zip"
                  }
                }
              },
              "rules": [{"action": "allow", "os": {"name": "osx"}}]
            }
          ]
        }
        """;

    @Test
    void parsesCoreFields() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        assertEquals("26.2", meta.id());
        assertEquals("25", meta.javaMajorVersion());
        assertEquals("release", meta.releaseType());
        assertEquals(39193383, meta.clientSize());
        assertEquals("2dc72797acbc1b63fc16a11c4ac393605f453754", meta.clientSha1());
    }

    @Test
    void parsesAssetIndex() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        assertNotNull(meta.assetIndex());
        assertEquals("32", meta.assetIndex().id());
        assertEquals(480579086L, meta.assetIndex().totalSize());
    }

    @Test
    void parsesLoggingClient() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        assertNotNull(meta.loggingClientUrl());
        assertTrue(meta.loggingClientUrl().endsWith("client-1.21.2.xml"));
    }

    @Test
    void parsesLibrariesAndClassifiers() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        assertEquals(3, meta.libraries().size());

        VersionMeta.Library gson = meta.libraries().get(0);
        assertEquals("com.google.code.gson:gson:2.14.0", gson.name());
        assertTrue(gson.hasMainArtifact());
        assertTrue(gson.rules().isEmpty());
        assertTrue(gson.isAllowedOnCurrentPlatform());

        VersionMeta.Library lwjgl = meta.libraries().get(1);
        assertEquals(2, lwjgl.classifiers().size());
        assertNotNull(lwjgl.classifiers().get("natives-windows"));
        assertEquals(2, lwjgl.rules().size());
    }

    @Test
    void libraryWithoutMainArtifactIsDetected() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        VersionMeta.Library jobj = meta.libraries().get(2);
        assertFalse(jobj.hasMainArtifact());
        assertNull(jobj.artifactPath());
    }

    @Test
    void osSpecificLibraryIsFilteredOnOtherPlatforms() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        VersionMeta.Library jobj = meta.libraries().get(2);
        boolean isMac = PlatformRules.currentOsName().equals("osx");
        assertEquals(isMac, jobj.isAllowedOnCurrentPlatform());
    }

    @Test
    void rulesAreEvaluatedNotPreResolved() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(SAMPLE));
        VersionMeta.Library lwjgl = meta.libraries().get(1);
        // rules 存在但未在解析期求值 —— 求值必须发生在安装现场
        assertFalse(lwjgl.rules().isEmpty());
    }

    @Test
    void rejectsMetaWithoutDownloads() {
        assertThrows(IllegalArgumentException.class, () ->
                MojangMetaClient.parseVersionMeta(Json.parseObject("{\"id\":\"x\"}")));
    }

    @Test
    void rejectsMetaWithoutClientDownload() {
        assertThrows(IllegalArgumentException.class, () ->
                MojangMetaClient.parseVersionMeta(Json.parseObject(
                        "{\"id\":\"x\",\"downloads\":{\"server\":{\"url\":\"u\",\"sha1\":\"s\"}}}")));
    }

    @Test
    void missingAssetIndexBecomesNull() {
        VersionMeta meta = MojangMetaClient.parseVersionMeta(Json.parseObject(
                "{\"id\":\"x\",\"downloads\":{\"client\":{\"url\":\"u\",\"sha1\":\"s\",\"size\":1}}}"));
        assertNull(meta.assetIndex());
        assertNull(meta.loggingClientUrl());
    }
}
