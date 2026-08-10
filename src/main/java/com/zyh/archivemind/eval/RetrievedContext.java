package com.zyh.archivemind.eval;

public record RetrievedContext(
        int rank,
        String fileMd5,
        Integer chunkId,
        String fileName,
        String textContent,
        String contextualizedContent,
        Double score,
        String userId,
        String orgTag,
        Boolean isPublic,
        String docTitle,
        String headingPath,
        String blockType
) {
}
