package com.zyh.archivemind.eval.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

@Data
public class EvalTask {
    private final String taskId;
    private Status status;
    private final AtomicInteger total = new AtomicInteger(0);
    private final AtomicInteger done = new AtomicInteger(0);
    private EvalReport result;
    private String errorMessage;
    private final LocalDateTime createdAt = LocalDateTime.now();

    public enum Status { RUNNING, COMPLETED, FAILED }

    public EvalTask(String taskId, Status status) {
        this.taskId = taskId;
        this.status = status;
    }

    public void complete(EvalReport report) {
        this.result = report;
        this.status = Status.COMPLETED;
    }

    public void fail(String msg) {
        this.errorMessage = msg;
        this.status = Status.FAILED;
    }

    public boolean isExpired(int hours) {
        return createdAt.plusHours(hours).isBefore(LocalDateTime.now());
    }
}
