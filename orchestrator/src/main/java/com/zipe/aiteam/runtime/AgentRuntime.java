package com.zipe.aiteam.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.sandbox.SandboxPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 廠商無關的 Agent 執行介面。
 *
 * 抽象刻意畫在「語意能力」層而非 CLI 旗標層 —— 不同 CLI 的旗標與輸出格式差異很大，
 * 但語意需求是一致的。詳見 docs-site/docs/multi-runtime.md。
 *
 * 安全隔離不屬於這一層。若安全依賴各 runtime 的權限旗標，
 * 系統安全性等於所有 runtime 中最弱的那一個。實作者必須把最終的子行程指令
 * 交給 ExecutionSandbox 包裝後再執行 —— 這不是建議，是介面契約的一部分。
 */
public interface AgentRuntime {

    /** 供 runtime 綁定設定參照，例如 "claude-code"、"codex"、"opencode"。 */
    String id();

    /** 預檢操作是否在 CLI 工具政策內；未知 runtime 預設拒絕，不自行擴權。 */
    default boolean permitsCommand(List<String> tools, List<String> argv) { return false; }

    /** 本 runtime 原生支援哪些能力；缺少的由呼叫端降級補足。 */
    Set<Capability> capabilities();

    RuntimeResult execute(RuntimeRequest request) throws RuntimeExecutionException;
    default RuntimeResult execute(RuntimeRequest request, java.util.function.Consumer<String> sessionStarted)
            throws RuntimeExecutionException {
        return execute(request);
    }

    /**
     * 這個 runtime 自己需要寫入的路徑（session 記錄、憑證更新、快取）。
     *
     * 不給寫的話 resume 會失效、OAuth token 到期也無法自動換新。
     * 由 runtime 自己宣告而不是寫死在 SandboxPolicy —— 各家 CLI 的狀態目錄不同，
     * 寫死等於讓第二個 runtime 一接上就靜默失效。
     */
    List<java.nio.file.Path> statePaths(java.nio.file.Path home);

    /**
     * 在可寫的狀態目錄內要釘回唯讀的路徑。
     *
     * 開放狀態目錄可寫會換來一條提權管道：設定檔可以定義 hook、外掛目錄可以塞進
     * 會被載入的程式碼，兩者都在「下一次」執行時生效，等於讓 agent 有辦法
     * 改寫自己的執行條件。巢狀覆蓋已實測有效（ReadWritePaths 內的 ReadOnlyPaths 勝出）。
     */
    List<java.nio.file.Path> escalationPins(java.nio.file.Path home);

    /**
     * runtime 自身在自動模式下一律拒絕寫入的專案內路徑，任何權限設定都開放不了。
     *
     * 目錄以結尾 "/" 表示（比對任一層目錄名），其餘為檔名；空清單代表沒有這類限制。
     * 這不是安全邊界（那是沙箱），而是要讓規劃與實作節點事先知道：
     * #1021 把改 `.claude/rules/` 寫進驗收，實作做完才被拒，分流又提議「開放權限後重派」，按了也只會再被拒。
     */
    default List<String> protectedWritePaths() { return List.of(); }

