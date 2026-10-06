package com.zipe.aiteam.store;

import com.zipe.aiteam.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 稽核紀錄。同時寫入資料庫與應用程式 log。
 *
 * 先前的版本在任務開始執行後就一片空白，完成與否只能靠使用者回報。
 * 稽核是 Orchestrator 的三大職責之一（另兩項是任務狀態機與審批閘門）。
 *
 * 查詢方式：
 *   sqlite3 ~/ai-team/ai-team.db "SELECT at,actor,action,detail FROM audit_log WHERE task_id='1001' ORDER BY id"
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    public static final String ACTION_RECEIVED    = "RECEIVED";
    public static final String ACTION_STATE       = "STATE_CHANGE";
    public static final String ACTION_RUNTIME     = "RUNTIME_CALL";
    public static final String ACTION_BUTTON      = "BUTTON";
    public static final String ACTION_ERROR       = "ERROR";
    public static final String ACTION_REJECTED    = "REJECTED";
    public static final String ACTION_RECOVERY    = "RECOVERY";
    /** Agent 想用但被權限設定擋下的工具呼叫。不記錄的話會被誤判成 agent 能力不足。 */
    public static final String ACTION_DENIED      = "TOOL_DENIED";
    /** 負責人以 agent 提出的結案選項結束任務；狀態仍是 ABORTED，但啟動清理時保留工作區。 */
    public static final String ACTION_OWNER_CLOSE = "OWNER_CLOSE";

    public static final String ACTOR_SYSTEM = "system";

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String taskId, String actor, String action, String detail) {
        record(taskId, actor, action, detail, null, null);
    }

    public void record(String taskId, String actor, String action, String detail,
                       Double costUsd, String sessionId) {
        try {
            jdbc.update("""
                    INSERT INTO audit_log (task_id, at, actor, action, detail, cost_usd, session_id)
                    VALUES (?,?,?,?,?,?,?)
                    """,
                    taskId, Instant.now().toString(), actor, action,
                    truncate(detail), costUsd, sessionId);
        } catch (Exception e) {
            // 稽核寫入失敗不應該讓任務失敗，但要讓人看得到
            log.error("稽核紀錄寫入失敗 task={} action={}", taskId, action, e);
        }

        if (ACTION_ERROR.equals(action)) {
            log.error("[稽核] task={} actor={} {} : {}", taskId, actor, action, truncate(detail));
        } else {
            log.info("[稽核] task={} actor={} {} : {}{}", taskId, actor, action, truncate(detail),
                    costUsd == null ? "" : String.format(" (US$%.4f)", costUsd));
        }
    }

    /** 任務是否留有某種稽核事件。查詢失敗會拋出例外，由呼叫端決定保守做法。 */
    public boolean has(String taskId, String action) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE task_id=? AND action=?",
                Integer.class, taskId, action);
        return n != null && n > 0;
    }

    /** 狀態轉換是最常查的事件，給它一個專用入口確保格式一致。 */
    public void stateChange(Task task, Task.State from, Task.State to, String actor) {
        record(task.id(), actor, ACTION_STATE, from + " → " + to);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
    }
}
