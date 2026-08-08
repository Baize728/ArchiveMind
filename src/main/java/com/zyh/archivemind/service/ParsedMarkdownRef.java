package com.zyh.archivemind.service;

import java.nio.file.Path;

/**
 * 解析后的 Markdown 引用。
 * 本次处理消费 localPath，长期缓存由 objectKey 指向 MinIO 对象。
 */
public record ParsedMarkdownRef(
        Path localPath,
        String objectKey,
        long markdownBytes,
        String contentHash,
        boolean fromCache
) {
}
