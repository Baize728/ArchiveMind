package com.zyh.archivemind.feedback.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BadCaseFeedbackMessage implements Serializable {
    private String traceId;
    private String action;
    private String comment;
}
