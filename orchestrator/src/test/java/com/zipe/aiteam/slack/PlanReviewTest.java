package com.zipe.aiteam.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanReviewTest {
    @Test void allScenariosPrecedeApprovalCommandAndReceiptRemainsUnchanged() throws Exception {
        var plan = new ObjectMapper().readTree("{\"status\":\"READY\",\"scenarios\":[{\"id\":\"SC-1\",\"steps\":\"一\\n二\"},{\"id\":\"SC-27\",\"expected_result\":\"確認未遺漏\"}]}");
        var before = plan.deepCopy(); var pages = PlanReview.pages("1014", "plan", plan);
        assertEquals(4, pages.size());
        assertTrue(pages.get(1).contains("一\n二"));
        assertTrue(pages.get(2).contains("SC-27"));
        assertTrue(pages.getLast().contains("補充 #1014 核准測試計畫 plan "));
        assertEquals(before, plan);
    }
    @Test void truncatedPlanCannotOfferApprovalCommand() throws Exception {
        var plan = new ObjectMapper().readTree("{\"scenarios\":[{\"id\":\"s\"}]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("scenarios").get(0)).put("steps", "長".repeat(20000));
        assertThrows(IllegalArgumentException.class, () -> PlanReview.pages("1", "plan", plan));
    }
}
