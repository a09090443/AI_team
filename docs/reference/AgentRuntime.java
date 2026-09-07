/*
 * 參考實作 — 多 Runtime 抽象，對應 multi-runtime.md。
 * 這不是可直接部署的成品：省略了設定注入、指標與完整的錯誤分類。
 *
 * 設計要點：
 *   1. 抽象畫在「語意能力」層，不是 CLI 旗標層
 *   2. 每個 runtime 宣告自己支援什麼（Capabilities）
 *   3. 缺少的能力由 Orchestrator 降級補上，呼叫端不需要知道底下是誰
 *   4. 安全隔離不在這一層 —— 見 ExecutionSandbox，它包在 runtime 外面
 */
package com.example.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public interface AgentRuntime {

    /** 供 runtime-binding.yaml 參照，例如 "claude-code"、"codex"。 */
    String id();

    /** 宣告本 runtime 支援哪些語意能力。Orchestrator 據此決定是否需要降級。 */
    Capabilities capabilities();

    /**
     * 執行一次 Agent 呼叫。
     *
     * 實作者只需處理自己「支援」的能力；不支援的欄位應忽略，
     * 由 Orchestrator 的降級層負責補足等效行為（見 multi-runtime.md 的降級策略表）。
     */
    RuntimeResult execute(RuntimeRequest request) throws RuntimeExecutionException;

    // ------------------------------------------------------------------

    enum Capability {
        /** 能在不覆蓋自身預設系統提示的前提下附加角色提示。 */
        APPEND_SYSTEM_PROMPT,
        /** 能以 schema 約束輸出並回傳已驗證的結構。 */
        STRUCTURED_OUTPUT,
        /** 能以 id 續接先前的對話。 */
        SESSION_RESUME,
        /** 能限制單次執行的回合數。 */
        MAX_TURNS,
        /** 有細粒度的工具/權限控制。注意：這只是縱深防禦，不是主要安全邊界。 */
        TOOL_PERMISSIONS,
        /** 回報本次執行的成本估算。 */
        COST_REPORTING,
        /** 自行處理工作目錄隔離（例如 git worktree）。 */
        WORKSPACE_ISOLATION
    }

    record Capabilities(Set<Capability> supported) {
        public boolean has(Capability c) { return supported.contains(c); }
    }

    /**
     * 廠商無關的請求。欄位描述「要什麼」，不描述「用哪個旗標」。
     *
     * 角色定義來自中立的 roles/*.yaml，由各 adapter 轉譯成自己的原生格式
     * （Claude Code 轉成 .claude/agents/*.md，其他 runtime 轉成各自的形式，
     *  沒有角色機制的則退回純文字附加在 prompt 前）。
     */
    record RuntimeRequest(
            String taskId,
            String role,                  // pm / architecture / backend / qa
            Path workingDirectory,        // 由 ExecutionSandbox 決定，不由 runtime 決定
            RoleDefinition roleDefinition,
            String prompt,
            String jsonSchema,            // nullable；不支援 STRUCTURED_OUTPUT 時由降級層處理
            String resumeSessionId,       // nullable；不支援 SESSION_RESUME 時由降級層處理
            String priorContextSummary,   // 降級層在缺少 SESSION_RESUME 時填入
            int maxTurns,
            Duration timeout              // 一律由外層強制，不倚賴 runtime 自己的上限
    ) {}

    /** 中立的角色定義，對應 roles/*.yaml。 */
    record RoleDefinition(
            String name,
            String systemPrompt,
            List<String> allowedToolHints,   // 語意提示；能否落實取決於 runtime
            List<String> deniedToolHints,
            boolean readOnly                 // 由 ExecutionSandbox 強制，不倚賴 runtime
    ) {}

    /**
     * 廠商無關的結果。
     *
     * sessionId 與 costEstimate 為 nullable —— 不是所有 runtime 都提供。
     * 呼叫端必須容忍 null，不要假設任何一項存在。
     */
    record RuntimeResult(
            String sessionId,             // nullable
            String rawText,
            JsonNode structuredOutput,    // 已通過 schema 驗證，無論來自原生支援或降級解析
            Double costEstimate,          // nullable
            boolean isError,
            Path diagnosticsLog           // stderr 落檔位置，供稽核
    ) {}

    class RuntimeExecutionException extends Exception {
        public RuntimeExecutionException(String message) { super(message); }
        public RuntimeExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}

/*
 * ------------------------------------------------------------------
 * 安全邊界不在 AgentRuntime 裡
 * ------------------------------------------------------------------
 *
 * 若安全依賴各 runtime 的權限旗標，系統安全性等於所有 runtime 中最弱的那一個。
 * 因此隔離由 Orchestrator 統一提供，包在 runtime 外面：
 *
 *   interface ExecutionSandbox {
 *       SandboxHandle create(String taskId, RoleDefinition role);
 *       // ・專用 unix user 或容器
 *       // ・唯讀掛載：專案唯讀副本
 *       // ・可寫掛載：僅該 task 的 worktree（readOnly 角色則完全不給）
 *       // ・網路：預設拒絕，白名單放行
 *       // ・無 sudo、無 docker socket、無 SSH 金鑰
 *   }
 *
 * 這讓你新增一個不熟悉的 CLI 時，不需要先搞懂它的權限模型才敢用。
 *
 * ------------------------------------------------------------------
 * 降級層的位置
 * ------------------------------------------------------------------
 *
 *   Orchestrator
 *       │
 *       ├── DegradationLayer          ← 補足 runtime 缺少的能力
 *       │     ・無 STRUCTURED_OUTPUT → 要求 fenced JSON，自行以 schema 驗證，
 *       │                              失敗重試一次後判為錯誤
 *       │     ・無 SESSION_RESUME    → 填入 priorContextSummary
 *       │     ・無 MAX_TURNS         → 靠 timeout + destroyForcibly
 *       │
 *       └── AgentRuntime             ← ClaudeCodeRunner / CodexRunner / ...
 *
 * 降級後的語意必須一致：呼叫端不需要知道底下是哪個 runtime，
 * 差別只在可靠度與成本，不在行為。
 */
