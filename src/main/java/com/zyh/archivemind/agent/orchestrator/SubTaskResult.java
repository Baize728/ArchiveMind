package com.zyh.archivemind.agent.orchestrator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 子任务执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubTaskResult {
    private int id;
    private String description;
    private String result;
}
