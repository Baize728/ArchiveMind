package com.zyh.archivemind.service;

import com.zyh.archivemind.client.EmbeddingClient;
import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.entity.EsDocument;
import com.zyh.archivemind.entity.TextChunk;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${embedding.api.model}")
    private String modelVersion;

    @Value("${embedding.api.dimension}")
    private int expectedDimension;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> chunks = fetchTextChunks(fileMd5, userId);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}, userId: {}", fileMd5, userId);
                return;
            }

            // 提取上下文增强文本用于 embedding（Contextual Retrieval）
            List<String> texts = chunks.stream()
                    .map(c -> c.getContextualizedContent() != null
                            ? c.getContextualizedContent()
                            : c.getContent())
                    .toList();

            // 调用外部模型生成向量
            List<float[]> vectors = embeddingClient.embed(texts);
            logEmbeddingDiagnostics(fileMd5, userId, chunks, texts, vectors);

            // 构建 Elasticsearch 文档并存储（含上下文增强字段）
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            buildEsDocumentId(fileMd5, userId, chunks.get(i).getChunkId()),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),              // textContent 原始文本
                            chunks.get(i).getContextualizedContent(), // contextualizedContent 增强文本
                            chunks.get(i).getDocTitle(),
                            chunks.get(i).getHeadingPath(),
                            chunks.get(i).getBlockType(),
                            chunks.get(i).getStartOffset(),
                            chunks.get(i).getEndOffset(),
                            chunks.get(i).getTokenLength(),
                            vectors.get(i),
                            modelVersion,
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            // 删除当前用户当前文件的旧索引，避免模型版本变化或历史随机 ID 遗留导致重复召回。
            elasticsearchService.deleteByFileMd5AndUserId(fileMd5, userId);
            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}, userId: {}, chunkCount: {}", fileMd5, userId, chunks.size());
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}, userId: {}", fileMd5, userId, e);
            throw new RuntimeException("向量化失败", e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5, String userId) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5AndUserIdOrderByChunkIdAsc(fileMd5, userId);

        // 转换为 TextChunk 列表（含上下文增强内容）
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getContextualizedContent(),
                        vector.getDocTitle(),
                        vector.getHeadingPath(),
                        vector.getBlockType(),
                        vector.getStartOffset(),
                        vector.getEndOffset(),
                        vector.getTokenLength()
                ))
                .toList();
    }

    private String buildEsDocumentId(String fileMd5, String userId, int chunkId) {
        String normalizedUserId = (userId == null || userId.isBlank()) ? "unknown" : userId;
        return fileMd5 + ":" + normalizedUserId + ":" + chunkId + ":" + modelVersion;
    }

    private void logEmbeddingDiagnostics(String fileMd5, String userId, List<TextChunk> chunks,
                                         List<String> texts, List<float[]> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            logger.warn("Embedding 诊断: 向量结果为空, fileMd5: {}, userId: {}, chunkCount: {}",
                    fileMd5, userId, chunks.size());
            return;
        }

        int nullVectorCount = 0;
        int mismatchCount = 0;
        int minDimension = Integer.MAX_VALUE;
        int maxDimension = 0;
        int firstDimension = vectors.get(0) == null ? -1 : vectors.get(0).length;
        int maxTextLength = 0;
        StringBuilder mismatchSamples = new StringBuilder();

        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            int actualDimension = vector == null ? -1 : vector.length;
            if (vector == null) {
                nullVectorCount++;
            } else {
                minDimension = Math.min(minDimension, actualDimension);
                maxDimension = Math.max(maxDimension, actualDimension);
            }

            if (i < texts.size() && texts.get(i) != null) {
                maxTextLength = Math.max(maxTextLength, texts.get(i).length());
            }

            if (actualDimension != expectedDimension) {
                mismatchCount++;
                if (mismatchSamples.length() < 300) {
                    int chunkId = i < chunks.size() ? chunks.get(i).getChunkId() : i + 1;
                    if (mismatchSamples.length() > 0) {
                        mismatchSamples.append("; ");
                    }
                    mismatchSamples.append("chunkId=").append(chunkId)
                            .append(", actualDim=").append(actualDimension);
                }
            }
        }

        if (minDimension == Integer.MAX_VALUE) {
            minDimension = -1;
        }

        logger.info("Embedding 诊断: fileMd5={}, userId={}, model={}, expectedDim={}, chunkCount={}, vectorCount={}, firstDim={}, minDim={}, maxDim={}, nullVectors={}, dimMismatch={}, maxEmbeddingTextLength={}",
                fileMd5, userId, modelVersion, expectedDimension, chunks.size(), vectors.size(),
                firstDimension, minDimension, maxDimension, nullVectorCount, mismatchCount, maxTextLength);

        if (vectors.size() != chunks.size()) {
            logger.warn("Embedding 诊断: chunk 数量与向量数量不一致, fileMd5={}, userId={}, chunkCount={}, vectorCount={}",
                    fileMd5, userId, chunks.size(), vectors.size());
        }
        if (mismatchCount > 0) {
            logger.warn("Embedding 诊断: 检测到向量维度不一致, expectedDim={}, mismatchCount={}, samples=[{}]",
                    expectedDimension, mismatchCount, mismatchSamples);
        }
    }
}
