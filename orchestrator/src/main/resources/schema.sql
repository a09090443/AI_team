-- AI Team Orchestrator 持久化結構
-- SQLite。所有 DDL 皆為 IF NOT EXISTS，可安全地重複執行。

CREATE TABLE IF NOT EXISTS task (
    id                TEXT PRIMARY KEY,
    slack_channel     TEXT    NOT NULL,
    slack_thread_ts   TEXT    NOT NULL,
    requester_user_id TEXT    NOT NULL,
    request_text      TEXT    NOT NULL,
    state             TEXT    NOT NULL,
    status_message_ts TEXT,
    pm_session_id     TEXT,
    pm_result         TEXT,              -- structured_output 的 JSON 原文
    redo_count        INTEGER NOT NULL DEFAULT 0,
    failure_reason    TEXT,
    created_at        TEXT    NOT NULL,
    updated_at        TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_state ON task(state);

-- 即使刪除最高編號任務也不重用 ID，避免舊 Slack 按鈕指到新任務。
CREATE TABLE IF NOT EXISTS task_sequence (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    value INTEGER NOT NULL
);
INSERT OR IGNORE INTO task_sequence(id,value) VALUES (1,1000);

-- Slack event_id 去重。
-- 必須持久化：服務重啟後，Slack 的重送若被當成新任務，同一個需求會被執行兩次。
CREATE TABLE IF NOT EXISTS processed_event (
    event_id TEXT PRIMARY KEY,
    seen_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_processed_event_seen_at ON processed_event(seen_at);

-- 稽核紀錄。Orchestrator 的三大職責之一，用來回答「這個任務到底發生過什麼事」。
CREATE TABLE IF NOT EXISTS audit_log (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id    TEXT,
    at         TEXT NOT NULL,
    actor      TEXT NOT NULL,     -- slack user id、system、或角色名稱
    action     TEXT NOT NULL,     -- STATE_CHANGE / BUTTON / RUNTIME_CALL / ERROR ...
    detail     TEXT,
    cost_usd   REAL,
    session_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_task ON audit_log(task_id, id);

-- 各階段的產出。用獨立資料表而非在 task 加欄位，
CREATE TABLE IF NOT EXISTS task_checkpoint (
    task_id TEXT PRIMARY KEY,
    data TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS direct_request (
    task_id TEXT PRIMARY KEY,
    data TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS flow_run (
    task_id TEXT PRIMARY KEY,
    data TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS task_remediation (
    task_id TEXT PRIMARY KEY,
    data TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS stage_attempt (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    role TEXT NOT NULL,
    session_id TEXT,
    result TEXT,
    completed_at TEXT NOT NULL
);

-- 這樣新增角色不需要改 schema。
CREATE TABLE IF NOT EXISTS stage_result (
    task_id      TEXT NOT NULL,
    role         TEXT NOT NULL,     -- pm / architecture / backend / qa
    session_id   TEXT,              -- 供 --resume 重做時續接
    result       TEXT,              -- structured_output 的 JSON 原文
    cost_usd     REAL,
    elapsed_sec  INTEGER,
    completed_at TEXT NOT NULL,
    PRIMARY KEY (task_id, role)
);
