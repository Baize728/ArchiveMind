package com.zyh.archivemind.eval.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class EvalSampleRow {
    private Long id;
    private String traceId;
    private String conversationId;
    private String expectedIntent;
    private String expectedSlots;      // JSON string
    private String expectedClarifyAction;
    private String expectedAnswer;
    private String labelNote;
    private String labeledBy;
    private LocalDateTime labeledAt;
    private String source;             // MANUAL / BADCASE_BACKFLOW
}
