package com.zyh.archivemind.agent.orchestrator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务执行计划
 * 由 LLM 分析用户消息复杂度后生成，包含子任务列表及其依赖关系
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    private boolean needsDecomposition;
    private List<SubTask> subTasks = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SubTask {
        private int id;
        private String description;
        private String agentType;
        private List<Integer> dependsOn = new ArrayList<>();
    }

    public static TaskPlan singleTask() {
        TaskPlan plan = new TaskPlan();
        plan.setNeedsDecomposition(false);
        plan.setSubTasks(Collections.emptyList());
        return plan;
    }
}
