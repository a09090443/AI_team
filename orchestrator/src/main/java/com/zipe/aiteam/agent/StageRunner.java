package com.zipe.aiteam.agent;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.runtime.RuntimeRegistry;
import com.zipe.aiteam.sandbox.SandboxPolicy;
import com.zipe.aiteam.workspace.WorkspaceManager;
import com.zipe.aiteam.task.Task;
import com.zipe.aiteam.task.FailureReport;
import com.zipe.aiteam.task.Checkpoint;
import com.zipe.aiteam.store.TaskStore;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 通用的階段執行器，取代先前寫死在 orchestrator 裡的 runPm()。
 *
 * 角色定義與 schema 讀自 jar 之外的檔案，調整提示詞不需要重新編譯。
 */
@Component
public class StageRunner {

    /** 跨工具通用的 agent 導覽入口檔名（非 Claude Code 專屬）。 */
    private static final String AGENTS_FILE = "AGENTS.md";

    /** 導覽地圖的字數上限，超過就截斷。 */
    private static final int AGENTS_MAX_CHARS = 16_000;

    private final AiTeamProperties props;
    private final RuntimeRegistry runtimes;
    private final WorkspaceManager workspaces;
    private final TaskStore store;
    private final com.zipe.aiteam.slack.SlackNotifier slack;

