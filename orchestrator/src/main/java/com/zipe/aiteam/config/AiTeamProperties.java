package com.zipe.aiteam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 設定全部來自 ~/ai-team/env.sh 匯出的環境變數。
 * Token 只存在於 VM 的 env.sh（權限 600），不進版控、不寫入設定檔。
 */
@Component
public class AiTeamProperties {

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.zipe.aiteam.flow.TeamCatalog teamCatalog;
    public boolean configuredTeam() { return teamCatalog != null && teamCatalog.enabled(); }
    public java.util.List<String> requiredBuildTools() {
        return configuredTeam() ? teamCatalog.snapshot().definition().project().requiredTools() : java.util.List.of("mvn");
    }
    public String secretReference(String name) { return teamCatalog == null ? "" : teamCatalog.secret(name); }
    public java.util.List<com.zipe.aiteam.slack.SlackNotifier.Identity> identities() {
        if (!configuredTeam()) return java.util.List.of(com.zipe.aiteam.slack.SlackNotifier.Identity.values());
        var identities = new java.util.ArrayList<com.zipe.aiteam.slack.SlackNotifier.Identity>();
        identities.add(com.zipe.aiteam.slack.SlackNotifier.Identity.SYSTEM);
        teamCatalog.snapshot().definition().roles().forEach((id, role) -> identities.add(com.zipe.aiteam.slack.SlackNotifier.Identity.of(id, role.name())));
        return java.util.List.copyOf(identities);
    }

    @Value("${SLACK_APP_TOKEN:}")
    private String slackAppToken;

    @Value("${SLACK_BOT_TOKEN:}")
    private String slackBotToken;

    @Value("${SLACK_APP_TOKEN_PM:}") private String appTokenPm;
    @Value("${SLACK_APP_TOKEN_ARCHITECTURE:}") private String appTokenArchitecture;
    @Value("${SLACK_APP_TOKEN_BACKEND:}") private String appTokenBackend;
    @Value("${SLACK_APP_TOKEN_QA:}") private String appTokenQa;
    @Value("${SLACK_APP_TOKEN_CODE_REVIEW:}") private String appTokenCodeReview;

    public String roleAppToken(String role) {
        if (configuredTeam()) {
            var configured = teamCatalog.role(role);
            return configured == null ? "" : teamCatalog.secret(configured.appTokenEnv());
        }
        String token = switch (role.toUpperCase(java.util.Locale.ROOT)) {
            case "PM" -> appTokenPm;
            case "ARCHITECTURE" -> appTokenArchitecture;
            case "BACKEND" -> appTokenBackend;
            case "QA" -> appTokenQa;
            case "CODE_REVIEW" -> appTokenCodeReview;
            default -> "";
        };
        return token == null ? "" : token.trim();
    }

    private static String configuredRole(String role) {
        return role.toUpperCase(java.util.Locale.ROOT).replaceFirst("^DIRECT_", "");
    }

    /**
     * 各角色專屬的 Bot Token。填了就以該 app 的真實身分發言，
     * 沒填則退回主 token + chat:write.customize 的名稱覆寫。
     * 分開設定是為了讓「還沒建好四個 app」的環境仍然可用。
     */
    @Value("${SLACK_BOT_TOKEN_PM:}")
    private String slackBotTokenPm;

    @Value("${SLACK_BOT_TOKEN_ARCHITECTURE:}")
    private String slackBotTokenArchitecture;

    @Value("${SLACK_BOT_TOKEN_BACKEND:}")
    private String slackBotTokenBackend;

    @Value("${SLACK_BOT_TOKEN_CODE_REVIEW:}")
    private String slackBotTokenCodeReview;

    @Value("${SLACK_BOT_TOKEN_QA:}")
    private String slackBotTokenQa;

    /**
     * 各角色使用的模型。留空表示用 claude CLI 的預設（目前是 sonnet）。
     * 接受別名（sonnet／opus／haiku）或完整模型 ID。
     */
    @Value("${AI_TEAM_MODEL_PM:}")
    private String modelPm;

    @Value("${AI_TEAM_MODEL_ARCHITECTURE:}")
    private String modelArchitecture;

