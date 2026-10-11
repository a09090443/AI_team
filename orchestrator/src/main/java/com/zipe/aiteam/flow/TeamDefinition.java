package com.zipe.aiteam.flow;

import java.util.List;
import java.util.Map;

/** 管理者配置，不接受 agent 修改。角色 ID 不帶任何流程語意。 */
public record TeamDefinition(int version, String id, Project project, Map<String, Role> roles,
                             String entry, int maxTransitions, Map<String, Node> nodes) {
    public record Project(String name, String repositoryEnv, String baseBranch,
                          String buildInstructions, String testInstructions, List<String> protectedPaths, List<String> cachePaths, List<String> requiredTools,
                          Verification verification, Map<String, Operation> operations, boolean requirePlanApproval,
                          /**
                           * 計畫通過系統的機械檢查（覆蓋全部驗收項目、每條情境有反例、操作可執行）時自動核准，
                           * 不再等真人按。發布仍必須由真人核准，需求變更也仍要真人同意。
                           */
                          boolean autoApprovePlanWhenValid, ServiceTarget serviceTarget) {
        public Project(String name, String repositoryEnv, String baseBranch, String buildInstructions,
                       String testInstructions, List<String> protectedPaths, List<String> cachePaths,
                       List<String> requiredTools, Verification verification, Map<String, Operation> operations,
                       boolean requirePlanApproval, boolean autoApprovePlanWhenValid) {
            this(name, repositoryEnv, baseBranch, buildInstructions, testInstructions, protectedPaths,
                    cachePaths, requiredTools, verification, operations, requirePlanApproval, autoApprovePlanWhenValid, null);
        }
        public Project(String name, String repositoryEnv, String baseBranch, String buildInstructions,
                       String testInstructions, List<String> protectedPaths, List<String> cachePaths,
                       List<String> requiredTools, Verification verification) {
            this(name, repositoryEnv, baseBranch, buildInstructions, testInstructions, protectedPaths,
                    cachePaths, requiredTools, verification, null, false, false);
        }
        public Project(String name, String repositoryEnv, String baseBranch, String buildInstructions,
                       String testInstructions, List<String> protectedPaths, List<String> cachePaths,
                       List<String> requiredTools, Verification verification,
                       Map<String, Operation> operations, boolean requirePlanApproval) {
            this(name, repositoryEnv, baseBranch, buildInstructions, testInstructions, protectedPaths,
                    cachePaths, requiredTools, verification, operations, requirePlanApproval, false);
        }
    }
    /** 管理者宣告各節點的必要操作；不接受模型新增權限。writes 相對於 repository 根目錄。 */
    public record Operation(List<String> nodes, String directory, List<String> argv, List<String> writes, String scriptSha256) {
        public Operation(List<String> nodes, String directory, List<String> argv, List<String> writes) {
            this(nodes, directory, argv, writes, null);
        }
    }
    /** 管理者核准的部署目標與檢查操作；模型不能用報告自行指定其他主機。 */
    public record ServiceTarget(String name, Map<String, String> checks) {}
    /** 管理者固定的 argv；不能由模型的 command 欄位提供。 */
    /**
     * @param docOnlyPaths 選填。系統查得的變更檔案全部符合這些路徑（且不在 {@link DocOnlyChanges} 的強制全跑名單）時，
     *                     略過建置測試指令；未設定＝一律全跑。
     */
    public record Verification(List<List<String>> commands, int timeoutSeconds, List<String> docOnlyPaths) {
        public Verification(List<List<String>> commands, int timeoutSeconds) { this(commands, timeoutSeconds, null); }
    }
    public record Role(String name, String runtime, String model, String prompt,
                       String botTokenEnv, String appTokenEnv, List<String> tools, String reasoningEffort, String capabilities) {
        public Role(String name, String runtime, String model, String prompt,
                    String botTokenEnv, String appTokenEnv, List<String> tools, String reasoningEffort) {
            this(name, runtime, model, prompt, botTokenEnv, appTokenEnv, tools, reasoningEffort, null);
        }
        public Role(String name, String runtime, String model, String prompt,
                    String botTokenEnv, String appTokenEnv, List<String> tools) {
            this(name, runtime, model, prompt, botTokenEnv, appTokenEnv, tools, null);
        }
        public String executionLabel() {
            return name + "／" + runtime + "／模型：" + (model == null || model.isBlank() ? "CLI 預設" : model)
                    + (reasoningEffort == null || reasoningEffort.isBlank() ? "" : "／推理：" + reasoningEffort)
                    + "（要求值）";
        }
    }
    public enum Scope { READ_ONLY, WORKSPACE, LIMITED }
    public enum Gate { REPORT, REQUIREMENTS, IMPLEMENTATION, TEST_PLAN, VERIFICATION, REVIEW, SERVICE }
    public enum Kind { AGENT, APPROVAL, END }
    /**
     * 實作節點選填：需求要改「原角色 runtime 必定寫不了」的路徑（例如 Claude Code 的 `.claude/`）時，
     * 若全部落在 paths 內，就改派給 role 實作；系統事後核對實際變更。見 {@link ProtectedPathFallback}。
     */
    public record Fallback(String role, List<String> paths) {}
    public record Node(Kind kind, String role, String schema, Scope scope, Gate gate,
                       boolean workspace, List<String> writablePaths, String plan,
                       String next, String blocked, Map<String, String> routes,
                       List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions,
                       boolean localTestNetwork, Fallback protectedPathFallback, List<String> candidateRoles, List<String> skills) {
        public Node(Kind kind, String role, String schema, Scope scope, Gate gate, boolean workspace,
                    List<String> writablePaths, String plan, String next, String blocked, Map<String,String> routes,
                    List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions,
                    boolean localTestNetwork, Fallback protectedPathFallback, List<String> candidateRoles) {
            this(kind, role, schema, scope, gate, workspace, writablePaths, plan, next, blocked, routes,
                    requires, maxVisits, timeoutSeconds, maxTurns, instructions, localTestNetwork, protectedPathFallback, candidateRoles, null);
        }
        public Node(Kind kind, String role, String schema, Scope scope, Gate gate, boolean workspace,
                    List<String> writablePaths, String plan, String next, String blocked, Map<String,String> routes,
                    List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions,
                    boolean localTestNetwork, Fallback protectedPathFallback) {
            this(kind, role, schema, scope, gate, workspace, writablePaths, plan, next, blocked, routes,
                    requires, maxVisits, timeoutSeconds, maxTurns, instructions, localTestNetwork, protectedPathFallback, null);
        }
        public Node(Kind kind, String role, String schema, Scope scope, Gate gate, boolean workspace,
                    List<String> writablePaths, String plan, String next, String blocked, Map<String,String> routes,
                    List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions) {
            this(kind, role, schema, scope, gate, workspace, writablePaths, plan, next, blocked, routes,
                    requires, maxVisits, timeoutSeconds, maxTurns, instructions, false, null);
        }
        public Node(Kind kind, String role, String schema, Scope scope, Gate gate, boolean workspace,
                    List<String> writablePaths, String plan, String next, String blocked, Map<String,String> routes,
                    List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions,
                    boolean localTestNetwork) {
            this(kind, role, schema, scope, gate, workspace, writablePaths, plan, next, blocked, routes,
                    requires, maxVisits, timeoutSeconds, maxTurns, instructions, localTestNetwork, null);
        }
        /** 改派時用：同一個節點契約，換成另一個角色執行。 */
        public Node withRole(String other) {
            return new Node(kind, other, schema, scope, gate, workspace, writablePaths, plan, next, blocked, routes,
                    requires, maxVisits, timeoutSeconds, maxTurns, instructions, localTestNetwork, protectedPathFallback, candidateRoles, skills);
        }
    }
    /** 此處只保存檔案內容與環境變數名稱，不保存 token 值。 */
    public record Snapshot(String revision, TeamDefinition definition, String repository, Map<String, String> assets) {}
}
