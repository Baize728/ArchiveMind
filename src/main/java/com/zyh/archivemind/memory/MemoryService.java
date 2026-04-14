package com.zyh.archivemind.memory;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 记忆服务统一入口
 * 协调短期记忆和长期记忆
 */
@Service
public class MemoryService {

    private final ShortTermMemory shortTermMemory;
    private final LongTermMemory longTermMemory;

    public MemoryService(ShortTermMemory shortTermMemory, LongTermMemory longTermMemory) {
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
    }

    public void cacheToolResult(String userId, String toolName,
                                 Map<String, Object> params, String result) {
        shortTermMemory.cache(userId, toolName, params, result);
    }

    public String getCachedToolResult(String userId, String toolName,
                                       Map<String, Object> params) {
        return shortTermMemory.recall(userId, toolName, params);
    }

    public AgentMemory storeMemory(String userId, String memoryType,
                                    String content, float importance) {
        return longTermMemory.store(userId, memoryType, content, importance);
    }

    public List<AgentMemory> retrieveMemories(String userId, int limit) {
        return longTermMemory.retrieve(userId, limit);
    }
}
