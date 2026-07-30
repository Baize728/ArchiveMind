package com.zyh.archivemind.feedback.model;

import lombok.Data;

@Data
public class FeedbackRequest {
    private String conversationId;
    private String traceId;
    private String action;     // LIKE / DISLIKE / PARTIAL_CORRECT / OUTDATED
    private Integer rating;    // optional 1-5
    private String comment;    // optional
}
