package com.zyh.archivemind.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 长期记忆
 * 使用 MySQL 存储用户偏好和重要交互记录
 */
@Component
public class LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemory.class);

    private final AgentMemoryRepository memoryRepository;

    public LongTermMemory(AgentMemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    public AgentMemory store(String userId, String memoryType, String content, float importance) {
        AgentMemory memory = AgentMemory.builder()
                .userId(userId)
                .memoryType(memoryType)
                .content(content)
                .importance(importance)
                .build();
        AgentMemory saved = memoryRepository.save(memory);
        logger.info("存储长期记忆: userId={}, type={}, importance={}", userId, memoryType, importance);
        return saved;
    }

    public List<AgentMemory> retrieve(String userId, int limit) {
        return memoryRepository.findByUserIdOrderByImportanceDesc(
                userId, PageRequest.of(0, limit));
    }
}
