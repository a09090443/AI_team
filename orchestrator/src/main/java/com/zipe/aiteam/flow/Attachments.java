package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zipe.aiteam.runtime.Diagnostics;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * agent 在報告的 attachments 宣告要給使用者下載的檔案，系統驗證後以該角色的 Slack App 上傳到原討論串。
 * agent 不持有 token、不直接呼叫 Slack；路徑、機密檔與大小限制由系統寫死，配置只能再收緊。
 * 未設定 {@link TeamDefinition#attachments()} 的快照（含所有舊快照）提示與 schema 逐字不變。
 */
public final class Attachments {
    private Attachments() {}
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_FILES = 10;
    static final long MAX_BYTES = 50L * 1024 * 1024;
    /** 超過此大小的文字檔不做內容掃描，直接拒絕，避免大檔夾帶憑證卻沒被看過。 */
    static final long SCAN_BYTES = 2L * 1024 * 1024;
    /** 機密與工具設定檔；配置的 allow 不能放行這些路徑。 */
    static final List<String> ALWAYS_DENIED = List.of(
            ".git/**", "**/.git/**", "**/.ssh/**", "**/.codex/**", "**/.claude/**", "**/.agents/**",
            "**/.env", "**/.env.*", "**/env.sh", "**/.npmrc", "**/.netrc", "**/.pypirc", "**/.git-credentials",
            "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore",
            "**/id_rsa*", "**/id_ecdsa*", "**/id_ed25519*", "**/id_dsa*", "**/credentials*");

    public record Candidate(Path file, String name, String title) {}
    public record Selection(List<Candidate> accepted, List<String> rejected) {}

    static boolean enabled(FlowRun flow) {
        return flow != null && flow.snapshot != null && flow.snapshot.definition().attachments() != null;
    }

    static void validate(TeamDefinition d) {
        AttachmentPolicy p = d.attachments();
        if (p == null) return;
        TeamCatalog.require(p.maxFiles() >= 1 && p.maxFiles() <= MAX_FILES, "attachments.maxFiles 須為 1～" + MAX_FILES);
        TeamCatalog.require(p.maxBytes() >= 1 && p.maxBytes() <= MAX_BYTES, "attachments.maxBytes 須為 1～" + MAX_BYTES);
        for (List<String> globs : Arrays.asList(p.allow(), p.deny())) {
            if (globs == null) continue;
            TeamCatalog.require(globs.size() <= 20, "attachments.allow／deny 各最多 20 條");
            globs.forEach(PathRules::validate);
        }
    }

    static String schema(String original, FlowRun flow) throws Exception {
        if (!enabled(flow)) return original;
        ObjectNode schema = (ObjectNode) JSON.readTree(original);
        ((ObjectNode) schema.get("properties")).set("attachments", JSON.readTree("""
                {"type":"array","maxItems":%d,"items":{"type":"object","additionalProperties":false,
                 "required":["path","title"],"properties":{
                 "path":{"type":"string","minLength":1,"maxLength":500},
                 "title":{"type":"string","maxLength":200}}}}
                """.formatted(flow.snapshot.definition().attachments().maxFiles())));
        schema.withArray("required").add("attachments");
        return JSON.writeValueAsString(schema);
    }

    static String notice(FlowRun flow) {
        if (!enabled(flow)) return "";
        AttachmentPolicy p = flow.snapshot.definition().attachments();
        return "\n附件：使用者需要下載的成品檔（報表、匯出檔、截圖等）可列在 attachments，path 為相對於工作目錄的路徑，"
                + "title 是給使用者看的簡短說明；系統驗證後會上傳到討論串。最多 " + p.maxFiles() + " 個、單檔 "
                + (p.maxBytes() / 1024) + " KB 以內"
                + (p.allow() == null || p.allow().isEmpty() ? "" : "，只接受符合 " + p.allow() + " 的路徑")
                + "。不要附原始碼、log 全文或任何含憑證的檔案；沒有要附的檔案就填空陣列。";
    }

    /** 逐一檢查 agent 宣告的檔案；不通過的列出原因，不影響階段結果。 */
    public static Selection select(JsonNode report, Path root, AttachmentPolicy policy) throws Exception {
        List<Candidate> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        JsonNode items = report == null ? null : report.path("attachments");
        if (policy == null || items == null || !items.isArray()) return new Selection(accepted, rejected);
        Path base = root.toRealPath();
        Set<Path> seen = new HashSet<>();
        for (JsonNode item : items) {
            String raw = item.path("path").asText("").trim();
            String label = raw.isEmpty() ? "（未提供路徑）" : raw;
            if (accepted.size() >= policy.maxFiles()) { rejected.add(label + "（超過每份報告 " + policy.maxFiles() + " 個的上限）"); continue; }
            String reason = reject(raw, base, policy);
            if (reason != null) { rejected.add(label + "（" + reason + "）"); continue; }
            Path file = base.resolve(raw).normalize();
            if (!seen.add(file)) continue;
            String title = item.path("title").asText("").trim();
            accepted.add(new Candidate(file, file.getFileName().toString(), title.isEmpty() ? file.getFileName().toString() : title));
        }
        return new Selection(List.copyOf(accepted), List.copyOf(rejected));
    }

    static String reject(String path, Path base, AttachmentPolicy policy) throws Exception {
        if (path.isEmpty() || path.length() > 500 || path.indexOf('\0') >= 0 || path.contains("\\") || path.contains(":")
                || path.startsWith("/") || Arrays.asList(path.split("/")).contains("..")) return "路徑必須是工作目錄內的相對路徑";
        String relative = Path.of(path).normalize().toString().replace('\\', '/');
        if (PathRules.allows(ALWAYS_DENIED, relative) || PathRules.allows(policy.deny(), relative)) return "屬於禁止上傳的檔案";
        if (policy.allow() != null && !policy.allow().isEmpty() && !PathRules.allows(policy.allow(), relative)) return "不在允許上傳的路徑內";
        Path file = base.resolve(relative).normalize();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return "檔案不存在";
        // 任何一層是 symlink 時實際路徑會不同；一律拒絕，不追連結到工作目錄外。
        if (!file.toRealPath().equals(file) || !file.startsWith(base)) return "不接受符號連結或工作目錄外的檔案";
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "不是一般檔案";
        long size = Files.size(file);
        if (size == 0) return "空檔案";
        if (size > policy.maxBytes()) return "超過單檔 " + policy.maxBytes() / 1024 + " KB 上限";
        return credentialReason(file, size);
    }

    /** 文字檔掃描憑證樣式（與對外訊息遮蔽同一套規則）；二進位檔不掃。 */
    static String credentialReason(Path file, long size) throws Exception {
        byte[] head;
        try (var in = Files.newInputStream(file)) { head = in.readNBytes((int) Math.min(size, 8192)); }
        for (byte b : head) if (b == 0) return null;
        if (size > SCAN_BYTES) return "文字檔超過 " + SCAN_BYTES / 1024 / 1024 + " MB，無法檢查是否含憑證";
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
        return Diagnostics.safe(text).equals(text) ? null : "內容疑似含憑證";
    }

    /** 給討論串的一行說明；全部成功且沒有被拒的檔案時回空字串。 */
    public static String note(Selection selection, String uploadError) {
        StringBuilder out = new StringBuilder();
        if (uploadError != null && !selection.accepted().isEmpty()) {
            out.append("附件未能上傳（").append(uploadError).append("）：")
                    .append(String.join("、", selection.accepted().stream().map(Candidate::name).toList()));
            if (uploadError.contains("missing_scope") || uploadError.contains("not_allowed_token_type"))
                out.append("\n管理者需在該角色的 Slack App 加上 files:write 權限並重新安裝。");
        }
        if (!selection.rejected().isEmpty()) {
            if (!out.isEmpty()) out.append('\n');
            out.append("未上傳的附件：").append(String.join("；", selection.rejected()));
        }
        return out.toString();
    }
}
