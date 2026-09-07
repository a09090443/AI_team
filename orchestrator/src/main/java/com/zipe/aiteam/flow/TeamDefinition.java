package com.zipe.aiteam.flow;

import java.util.List;
import java.util.Map;

/** 管理者配置，不接受 agent 修改。角色 ID 不帶任何流程語意。 */
public record TeamDefinition(int version, String id, Project project, Map<String, Role> roles,
                             String entry, int maxTransitions, Map<String, Node> nodes) {
    public record Project(String name, String repositoryEnv, String baseBranch,
                          String buildInstructions, String testInstructions, List<String> protectedPaths, List<String> cachePaths, List<String> requiredTools,
                          Verification verification) {}
    /** 管理者固定的 argv；不能由模型的 command 欄位提供。 */
    public record Verification(List<List<String>> commands, int timeoutSeconds) {}
    public record Role(String name, String runtime, String model, String prompt,
                       String botTokenEnv, String appTokenEnv, List<String> tools) {}
    public enum Scope { READ_ONLY, WORKSPACE, LIMITED }
    public enum Gate { REPORT, REQUIREMENTS, IMPLEMENTATION, TEST_PLAN, VERIFICATION, REVIEW }
    public enum Kind { AGENT, APPROVAL, END }
    public record Node(Kind kind, String role, String schema, Scope scope, Gate gate,
                       boolean workspace, List<String> writablePaths, String plan,
                       String next, String blocked, Map<String, String> routes,
                       List<String> requires, int maxVisits, int timeoutSeconds, int maxTurns, String instructions) {}
    /** 此處只保存檔案內容與環境變數名稱，不保存 token 值。 */
    public record Snapshot(String revision, TeamDefinition definition, String repository, Map<String, String> assets) {}
}
