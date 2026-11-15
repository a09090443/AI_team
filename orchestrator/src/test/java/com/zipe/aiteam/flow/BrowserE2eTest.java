package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.zipe.aiteam.flow.TeamDefinition.*;
import static org.assertj.core.api.Assertions.*;

class BrowserE2eTest {
    @TempDir Path tmp;
    final ObjectMapper mapper = new ObjectMapper();

    ObjectNode team(String name) throws Exception { return (ObjectNode) mapper.readTree(Files.readString(Path.of("teams/" + name + ".json"))); }
    TeamDefinition expandAndValidate(ObjectNode tree) throws Exception {
        BrowserE2e.expand(tree);
        TeamDefinition d = mapper.treeToValue(tree, TeamDefinition.class);
        TeamCatalog.validate(d);
        return d;
    }
    static List<String> texts(JsonNode array) { return mapper().convertValue(array, List.class); }
    static ObjectMapper mapper() { return new ObjectMapper(); }

    @Test void oneLineExpandsIntoFixedOperationsThatPassTheNormalValidation() throws Exception {
        ObjectNode tree = team("node-web");
        ((ObjectNode) tree.path("project")).putObject("browserE2e").put("directory", ".");
        TeamDefinition d = expandAndValidate(tree);

        Operation e2e = d.project().operations().get(BrowserE2e.OP_E2E);
        Operation browsers = d.project().operations().get(BrowserE2e.OP_BROWSERS);
        assertThat(e2e.argv()).containsExactly("npm", "run", "test:e2e");
        assertThat(e2e.writes()).containsExactly("test-results", "playwright-report");
        assertThat(browsers.argv()).containsExactly("npm", "exec", "--", "playwright", "install", "--only-shell", "chromium");
        // 實作類節點拿兩個操作，驗收節點只拿 e2e。
        d.nodes().forEach((id, node) -> {
            if (node.kind() != Kind.AGENT) return;
            if (node.gate() == Gate.IMPLEMENTATION) assertThat(browsers.nodes()).contains(id);
            if (node.gate() == Gate.VERIFICATION) {
                assertThat(e2e.nodes()).contains(id);
                assertThat(browsers.nodes()).doesNotContain(id);
                assertThat(node.writablePaths()).contains("test-results/**", "playwright-report/**");
            }
        });
        assertThat(d.project().cachePaths()).contains(".cache/ms-playwright");
        assertThat(d.attachments().allow()).containsExactly("test-results/**", "playwright-report/**");
        assertThat(d.project().browserE2e()).isEqualTo(new BrowserE2ePolicy(".", null));
    }

    @Test void subdirectoryPrefixesEveryPathAndKeepsAdministratorOperations() throws Exception {
        ObjectNode tree = team("gradle-fullstack");
        ObjectNode project = (ObjectNode) tree.path("project");
        project.putObject("browserE2e").put("directory", "frontend");
        project.with("operations").set(BrowserE2e.OP_E2E, mapper.readTree("""
                {"nodes":["verification"],"directory":"frontend","argv":["npm","run","e2e:ci"],"writes":["frontend/test-results"]}"""));
        TeamDefinition d = expandAndValidate(tree);

        assertThat(d.project().operations().get(BrowserE2e.OP_E2E).argv()).containsExactly("npm", "run", "e2e:ci");
        // 在 repository 根目錄以 npm --prefix 執行，Bash(npm --prefix frontend *) 這類角色權限才涵蓋得到。
        var browsers = d.project().operations().get(BrowserE2e.OP_BROWSERS);
        assertThat(browsers.directory()).isEqualTo(".");
        assertThat(browsers.argv()).containsExactly("npm", "--prefix", "frontend", "exec", "--", "playwright", "install", "--only-shell", "chromium");
        d.nodes().values().stream().filter(n -> n.kind() == Kind.AGENT && n.gate() == Gate.VERIFICATION
                        && n.writablePaths() != null && !n.writablePaths().isEmpty())
                .forEach(n -> assertThat(n.writablePaths()).contains("frontend/test-results/**", "frontend/playwright-report/**"));
    }

