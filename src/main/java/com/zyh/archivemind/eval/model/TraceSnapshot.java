package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceSnapshot {
    private String intent;
    private Map<String, String> slots;        // 4-dim: domain/docScope/timeRange/entity
    private String clarifyAction;             // ASK / READY
    private List<CitedChunk> citedChunks;
    private Long tokenCost;
    private boolean fallbackUsed;
    private String finalText;
    private String userInput;
}
