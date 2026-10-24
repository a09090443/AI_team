package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * service 路線成功交付時的部署檔（例如 agent 為 sandbox 目標寫的安裝／啟動腳本），保存在實例目錄
 * {@code $AI_TEAM_HOME/service-context/<target>.files/}，下次同目標的 service 節點執行前還原到工作區。
 * 起因 #1059：腳本只 commit 在任務工作區，任務完成後啟動清理刪掉工作區，下次部署得從頭重寫。
 * 只保存節點可寫路徑內、相對基準有變更的檔案，排除機密路徑；還原只補工作區沒有的檔案，repo 已有的版本優先。
 */
public final class ServiceArtifacts {
    private ServiceArtifacts() {}
    static final int MAX_FILES = 200;
    static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Saved(String sourceTask, String head, List<String> files) {}
    public record Restored(String sourceTask, String head, List<String> added, List<String> differing) {}

    static Path directory(Path home, String target) {
        if (home == null || target == null || !target.matches("[a-zA-Z0-9_-]{1,100}"))
            throw new IllegalArgumentException("無效的服務目標名稱");
        return home.resolve("service-context").resolve(target + ".files");
    }

    /** 保存本次交付的部署檔；沒有符合的變更時回 null，保留上一份。超過上限拋出例外，由呼叫端記錄、不擋交付。 */
    public static Saved save(Path home, String target, String taskId, String head, Path workspace,
                             List<String> changed, List<String> writable) throws IOException {
        Path dir = directory(home, target);
        List<String> files = changed.stream().distinct().sorted()
                .filter(p -> safe(workspace, p) && PathRules.allows(writable, p) && !ServiceDelivery.sensitivePath(p))
                .filter(p -> Files.isRegularFile(workspace.resolve(p), LinkOption.NOFOLLOW_LINKS)).toList();
        if (files.isEmpty()) return null;
        long total = 0;
        for (String p : files) total += Files.size(workspace.resolve(p));
        if (files.size() > MAX_FILES || total > MAX_BYTES)
            throw new IOException("部署檔超過保存上限（" + files.size() + " 個、" + total + " bytes）");
        Files.createDirectories(dir.getParent());
        Path temp = Files.createTempDirectory(dir.getParent(), target + ".files.tmp");
        try {
            ArrayNode list = JSON.createArrayNode();
            for (String p : files) {
                byte[] content = Files.readAllBytes(workspace.resolve(p));
                Path out = temp.resolve("files").resolve(p);
                Files.createDirectories(out.getParent());
                Files.write(out, content);
                list.addObject().put("path", p).put("sha256", sha256(content))
                        .put("executable", Files.isExecutable(workspace.resolve(p)));
            }
            ObjectNode manifest = JSON.createObjectNode().put("version", 1).put("target", target).put("task", taskId)
                    .put("head", head).put("saved_at", Instant.now().toString());
            manifest.set("files", list);
            Files.writeString(temp.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            Path old = dir.resolveSibling(target + ".files.old");
            deleteTree(old);
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
            Files.move(temp, dir, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(old);
            return new Saved(taskId, head, files);
        } finally {
            deleteTree(temp);
        }
    }

    /** 把保存的部署檔補進工作區（只補缺檔）；沒有保存或保存內容不完整時回 null，不動工作區。 */
    public static Restored restore(Path home, String target, Path workspace) throws IOException {
        Path dir = directory(home, target);
        Path manifestFile = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) return null;
        JsonNode manifest = JSON.readTree(manifestFile.toFile());
        if (manifest.path("version").asInt() != 1 || !target.equals(manifest.path("target").asText())) return null;
        Map<String, byte[]> verified = new LinkedHashMap<>();
        for (JsonNode file : manifest.path("files")) {
            String p = file.path("path").asText();
            Path stored = dir.resolve("files").resolve(p);
            if (!safe(workspace, p) || ServiceDelivery.sensitivePath(p) || !Files.isRegularFile(stored, LinkOption.NOFOLLOW_LINKS)) return null;
            byte[] content = Files.readAllBytes(stored);
            if (!sha256(content).equals(file.path("sha256").asText())) return null;
            verified.put(p, content);
        }
        List<String> added = new ArrayList<>(), differing = new ArrayList<>();
        for (var entry : verified.entrySet()) {
            Path out = workspace.resolve(entry.getKey());
            if (Files.exists(out, LinkOption.NOFOLLOW_LINKS)) {
                if (!Arrays.equals(Files.readAllBytes(out), entry.getValue())) differing.add(entry.getKey());
                continue;
            }
            Files.createDirectories(out.getParent());
            Files.write(out, entry.getValue());
            JsonNode meta = findFile(manifest, entry.getKey());
            if (meta != null && meta.path("executable").asBoolean()) out.toFile().setExecutable(true, false);
            added.add(entry.getKey());
        }
        return new Restored(manifest.path("task").asText(), manifest.path("head").asText(), added, differing);
    }

    /** 給 service 角色的說明：還原了哪些檔、哪些以 repo 版本為準。 */
    public static String notice(Restored restored) {
        StringBuilder out = new StringBuilder("【前次成功部署的部署檔】任務 #").append(restored.sourceTask())
                .append("（來源 ").append(restored.head().length() > 12 ? restored.head().substring(0, 12) : restored.head())
                .append("）交付成功時保存的部署檔");
        if (!restored.added().isEmpty())
            out.append("已由系統還原並提交到工作區：").append(String.join("、", restored.added())).append("。");
        if (!restored.differing().isEmpty())
            out.append("下列檔案 repo 已有不同版本，以 repo 為準、未覆蓋：").append(String.join("、", restored.differing())).append("。");
        return out.append("先沿用這些檔案部署；失敗時依新的收據證據修正，不要無故重寫。").toString();
    }

    private static JsonNode findFile(JsonNode manifest, String path) {
        for (JsonNode file : manifest.path("files")) if (path.equals(file.path("path").asText())) return file;
        return null;
    }

    private static boolean safe(Path workspace, String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")) return false;
        if (Arrays.stream(path.split("/")).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..") || s.equals(".git"))) return false;
        Path resolved = workspace.resolve(path).normalize();
        if (!resolved.startsWith(workspace.normalize())) return false;
        for (Path p = resolved.getParent(); p != null && !p.equals(workspace.normalize()); p = p.getParent())
            if (Files.isSymbolicLink(p)) return false;
        return true;
    }

    static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