    @Value("${AI_TEAM_MODEL_BACKEND:}")
    private String modelBackend;

    @Value("${AI_TEAM_MODEL_CODE_REVIEW:}")
    private String modelCodeReview;

    @Value("${AI_TEAM_MODEL_QA:}")
    private String modelQa;

    /**
     * 每個角色要用哪個 runtime（值為 AgentRuntime.id()，例如 claude-code、codex）。
     * 留空一律退回 claude-code —— 新增 runtime 不該改變既有行為。
     */
    @Value("${AI_TEAM_RUNTIME_PM:}")
    private String runtimePm;

    @Value("${AI_TEAM_RUNTIME_ARCHITECTURE:}")
    private String runtimeArchitecture;

    @Value("${AI_TEAM_RUNTIME_BACKEND:}")
    private String runtimeBackend;

    @Value("${AI_TEAM_RUNTIME_CODE_REVIEW:}")
    private String runtimeCodeReview;

    @Value("${AI_TEAM_RUNTIME_QA:}")
    private String runtimeQa;

    /**
     * 階段回報的詳細程度：brief（預設）只貼判定／摘要／阻礙／建議後續，full 貼完整報告。
     *
     * 預設改成 brief 是因為完整報告動輒數千字，真正需要人處理的部分會被情境清單
     * 與證據淹沒。完整報告沒有消失，仍存在 stage_result，可用
     * 「補充 #<ID> 檢視完整報告」隨時叫出來。
     */
    @Value("${AI_TEAM_REPORT_DETAIL:brief}")
    private String reportDetail;

    public boolean briefReports() {
        return reportDetail == null || !"full".equalsIgnoreCase(reportDetail.trim());
    }

    /**
     * 啟動時自動跑基準驗證（目前配置＋基準分支最新 commit 還沒有結果時）。預設 on；off 只關掉自動觸發，
     * Slack 的「基準驗證」指令照樣可用。
     */
    @Value("${AI_TEAM_BASELINE_VERIFY:on}")
    private String baselineVerify;

    public boolean baselineVerifyOnStart() {
        return baselineVerify == null || !"off".equalsIgnoreCase(baselineVerify.trim());
    }

    /** Code Review 階段的開關。預設開啟；設成 off 可整個略過。 */
    @Value("${AI_TEAM_CODE_REVIEW:on}")
    private String codeReview;

    @Value("${AI_TEAM_ALLOWED_USERS:}")
    private String allowedUsersRaw;

    @Value("${AI_TEAM_CHANNEL_ID:}")
    private String channelId;

    @Value("${CLAUDE_BIN:}")
    private String claudeBin;

    @Value("${CODEX_BIN:}")
    private String codexBin;

    @Value("${OPENCODE_BIN:}")
    private String opencodeBin;

    @Value("${AI_TEAM_REPO:}")
    private String repoPath;

    @Value("${AI_TEAM_HOME:}")
    private String homePath;

    @Value("${AI_TEAM_LOGS:}")
    private String logsPath;

    @Value("${AI_TEAM_WORKSPACES:}")
    private String workspacesPath;

    @Value("${AI_TEAM_MAX_CONCURRENCY:1}")
    private int maxConcurrency;

    @Value("${AI_TEAM_TASK_TIMEOUT_SECONDS:0}")
    private long taskTimeoutSeconds;

    /**
     * 系統出錯時請 agent 以白話說明錯誤所用的模型（claude-code runtime）。每次停給人呼叫一次，
     * 說明不需要深度推理，預設用便宜快速的 haiku。設 AI_TEAM_EXPLAINER_ENABLED=false 則一律用固定的後備文字。
     */
    @Value("${AI_TEAM_EXPLAINER_MODEL:haiku}")
    private String explainerModel = "haiku";
    @Value("${AI_TEAM_EXPLAINER_ENABLED:true}")
    private boolean explainerEnabled = true;

    public String explainerModel() { return explainerModel == null || explainerModel.isBlank() ? "haiku" : explainerModel.trim(); }
    public boolean explainerEnabled() { return explainerEnabled; }

    @Value("${AI_TEAM_PROGRESS_INTERVAL_SECONDS:300}")
    private long progressIntervalSeconds = 300;

