package com.zipe.aiteam.flow;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BaselineVerifierTest {
    @TempDir Path tmp;
    AiTeamProperties props = mock(AiTeamProperties.class);
    TeamCatalog catalog = mock(TeamCatalog.class);
    WorkspaceManager workspaces = mock(WorkspaceManager.class);
    IndependentVerifier verifier = mock(IndependentVerifier.class);
    ExecutionSandbox sandbox = mock(ExecutionSandbox.class);
    BaselineVerifier baseline;
    TeamDefinition.Snapshot snapshot;
    final String sha = "a".repeat(40);
    final List<List<String>> ran = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        snapshot = TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.resolve("repo").toString());
        when(catalog.enabled()).thenReturn(true);
        when(catalog.snapshot()).thenReturn(snapshot);
        when(props.homePath()).thenReturn(tmp.resolve("home"));
        when(workspaces.baseRef(any(), any())).thenReturn(new WorkspaceManager.BaseRef("main", sha));
        when(verifier.dependencyCache()).thenReturn(Files.createDirectories(tmp.resolve("cache")));
        baseline = new BaselineVerifier(props, catalog, workspaces, verifier, sandbox);
    }

    /** git init 時建出 repo 目錄；其餘指令依 outcome 決定寫什麼紀錄、是否以非零結束。 */
    void stub(Map<String, String> failures) throws Exception {
        doAnswer(c -> {
            List<String> argv = c.getArgument(1); Path cwd = c.getArgument(2); Path log = c.getArgument(5);
            if (argv.contains("init")) Files.createDirectories(Path.of(argv.getLast()).resolve(".git"));
            else if (!argv.getFirst().equals("git")) {
                ran.add(argv);
                Files.createDirectories(cwd);
                String key = String.join(" ", argv);
                if ("TIMEOUT".equals(failures.get(key))) throw new IndependentVerifier.VerificationTimeout(argv, log);
                Files.writeString(log, failures.getOrDefault(key, "ok"));
                if (failures.containsKey(key)) throw new IndependentVerifier.CommandExit(argv, 1, log);
            }
            return null;
        }).when(verifier).execute(eq(BaselineVerifier.ID), anyList(), any(), any(), any(), any(), anyLong());
    }

    @Test void failingCommandDoesNotStopTheRestAndIsReportedToAgentsAndAdmins() throws Exception {
        stub(Map.of("npm test", "[ERROR] Tests run: 2, Failures: 2\nFooTest <<< FAILURE!"));

        var report = baseline.run();

        assertThat(ran).containsExactly(List.of("npm", "ci"), List.of("npm", "test"), List.of("npm", "run", "build"));
        assertThat(report.passed()).isFalse();
        assertThat(report.problems()).extracting(BaselineVerifier.Result::outcome).containsExactly(BaselineVerifier.Outcome.FAIL);
        assertThat(baseline.alreadyVerified(sha)).isTrue();
        assertThat(baseline.latest()).contains(report);
        assertThat(baseline.notice(snapshot.revision())).contains("系統事實", "npm test", "程式或測試失敗", "不是本任務造成的", "需求階段");
        assertThat(BaselineVerifier.slackSummary(report)).contains("⚠️ 基準驗證未通過", "❌ npm test", "結束碼 1", "不會阻擋任務");
    }

    @Test void passingBaselineAddsNothingToTheAgentContext() throws Exception {
        stub(Map.of());
        var report = baseline.run();
        assertThat(report.passed()).isTrue();
        assertThat(baseline.notice(snapshot.revision())).isEmpty();
        assertThat(BaselineVerifier.slackSummary(report)).startsWith("✅ 基準驗證通過");
    }

    @Test void environmentAndTimeoutAreNotCalledTestFailures() throws Exception {
        stub(Map.of("npm ci", "npm ERR! Could not resolve dependencies", "npm test", "TIMEOUT"));
        var report = baseline.run();
        assertThat(report.results()).extracting(BaselineVerifier.Result::outcome).containsExactly(
                BaselineVerifier.Outcome.ENVIRONMENT, BaselineVerifier.Outcome.TIMEOUT, BaselineVerifier.Outcome.SKIPPED);
        assertThat(ran).hasSize(2);
    }

    void enableAutonomy() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(snapshot.definition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.path("project")).set("autonomy", mapper.readTree(
                "{\"modelTimeoutRecoveries\":0,\"maxTimeoutSeconds\":0,\"verificationContinuations\":2,"
                        + "\"requirementChangeOptions\":false,\"channelCommandRouting\":false}"));
        snapshot = new TeamDefinition.Snapshot("b".repeat(64), mapper.treeToValue(definition, TeamDefinition.class),
                snapshot.repository(), snapshot.assets());
        when(catalog.snapshot()).thenReturn(snapshot);
    }
    /** 第一次跑到 timeoutAt 指令時用完總時限，之後都通過。 */
    void stubTimeoutOnce(String timeoutAt) throws Exception {
        int[] left = {1};
        doAnswer(c -> {
            List<String> argv = c.getArgument(1); Path cwd = c.getArgument(2); Path log = c.getArgument(5);
            if (argv.contains("init")) Files.createDirectories(Path.of(argv.getLast()).resolve(".git"));
            else if (!argv.getFirst().equals("git")) {
                ran.add(argv); Files.createDirectories(cwd);
                if (String.join(" ", argv).equals(timeoutAt) && left[0]-- > 0) throw new IndependentVerifier.VerificationTimeout(argv, log);
                Files.writeString(log, "ok");
            }
            return null;
        }).when(verifier).execute(eq(BaselineVerifier.ID), anyList(), any(), any(), any(), any(), anyLong());
    }

    @Test void autonomyContinuesTheRemainingCommandsAfterAProductiveRound() throws Exception {
        enableAutonomy(); stubTimeoutOnce("npm test");
        var report = baseline.run();
        // #1053：五條指令 1800 秒跑不完；前一輪有通過的就給剩下的一個新總時限。
        assertThat(report.passed()).isTrue();
        assertThat(ran).containsExactly(List.of("npm", "ci"), List.of("npm", "test"), List.of("npm", "test"), List.of("npm", "run", "build"));
        assertThat(report.results().get(1).summary()).contains("自動續驗第 1 輪");
    }

    @Test void autonomyDoesNotContinueARoundThatPassedNothing() throws Exception {
        enableAutonomy(); stubTimeoutOnce("npm ci");
        var report = baseline.run();
        assertThat(report.results()).extracting(BaselineVerifier.Result::outcome).containsExactly(
                BaselineVerifier.Outcome.TIMEOUT, BaselineVerifier.Outcome.SKIPPED, BaselineVerifier.Outcome.SKIPPED);
    }

    @Test void missingBaseBranchIsRecordedWithoutRunningAnything() throws Exception {
        when(workspaces.baseRef(any(), any())).thenThrow(new WorkspaceManager.WorkspaceException("unknown revision"));
        var report = baseline.run();
        assertThat(report.error()).contains("取不到基準分支");
        assertThat(report.passed()).isFalse();
        verify(verifier, never()).execute(anyString(), anyList(), any(), any(), any(), any(), anyLong());
        assertThat(baseline.notice(snapshot.revision())).contains("基準驗證沒能執行");
    }
}
