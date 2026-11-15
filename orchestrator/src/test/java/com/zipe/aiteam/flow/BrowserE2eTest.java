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
        assertThat(d.project().operations().get(BrowserE2e.OP_BROWSERS).directory()).isEqualTo("frontend");
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
        assertThat(BrowserE2e.planNotice(policy, true)).contains("已就緒").contains("`e2e`");
        assertThat(BrowserE2e.planNotice(policy, false)).contains("尚未加入 Playwright").contains("不要引用");
        assertThat(BrowserE2e.requestText(policy, "42", "列表頁"))
                .contains("web/package.json").contains("web/playwright.config.ts").contains("page.route")
                .contains("任務 #42").endsWith("列表頁");
    }
}
