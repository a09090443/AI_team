package com.zipe.aiteam.slack;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.block.LayoutBlock;
import com.zipe.aiteam.config.AiTeamProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/**
 * 所有對 Slack 的發言都經過這裡。
 *
 * 每個角色可以是 workspace 裡獨立的使用者：只要 env.sh 有它專屬的
 * SLACK_BOT_TOKEN_&lt;角色&gt;，就用那個 app 的真實身分發言 ——
 * 會出現在成員清單、有自己的個人檔案與頭像、可以被 @mention。
 *
 * 沒有專屬 token 的角色退回主 token 加上 chat:write.customize 的名稱／圖示覆寫。
 * 保留這條退路，是為了讓「四個 app 還沒建好」的環境仍然完整可用，
 * 而不是安裝到一半就整條管線壞掉。
 *
 * 佇列狀態與錯誤一律走中性的系統身分 —— 基礎設施的訊息不該假裝是某個角色說的。
 */
@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    /** 角色外觀。只有在該角色沒有專屬 token 時才會用到。 */
    public static final class Identity {
        /** 系統訊息一律以主 App 本身的身分發出，不覆寫名稱；名稱只在 tag 不到時當後備文字。 */
        public static final Identity SYSTEM = new Identity("SYSTEM", "AI Team", ":robot_face:");
        public static final Identity PM = new Identity("PM", "PM Agent", ":clipboard:");
        public static final Identity ARCHITECTURE = new Identity("ARCHITECTURE", "Architecture Agent", ":triangular_ruler:");
        public static final Identity BACKEND = new Identity("BACKEND", "Backend Agent", ":hammer_and_wrench:");
        public static final Identity QA = new Identity("QA", "QA Agent", ":mag:");
        public static final Identity CODE_REVIEW = new Identity("CODE_REVIEW", "Code Reviewer", ":eyes:");
        private final String id;
        final String username;
        final String icon;
        private Identity(String id, String username, String icon) { this.id = id; this.username = username; this.icon = icon; }
        public static Identity of(String id, String display) { return new Identity(id.toUpperCase(java.util.Locale.ROOT), display, ":robot_face:"); }
        @com.fasterxml.jackson.annotation.JsonValue public String name() { return id; }
        @com.fasterxml.jackson.annotation.JsonCreator public static Identity valueOf(String id) {
            for (Identity identity : values()) if (identity.id.equalsIgnoreCase(id)) return identity;
            return of(id, id);
        }
        /** 僅舊版配置相容預設；新模式列舉來自 TeamCatalog。 */
        public static Identity[] values() { return new Identity[]{SYSTEM, PM, ARCHITECTURE, BACKEND, QA, CODE_REVIEW}; }
        @Override public boolean equals(Object other) { return other instanceof Identity identity && id.equals(identity.id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public String toString() { return id; }
    }

    private final AiTeamProperties props;
    private final Slack slack = Slack.getInstance();

    private final Map<Identity, MethodsClient> clients = new ConcurrentHashMap<>();
    /** 有專屬 token 的角色。這些不做名稱覆寫，讓真實身分顯示出來。 */
    private final Map<Identity, Boolean> ownAccount = new ConcurrentHashMap<>();

    /**
     * 各角色的 bot user ID，用來組出真正的 mention。
     *
     * 只有獨立帳號才有 —— 名稱覆寫出來的假身分沒有 user ID，tag 不到。
     * 這也是「每個 agent 是一個使用者」與「一個 bot 換名字」的實質差別所在。
     */
    private final Map<Identity, String> userIds = new ConcurrentHashMap<>();

    public SlackNotifier(AiTeamProperties props) {
        this.props = props;
    }

    @PostConstruct
    void buildClients() {
        MethodsClient main = slack.methods(props.slackBotToken());
        StringBuilder own = new StringBuilder();
        StringBuilder shared = new StringBuilder();

        for (Identity id : props.identities()) {
            String token = id == Identity.SYSTEM ? "" : props.roleBotToken(id.name());
            boolean hasOwn = !token.isEmpty();
            clients.put(id, hasOwn ? slack.methods(token) : main);
            ownAccount.put(id, hasOwn);
            if (id != Identity.SYSTEM) {
                (hasOwn ? own : shared).append(id.name()).append(' ');
            }
        }
        resolveUserIds();
        log.info("Slack 身分：獨立帳號 [{}]　共用主 token [{}]",
                own.toString().trim().isEmpty() ? "無" : own.toString().trim(),
                shared.toString().trim().isEmpty() ? "無" : shared.toString().trim());
        if (!shared.toString().trim().isEmpty()) {
            log.warn("共用主 token 的角色 tag 不到 —— 交接訊息中的 mention 會退成粗體名稱。"
                   + "要讓它們成為可被 @ 的獨立使用者，用 deploy/agent-manifests/ 建立對應的 App，"
                   + "把 token 填進 SLACK_BOT_TOKEN_<角色> 並邀請進頻道。");
        }
    }

    /**
     * 問每個獨立帳號自己的 user ID。
     *
     * 失敗只警告不中斷 —— 拿不到 ID 只是 mention 退化成粗體名稱，
     * 不該讓整個服務因為一個顯示層的問題起不來。
     */
    private void resolveUserIds() {
        for (Identity id : props.identities()) {
            if (id != Identity.SYSTEM && !Boolean.TRUE.equals(ownAccount.get(id))) continue;
            try {
                var resp = clients.get(id).authTest(r -> r);
                if (resp.isOk() && resp.getUserId() != null) {
                    userIds.put(id, resp.getUserId());
                } else {
                    log.warn("取得 {} 的 user ID 失敗：{}", id, resp.getError());
                }
            } catch (Exception e) {
                log.warn("取得 {} 的 user ID 時發生例外：{}", id, e.toString());
            }
        }
    }

    /**
     * 可以貼進訊息文字的 mention。
     *
     * 有 user ID 就產出真正的 <@Uxxx>（對方會收到通知、名字會 highlight）；
     * 沒有就退成粗體名稱 —— 看得懂，但 tag 不到人。
     */
    public String mention(Identity who) {
        String id = userIds.get(who);
        return id != null ? "<@" + id + ">" : "*" + who.username + "*";
    }

    /** 這個角色是否真的 tag 得到。用來決定要不要提醒使用者去建 App。 */
    public boolean isMentionable(Identity who) { return userIds.containsKey(who); }

    public boolean isAgentUser(String userId) { return userId != null && userIds.containsValue(userId); }

    /** 依任務快照的 token 引用解析接棒者，不以目前角色 ID 猜測舊任務的帳號。 */
    public String mentionConfigured(com.zipe.aiteam.flow.TeamDefinition.Role role) {
        String token = props.secretReference(role.botTokenEnv());
        if (!token.isBlank()) {
            String userId = configuredUserId(token);
            if (userId != null && userId.matches("[UW][A-Z0-9]+")) return "<@" + userId + ">";
        }
        log.warn("角色 {} 無法建立真實交接 mention，退回顯示名稱", role.name());
        return "*" + role.name() + "*（目前無法建立 @通知）";
    }

    String configuredUserId(String token) {
        try {
            var response = slack.methods(token).authTest(r -> r);
            return response.isOk() ? response.getUserId() : null;
        } catch (Exception e) {
            log.warn("解析交接接棒者失敗：{}", com.zipe.aiteam.runtime.Diagnostics.describe(e));
            return null;
        }
    }

    /** 以任務快照的 token 引用發言，配置刪除角色也不改成另一個人的帳號。 */
    public String postConfigured(com.zipe.aiteam.flow.TeamDefinition.Role role, String channel, String thread, String text) {
        String token = props.secretReference(role.botTokenEnv());
        if (token.isBlank()) return post(Identity.SYSTEM, channel, thread, "[" + role.name() + "；角色 token 未設定，系統代發]\n" + text);
        try {
            var response = slack.methods(token).chatPostMessage(r -> r.channel(channel).threadTs(thread).text(com.zipe.aiteam.runtime.Diagnostics.safe(text)));
            if (!response.isOk()) { log.error("角色 {} 發言失敗：{}", role.name(), response.getError()); return null; }
            return response.getTs();
        } catch (Exception e) { log.error("角色 {} 發言失敗：{}", role.name(), com.zipe.aiteam.runtime.Diagnostics.describe(e)); return null; }
    }

    /**
     * 只有「沒有專屬 token、借用主 App 發言的角色」才覆寫名稱與圖示。系統身分本來就是主 App，
     * 覆寫會把每個實例的主 App 都改成同一個寫死的名字：第二個實例的系統訊息曾因此顯示成第一個實例的 App 名稱。
     */
    static boolean overridesName(Identity who, boolean hasOwn) {
        return !hasOwn && !Identity.SYSTEM.equals(who);
    }

    /** @return 發出訊息的 ts，供之後 chat.update 使用；失敗回 null。 */
    public String post(Identity who, String channel, String threadTs, String text) {
        return post(who, channel, threadTs, text, null);
    }

    public String post(Identity who, String channel, String threadTs,
                       String text, List<LayoutBlock> blocks) {
        String safeText = com.zipe.aiteam.runtime.Diagnostics.safe(text);
        boolean hasOwn = Boolean.TRUE.equals(ownAccount.get(who));
        try {
            var resp = clients.getOrDefault(who, clients.get(Identity.SYSTEM)).chatPostMessage(r -> {
                r.channel(channel).threadTs(threadTs).text(safeText).blocks(blocks);
                // 有自己的帳號就不要覆寫顯示名稱，否則真實身分反而被蓋掉
                if (overridesName(who, hasOwn)) r.username(who.username).iconEmoji(who.icon);
                return r;
            });
            if (!resp.isOk()) {
                log.error("chatPostMessage 失敗（身分 {}）：{}", who, resp.getError());
                return null;
            }
            return resp.getTs();
        } catch (IOException | SlackApiException e) {
            log.error("chatPostMessage 例外（身分 {}）", who, e);
            return null;
        }
    }

    /**
     * 更新既有訊息，用來做進度回報而不洗版。
     *
     * 必須用「當初發出這則訊息的那個身分」來更新 —— Slack 只允許訊息的作者修改它。
     */
    public void update(Identity who, String channel, String ts,
                       String text, List<LayoutBlock> blocks) {
        if (ts == null) return;
        String safeText = com.zipe.aiteam.runtime.Diagnostics.safe(text);
        try {
            var resp = clients.getOrDefault(who, clients.get(Identity.SYSTEM)).chatUpdate(r -> r
                    .channel(channel)
                    .ts(ts)
                    .text(safeText)
                    .blocks(blocks));
            if (!resp.isOk()) {
                log.error("chatUpdate 失敗（身分 {}）：{}", who, resp.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.error("chatUpdate 例外（身分 {}）", who, e);
        }
    }
}
