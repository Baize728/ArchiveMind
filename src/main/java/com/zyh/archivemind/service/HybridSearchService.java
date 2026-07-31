package com.zyh.archivemind.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.zyh.archivemind.client.EmbeddingClient;
import com.zyh.archivemind.entity.EsDocument;
import com.zyh.archivemind.entity.SearchResult;
import com.zyh.archivemind.model.User;
import com.zyh.archivemind.exception.CustomException;
import com.zyh.archivemind.model.DocumentVector;
import com.zyh.archivemind.repository.UserRepository;
import com.zyh.archivemind.repository.FileUploadRepository;
import com.zyh.archivemind.repository.DocumentVectorRepository;
import com.zyh.archivemind.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 混合搜索服务 —— 企业级 RAG 检索管道。
 *
 * 检索管道：RRF 粗排 → 权限过滤 → Reranker 精排 → Parent-Child 上下文回溯。
 * 相比旧版 rescore 线性加权，RRF 天然免疫 KNN 与 BM25 的分数尺度差异。
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    /** RRF 常数：控制排名影响程度，TREC 实验表明 60 在多数场景最优 */
    static final double RRF_K = 60.0;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RerankerService rerankerService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Value("${retrieval.recall-size:50}")
    private int recallSize;

    /**
     * 企业级混合搜索——RRF 融合 + Reranker 精排 + Parent-Child 回溯 + 权限过滤。
     *
     * 管道：KNN + BM25 分别检索 → RRF 融合同一轮候选 → 权限过滤 →
     *       Reranker Cross-Encoder 精排 → Parent-Child 上下文回溯 → 返回
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("企业级混合搜索启动, query={}, userId={}, topK={}", query, userId, topK);

        try {
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            String userDbId = getUserDbId(userId);

            final List<Float> queryVector = embedToVectorList(query);
            if (queryVector == null) {
                logger.warn("向量生成失败，降级为纯文本搜索");
                return textOnlySearchWithPermission(query, userDbId, userEffectiveTags, topK);
            }

            // === 阶段 1: RRF 融合检索 ===
            // 并行执行 KNN 和 BM25，各取 recallSize 条，RRF 合并排名
            List<SearchResult> rrfResults = rrfSearch(query, queryVector, userDbId, userEffectiveTags);
            logger.debug("RRF 融合完成，候选: {} 条", rrfResults.size());

            if (rrfResults.isEmpty()) {
                return Collections.emptyList();
            }

            // === 阶段 2: Cross-Encoder 重排序 ===
            List<SearchResult> reranked = rerankerService.rerank(query, rrfResults, topK);
            logger.debug("Reranker 精排完成，返回: {} 条", reranked.size());

            // === 阶段 3: Parent-Child 上下文回溯 ===
            List<SearchResult> resolved = resolveParentContext(reranked);

            attachFileNames(resolved);
            return resolved;

        } catch (Exception e) {
            logger.error("混合搜索失败", e);
            try {
                return textOnlySearchWithPermission(query, getUserDbId(userId),
                        getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("降级搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    // ===================== RRF 融合检索 =====================

    /**
     * RRF（Reciprocal Rank Fusion）融合检索。
     * 分别执行 KNN 语义搜索和 BM25 关键词搜索，在应用层按排名融合，
     * 两个检索器共识度高的文档获得更高 RRF 分数。
     */
    private List<SearchResult> rrfSearch(String query, List<Float> queryVector,
                                          String userDbId, List<String> userEffectiveTags) {
        // 并行执行（生产可改为 CompletableFuture 异步）
        List<EsDocument> knnDocs = knnSearch(queryVector, query, userDbId, userEffectiveTags);
        List<EsDocument> bm25Docs = bm25Search(query, userDbId, userEffectiveTags);

        if (knnDocs.isEmpty() && bm25Docs.isEmpty()) {
            return Collections.emptyList();
        }

        // RRF 融合：只看排名不看分数
        Map<String, Integer> knnRanks = toRankMap(knnDocs);
        Map<String, Integer> bm25Ranks = toRankMap(bm25Docs);
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, EsDocument> docMap = new LinkedHashMap<>();

        for (EsDocument doc : knnDocs) docMap.putIfAbsent(docKey(doc), doc);
        for (EsDocument doc : bm25Docs) docMap.putIfAbsent(docKey(doc), doc);

        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(knnRanks.keySet());
        allKeys.addAll(bm25Ranks.keySet());

        for (String key : allKeys) {
            double score = 0.0;
            if (knnRanks.containsKey(key)) score += 1.0 / (RRF_K + knnRanks.get(key));
            if (bm25Ranks.containsKey(key)) score += 1.0 / (RRF_K + bm25Ranks.get(key));
            rrfScores.put(key, score);
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    EsDocument doc = docMap.get(e.getKey());
                    return new SearchResult(
                            doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                            e.getValue(),
                            doc.getUserId(), doc.getOrgTag(), doc.isPublic()
                    );
                })
                .toList();
    }

    /** KNN 语义向量搜索 */
    private List<EsDocument> knnSearch(List<Float> queryVector, String query,
                                        String userDbId, List<String> userEffectiveTags) {
        try {
            int k = Math.max(recallSize, 100);
            SearchResponse<EsDocument> resp = esClient.search(s -> {
                s.index("knowledge_base");
                s.knn(kn -> kn.field("vector").queryVector(queryVector)
                        .k(k).numCandidates(k * 2));
                s.query(q -> q.bool(b -> {
                    b.should(sh -> sh.match(m -> m.field("textContent").query(query)));
                    b.filter(f -> f.bool(bf -> {
                        buildPermissionFilter(bf, userDbId, userEffectiveTags);
                        return bf;
                    }));
                    return b;
                }));
                s.size(recallSize);
                return s;
            }, EsDocument.class);
            return extractDocs(resp);
        } catch (Exception e) {
            logger.error("KNN 搜索失败", e);
            return Collections.emptyList();
        }
    }

    /** BM25 关键词搜索 */
    private List<EsDocument> bm25Search(String query, String userDbId,
                                         List<String> userEffectiveTags) {
        try {
            SearchResponse<EsDocument> resp = esClient.search(s -> {
                s.index("knowledge_base");
                s.query(q -> q.bool(b -> {
                    b.must(m -> m.match(ma -> ma.field("textContent").query(query)));
                    b.filter(f -> f.bool(bf -> {
                        buildPermissionFilter(bf, userDbId, userEffectiveTags);
                        return bf;
                    }));
                    return b;
                }));
                s.size(recallSize);
                return s;
            }, EsDocument.class);
            return extractDocs(resp);
        } catch (Exception e) {
            logger.error("BM25 搜索失败", e);
            return Collections.emptyList();
        }
    }

    // ===================== Parent-Child 上下文回溯 =====================

    /**
     * Parent-Child 上下文回溯。
     * 检索命中子块后，通过 parentChunkId 回取父块完整文本，同一父块的多个命中去重合并。
     * 父块提供子块缺失的跨段落上下文（指代消解、逻辑链、表格完整性等）。
     */
    private List<SearchResult> resolveParentContext(List<SearchResult> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();

        // 收集所有 (fileMd5, chunkId) 对
        Set<String> md5s = raw.stream().map(SearchResult::getFileMd5).collect(Collectors.toSet());
        Set<Integer> chunkIds = raw.stream().map(SearchResult::getChunkId).collect(Collectors.toSet());

        List<DocumentVector> vectors;
        try {
            vectors = documentVectorRepository.findByFileMd5InAndChunkIdIn(
                    new ArrayList<>(md5s), new ArrayList<>(chunkIds));
        } catch (Exception e) {
            logger.warn("Parent-Child 回溯查询失败，使用原始子块: {}", e.getMessage());
            return raw;
        }

        // 构建 (fileMd5:chunkId) → DocumentVector 映射
        Map<String, DocumentVector> vecMap = vectors.stream()
                .collect(Collectors.toMap(
                        v -> v.getFileMd5() + ":" + v.getChunkId(),
                        v -> v, (a, b) -> a));

        // 去重：同一 (fileMd5, parentChunkId) 只保留一条，用父块文本替换子块
        Map<String, SearchResult> deduped = new LinkedHashMap<>();
        for (SearchResult r : raw) {
            DocumentVector dv = vecMap.get(r.getFileMd5() + ":" + r.getChunkId());
            if (dv != null && dv.getParentChunkId() != null && dv.getParentText() != null) {
                String parentKey = r.getFileMd5() + ":parent:" + dv.getParentChunkId();
                if (!deduped.containsKey(parentKey)) {
                    r.setTextContent(dv.getParentText());  // 替换为父块完整文本
                    deduped.put(parentKey, r);
                }
                // 如果已经存在，跳过（去重）
            } else {
                // 无父块信息，保留原样
                deduped.put(r.getFileMd5() + ":" + r.getChunkId(), r);
            }
        }

        List<SearchResult> resolved = new ArrayList<>(deduped.values());
        if (raw.size() != resolved.size()) {
            logger.debug("Parent 回溯去重: {} → {}", raw.size(), resolved.size());
        }
        return resolved;
    }

    // ===================== 权限过滤 =====================

    /** 构建 ES 权限过滤 DSL（写入 BoolQuery.Builder，直接作为 filter clause） */
    private void buildPermissionFilter(
            co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder b,
            String userDbId, List<String> userEffectiveTags) {
        b.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
        b.should(s2 -> s2.term(t -> t.field("public").value(true)));
        if (!userEffectiveTags.isEmpty()) {
            for (String tag : userEffectiveTags) {
                b.should(s3 -> s3.term(t -> t.field("orgTag").value(tag)));
            }
        }
        b.minimumShouldMatch("1");
    }

    // ===================== 纯文本降级搜索 =====================

    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.match(ma -> ma.field("textContent").query(query)));
                        b.filter(f -> f.bool(bf -> {
                            buildPermissionFilter(bf, userDbId, userEffectiveTags);
                            return bf;
                        }));
                        return b;
                    }))
                    .minScore(0.3d)
                    .size(topK),
                    EsDocument.class
            );

            List<SearchResult> results = extractDocs(response).stream()
                    .map(doc -> new SearchResult(
                            doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(),
                            (double) 0, // text-only 无分数
                            doc.getUserId(), doc.getOrgTag(), doc.isPublic()
                    ))
                    .toList();
            attachFileNames(results);
            return results;
        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return new ArrayList<>();
        }
    }

    // ===================== 向后兼容：无权限过滤搜索 =====================

    /** 原始搜索方法（无权限过滤），保留向后兼容。新代码请使用 searchWithPermission。 */
    public List<SearchResult> search(String query, int topK) {
        logger.warn("使用了无权限过滤的搜索方法，建议使用 searchWithPermission");
        List<String> emptyTags = Collections.emptyList();
        return textOnlySearchWithPermission(query, "", emptyTags, topK);
    }

    // ===================== 辅助方法 =====================

    /** 从 ES 响应中提取 EsDocument 列表 */
    private List<EsDocument> extractDocs(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return hit.source();
                })
                .toList();
    }

    /** 构建 (fileMd5:chunkId) → rank 的排名映射 */
    private Map<String, Integer> toRankMap(List<EsDocument> docs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            map.putIfAbsent(docKey(docs.get(i)), i + 1); // rank 从 1 开始
        }
        return map;
    }

    private String docKey(EsDocument doc) {
        return doc.getFileMd5() + ":" + doc.getChunkId();
    }

    /** 生成查询向量 */
    private List<Float> embedToVectorList(String text) {
        try {
            List<float[]> vecs = embeddingClient.embed(List.of(text));
            if (vecs == null || vecs.isEmpty()) return null;
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) list.add(v);
            return list;
        } catch (Exception e) {
            logger.error("生成向量失败", e);
            return null;
        }
    }

    /** 获取用户有效组织标签 */
    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user;
            try {
                Long userIdLong = Long.parseLong(userId);
                user = userRepository.findById(userIdLong)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            }
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户组织标签失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /** 获取用户数据库 ID */
    private String getUserDbId(String userId) {
        try {
            User user;
            try {
                Long id = Long.parseLong(userId);
                user = userRepository.findById(id)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return id.toString();
            } catch (NumberFormatException e) {
                user = userRepository.findByUsername(userId)
                        .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
                return user.getId().toString();
            }
        } catch (Exception e) {
            throw new RuntimeException("获取用户数据库ID失败", e);
        }
    }

    /** 补充文件名 */
    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return;
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5).collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(FileUpload::getFileMd5, FileUpload::getFileName));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }
}
