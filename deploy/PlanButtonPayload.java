import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.flow.*;
import com.zipe.aiteam.slack.PlanReview;
import java.nio.file.*;

/** 使用產品的按鈕產生器補發既有任務卡片；不寫入任務或核准資料。 */
class PlanButtonPayload {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var task = mapper.readTree(Files.readString(Path.of(args[0]))).get(0);
        var flow = mapper.readValue(Files.readString(Path.of(args[1])), FlowRun.class);
        if (!task.path("state").asText().equals("AWAIT_HUMAN")) throw new IllegalStateException("任務已不在等待狀態，勿補發核准卡");
        var d = new PlanReview.Decision(task.path("id").asText(), "plan", Traceability.digest(flow.receipts.get("plan")), flow.snapshot.revision(), flow.baselineRevision);
        String blocks = com.slack.api.util.json.GsonFactory.createSnakeCase().toJson(PlanReview.buttons(d));
        var payload = mapper.createObjectNode().put("channel", task.path("slack_channel").asText())
                .put("thread_ts", task.path("slack_thread_ts").asText())
                .put("text", "測試計畫核准按鈕已補上。你的既有核准若已完成仍然保留，無需重複核准；核准不會自動續跑 QA。")
                .put("unfurl_links", false).put("unfurl_media", false);
        payload.set("blocks", mapper.readTree(blocks));
        Files.writeString(Path.of(args[2]), mapper.writeValueAsString(payload));
    }
}
