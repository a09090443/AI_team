package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.publish.PullRequestPublisher.PullRequestState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PullRequestSyncTest {
    @org.junit.jupiter.api.io.TempDir Path tmp;
    TeamDefinition.Snapshot load(String file) throws Exception {
        java.nio.file.Files.createDirectories(tmp.resolve("repo/.git"));
        return TeamCatalog.load(Path.of("teams/" + file), key -> tmp.resolve("repo").toString());
    }
    static FlowRun published(TeamDefinition.Snapshot snapshot) {
        FlowRun flow = new FlowRun(snapshot);
        flow.prUrl = "https://github.com/o/r/pull/6"; flow.branch = "feature/ai-team-task-1055"; flow.publishedHead = "a".repeat(40);
        return flow;
    }
    static PullRequestState pr(String state, Boolean mergeable, String mergeableState, String headSha, String base) {
        return new PullRequestState(6, state, false, mergeable, mergeableState, "feature/ai-team-task-1055", headSha, base);
    }

    @Test void conflictOnTheSystemsOwnHeadSyncsUntilRoundsAreUsed() throws Exception {
        var flow = published(load("bestpartner.json"));
        var conflicting = pr("open", false, "dirty", flow.publishedHead, "dev");
        assertThat(PullRequestSync.assess(flow, conflicting, 2)).isEqualTo(PullRequestSync.Action.SYNC);
        flow.pullRequestSyncRounds = 2;
        assertThat(PullRequestSync.assess(flow, conflicting, 2)).isEqualTo(PullRequestSync.Action.EXHAUSTED);
    }

    @Test void someoneElsesPushOrDifferentBaseIsNeverOverwritten() throws Exception {
        var flow = published(load("bestpartner.json"));
        assertThat(PullRequestSync.assess(flow, pr("open", false, "dirty", "b".repeat(40), "dev"), 2)).isEqualTo(PullRequestSync.Action.MISMATCH);
        assertThat(PullRequestSync.assess(flow, pr("open", false, "dirty", flow.publishedHead, "master"), 2)).isEqualTo(PullRequestSync.Action.MISMATCH);
    }

    @Test void closedComputingAndMergeablePullRequestsNeedNothing() throws Exception {
        var flow = published(load("bestpartner.json"));
        assertThat(PullRequestSync.assess(flow, pr("closed", null, "", flow.publishedHead, "dev"), 2)).isEqualTo(PullRequestSync.Action.CLOSED);
        assertThat(PullRequestSync.assess(flow, new PullRequestState(6, "closed", true, null, "", "x", "y", "dev"), 2)).isEqualTo(PullRequestSync.Action.CLOSED);
        assertThat(PullRequestSync.assess(flow, pr("open", null, "unknown", flow.publishedHead, "dev"), 2)).isEqualTo(PullRequestSync.Action.NONE);
        assertThat(PullRequestSync.assess(flow, pr("open", true, "clean", flow.publishedHead, "dev"), 2)).isEqualTo(PullRequestSync.Action.NONE);
        // 推回後才需要 GitHub 的確認；確認過一次就不再說。
        flow.pullRequestNotice = PullRequestSync.pushedNotice(flow.publishedHead);
        assertThat(PullRequestSync.assess(flow, pr("open", true, "clean", flow.publishedHead, "dev"), 2)).isEqualTo(PullRequestSync.Action.CONFIRMED);
        flow.pullRequestNotice = PullRequestSync.confirmedNotice(flow.publishedHead);
        assertThat(PullRequestSync.assess(flow, pr("open", true, "clean", flow.publishedHead, "dev"), 2)).isEqualTo(PullRequestSync.Action.NONE);
    }

    @Test void theFirstImplementationOnTheMainLineResolvesConflictsInEveryConfiguration() throws Exception {
        assertThat(PullRequestSync.implementationNode(load("bestpartner.json").definition())).isEqualTo("implementation");
        // node-web 的實作分兩段（implementation → frontend），由第一段解衝突。
        assertThat(PullRequestSync.implementationNode(load("node-web.json").definition())).isEqualTo("implementation");
        assertThat(PullRequestSync.implementationNode(load("spring-starters.json").definition())).isNotNull();
    }

    @Test void syncIsOffUnlessConfiguredAndOnlyBestpartnerTurnsItOn() throws Exception {
        assertThat(PullRequestSync.maxRounds(load("bestpartner.json").definition())).isEqualTo(2);
        assertThat(PullRequestSync.autoPush(load("bestpartner.json").definition())).isTrue();
        for (String other : List.of("node-web.json", "spring-starters.json", "dynamic-api.json")) {
            assertThat(PullRequestSync.maxRounds(load(other).definition())).as(other).isZero();
            assertThat(PullRequestSync.autoPush(load(other).definition())).as(other).isFalse();
        }
    }

    @Test void roundBudgetLetsAnExhaustedTaskFinishOneSyncButIsBounded() throws Exception {
        var flow = published(load("bestpartner.json"));
        flow.transitions = flow.snapshot.definition().maxTransitions() + 14;
        assertThat(FlowLimits.transitionsExhausted(flow)).isTrue();
        flow.pullRequestSync = new PullRequestSync.Round(1, 6, "dev", "b".repeat(40), flow.publishedHead, List.of("a.md"),
                "排程偵測", true, flow.transitions, "2026-10-23T00:00:00Z");
        assertThat(FlowLimits.transitionsExhausted(flow)).isFalse();
        flow.transitions += PullRequestSync.TRANSITION_BUDGET;
        assertThat(FlowLimits.transitionsExhausted(flow)).isTrue();
    }

    @Test void instructionGivesTheSystemFactsAndForbidsRewritingHistory() {
        var round = new PullRequestSync.Round(1, 6, "dev", "b".repeat(40), "a".repeat(40),
                List.of("docs-site/docs/getting-started/installation.md"), "排程偵測", true, 0, "t");
        String text = PullRequestSync.instruction(round, "https://github.com/o/r/pull/6");
        assertThat(text).contains("installation.md").contains("b".repeat(40)).contains("a".repeat(40))
                .contains("禁止：rebase").contains("系統核對後代為提交");
    }

    @Test void githubStateKeepsUnknownMergeableAsNull() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"number":6,"state":"open","merged":false,"mergeable":null,"mergeable_state":"unknown",
                 "head":{"ref":"feature/ai-team-task-1055","sha":"abc"},"base":{"ref":"dev"}}""");
        var state = com.zipe.aiteam.publish.PullRequestPublisher.parseState(json);
        assertThat(state.mergeable()).isNull();
        assertThat(state.conflicting()).isFalse();
        var dirty = com.zipe.aiteam.publish.PullRequestPublisher.parseState(new ObjectMapper().readTree("""
                {"number":6,"state":"open","merged":false,"mergeable":false,"mergeable_state":"dirty",
                 "head":{"ref":"h","sha":"s"},"base":{"ref":"dev"}}"""));
        assertThat(dirty.conflicting()).isTrue();
        assertThat(dirty.baseRef()).isEqualTo("dev");
    }

    @Test void catalogRejectsOutOfRangeRounds() throws Exception {
        var snapshot = load("bestpartner.json");
        var mapper = new ObjectMapper();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(snapshot.definition());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("project").path("autonomy")).put("pullRequestSyncRounds", 4);
        var invalid = mapper.treeToValue(tree, TeamDefinition.class);
        assertThatThrownBy(() -> TeamCatalog.validate(invalid)).hasMessageContaining("pullRequestSyncRounds");
    }
}