    public long progressIntervalSeconds() { return Math.max(30, progressIntervalSeconds); }

    @Value("${AI_TEAM_GIT_TIMEOUT_SECONDS:300}")
    private long gitTimeoutSeconds;

    @Value("${AI_TEAM_GIT_USER_NAME:AI Team}")
    private String gitUserName;

    @Value("${AI_TEAM_GIT_USER_EMAIL:ai-team@localhost}")
    private String gitUserEmail;

    @Value("${AI_TEAM_BASE_BRANCH:}")
    private String baseBranch;

    @Value("${AI_TEAM_BRANCH_PREFIX:feature/ai-team-task-}")
    private String branchPrefix;

    @Value("${AI_TEAM_GITHUB_TOKEN:}")
    private String githubToken;

    @Value("${AI_TEAM_INSTANCE:}")
    private String instance;

    /** 舊格式 unit 名稱的固定字首；當作實例名會讓 glob 互相配到，所以保留。 */
    private static final Set<String> RESERVED_INSTANCES = Set.of("check", "preflight", "selftest", "codex");

    /**
     * 本服務實例的 systemd unit 名稱字首：空 → {@code ai-team-}（單一實例，名稱與舊版完全相同），
     * 有值 → {@code ai-team-<實例>-}。
     *
     * 同一台 VM 跑多個專案服務時，各實例的任務編號都從 #1001 起；沒有字首的話，
     * 「中斷這一輪」以 glob 停 unit 會把另一個專案同編號任務的 agent 一起停掉。
     * 實例名必須以字母開頭：任務編號是純數字，舊格式 glob（ai-team-1001-*）與新格式互不配對。
     */
    public String unitPrefix() { return unitPrefixFor(instance); }

    static String unitPrefixFor(String instance) {
        String value = instance == null ? "" : instance.trim();
        if (value.isEmpty()) return "ai-team-";
        if (!value.matches("[a-z][a-z0-9]{0,15}") || RESERVED_INSTANCES.contains(value))
            throw new IllegalStateException("AI_TEAM_INSTANCE 無效：須為小寫英文字母開頭、"
                    + "只含小寫英數、最多 16 字，且不可為 check／preflight／selftest／codex");
        return "ai-team-" + value + "-";
    }

    public String slackAppToken()  { return slackAppToken; }
    public String slackBotToken()  { return slackBotToken; }

    /**
     * 依角色名稱取得專屬 token，沒有就回空字串。
     * 角色名稱用 SlackNotifier.Identity 的 name()，大小寫不敏感。
     */
    /**
     * 依角色名稱取得指定的模型，沒設定就回空字串。
     *
     * 空字串代表「不要傳 --model」，交給 CLI 決定 —— 這與「傳一個空值」不同，
     * 後者會讓 claude 以結束碼 1 失敗。
     */
    public String roleModel(String role) {
        if (configuredTeam() && teamCatalog.role(role) != null) return java.util.Objects.toString(teamCatalog.role(role).model(), "");
        String m = switch (configuredRole(role)) {
            case "PM", "PM_TRIAGE" -> modelPm;
            case "ARCHITECTURE" -> modelArchitecture;
            case "BACKEND"      -> modelBackend;
            case "QA", "QA_PLAN" -> modelQa;
            case "CODE_REVIEW"  -> modelCodeReview;
            default             -> "";
        };
        return m == null ? "" : m.trim();
    }

    /**
     * 這個角色要用的 runtime id。空值退回 claude-code。
     *
     * 讓環境變數決定而不是寫死，是為了能對同一個需求換 runtime 重跑做比較 ——
     * 這正是目前用來分辨「改動有沒有效」與「這次剛好走了另一條路」的手段。
     */
    public String roleRuntime(String role) {
        if (configuredTeam() && teamCatalog.role(role) != null) return teamCatalog.role(role).runtime();
        String r = switch (configuredRole(role)) {
            case "PM", "PM_TRIAGE" -> runtimePm;
            case "ARCHITECTURE" -> runtimeArchitecture;
            case "BACKEND"      -> runtimeBackend;
            case "QA", "QA_PLAN" -> runtimeQa;
            case "CODE_REVIEW"  -> runtimeCodeReview;
            default             -> "";
        };
        r = r == null ? "" : r.trim();
        return r.isEmpty() ? "claude-code" : r;
    }

