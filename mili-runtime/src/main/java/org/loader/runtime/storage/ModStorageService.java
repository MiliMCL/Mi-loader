package org.loader.runtime.storage;

import org.loader.api.storage.ModStorage;
import org.loader.api.storage.StorageService;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link StorageService} 的平台实现 —— 按 modId 管理存储实例。
 *
 * <h2>目录布局</h2>
 * <pre>
 *   &lt;gameDir&gt;/config/mili/storage/&lt;modId&gt;.properties
 * </pre>
 * 放在 {@code config/} 而不是 {@code mods/}：玩家备份配置目录时
 * 会连 Mod 状态一起带走，而 mods 目录里的文件通常被当成可替换的二进制。
 *
 * <h2>关闭时的落盘</h2>
 * {@link #flushAll()} 在平台关停时调用。它遍历所有实例并 flush，
 * 单个失败不影响其余 —— 与 {@code LoaderMain.shutdown()} 的
 * 「逆序释放、逐个容错」策略一致。
 */
public final class ModStorageService implements StorageService.Provider {

    private final Path directory;
    private final boolean persistent;
    private final Map<String, PropertiesModStorage> byMod = new ConcurrentHashMap<>();

    private ModStorageService(Path directory, boolean persistent) {
        this.directory = directory;
        this.persistent = persistent;
    }

    /**
     * 装配并注册为平台实现。
     *
     * @param storageDir 存储目录；null 或不可创建时降级为纯内存
     */
    public static ModStorageService install(Path storageDir) {
        boolean persistent = false;
        if (storageDir != null) {
            try {
                java.nio.file.Files.createDirectories(storageDir);
                persistent = java.nio.file.Files.isWritable(storageDir);
            } catch (Exception e) {
                persistent = false;
            }
        }
        ModStorageService service = new ModStorageService(storageDir, persistent);
        StorageService.registerProvider(service);
        return service;
    }

    @Override
    public ModStorage forMod(String modId) {
        return byMod.computeIfAbsent(modId, id -> {
            if (!persistent || directory == null) {
                return PropertiesModStorage.inMemory(id);
            }
            return PropertiesModStorage.open(id, directory.resolve(id + ".properties"));
        });
    }

    /** 落盘全部；返回成功数量。 */
    public int flushAll() {
        int ok = 0;
        for (PropertiesModStorage storage : byMod.values()) {
            if (storage.flush()) {
                ok++;
            }
        }
        return ok;
    }

    /** 已打开的存储数量（诊断用）。 */
    public int size() {
        return byMod.size();
    }

    public boolean persistent() {
        return persistent;
    }

    public Path directory() {
        return directory;
    }
}
