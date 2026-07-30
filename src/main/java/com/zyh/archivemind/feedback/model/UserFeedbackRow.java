package com.zyh.archivemind.feedback.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserFeedbackRow {
    private Long id;
    private String userId;
    private String conversationId;
    private String traceId;
    private String action;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
}
