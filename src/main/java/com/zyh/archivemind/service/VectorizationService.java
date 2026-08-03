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
import java.util.UUID;
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
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
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

            // 构建 Elasticsearch 文档并存储（含上下文增强字段）
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),              // textContent 原始文本
                            chunks.get(i).getContextualizedContent(), // contextualizedContent 增强文本
                            vectors.get(i),
                            modelVersion,
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}", fileMd5);
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("向量化失败", e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表（含上下文增强内容）
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getContextualizedContent()
                ))
                .toList();
    }
}