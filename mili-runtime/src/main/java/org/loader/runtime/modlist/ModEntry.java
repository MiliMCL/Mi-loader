package org.loader.runtime.modlist;

/**
 * Mod 列表中的一个条目 —— 纯数据，跨 ClassLoader 安全。
 *
 * <h2>为什么是扁平 record 而不是直接暴露 {@code ModManifest}</h2>
 * 列表的消费方是 GUI 分发器（运行在平台 ClassLoader 上），而
 * {@code ModManifest} 携带的是 loader 内部状态。把内部对象直接递出去，
 * 等于把「GUI 能看到什么」和「loader 怎么存」绑死 —— 后续任何
 * 清单结构的调整都会在 UI 层炸开。
 *
 * <p>另一个原因是性能：这个 record 是<b>每次打开列表时构建一次</b>的
 * 快照，渲染热路径只读不可变字段，不做任何反射或 map 查询。
 *
 * @param id           Mod 标识（{@code mod.json} 的 {@code id}）
 * @param name         人类可读名称
 * @param version      版本字符串
 * @param description  描述（可为空串，绝不返回 null）
 * @param author       作者（可为空串）
 * @param source       来源文件名，如 {@code mymod-1.0.jar}；目录布局为空串
 * @param state        生命周期状态名（{@code ACTIVE} / {@code SUSPENDED} / ...）
 * @param loadedClasses 该 Mod 已加载的类数量 —— 内存占用最直观的代理指标
 * @param blocks       声明的方块数量
 * @param pinned       是否被平台固定（不可卸载）
 */
public record ModEntry(
        String id,
        String name,
        String version,
        String description,
        String author,
        String source,
        String state,
        int loadedClasses,
        int blocks,
        boolean pinned) {

    public ModEntry {
        id = id != null ? id : "";
        name = name != null && !name.isBlank() ? name : id;
        version = version != null ? version : "";
        description = description != null ? description : "";
        author = author != null ? author : "";
        source = source != null ? source : "";
        state = state != null ? state : "UNKNOWN";
    }

    /** 一行摘要：{@code id v版本}。 */
    public String title() {
        return version.isEmpty() ? id : id + " v" + version;
    }

    /** 状态是否表示「正常运行」。 */
    public boolean healthy() {
        return "ACTIVE".equals(state) || "INITIALIZING".equals(state);
    }
}
