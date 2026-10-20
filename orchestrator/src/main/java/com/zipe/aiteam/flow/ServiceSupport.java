package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.runtime.Diagnostics;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** System-owned service evidence survives source workspace cleanup. */
public final class ServiceSupport {
    private ServiceSupport() {}
    private static final ObjectMapper JSON=new ObjectMapper();
    public static boolean configured(Path home,String target) {
        if(home==null||target==null||!target.matches("[a-z0-9][a-z0-9-]{0,63}"))return false;
        Path file=home.resolve("service-context").resolve(target+".json");
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>32768)return false;
            JsonNode registration=JSON.readTree(file.toFile());
            return registration.path("target").asText().equals(target)
                    && registration.path("access_endpoints").isArray()&&!registration.path("access_endpoints").isEmpty();
        }
        catch(Exception e){return false;}
    }
    public static String collect(Path home,String task,String parent,String target) {
        if(!configured(home,target))return "";
        if(!task.matches("[0-9]+")||!parent.matches("[0-9]+"))throw new IllegalArgumentException("診斷任務編號不合法");
        Path dir=home.resolve("service-support").resolve(task);
        Process process=null;
        try {
            Files.createDirectories(dir);
            if(!dir.toRealPath().equals(dir.toAbsolutePath().normalize()))throw new IllegalStateException("診斷路徑不符");
            Path script=home.resolve("teams/skills/service-build/scripts/support.py");
            if(!Files.isRegularFile(script,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("固定診斷工具不存在");
            Path output=dir.resolve("current.json"),error=dir.resolve("diagnostic.stderr.log");
            Files.writeString(output,"{\"healthy\":false,\"error\":\"SUPPORT_PENDING\"}");
            process=new ProcessBuilder("python3",script.toString(),home.toString(),task,parent,target)
                    .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            if(!process.waitFor(600,TimeUnit.SECONDS)) {
                process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);
                Files.writeString(output,"{\"healthy\":false,\"error\":\"SUPPORT_TIMEOUT\"}");
            }
            return notice(home,task);
        } catch(InterruptedException e){if(process!=null)process.destroyForcibly();Thread.currentThread().interrupt();throw new IllegalStateException("診斷已中斷",e);}
        catch(Exception e){return "\n系統服務診斷未完成，不能宣稱目前健康或要求重新部署。原因類別="+e.getClass().getSimpleName();}
    }
    public static JsonNode current(Path home,String task) {
        if(home==null||task==null||!task.matches("[0-9]+"))return JSON.createObjectNode();
        Path file=home.resolve("service-support").resolve(task).resolve("current.json");
        try {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536
                    ||System.currentTimeMillis()-Files.getLastModifiedTime(file).toMillis()>600000)return JSON.createObjectNode();
            return JSON.readTree(file.toFile());}catch(Exception e){return JSON.createObjectNode();}
    }
    public static String notice(Path home,String task) {
        JsonNode evidence=current(home,task);
        return "\n【系統服務追問診斷：固定工具實測，非模型推測】\n"+Diagnostics.clip(Diagnostics.safe(evidence.toString()),16000)
                +"\nurl是給指定audience使用的入口，probe_url是Agent VM實際探測位置。禁止把VM內部位址當Windows入口，禁止把probe成功說成已從使用者電腦驗證。"
                +"已登錄user URL與probe URL的轉送關係由管理者提供；回覆明列完整路徑及使用位置。若原網址錯誤而登錄入口健康，直接更正入口，無需重新部署。"
                +"repair_attempted/operations表示系統已透過同一SSH固定工具執行既有版本修復；依真實結果說明，不請人重填同樣問題。";
    }
    public static String replyReason(Path home,String task,String request,String answer) {
        if(request==null||!request.matches("(?is).*(網址|打不開|無法.*開|連線|https?://|url|website|accessible).*"))return null;
        JsonNode evidence=current(home,task);
        if(!evidence.path("endpoints").isArray()||evidence.path("endpoints").isEmpty())return null;
        if(answer.matches("(?is).*(已從|已經從|從你的Windows|在你的Windows).{0,24}(驗證成功|實測成功|確認正常|測試成功).*"))
            return "不可宣稱已從使用者電腦驗證；系統探測位置是Agent VM。"+notice(home,task);
        for(JsonNode endpoint:evidence.path("endpoints"))
            if(!answer.contains(endpoint.path("url").asText()))return "回覆缺少指定使用位置的完整入口；請直接更正網址並說明檢查位置："+notice(home,task);
        return null;
    }
    public static String verifiedReply(Path home,String task) {
        JsonNode evidence=current(home,task);
        StringBuilder text=new StringBuilder(evidence.path("healthy").asBoolean()?"目前服務入口檢查正常，請改用以下完整網址：":"目前入口仍無法確認正常，已保留診斷結果：");
        for(JsonNode e:evidence.path("endpoints"))text.append("\n").append(e.path("audience").asText()).append("：").append(e.path("url").asText())
                .append("（Agent VM探測HTTP ").append(e.path("http_status").asText("未確認")).append("）");
        return text.append("\n檢查由Agent VM進行，未宣稱已從你的電腦驗證。這不是重新部署；原任務的版本與資料保留。" ).toString();
    }
    public static String deliveryReason(Path home,String task,JsonNode report) {
        JsonNode evidence=current(home,task);
        if(!evidence.has("endpoints"))return "已登錄服務入口但缺少本次診斷證據，先修復工具或補驗證";
        if(!evidence.path("healthy").asBoolean())return "系統服務入口探測未通過，先診斷並修復，不能宣稱交付完成";
        for(JsonNode endpoint:evidence.path("endpoints"))
            if(!report.path("summary").asText().contains(endpoint.path("url").asText()))return "服務摘要缺少已登錄的使用者入口與完整應用路徑";
        return null;
    }
}
