package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 每次狀態變更整份保存；快照不隨磁碟上配置更動。 */
public class FlowRun {
    public TeamDefinition.Snapshot snapshot;
    public String node;
    public int transitions;
    public boolean workspaceReady;
    public String workspacePath;
    public String context = "";
    public String lastBlockReason;
    /** 目前受阻鏈的證據摘要；只允許配置內既有節點使用有界自動交接。 */
    public String automaticHandoff;
    /** 任務生命週期內已使用的自動交接額度，不因新錯誤文字或 commit 重設。 */
    public int automaticHandoffs;
    /** 真人針對當前受阻證據核准的額外事件額度；不重設既有事件或節點計數。 */
    public int automaticHandoffExtraBudget;
    /** 同一受阻事件已通過的節點數；另設上限，避免同一證據在節點間無限循環。 */
    public int automaticHandoffHops;
    public Set<String> automaticHandoffKeys = new LinkedHashSet<>();
    /** 已使用的額度延長授權 token，永久保留以防相同現場重放。 */
    public Set<String> automaticHandoffExtensionKeys = new LinkedHashSet<>();
    public Map<String,String> recoveryGrants = new LinkedHashMap<>();
    public Set<String> recoveryRevisions = new LinkedHashSet<>();
    /** 人工修復後的重驗授權綁定節點與實際 commit；已使用 key 永久保留以防重放。 */
    public Map<String,String> remediationRecoveryGrants = new LinkedHashMap<>();
    public Set<String> remediationRecoveryKeys = new LinkedHashSet<>();
    public String approvalHead;
    public String origin;
    public String branch;
    public String beforeHead;
    public String activeNode;
    public boolean dialogue;
    public String dialogueRole;
    public String parentId;
    /** 用完自動處理次數後，由負責人承接成的新任務編號；有值時本任務不再接受任何操作。 */
    public String successor;
    /** 本任務承接自哪個任務；工作區從該任務的分支 HEAD 開始。 */
    public String continuationOf;
    /** 純詢問結束後，以「開成新需求」按鈕從 out_of_scope 開立的任務：項目序號（從 1 起）→ 新任務編號。防止重複開立。 */
    public Map<Integer,String> spinoffs = new LinkedHashMap<>();
    /** 本任務由哪個任務的純詢問發現開立；不沿用該任務的任何核准或工作區。 */
    public String spinoffOf;
    /**
     * 受保護路徑改派（2026-10-04 前的舊行為）：節點 → 實際執行的角色，整個節點交給改派角色。
     * 只保留給已經這樣決定的舊任務沿用；新決定改走 {@link #delegatedParts} 的分段改派。
     */
    public Map<String,String> roleOverrides = new LinkedHashMap<>();
    /**
     * 受保護路徑分段改派：節點 → 本輪改派段的結果。改派角色只改允許的受保護路徑，系統核對後代為提交，
     * 其餘工作接著由原角色完成。startHead 等於本輪起始 commit 時代表同一輪已完成，續跑時略過改派段。
     */
    public Map<String,DelegatedPart> delegatedParts = new LinkedHashMap<>();
    public record DelegatedPart(String role, String startHead, String head, List<String> files, String summary) {}
    public String acceptanceOwner;
    public String baselineRevision;
    public String changeTarget;
    public String pendingPlanRevision;
    public boolean changeAuthorized;
    public String prUrl;
    public String publishedHead;
    public IndependentVerifier.Receipt independentEvidence;
    public List<JsonNode> baselineHistory = new ArrayList<>();
    public Map<String,Integer> visits = new LinkedHashMap<>();
    /**
     * 各節點上次啟動時的輸入指紋，以及「輸入完全相同的連續重跑」次數。
     * 防循環要擋的是原地打轉，不是正常往前推進：輸入變了（新 commit、新的前置結果、
     * 新的受阻證據）就是有進展，不該佔用重試次數。
     */
    public Map<String,String> nodeFingerprints = new LinkedHashMap<>();
    public Map<String,Integer> repeats = new LinkedHashMap<>();
    /** 系統自身失敗（啟動不了、暫時性錯誤）的自動重試次數；不佔用節點重試次數。 */
    public Map<String,Integer> systemRetries = new LinkedHashMap<>();
    /**
     * 真人補充的次數。納入節點輸入指紋，讓「人給了新資訊」一定算成新的輸入；
     * agent 自己每輪追加的受阻紀錄則不算，否則防循環會失效。
     */
    public int supplements;
    public Map<String,JsonNode> receipts = new LinkedHashMap<>();
    /**
     * 尚未解除的受阻發現，以節點 ID 為鍵（因此筆數受配置節點數限制，不會無限成長）。
     * 受阻報告不會寫入 receipts，而 context 有長度上限會淘汰舊段落；
     * 這份紀錄是唯一保證送達下游節點的開放缺陷來源，該節點自己通過後才移除。
     */
    public Map<String,JsonNode> blockedFindings = new LinkedHashMap<>();
    public Map<String,String> heads = new LinkedHashMap<>();
    /** 各 workspace 節點最近一次真正開始執行時的 HEAD，供人工修復重驗比對。 */
    public Map<String,String> attemptHeads = new LinkedHashMap<>();
    public Map<String,OperationPreflight.Evidence> executionContexts = new LinkedHashMap<>();
    public Map<String,PlanApproval> planApprovals = new LinkedHashMap<>();
    public List<TeamDefinition.Snapshot> migrationHistory = new ArrayList<>();
    public FlowRun() {}
    public FlowRun(TeamDefinition.Snapshot snapshot) { this.snapshot = snapshot; this.node = snapshot.definition().entry(); }
}
