package com.zipe.aiteam.task;

/** QA 受阻後的持久化派工狀態；不取代原 PM 與 QA 計畫。 */
public record Remediation(String taskId, String phase, int repairs, String context) {
    public static final int MAX_REPAIRS = 2;
    public boolean active() { return java.util.Set.of("pm_triage", "backend", "qa", "code_review").contains(phase); }
    public Remediation next(String next, int count, String detail) { return new Remediation(taskId, next, count, detail); }
}
