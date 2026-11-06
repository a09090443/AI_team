package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RulesProposalTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    final String path = ".claude/rules/guide.md";
    final RulesProposal.Original original = new RulesProposal.Original(path, "hash", "old");

    @Test void onlyMarkdownRulesAreEligible() {
        assertThat(RulesProposal.safePath(path)).isTrue();
        for (String bad : List.of(".claude/rules/../settings.md", ".claude/rules/a.sh", ".claude/skills/a.md",
                ".claude/settings.json", ".claude/rules//x.md", ".claude/rules/x.md:stream", ".claude/rules/x\n.md", "/tmp/x.md"))
            assertThat(RulesProposal.safePath(bad)).as(bad).isFalse();
        assertThat(RulesProposal.protectedBy(List.of(".claude/rules"), path)).isTrue();
        assertThat(RulesProposal.protectedBy(List.of(".claude/**"), path)).isTrue();
        assertThat(RulesProposal.protectedBy(List.of("src/**"), path)).isFalse();
    }
    com.fasterxml.jackson.databind.node.ObjectNode proposal(String target, String hash, String content) {
        var value = json.createObjectNode(); value.putArray("changes").addObject().put("path", target).put("sha256", hash).put("content", content);
        return value;
    }
    @Test void proposalsMustCoverExactlyTheAuthorizedFilesAndOriginalHashes() throws Exception {
        assertThat(RulesProposal.validate(proposal(path, "hash", "new"), List.of(original))).containsOnlyKeys(path);
        assertThatThrownBy(() -> RulesProposal.validate(proposal(path, "stale", "new"), List.of(original))).hasMessageContaining("雜湊");
        assertThatThrownBy(() -> RulesProposal.validate(proposal(".claude/rules/extra.md", "hash", "new"), List.of(original))).hasMessageContaining("路徑");
        var duplicate = proposal(path, "hash", "new"); duplicate.withArray("changes").add(duplicate.path("changes").get(0).deepCopy());
        assertThatThrownBy(() -> RulesProposal.validate(duplicate, List.of(original))).hasMessageContaining("檔案數");
        var shell = proposal(path, "hash", "new"); shell.put("command", "sh");
        assertThatThrownBy(() -> RulesProposal.validate(shell, List.of(original))).hasMessageContaining("格式");
        assertThatThrownBy(() -> RulesProposal.validate(proposal(path, "hash", "x".repeat(65537)), List.of(original))).hasMessageContaining("上限");
        assertThatThrownBy(() -> RulesProposal.validate(proposal(path, "hash", ""), List.of(original))).hasMessageContaining("空白");
    }
    void securePlatform() throws Exception {
        try (var directory = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(directory instanceof SecureDirectoryStream<?>, "Linux secure directory streams required");
        }
        Files.createDirectories(root.resolve(".claude/rules")); Files.writeString(root.resolve(path), "old");
    }
    @Test void writesLiteralDataAndRejectsStaleOriginalWithoutAnyOtherChange() throws Exception {
        securePlatform(); var before = RulesProposal.capture(root, List.of(path));
        var payload = "文件範例：$(touch SHOULD_NOT_EXIST)\n";
        var changes = RulesProposal.validate(proposal(path, before.getFirst().sha256(), payload), before);
        assertThat(RulesProposal.apply(root, before, changes)).containsExactly(path);
        assertThat(Files.readString(root.resolve(path))).isEqualTo(payload);
        assertThat(root.resolve("SHOULD_NOT_EXIST")).doesNotExist();
        assertThatThrownBy(() -> RulesProposal.apply(root, before, changes)).hasMessageContaining("原文已變更");
        assertThat(Files.readString(root.resolve(path))).isEqualTo(payload);
        try (var files = Files.list(root.resolve(".claude/rules"))) { assertThat(files.toList()).containsExactly(root.resolve(path)); }
    }
    @Test void rejectsParentAndFileSymlinksAndDoesNotModifyOutsideTargets() throws Exception {
        securePlatform(); Path outside = root.resolve("outside.md"); Files.writeString(outside, "outside");
        Files.delete(root.resolve(path)); Files.createSymbolicLink(root.resolve(path), outside);
        assertThatThrownBy(() -> RulesProposal.capture(root, List.of(path))).isInstanceOf(java.io.IOException.class);
        Files.delete(root.resolve(path)); Files.delete(root.resolve(".claude/rules"));
        Files.createSymbolicLink(root.resolve(".claude/rules"), root);
        assertThatThrownBy(() -> RulesProposal.capture(root, List.of(".claude/rules/outside.md"))).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(outside)).isEqualTo("outside");
    }
    @Test void allOriginalsAreCheckedBeforeAnyWriteAndHardlinksAreReplacedNotTruncated() throws Exception {
        securePlatform(); String second = ".claude/rules/second.md";
        Files.writeString(root.resolve(second), "second");
        var before = RulesProposal.capture(root, List.of(path, second));
        var changes = new LinkedHashMap<String, byte[]>(); changes.put(path, "new".getBytes()); changes.put(second, "new2".getBytes());
        Files.writeString(root.resolve(second), "stale");
        assertThatThrownBy(() -> RulesProposal.apply(root, before, changes)).hasMessageContaining("原文已變更");
        assertThat(Files.readString(root.resolve(path))).isEqualTo("old");
        Path outside = root.resolve("outside.md"); Files.createLink(outside, root.resolve(path));
        var one = RulesProposal.capture(root, List.of(path));
        RulesProposal.apply(root, one, Map.of(path, "new".getBytes()));
        assertThat(Files.readString(outside)).isEqualTo("old");
        assertThat(Files.readString(root.resolve(path))).isEqualTo("new");
    }
}
