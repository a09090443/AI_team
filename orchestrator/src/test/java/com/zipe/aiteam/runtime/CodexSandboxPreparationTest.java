package com.zipe.aiteam.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class CodexSandboxPreparationTest {
    @TempDir Path tmp;
    @Test void createsEmptyMountPointsAndPreservesExistingContents() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        CodexSandboxPreparation.prepare(tmp);
        assertThat(tmp.resolve(".agents")).isEmptyDirectory();
        assertThat(tmp.resolve(".codex")).isEmptyDirectory();
        Files.writeString(tmp.resolve(".agents/keep"), "unchanged");
        CodexSandboxPreparation.prepare(tmp);
        assertThat(Files.readString(tmp.resolve(".agents/keep"))).isEqualTo("unchanged");
    }
    @Test void refusesMissingGitWithoutCreatingFakeRepository() {
        assertThatThrownBy(() -> CodexSandboxPreparation.prepare(tmp)).hasMessageContaining("假 repository");
        assertThat(tmp).isEmptyDirectory();
    }
    @Test void refusesFileBeforeCreatingEitherDirectory() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Files.writeString(tmp.resolve(".codex"), "keep");
        assertThatThrownBy(() -> CodexSandboxPreparation.prepare(tmp)).hasMessageContaining("拒絕覆寫");
        assertThat(tmp.resolve(".agents")).doesNotExist();
        assertThat(Files.readString(tmp.resolve(".codex"))).isEqualTo("keep");
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void refusesSymlinkAndWorktreeRootAlias() throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.createSymbolicLink(tmp.resolve(".agents"), outside);
        assertThatThrownBy(() -> CodexSandboxPreparation.prepare(tmp)).hasMessageContaining("拒絕覆寫");
        assertThat(outside).isEmptyDirectory();
        Path alias = Files.createSymbolicLink(tmp.resolve("alias"), tmp);
        assertThatThrownBy(() -> CodexSandboxPreparation.prepare(alias)).hasMessageContaining("canonical");
    }
}
