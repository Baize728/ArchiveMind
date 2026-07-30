package com.zyh.archivemind.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeResult {
    private int faithfulness;      // 1-5
    private int relevance;         // 1-5
    private int completeness;      // 1-5
    private int naturalness;       // 1-5
    private Integer correctness;   // 1-5, null when no expected_answer
    private String reason;
}
