package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ServiceArtifactsTest {
    @TempDir Path tmp;
    static final List<String> WRITES = List.of("deploy/**");

    Path workspace(String name, String... files) throws Exception {
        Path ws = tmp.resolve(name);
        for (int i = 0; i < files.length; i += 2) {
            Files.createDirectories(ws.resolve(files[i]).getParent());
            Files.writeString(ws.resolve(files[i]), files[i + 1]);
        }
        Files.createDirectories(ws);
        return ws;
    }

    @Test void savesOnlyWritableNonSecretFilesAndRestoresMissingOnes() throws Exception {
        Path home = tmp.resolve("home");
        Path done = workspace("done", "deploy/sandbox/start.sh", "echo start", "deploy/.env.sit", "SECRET=1",
                "deploy/.secrets/db", "x", "src/App.java", "class App {}");
        var saved = ServiceArtifacts.save(home, "sit", "1059", "abc", done,
                List.of("deploy/sandbox/start.sh", "deploy/.env.sit", "deploy/.secrets/db", "src/App.java", "deploy/gone.sh"), WRITES);
        assertThat(saved.files()).containsExactly("deploy/sandbox/start.sh");

        Path next = workspace("next", "deploy/keep.sh", "repo version");
        var restored = ServiceArtifacts.restore(home, "sit", next);
        assertThat(restored.added()).containsExactly("deploy/sandbox/start.sh");
        assertThat(restored.sourceTask()).isEqualTo("1059");
        assertThat(Files.readString(next.resolve("deploy/sandbox/start.sh"))).isEqualTo("echo start");
        assertThat(ServiceArtifacts.notice(restored)).contains("#1059", "deploy/sandbox/start.sh");
        assertThat(ServiceArtifacts.restore(home, "sit", next).added()).isEmpty();
    }

    @Test void repoVersionWinsOverSavedFile() throws Exception {
        Path home = tmp.resolve("home");
        ServiceArtifacts.save(home, "sit", "1", "abc", workspace("a", "deploy/start.sh", "saved"), List.of("deploy/start.sh"), WRITES);
        Path next = workspace("b", "deploy/start.sh", "repo");
        var restored = ServiceArtifacts.restore(home, "sit", next);
        assertThat(restored.added()).isEmpty();
        assertThat(restored.differing()).containsExactly("deploy/start.sh");
        assertThat(Files.readString(next.resolve("deploy/start.sh"))).isEqualTo("repo");
    }

    @Test void nothingChangedKeepsPreviousCopy() throws Exception {
        Path home = tmp.resolve("home");
        ServiceArtifacts.save(home, "sit", "1", "abc", workspace("a", "deploy/start.sh", "v1"), List.of("deploy/start.sh"), WRITES);
        assertThat(ServiceArtifacts.save(home, "sit", "2", "def", workspace("b"), List.of("src/App.java"), WRITES)).isNull();
        assertThat(ServiceArtifacts.restore(home, "sit", workspace("c")).sourceTask()).isEqualTo("1");
    }

    @Test void newerSuccessReplacesWholeCopy() throws Exception {
        Path home = tmp.resolve("home");
        ServiceArtifacts.save(home, "sit", "1", "abc", workspace("a", "deploy/old.sh", "old"), List.of("deploy/old.sh"), WRITES);
        ServiceArtifacts.save(home, "sit", "2", "def", workspace("b", "deploy/new.sh", "new"), List.of("deploy/new.sh"), WRITES);
        var restored = ServiceArtifacts.restore(home, "sit", workspace("c"));
        assertThat(restored.added()).containsExactly("deploy/new.sh");
        assertThat(Files.exists(home.resolve("service-context/sit.files.old"))).isFalse();
    }

    @Test void tamperedOrUnsafeManifestRestoresNothing() throws Exception {
        Path home = tmp.resolve("home");
        ServiceArtifacts.save(home, "sit", "1", "abc", workspace("a", "deploy/start.sh", "v1"), List.of("deploy/start.sh"), WRITES);
        Files.writeString(home.resolve("service-context/sit.files/files/deploy/start.sh"), "changed");
        Path next = workspace("b");
        assertThat(ServiceArtifacts.restore(home, "sit", next)).isNull();
        assertThat(Files.exists(next.resolve("deploy/start.sh"))).isFalse();

        var mapper = new ObjectMapper();
        Path manifest = home.resolve("service-context/sit.files/manifest.json");
        ObjectNode data = (ObjectNode) mapper.readTree(manifest.toFile());
        ((ObjectNode) data.path("files").get(0)).put("path", "../escape.sh");
        Files.writeString(manifest, data.toString());
        assertThat(ServiceArtifacts.restore(home, "sit", next)).isNull();
        assertThat(Files.exists(tmp.resolve("escape.sh"))).isFalse();
    }

    @Test void oversizedOutputIsNotSaved() throws Exception {
        Path home = tmp.resolve("home");
        Path ws = workspace("a", "deploy/big.bin", "x".repeat((int) ServiceArtifacts.MAX_BYTES + 1));
        assertThatThrownBy(() -> ServiceArtifacts.save(home, "sit", "1", "abc", ws, List.of("deploy/big.bin"), WRITES))
                .hasMessageContaining("上限");
        assertThat(Files.exists(home.resolve("service-context/sit.files"))).isFalse();
    }

    @Test void invalidTargetNameIsRejected() throws Exception {
        assertThatThrownBy(() -> ServiceArtifacts.restore(tmp, "../x", tmp)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ServiceArtifacts.restore(tmp, "missing", tmp)).isNull();
    }
}
