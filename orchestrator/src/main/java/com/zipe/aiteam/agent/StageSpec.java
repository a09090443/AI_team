package com.zipe.aiteam.agent;

import com.zipe.aiteam.slack.SlackNotifier.Identity;
import com.zipe.aiteam.task.Task;

import java.util.List;

/**
 * 一個管線階段的完整定義。
 *
 * 把階段做成資料而非程式碼，是為了讓「加一個角色」變成新增一筆 StageSpec，
 * 而不是複製一份 runPm()。管線的自動串接邏輯只寫一次。
 */
public record StageSpec(
        String role,                 // pm / architecture / backend / qa
        String displayName,
        Identity identity,
        Task.State runningState,
        List<String> allowedTools,
        int maxTurns,
        String promptFileName,
        String schemaFileName,
        WriteScope writeScope,       // 決定工作目錄與沙箱政策
        int timeoutSeconds,          // 各階段的實際成本差很多，用同一個值不是太緊就是太鬆
        String model                 // 空字串＝不傳 --model，交給 CLI 決定
) {

    /**
     * 這個角色被允許寫到什麼程度。
     *
     * 用三態而不是布林，是因為 Backend 與 QA 都要寫，但寫的範圍完全不同：
     * QA 要能新增測試、跑建置產生 target/，卻不該碰產品程式碼。
     * 布林表達不了這個差別，只能靠提示詞自律 —— 那不是邊界。
     */
    public enum WriteScope {
        /** 唯讀角色。連工作區都不需要，直接以主 repo 為 cwd。 */
        NONE,
        /** 可改產品程式碼（Backend）。整個工作區可寫。 */
        FULL,
        /** 只能改測試與建置產物（QA）。src/main 與 pom.xml 在沙箱層釘回唯讀。 */
        TESTS_ONLY,
        /**
         * 在工作區內執行但完全不可寫（Code Reviewer）。
         *
         * 與 NONE 的差別是工作目錄：NONE 用主 repo，看不到這次任務的改動；
         * 這個要看的正是工作區裡的 commit，但一個字都不該改。
         */
        READ_ONLY_WORKSPACE
    }

    /** 需要獨立工作區的角色。 */
    public boolean needsWorkspace() { return writeScope != WriteScope.NONE; }

    public static StageSpec direct(Identity identity) {
        if (identity == Identity.SYSTEM) throw new IllegalArgumentException("主機器人不是對話角色");
        return new StageSpec("direct_" + identity.name().toLowerCase(java.util.Locale.ROOT),
                identity.name() + " 對話", identity, Task.State.DIRECT_RUNNING,
                List.of("Read", "Grep", "Glob"), 20, "direct.md", "direct-schema.json",
                WriteScope.NONE, 900, "");
    }

    /** 唯讀角色：需求分類與任務拆解。 */
    public static final StageSpec PM = new StageSpec(
            "pm", "PM Agent", Identity.PM,
            Task.State.PM_RUNNING,
            List.of("Read", "Grep", "Glob"),
            15,
            "pm.md", "task-schema.json",
            WriteScope.NONE, 900, "");

    public static final StageSpec PM_TRIAGE = new StageSpec(
            "pm_triage", "PM 問題處理", Identity.PM, Task.State.PM_TRIAGE_RUNNING,
            List.of("Read", "Grep", "Glob"), 20, "pm_triage.md", "triage-schema.json",
            WriteScope.NONE, 900, "");

    /** 唯讀角色：分析既有架構並產出 ADR 內容，落檔由 Orchestrator 負責。 */
    public static final StageSpec ARCHITECTURE = new StageSpec(
            "architecture", "Architecture Agent", Identity.ARCHITECTURE,
            Task.State.ARCH_RUNNING,
            List.of("Read", "Grep", "Glob", "Bash(git log *)", "Bash(git diff *)", "Bash(git show *)"),
            25,
            "architecture.md", "adr-schema.json",
            WriteScope.NONE, 900, "");

    /** 實作前制定情境測試計畫，與驗收共用 QA 身分與模型，但獨立保存產出。 */
    public static final StageSpec QA_PLAN = new StageSpec(
            "qa_plan", "QA 測試規劃", Identity.QA, Task.State.QA_PLAN_RUNNING,
            List.of("Read", "Grep", "Glob"), 25,
            "qa_plan.md", "qa-plan-schema.json", WriteScope.NONE, 900, "");

    /** 會修改產品程式碼的角色。整個隔離工作區可寫。 */
    public static final StageSpec BACKEND = new StageSpec(
            "backend", "Backend Agent", Identity.BACKEND,
            Task.State.IMPL_RUNNING,
            // 唯讀的 shell 工具要給足。實測 #1003 因為 ls 不在清單裡被擋，
            // 回合被浪費在被拒絕的呼叫上，最後用盡上限而中止（見 docs-site/docs/backend-report.md）。
            // 收緊 runtime 權限不會增加安全性 —— 沙箱才是真正的邊界。
            List.of("Read", "Write", "Edit", "Grep", "Glob",
                    "Bash(ls *)", "Bash(cat *)", "Bash(find *)", "Bash(head *)",
                    "Bash(tail *)", "Bash(wc *)", "Bash(diff *)",
                    "Bash(mvn *)", "Bash(git *)"),
            80,
            "backend.md", "impl-schema.json",
            // 暖快取下全 reactor 的 mvn verify 要 118 秒，Backend 一輪可能跑 2-3 次；
            // 900 秒會在還沒寫完程式前就逾時（實測見 docs-site/docs/backend-report.md）。
            WriteScope.FULL, 1800, "");

    /**
     * 驗收角色。在 Backend 的工作區內執行，可以補測試，但碰不到產品程式碼。
     *
     * 給 Write／Edit 是因為決策 2：QA 要補上 Backend 漏掉的回歸情境。
     * 真正的邊界不在這份工具清單，而在 WriteScope.TESTS_ONLY 對應的沙箱政策 ——
     * src/main 與 pom.xml 會被釘成唯讀，說服不了也寫不進去。
     */
    public static final StageSpec QA = new StageSpec(
            "qa", "QA Agent", Identity.QA,
            Task.State.QA_RUNNING,
            List.of("Read", "Write", "Edit", "Grep", "Glob",
                    "Bash(ls *)", "Bash(cat *)", "Bash(find *)", "Bash(head *)",
                    "Bash(tail *)", "Bash(wc *)", "Bash(diff *)",
                    "Bash(mvn *)", "Bash(git *)"),
            60,
            "qa.md", "qa-schema.json",
            WriteScope.TESTS_ONLY, 1800, "");   // 在 Backend 的工作區中執行，但碰不到產品程式碼

    /**
     * Code Reviewer。在 QA 之後、PR 閘門之前，審的是「即將開 PR 的最終狀態」。
     *
     * 唯讀而且是外部強制的：WriteScope.READ_ONLY_WORKSPACE 讓它在工作區裡執行
     * （看得到這次任務的所有 commit），但沙箱不給任何可寫路徑。review 就是 review，
     * 不該順手改 —— 改了的話 QA 剛驗過的狀態就不是最終狀態了。
     *
     * 建議跑在 codex 上（env.sh 設 AI_TEAM_RUNTIME_CODE_REVIEW=codex）。用另一個模型家族看
     * 同一份程式碼，才有第二意見的意義；同一個引擎自己審自己，盲點也是同一套。
     *
     * 注意 codex 沒有 --max-turns，maxTurns 對它無效，逾時是唯一的上限。
     * 這裡填的值是為了日後若改用 claude 仍然合理。
     */
    public static final StageSpec CODE_REVIEW = new StageSpec(
            "code_review", "Code Reviewer", Identity.CODE_REVIEW,
            Task.State.REVIEW_RUNNING,
            List.of("Read", "Grep", "Glob",
                    "Bash(ls *)", "Bash(cat *)", "Bash(find *)", "Bash(head *)",
                    "Bash(tail *)", "Bash(wc *)", "Bash(diff *)",
                    "Bash(git log *)", "Bash(git diff *)", "Bash(git show *)",
                    "Bash(git status *)"),
            40,
            "code_review.md", "review-schema.json",
            WriteScope.READ_ONLY_WORKSPACE, 1200, "");

    @Override
    public String toString() {
        return role;
    }
}
