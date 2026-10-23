package com.zipe.aiteam.flow;

import com.zipe.aiteam.publish.PullRequestPublisher.PullRequestState;

import java.util.List;
import java.util.Objects;

import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 已開 PR 與基準衝突時，回原任務解衝突並推回同一個分支（#1056）。
 *
 * #1056 的使用者先合併了任務 1044 的 PR，任務 1055 已開的 PR 就跟 dev 衝突。流程只能另開任務、另開新 PR，
 * 原 PR 永遠解不了，最後只能交給真人。PR 是系統自己開的、分支與工作區都還在，由原任務接著處理最直接：
 * 系統以一般 merge 把最新基準併進來，衝突交給實作角色解，沿用原任務的需求、計畫與核准，
 * 重跑驗收與審查後推回同一個分支（fast-forward，不 force push）。
 *
 * 這裡集中判斷與核對；狀態轉換、派工與發布在 {@link FlowEngine}。
 * 開關讀管理者目前的配置（project.autonomy.pullRequestSyncRounds／pullRequestSyncAutoPush），
 * 因為舊任務快照沒有這兩個欄位；開始同步時把當下的授權記在 {@link Round}，之後不再看配置。
 */
public final class PullRequestSync {
    private PullRequestSync() {}

    /** 每輪固定的換手額度：實作、驗收、審查、核准，另留一次分流加返工來回。整輪上限已用完的舊任務也能完成一輪。 */
    static final int TRANSITION_BUDGET = 8;

    /**
     * 一輪同步。
     *
     * @param parentHead          系統上次推上 PR 的 commit；推回前要求遠端仍是它
     * @param transitionsAtStart  開始時的整輪換手數，額度以此起算，不重設歷史計數
     * @param autoPush            開始時配置是否允許全部通過即推回
     */
    public record Round(int round, int prNumber, String baseBranch, String baseSha, String parentHead,
                        List<String> conflicts, String trigger, boolean autoPush, int transitionsAtStart, String startedAt) {}

    public enum Action {
        /** 沒有要做的事（PR 可合併，或 GitHub 還在計算）。 */
        NONE,
        /** PR 已合併或關閉，之後不再查。 */
        CLOSED,
        /** 衝突且條件都符合，開始同步。 */
        SYNC,
        /** 衝突，但 PR 的分支、head 或基準與系統紀錄不同（例如有人手動推過）；說明一次後不自動處理。 */
        MISMATCH,
        /** 衝突，但本 PR 的同步輪數已用完。 */
        EXHAUSTED,
        /** 上一輪推回後，GitHub 確認已可合併。 */
        CONFIRMED
    }

    public static int maxRounds(TeamDefinition definition) {
        var autonomy = definition == null ? null : definition.project().autonomy();
        return autonomy == null ? 0 : autonomy.pullRequestSyncRounds();
    }

    public static boolean autoPush(TeamDefinition definition) {
        var autonomy = definition == null ? null : definition.project().autonomy();
        return autonomy != null && autonomy.pullRequestSyncAutoPush();
    }

    /**
     * 解衝突的節點：從 entry 沿 next 走主線遇到的第一個 gate=IMPLEMENTATION 節點；沒有時為 null（配置驗證會擋下）。
     * 實作分成多段的配置（例如 node-web 的 implementation → frontend）由第一段解衝突，後段照主線接著跑。
     */
    static String implementationNode(TeamDefinition definition) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id = definition.entry(); id != null && seen.add(id); ) {
            Node node = definition.nodes().get(id);
            if (node == null) return null;
            if (node.kind() == Kind.AGENT && node.gate() == Gate.IMPLEMENTATION) return id;
            id = node.next();
        }
        return null;
    }

    /** 這個節點這一輪是否負責完成合併 commit（系統要核對合併結果、以自動合併結果為稽核起點）。 */
    static boolean resolving(FlowRun flow, String nodeId) {
        return flow.pullRequestSync != null && nodeId.equals(implementationNode(flow.snapshot.definition()));
    }

    /** 同步輪的換手額度還沒用完；用完後照一般上限停下等人，不另外延長。 */
    static boolean forwardAllowed(FlowRun flow) {
        Round round = flow.pullRequestSync;
        return round != null && flow.transitions < round.transitionsAtStart() + TRANSITION_BUDGET;
    }

    /** 排程與手動指令共用的判斷；不改任何狀態。 */
    public static Action assess(FlowRun flow, PullRequestState pr, int maxRounds) {
        if (!pr.open()) return Action.CLOSED;
        if (pr.mergeable() == null) return Action.NONE;
        if (!pr.conflicting())
            return flow.pullRequestNotice != null && flow.pullRequestNotice.equals(pushedNotice(flow.publishedHead))
                    ? Action.CONFIRMED : Action.NONE;
        if (!Objects.equals(pr.headRef(), flow.branch) || !Objects.equals(pr.headSha(), flow.publishedHead)
                || !Objects.equals(pr.baseRef(), flow.snapshot.definition().project().baseBranch()))
            return Action.MISMATCH;
        if (flow.pullRequestSyncRounds >= maxRounds) return Action.EXHAUSTED;
        return Action.SYNC;
    }

    /** 推回後等 GitHub 確認的標記；確認後改成 {@link #confirmedNotice}，同一件事只說一次。 */
    static String pushedNotice(String head) { return "pushed:" + head; }
    static String confirmedNotice(String head) { return "confirmed:" + head; }
    static String mismatchNotice(PullRequestState pr) { return "mismatch:" + pr.headRef() + "@" + pr.headSha() + "->" + pr.baseRef(); }
    static String exhaustedNotice(String head) { return "exhausted:" + head; }

    /** 交給實作角色的固定事實與要求；只列系統查得的資料，不讓角色自己猜是哪個 PR、哪些檔案衝突。 */
    static String instruction(Round round, String prUrl) {
        return "【系統：已開 PR 與基準衝突，第 " + round.round() + " 輪同步】\n"
                + "PR：" + prUrl + "（#" + round.prNumber() + "），來源分支就是本工作區目前的分支，目標分支 " + round.baseBranch() + "。\n"
                + "系統已在本工作區執行 git merge --no-ff --no-commit " + round.baseSha() + "（" + round.baseBranch() + " 的最新 commit），"
                + "原 PR 的 commit 是 " + round.parentHead() + "。\n"
                + (round.conflicts().isEmpty()
                    ? "git 沒有回報文字衝突，系統已完成合併 commit；請確認合併後的內容沒有語意衝突，沒有需要修改就直接回報完成。\n"
                    : "有衝突的檔案（系統以 git diff --diff-filter=U 查得，只有這些）：" + String.join("、", round.conflicts()) + "\n"
                        + "要求：只修改衝突段落，兩側有效的修改都保留，重複的內容合併成一份。"
                        + "能寫 .git 的角色解完後 git add 這些檔案並執行 git commit --no-edit 完成合併 commit；"
                        + "沙箱不能寫 .git 的角色只要改好檔案，系統核對後代為提交合併 commit。\n")
                + "禁止：rebase、git merge --abort、重新合併、修改衝突檔以外的檔案、push。推回 PR 由系統在驗收與審查通過後執行。\n"
                + "系統會核對：HEAD 是以上兩個 commit 的合併 commit、衝突檔沒有殘留衝突標記、衝突檔以外的內容與 git 自動合併結果相同。\n"
                + "需求、測試計畫與核准沿用原任務，不需要重新整理；驗收會在合併後的 commit 上重跑。";
    }
}
