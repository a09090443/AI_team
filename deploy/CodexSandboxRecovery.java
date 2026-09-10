package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** #1014 一次性恢復檢查；不改 DB、不呼叫模型，只準備空掛載點及產生候選 flow。 */
public class CodexSandboxRecovery {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        ObjectNode raw = (ObjectNode)mapper.readTree(Files.readString(Path.of(args[0])));
        FlowRun flow = mapper.treeToValue(raw, FlowRun.class);
        Path cwd = Path.of("/home/vboxuser/ai-team/workspaces/task-1014");
        if (!"triage".equals(flow.node) || flow.activeNode != null || flow.dialogue
                || flow.pendingPlanRevision != null || flow.changeTarget != null
                || !cwd.toString().equals(flow.workspacePath)
                || flow.visits.getOrDefault("verification", 0) != 2
                || flow.visits.getOrDefault("triage", 0) != 5 || flow.transitions != 12)
            throw new IllegalStateException("#1014 狀態已變，不得套用恢復");
        var node = flow.snapshot.definition().nodes().get("verification");
        if (PlanApproval.blockingReason(flow, node.plan()) != null)
            throw new IllegalStateException("缺少有效真人核准");
        if (flow.transitions >= flow.snapshot.definition().maxTransitions()
                || flow.visits.get("verification") >= node.maxVisits())
            throw new IllegalStateException("QA 已無執行次數，不得繞過上限");
        for (String dependency : node.requires()) {
            String block = FlowGates.blockingReason(flow.snapshot.definition().nodes().get(dependency), flow.receipts.get(dependency), flow);
            if (block != null) throw new IllegalStateException(dependency + ": " + block);
        }
        var props = new AiTeamProperties() {
            @Override public Path userHome() { return Path.of("/home/vboxuser"); }
            @Override public Path codexBin() { return Path.of("/home/vboxuser/.local/node/bin/codex"); }
        };
        var sandbox = new ExecutionSandbox(props);
        var runtime = new CodexRuntime(props, sandbox);
        var executor = new ConfiguredExecutor(props, null, sandbox, null, null);
        SandboxPolicy policy = executor.policy(flow, node, runtime, cwd);
        var prepare = Class.forName("com.zipe.aiteam.runtime.CodexSandboxPreparation").getDeclaredMethod("prepare", Path.class);
        prepare.setAccessible(true); prepare.invoke(null, cwd);
        var check = CodexRuntime.class.getDeclaredMethod("checkSandbox", AgentRuntime.RuntimeRequest.class, Path.class);
        check.setAccessible(true);
        check.invoke(runtime, new AgentRuntime.RuntimeRequest("1014", "verification", cwd, cwd.resolve("AGENTS.md"), "unused",
                List.of(), null, null, 1, Duration.ofSeconds(30), policy, "gpt-5.6-sol"), Path.of(args[2]));
        raw.put("node", "verification"); raw.putNull("activeNode");
        raw.put("lastBlockReason", "前次 QA 為 Codex bwrap 掛載初始化失敗，非 token 或缺少核准；已修復掛載點並通過相同 policy 預檢，尚需重新執行 QA。");
        raw.put("context", flow.context + "\n【管理者受控恢復 2026-09-09】原 Codex bwrap .agents 唯讀啟動錯誤已修復，雙層沙箱預檢通過。"
                + "沿用原工作區及真人核准，保留所有既有 commit，不重做實作。重新执行 QA，不得沿用失敗測試作為 PASS；PR 仍需另行核准。"
                + "僅由 triage 定位 verification；原節點計數及上限未變，先前 triage 計數包含被拒絕的嘗試，不等於 PM 實際執行五次。");
        Files.writeString(Path.of(args[1]), mapper.writeValueAsString(raw) + "\n");
        System.out.println("PASS: original approval, prerequisites and exact #1014 nested sandbox; counters preserved");
    }
}