    @Test void existingAttachmentPolicyIsMergedOrLeftOpen() throws Exception {
        ObjectNode merged = team("node-web");
        ((ObjectNode) merged.path("project")).putObject("browserE2e").put("directory", "web");
        merged.set("attachments", mapper.readTree("{\"maxFiles\":3,\"maxBytes\":1000,\"allow\":[\"reports/**\"],\"deny\":[]}"));
        assertThat(expandAndValidate(merged).attachments().allow())
                .containsExactly("reports/**", "web/test-results/**", "web/playwright-report/**");

        ObjectNode open = team("node-web");
        ((ObjectNode) open.path("project")).putObject("browserE2e").put("directory", "web");
        open.set("attachments", mapper.readTree("{\"maxFiles\":3,\"maxBytes\":1000}"));
        assertThat(expandAndValidate(open).attachments().allow()).isNull();
    }

    @Test void unsetConfigurationIsUntouchedAndBadDirectoriesAreRejected() throws Exception {
        ObjectNode plain = team("node-web");
        String before = plain.toString();
        BrowserE2e.expand(plain);
        assertThat(plain.toString()).isEqualTo(before);

        for (String bad : List.of("../web", "web/../..", ".git", "/abs", "a\\b", "")) {
            ObjectNode tree = team("node-web");
            ((ObjectNode) tree.path("project")).putObject("browserE2e").put("directory", bad);
            assertThatThrownBy(() -> expandAndValidate(tree)).as(bad).hasMessageContaining("project.browserE2e");
        }
    }

    @Test void readinessNeedsTheConfigFileAndTheScript() {
        BrowserE2ePolicy policy = new BrowserE2ePolicy("web", null);
        Map<String, String> files = new HashMap<>();
        assertThat(BrowserE2e.ready(policy, files::get)).isFalse();
        files.put("web/package.json", "{\"scripts\":{\"test:e2e\":\"playwright test\"}}");
        assertThat(BrowserE2e.ready(policy, files::get)).isFalse();
        files.put("web/playwright.config.ts", "export default {}");
        assertThat(BrowserE2e.ready(policy, files::get)).isTrue();
        files.put("web/package.json", "{\"scripts\":{\"test\":\"vitest\"}}");
        assertThat(BrowserE2e.ready(policy, files::get)).isFalse();
        files.put("web/package.json", "not json");
        assertThat(BrowserE2e.ready(policy, files::get)).isFalse();
    }

    @Test void prefixScopedRolePermissionCoversTheExpandedOperations() throws Exception {
        ObjectNode tree = team("gradle-fullstack");
        ((ObjectNode) tree.path("project")).putObject("browserE2e").put("directory", "web");
        TeamDefinition d = expandAndValidate(tree);
        var runtime = new com.zipe.aiteam.runtime.ClaudeCodeRuntime(null, null);
        List<String> tools = List.of("Bash(npm --prefix web *)");
        assertThat(runtime.permitsCommand(tools, d.project().operations().get(BrowserE2e.OP_BROWSERS).argv())).isTrue();
        assertThat(runtime.permitsCommand(tools, d.project().operations().get(BrowserE2e.OP_E2E).argv())).isTrue();
    }

