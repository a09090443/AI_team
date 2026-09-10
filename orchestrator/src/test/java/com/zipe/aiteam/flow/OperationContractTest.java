package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.sandbox.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationContractTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode config(String name) throws Exception { return (ObjectNode)mapper.readTree(Files.readString(Path.of("teams/" + name + ".json"))); }
    @Test void bothStacksDeclareOperationsWithoutEngineRoleSwitches() throws Exception {
        for (String name : List.of("spring-starters", "node-web")) {
            var definition = mapper.treeToValue(config(name), TeamDefinition.class);
            TeamCatalog.validate(definition);
            assertThat(OperationContract.forNode(definition, "implementation")).isNotEmpty();
            assertThat(OperationContract.forNode(definition, "triage")).isEmpty();
        }
    }
    @Test void missingToolDeclarationRejected() throws Exception {
        var config = config("node-web"); ((ObjectNode)config.path("project")).putArray("requiredTools").add("git");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(config, TeamDefinition.class))).hasMessageContaining("操作執行檔未宣告");
    }
    @Test void readOnlyWriteAndShellExpressionsRejected() throws Exception {
        var config = config("node-web");
        var op = (ObjectNode)config.path("project").path("operations").path("install");
        op.putArray("nodes").add("triage");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(config, TeamDefinition.class))).hasMessageContaining("唯讀節點");
        op.putArray("nodes").add("implementation"); op.putArray("argv").add("npm").add("ci; touch bad");
        assertThatThrownBy(() -> TeamCatalog.validate(mapper.treeToValue(config, TeamDefinition.class))).hasMessageContaining("shell 表達式");
    }
    @Test void unsafePathsAndSymlinksRejected() throws Exception {
        assertThatThrownBy(() -> OperationContract.resolve(tmp, "../outside")).hasMessageContaining("安全相對路徑");
        Path outside = Files.createTempDirectory("op-outside-");
        try {
            Files.createSymbolicLink(tmp.resolve("link"), outside);
            assertThatThrownBy(() -> OperationContract.resolve(tmp, "link/new-file")).hasMessageContaining("symlink");
        } finally { Files.deleteIfExists(tmp.resolve("link")); Files.deleteIfExists(outside); }
    }
    @Test void cliPermissionIsNotInferredFromBinaryPresence() {
        var runtime = new ClaudeCodeRuntime(null, null);
        assertThat(runtime.permitsCommand(List.of("Bash(mvn *)"), List.of("npm", "ci"))).isFalse();
        assertThat(runtime.permitsCommand(List.of("Bash(npm ci)"), List.of("npm", "ci"))).isTrue();
        assertThat(runtime.permitsCommand(List.of("Bash(npm ci)"), List.of("npm", "publish"))).isFalse();
        assertThat(runtime.permitsCommand(List.of("Bash(npm *)"), List.of("npm", "test"))).isTrue();
    }
    FlowRun flow() throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        var flow = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.resolve("repo").toString()));
        flow.node = "implementation"; return flow;
    }
    @Test void undeclaredRuntimePermissionStopsBeforeProbe() throws Exception {
        var flow = flow(); var props = mock(AiTeamProperties.class);
        var preflight = new OperationPreflight(props, null);
        assertThatThrownBy(() -> preflight.check("42", flow, flow.snapshot.definition().nodes().get(flow.node), tmp.resolve("repo"),
                new SandboxPolicy(List.of(tmp.resolve("repo")), List.of(), 256, 100, 32), mock(AgentRuntime.class)))
                .hasMessageContaining("未授權指令");
        verifyNoInteractions(props);
    }
    @Test void sandboxWritablePolicyIsIndependentOfCliApproval() throws Exception {
        var flow = flow();
        var preflight = new OperationPreflight(mock(AiTeamProperties.class), null);
        assertThatThrownBy(() -> preflight.check("42", flow, flow.snapshot.definition().nodes().get(flow.node), tmp.resolve("repo"),
                new SandboxPolicy(List.of(), List.of(), 256, 100, 32), new ClaudeCodeRuntime(null, null)))
                .hasMessageContaining("寫入超出沙箱政策");
    }
    @Test void successfulProbePersistsHeadAndLogHash() throws Exception {
        var flow = flow(); var props = mock(AiTeamProperties.class); when(props.logsPath()).thenReturn(tmp.resolve("logs"));
        when(props.userHome()).thenReturn(tmp.resolve("repo")); Files.createDirectories(tmp.resolve("repo/.npm"));
        var preflight = new OperationPreflight(props, null) {
            @Override void probe(Path cwd, SandboxPolicy policy, List<String> args, Path log) throws Exception {
                assertThat(args).contains("tool", "npm", "dir", "write");
                Files.writeString(log, "a".repeat(40) + "\n");
            }
        };
        var evidence = preflight.check("42", flow, flow.snapshot.definition().nodes().get(flow.node), tmp.resolve("repo"),
                new SandboxPolicy(List.of(tmp.resolve("repo")), List.of(), 256, 100, 32), new ClaudeCodeRuntime(null, null));
        assertThat(evidence.head()).isEqualTo("a".repeat(40));
        assertThat(evidence.sha256()).isEqualTo(IndependentVerifier.hash(Path.of(evidence.log())));
    }
    @Test void missingCacheIsNotSilentlyDroppedFromPolicy() throws Exception {
        var flow = flow(); var props = mock(AiTeamProperties.class); when(props.userHome()).thenReturn(tmp.resolve("repo"));
        assertThatThrownBy(() -> new OperationPreflight(props, null).check("42", flow, flow.snapshot.definition().nodes().get(flow.node), tmp.resolve("repo"),
                new SandboxPolicy(List.of(tmp.resolve("repo")), List.of(), 256, 100, 32), new ClaudeCodeRuntime(null, null)))
                .hasMessageContaining("快取目錄缺失");
    }
}
