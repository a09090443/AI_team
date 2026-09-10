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
    public Map<String,String> recoveryGrants = new LinkedHashMap<>();
    public Set<String> recoveryRevisions = new LinkedHashSet<>();
    public String approvalHead;
    public String origin;
    public String branch;
    public String beforeHead;
    public String activeNode;
    public boolean dialogue;
    public String dialogueRole;
    public String parentId;
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
    public Map<String,JsonNode> receipts = new LinkedHashMap<>();
    public Map<String,String> heads = new LinkedHashMap<>();
    public Map<String,OperationPreflight.Evidence> executionContexts = new LinkedHashMap<>();
    public Map<String,PlanApproval> planApprovals = new LinkedHashMap<>();
    public List<TeamDefinition.Snapshot> migrationHistory = new ArrayList<>();
    public FlowRun() {}
    public FlowRun(TeamDefinition.Snapshot snapshot) { this.snapshot = snapshot; this.node = snapshot.definition().entry(); }
}
