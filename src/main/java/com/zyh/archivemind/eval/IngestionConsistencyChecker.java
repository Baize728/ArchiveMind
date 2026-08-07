package com.zyh.archivemind.eval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountResponse;
import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import com.zyh.archivemind.repository.ParsedDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IngestionConsistencyChecker {

    private static final Logger logger = LoggerFactory.getLogger(IngestionConsistencyChecker.class);

    private final DocumentVectorRepository documentVectorRepository;
    private final ParsedDocumentRepository parsedDocumentRepository;
    private final ElasticsearchClient esClient;

    public IngestionConsistencyChecker(DocumentVectorRepository documentVectorRepository,
                                       ParsedDocumentRepository parsedDocumentRepository,
                                       ElasticsearchClient esClient) {
        this.documentVectorRepository = documentVectorRepository;
        this.parsedDocumentRepository = parsedDocumentRepository;
        this.esClient = esClient;
    }

    public IngestionCheckResult check(EvalCase evalCase) {
        if (evalCase.goldFileMd5() == null || evalCase.goldFileMd5().isBlank()) {
            return IngestionCheckResult.empty();
        }

        String fileMd5 = evalCase.goldFileMd5();
        String userId = evalCase.effectiveUserId();
        long parsedSuccessCount = parsedDocumentRepository.countByFileMd5AndParseStatus(fileMd5, "SUCCESS");
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5AndUserIdOrderByChunkIdAsc(fileMd5, userId);
        long documentVectorCount = vectors.size();
        long esDocumentCount = countEsDocuments(fileMd5, userId);

        double contextualizedRate = ratio(vectors.stream()
                .filter(v -> v.getContextualizedContent() != null && !v.getContextualizedContent().isBlank())
                .count(), documentVectorCount);
        double structureRate = ratio(vectors.stream()
                .filter(v -> notBlank(v.getDocTitle()) || notBlank(v.getHeadingPath()) || notBlank(v.getBlockType()))
                .count(), documentVectorCount);
        double permissionRate = ratio(vectors.stream()
                .filter(v -> notBlank(v.getUserId()))
                .count(), documentVectorCount);

        return new IngestionCheckResult(
                parsedSuccessCount,
                documentVectorCount,
                esDocumentCount,
                documentVectorCount > 0 && documentVectorCount == esDocumentCount,
                contextualizedRate,
                structureRate,
                permissionRate
        );
    }

    private long countEsDocuments(String fileMd5, String userId) {
        try {
            CountResponse response = esClient.count(c -> c
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("fileMd5").value(fileMd5)))
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                    )));
            return response.count();
        } catch (Exception e) {
            logger.warn("ES 文档数量检查失败: fileMd5={}, userId={}, error={}",
                    fileMd5, userId, e.getMessage());
            return -1;
        }
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator * 1.0 / denominator;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
