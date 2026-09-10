package com.zipe.aiteam.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 從 JSONL 事件流取出 session id；不中斷 stdout 落檔。 */
final class SessionCapture {
    private SessionCapture() {}

    /** codex：session id 在第一個 thread.started 的 thread_id。 */
    static boolean await(Process process, Duration timeout, Path stdout, Consumer<String> started)
            throws Exception {
        return await(process, timeout, stdout, started,
                event -> "thread.started".equals(event.path("type").asText())
                        ? event.path("thread_id").asText("") : "");
    }

    /**
     * 各家 CLI 把 session id 放在不同欄位，所以取值方式由 runtime 自己給。
     * 抽出來而不是加第二份輪詢迴圈 —— 這段的重點是「逾時也要留下已取得的 id」，
     * 複製一份就等於多一個會漏掉這件事的地方。
     */
    static boolean await(Process process, Duration timeout, Path stdout, Consumer<String> started,
                         java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, String> sessionId)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean captured = false;
        while (true) {
            boolean finished = process.waitFor(100, TimeUnit.MILLISECONDS);
            if (!captured && Files.exists(stdout)) {
                byte[] bytes;
                try (var in = Files.newInputStream(stdout)) { bytes = in.readNBytes(8192); }
                for (String line : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    try {
                        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
                        if (event == null) continue;
                        String id = sessionId.apply(event);
                        if (id != null && !id.isBlank()) {
                            started.accept(id);
                            captured = true;
                            break;
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
                }
            }
            if (finished) return true;
            if (System.nanoTime() >= deadline) return false;
        }
    }

    static void preserve(Path file) throws java.io.IOException {
        if (Files.exists(file)) Files.move(file, file.resolveSibling(file.getFileName()
                + ".previous-" + java.util.UUID.randomUUID()));
    }
}
