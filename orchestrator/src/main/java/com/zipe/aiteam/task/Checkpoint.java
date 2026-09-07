package com.zipe.aiteam.task;

/** 中斷階段的最小可恢復現場。工作樹與未提交檔案另保留在原工作區。 */
public record Checkpoint(String taskId, String role, String runtime, String model,
                         String sessionId, String upstream, String qaHead, String reason) {
    public Checkpoint withSession(String id) {
        return new Checkpoint(taskId, role, runtime, model, id, upstream, qaHead, reason);
    }
    public Checkpoint withReason(String message) {
        return new Checkpoint(taskId, role, runtime, model, sessionId, upstream, qaHead, message);
    }
}