    public String roleBotToken(String role) {
        if (configuredTeam()) {
            var configured = teamCatalog.role(role);
            return configured == null ? "" : teamCatalog.secret(configured.botTokenEnv());
        }
        String t = switch (role.toUpperCase()) {
            case "PM"           -> slackBotTokenPm;
            case "ARCHITECTURE" -> slackBotTokenArchitecture;
            case "BACKEND"      -> slackBotTokenBackend;
            case "QA"           -> slackBotTokenQa;
            case "CODE_REVIEW"  -> slackBotTokenCodeReview;
            default             -> "";
        };
        return t == null ? "" : t.trim();
    }
    public String channelId()      { return channelId; }
    public Path   claudeBin()      { return Path.of(claudeBin); }
    public Path   codexBin()       { return Path.of(codexBin); }
    public Path   opencodeBin()    { return Path.of(opencodeBin); }
    public Path   repoPath()       { return configuredTeam() ? Path.of(teamCatalog.snapshot().repository()) : Path.of(repoPath); }
    public Path   homePath()       { return Path.of(homePath); }
    public Path   logsPath()       { return Path.of(logsPath); }
    public Path   workspacesPath() { return Path.of(workspacesPath); }
    public int    maxConcurrency() { return maxConcurrency; }

    /** 作業系統層級的家目錄，與 AI_TEAM_HOME（~/ai-team）不同，別混用。 */
    public Path   userHome()       { return Path.of(System.getProperty("user.home")); }

    /** Maven 本機倉庫。沙箱內不給寫的話每次建置都要重抓依賴。 */
    public Path   mavenRepo()      { return userHome().resolve(".m2"); }
    /**
     * 全域逾時覆寫。0（預設）表示採用各階段自己的值 —— 見 StageSpec.timeoutSeconds。
     * 各階段的成本差很多：PM 一分鐘，Backend 光跑一次 mvn verify 就要兩分鐘。
     * 這個開關留著是為了「現在就是想全部縮短或拉長」的情況。
     */
    public Duration taskTimeout()  { return Duration.ofSeconds(taskTimeoutSeconds); }

    /** git 單一指令的逾時。clone 一個 7 MB 的 repo 約 0.3 秒，300 秒是給網路操作的餘裕。 */
    public Duration gitTimeout()   { return Duration.ofSeconds(gitTimeoutSeconds); }

    /** Agent 產生的 commit 的署名。寫在工作區的 local config，不動使用者的 ~/.gitconfig。 */
    public String gitUserName()    { return gitUserName; }
    public String gitUserEmail()   { return gitUserEmail; }

    /**
     * 開分支的基準。空字串表示自動判斷（優先 develop，其次 origin 的預設分支）。
     * 會設成可覆寫是因為這是 repo 慣例而非通則 —— 不是每個專案都走 gitflow。
     */
    public String baseBranch()     { return baseBranch == null ? "" : baseBranch.trim(); }

    /**
     * 任務分支的前綴。預設 feature/ 是為了符合 gitflow 專案常見的
     * 「feature → develop → master」流程。
     */
    public String branchPrefix()   { return branchPrefix; }

    /**
     * 推分支與開 PR 用的 GitHub token（classic 需 repo scope，fine-grained 需
     * Contents:write 與 Pull requests:write）。
     *
     * 沒有它時管線一樣跑得完，只是停在核准閘門後推不出去 —— 這是刻意的：
     * 缺憑證不該讓整個服務起不來，但要在真的要推的時候給出明確訊息。
     */
    public String githubToken()    { return githubToken == null ? "" : githubToken.trim(); }

    public boolean hasGithubToken() { return !githubToken().isEmpty(); }

    /** 是否有任何角色指定用 codex。 */
    /**
     * 是否執行 Code Review 階段。
     *
     * 預設開啟。它只提供第二意見、不阻斷管線，但確實會增加每個任務的時間與額度，
     * 所以留一個關得掉的開關。
     */
    public boolean codeReviewEnabled() {
        return codeReview == null || !"off".equalsIgnoreCase(codeReview.trim());
    }