    /** 以本 runtime 的 {@link #protectedWritePaths()} 判斷一個寫入目標（相對或絕對路徑）是否必定被拒。 */
    default boolean isProtectedWrite(String path) {
        if (path == null || path.isBlank()) return false;
        String[] parts = path.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length; i++) {
            for (String rule : protectedWritePaths()) {
                if (rule.endsWith("/")) {
                    String[] dir = rule.substring(0, rule.length() - 1).split("/");
                    if (i + dir.length >= parts.length) continue; // 目錄本身之下要還有東西
                    boolean match = true;
                    for (int j = 0; j < dir.length && match; j++) match = parts[i + j].equals(dir[j]);
                    // Claude Code 自己的 git worktree 放在這裡，官方明列為例外。
                    if (match && rule.equals(".claude/") && parts[i + 1].equals("worktrees")) match = false;
                    if (match) return true;
                } else if (i == parts.length - 1 && parts[i].equals(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    enum Capability {
        /** 能在不覆蓋自身預設系統提示的前提下附加角色提示 */
        APPEND_SYSTEM_PROMPT,
        /** 能以 schema 約束輸出並回傳已驗證的結構 */
        STRUCTURED_OUTPUT,
        /** 能以 id 續接先前的對話 */
        SESSION_RESUME,
        /** 能限制單次執行的回合數 */
        MAX_TURNS,
        /** 有細粒度的工具權限控制（僅為縱深防禦，非主要安全邊界） */
        TOOL_PERMISSIONS,
        /** 回報本次執行的成本估算 */
        COST_REPORTING,
        /** 能指定這次執行要用哪個模型 */
        MODEL_SELECTION,
        /** 原生就會載入工作目錄的 AGENTS.md，呼叫端不需再附進 prompt */
        NATIVE_AGENTS_MAP
    }

    record RuntimeRequest(
            String taskId,
            String role,
            Path workingDirectory,
            Path systemPromptFile,
            String prompt,
            List<String> allowedTools,
            String jsonSchema,        // nullable
            String resumeSessionId,   // nullable
            int maxTurns,
            Duration timeout,
            /** 這次執行的隔離條件。不可為 null —— runtime 沒有「不隔離」這個選項。 */
            SandboxPolicy sandboxPolicy,
            /**
             * 指定模型。空字串表示不指定，由 runtime 自己決定。
             *
             * 不用 null 表示「不指定」，是因為 null 與「空字串」在傳給 CLI 時
             * 行為不同：傳空值會讓 claude 以結束碼 1 失敗，必須整個略過旗標。
             */
            String model,
            String reasoningEffort
    ) {
        public RuntimeRequest(String taskId, String role, Path workingDirectory, Path systemPromptFile,
                              String prompt, List<String> allowedTools, String jsonSchema, String resumeSessionId,
                              int maxTurns, Duration timeout, SandboxPolicy sandboxPolicy, String model) {
            this(taskId, role, workingDirectory, systemPromptFile, prompt, allowedTools, jsonSchema,
                    resumeSessionId, maxTurns, timeout, sandboxPolicy, model, null);
        }
        public RuntimeRequest {
            if (reasoningEffort != null && !reasoningEffort.isBlank()
                    && !Set.of("low", "medium", "high", "xhigh").contains(reasoningEffort)) {
                throw new IllegalArgumentException("不支援的 reasoningEffort");
            }
            if (sandboxPolicy == null) {
                throw new IllegalArgumentException(
                        "sandboxPolicy 不可為 null：所有 agent 執行都必須經過 ExecutionSandbox。");
            }
        }
    }

    /**
     * sessionId 與 costEstimate 為 nullable —— 不是所有 runtime 都提供。
     * 呼叫端必須容忍 null。
     */
    record RuntimeResult(
            String sessionId,
            String rawText,
            JsonNode structuredOutput,
            Double costEstimate,
            boolean isError,
            Path diagnosticsLog,
            /**
             * 這次實際使用的模型（可能不只一個，claude 內部會用小模型做輔助工作）。
             * 記錄實際值而非我們要求的值 —— 兩者不一定相同，而比較不同輪次時要看實際的。
             */
            String modelsUsed,
            /**
             * 被權限設定擋下來的工具呼叫。空陣列或 null 表示沒有。
             *
             * 這是 agent 「想做但做不到」的清單。不看它的話，一個因為工具沒開
             * 而做不完的任務，看起來會像是 agent 自己能力不足 —— 兩者的處理方式完全不同。
             */
            JsonNode permissionDenials
    ) {}

    class RuntimeExecutionException extends Exception {
        public RuntimeExecutionException(String message) { super(message); }
        public RuntimeExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
