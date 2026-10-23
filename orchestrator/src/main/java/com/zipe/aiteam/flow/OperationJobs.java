package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.runtime.Diagnostics;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Runtime-independent operation journal. Executors own these files outside model workspaces. */
public final class OperationJobs {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "UNCERTAIN");
    private final Path home;
    public OperationJobs(Path home) { this.home = home; }

    public record Job(String id, String action, String target, String state, String sourceHead, long updated,
                      long deadline, String errorKind, JsonNode result) {
        public boolean pending() { return Set.of("QUEUED", "RUNNING", "UNCERTAIN").contains(state); }
    }
    public record Batch(List<Job> jobs) {
        public boolean empty() { return jobs.isEmpty(); }
        public boolean pending() { return jobs.stream().anyMatch(Job::pending); }
        public long deadline() { return jobs.stream().filter(Job::pending).mapToLong(Job::deadline).max().orElse(0); }
        /** Progress timestamps do not create a new recovery opportunity. */
        public String fingerprint() {
            try {
                String text = jobs.stream().sorted(Comparator.comparing(Job::id)).map(j -> j.id()+":"+j.state()+":"+j.sourceHead()+":"+j.errorKind()).reduce("", (a,b)->a+"\n"+b);
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        public String notice() {
            StringBuilder out = new StringBuilder("\n【系統長作業檢查點：來自固定執行器，不是模型自陳】\n");
            for (Job job : jobs) {
                out.append("job=").append(job.id()).append(" action=").append(job.action()).append(" state=").append(job.state())
                        .append(" target=").append(job.target()).append(" source_head=").append(job.sourceHead());
                if (job.errorKind() != null) out.append(" error_kind=").append(job.errorKind());
                if (job.result() != null && !job.result().isNull()) out.append(" result=").append(job.result());
                out.append('\n');
            }
            out.append("若工具已有未取回的pending action，先呼叫該action收取原結果，不能從prepare重新排。已成功操作不可重送或重建；依同一來源檢查點只補缺少步驟。FAILED先診斷並在既有授權內修復；RUNNING/UNCERTAIN查原job結果，不新增重複操作。\n")
                    .append("固定工具若回OPERATION_PENDING，這不是失敗；本輪立即交回BLOCKED並說明等待系統作業即可，不要長時間輪詢、不要請使用者重啟。系統等待完成後會自動接續，不消耗新節點重跑次數。\n");
            return Diagnostics.safe(Diagnostics.clip(out.toString(), 12000));
        }
    }
    public Batch read(String task, String node, String head) {
        if (home == null || task == null || !task.matches("[0-9]+") || node == null || head == null || head.isBlank()) return new Batch(List.of());
        Path base = home.resolve("operation-jobs"), directory = base.resolve(task);
        if (Files.isSymbolicLink(base) || Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return new Batch(List.of());
        List<Job> jobs = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path file : paths.sorted().limit(256).toList()) {
                if (!file.getFileName().toString().matches("[0-9a-f]{32}\\.json") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)>65536) continue;
                JsonNode record = JSON.readTree(file.toFile());
                String id=record.path("id").asText(), state=record.path("state").asText();
                if (record.path("version").asInt()!=1 || !file.getFileName().toString().equals(id+".json")
                        || !task.equals(record.path("task").asText()) || !node.equals(record.path("node").asText())
                        || !head.equals(record.path("source_head").asText()) || !STATES.contains(state)) continue;
                JsonNode result=record.get("result");
                if ("SUCCEEDED".equals(state) && !record.path("verified").asBoolean()) continue;
                if (Set.of("SUCCEEDED","FAILED").contains(state)
                        && (result==null || !id.equals(result.path("id").asText())
                        || !record.path("action").asText().equals(result.path("action").asText())
                        || !record.path("target").asText().equals(result.path("target").asText())
                        || (record.hasNonNull("source_sha256") && !record.path("source_sha256").equals(result.path("source_sha256")))
                        || !result.path("exit_code").isIntegralNumber()
                        || ("SUCCEEDED".equals(state) != (result.path("exit_code").asInt()==0)))) continue;
                jobs.add(new Job(id,record.path("action").asText(),record.path("target").asText(),state,head,record.path("updated").asLong(),
                        record.path("deadline").asLong(),record.path("error_kind").isTextual()?record.path("error_kind").asText():null,result));
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("作業紀錄無法讀取；保留現場，不猜測部署結果", e); }
        jobs.sort(Comparator.comparing(Job::updated).thenComparing(Job::id));
        return new Batch(List.copyOf(jobs));
    }
    /** Explicit user cancellation is separate from a model timeout or transport loss. */
    public void requestCancellation(String task, String node, String head) {
        for (Job job : read(task,node,head).jobs()) {
            if (!job.pending()) continue;
            try {
                Path directory=home.resolve("operation-cancellations").resolve(task);
                Files.createDirectories(directory);
                if (Files.isSymbolicLink(directory) || !directory.toRealPath().equals(directory.toAbsolutePath().normalize()))
                    throw new IllegalStateException("取消作業路徑不安全");
                var record=JSON.createObjectNode().put("version",1).put("id",job.id()).put("task",task)
                        .put("node",node).put("requested_at",java.time.Instant.now().getEpochSecond()).put("reason","user_interrupt");
                Path temporary=Files.createTempFile(directory,".cancel-",".json");
                Files.writeString(temporary,record.toString());
                Files.move(temporary,directory.resolve(job.id()+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) { throw new IllegalStateException("作業取消要求無法保存",e); }
        }
    }
    public static String instruction() {
        return "\n【持久長作業執行契約】工具回OPERATION_PENDING表示已受理、尚在背景執行，不是失敗。立即結束本輪模型並以BLOCKED說明系統作業仍在執行；系統會等待並自動續同節點。不要每隔幾秒輪詢、不要新建同一start、不要要求真人重新授權。已成功的同來源操作沿用系統檢查點，只補缺少驗證。\n";
    }
}