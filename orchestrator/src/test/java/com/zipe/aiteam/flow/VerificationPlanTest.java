package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

class VerificationPlanTest {
    /** gradle-fullstack 範例的配置形狀：兩個後端指令、三個前端指令；文件站、DDL、設定範本由 QA 驗證。 */
    final Verification spec = new Verification(
            List.of(List.of("gradle", "a"), List.of("gradle", "b"), List.of("npm", "ci"), List.of("npm", "build"), List.of("npm", "test")),
            1800, List.of("*.md", "docs/**", ".claude/**"),
            List.of(List.of("backend/**"), List.of("backend/**"),
                    List.of("frontend/**"), List.of("frontend/**"), List.of("frontend/**")),
            List.of("docs-site/**", "docs/sql/**", ".env.example"));

    @Test void issue1044ChangeSetNeedsNoSystemCommand() {
        var selection = VerificationPlan.select(spec, List.of(".env.example", "docs-site/sidebars.ts", "docs/sql/schema.sql",
                "README.md", ".claude/rules/authentication-and-security.md", "docs-site/docs/getting-started/installation.md"));
        assertThat(selection.fullRun()).isFalse();
        for (int i = 0; i < 5; i++) assertThat(selection.needs(i)).isFalse();
    }
    @Test void buildFilesFollowTheirScopeOrQaOnlyAndRootBuildFilesRunEverything() {
        var ui = VerificationPlan.select(spec, List.of("frontend/package.json"));
        assertThat(ui.fullRun()).isFalse();
        assertThat(List.of(ui.needs(0), ui.needs(1), ui.needs(2), ui.needs(3), ui.needs(4))).containsExactly(false, false, true, true, true);
        assertThat(ui.matched().get(2)).containsExactly("frontend/package.json");
        // 使用者決定：QA 專屬路徑內的建置檔仍只由 QA 驗證（系統本來就沒有建置 docs-site 的指令）。
        assertThat(VerificationPlan.select(spec, List.of("docs-site/package.json", "docs-site/package-lock.json")).needs(0)).isFalse();
        // 不屬於任何範圍的建置檔、腳本或子專案一律全跑。
        for (String path : List.of("package.json", "scripts/drift-scan.sh", "mcp-servers/x/build.gradle.kts", "skills/a/SKILL.md"))
            assertThat(VerificationPlan.select(spec, List.of(path)).fullRun()).as(path).isTrue();
        // docOnlyPaths 底下的非純文字檔不是純文件：docs/** 的腳本全跑，docs/sql/** 例外是 QA 專屬。
        assertThat(VerificationPlan.select(spec, List.of("docs/install.sh")).fullRun()).isTrue();
        assertThat(VerificationPlan.select(spec, List.of("backend/src/main/resources/prompt.md")).needs(0)).isTrue();
    }
    /** #1055：只改部署定義、nginx 與規則文件，實際配置不再因 compose 檔誤跑後端測試。 */
    @Test void deploymentDefinitionsDoNotTriggerBackendTests(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve(".git"));
        var real = TeamCatalog.load(java.nio.file.Path.of("teams/gradle-fullstack.json"), key -> tmp.toString()).definition().project().verification();
        var selection = VerificationPlan.select(real, List.of("docs/docker/sit/docker-compose.yml",
                "frontend/nginx/default.conf", ".claude/rules/build-and-run.md", "README.md"));
        assertThat(selection.fullRun()).as(selection.fullRunReason()).isFalse();
        assertThat(selection.needs(0)).isFalse();
        assertThat(selection.needs(1)).isFalse();
        assertThat(selection.needs(4)).isTrue();
    }
    @Test void unknownOrUnscopedChangesRunEverything() {
        assertThat(VerificationPlan.select(spec, null).fullRun()).isTrue();
        assertThat(VerificationPlan.select(spec, List.of()).fullRun()).isTrue();
        var legacy = new Verification(spec.commands(), 1800, spec.docOnlyPaths());
        assertThat(VerificationPlan.select(legacy, List.of("frontend/a.ts")).fullRun()).isTrue();
    }
    @Test void fingerprintsCoverOwnScopeAndSharedFilesOnly() {
        List<String> tree = List.of("100644 s1\tbackend/A.kt", "100644 u1\tfrontend/a.ts",
                "100644 d1\tdocs-site/sidebars.ts", "100644 r1\tREADME.md", "100644 x1\tsettings.gradle");
        var base = VerificationPlan.fingerprints(spec, tree);
        var docsChanged = VerificationPlan.fingerprints(spec, List.of("100644 s1\tbackend/A.kt", "100644 u1\tfrontend/a.ts",
                "100644 d2\tdocs-site/sidebars.ts", "100644 r2\tREADME.md", "100644 x1\tsettings.gradle"));
        assertThat(docsChanged).isEqualTo(base);
        var uiChanged = VerificationPlan.fingerprints(spec, List.of("100644 s1\tbackend/A.kt", "100644 u2\tfrontend/a.ts",
                "100644 d1\tdocs-site/sidebars.ts", "100644 r1\tREADME.md", "100644 x1\tsettings.gradle"));
        assertThat(uiChanged.subList(0, 2)).isEqualTo(base.subList(0, 2));
        assertThat(uiChanged.get(2)).isNotEqualTo(base.get(2));
        // 共用檔（不屬於任何範圍）一變，所有指令的指紋都變。
        var shared = VerificationPlan.fingerprints(spec, List.of("100644 s1\tbackend/A.kt", "100644 u1\tfrontend/a.ts",
                "100644 d1\tdocs-site/sidebars.ts", "100644 r1\tREADME.md", "100644 x2\tsettings.gradle"));
        for (int i = 0; i < 5; i++) assertThat(shared.get(i)).isNotEqualTo(base.get(i));
        // 同一範圍、不同指令也各自獨立（指紋含 argv）。
        assertThat(base.get(0)).isNotEqualTo(base.get(1));
        var legacy = VerificationPlan.fingerprints(new Verification(spec.commands(), 1800), tree);
        assertThat(VerificationPlan.fingerprints(new Verification(spec.commands(), 1800), List.of("100644 r2\tREADME.md"))).isNotEqualTo(legacy);
    }
    @Test void configurationIsValidatedConservatively() {
        assertThatCode(() -> VerificationPlan.validate(spec)).doesNotThrowAnyException();
        assertThatThrownBy(() -> VerificationPlan.validate(new Verification(spec.commands(), 1800, null, List.of(List.of("a/**")), null)))
                .hasMessageContaining("一一對應");
        assertThatThrownBy(() -> VerificationPlan.validate(new Verification(spec.commands(), 1800, null, null, List.of("docs-site/**"))))
                .hasMessageContaining("一起設定");
        assertThatThrownBy(() -> VerificationPlan.validate(new Verification(spec.commands(), 1800, null, spec.commandScopes(), List.of("frontend/docs/**"))))
                .hasMessageContaining("重疊");
        assertThatThrownBy(() -> VerificationPlan.validate(new Verification(spec.commands(), 1800, null, spec.commandScopes(), List.of("**/*.sql"))))
                .hasMessageContaining("重疊");
        assertThatThrownBy(() -> VerificationPlan.validate(new Verification(spec.commands(), 1800, null,
                List.of(List.of("**"), List.of("a/**"), List.of("a/**"), List.of("a/**"), List.of("a/**")), null)))
                .hasMessageContaining("過寬");
        assertThat(VerificationPlan.mayOverlap("frontend-x/**", "frontend/**")).isFalse();
    }
}
