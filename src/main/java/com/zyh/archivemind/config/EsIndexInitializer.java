package com.zyh.archivemind.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import org.apache.http.ConnectionClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.io.StringReader;

@Component
public class EsIndexInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(EsIndexInitializer.class);
    private static final String STRUCTURAL_FIELDS_MAPPING = """
            {
              "properties": {
                "docTitle": {
                  "type": "text",
                  "analyzer": "ik_max_word",
                  "search_analyzer": "ik_smart",
                  "fields": {
                    "keyword": {
                      "type": "keyword",
                      "ignore_above": 512
                    }
                  }
                },
                "headingPath": {
                  "type": "text",
                  "analyzer": "ik_max_word",
                  "search_analyzer": "ik_smart",
                  "fields": {
                    "keyword": {
                      "type": "keyword",
                      "ignore_above": 1024
                    }
                  }
                },
                "blockType": {
                  "type": "keyword"
                },
                "startOffset": {
                  "type": "integer"
                },
                "endOffset": {
                  "type": "integer"
                },
                "tokenLength": {
                  "type": "integer"
                }
              }
            }
            """;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ElasticsearchClient esClient;

    @Value("classpath:es-mappings/knowledge_base.json") // 加载 JSON 文件
    private org.springframework.core.io.Resource mappingResource;

    @Value("${embedding.api.dimension}")
    private int embeddingDimension;

    @Override
    public void run(String... args) throws Exception {
        try {
            initializeIndex();
        } catch (Exception exception) {
            // 特别处理连接关闭异常，尝试重新连接
            if (exception instanceof ConnectionClosedException || (exception.getCause() != null && exception.getCause() instanceof ConnectionClosedException)) {
                logger.error("Elasticsearch连接已关闭，等待5秒后重试...");
                try {
                    Thread.sleep(5000); // 等待5秒后重试
                    initializeIndex();
                } catch (Exception retryException) {
                    logger.error("重试初始化索引失败，请检查Elasticsearch连接配置，比如说是否开启了 HTTPS 模式: {}", retryException.getMessage());
                    throw new RuntimeException("初始化索引失败，重试也未能成功", retryException);
                }
            } else if (exception.getCause() instanceof java.net.ConnectException) {
                // Elasticsearch 未启动时不阻止应用启动，只打警告
                logger.warn("Elasticsearch 连接失败（{}），搜索功能将不可用。请启动 Elasticsearch 后重启应用。",
                        exception.getCause().getMessage());
            } else {
                throw new RuntimeException("初始化索引失败", exception);
            }
        }
    }

    /**
     * 初始化索引的核心逻辑
     * @throws Exception
     */
    private void initializeIndex() throws Exception {
        validateMappingVectorDimension();

        // 检查索引是否存在
        BooleanResponse existsResponse = esClient.indices().exists(ExistsRequest.of(e -> e.index("knowledge_base")));
        if (!existsResponse.value()) {
            createIndex();
        } else {
            logger.info("索引 'knowledge_base' 已存在");
            applyStructuralFieldMappings();
            validateExistingIndexVectorDimension();
        }
    }

    /**
     * 创建索引
     * @throws Exception
     */
    private void createIndex() throws Exception {
        // 读取 JSON 文件内容
        String mappingJson = readMappingJson();

        // 创建索引并应用映射
        CreateIndexRequest createIndexRequest = CreateIndexRequest.of(c -> c
                .index("knowledge_base") // 索引名称
                .withJson(new StringReader(mappingJson)) // 使用 JSON 文件定义映射
        );
        esClient.indices().create(createIndexRequest);
        logger.info("索引 'knowledge_base' 已创建");
    }

    private void applyStructuralFieldMappings() {
        try {
            esClient.indices().putMapping(p -> p
                    .index("knowledge_base")
                    .withJson(new StringReader(STRUCTURAL_FIELDS_MAPPING)));
            logger.info("结构化 Chunk 字段 mapping 已确认: docTitle, headingPath, blockType, offsets");
        } catch (Exception e) {
            logger.warn("结构化 Chunk 字段 mapping 自动更新失败，如字段已被动态映射为其他类型，请重建索引或手动迁移: {}",
                    e.getMessage());
        }
    }

    private void validateMappingVectorDimension() throws Exception {
        String mappingJson = readMappingJson();
        JsonNode dimsNode = objectMapper.readTree(mappingJson)
                .path("mappings")
                .path("properties")
                .path("vector")
                .path("dims");

        if (!dimsNode.isInt()) {
            throw new IllegalStateException("ES mapping 缺少 vector.dims 配置，请检查 knowledge_base.json");
        }

        int mappingDimension = dimsNode.asInt();
        if (mappingDimension != embeddingDimension) {
            throw new IllegalStateException("Embedding 维度与 ES mapping 不一致: embedding.api.dimension="
                    + embeddingDimension + ", knowledge_base.vector.dims=" + mappingDimension);
        }

        logger.info("Embedding 维度校验通过: {}", embeddingDimension);
    }

    private String readMappingJson() throws Exception {
        try (var inputStream = mappingResource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void validateExistingIndexVectorDimension() throws Exception {
        GetMappingResponse response = esClient.indices().getMapping(g -> g.index("knowledge_base"));
        var indexMapping = response.result().get("knowledge_base");
        if (indexMapping == null || indexMapping.mappings() == null) {
            logger.warn("无法读取索引 'knowledge_base' 的 mapping，跳过实际索引维度校验");
            return;
        }

        var vectorProperty = indexMapping.mappings().properties().get("vector");
        if (vectorProperty == null || !vectorProperty.isDenseVector()) {
            throw new IllegalStateException("现有 ES 索引缺少 dense_vector 字段: knowledge_base.vector");
        }

        Integer existingDimension = vectorProperty.denseVector().dims();
        if (existingDimension == null) {
            throw new IllegalStateException("现有 ES 索引缺少 knowledge_base.vector.dims 配置");
        }

        if (existingDimension != embeddingDimension) {
            throw new IllegalStateException("Embedding 维度与现有 ES 索引不一致: embedding.api.dimension="
                    + embeddingDimension + ", existing knowledge_base.vector.dims=" + existingDimension);
        }

        logger.info("现有 ES 索引向量维度校验通过: {}", existingDimension);
    }
}
