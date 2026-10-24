package org.loader.installer.meta;

import java.io.IOException;
import java.io.InputStream;

/**
 * 版本元数据来源抽象。
 *
 * <p>安装器需要两种能力：拉取某个版本的完整元数据，以及按 URL 打开资源流。
 * 把它抽成接口的唯一目的是<b>可测性</b> —— 安装流程的失败路径（必需库失败、
 * 资源失败、校验不符）必须能被离线复现，否则只能靠联网碰运气，而这些路径
 * 恰恰是最需要回归保护的部分。
 *
 * <p>{@link MojangMetaClient} 是唯一的生产实现。
 */
public interface MetaSource {

    /** 拉取并解析指定版本的完整元数据。 */
    VersionMeta fetchVersionMeta(String versionId) throws IOException, InterruptedException;

    /** 打开一个远程资源的输入流（流式，不整包进内存）。 */
    InputStream openStream(String url) throws IOException, InterruptedException;

    /** 包装一个只提供元数据的实现（{@code openStream} 不支持，调用即抛）。 */
    static MetaSource of(VersionMeta meta) {
        return new MetaSource() {
            @Override
            public VersionMeta fetchVersionMeta(String versionId) {
                return meta;
            }

            @Override
            public InputStream openStream(String url) throws IOException {
                throw new IOException("MetaSource.of(...) 不支持流式下载: " + url);
            }
        };
    }
}