    @Test void createsOnlyItsOwnBrowserCacheAndNeverThroughASymlink() throws Exception {
        ObjectNode tree = team("node-web");
        ((ObjectNode) tree.path("project")).putObject("browserE2e").put("directory", ".");
        TeamDefinition d = expandAndValidate(tree);
        Path home = Files.createDirectories(tmp.resolve("home"));
        BrowserE2e.ensureCache(home, d);
        assertThat(home.resolve(".cache/ms-playwright")).isDirectory();
        assertThat(home.resolve(".npm")).doesNotExist();

        Path other = Files.createDirectories(tmp.resolve("other"));
        Path linked = Files.createDirectories(tmp.resolve("linked"));
        try { Files.createSymbolicLink(linked.resolve(".cache"), other); }
        catch (UnsupportedOperationException | java.io.IOException noSymlinks) { return; }
        BrowserE2e.ensureCache(linked, d);
        assertThat(other.resolve("ms-playwright")).doesNotExist();

        TeamDefinition plain = mapper.treeToValue(team("node-web"), TeamDefinition.class);
        Path untouched = Files.createDirectories(tmp.resolve("untouched"));
        BrowserE2e.ensureCache(untouched, plain);
        assertThat(untouched.resolve(".cache")).doesNotExist();
    }

    @Test void keepsOnlyRegularPngScreenshotsWithinTheLimitsAndNamesThemByTest() throws Exception {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path results = Files.createDirectories(repo.resolve("web/test-results"));
        for (String test : List.of("form-blank-chromium", "form-valid-chromium", "list-chromium")) {
            Path dir = Files.createDirectories(results.resolve(test));
            Files.writeString(dir.resolve("test-finished-1.png"), "png");
            Files.writeString(dir.resolve("trace.zip"), "zip");
        }
        Files.write(results.resolve("empty.png"), new byte[0]);
        try { Files.createSymbolicLink(results.resolve("linked.png"), results.resolve("list-chromium/test-finished-1.png")); }
        catch (UnsupportedOperationException | java.io.IOException noSymlinks) { /* Windows 無權限時略過這一項 */ }
        Path target = tmp.resolve("kept");
        var limits = new AttachmentPolicy(2, 1024, List.of(), List.of());

        List<Path> kept = BrowserE2e.collectScreenshots(repo, new BrowserE2ePolicy("web", null), target, limits);
        assertThat(kept).containsExactly(target.resolve("form-blank-chromium/test-finished-1.png"),
                target.resolve("form-valid-chromium/test-finished-1.png"));

        var candidates = BrowserE2e.screenshotCandidates(target);
        assertThat(candidates).extracting(Attachments.Candidate::name).containsExactly("form-blank-chromium.png", "form-valid-chromium.png");
        assertThat(candidates.getFirst().title()).isEqualTo("form-blank-chromium（test-finished-1.png）");
        assertThat(BrowserE2e.collectScreenshots(tmp.resolve("missing"), new BrowserE2ePolicy("web", null), target, null)).isEmpty();
        assertThat(BrowserE2e.screenshotNote("7", "629f00c5088", 0)).contains("screenshot: 'on'");
    }

    @Test void attachmentPromptLeavesE2eScreenshotsToTheSystem() throws Exception {
        // #1052：實作與 QA 各自附了同一批截圖，加上系統貼的共三份。
        ObjectNode tree = team("gradle-fullstack");
        ((ObjectNode) tree.path("project")).putObject("browserE2e").put("directory", "web");
        TeamDefinition d = expandAndValidate(tree);
        FlowRun flow = new FlowRun(new Snapshot("rev", d, tmp.toString(), Map.of()));
        assertThat(Attachments.notice(flow)).contains("web/test-results").contains("由系統統一貼到討論串").contains("不要再把它們列進 attachments");

        flow.dialogue = true;
        assertThat(Attachments.e2eScreenshotNotice(flow)).isEmpty();
    }

    @Test void onlyARunOfTheE2eCommandCounts() {
        var policy = new BrowserE2ePolicy("web", null);
        List<String> e2e = List.of("npm", "--prefix", "web", "run", "test:e2e");
        var run = new VerificationPlan.Decision(0, e2e, VerificationPlan.Action.RUN, "r", List.of(), "f", null, null, null);
        var skip = new VerificationPlan.Decision(0, e2e, VerificationPlan.Action.SKIP, "r", List.of(), "f", null, null, null);
        assertThat(BrowserE2e.ran(List.of(run), policy)).isTrue();
        assertThat(BrowserE2e.ran(List.of(skip), policy)).isFalse();
        assertThat(BrowserE2e.ran(null, policy)).isFalse();
    }

