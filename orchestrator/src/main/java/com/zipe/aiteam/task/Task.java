package com.zipe.aiteam.task;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一個任務的完整狀態。
 *
 * 各階段的產出存在 stages 中（key 為角色名稱），由 TaskStore 持久化到
 * stage_result 資料表。新增角色不需要改這個類別。
 */
public class Task {

    /** 重做次數上限，避免人與 Agent 在迴圈裡互相消耗額度。 */
    public static final int MAX_REDO = 3;

    public enum State {
        QUEUED,
        WAITING_QUOTA,
        RESUME_QUEUED,
        TRIAGE_QUEUED,
        PM_TRIAGE_RUNNING,
        DIRECT_RUNNING,
        STAGE_RUNNING,
        PUBLISH_RUNNING,
        PM_RUNNING,
        ARCH_RUNNING,
        QA_PLAN_RUNNING,
        IMPL_RUNNING,
        QA_RUNNING,
        REVIEW_RUNNING,
        /** 全自動模式下唯一的人工閘門：推分支與開 PR 之前。 */
        AWAIT_PR_APPROVAL,
        /** PM 自認把握度低，或判定為純詢問，停下來等人決定。 */
        AWAIT_HUMAN,
        COMPLETED,
        /** 自動開發管線結束，不代表已合併、發布或產品驗收。 */
        PR_CREATED,
        ABORTED,
        FAILED;

        public boolean isRunning()  { return name().endsWith("_RUNNING"); }
        public boolean isWaiting()  { return this == AWAIT_PR_APPROVAL || this == AWAIT_HUMAN
                || this == WAITING_QUOTA || this == RESUME_QUEUED || this == TRIAGE_QUEUED; }
        public boolean isTerminal() { return this == COMPLETED || this == PR_CREATED || this == ABORTED || this == FAILED; }
    }

    private final String id;
    private final String slackChannel;
    private final String slackThreadTs;
    private final String requesterUserId;
    private final String requestText;
    private final Instant createdAt;

    private volatile State state;
    private volatile String statusMessageTs;
    private volatile int redoCount;
    private volatile String failureReason;
    private Checkpoint continuation;
    private String qaBaseline;
    public Checkpoint continuation() { return continuation; }
    public void continuation(Checkpoint value) { continuation = value; }
    public String qaBaseline() { return qaBaseline; }
    public void qaBaseline(String value) { qaBaseline = value; }

    private final Map<String, StageOutcome> stages = new ConcurrentHashMap<>();

    public Task(String id, String slackChannel, String slackThreadTs,
                String requesterUserId, String requestText) {
        this(id, slackChannel, slackThreadTs, requesterUserId, requestText,
             State.QUEUED, null, 0, null, Instant.now());
    }

    private Task(String id, String slackChannel, String slackThreadTs,
                 String requesterUserId, String requestText, State state,
                 String statusMessageTs, int redoCount, String failureReason,
                 Instant createdAt) {
        this.id = id;
        this.slackChannel = slackChannel;
        this.slackThreadTs = slackThreadTs;
        this.requesterUserId = requesterUserId;
        this.requestText = requestText;
        this.state = state;
        this.statusMessageTs = statusMessageTs;
        this.redoCount = redoCount;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
    }

    /** 從資料庫還原。只由 TaskStore 呼叫。 */
    public static Task rehydrate(String id, String slackChannel, String slackThreadTs,
                                 String requesterUserId, String requestText, State state,
                                 String statusMessageTs, int redoCount, String failureReason,
                                 Instant createdAt) {
        return new Task(id, slackChannel, slackThreadTs, requesterUserId, requestText,
                        state, statusMessageTs, redoCount, failureReason, createdAt);
    }

    public String  id()              { return id; }
    public String  slackChannel()    { return slackChannel; }
    public String  slackThreadTs()   { return slackThreadTs; }
    public String  requesterUserId() { return requesterUserId; }
    public String  requestText()     { return requestText; }
    public Instant createdAt()       { return createdAt; }

    public State   state()           { return state; }
    public String  statusMessageTs() { return statusMessageTs; }
    public int     redoCount()       { return redoCount; }
    public String  failureReason()   { return failureReason; }

    public void state(State s)             { this.state = s; }
    public void statusMessageTs(String ts) { this.statusMessageTs = ts; }
    public void failure(String reason)     { this.failureReason = reason; this.state = State.FAILED; }
    public int  incrementRedo()            { return ++redoCount; }

    // ------------------------------------------------------------------ 階段產出

    public void putStage(StageOutcome outcome) { stages.put(outcome.role(), outcome); }

    public Optional<StageOutcome> stage(String role) {
        return Optional.ofNullable(stages.get(role));
    }

    public Map<String, StageOutcome> stages() { return Map.copyOf(stages); }

    /** 目前為止的總花費，用於在 Slack 上顯示整個任務的成本。 */
    public double totalCost() {
        return stages.values().stream()
                .filter(s -> s.costUsd() != null)
                .mapToDouble(StageOutcome::costUsd)
                .sum();
    }

    @Override
    public String toString() {
        return "Task#" + id + "[" + state + "]";
    }
}
