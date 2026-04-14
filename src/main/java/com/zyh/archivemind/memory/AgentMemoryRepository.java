package com.zyh.archivemind.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Agent 长期记忆 JPA Repository
 */
public interface AgentMemoryRepository extends JpaRepository<AgentMemory, Long> {

    List<AgentMemory> findByUserIdOrderByImportanceDesc(String userId, Pageable pageable);
}
