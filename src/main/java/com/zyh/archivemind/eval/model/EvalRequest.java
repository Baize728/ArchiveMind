package com.zyh.archivemind.eval.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EvalRequest {
    private String mode;        // TIME_RANGE / GOLD_SET / BADCASE_ONLY
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String userId;
    private Boolean includeJudge;
    private Integer limit;
}
