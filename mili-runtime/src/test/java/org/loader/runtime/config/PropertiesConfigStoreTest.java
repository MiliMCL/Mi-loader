package org.loader.runtime.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.loader.api.config.ModConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties 配置存储的行为测试 —— 重点在「手改文件不可信」的容错语义。
 */
class PropertiesConfigStoreTest {

    @TempDir
    Path tempDir;

    private PropertiesConfigStore store() {
        return new PropertiesConfigStore(tempDir);
    }

    @Test
    @DisplayName("同一 modId 复用同一实例")
    void sameInstancePerMod() {
        var s = store();
        ModConfig a = s.load("mymod");
        ModConfig b = s.load("mymod");
        assertSame(a, b, "配置是单例语义 —— 两份实例会导致内存/磁盘状态分叉");
    }

    @Test
    @DisplayName("类型化读写与持久化往返")
    void typedRoundTrip() throws Exception {
        var s = store();
        ModConfig cfg = s.load("mymod");
        cfg.setInt("level", 3);
        cfg.setBoolean("enabled", true);
        cfg.setDouble("ratio", 0.5);
        cfg.set("name", "测试值");
        String path = cfg.save();

        assertTrue(Files.exists(Path.of(path)), "save() 返回的路径应存在");

        // 新 store 实例从磁盘读回
        var s2 = store();
        ModConfig cfg2 = s2.load("mymod");
        assertEquals(3, cfg2.getInt("level", 0));
        assertTrue(cfg2.getBoolean("enabled", false));
        assertEquals(0.5, cfg2.getDouble("ratio", 0.0));
        assertEquals("测试值", cfg2.getString("name", ""));
    }

    @Test
    @DisplayName("手改坏值：解析失败回默认值而非崩溃")
    void corruptedValuesFallBackToDefaults() throws Exception {
        ModConfig cfg = store().load("mymod");
        cfg.setInt("level", 3);
        cfg.save();

        // 模拟玩家手改坏
        Path file = Path.of(cfg.save());
        Files.writeString(file, "level=abc\nbrokenBool=maybe\nname=ok\n");

        ModConfig cfg2 = store().load("mymod");
        assertEquals(7, cfg2.getInt("level", 7),
                "非法整数必须回默认值");
        assertTrue(cfg2.getBoolean("brokenBool", true),
                "非法布尔必须回默认值");
        assertEquals("ok", cfg2.getString("name", "def"),
                "合法键照常读取");
    }

    @Test
    @DisplayName("缺失文件 = 全默认值；首存才建目录")
    void missingFileMeansDefaults() {
        ModConfig cfg = store().load("freshmod");
        assertFalse(cfg.has("anything"));
        assertEquals(42, cfg.getInt("anything", 42));
        assertFalse(Files.exists(tempDir.resolve("freshmod.properties")),
                "未 save 前不应创建文件");
    }
}
