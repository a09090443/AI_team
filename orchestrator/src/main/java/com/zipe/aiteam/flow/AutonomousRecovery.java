package com.zipe.aiteam.flow;

import com.zipe.aiteam.flow.TeamDefinition.Autonomy;
import com.zipe.aiteam.flow.TeamDefinition.Node;

/**
 * 管理者以 project.autonomy 授權的自主恢復（2026-10-22，#1053）。
 *
 * #1053 一天內停了四次，每次都只是系統自己能處理的事：實作模型跑到 30 分鐘被砍、驗證總時限用完、
 * 分流給了錯的按鈕、指令打在頻道頂層被開成新任務。這裡只決定「能不能自動再試」與次數記帳，
 * 不擴權、不代替核准；狀態放在 systemRetries，不新增 FlowRun 欄位。
 */
public final class AutonomousRecovery {
    private AutonomousRecovery() {}

    static final String TIMEOUT_KEY = "autonomy-timeout:";
    static final String TIMEOUT_TOTAL_KEY = "autonomy-timeout-total";
    static final String EXTENDED_KEY = "autonomy-timeout-extended:";
    static final String VERIFY_KEY = "autonomy-verify:";

    public static Autonomy of(FlowRun flow) {
        return flow == null || flow.snapshot == null ? null : flow.snapshot.definition().project().autonomy();
    }

    /** 節點這一輪的時限：自動接續過的節點改用 autonomy.maxTimeoutSeconds（不會比原值短）。 */
    public static int timeoutSeconds(FlowRun flow, String nodeId, Node node) {
        Autonomy autonomy = of(flow);
        if (autonomy == null || !flow.systemRetries.containsKey(EXTENDED_KEY + nodeId)) return node.timeoutSeconds();
        return Math.max(node.timeoutSeconds(), autonomy.maxTimeoutSeconds());
    }

    /**
     * 模型逾時、沒有長作業可對帳時，是否自動接續原 session 一次。
     * 每任務最多 modelTimeoutRecoveries 次；同一節點在同一工作區狀態（HEAD＋未提交指紋）只接續一次，
     * 兩輪之間沒有任何進展就停給人，避免同一件事一直被砍。
     *
     * @return 可以接續時回傳本次是第幾次；不允許回傳 0（不改任何記帳）
     */
    public static int claimTimeoutRecovery(FlowRun flow, String nodeId, String workspaceFingerprint) {
        Autonomy autonomy = of(flow);
        if (autonomy == null || autonomy.modelTimeoutRecoveries() <= 0) return 0;
        int used = flow.systemRetries.getOrDefault(TIMEOUT_TOTAL_KEY, 0);
        String key = TIMEOUT_KEY + nodeId + ":" + workspaceFingerprint;
        if (used >= autonomy.modelTimeoutRecoveries() || flow.systemRetries.containsKey(key)) return 0;
        flow.systemRetries.put(key, 1);
        flow.systemRetries.put(TIMEOUT_TOTAL_KEY, used + 1);
        flow.systemRetries.put(EXTENDED_KEY + nodeId, 1);
        return used + 1;
    }

    static String timeoutContext(int elapsedMinutes, int nextMinutes, String workspace) {
        return "系統自動接續（管理者授權的自主恢復）：上一輪跑了約 " + elapsedMinutes + " 分鐘、碰到時限被中止，"
                + "工作沒有遺失，這次沿用同一個對話接著做，時限 " + nextMinutes + " 分鐘。"
                + "\n先確認工作區現況，已完成且通過的步驟不要重做；只跑完成需求必要、而且與本次變更有關的檢查，"
                + "跑長指令前先判斷是否真的需要。"
                + (workspace == null || workspace.isBlank() ? "" : "\n目前工作區（系統查得）：" + workspace);
    }

    /**
     * 驗證用完總時限時，是否沿用已通過的指令再跑一輪。每輪至少要多通過一條指令，否則停給人；
     * 同一個 commit 最多續 verificationContinuations 輪。
     */
    public static int claimVerificationContinuation(FlowRun flow, String head, int passedBefore, int passedAfter) {
        Autonomy autonomy = of(flow);
        return claimVerificationContinuation(autonomy, flow.systemRetries, VERIFY_KEY + head, passedBefore, passedAfter);
    }

    /** 這個 commit 已用掉的續驗輪數。 */
    public static int verificationRounds(FlowRun flow, String head) {
        return head == null ? 0 : flow.systemRetries.getOrDefault(VERIFY_KEY + head, 0);
    }

    static int claimVerificationContinuation(Autonomy autonomy, java.util.Map<String, Integer> counters, String key,
                                             int passedBefore, int passedAfter) {
        if (autonomy == null || autonomy.verificationContinuations() <= 0 || passedAfter <= passedBefore) return 0;
        int used = counters.getOrDefault(key, 0);
        if (used >= autonomy.verificationContinuations()) return 0;
        counters.put(key, used + 1);
        return used + 1;
    }

    /** 讓 agent 自己安排優先順序：知道時限，才不會把時間花在本次不需要的長檢查上（#1053 實作補跑後端測試）。 */
    static String timeBudgetNotice(FlowRun flow, int seconds) {
        // 屬於逾時接續的配套；只開其他 autonomy 項目（例如只開 deferUnverifiable）的團隊不加這段提示。
        Autonomy autonomy = of(flow);
        if (autonomy == null || autonomy.modelTimeoutRecoveries() <= 0) return "";
        return "\n本階段時限：" + Math.max(1, seconds / 60) + " 分鐘（含所有安裝、建置與測試指令）。"
                + "優先完成需求與必要檢查；與本次變更無關的長時間檢查不要執行，在 summary 說明未執行的原因。";
    }

    public static boolean requirementChangeOptions(FlowRun flow) {
        Autonomy autonomy = of(flow);
        return autonomy != null && autonomy.requirementChangeOptions();
    }

    public static boolean channelCommandRouting(TeamDefinition definition) {
        return definition != null && definition.project().autonomy() != null
                && definition.project().autonomy().channelCommandRouting();
    }
}
