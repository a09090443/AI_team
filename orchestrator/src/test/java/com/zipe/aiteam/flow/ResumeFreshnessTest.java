package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeFreshnessTest {
    @TempDir Path tmp;

    @Test void idleIsMeasuredFromTranscriptModificationTime() throws Exception {
        Path transcript = Files.writeString(tmp.resolve("s.jsonl"), "{}");
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        Files.setLastModifiedTime(transcript, FileTime.from(now.minus(Duration.ofMinutes(90))));
        Optional<Long> idle = ResumeFreshness.idleMinutes(Optional.of(transcript), now);
        assertThat(idle).contains(90L);
        assertThat(ResumeFreshness.stale(idle, 60)).isTrue();
        assertThat(ResumeFreshness.stale(idle, 120)).isFalse();
        assertThat(ResumeFreshness.stale(idle, 0)).as("0 表示停用，一律續用").isFalse();
    }

    @Test void unknownTranscriptKeepsResuming() {
        Optional<Long> idle = ResumeFreshness.idleMinutes(Optional.empty(), Instant.now());
        assertThat(idle).isEmpty();
        assertThat(ResumeFreshness.stale(idle, 60)).isFalse();
        assertThat(ResumeFreshness.idleMinutes(Optional.of(tmp.resolve("missing.jsonl")), Instant.now())).isEmpty();
    }

    @Test void handOverCopiesTranscriptAndExcludesItFromGit() throws Exception {
        Path repo = tmp.resolve("repo"); Files.createDirectories(repo.resolve(".git/info"));
        Files.writeString(repo.resolve(".git/info/exclude"), "/.ai-team-inbox/\n");
        Path transcript = Files.writeString(tmp.resolve("s.jsonl"), "{\"type\":\"user\"}\n");
        String copied = ResumeFreshness.handOver(repo, transcript, "abc-123");
        assertThat(copied).isEqualTo(".ai-team-context/previous-session-abc-123.jsonl");
        assertThat(Files.readString(repo.resolve(copied))).contains("\"type\":\"user\"");
        String exclude = Files.readString(repo.resolve(".git/info/exclude"));
        assertThat(exclude).contains("/.ai-team-inbox/", "/.ai-team-context/");
        ResumeFreshness.handOver(repo, transcript, "abc-123");
        assertThat(Files.readString(repo.resolve(".git/info/exclude")).split("/.ai-team-context/", -1)).hasSize(2);
    }

    @Test void refusesSymlinkedContextDirectoryAndOddSessionIds() throws Exception {
        Path repo = tmp.resolve("repo"); Files.createDirectories(repo);
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path transcript = Files.writeString(tmp.resolve("s.jsonl"), "{}");
        assertThatThrownBy(() -> ResumeFreshness.handOver(repo, transcript, "../x")).isInstanceOf(IOException.class);
        try {
            Files.createSymbolicLink(repo.resolve(ResumeFreshness.DIR), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // Windows 沒有建立符號連結的權限時略過這段
        }
        assertThatThrownBy(() -> ResumeFreshness.handOver(repo, transcript, "abc")).hasMessageContaining("符號連結");
        assertThat(outside).isEmptyDirectory();
    }

    @Test void runtimesLocateTheirOwnSessionTranscripts() throws Exception {
        Path claude = tmp.resolve(".claude/projects/-home-x-repo");
        Files.createDirectories(claude);
        Path claudeFile = Files.writeString(claude.resolve("11111111-2222.jsonl"), "{}");
        assertThat(com.zipe.aiteam.runtime.CommandRuns.claudeTranscript(tmp.resolve(".claude"), "11111111-2222")).contains(claudeFile);
        Path day = Files.createDirectories(tmp.resolve(".codex/sessions/2026/11/07"));
        Path codexFile = Files.writeString(day.resolve("rollout-2026-11-07T10-33-00-019a-thread.jsonl"), "{}");
        assertThat(com.zipe.aiteam.runtime.CommandRuns.codexTranscript(tmp.resolve(".codex"), "019a-thread")).contains(codexFile);
        assertThat(com.zipe.aiteam.runtime.CommandRuns.codexTranscript(tmp.resolve(".codex"), "other")).isEmpty();
        assertThat(com.zipe.aiteam.runtime.CommandRuns.claudeTranscript(tmp.resolve(".claude"), "../etc")).isEmpty();
    }

    @Test void noticeTellsAgentToVerifyWorkspaceAndGrepTheTranscript() {
        assertThat(ResumeFreshness.notice(130, ".ai-team-context/previous-session-x.jsonl"))
                .contains("130 分鐘", "git status", ".ai-team-context/previous-session-x.jsonl", "grep", "不要整份讀進來");
        assertThat(ResumeFreshness.notice(130, null)).contains("無法複製");
    }
}
