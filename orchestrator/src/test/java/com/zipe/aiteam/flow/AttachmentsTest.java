package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

class AttachmentsTest {
    @TempDir Path tmp;
    static final ObjectMapper JSON = new ObjectMapper();
    Snapshot plain;
    Path work;

    @BeforeEach void setup() throws Exception {
        Files.createDirectories(tmp.resolve(".git"));
        plain = TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString());
        work = Files.createDirectories(tmp.resolve("work"));
    }

    static Snapshot withAttachments(Snapshot original, AttachmentPolicy policy) throws Exception {
        ObjectNode d = JSON.valueToTree(original.definition());
        d.set("attachments", JSON.valueToTree(policy));
        TeamDefinition definition = JSON.treeToValue(d, TeamDefinition.class);
        TeamCatalog.validate(definition);
        return new Snapshot(original.revision() + "-attach", definition, original.repository(), original.assets());
    }
    static AttachmentPolicy policy(List<String> allow, List<String> deny) { return new AttachmentPolicy(3, 1024, allow, deny); }
    ObjectNode report(String... paths) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode items = r.putArray("attachments");
        for (String p : paths) items.addObject().put("path", p).put("title", "");
        return r;
    }

    @Test void oldSnapshotsKeepSchemaAndPromptUnchanged() throws Exception {
        FlowRun old = new FlowRun(plain);
        String schema = plain.assets().get(plain.definition().nodes().get("plan").schema());
        assertThat(Attachments.schema(schema, old)).isEqualTo(schema);
        assertThat(Attachments.notice(old)).isEmpty();
        assertThat(JSON.writeValueAsString(plain.definition())).doesNotContain("attachments");
    }

    @Test void enabledSnapshotAddsRequiredBoundedField() throws Exception {
        FlowRun flow = new FlowRun(withAttachments(plain, policy(null, null)));
        flow.dialogue = true;
        String schema = "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}";
        JsonNode extended = JSON.readTree(Attachments.schema(schema, flow));
        assertThat(extended.path("required").toString()).contains("attachments");
        assertThat(extended.path("properties").path("attachments").path("maxItems").asInt()).isEqualTo(3);
        assertThat(Attachments.notice(flow)).contains("attachments", "最多 3 個");
    }

    @Test void catalogRejectsUnboundedPolicy() {
        assertThatThrownBy(() -> withAttachments(plain, new AttachmentPolicy(11, 1024, null, null))).hasMessageContaining("maxFiles");
        assertThatThrownBy(() -> withAttachments(plain, new AttachmentPolicy(1, Attachments.MAX_BYTES + 1, null, null))).hasMessageContaining("maxBytes");
        assertThatThrownBy(() -> withAttachments(plain, policy(List.of("../x/**"), null))).hasMessageContaining("不安全");
    }

    @Test void acceptsReportFilesInsideWorkspace() throws Exception {
        Files.createDirectories(work.resolve("out"));
        Files.writeString(work.resolve("out/report.csv"), "a,b\n1,2\n");
        ObjectNode r = report("out/report.csv");
        ((ObjectNode) r.path("attachments").get(0)).put("title", "月報");
        var selection = Attachments.select(r, work, policy(null, null));
        assertThat(selection.rejected()).isEmpty();
        assertThat(selection.accepted()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("report.csv");
            assertThat(c.title()).isEqualTo("月報");
        });
    }

    @Test void rejectsEscapesSecretsAndLimits() throws Exception {
        Files.writeString(work.resolve(".env"), "A=1\n");
        Files.writeString(work.resolve("leak.txt"), "token: ghp_abcdefghijklmnop1234\n");
        Files.write(work.resolve("big.bin"), new byte[2048]);
        Files.writeString(work.resolve("empty.txt"), "");
        Files.writeString(tmp.resolve("outside.txt"), "x");
        var selection = Attachments.select(report("../outside.txt", "/etc/passwd", ".env", "leak.txt", "big.bin", "missing.txt", "empty.txt"),
                work, new AttachmentPolicy(10, 1024, null, null));
        assertThat(selection.accepted()).isEmpty();
        assertThat(String.join("\n", selection.rejected()))
                .contains("../outside.txt（路徑必須", "/etc/passwd（路徑必須", ".env（屬於禁止", "leak.txt（內容疑似含憑證",
                        "big.bin（超過單檔", "missing.txt（檔案不存在", "empty.txt（空檔案");
    }

    @Test void allowAndDenyOnlyNarrow() throws Exception {
        Files.writeString(work.resolve("a.csv"), "1");
        Files.writeString(work.resolve("b.txt"), "2");
        Files.writeString(work.resolve("c.pem"), "3");
        var selection = Attachments.select(report("a.csv", "b.txt", "c.pem"), work, policy(List.of("**/*.csv", "**/*.pem"), List.of("a.*")));
        assertThat(selection.accepted()).isEmpty();
        assertThat(String.join("\n", selection.rejected())).contains("a.csv（屬於禁止", "b.txt（不在允許", "c.pem（屬於禁止");
    }

    @Test void capsCountAndDeduplicates() throws Exception {
        for (String name : List.of("1.txt", "2.txt", "3.txt", "4.txt")) Files.writeString(work.resolve(name), name);
        var selection = Attachments.select(report("1.txt", "./1.txt", "2.txt", "3.txt", "4.txt"), work, policy(null, null));
        assertThat(selection.accepted()).extracting(Attachments.Candidate::name).containsExactly("1.txt", "2.txt", "3.txt");
        assertThat(selection.rejected()).singleElement().asString().contains("4.txt", "上限");
    }

    @Test void rejectsSymlinks() throws Exception {
        Files.writeString(tmp.resolve("secret.txt"), "x");
        try {
            Files.createSymbolicLink(work.resolve("link.txt"), tmp.resolve("secret.txt"));
        } catch (UnsupportedOperationException | java.io.IOException noPrivilege) {
            Assumptions.abort("此環境不能建立符號連結");
        }
        var selection = Attachments.select(report("link.txt"), work, policy(null, null));
        assertThat(selection.rejected()).singleElement().asString().contains("符號連結");
    }

    @Test void noteExplainsMissingScope() {
        var selection = new Attachments.Selection(List.of(new Attachments.Candidate(work.resolve("a.csv"), "a.csv", "a")), List.of("b.txt（檔案不存在）"));
        assertThat(Attachments.note(selection, "missing_scope")).contains("a.csv", "files:write", "b.txt（檔案不存在）");
        assertThat(Attachments.note(new Attachments.Selection(selection.accepted(), List.of()), null)).isEmpty();
    }
}
