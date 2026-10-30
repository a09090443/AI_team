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
    static boolean serviceDialogue(FlowRun flow) {
        return flow.dialogue && flow.parentId!=null && flow.snapshot.definition().project().serviceTarget()!=null
                && flow.snapshot.definition().nodes().values().stream()
                    .anyMatch(n -> n.gate()==TeamDefinition.Gate.SERVICE && Objects.equals(n.role(),flow.dialogueRole));
    }
    private static String unavailable(Path dir,String reason) {
        try {
            Path temporary=Files.createTempFile(dir,".support-",".json");
            Files.writeString(temporary,JSON.createObjectNode().put("healthy",false).put("error",reason).toString());
            Files.move(temporary,dir.resolve("current.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){ /* Without matching generation current() still rejects stale evidence. */ }
        return "\n系統診斷未完成，現在健康未知；保留原job，不另建部署。原因="+reason;
    }
    static JsonNode job(Path home,String task) {
        if(home==null || task==null || !task.matches("[0-9]+"))return JSON.createObjectNode();
        Path file=home.resolve("service-support").resolve(task).resolve("job.json");
        try { if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>32768
                || !file.toRealPath().equals(file.toAbsolutePath().normalize()))return JSON.createObjectNode();
            return JSON.readTree(file.toFile());
        }catch(Exception e){return JSON.createObjectNode();}
    }
    public static boolean recoverable(Path home,String task,String parent,String target) {
        JsonNode job=job(home,task);
        return configured(home,target) && job.path("version").asInt()==1
                && task.equals(job.path("task").asText()) && parent.equals(job.path("parent").asText())
                && target.equals(job.path("target").asText())
                && Set.of("RUNNING","COMPLETED","FAILED").contains(job.path("state").asText())
                && job.path("deadline").asLong()>java.time.Instant.now().getEpochSecond();
    }
    public static String collect(Path home,String task,String parent,String target) {
        return collect(home,task,parent,target,"default",()->false);
    }
    /** Independent fixed worker owns the lock/checkpoint. JVM shutdown does not discard its remote job IDs. */
    public static String collect(Path home,String task,String parent,String target,String generation,java.util.function.BooleanSupplier cancelled) {
        if(!configured(home,target))return "";
        if(!task.matches("[0-9]+")||!parent.matches("[0-9]+"))throw new IllegalArgumentException("診斷任務編號不合法");
        Path dir=home.resolve("service-support").resolve(task);
        Process process=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(610);
        try {
            Files.createDirectories(dir);
            if(!dir.toRealPath().equals(dir.toAbsolutePath().normalize()))throw new IllegalStateException("診斷路徑不符");
            Path script=home.resolve("teams/skills/service-build/scripts/support.py");
            if(!Files.isRegularFile(script,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("固定診斷工具不存在");
            while(System.nanoTime()<deadline) {
                if(cancelled.getAsBoolean())return "\n系統停止等待；保留原診斷作業，未重新部署。";
                JsonNode saved=job(home,task);
                boolean matching=saved.path("version").asInt()==1 && task.equals(saved.path("task").asText()) && parent.equals(saved.path("parent").asText())
                        && target.equals(saved.path("target").asText()) && generation.equals(saved.path("generation").asText());
                if(matching && Set.of("COMPLETED","FAILED").contains(saved.path("state").asText()))return notice(home,task);
                if(matching && saved.path("deadline").asLong()<=java.time.Instant.now().getEpochSecond())
                    return "\n系統診斷已逾時，保留原作業ID；目前健康未知，不自動另建修復。";
                if(process!=null && !process.isAlive() && process.exitValue()!=0 && process.exitValue()!=75)
                    return unavailable(dir,"SUPPORT_WORKER_UNAVAILABLE");
                if(process==null || !process.isAlive())
                    process=new ProcessBuilder("python3",script.toString(),home.toString(),task,parent,target,"--job",generation)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("diagnostic.stderr.log").toFile())).start();
                Thread.sleep(1000);
            }
            return unavailable(dir,"SUPPORT_WAIT_UNCONFIRMED");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("診斷等待已中斷，持久作業保留",e);}
        catch(Exception e){return unavailable(dir,"SUPPORT_CHECK_UNAVAILABLE");}
    }
    public static JsonNode current(Path home,String task) {
        if(home==null||task==null||!task.matches("[0-9]+"))return JSON.createObjectNode();
        Path file=home.resolve("service-support").resolve(task).resolve("current.json");
        try {if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>65536
                    ||System.currentTimeMillis()-Files.getLastModifiedTime(file).toMillis()>600000)return JSON.createObjectNode();
            JsonNode evidence=JSON.readTree(file.toFile()),saved=job(home,task);
            if(!saved.isEmpty()) {
                if(!Set.of("COMPLETED","FAILED").contains(saved.path("state").asText()))
                    return JSON.createObjectNode().put("healthy",false).put("error","SUPPORT_PENDING");
                if(saved.path("state").asText().equals("COMPLETED") &&
                        (!saved.path("generation").equals(evidence.path("generation"))
                        || !saved.path("target").equals(evidence.path("target"))
                        || !saved.path("parent").equals(evidence.path("parent"))))return JSON.createObjectNode();
            }
            return evidence;}catch(Exception e){return JSON.createObjectNode();}
    }
    public static String notice(Path home,String task) {
        JsonNode evidence=current(home,task);
        return "\n【系統服務追問診斷：固定工具實測，非模型推測】\n"+Diagnostics.clip(Diagnostics.safe(evidence.toString()),16000)
                +"\nurl是給指定audience使用的入口，probe_url是Agent VM實際探測位置。禁止把VM內部位址當Windows入口，禁止把probe成功說成已從使用者電腦驗證。"
                +"已登錄user URL與probe URL的轉送關係由管理者提供；回覆明列完整路徑及使用位置。若原網址錯誤而登錄入口健康，直接更正入口，無需重新部署。"
                +"只有repair_attempted=true才表示系統曾嘗試固定SSH修復；是否成功依operations真實結果說明，不請人重填同樣問題。";
    }
    public static String replyReason(Path home,String task,String request,String answer) {
        if(request==null||!request.matches("(?is).*(網址|打不開|無法.*開|連線|https?://|url|website|accessible).*"))return null;
        JsonNode evidence=current(home,task);
        if(!evidence.path("endpoints").isArray()||evidence.path("endpoints").isEmpty())return null;
        if(unsupportedFollowup(answer))return "不要要求使用者用執行前綴續跑已結案任務；直接回答診斷/修復結果與正確入口。若仍缺新瀏覽器錯誤，只詢問該必要資訊。"+notice(home,task);
        if(answer.matches("(?is).*(已從|已經從|從你的Windows|在你的Windows).{0,24}(驗證成功|實測成功|確認正常|測試成功).*"))
            return "不可宣稱已從使用者電腦驗證；系統探測位置是Agent VM。"+notice(home,task);
        for(JsonNode endpoint:evidence.path("endpoints"))
            if(!answer.contains(endpoint.path("url").asText()))return "回覆缺少指定使用位置的完整入口；請直接更正網址並說明檢查位置："+notice(home,task);
        return null;
    }
    public static boolean unsupportedFollowup(String answer) {
        return answer!=null&&answer.matches("(?is).*@[^`\\n]{1,100}\\s執行\\s.*");
    }
    public static String verifiedReply(Path home,String task) {
        JsonNode evidence=current(home,task);
        StringBuilder text=new StringBuilder(evidence.path("healthy").asBoolean()?"目前服務入口檢查正常，請改用以下完整網址：":"目前入口仍無法確認正常，已保留診斷結果：");
        for(JsonNode e:evidence.path("endpoints"))text.append("\n").append(e.path("audience").asText()).append("：").append(e.path("url").asText())
                .append("（Agent VM探測HTTP ").append(e.path("http_status").asText("未確認")).append("）");
        return text.append("\n檢查由Agent VM進行，未宣稱已從你的電腦驗證。這不是重新部署；原任務的版本與資料保留。" ).toString();
    }
    /**
     * 接沙箱時系統登錄的使用者位址（service-context 的 user_host）；管理者已登錄 access_endpoints 時以登錄入口為準，回 null。
     * 位址來自系統設定而非模型，所以交付網址的主機可信，只剩埠與路徑要靠實測確認。
     */
    public static String userHost(Path home,String target) {
        if(home==null||target==null||!target.matches("[a-z0-9][a-z0-9-]{0,63}"))return null;
        Path file=home.resolve("service-context").resolve(target+".json");
        try {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>32768)return null;
            JsonNode data=JSON.readTree(file.toFile());
            String host=data.path("user_host").asText("");
            if(!data.path("target").asText().equals(target)||!host.matches("[A-Za-z0-9.-]{1,253}"))return null;
            return data.path("access_endpoints").isArray()&&!data.path("access_endpoints").isEmpty()?null:host;
        }catch(Exception e){return null;}
    }
    public record Delivery(String block,String evidence) {}
    private static final java.util.regex.Pattern URL=java.util.regex.Pattern.compile("https?://[A-Za-z0-9.\\-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._~%!$&'*+,;=:@/\\-]*)?");
    /** 未登錄入口的沙箱目標：從摘要取出主機是 user_host 的網址，由系統從 Agent VM 實測，2xx 才交付。只測該主機，不探測模型給的其他位址。 */
    public static Delivery derivedDelivery(String host,JsonNode report,java.util.function.Function<String,Integer> status) {
        if(!"RUNNING".equals(report.path("service_status").asText()))return new Delivery(null,"");
        String hint="沙箱使用者位址是 "+host+"（系統接沙箱時登錄），交付摘要要寫完整網址 http://"+host+":<實際對外埠><應用路徑>，系統會從 Agent VM 實測";
        Set<String> urls=new LinkedHashSet<>();
        var matcher=URL.matcher(report.path("summary").asText());
        while(matcher.find()&&urls.size()<4) {
            String url=matcher.group();
            if(java.net.URI.create(url).getHost().equalsIgnoreCase(host))urls.add(url);
        }
        if(urls.isEmpty())return new Delivery("服務摘要缺少使用者入口；"+hint,"");
        StringBuilder evidence=new StringBuilder();
        for(String url:urls) {
            Integer code=status.apply(url);
            evidence.append(url).append(" → ").append(code==null?"連不上":"HTTP "+code).append("；");
            if(code==null||code<200||code>=300)
                return new Delivery("系統從 Agent VM 實測交付網址未通過："+evidence+"先確認對外埠、應用路徑與容器埠對應後修正；"+hint,evidence.toString());
        }
        return new Delivery(null,evidence.toString());
    }
    /** 固定探測：不走 proxy、不跟隨轉址，5 秒逾時；連不上回 null。 */
    public static Integer httpStatus(String url) {
        try {
            var client=java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY)
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER).connectTimeout(java.time.Duration.ofSeconds(5)).build();
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(5))
                    .header("Range","bytes=0-0").GET().build();
            return client.send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        }catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
        catch(Exception e){return null;}
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
