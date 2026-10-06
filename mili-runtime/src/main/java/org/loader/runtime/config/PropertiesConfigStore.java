package org.loader.runtime.config;

import org.loader.api.config.ConfigService;
import org.loader.api.config.ConfigWriteException;
import org.loader.api.config.ModConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Properties 配置存储 —— {@link ModConfig} 的零依赖实现。
 *
 * <h2>为什么不用 JSON</h2>
 * 配置的本质是「少量标量的持久化」。Properties 零依赖（JDK 自带）、
 * 玩家可用任何编辑器手改、损坏时逐键降级（一个键解析失败不影响
 * 其余键）—— JSON 一处语法错误整份作废。若未来确需层级结构，
 * 在 {@code ModConfig} 之下加一层序列化策略即可，ABI 不用动。
 *
 * <h2>损坏容错</h2>
 * 手改的配置文件不可信：非法数字、缺失文件、IO 错误都不抛 ——
 * 返回默认值并记日志。玩家改坏了配置，得到的应该是「回到默认」，
 * 不是启动崩溃。
 *
 * <h2>实例唯一性</h2>
 * 同一 modId 复用同一实例（{@link ConfigService#load} 的契约），
 * 避免内存态与磁盘态分叉。
 */
public final class PropertiesConfigStore implements ConfigService.Provider {

    private static final Logger LOG = Logger.getLogger("Mili/Config");

    /** 配置根目录（相对游戏工作目录 —— 与原版 options.txt 同一基准）。 */
    private static final String DEFAULT_CONFIG_DIR = "config/mili";

    private final Path baseDir;

    /** 生产构造器 —— 使用默认目录。 */
    public PropertiesConfigStore() {
        this(Path.of(DEFAULT_CONFIG_DIR));
    }

    /** 测试 / 定制目录构造器。 */
    PropertiesConfigStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    private final Map<String, PropertiesConfig> loaded = new ConcurrentHashMap<>();

    @Override
    public ModConfig load(String modId) {
        return loaded.computeIfAbsent(modId, id -> new PropertiesConfig(id, baseDir));
    }

    /** 单个 mod 的配置实例。 */
    static final class PropertiesConfig implements ModConfig {

        private final String modId;
        private final Path file;
        private final Properties values = new Properties();

        PropertiesConfig(String modId, Path baseDir) {
            this.modId = modId;
            this.file = baseDir.resolve(modId + ".properties");
            loadFromDisk();
        }

        @Override
        public String modId() {
            return modId;
        }

        // ── 读（损坏容错：解析失败一律回默认值） ─────────────────────────

        @Override
        public String getString(String key, String defaultValue) {
            synchronized (values) {
                String v = values.getProperty(key);
                return v != null ? v : defaultValue;
            }
        }

        @Override
        public int getInt(String key, int defaultValue) {
            String v = getString(key, null);
            if (v == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                LOG.warning("[Mili] 配置 " + modId + "." + key
                        + "=\"" + v + "\" 不是合法整数，使用默认值 " + defaultValue);
                return defaultValue;
            }
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            String v = getString(key, null);
            if (v == null) {
                return defaultValue;
            }
            if (v.equalsIgnoreCase("true")) {
                return true;
            }
            if (v.equalsIgnoreCase("false")) {
                return false;
            }
            LOG.warning("[Mili] 配置 " + modId + "." + key
                    + "=\"" + v + "\" 不是合法布尔值，使用默认值 " + defaultValue);
            return defaultValue;
        }

        @Override
        public double getDouble(String key, double defaultValue) {
            String v = getString(key, null);
            if (v == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                LOG.warning("[Mili] 配置 " + modId + "." + key
                        + "=\"" + v + "\" 不是合法数值，使用默认值 " + defaultValue);
                return defaultValue;
            }
        }

        // ── 写（内存即时生效，磁盘由 save() 显式持久化） ─────────────────

        @Override
        public void set(String key, String value) {
            synchronized (values) {
                if (value == null) {
                    values.remove(key);
                } else {
                    values.setProperty(key, value);
                }
            }
        }

        @Override
        public void setInt(String key, int value) {
            set(key, Integer.toString(value));
        }

        @Override
        public void setBoolean(String key, boolean value) {
            set(key, Boolean.toString(value));
        }

        @Override
        public void setDouble(String key, double value) {
            set(key, Double.toString(value));
        }

        @Override
        public boolean has(String key) {
            synchronized (values) {
                return values.containsKey(key);
            }
        }

        @Override
        public Iterable<String> keys() {
            synchronized (values) {
                Set<String> keys = new TreeSet<>();
                for (Object k : values.keySet()) {
                    keys.add(String.valueOf(k));
                }
                return keys;
            }
        }

        // ── 磁盘 ─────────────────────────────────────────────────────────

        @Override
        public String save() {
            // 先写临时文件再原子替换：写入中途崩溃（断电、崩溃日志导出）
            // 不能让玩家丢失整份配置 —— 那比「没保存这一次」严重得多。
            StringWriter out = new StringWriter();
            String body;
            synchronized (values) {
                // TreeMap 序列化保证 diff 稳定（Properties 自身无序）
                Map<String, String> sorted = new TreeMap<>();
                for (String k : values.stringPropertyNames()) {
                    sorted.put(k, values.getProperty(k));
                }
                sorted.forEach((k, v) -> out.write(k + "=" + v + "\n"));
                body = out.toString();
            }
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, body, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new ConfigWriteException(
                        "保存配置失败: " + file + " —— " + e.getMessage(), e);
            }
            return file.toString();
        }

        private void loadFromDisk() {
            if (!Files.isRegularFile(file)) {
                return; // 首次加载 —— 文件在首次 save() 时创建
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] bytes = in.readAllBytes();
                Properties parsed = new Properties();
                // StringReader 而非直接 load(InputStream)：UTF-8 明确，
                // 不受 Properties 历史上的 ISO-8859-1 默认影响。
                parsed.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
                synchronized (values) {
                    values.putAll(parsed);
                }
            } catch (IOException e) {
                // 读不了 ≠ 世界末日：全部走默认值，下次 save() 覆盖重建。
                LOG.log(Level.WARNING,
                        "[Mili] 读取配置失败（全部使用默认值）: " + file, e);
            }
        }
    }
}
