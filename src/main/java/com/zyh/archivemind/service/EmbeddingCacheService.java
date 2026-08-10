package com.zyh.archivemind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Embedding 向量缓存服务。
 *
 * Embedding 函数对相同输入和模型是确定性的——SHA256(text+model) 作为 key，
 * 缓存到 Redis，TTL 永久（无过期问题）。
 *
 * 向量以 Base64(float[] bytes) 格式存储，2048d * 4B = 8KB/条，Redis 可存百万级。
 */
@Service
public class EmbeddingCacheService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCacheService.class);

    private final StringRedisTemplate redis;
    private final MessageDigest digest;
    private final String modelVersion;
    private static final String CACHE_PREFIX = "emb:";

    public EmbeddingCacheService(
            StringRedisTemplate redis,
            @Value("${embedding.api.model}") String modelVersion) {
        this.redis = redis;
        this.modelVersion = modelVersion;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
        this.digest = md;
    }

    /**
     * 获取缓存的向量，miss 返回 null。
     */
    public float[] get(String text) {
        String encoded = redis.opsForValue().get(cacheKey(text));
        if (encoded != null) {
            log.debug("Embedding 缓存命中: ...{}", cacheKey(text).substring(Math.min(cacheKey(text).length(), 20)));
            return decodeVector(encoded);
        }
        return null;
    }

    /**
     * 写入缓存。
     */
    public void put(String text, float[] vector) {
        redis.opsForValue().set(cacheKey(text), encodeVector(vector));
    }

    /**
     * 批量获取：已有缓存直接返回，未命中的保留 null 由调用方批量调 API 后回填。
     *
     * @return 与 texts 一一对应的向量数组，null 表示未命中
     */
    public float[][] batchGet(List<String> texts) {
        float[][] results = new float[texts.size()][];
        int hitCount = 0;
        for (int i = 0; i < texts.size(); i++) {
            results[i] = get(texts.get(i));
            if (results[i] != null) hitCount++;
        }
        if (texts.size() > 1) {
            log.info("Embedding 缓存批量查询: 命中 {}/{}, 未命中 {}", hitCount, texts.size(), texts.size() - hitCount);
        }
        return results;
    }

    /**
     * 批量回填未命中的向量。
     */
    public void batchPut(List<String> texts, List<float[]> vectors) {
        for (int i = 0; i < texts.size(); i++) {
            put(texts.get(i), vectors.get(i));
        }
    }

    private String cacheKey(String text) {
        byte[] hash = digest.digest((text + modelVersion).getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + modelVersion + ":" + bytesToHex(hash).substring(0, 32);
    }

    private String encodeVector(float[] vector) {
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float v : vector) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private float[] decodeVector(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        for (int i = 0; i < vector.length; i++) vector[i] = buf.getFloat();
        return vector;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
