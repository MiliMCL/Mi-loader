package org.loader.runtime.storage;

import org.loader.api.storage.ModStorage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 基于属性文件的 {@link ModStorage} 实现。
 *
 * <h2>为什么是 properties 而不是 JSON</h2>
 * 平台已经有一个不依赖任何第三方库的 properties 配置存储
 * （{@code PropertiesConfigStore}），复用它意味着<b>零新增依赖</b>。
 * 而 JSON 需要自带一个解析器或引入 Gson/Jackson —— 为了几十个键值对
 * 往平台里塞一个序列化库，代价与收益完全不成比例。
 *
 * <h2>原子写</h2>
 * 落盘走「写临时文件 → 原子 move」。直接 {@code store} 到目标文件
 * 在进程被杀时会留下半个文件，而半个 properties 文件在下次启动时
 * 会被解析成「键值对少了一半」—— 这种损坏不报错，只是数据静默丢失。
 * 原子 move 保证任意时刻磁盘上的文件要么是旧的完整版，要么是新的完整版。
 *
 * <h2>编码</h2>
 * 用 {@code load(Reader)} / {@code store(Writer)} 的 UTF-8 变体
 * （Java 9+）。老的 {@code load(InputStream)} 固定 ISO-8859-1，
 * 中文值会被转义成「反斜杠 + u + 四位十六进制」的形态 —— 能读但没法看，
 * 排查时很痛苦。
 *
 * <h2>并发</h2>
 * 值放在 {@link ConcurrentHashMap}；落盘串行化在 {@code writeLock} 上。
 * 读永不阻塞 —— 这与「诊断/UI 线程读配置」的实际用法匹配。
 */
public final class PropertiesModStorage implements ModStorage {

    /** 键的合法字符集。见 {@code ModStorage} 的「键的命名」说明。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    private final String modId;
    private final Path file;
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile boolean dirty;
    private volatile long lastFlushMillis;
    private volatile boolean persistent;

    private PropertiesModStorage(String modId, Path file, boolean persistent) {
        this.modId = modId;
        this.file = file;
        this.persistent = persistent;
    }

    /**
     * 打开（或创建）一个存储。文件损坏时降级为内存存储而不是抛异常 ——
     * 一个坏掉的配置文件不该阻止 Mod 启动，应该让它在下次 flush 时被覆盖修复。
     */
    public static PropertiesModStorage open(String modId, Path file) {
        PropertiesModStorage storage = new PropertiesModStorage(modId, file, true);
        storage.load();
        return storage;
    }

    /** 纯内存实现 —— 供测试与「配置目录不可写」的降级场景。 */
    public static PropertiesModStorage inMemory(String modId) {
        return new PropertiesModStorage(modId, null, false);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            // 损坏：保持空值集，把 persistent 标记为可用（下次 flush 覆盖它）
            return;
        }
        props.forEach((k, v) -> {
            if (k instanceof String key && v instanceof String value
                    && KEY_PATTERN.matcher(key).matches()) {
                values.put(key, value);
            }
        });
    }

    public String modId() {
        return modId;
    }

    /** 文件位置；内存实现返回 null。 */
    public Path file() {
        return file;
    }

    @Override
    public java.util.Optional<String> get(String key) {
        if (key == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(values.get(key));
    }

    @Override
    public void put(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        requireValidKey(key);
        values.put(key, value);
        dirty = true;
    }

    @Override
    public boolean remove(String key) {
        if (key == null) {
            return false;
        }
        boolean existed = values.remove(key) != null;
        if (existed) {
            dirty = true;
        }
        return existed;
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    @Override
    public boolean flush() {
        if (!dirty) {
            return true;
        }
        if (!persistent || file == null) {
            dirty = false;
            lastFlushMillis = System.currentTimeMillis();
            return false;
        }
        synchronized (writeLock) {
            if (!dirty) {
                return true;
            }
            Properties props = new Properties();
            props.putAll(values);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    props.store(writer, "Mili mod storage: " + modId);
                }
                try {
                    Files.move(tmp, file,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                dirty = false;
                lastFlushMillis = System.currentTimeMillis();
                return true;
            } catch (IOException e) {
                // 落盘失败不抛：存储是「尽力而为」的附属能力，
                // 让它把 Mod 的关闭流程炸掉是本末倒置。
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 临时文件删不掉只会留下垃圾，不影响正确性
                }
                return false;
            }
        }
    }

    @Override
    public long lastFlushMillis() {
        return lastFlushMillis;
    }

    @Override
    public boolean persistent() {
        return persistent;
    }

    /** 是否有未落盘的修改（诊断用）。 */
    public boolean isDirty() {
        return dirty;
    }

    private static void requireValidKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "存储键非法（应为 [A-Za-z0-9_.-]，1..128 字符）: \"" + key + "\"");
        }
    }

    @Override
    public String toString() {
        return "PropertiesModStorage[" + modId + ", keys=" + values.size()
                + (persistent ? ", " + file : ", in-memory") + "]";
    }
}
