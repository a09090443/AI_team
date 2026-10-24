package com.zipe.aiteam.flow;

import java.util.List;

/**
 * 實作節點每一輪動手前，系統先把最新的基準分支合併進任務分支（2026-10-24）。
 *
 * 工作區只在第一個工作區節點建立時取一次基準；之後等計畫核准、返工、續做可能隔了好幾個小時，
 * 期間別人合併的修改都看不到，實作角色在舊程式上改，到開 PR 才發現衝突或重做。
 * 沙箱裡的 agent 連不到遠端、也不該自己 pull，所以由系統在派工前 fetch 並以一般 merge 併入
 * （不 rebase、不改寫既有 commit，已推過的分支之後推送仍是 fast-forward）。
 *
 * 沒有衝突時系統直接提交合併 commit，本輪的稽核起點就是合併後的 commit；
 * 有衝突時保留合併中的狀態，由本輪的實作角色先解衝突再做原本的工作，系統事後以
 * {@link com.zipe.aiteam.workspace.WorkspaceManager#mergeProblem} 核對，與已開 PR 的衝突同步（{@link PullRequestSync}）共用同一套檢查。
 */
public final class BaseSync {
    private BaseSync() {}

    /**
     * 待解衝突的一次同步。
     *
     * @param node       負責解衝突的節點（同步當下開始的那一輪）
     * @param parentHead 合併前任務分支的 commit
     * @param baseSha    併入的基準最新 commit
     */
    public record Round(String node, String baseBranch, String baseSha, String parentHead, List<String> conflicts, String startedAt) {}

    /** 這個節點這一輪是否負責完成合併 commit（系統要核對合併結果、以自動合併結果為稽核起點）。 */
    static boolean resolving(FlowRun flow, String nodeId) {
        return flow.baseSync != null && nodeId.equals(flow.baseSync.node());
    }

    /** 沒有衝突、系統已完成合併時給角色的事實。 */
    static String merged(String baseBranch, String baseSha, String parentHead) {
        return "【系統：動手前已同步最新基準】已把 " + baseBranch + " 的最新 commit " + baseSha
                + " 合併進本工作區（原 commit " + parentHead + "，git 沒有回報衝突，系統已提交合併 commit）。"
                + "請以目前的程式為準；基準帶進來的修改與本任務有關時一併考慮。";
    }

    /** 無法同步時給角色的事實；不擋派工，但要求角色不要把工作區當成最新版本。 */
    static String skipped(String reason) {
        return "【系統：這一輪沒有同步最新基準】" + reason
                + "。工作區可能落後遠端；涉及他人近期修改的判斷請標明「無法確認最新狀態」。";
    }

    /** 交給實作角色的固定事實與要求；只列系統查得的資料。 */
    static String instruction(Round round) {
        return "【系統：動手前同步最新基準，有衝突】\n"
                + "系統已在本工作區執行 git merge --no-ff --no-commit " + round.baseSha() + "（" + round.baseBranch() + " 的最新 commit），"
                + "合併前的 commit 是 " + round.parentHead() + "。\n"
                + "有衝突的檔案（系統以 git diff --diff-filter=U 查得，只有這些）：" + String.join("、", round.conflicts()) + "\n"
                + "要求：先解衝突再做本輪的工作。只修改衝突段落，兩側有效的修改都保留；"
                + "能寫 .git 的角色解完後 git add 這些檔案並執行 git commit --no-edit 完成合併 commit，之後的修改另外 commit；"
                + "沙箱不能寫 .git 的角色改好檔案即可，系統核對後代為提交。\n"
                + "禁止：rebase、git merge --abort、重新合併、push。\n"
                + "系統會核對：HEAD 是以上兩個 commit 的合併 commit（或其後續 commit）、衝突檔沒有殘留衝突標記。";
    }
}
