package com.zipe.aiteam.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 讀取 JSONL 第一個 thread.started；不中斷 stdout 落檔。 */
final class SessionCapture {
    private SessionCapture() {}
    static boolean await(Process process, Duration timeout, Path stdout, Consumer<String> started)
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
                        if (event != null && "thread.started".equals(event.path("type").asText())
                                && !event.path("thread_id").asText().isBlank()) {
                            started.accept(event.path("thread_id").asText());
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
