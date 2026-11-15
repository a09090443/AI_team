package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.store.TaskStore.ServiceHistory;
import java.nio.file.*;
import java.util.List;

/** 非機密維運說明與歷史報告是背景資訊，不是本次服務檢查或操作授權。 */
public final class ServiceDeploymentContext {
    private ServiceDeploymentContext() {}
    private static final ObjectMapper JSON = new ObjectMapper();
    public static String notice(Path home, String target, List<ServiceHistory> history) {
        StringBuilder out = new StringBuilder("\n【服務部署歷史與恢復入口】\n以下是歷史資料與管理者說明，不是本次已執行的操作或新增權限。")
                .append("過去成功不保證目前健康；本次部署通道沒回應不代表過去從未部署、舊服務停止或主機設定遺失。")
                .append("回報請分別說明過去結果、本次進度、目前未核實事項。不得用歷史結果填本次 checks。\n同目標歷史階段報告：");
        if (history == null || history.isEmpty()) out.append("無可用紀錄（不代表從未部署）。");
        else for (ServiceHistory h : history) {
            try {
                JsonNode d = JSON.readTree(h.report());
                out.append("\n任務 #").append(h.taskId()).append("，時間 ").append(h.completedAt())
                        .append("，角色回報 service_status=").append(safe(d, "service_status"));
                for (JsonNode check : d.path("checks"))
                    out.append("；").append(safe(check,"name")).append("=").append(safe(check,"status"))
                            .append(" exit=").append(safe(check,"exit_code"));
            } catch (Exception e) { out.append("\n歷史報告無法解析，不得當成功。"); }
        }
        if (home != null && target != null && target.matches("[a-zA-Z0-9_-]{1,100}")) {
            Path file = home.resolve("service-context").resolve(target + ".json");
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.size(file) > 32768) throw new IllegalArgumentException("oversized context");
                    JsonNode data = JSON.readTree(file.toFile());
                    if (!target.equals(data.path("target").asText())) throw new IllegalArgumentException("target mismatch");
                    out.append("\n管理者恢復說明（").append(file).append("）：");
                    for (String key : List.of("host", "program", "config_file", "start_command", "notes"))
                        if (data.hasNonNull(key)) out.append("\n").append(key).append(": ").append(safe(data,key));
                    if(data.path("access_endpoints").isArray())
                        out.append("\n管理者登錄的使用者入口與內部探測對應（不代表本次已實測）：").append(data.path("access_endpoints"));
                    String host=ServiceSupport.userHost(home,target);
                    if(host!=null)
                        out.append("\n使用者電腦連這台沙箱的位址：").append(host).append("（系統接沙箱時登錄，非推測）。")
                                .append("交付摘要寫完整網址 http://").append(host).append(":<實際對外埠><應用路徑>，不要用 127.0.0.1 或容器內位址；")
                                .append("系統結案前會從 Agent VM 實測摘要中這個主機的網址，2xx 才交付，所以不需要等人登錄入口。")
                                // #1012：部署角色在自己的沙箱 curl 連不出去，就回 NOT_RUNNING 停給人，要求另建 Agent VM 探測操作；系統其實會代測。
                                .append("你所在的執行沙箱可能連不到這個網段，在沙箱內連不上不代表服務失敗，也不要因此回報 NOT_RUNNING 或要求另建探測操作：")
                                .append("目標端檢查通過就回報 RUNNING，摘要列出應回 2xx 的 GET 網址（最多 4 個），系統實測的結果就是 Agent VM 的證據，")
                                .append("並寫入交接紀錄供審查引用。系統只送 GET；POST 或預期非 2xx 的路由不要放進摘要網址，以目標端實測為證據並在報告說明。");
                    int count=0;
                    for (JsonNode h : data.path("history")) {
                        if (++count > 5) break;
                        out.append("\n管理者登錄歷史：");
                        for (String key : List.of("at","task","outcome","evidence"))
                            out.append(key).append("=").append(safe(h,key)).append("；");
                    }
                    return out.append("\n工具離線時請完整告知使用者在哪台機器、執行哪個程式、使用哪份設定與啟動指令；")
                            .append("說明依據是管理者登錄，不宣稱已檢查程序。缺少必要欄位時如實指出缺漏。不得自行執行宿主啟動指令。\n").toString();
                } catch (Exception e) { out.append("\n管理者恢復說明無法讀取或目標不符，需管理者修正。"); }
            }
        }
        return out.append("\n尚無完整的管理者恢復入口；請明說缺宿主機、程式、設定檔或啟動方式，不能只叫使用者找維運者。\n").toString();
    }
    /**
     * 計畫、驗收、審查階段的精簡現況（只在完整部署歷史沒附上時用）：告知目標上已有部署過的服務與登錄入口，
     * 並講清楚那是先前版本、本階段連不到、不能當本次證據。
     * 否則這些階段會以為「沒有測試環境」，或反過來拿舊部署當本次 commit 的驗收證據。
     * 沒有任何部署紀錄也沒有登錄入口時回空字串，不增加提示。
     */
    public static String testStageNotice(Path home, String target, List<ServiceHistory> history) {
        return testStageNotice(home, target, history, ServiceSupport::httpStatus);
    }
    /**
     * 入口來源依序：管理者登錄的 access_endpoints；沒有時，取最近一次回報 RUNNING 的部署摘要中主機等於接沙箱時登錄的
     * user_host 的網址（每個接沙箱的專案都有，不需逐一登錄），並由系統當場從 Agent VM 實測一次（最多 2 個、不跟隨轉址）。
     */
    static String testStageNotice(Path home, String target, List<ServiceHistory> history,
                                  java.util.function.Function<String, Integer> status) {
        String latest = null;
        JsonNode running = null;
        if (history != null) for (ServiceHistory h : history) {
            try {
                JsonNode d = JSON.readTree(h.report());
                if (latest == null)
                    latest = "任務 #" + h.taskId() + "（" + h.completedAt() + "，角色回報 service_status=" + safe(d, "service_status") + "）";
                if (running == null && "RUNNING".equals(d.path("service_status").asText())) running = d;
                if (running != null) break;
            } catch (Exception ignored) { /* 下一筆 */ }
        }
        StringBuilder urls = new StringBuilder();
        if (home != null && target != null && target.matches("[a-zA-Z0-9_-]{1,100}")) {
            Path file = home.resolve("service-context").resolve(target + ".json");
            try {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 32768) {
                    JsonNode data = JSON.readTree(file.toFile());
                    if (target.equals(data.path("target").asText()))
                        for (JsonNode e : data.path("access_endpoints"))
                            if (e.path("url").isTextual())
                                urls.append(urls.isEmpty() ? "" : "、").append(safe(e, "url"))
                                        .append(e.path("audience").isTextual() ? "（" + safe(e, "audience") + "）" : "");
                }
            } catch (Exception ignored) { /* 讀不到就不列入口 */ }
        }
        if (urls.isEmpty() && running != null) {
            int probed = 0;
            for (String url : ServiceSupport.userUrls(ServiceSupport.userHost(home, target), running)) {
                if (++probed > 2) break;
                Integer code = status.apply(url);
                urls.append(urls.isEmpty() ? "" : "、").append(url)
                        .append("（取自最近一次 RUNNING 的部署摘要；系統剛從 Agent VM 實測：").append(code == null ? "連不上" : "HTTP " + code).append("）");
            }
        }
        if (latest == null && urls.isEmpty()) return "";
        return "\n沙箱測試環境現況（系統查得，背景資訊）：部署目標 " + target + " 上已有部署過的服務"
                + (latest == null ? "" : "，最近一次為" + latest)
                + (urls.isEmpty() ? "" : "；入口：" + urls)
                + "。那是先前部署的版本，不是本任務的 commit；本階段的操作連不到它，也不能拿它當本次驗收證據。"
                + "需要在部署後實機驗證的項目依延後驗收處理（PR 會列出），由部署流程與負責人另行確認；"
                + "不要因此回報「沒有測試環境」，也不要要求另建環境。";
    }
    private static String safe(JsonNode node, String key) {
        String value=node.path(key).asText("未記錄");
        return value.length()>1500 ? value.substring(0,1500)+"…" : value;
    }
}
