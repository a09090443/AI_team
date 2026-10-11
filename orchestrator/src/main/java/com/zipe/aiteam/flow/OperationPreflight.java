package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.AgentRuntime;
import com.zipe.aiteam.sandbox.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 在與 agent 相同的沙箱政策內檢查，不執行 npm/mvn 等建置，不呼叫模型。 */
public class OperationPreflight {
    public record Evidence(String workspace, String head, String configuration, String checkedAt, String log, String sha256) {}
    private final AiTeamProperties props;
    private final ExecutionSandbox sandbox;
    public OperationPreflight(AiTeamProperties props, ExecutionSandbox sandbox) { this.props = props; this.sandbox = sandbox; }
    public Evidence check(String taskId, FlowRun flow, TeamDefinition.Node node, Path cwd,
                          SandboxPolicy policy, AgentRuntime runtime) throws Exception {
        Map<String, TeamDefinition.Operation> operations = flow.dialogue ? Map.of() : OperationContract.forNode(flow.snapshot.definition(), flow.node);
        var role = flow.snapshot.definition().roles().get(node.role());
        for (Path script : OperationContract.scriptPins(flow.snapshot.definition(), flow.node, cwd)) {
            if (policy.readOnlyPins().stream().noneMatch(pin -> script.startsWith(pin.toAbsolutePath().normalize()))
                    && policy.writablePaths().stream().anyMatch(write -> script.startsWith(write.toAbsolutePath().normalize())))
                throw new IllegalStateException("核准腳本未釘選唯讀：" + script);
        }
        List<String> args = new ArrayList<>();
        for (var entry : operations.entrySet()) {
            var operation = entry.getValue();
            if (!runtime.permitsCommand(role.tools(), operation.argv()))
                throw new IllegalStateException("派工前檢查：操作=" + entry.getKey() + "，角色=" + node.role()
                        + "，未授權指令=" + operation.argv() + "；請管理者檢視配置差異，未呼叫模型或自動擴權");
            Path directory = OperationContract.resolve(cwd, operation.directory());
            if (!Files.isDirectory(directory)) throw new IllegalStateException("派工前檢查：操作目錄不存在：" + directory);
            args.add("tool"); args.add(operation.argv().getFirst());
            args.add("dir"); args.add(directory.toString());
            for (String write : operation.writes()) {
                Path path = OperationContract.resolve(cwd, write);
                boolean writable = policy.writablePaths().stream().anyMatch(p -> path.startsWith(p.toAbsolutePath().normalize()));
                boolean pinned = policy.readOnlyPins().stream().anyMatch(p -> path.startsWith(p.toAbsolutePath().normalize()));
                if (!writable || pinned) throw new IllegalStateException("派工前檢查：寫入超出沙箱政策：" + path);
                Path existing = path;
                while (!Files.exists(existing)) existing = existing.getParent();
                args.add("write"); args.add(existing.toString());
            }
        }
        // 依原快照檢查工具，而不是僅相信服務啟動時檢查的最新專案。
        for (String tool : flow.snapshot.definition().project().requiredTools()) { args.add("tool"); args.add(tool); }
        if (!operations.isEmpty() && node.scope() != TeamDefinition.Scope.READ_ONLY
                && flow.snapshot.definition().project().cachePaths() != null) {
            for (String cache : flow.snapshot.definition().project().cachePaths()) {
                Path path = props.userHome().resolve(cache).toAbsolutePath().normalize();
                if (!Files.isDirectory(path) || !path.toRealPath().equals(path))
                    throw new IllegalStateException("派工前檢查：快取目錄缺失或含 symlink：" + path + "；請管理者建立／確認，未自行擴權");
                if (policy.writablePaths().stream().noneMatch(p -> path.startsWith(p.toAbsolutePath().normalize())))
                    throw new IllegalStateException("派工前檢查：快取未列入可寫政策：" + path);
                args.add("write"); args.add(path.toString());
            }
        }
        Path directory = props.logsPath().resolve(taskId);
        Files.createDirectories(directory);
        Path log = Files.createTempFile(directory, "preflight-", ".log");
        probe(cwd, policy, args, log);
        String head = Files.readAllLines(log).stream().filter(s -> s.matches("[0-9a-f]{40,64}")).findFirst()
                .orElseThrow(() -> new IllegalStateException("派工前檢查缺 Git HEAD 證據：" + log));
        return new Evidence(cwd.toRealPath().toString(), head, flow.snapshot.revision(), Instant.now().toString(),
                log.toString(), IndependentVerifier.hash(log));
    }
    void probe(Path cwd, SandboxPolicy policy, List<String> args, Path log) throws Exception {
        String script = "set -eu; git rev-parse --verify HEAD; while [ $# -gt 0 ]; do kind=$1; value=$2; shift 2; "
                + "case $kind in tool) command -v -- \"$value\" >/dev/null || { printf 'missing tool: %s\\n' \"$value\"; exit 21; };; "
                + "dir) test -d \"$value\" || exit 22;; write) test -w \"$value\" || { printf 'not writable: %s\\n' \"$value\"; exit 23; };; esac; done";
        List<String> command = new ArrayList<>(List.of("sh", "-c", script, "preflight")); command.addAll(args);
        String unit = props.unitPrefix() + "preflight-" + UUID.randomUUID();
        List<String> wrapped = new ArrayList<>(sandbox.wrap(unit, policy, cwd, command));
        wrapped.addAll(wrapped.indexOf("--"), List.of("-p", "RuntimeMaxSec=20", "-p", "KillMode=control-group"));
        Process process = com.zipe.aiteam.sandbox.ArgumentListTooLong.start(new ProcessBuilder(wrapped).redirectErrorStream(true).redirectOutput(log.toFile()));
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalStateException("派工前檢查失敗；請管理者確認工具／權限，未呼叫模型。證據：" + log);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
