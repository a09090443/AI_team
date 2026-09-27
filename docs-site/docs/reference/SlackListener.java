/*
 * 參考實作 — 用來說明 architecture-review.md 的 B1 與 I5 修正點。
 * 這不是可直接部署的成品：去重表用記憶體實作（正式環境應改用 Redis 或資料庫，
 * 才能在重啟與多實例下生效），也省略了錯誤處理與 Block Kit 排版。
 *
 * 對照原文（第 22 節）修正了：
 *   B1  ack 提前到處理之前，避免 Slack 逾時重送造成同一需求被執行四次
 *   B1  以 event_id 去重，擋掉已經發生的重送
 *   I5  觸發者白名單
 *   I3  以 thread_ts 作為 session 續接的鍵
 */
package com.example.aiteam.slack;

import com.example.aiteam.task.InboundRequest;
import com.example.aiteam.task.TaskQueue;
import com.slack.api.bolt.App;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.event.AppMentionEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SlackListener {

    private final TaskQueue taskQueue;
    private final Set<String> allowedUserIds;

    /**
     * B1：Slack 在未於 3 秒內收到 ack 時會重送 3 次（立即、約 1 分、約 5 分）。
     * 就算 ack 已經提前，網路抖動與部署重啟仍可能造成重送，因此去重是必要的。
     *
     * 正式環境改用 Redis SETNX 並設 TTL（例如 10 分鐘，涵蓋 Slack 的重送窗口），
     * 否則多實例部署或重啟後去重會失效。
     */
    private final Map<String, Boolean> seenEventIds = new ConcurrentHashMap<>();

    public SlackListener(TaskQueue taskQueue, Set<String> allowedUserIds) {
        this.taskQueue = taskQueue;
        this.allowedUserIds = allowedUserIds;
    }

    public void start() throws Exception {
        App app = new App();

        app.event(AppMentionEvent.class, (payload, ctx) -> {

            // ---- 以下所有動作都必須在 3 秒內完成 ----

            String eventId = payload.getEventId();
            if (seenEventIds.putIfAbsent(eventId, Boolean.TRUE) != null) {
                ctx.logger.info("忽略重複事件 {}", eventId);
                return ctx.ack();
            }

            AppMentionEvent event = payload.getEvent();

            // I5：以 Slack user ID 授權，不倚賴頻道權限。
            // 這個 bot 能改程式碼、執行 shell、建立 commit，不該讓任何
            // workspace 成員都能觸發。
            if (!allowedUserIds.contains(event.getUser())) {
                taskQueue.postRejection(
                        event.getChannel(),
                        threadTsOf(event),
                        "你目前不在允許清單中。請聯絡管理者。");
                return ctx.ack();
            }

            // I3：同一 thread 的後續訊息會續接同一個 Claude Code session。
            // 首則訊息的 thread_ts 為 null，此時用它自己的 ts。
            taskQueue.submit(new InboundRequest(
                    eventId,
                    event.getChannel(),
                    threadTsOf(event),
                    event.getUser(),
                    event.getText()));

            // 連「已收到」的回覆都交給 worker 發送。
            // handler 內每多一次 Slack API 往返就多一次逾時風險，
            // 這裡保持在微秒等級。
            return ctx.ack();
        });

        new SocketModeApp(System.getenv("SLACK_APP_TOKEN"), app).start();
    }

    private static String threadTsOf(AppMentionEvent event) {
        return event.getThreadTs() != null ? event.getThreadTs() : event.getTs();
    }
}

/*
 * 背景 worker 的職責（在 TaskQueue / TaskOrchestrator 中實作）：
 *
 *   1. 立刻回覆「已收到，任務 #N 已排入佇列」到原 thread
 *   2. 取得全域並行許可（訂閱帳號下上限為 1，見 I6）
 *   3. 查出 thread_ts 對應的 session_id（若有）
 *   4. 依角色呼叫 ClaudeCodeRunner
 *   5. 存回新的 session_id
 *   6. 用 chat.postMessage 把結果回覆到原 thread
 *   7. 需要人工核准時，附上互動按鈕並把任務狀態停在 AWAIT_HUMAN
 *
 * 關鍵：步驟 2 到 6 全部發生在 ack 之後，不受 3 秒限制。
 */
