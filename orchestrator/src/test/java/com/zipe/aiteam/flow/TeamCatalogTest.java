package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class TeamCatalogTest {
    @Test void skillsAreFrozenDeliveredAndPreservedOnRoleChange() throws Exception {
        Files.createDirectories(tmp.resolve("repo/.git"));
        Path teams = tmp.resolve("teams");
        try (var paths = Files.walk(Path.of("teams"))) {
            for (Path path : paths.toList()) {
                Path destination = teams.resolve(Path.of("teams").relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
        var before = TeamCatalog.load(teams.resolve("bestpartner.json"), key -> tmp.resolve("repo").toString());
        var node = before.definition().nodes().get("service");
        String original = ConfiguredExecutor.skillInstructions(before, node);
        assertThat(original).contains("name: service-build", "本次須");
        assertThat(node.withRole("coordinator").skills()).isEqualTo(node.skills());
        Files.writeString(teams.resolve("skills/service-build/SKILL.md"), "changed skill content");
        var after = TeamCatalog.load(teams.resolve("bestpartner.json"), key -> tmp.resolve("repo").toString());
        assertThat(after.revision()).isNotEqualTo(before.revision());
        assertThat(ConfiguredExecutor.skillInstructions(before, node)).isEqualTo(original);
        assertThat(ConfiguredExecutor.skillInstructions(after, node)).contains("changed skill content");
        assertThatThrownBy(() -> TeamMigration.preview(before, after)).hasMessageContaining("不能原地遷移");
    }

    @Test void skillsDoNotExpandPathsAndOldNodesStillWork() throws Exception {
        var json = config();
        var node = (ObjectNode) json.path("nodes").path("implementation");
        assertThat(mapper.treeToValue(json, TeamDefinition.class).nodes().get("implementation").skills()).isNull();
        node.putArray("skills").add("../outside/SKILL.md");
        assertThatThrownBy(() -> validate(json)).hasMessageContaining("skill");
        node.putArray("skills").add("skills/build/SKILL.md").add("skills/build/SKILL.md");
        assertThatThrownBy(() -> validate(json)).hasMessageContaining("不得重複");
    }

    @Test void bestpartnerDesignBlockCanBeTriagedWithoutBypassingPrerequisites() throws Exception {
        var definition = mapper.readValue(Files.readString(Path.of("teams/bestpartner.json")), TeamDefinition.class);
        TeamCatalog.validate(definition);
        assertThat(definition.nodes().get("design").blocked()).isEqualTo("triage");
        assertThat(definition.nodes().get("design").routes()).containsEntry("human", "human");
        assertThat(definition.nodes().get("triage").routes()).containsEntry("design", "design").containsEntry("plan", "plan");
        assertThat(definition.nodes().get("implementation").requires()).contains("plan");
    }
    @Test void springBuilderCanBuildDocsWithoutBroadNpmOrPmWriteAccess() throws Exception {
        var definition = mapper.readValue(Files.readString(Path.of("teams/spring-starters.json")), TeamDefinition.class);
        TeamCatalog.validate(definition);
        assertThat(definition.roles().get("builder").tools()).contains("Bash(npm ci)", "Bash(npm run build)")
                .doesNotContain("Bash(npm *)", "Bash(npx *)");
        assertThat(definition.roles().get("coordinator").tools()).doesNotContain("Write", "Edit", "Bash(npm run build)");
        assertThat(definition.project().requiredTools()).contains("node", "npm");
        assertThat(definition.project().cachePaths()).contains(".npm");
    }
    @Test void bundledTeamsHaveNoInspectionToolWarnings() throws Exception {
        for (String name : java.util.List.of("spring-starters", "node-web")) {
            var definition = mapper.readValue(Files.readString(Path.of("teams/" + name + ".json")), TeamDefinition.class);
            assertThat(TeamCatalog.warnings(definition)).as(name).isEmpty();
        }
    }
    @Test void warnsWhenAReadOnlyJudgingNodesRoleCannotInspectAnything() throws Exception {
        // 新專案常見的錯：分流／審查角色只給 Read/Grep/Glob，執行時一查 git 就被工具遭拒規則作廢。
        var json = (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/spring-starters.json")));
        var tools = ((ObjectNode) json.path("roles").path("coordinator")).putArray("tools");
        tools.add("Read").add("Grep").add("Glob");
        var definition = mapper.treeToValue(json, TeamDefinition.class);

        assertThat(TeamCatalog.warnings(definition))
                .anyMatch(w -> w.contains("節點 triage") && w.contains("coordinator") && w.contains("Bash(git log *)"));
    }
    @Test void qaModelEffortAndOldSnapshotCompatibility() throws Exception {
        var definition = mapper.readValue(Files.readString(Path.of("teams/spring-starters.json")), TeamDefinition.class);
        TeamCatalog.validate(definition);
        var qa = definition.roles().get("tester");
        assertThat(qa.model()).isEqualTo("gpt-5.6-sol");
        assertThat(qa.reasoningEffort()).isEqualTo("medium");
        assertThat(qa.executionLabel()).contains("gpt-5.6-sol", "medium", "要求值");
        var old = config();
        assertThat(mapper.treeToValue(old, TeamDefinition.class).roles().get("builder").reasoningEffort()).isNull();
        validate(old);
        var role = (ObjectNode)old.path("roles").path("builder");
        role.put("reasoningEffort", "medium");
        assertThatThrownBy(() -> validate(old)).hasMessageContaining("僅支援 codex");
        role.put("runtime", "codex").put("reasoningEffort", "invalid");
        assertThatThrownBy(() -> validate(old)).hasMessageContaining("不支援的 reasoningEffort");
    }
    @TempDir Path tmp;
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode config() throws Exception { return (ObjectNode)mapper.readTree(Files.readString(Path.of("teams/node-web.json"))); }
    void validate(ObjectNode config) throws Exception { TeamCatalog.validate(mapper.treeToValue(config, TeamDefinition.class)); }
    @Test void nodeProjectAddsFrontendAndReplacesReviewerWithoutJavaChanges() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        var snapshot = TeamCatalog.load(Path.of("teams/node-web.json"), key -> tmp.toString());
        assertThat(snapshot.definition().roles()).containsKeys("frontend", "security_auditor").doesNotContainKeys("architect", "inspector");
        assertThat(snapshot.definition().project().protectedPaths()).contains("src/**", "package.json").doesNotContain("**/pom.xml");
        assertThat(snapshot.assets()).containsKeys("prompts/plan.md", "prompts/triage.md");
    }
    @Test void missingRoleReferenceRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("roles")).remove("frontend");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("不存在角色");
    }
    @Test void deletingMandatoryReviewRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("approval")).putArray("requires").add("implementation").add("plan").add("verification");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("不可省略");
    }
    @Test void unsafeFilesystemPolicyRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("verification")).putArray("writablePaths").add("../../.ssh/**");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("不安全");
    }
    @Test void writableReviewerRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("review")).put("scope", "LIMITED");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("唯讀");
    }
    @Test void missingTransitionRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("review")).put("next", "missing");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("不存在");
    }
    @Test void unboundedRetryRejected() throws Exception {
        var config = config(); config.put("maxTransitions", 999);
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("1..64");
    }
    @Test void broadCacheWriteRejected() throws Exception {
        var config = config(); ((ObjectNode)config.path("project")).putArray("cachePaths").add(".ssh");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("快取");
    }
    @Test void relativeGlobWorksForJavaAndOtherStacks() {
        assertThat(PathRules.matches("**/src/test/**", "src/test/A.java")).isTrue();
        assertThat(PathRules.matches("**/src/test/**", "module/src/test/A.java")).isTrue();
        assertThat(PathRules.matches("tests/**", "tests/email.test.ts")).isTrue();
        assertThat(PathRules.matches("tests/**", "src/main/email.ts")).isFalse();
    }
    @Test void serializedIdentityAcceptsNewRoleAndOldEnumStrings() throws Exception {
        var type = com.zipe.aiteam.slack.SlackNotifier.Identity.class;
        assertThat(mapper.readValue("\"FRONTEND\"", type).name()).isEqualTo("FRONTEND");
        assertThat(mapper.readValue("\"QA\"", type)).isEqualTo(com.zipe.aiteam.slack.SlackNotifier.Identity.QA);
    }
    @Test void sameRoleCannotImplementAndIndependentlyApproveItself() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("review")).put("role", "builder");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("獨立審查");
    }
    @Test void shellInjectionInToolNameRejectedAtLoad() throws Exception {
        var config = config(); ((ObjectNode)config.path("project")).putArray("requiredTools").add("npm;curl example.com");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("requiredTools");
    }
    @Test void v2CannotOmitIndependentCommandEvidence() throws Exception {
        var config = config(); ((ObjectNode)config.path("project")).remove("verification");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("獨立驗證");
    }
    @Test void v2CannotSkipRequirementsAtPublication() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("approval")).putArray("requires")
                .add("implementation").add("frontend").add("plan").add("verification").add("review");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("需求基準");
    }
    @Test void dependencyCycleRejectedInsteadOfInfiniteRecursion() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("intake")).putArray("requires").add("plan");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("循環");
    }
    @Test void docOnlyPathsMustBeNarrowAndRelative() throws Exception {
        for (String bad : new String[]{"**", "**/*", "*", "../x/**", "/abs/**", ".git/**", "a\\b"}) {
            var config = config();
            ((ObjectNode)config.path("project").path("verification")).putArray("docOnlyPaths").add(bad);
            assertThatThrownBy(() -> validate(config)).as(bad).hasMessageContaining("docOnlyPaths");
        }
        var config = config();
        ((ObjectNode)config.path("project").path("verification")).putArray("docOnlyPaths").add(".agents/**").add("*.md").add("docs/**");
        validate(config);
        var empty = config();
        ((ObjectNode)empty.path("project").path("verification")).putArray("docOnlyPaths");
        assertThatThrownBy(() -> validate(empty)).hasMessageContaining("docOnlyPaths");
    }
    @Test void implementationCannotBypassTestPlan() throws Exception {
        var config = config(); ((ObjectNode)config.path("nodes").path("implementation")).putArray("requires").add("intake");
        assertThatThrownBy(() -> validate(config)).hasMessageContaining("實作必須相依於測試計畫");
    }
}
