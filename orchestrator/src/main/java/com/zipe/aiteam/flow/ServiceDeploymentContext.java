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
                                .append("系統結案前會從 Agent VM 實測摘要中這個主機的網址，2xx 才交付，所以不需要等人登錄入口。");
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
    private static String safe(JsonNode node, String key) {
        String value=node.path(key).asText("未記錄");
        return value.length()>1500 ? value.substring(0,1500)+"…" : value;
    }
}