    public StageRunner(AiTeamProperties props, RuntimeRegistry runtimes, WorkspaceManager workspaces) {
        this(props, runtimes, workspaces, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StageRunner(AiTeamProperties props, RuntimeRegistry runtimes, WorkspaceManager workspaces, TaskStore store,
                       com.zipe.aiteam.slack.SlackNotifier slack) {
        this.props = props;
        this.runtimes = runtimes;
        this.workspaces = workspaces;
        this.store = store;
        this.slack = slack;
    }

    /**
     * @param upstream 上游階段的產出，會併入 prompt 讓下游看得到前面的結論。
     *                 null 表示這是管線的第一個階段。
     * @param feedback 人類要求重做時的回饋；非 null 時會以 --resume 續接該階段的 session。
     */
    public AgentRuntime.RuntimeResult run(Task task, StageSpec stage,
                                          String upstream, String feedback,
                                          String resumeSessionId)
            throws AgentRuntime.RuntimeExecutionException {

        // 這個角色要用哪個引擎。找不到就直接失敗，不默默退回預設 ——
        // 設定值打錯而安靜退回，會讓人拿著一份不是該 runtime 跑出來的結果做比較。
        Checkpoint previous = task.continuation();
        boolean continuing = previous != null && previous.role().equals(stage.role());
        AgentRuntime runtime = runtimes.require(continuing ? previous.runtime() : props.roleRuntime(stage.role()));

        Path promptFile = props.homePath().resolve("prompts").resolve(stage.promptFileName());
        Path schemaFile = props.homePath().resolve("schemas").resolve(stage.schemaFileName());

        if (!Files.isReadable(promptFile)) {
            throw new AgentRuntime.RuntimeExecutionException(
                    stage.displayName() + " 的角色定義讀不到：" + promptFile);
        }
        String schema;
        try {
            schema = FailureReport.extendSchema(Files.readString(schemaFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AgentRuntime.RuntimeExecutionException(
                    stage.displayName() + " 的 schema 讀不到：" + schemaFile, e);
        }

        Path workDir = stage.needsWorkspace()
                ? workspaces.pathFor(task.id())   // 由 TaskOrchestrator 事先 provision
                : props.repoPath();

        if (stage.role().startsWith("direct_") && store != null) {
            var direct = store.direct(task.id()).orElseThrow(() -> new IllegalStateException("缺少角色請求紀錄"));
            if (direct.parentId() != null && workspaces.exists(direct.parentId())) {
                workDir = workspaces.pathFor(direct.parentId());
            }
        }

        // 這裡不自己建目錄。一個空目錄不是 git repository，agent 會在裡面做出
        // 無意義的結果；而沙箱掛載不存在的路徑會讓 unit 起不來且毫無輸出。
        // 兩種都是難查的靜默失敗，寧可在這裡明確報錯。
        if (stage.needsWorkspace() && !Files.isDirectory(workDir.resolve(".git"))) {
            throw new AgentRuntime.RuntimeExecutionException(
                    stage.displayName() + " 的工作區尚未建立或不是 git repository："
                  + workDir + "（應由 TaskOrchestrator 先呼叫 WorkspaceManager.provision）");
        }

        SandboxPolicy policy = buildPolicy(stage, workDir, runtime);

        Checkpoint checkpoint = new Checkpoint(task.id(), stage.role(), runtime.id(),
                continuing ? previous.model() : resolveModel(stage),
                continuing ? previous.sessionId() : null, upstream, task.qaBaseline(), null);
        if (store != null) store.saveCheckpoint(checkpoint);
        if (continuing && (previous.sessionId() == null || previous.sessionId().isBlank()) && slack != null) {
            slack.post(com.zipe.aiteam.slack.SlackNotifier.Identity.SYSTEM,
                    task.slackChannel(), task.slackThreadTs(),
                    "中斷時尚未取得 session ID。將以新 session 接手同一階段，"
                    + "沿用原工作區與已保存需求／上游脈絡，先核實進度。");
        }
        java.util.function.Consumer<String> sessionStarted = session -> {
            if (store != null) store.saveCheckpoint(checkpoint.withSession(session));
        };
        AgentRuntime.RuntimeRequest request = new AgentRuntime.RuntimeRequest(
                task.id(),
                stage.role(),
                workDir,
                promptFile,
                buildPrompt(task, stage, workDir, runtime, upstream, feedback) + FailureReport.INSTRUCTIONS,
                stage.allowedTools(),
                schema,
                continuing ? previous.sessionId() : (feedback != null ? resumeSessionId : null),
                stage.maxTurns(),
                resolveTimeout(stage),
                policy,
                checkpoint.model());
        try (var progress = new com.zipe.aiteam.slack.StageProgress(slack, store, task, stage.displayName(), props.progressIntervalSeconds())) {
            return runtime.execute(request, sessionStarted);
        } catch (AgentRuntime.RuntimeExecutionException e) {
            String detail = com.zipe.aiteam.runtime.Diagnostics.describe(e);
            // 只有明確的「session 不存在」才以新 session 接手；網路、額度、權限問題不算。
            if (!continuing || previous.sessionId() == null
                    || !(detail.contains("No conversation found with session ID")
                         || detail.contains("No session found with id")
                         || detail.contains("Session not found"))) throw e;
            Checkpoint fallback = checkpoint.withSession(null).withReason("原 session 不存在，以保存脈絡接手：" + detail);
            if (store != null) store.saveCheckpoint(fallback);
            if (slack != null) slack.post(com.zipe.aiteam.slack.SlackNotifier.Identity.SYSTEM,
                    task.slackChannel(), task.slackThreadTs(),
                    "原 session 無法恢復（明確回報不存在）。將以新 session 在原工作區接手同一階段，"
                    + "保留未提交修改，依已保存需求／上游報告先核實進度。\n" + detail);
            AgentRuntime.RuntimeRequest fresh = new AgentRuntime.RuntimeRequest(request.taskId(), request.role(),
                    request.workingDirectory(), request.systemPromptFile(), request.prompt(), request.allowedTools(),
                    request.jsonSchema(), null, request.maxTurns(), request.timeout(), request.sandboxPolicy(), request.model());
            try (var progress = new com.zipe.aiteam.slack.StageProgress(slack, store, task, stage.displayName() + "／新 session 接手", props.progressIntervalSeconds())) {
              return runtime.execute(fresh, session -> {
                if (store != null) store.saveCheckpoint(fallback.withSession(session));
              });
            }
        }
    }

    /**
     * 依角色的寫入範圍決定沙箱政策。
     *
     * TESTS_ONLY 的掃描失敗一律中止：與其在沒有保護的情況下讓 QA 動起來，
     * 不如讓這個階段明確失敗。
     */
    private SandboxPolicy buildPolicy(StageSpec stage, Path workDir, AgentRuntime runtime)
            throws AgentRuntime.RuntimeExecutionException {
        java.util.List<Path> caches = java.util.List.of(props.mavenRepo());
        // 可寫路徑與提權釘選由 runtime 自己宣告 —— 各家 CLI 的狀態目錄不同。
        SandboxPolicy.AgentState state = new SandboxPolicy.AgentState(
                runtime.statePaths(props.userHome()), runtime.escalationPins(props.userHome()));
        return switch (stage.writeScope()) {
            case NONE -> SandboxPolicy.readOnly(state);
            // 在工作區裡跑，但一個可寫路徑都不給 —— 唯讀是沙箱強制的，不是提示詞拜託的。
            case READ_ONLY_WORKSPACE -> SandboxPolicy.readOnly(state);
            case FULL -> SandboxPolicy.writable(state, workDir, caches);
            case TESTS_ONLY -> {
                try {
                    yield SandboxPolicy.qaReview(state, workDir, caches,
                            SandboxPolicy.scanProductionPaths(workDir));
                } catch (IOException e) {
                    throw new AgentRuntime.RuntimeExecutionException(
                            "無法掃描要保護的產品程式碼路徑，拒絕在沒有保護的情況下執行 "
                          + stage.displayName(), e);
                }
            }
        };
    }

    /**
     * 決定這個階段要用哪個模型。
     *
     * 順序：環境變數 AI_TEAM_MODEL_<角色> > StageSpec 的預設 > 空字串（交給 CLI）。
     * 讓環境變數優先，是因為調模型多半是為了當下的成本或品質取捨，
     * 不該每次都要改程式碼重編譯。
     */
    public String resolveModel(StageSpec stage) {
        String fromEnv = props.roleModel(stage.role());
        if (!fromEnv.isEmpty()) return fromEnv;
        return stage.model() == null ? "" : stage.model();
    }

    /** 全域設定有值就覆寫，否則用階段自己的逾時。 */
    private java.time.Duration resolveTimeout(StageSpec stage) {
        java.time.Duration override = props.taskTimeout();
        return override.isZero() ? java.time.Duration.ofSeconds(stage.timeoutSeconds()) : override;
    }

    /**
     * 讀取工作目錄根目錄的 AGENTS.md。
     *
     * CLAUDE.md 由 Claude Code 自動載入，AGENTS.md 不會 —— 實測 agent 對它的讀取次數是 0，
     * 靠提示詞要求它自己去讀也無效。裡面有「相依方向 iam 到 logon 單向」這類
     * 只寫在該檔的硬性規範，所以由 orchestrator 主動附進 prompt。
     *
     * 用的是跨工具的通用檔名，不是 Claude Code 專屬，換 runtime 時這段仍然成立。
     *
     * 讀不到就當作沒有：目標 repo 未必有這個檔案，缺它不該讓整個階段失敗。
     */
    String readAgentsMap(Path workDir) {
        Path file = workDir.resolve(AGENTS_FILE);
        if (!Files.isReadable(file)) return "";
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > AGENTS_MAX_CHARS) {
                // 它應該是地圖而不是手冊。真的長成手冊時寧可截斷，
                // 也不要讓它把需求本身和上游產出擠出注意力。
                return content.substring(0, AGENTS_MAX_CHARS)
                     + System.lineSeparator() + System.lineSeparator()
                     + "…（" + AGENTS_FILE + " 過長，已截斷）";
            }
            return content;
        } catch (IOException e) {
            return "";
        }
    }

    private String buildPrompt(Task task, StageSpec stage, Path workDir, AgentRuntime runtime,
                               String upstream, String feedback) {
        // 重做走 --resume，前一輪的 context（含導覽地圖）都還在，不必重複附上。
        if (feedback != null) {
            return """
                   人類審核者要求重做，回饋如下：

                   %s

                   請依照這個回饋重新處理，並再次輸出符合 schema 的結果。
                   """.formatted(feedback);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("任務編號：#").append(task.id()).append("\n\n");
        sb.append("原始需求（由 Slack 使用者 ").append(task.requesterUserId()).append(" 提出）：\n\n");
        sb.append(task.requestText()).append("\n");

        // 原生就會載入 AGENTS.md 的 runtime（codex）不必重複附，那只是浪費 token。
        String agentsMap = runtime.capabilities().contains(AgentRuntime.Capability.NATIVE_AGENTS_MAP)
                ? "" : readAgentsMap(workDir);
        if (!agentsMap.isEmpty()) {
            sb.append("\n---\n\n專案的 agent 導覽地圖（").append(AGENTS_FILE)
              .append("，位於工作目錄根目錄）：\n\n");
            sb.append(agentsMap).append("\n");
            sb.append("\n這份地圖指向的檔案（.claude/rules/、.claude/skills/ 等）本身不在上面，")
              .append("需要時請自行讀取。其中「不可違反的規範」一節請視為硬性條件。\n");
        }

        if (upstream != null && !upstream.isBlank()) {
            sb.append("\n---\n\n上游階段的產出：\n\n");
            sb.append(upstream).append("\n");
        }

        sb.append("\n---\n\n請依照你的角色職責處理，並輸出符合 schema 的結構化結果。");
        if (stage.writeScope() == StageSpec.WriteScope.NONE) {
            sb.append("不要修改任何檔案。");
        }
        return sb.toString();
    }
}
