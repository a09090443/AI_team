package com.zipe.aiteam.runtime;

import com.zipe.aiteam.sandbox.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CodexLocalTestPolicyTest {
    @TempDir Path tmp;

    private AgentRuntime.RuntimeRequest request(Path cwd, Path state, Path cache) {
        var policy = new SandboxPolicy(List.of(cwd.resolve("target"),cwd.resolve(".git"),state,cache),List.of(),512,100,128,true);
        return new AgentRuntime.RuntimeRequest("1","qa",cwd,cwd.resolve("role"),"",List.of(),null,null,1,Duration.ofSeconds(10),policy,"");
    }

    @Test void profileDoesNotExposeRuntimeStateAndKeepsMetadataReadonly() {
        Path state = tmp.resolve("home/.codex"), cwd = tmp.resolve("repo"), cache = tmp.resolve("home/.npm");
        var req = request(cwd, state, cache);
        assertThat(CodexLocalTestPolicy.commandWrites(req,List.of(state))).containsExactly(cwd.resolve("target"),cache);
        String args = String.join(" ",CodexLocalTestPolicy.sandboxArguments(req,List.of(state)));
        assertThat(args).contains("domains={}","features.network_proxy=true","allow_upstream_proxy=false","approval_policy=\"never\"")
                .doesNotContain("danger-full-access",state.toString());
    }

    /**
     * codex sandbox 要 -P，codex exec 收到 -P 會以結束碼 2 拒絕。
     * 兩邊曾共用同一組參數，預檢通過但真正派工必然失敗（#1014）。
     */
    @Test void sandboxUsesNamedProfileFlagAndExecUsesDefaultPermissions() {
        Path state = tmp.resolve("home/.codex"), cwd = tmp.resolve("repo"), cache = tmp.resolve("home/.npm");
        var req = request(cwd, state, cache);
        List<String> forSandbox = CodexLocalTestPolicy.sandboxArguments(req, List.of(state));
        List<String> forExec = CodexLocalTestPolicy.execArguments(req, List.of(state));

        assertThat(forSandbox.subList(0, 2)).containsExactly("-P", CodexLocalTestPolicy.PROFILE);
        assertThat(forExec).doesNotContain("-P", "--permission-profile");
        assertThat(String.join(" ", forExec)).contains("default_permissions=\"" + CodexLocalTestPolicy.PROFILE + "\"");

        // 兩邊套用的必須是同一個 profile 定義，只是指定方式不同。
        String definition = forSandbox.stream().filter(a -> a.startsWith("permissions.")).findFirst().orElseThrow();
        assertThat(forExec).contains(definition);
    }
}
