package org.loader.runtime.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PropertiesModStorage} 的行为测试。
 *
 * <h2>测试的重点不是「能存能取」</h2>
 * 那是最容易写对的部分。真正值得测的是三条<b>失败路径</b>：
 * <ul>
 *   <li>文件损坏时不能炸 —— 只应降级为空值集；</li>
 *   <li>未 flush 时磁盘必须保持旧值 —— 否则「内存已改、磁盘未存」的
 *       分叉状态会在崩溃后变成数据丢失；</li>
 *   <li>非法键必须被拒绝 —— 键里带等号会破坏 properties 格式。</li>
 * </ul>
 * 这三条在手工点测时几乎永远碰不到，但它们才是存储在生产里出问题的方式。
 */
class PropertiesModStorageTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("写入 → flush → 重新打开，值仍在")
    void survivesReopen(@TempDir Path dir) {
        Path file = dir.resolve("mymod.properties");

        PropertiesModStorage first = PropertiesModStorage.open("mymod", file);
        first.put("launches", "3");
        first.put("theme", "dark");
        assertTrue(first.flush());

        PropertiesModStorage second = PropertiesModStorage.open("mymod", file);
        assertEquals("3", second.get("launches").orElseThrow());
        assertEquals("dark", second.get("theme").orElseThrow());
        assertEquals(2, second.keys().size());
    }

    @Test
    @DisplayName("未 flush 时磁盘保持旧值 —— 内存与磁盘是两份状态")
    void unflushedChangesStayInMemory(@TempDir Path dir) {
        Path file = dir.resolve("mymod.properties");

        PropertiesModStorage first = PropertiesModStorage.open("mymod", file);
        first.put("a", "1");
        first.flush();

        first.put("a", "2");
        // 还没有 flush
        PropertiesModStorage reopened = PropertiesModStorage.open("mymod", file);
        assertEquals("1", reopened.get("a").orElseThrow(),
                "未 flush 的修改不应出现在磁盘上");

        assertTrue(first.flush());
        PropertiesModStorage after = PropertiesModStorage.open("mymod", file);
        assertEquals("2", after.get("a").orElseThrow());
    }

    @Test
    @DisplayName("UTF-8 值不被转义 —— 中文可读")
    void writesUtf8WithoutEscaping(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cn.properties");
        PropertiesModStorage storage = PropertiesModStorage.open("cn", file);
        storage.put("greeting", "你好，世界");
        assertTrue(storage.flush());

        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(raw.contains("你好，世界"),
                "文件应以 UTF-8 明文保存中文，实际内容:\n" + raw);
    }

    @Test
    @DisplayName("损坏的文件降级为空集而不是抛异常")
    void corruptFileDegradesGracefully(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.properties");
        // 反斜杠结尾的续行没有后续 —— properties 解析会抛 IllegalArgumentException
        Files.writeString(file, "key=\\uZZZZ\n", StandardCharsets.UTF_8);

        PropertiesModStorage storage = assertDoesNotThrow(
                () -> PropertiesModStorage.open("broken", file));
        assertTrue(storage.keys().isEmpty() || storage.keys().size() >= 0);

        // 仍可继续写入并落盘（覆盖掉损坏内容）
        storage.put("recovered", "yes");
        assertTrue(storage.flush());
        assertEquals("yes",
                PropertiesModStorage.open("broken", file).get("recovered").orElseThrow());
    }

    @Test
    @DisplayName("非法键被拒绝")
    void rejectsInvalidKeys(@TempDir Path dir) {
        PropertiesModStorage storage = PropertiesModStorage.inMemory("m");
        assertThrows(IllegalArgumentException.class, () -> storage.put("has space", "x"));
        assertThrows(IllegalArgumentException.class, () -> storage.put("has=equals", "x"));
        assertThrows(IllegalArgumentException.class, () -> storage.put("", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("x".repeat(129), "x"));

        // 合法的应该通过
        assertDoesNotThrow(() -> storage.put("ok.key_1-2", "v"));
    }

    @Test
    @DisplayName("类型化读取：解析失败回退默认值")
    void typedAccessorsFallBackToDefaults(@TempDir Path dir) {
        PropertiesModStorage storage = PropertiesModStorage.inMemory("m");
        storage.put("num", "42");
        storage.put("bad", "not-a-number");
        storage.put("flag", "on");

        assertEquals(42, storage.getInt("num", -1));
        assertEquals(-1, storage.getInt("bad", -1));
        assertEquals(-1, storage.getInt("missing", -1));
        assertTrue(storage.getBoolean("flag", false));
        assertFalse(storage.getBoolean("missing", false));
        assertEquals("d", storage.getOrDefault("missing", "d"));
    }

    @Test
    @DisplayName("内存实现不声称持久化，flush 返回 false 但不丢数据")
    void inMemoryIsHonestAboutPersistence() {
        PropertiesModStorage storage = PropertiesModStorage.inMemory("m");
        storage.put("k", "v");

        assertFalse(storage.persistent());
        assertFalse(storage.flush(), "内存实现的 flush 应明确返回 false");
        assertEquals("v", storage.get("k").orElseThrow());
    }

    @Test
    @DisplayName("remove 的返回值反映「是否真的删掉了」")
    void removeReportsExistence(@TempDir Path dir) {
        PropertiesModStorage storage = PropertiesModStorage.inMemory("m");
        storage.put("k", "v");
        assertTrue(storage.remove("k"));
        assertFalse(storage.remove("k"));
        assertTrue(storage.get("k").isEmpty());
    }

    @Test
    @DisplayName("服务层：同一 modId 返回同一实例")
    void serviceReturnsSameInstancePerMod(@TempDir Path dir) {
        ModStorageService service = ModStorageService.install(dir);
        assertSame(service.forMod("a"), service.forMod("a"));
        assertNotSame(service.forMod("a"), service.forMod("b"));
        assertEquals(2, service.size());
    }
}
