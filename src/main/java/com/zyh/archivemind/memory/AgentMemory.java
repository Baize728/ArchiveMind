package com.zyh.archivemind.memory;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 长期记忆 JPA 实体
 * 对应 agent_memory 数据库表，存储用户偏好和重要交互记录
 */
@Entity
@Table(name = "agent_memory", indexes = {
        @Index(name = "idx_user_importance", columnList = "user_id, importance DESC")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "memory_type", nullable = false)
    private String memoryType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private float importance = 0.5f;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
