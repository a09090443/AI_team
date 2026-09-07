package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.publish.PullRequestPublisher;
import com.zipe.aiteam.runtime.*;
import com.zipe.aiteam.slack.SlackNotifier;
import com.zipe.aiteam.store.*;
import com.zipe.aiteam.task.*;
import com.zipe.aiteam.workspace.WorkspaceManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FlowEngineTest {
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    TaskStore store;
    TeamCatalog catalog = mock(TeamCatalog.class);
    AiTeamProperties props = mock(AiTeamProperties.class);
    ConfiguredExecutor executor = mock(ConfiguredExecutor.class);
    WorkspaceManager workspace = mock(WorkspaceManager.class);
    SlackNotifier slack = mock(SlackNotifier.class);
    PullRequestPublisher publisher = mock(PullRequestPublisher.class);
    IndependentVerifier verifier = mock(IndependentVerifier.class);
    FlowEngine engine;
    Task task = new Task("42", "c", "t", "u", "email failover");
    List<String> calls = new ArrayList<>();
    List<String> resumed = new ArrayList<>();
    String mode = "normal";
    boolean provisioned;
    @BeforeEach void setup(TestInfo info) throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        store = new TaskStore(new JdbcTemplate(ds));
        when(catalog.enabled()).thenReturn(true);
        boolean spring = info.getTestMethod().orElseThrow().getName().startsWith("spring");
        when(catalog.snapshot()).thenReturn(TeamCatalog.load(Path.of(spring ? "teams/spring-starters.json" : "teams/node-web.json"), key -> tmp.resolve("repo").toString()));
        when(props.allowedUsers()).thenReturn(Set.of("u"));
        when(workspace.exists("42")).thenAnswer(c -> provisioned);
        when(workspace.provision(eq(task), any(), anyString())).thenAnswer(c -> { provisioned = true; return tmp; });
        when(workspace.pathFor("42")).thenReturn(tmp.resolve("work"));
        when(workspace.headSha("42")).thenReturn("head1"); when(workspace.clean("42")).thenReturn(true);
        when(workspace.origin("42")).thenReturn("https://github.com/test/example.git");
        when(workspace.currentBranch("42")).thenReturn("feature/task42"); when(workspace.baseBranch("42")).thenReturn("main");
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of());
        when(executor.execute(any(), any(), any())).thenAnswer(c -> {
            FlowRun flow = c.getArgument(1); TeamDefinition.Node node = c.getArgument(2); calls.add(flow.node + ":" + node.role());
            Task executing = c.getArgument(0);
            if (executing.continuation() != null) resumed.add(flow.node + ":" + executing.continuation().sessionId());
            if (mode.equals("quota") && flow.node.equals("verification")) {
                store.saveCheckpoint(new Checkpoint("42", flow.node, "claude-code", "sonnet", "saved-session", "context", "head1", "quota"));
                throw new QuotaExceeded("usage_limit_reached");
            }
            JsonNode report = report(node.gate());
            if (node.gate() == TeamDefinition.Gate.TEST_PLAN || node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("baseline_revision", flow.baselineRevision);
            if (node.gate() == TeamDefinition.Gate.VERIFICATION)
                ((ObjectNode) report).put("plan_revision", Traceability.digest(flow.receipts.get(node.plan())));
            if (flow.node.equals("design")) ((ObjectNode)report).put("route", "ready");
            if (flow.node.equals("triage")) ((ObjectNode)report).put("route", mode.equals("repair") ? "verification" : mode.equals("change") ? "requirements" : mode.equals("replan") ? "plan" : "human");
            if (mode.equals("blocked") && flow.node.equals("verification")) ((ObjectNode)report).withArray("uncertainties").add("SMTP 無法核實");
            return new AgentRuntime.RuntimeResult("session", "", report, 0.1, false, tmp.resolve("log"), "model", null);
        });
        when(verifier.verify(any(), any())).thenAnswer(c -> {
            FlowRun f = c.getArgument(1);
            return new IndependentVerifier.Receipt("head1", f.baselineRevision, f.snapshot.revision(), "start", "end", List.of());
        });
        engine = new FlowEngine(catalog, props, store, executor, workspace, slack, mock(AuditLog.class), publisher, verifier);
        engine.prepare(task); store.save(task);
    }
    JsonNode report(TeamDefinition.Gate gate) throws Exception {
        String fields = switch (gate) {
            case REPORT -> "\"summary\":\"已確認\",\"route\":\"implementation\"";
            case REQUIREMENTS -> "\"summary\":\"郵件需求\",\"route\":\"implementation\",\"requirements\":[{\"id\":\"R1\",\"kind\":\"FUNCTIONAL\",\"description\":\"寄送郵件\",\"acceptance\":\"正確送達\",\"source\":\"使用者需求\"}],\"out_of_scope\":[],\"open_questions\":[]";
            case IMPLEMENTATION -> "\"status\":\"SUCCESS\",\"summary\":\"實作完成\",\"quality_gate\":{\"verify_passed\":true},\"boundary_conflict\":null";
            case TEST_PLAN -> "\"status\":\"READY\",\"scenarios\":[{\"id\":\"s1\",\"requirement_ids\":[\"R1\"],\"criterion\":\"c\",\"preconditions\":\"p\",\"test_data\":\"d\",\"method\":\"m\",\"steps\":\"s\",\"expected_result\":\"r\",\"environment\":\"e\"}]";
            case VERIFICATION -> "\"verdict\":\"PASS\",\"verify_passed\":true,\"scenario_results\":[{\"scenario_id\":\"s1\",\"status\":\"PASS\",\"actual_result\":\"ok\",\"evidence\":\"log\",\"command\":\"npm test\"}],\"findings\":[],\"uncertainties\":[]";
            case REVIEW -> "\"verification_status\":\"VERIFIED\",\"confidence\":\"HIGH\",\"verdict\":\"APPROVE\",\"blockers\":[],\"checked\":[\"diff\"],\"findings\":[]";
        };
        return mapper.readTree("{\"execution_status\":\"COMPLETED\",\"failure_report\":null," + fields + "}");
    }
    @Test void arbitraryFrontendAndReviewerExecuteFromConfiguration() {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
    }
    @Test void springConfigurationUsesSameEngineWithDifferentRolesAndNodes() {
        engine.run(task);
        assertThat(calls).containsExactly("intake:coordinator", "design:architect", "plan:tester", "implementation:builder", "verification:tester", "review:inspector");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL); verifyNoInteractions(publisher);
    }
    @Test void blockedVerificationReallyRoutesToConfiguredCoordinatorAndHuman() {
        mode = "blocked"; engine.run(task);
        assertThat(calls).contains("triage:coordinator").doesNotContain("review:security_auditor");
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN); verifyNoInteractions(publisher);
    }
    @Test void humanFeedbackResumesConfiguredRepairNotWholePipeline() {
        mode = "blocked"; engine.run(task); calls.clear(); mode = "repair";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "已提供 SMTP 環境", "u", jobs::add); jobs.get(0).run();
        assertThat(calls).containsExactly("triage:coordinator", "verification:tester", "review:security_auditor");
        assertThat(store.find("42").orElseThrow().state()).isEqualTo(Task.State.AWAIT_PR_APPROVAL);
    }
    @Test void quotaKeepsSnapshotNodeSessionAndOriginalWorkspace() throws Exception {
        mode = "quota"; engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.WAITING_QUOTA);
        String revision = store.flow("42").orElseThrow().snapshot.revision();
        when(catalog.snapshot()).thenThrow(new AssertionError("續跑不可讀新配置"));
        calls.clear(); mode = "normal"; List<Runnable> jobs = new ArrayList<>();
        engine.resume(task, null, "u", jobs::add); jobs.get(0).run();
        assertThat(calls).containsExactly("verification:tester", "review:security_auditor");
        assertThat(store.flow("42").orElseThrow().snapshot.revision()).isEqualTo(revision);
        verify(workspace, times(1)).provision(any(), any(), any());
        assertThat(resumed).containsExactly("verification:saved-session");
    }
    @Test void changedHeadAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(workspace.headSha("42")).thenReturn("changed");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void onlyWhitelistedHumanCanPublishAndDoubleClickIsAtomic() throws Exception {
        engine.run(task); List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "stranger", jobs::add); assertThat(jobs).isEmpty();
        engine.approve(task, "u", jobs::add); engine.approve(task, "u", jobs::add); assertThat(jobs).hasSize(1);
        when(publisher.publish(eq("42"), any(), any(), eq("feature/task42"))).thenReturn(new PullRequestPublisher.Result("feature/task42", "https://example.test/pr/1", 1));
        jobs.get(0).run(); verify(publisher).publish(eq("42"), any(), any(), eq("feature/task42"));
        assertThat(task.state()).isEqualTo(Task.State.PR_CREATED);
        assertThat(store.flow("42").orElseThrow().prUrl).isEqualTo("https://example.test/pr/1");
        assertThat(store.findUnfinished()).isEmpty();
    }
    @Test void limitedScopeIndexBypassStopsBeforeReview() throws Exception {
        when(workspace.auditPaths(eq("42"), anyString())).thenReturn(List.of("src/email.ts")); engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN); assertThat(calls).doesNotContain("review:security_auditor"); verifyNoInteractions(publisher);
    }
    @Test void restartUsesPersistedDefinitionEvenIfRoleRemovedFromCurrentConfig() {
        mode = "quota"; engine.run(task);
        FlowRun saved = store.flow("42").orElseThrow();
        assertThat(saved.snapshot.definition().roles()).containsKey("security_auditor");
        when(catalog.enabled()).thenReturn(false); when(catalog.snapshot()).thenThrow(new AssertionError("舊任務不能改用新配置"));
        task.state(Task.State.STAGE_RUNNING); store.save(task); engine.recover(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().snapshot.definition().roles()).containsKey("security_auditor");
    }
    @Test void independentFailureStopsBeforeReviewerAndPublication() throws Exception {
        doThrow(new IllegalStateException("exit=1; check-0.log")).when(verifier).verify(any(), any());
        engine.run(task);
        assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(calls).doesNotContain("review:security_auditor"); verifyNoInteractions(publisher);
    }
    @Test void corruptedEvidenceAfterApprovalNeverPublishes() throws Exception {
        engine.run(task); when(verifier.blockingReason(any(), anyString())).thenReturn("log 雜湊不符");
        List<Runnable> jobs = new ArrayList<>(); engine.approve(task, "u", jobs::add); jobs.get(0).run();
        verifyNoInteractions(publisher); assertThat(task.state()).isEqualTo(Task.State.AWAIT_HUMAN);
    }
    @Test void planRepairRerunsImplementationAndVerification() {
        mode = "blocked"; engine.run(task); mode = "replan"; calls.clear();
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "測試計畫缺漏", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("triage:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
    }
    @Test void baselineChangeRequiresExplicitOwnerApprovalAndKeepsHistory() {
        mode = "blocked"; engine.run(task); mode = "change";
        List<Runnable> jobs = new ArrayList<>(); engine.resume(task, "需要重新確認需求", "u", jobs::add); jobs.removeFirst().run();
        Task saved = store.find("42").orElseThrow();
        assertThat(saved.state()).isEqualTo(Task.State.AWAIT_HUMAN);
        assertThat(store.flow("42").orElseThrow().changeTarget).isEqualTo("intake");
        calls.clear(); engine.resume(saved, "好", "u", jobs::add); assertThat(jobs).isEmpty();
        engine.resume(saved, "核准需求變更 修正寄送驗收條件", "stranger", jobs::add); assertThat(jobs).isEmpty();
        mode = "normal"; engine.resume(saved, "核准需求變更 修正寄送驗收條件", "u", jobs::add); jobs.getFirst().run();
        assertThat(calls).containsExactly("intake:coordinator", "plan:tester", "implementation:builder", "frontend:frontend", "verification:tester", "review:security_auditor");
        assertThat(store.flow("42").orElseThrow().baselineHistory).hasSize(1);
    }

    /**
     * 交接通知在改成配置式引擎後整批漏掉了。這兩個測試釘住的是：
     * 換角色時要以「上一棒的身分」發言並 tag 下一棒，受阻退回時也一樣。
     */
    @Test void springHandoffTagsNextRoleWithPreviousRoleIdentity() {
        when(slack.mention(any())).thenAnswer(c -> "@" + c.getArgument(0));
        engine.run(task);
        var texts = org.mockito.ArgumentCaptor.forClass(String.class);
        var roles = org.mockito.ArgumentCaptor.forClass(TeamDefinition.Role.class);
        verify(slack, atLeastOnce()).postConfigured(roles.capture(), any(), any(), texts.capture());
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@ARCHITECT") && t.contains("換你接手"));
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@INSPECTOR"));
        // 交接是以上一棒的身分發的，不是系統代發
        assertThat(roles.getAllValues()).extracting(TeamDefinition.Role::botTokenEnv).doesNotContainNull();
    }

    @Test void blockedHandoffTagsTheRoleThatTakesOverAndTheOwner() {
        mode = "blocked";
        when(slack.mention(any())).thenAnswer(c -> "@" + c.getArgument(0));
        engine.run(task);
        var texts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).postConfigured(any(), any(), any(), texts.capture());
        assertThat(texts.getAllValues()).anyMatch(t -> t.contains("@COORDINATOR") && t.contains("受阻"));
        // 停下等人時要 tag 需求提出者，否則沒有人知道輪到自己
        var system = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(slack, atLeastOnce()).post(any(), any(), any(), system.capture());
        assertThat(system.getAllValues()).anyMatch(t -> t.contains("<@u>"));
    }
}
