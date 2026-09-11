-- AI Team Orchestrator 持久化結構
-- SQLite。所有 DDL 皆為 IF NOT EXISTS，可安全地重複執行。

-- 任務主表。一則 Slack 需求一列，id 由 task_sequence 發號（字串型別，排序要 CAST）。
CREATE TABLE IF NOT EXISTS task (
    id                TEXT PRIMARY KEY,  -- 序號字串，例如 '1014'
    slack_channel     TEXT    NOT NULL,  -- 需求所在頻道
    slack_thread_ts   TEXT    NOT NULL,  -- 討論串根訊息 ts；所有回報都貼回這裡
    requester_user_id TEXT    NOT NULL,  -- 提出者；也是唯一能核准的人
    request_text      TEXT    NOT NULL,  -- 原始需求文字，不做正規化
    state             TEXT    NOT NULL,  -- Task.State 列舉名稱，見 docs/data-model.md
    status_message_ts TEXT,              -- 狀態訊息 ts，供原地編輯進度
    pm_session_id     TEXT,              -- 遺留欄位，v2 後已無程式碼讀寫
    pm_result         TEXT,              -- 遺留欄位，產出改存 stage_result
    redo_count        INTEGER NOT NULL DEFAULT 0,  -- 重做次數，上限 Task.MAX_REDO
    failure_reason    TEXT,              -- 僅 FAILED 時有值
    created_at        TEXT    NOT NULL,  -- ISO-8601 UTC，建立後不再變動
    updated_at        TEXT    NOT NULL   -- ISO-8601 UTC，每次 save 覆寫
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
    task_id    TEXT,              -- 可為 NULL：任務尚未建立就發生的事件
    at         TEXT NOT NULL,     -- ISO-8601 UTC
    actor      TEXT NOT NULL,     -- slack user id、system、或角色名稱
    action     TEXT NOT NULL,     -- RECEIVED / STATE_CHANGE / RUNTIME_CALL / BUTTON /
                                  -- ERROR / REJECTED / RECOVERY / TOOL_DENIED
    detail     TEXT,              -- 自由文字，寫入前截斷至 2000 字元
    cost_usd   REAL,              -- 僅 runtime 有回報花費的事件才有值
    session_id TEXT               -- 對應 runtime session，供事後追查
);

CREATE INDEX IF NOT EXISTS idx_audit_task ON audit_log(task_id, id);

-- ------------------------------------------------------------------ 執行現場
-- 以下四張表都是「一個任務一列」的 JSON 快照（task_id + data）。
-- 之所以整份序列化而不展開成欄位：這些結構會隨角色與流程配置演進，
-- 展開後每加一個欄位就要動 schema 與遷移。欄位語意見 docs/data-model.md。

-- 中斷階段的最小可恢復現場（Checkpoint）。額度用盡或執行中斷後，
-- 靠它把同一個 session 接回來；工作樹與未提交檔案另存在工作區。
CREATE TABLE IF NOT EXISTS task_checkpoint (
    task_id TEXT PRIMARY KEY,          -- 對應 task.id
    data    TEXT NOT NULL              -- com.zipe.aiteam.task.Checkpoint 的 JSON
);

-- 對話另存的唯讀子請求（DirectRequest）。有自己的 task id，
-- 透過 parentId 指回原任務，不覆蓋原任務的驗收與 checkpoint。
CREATE TABLE IF NOT EXISTS direct_request (
    task_id TEXT PRIMARY KEY,          -- 子請求自己的 task.id
    data    TEXT NOT NULL              -- com.zipe.aiteam.task.DirectRequest 的 JSON
);

-- v2 配置式流程的整份執行快照（FlowRun）。每次狀態變更整份覆寫。
-- 快照內含當時的 teams 配置（snapshot.revision），因此磁碟上的配置改了
-- 也不會影響進行中的任務——這是刻意的，避免中途換規則。
CREATE TABLE IF NOT EXISTS flow_run (
    task_id TEXT PRIMARY KEY,          -- 對應 task.id
    data    TEXT NOT NULL              -- com.zipe.aiteam.flow.FlowRun 的 JSON
);

-- QA 受阻後的 PM 派工狀態（Remediation）。不取代原 PM 與 QA 計畫；
-- 修復次數上限 Remediation.MAX_REPAIRS，收斂後整列刪除。
CREATE TABLE IF NOT EXISTS task_remediation (
    task_id TEXT PRIMARY KEY,          -- 對應 task.id
    data    TEXT NOT NULL              -- com.zipe.aiteam.task.Remediation 的 JSON
);

-- ------------------------------------------------------------------ 階段產出
-- 每一次階段執行都留一列，包含被後續重做覆蓋掉的那幾次。
-- 目前只寫不讀，用途是事後追查「這個角色到底跑過幾次、每次交出什麼」。
CREATE TABLE IF NOT EXISTS stage_attempt (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id      TEXT NOT NULL,        -- 對應 task.id（未設外鍵，任務刪除不連動）
    role         TEXT NOT NULL,        -- 角色名稱，取值由 teams 配置決定
    session_id   TEXT,                 -- 該次執行的 runtime session
    result       TEXT,                 -- structured_output 的 JSON 原文
    completed_at TEXT NOT NULL         -- ISO-8601 UTC
);

-- 各角色的「最新」產出，(task_id, role) 唯一，重做時覆蓋。
-- 用獨立資料表而非在 task 加欄位，這樣新增角色不需要改 schema。
CREATE TABLE IF NOT EXISTS stage_result (
    task_id      TEXT NOT NULL,
    role         TEXT NOT NULL,     -- pm / architecture / backend / qa
    session_id   TEXT,              -- 供 --resume 重做時續接
    result       TEXT,              -- structured_output 的 JSON 原文
    cost_usd     REAL,              -- 該階段花費（USD）；runtime 沒回報則為 NULL
    elapsed_sec  INTEGER,           -- 該階段耗時（秒）
    completed_at TEXT NOT NULL,     -- ISO-8601 UTC
    PRIMARY KEY (task_id, role)
);
