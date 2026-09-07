package com.zipe.aiteam.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QaGateTest {
    static ObjectNode plan() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"status":"READY","summary":"mail acceptance",
             "scenarios":[{"id":"S1","criterion":"primary SMTP success","preconditions":"two controlled SMTP servers",
              "test_data":"one message","method":"integration","steps":"send once",
              "expected_result":"only primary receives once","environment":"test SMTP servers"}]}
            """);
    }
    static ObjectNode report() throws Exception {
        return (ObjectNode) new ObjectMapper().readTree("""
            {"execution_status":"COMPLETED","failure_report":null,"verdict":"PASS","verify_passed":true,
             "scenario_results":[{"scenario_id":"S1","status":"PASS","actual_result":"delivered once",
              "command":"mvn test","evidence":"surefire report"}],"findings":[],"uncertainties":[]}
            """);
    }
    @Test void completeEvidencePasses() throws Exception {
        assertThat(QaGate.executionReason(plan(), report())).isNull();
    }
    @Test void incompletePlanCannotStartImplementation() throws Exception {
        var p = plan();
        ((ObjectNode)p.path("scenarios").get(0)).put("expected_result", " ");
        assertThat(QaGate.planReason(p)).contains("expected_result");
        assertThat(FailureReport.blockingReason("qa_plan", p)).isNotNull();
    }
    @Test void missingOrDuplicateScenariosCannotPass() throws Exception {
        var r = report();
        r.withArray("scenario_results").removeAll();
        assertThat(QaGate.executionReason(plan(), r)).contains("遺漏");
        r = report();
        r.withArray("scenario_results").add(r.path("scenario_results").get(0).deepCopy());
        assertThat(QaGate.executionReason(plan(), r)).contains("重複");
    }
    @Test void unavailableEnvironmentCannotHideBehindPassLabel() throws Exception {
        for (String status : new String[]{"FAIL", "BLOCKED", "NOT_RUN"}) {
            var r = report();
            ((ObjectNode)r.path("scenario_results").get(0)).put("status", status);
            assertThat(QaGate.executionReason(plan(), r)).contains("S1", "未通過");
        }
    }
    @Test void missingEvidenceHighFindingsAndUncertaintiesBlock() throws Exception {
        var r = report();
        ((ObjectNode)r.path("scenario_results").get(0)).put("evidence", "");
        assertThat(QaGate.executionReason(plan(), r)).contains("evidence");
        r = report(); r.withArray("findings").addObject().put("severity", "HIGH");
        assertThat(QaGate.executionReason(plan(), r)).contains("HIGH");
        r = report(); r.withArray("uncertainties").add("SMTP not available");
        assertThat(QaGate.executionReason(plan(), r)).contains("未核實");
        assertThat(QaGate.executionReason(null, report())).contains("缺少");
    }
}