    public boolean usesCodex() { return usesRuntime("codex"); }

    /** 是否有任何角色指定用 opencode。 */
    public boolean usesOpencode() { return usesRuntime("opencode"); }

    /**
     * 是否有任何角色指定用這個 runtime。
     *
     * 用途只有一個：決定要不要在啟動時要求對應的 CLI 路徑。
     * 沒人用的 runtime 不該讓既有部署起不來 —— 多接一個 adapter 不等於多一項安裝需求。
     */
    private boolean usesRuntime(String runtimeId) {
        if (configuredTeam()) return teamCatalog.snapshot().definition().roles().values().stream()
                .anyMatch(r -> runtimeId.equals(r.runtime()));
        return java.util.stream.Stream.of("PM", "ARCHITECTURE", "BACKEND", "QA", "CODE_REVIEW")
                .anyMatch(r -> runtimeId.equals(roleRuntime(r)));
    }

    /** 觸發者白名單。只靠頻道權限不夠。 */
    public Set<String> allowedUsers() {
        if (allowedUsersRaw == null || allowedUsersRaw.isBlank()) return Set.of();
        return Arrays.stream(allowedUsersRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 啟動時就把設定問題爆出來，不要等到第一則訊息進來才發現。 */
    public void validate() {
        unitPrefix();
        require(slackAppToken, "SLACK_APP_TOKEN");
        require(slackBotToken, "SLACK_BOT_TOKEN");
        validateRoleTokens();
        require(claudeBin,     "CLAUDE_BIN");
        if (!configuredTeam()) require(repoPath, "AI_TEAM_REPO");
        if (allowedUsers().isEmpty()) {
            throw new IllegalStateException(
                    "AI_TEAM_ALLOWED_USERS 未設定。這個 bot 能修改程式碼與執行 shell，"
                  + "不允許在沒有觸發者白名單的情況下啟動。");
        }
        if (!claudeBin().toFile().canExecute()) {
            throw new IllegalStateException("CLAUDE_BIN 無法執行：" + claudeBin);
        }
        // 只有真的有角色指定 codex 時才要求 CODEX_BIN。
        // 沒設 runtime 的既有部署不該因為多了一個 adapter 就起不來。
        if (usesCodex()) {
            require(codexBin, "CODEX_BIN");
            if (!codexBin().toFile().canExecute()) {
                throw new IllegalStateException("CODEX_BIN 無法執行：" + codexBin);
            }
        }
        // 同理：沒有角色指定 opencode 時不要求 OPENCODE_BIN。
        if (usesOpencode()) {
            require(opencodeBin, "OPENCODE_BIN");
            if (!opencodeBin().toFile().canExecute()) {
                throw new IllegalStateException("OPENCODE_BIN 無法執行：" + opencodeBin);
            }
        }
        if (!repoPath().resolve(".git").toFile().isDirectory()) {
            throw new IllegalStateException("AI_TEAM_REPO 不是 git repository：" + repoPath);
        }
    }

    /** 不將 token 值放入例外；避免角色 socket 意外連到另一個 App。 */
    public void validateRoleTokens() {
        Set<String> appTokens = new java.util.HashSet<>();
        Set<String> botTokens = new java.util.HashSet<>();
        if (slackAppToken != null) appTokens.add(slackAppToken.trim());
        if (slackBotToken != null) botTokens.add(slackBotToken.trim());
        for (String role : identities().stream().filter(i -> !i.equals(com.zipe.aiteam.slack.SlackNotifier.Identity.SYSTEM)).map(com.zipe.aiteam.slack.SlackNotifier.Identity::name).toList()) {
            String app = roleAppToken(role);
            if (app.isEmpty()) continue;
            String bot = roleBotToken(role);
            if (bot.isEmpty()) throw new IllegalStateException(role + " 直接對話缺少 SLACK_BOT_TOKEN_" + role);
            if (!appTokens.add(app) || !botTokens.add(bot))
                throw new IllegalStateException(role + " 直接對話 token 與其他 App 重複；每個角色必須用自己的 token");
        }
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    name + " 未設定。請確認已 source ~/ai-team/env.sh 再啟動。");
        }
    }
}
