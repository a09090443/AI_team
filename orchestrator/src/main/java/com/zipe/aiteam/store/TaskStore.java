package com.zipe.aiteam.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.task.StageOutcome;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.task.Checkpoint;
import com.zipe.aiteam.task.Remediation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 任務狀態、階段產出、去重表的持久化。
 *
 * 這一層存在的理由：先前全部放在記憶體，服務一重啟所有進行中的任務與
 * 按鈕對應的 taskId 就消失，Slack 上的按鈕會變成死的；重啟後 Slack 的
 * 重送也會被當成新任務再跑一次。
 */
@Repository
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);

    /** 去重表保留時間。Slack 最長在 5 分鐘後重送，1 小時綽綽有餘。 */
    private static final long DEDUP_RETENTION_HOURS = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 啟動時把序號接續到既有最大值，避免重啟後 id 重複。 */
    public void initSequence() {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(id AS INTEGER)), 1000) FROM task", Long.class);
        jdbc.update("UPDATE task_sequence SET value=MAX(value,?) WHERE id=1", max == null ? 1000 : max);
        log.info("任務序號接續自 {}", jdbc.queryForObject("SELECT value FROM task_sequence WHERE id=1", Long.class));
    }

    public String nextTaskId() {
        Long next = jdbc.queryForObject("UPDATE task_sequence SET value=value+1 WHERE id=1 RETURNING value", Long.class);
        if (next == null) throw new IllegalStateException("任務序號遺失，拒絕重用歷史任務 ID");
        return String.valueOf(next);
    }

    public void saveDirect(com.zipe.aiteam.task.DirectRequest request) {
        try {
            jdbc.update("INSERT INTO direct_request(task_id,data) VALUES (?,?)", request.taskId(), mapper.writeValueAsString(request));
        } catch (java.io.IOException e) { throw new IllegalStateException("無法保存角色請求", e); }
    }

    public Optional<com.zipe.aiteam.task.DirectRequest> direct(String id) {
        return jdbc.query("SELECT data FROM direct_request WHERE task_id=?", (rs, n) -> {
            try { return mapper.readValue(rs.getString(1), com.zipe.aiteam.task.DirectRequest.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("角色請求紀錄損毀", e); }
        }, id).stream().findFirst();
    }

    /** 精確限定頻道與討論串，不混入其他任務。 */
    public List<Task> inThread(String channel, String thread) {
        List<Task> tasks = jdbc.query("SELECT * FROM task WHERE slack_channel=? AND slack_thread_ts=? ORDER BY CAST(id AS INTEGER)",
                TASK_MAPPER, channel, thread);
        tasks.forEach(this::loadStages);
        return tasks;
    }

    public void saveCheckpoint(Checkpoint checkpoint) {
        try {
            jdbc.update("INSERT INTO task_checkpoint(task_id,data) VALUES (?,?) "
                    + "ON CONFLICT(task_id) DO UPDATE SET data=excluded.data",
                    checkpoint.taskId(), mapper.writeValueAsString(checkpoint));
        } catch (java.io.IOException e) { throw new IllegalStateException("無法保存續跑現場", e); }
    }

    public Optional<Checkpoint> checkpoint(String taskId) {
        return jdbc.query("SELECT data FROM task_checkpoint WHERE task_id=?", (rs, n) -> {
            try { return mapper.readValue(rs.getString(1), Checkpoint.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("續跑現場無法解析", e); }
        }, taskId).stream().findFirst();
    }

    public void saveFlow(String id, com.zipe.aiteam.flow.FlowRun flow) {
        try {
            jdbc.update("INSERT INTO flow_run(task_id,data) VALUES (?,?) ON CONFLICT(task_id) DO UPDATE SET data=excluded.data", id, mapper.writeValueAsString(flow));
        } catch (java.io.IOException e) { throw new IllegalStateException("無法保存流程快照", e); }
    }
    public Optional<com.zipe.aiteam.flow.FlowRun> flow(String id) {
        return jdbc.query("SELECT data FROM flow_run WHERE task_id=?", (rs, n) -> {
            try { return mapper.readValue(rs.getString(1), com.zipe.aiteam.flow.FlowRun.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("流程快照損毀，拒絕改用新配置", e); }
        }, id).stream().findFirst();
    }
    public boolean claimState(String id, Task.State from, Task.State to) {
        return jdbc.update("UPDATE task SET state=?,updated_at=? WHERE id=? AND state=?", to.name(), Instant.now().toString(), id, from.name()) == 1;
    }

    /** SQLite 原子狀態轉換，連按／重送只有一次可排入佇列。 */
    public boolean claimContinuation(String taskId) {
        return jdbc.update("UPDATE task SET state='RESUME_QUEUED',updated_at=? "
                + "WHERE id=? AND state='WAITING_QUOTA'", Instant.now().toString(), taskId) == 1;
    }

    // ------------------------------------------------------------------ 任務

    public void saveRemediation(Remediation r) {
        try {
            jdbc.update("INSERT INTO task_remediation(task_id,data) VALUES (?,?) ON CONFLICT(task_id) DO UPDATE SET data=excluded.data",
                    r.taskId(), mapper.writeValueAsString(r));
        } catch (java.io.IOException e) { throw new IllegalStateException("無法保存 PM 派工狀態", e); }
    }

    public Optional<Remediation> remediation(String id) {
        return jdbc.query("SELECT data FROM task_remediation WHERE task_id=?", (rs, n) -> {
            try { return mapper.readValue(rs.getString(1), Remediation.class); }
            catch (java.io.IOException e) { throw new IllegalStateException("PM 派工狀態損毀", e); }
        }, id).stream().findFirst();
    }

    public boolean claimTriage(String id) {
        return jdbc.update("UPDATE task SET state='TRIAGE_QUEUED',updated_at=? WHERE id=? AND state='AWAIT_HUMAN'",
                Instant.now().toString(), id) == 1;
    }

    public void clearRemediation(String id) {
        jdbc.update("DELETE FROM task_remediation WHERE task_id=?", id);
    }

    public void save(Task t) {
        jdbc.update("""
                INSERT INTO task (id, slack_channel, slack_thread_ts, requester_user_id,
                                  request_text, state, status_message_ts,
                                  redo_count, failure_reason, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    state             = excluded.state,
                    status_message_ts = excluded.status_message_ts,
                    redo_count        = excluded.redo_count,
                    failure_reason    = excluded.failure_reason,
                    updated_at        = excluded.updated_at
                """,
                t.id(), t.slackChannel(), t.slackThreadTs(), t.requesterUserId(),
                t.requestText(), t.state().name(), t.statusMessageTs(),
                t.redoCount(), t.failureReason(),
                t.createdAt().toString(), Instant.now().toString());
    }

    public void saveStage(String taskId, StageOutcome s) {
        jdbc.update("INSERT INTO stage_attempt(task_id,role,session_id,result,completed_at) VALUES (?,?,?,?,?)",
                taskId, s.role(), s.sessionId(), s.result() == null ? null : s.result().toString(), Instant.now().toString());
        jdbc.update("""
                INSERT INTO stage_result (task_id, role, session_id, result,
                                          cost_usd, elapsed_sec, completed_at)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(task_id, role) DO UPDATE SET
                    session_id   = excluded.session_id,
                    result       = excluded.result,
                    cost_usd     = excluded.cost_usd,
                    elapsed_sec  = excluded.elapsed_sec,
                    completed_at = excluded.completed_at
                """,
                taskId, s.role(), s.sessionId(),
                s.result() == null ? null : s.result().toString(),
                s.costUsd(), s.elapsedSec(), Instant.now().toString());
    }

    public Optional<Task> find(String taskId) {
        List<Task> rows = jdbc.query("SELECT * FROM task WHERE id = ?", TASK_MAPPER, taskId);
        if (rows.isEmpty()) return Optional.empty();
        Task t = rows.get(0);
        loadStages(t);
        return Optional.of(t);
    }

    /** 重啟時用來恢復尚未結束的任務。 */
    public List<Task> findUnfinished() {
        List<Task> rows = jdbc.query("""
                SELECT * FROM task
                WHERE state NOT IN ('COMPLETED','PR_CREATED','ABORTED','FAILED')
                ORDER BY CAST(id AS INTEGER)
                """, TASK_MAPPER);
        rows.forEach(this::loadStages);
        return rows;
    }

    private void loadStages(Task t) {
        jdbc.query("SELECT * FROM stage_result WHERE task_id = ?", rs -> {
            JsonNode result = null;
            String raw = rs.getString("result");
            if (raw != null && !raw.isBlank()) {
                try {
                    result = mapper.readTree(raw);
                } catch (Exception e) {
                    log.warn("任務 {} 階段 {} 的結果無法解析", t.id(), rs.getString("role"));
                }
            }
            Double cost = rs.getObject("cost_usd") == null ? null : rs.getDouble("cost_usd");
            t.putStage(new StageOutcome(
                    rs.getString("role"), rs.getString("session_id"),
                    result, cost, rs.getLong("elapsed_sec")));
        }, t.id());
    }

    private static final RowMapper<Task> TASK_MAPPER = (rs, n) -> Task.rehydrate(
            rs.getString("id"),
            rs.getString("slack_channel"),
            rs.getString("slack_thread_ts"),
            rs.getString("requester_user_id"),
            rs.getString("request_text"),
            Task.State.valueOf(rs.getString("state")),
            rs.getString("status_message_ts"),
            rs.getInt("redo_count"),
            rs.getString("failure_reason"),
            Instant.parse(rs.getString("created_at")));

    // ------------------------------------------------------------------ 去重

    /** @return true 表示這是第一次看到；false 表示重複事件，應忽略。 */
    public boolean markEventSeen(String eventId) {
        if (eventId == null || eventId.isBlank()) return true;
        return jdbc.update(
                "INSERT OR IGNORE INTO processed_event (event_id, seen_at) VALUES (?, ?)",
                eventId, Instant.now().toString()) > 0;
    }

    public int purgeOldEvents() {
        String cutoff = Instant.now().minus(DEDUP_RETENTION_HOURS, ChronoUnit.HOURS).toString();
        return jdbc.update("DELETE FROM processed_event WHERE seen_at < ?", cutoff);
    }
}