    @Test void directoryReaderStaysInsideTheRoot() throws Exception {
        Files.createDirectories(tmp.resolve("web"));
        Files.writeString(tmp.resolve("web/playwright.config.ts"), "x");
        var reader = BrowserE2e.directoryReader(tmp);
        assertThat(reader.apply("web/playwright.config.ts")).isEqualTo("x");
        assertThat(reader.apply("web/missing")).isNull();
        assertThat(reader.apply("../outside")).isNull();
    }

    @Test void verificationAppendsOnlyWhenReadyAndScopesToTheFrontend() {
        BrowserE2ePolicy policy = new BrowserE2ePolicy("web", null);
        Verification scoped = new Verification(List.of(List.of("mvn", "test")), 600, null, List.of(List.of("src/**")), null);
        assertThat(BrowserE2e.effective(scoped, policy, false)).isSameAs(scoped);

        Verification ready = BrowserE2e.effective(scoped, policy, true);
        assertThat(ready.commands()).containsExactly(List.of("mvn", "test"),
                List.of("npm", "--prefix", "web", "exec", "--", "playwright", "install", "--only-shell", "chromium"),
                List.of("npm", "--prefix", "web", "run", "test:e2e"));
        assertThat(ready.commandScopes()).containsExactly(List.of("src/**"), List.of("web/**"), List.of("web/**"));

        Verification unscoped = new Verification(List.of(List.of("npm", "run", "test:e2e")), 600);
        Verification root = BrowserE2e.effective(unscoped, new BrowserE2ePolicy(".", null), true);
        assertThat(root.commands()).containsExactly(List.of("npm", "run", "test:e2e"),
                List.of("npm", "exec", "--", "playwright", "install", "--only-shell", "chromium"));
        assertThat(root.commandScopes()).isNull();
        assertThat(BrowserE2e.effective(unscoped, null, true)).isSameAs(unscoped);
    }

    @Test void planOfferFieldIsRequiredNullableAndParsed() throws Exception {
        JsonNode schema = mapper.readTree(BrowserE2e.extendSchema("{\"type\":\"object\",\"properties\":{},\"required\":[]}"));
        assertThat(texts(schema.path("required"))).containsExactly("browser_e2e_offer");
        assertThat(schema.path("properties").path("browser_e2e_offer").path("type").toString()).contains("null");

        assertThat(BrowserE2e.offerReason(mapper.readTree("{\"browser_e2e_offer\":null}"))).isNull();
        assertThat(BrowserE2e.offerReason(mapper.readTree("{\"browser_e2e_offer\":{\"reason\":\"  \"}}"))).isNull();
        assertThat(BrowserE2e.offerReason(mapper.readTree("{\"browser_e2e_offer\":{\"reason\":\"列表頁\"}}"))).isEqualTo("列表頁");
    }

    @Test void noticesAndRequestTextUseTheConfiguredDirectory() {
        BrowserE2ePolicy policy = new BrowserE2ePolicy("web", false);
        assertThat(policy.offers()).isFalse();
        assertThat(new BrowserE2ePolicy("web", null).offers()).isTrue();
        assertThat(BrowserE2e.planNotice(null, true)).isEmpty();
        assertThat(BrowserE2e.planNotice(policy, true)).contains("已就緒").contains("`e2e`")
                // #1050：QA 不知道截圖由系統保留，把「每個測試都有截圖」判成無法驗收。
                .contains("e2eScreenshots").contains("不需要另外的檢查操作");
        assertThat(BrowserE2e.planNotice(policy, false)).contains("尚未加入 Playwright").contains("不要引用");
        assertThat(BrowserE2e.requestText(policy, "42", "列表頁"))
                .contains("web/package.json").contains("web/playwright.config.ts").contains("page.route")
                .contains("任務 #42").endsWith("列表頁");
    }
}
