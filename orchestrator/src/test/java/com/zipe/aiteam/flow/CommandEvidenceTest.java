package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.runtime.CommandRuns.CommandRun;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class CommandEvidenceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Map<String, TeamDefinition.Operation> ops = new LinkedHashMap<>(Map.of(
            "backend-test", new TeamDefinition.Operation(List.of("implementation"), ".",
                    List.of("gradle", "-p", "backend", "--no-daemon", "test", "-x", "buildFrontend"), List.of(), null)));

    JsonNode success() throws Exception {
        return mapper.readTree("{\"status\":\"SUCCESS\",\"quality_gate\":{\"verify_passed\":true},\"boundary_conflict\":null}");
    }

    @Test void claimedSuccessWithFailedConfiguredOperationIsRejected() throws Exception {
        // #1001：backend-test 結束碼 1，報告卻是 SUCCESS／verify_passed=true。
        String reason = CommandEvidence.contradiction(ops,
                List.of(new CommandRun("gradle -p backend --no-daemon test -x buildFrontend", 1)), success());
        assertThat(reason).contains("backend-test", "結束碼 1", "受阻");
    }

    @Test void laterSuccessfulRerunOfTheSameOperationWins() throws Exception {
        assertThat(CommandEvidence.contradiction(ops, List.of(
                new CommandRun("gradle -p backend --no-daemon test -x buildFrontend", 1),
                new CommandRun("gradle  -p backend --no-daemon test -x buildFrontend 2>&1 | tail -40", 0)), success())).isNull();
    }

    @Test void subsetOrUnrelatedCommandsAreNotTreatedAsTheOperation() throws Exception {
        assertThat(CommandEvidence.contradiction(ops, List.of(
                new CommandRun("gradle -p backend --no-daemon test -x buildFrontend --tests FooTest", 1),
                new CommandRun("cd backend && gradle test", 1),
                new CommandRun("git status", 128)), success())).isNull();
        assertThat(CommandEvidence.matches("gradle -p backend --no-daemon test -x buildFrontend > out.log", String.join(" ",
                ops.get("backend-test").argv()))).isTrue();
    }

    @Test void honestReportsAndMissingEvidenceAreLeftToTheNormalGate() throws Exception {
        JsonNode blocked = mapper.readTree("{\"status\":\"BLOCKED\",\"quality_gate\":{\"verify_passed\":false},\"boundary_conflict\":null}");
        List<CommandRun> failed = List.of(new CommandRun("gradle -p backend --no-daemon test -x buildFrontend", 1));
        assertThat(CommandEvidence.contradiction(ops, failed, blocked)).isNull();
        assertThat(CommandEvidence.contradiction(ops, List.of(), success())).isNull();
        assertThat(CommandEvidence.contradiction(Map.of(), failed, success())).isNull();
    }
}
