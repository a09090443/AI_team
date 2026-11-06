package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.security.MessageDigest;
import java.util.*;

/** Only data crosses the model boundary. Directory-relative, no-follow IO requires Linux secure streams. */
final class RulesProposal {
    private RulesProposal() {}
    static final int MAX_FILE_BYTES = 65536, MAX_TOTAL_BYTES = 262144, MAX_FILES = 10;
    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["changes"],"properties":{"changes":{
              "type":"array","maxItems":10,"items":{"type":"object","additionalProperties":false,
              "required":["path","sha256","content"],"properties":{"path":{"type":"string"},
              "sha256":{"type":"string"},"content":{"type":"string"}}}}}}
            """;
    record Original(String path, String sha256, String content) {}

    static boolean eligible(TeamDefinition.Node node, List<String> paths) {
        return node.protectedPathFallback() != null
                && Boolean.TRUE.equals(node.protectedPathFallback().programRules())
                && !paths.isEmpty() && paths.size() <= MAX_FILES
                && paths.stream().allMatch(RulesProposal::safePath);
    }
    static boolean safePath(String path) {
        return path != null && path.startsWith(".claude/rules/") && path.endsWith(".md")
                && path.length() <= 512 && !path.contains("\\") && !path.contains(":") && !path.contains("*") && !path.contains("?")
                && path.chars().noneMatch(Character::isISOControl)
                && Arrays.stream(path.split("/", -1)).allMatch(p -> !p.isBlank() && !p.equals(".") && !p.equals(".."))
                && !ProtectedPathFallback.denied(path);
    }
    static boolean protectedBy(List<String> patterns, String path) {
        for (String current = path; ; current = current.substring(0, current.lastIndexOf('/'))) {
            if (PathRules.allows(patterns, current)) return true;
            if (!current.contains("/")) return false;
        }
    }
    static List<Original> capture(Path workspace, List<String> paths) throws IOException {
        if (paths.isEmpty() || paths.size() > MAX_FILES || new HashSet<>(paths).size() != paths.size())
            throw new IOException("rules 清單數量無效");
        List<Original> result = new ArrayList<>(); int total = 0;
        for (String path : paths) {
            if (!safePath(path)) throw new IOException("不允許的 rules 路徑");
            try (Target target = Target.open(workspace, path)) {
                byte[] bytes = target.read(); total += bytes.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("rules 原文總量超過上限");
                String content;
                try { content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
                catch (java.nio.charset.CharacterCodingException e) { throw new IOException("rules 不是 UTF-8", e); }
                if (content.indexOf('\0') >= 0) throw new IOException("rules 含二進位內容");
                result.add(new Original(path, sha(bytes), content));
            }
        }
        return List.copyOf(result);
    }
    /** Complete proposal is validated before any write; model cannot choose the authorization set. */
    static Map<String, byte[]> validate(JsonNode proposal, List<Original> originals) throws IOException {
        if (proposal == null || !proposal.isObject() || proposal.size() != 1 || !proposal.path("changes").isArray()
                || proposal.path("changes").size() != originals.size()) throw new IOException("rules 提案格式或檔案數不符");
        Map<String, Original> expected = new HashMap<>(); originals.forEach(o -> expected.put(o.path(), o));
        Map<String, byte[]> changes = new LinkedHashMap<>(); int total = 0;
        for (JsonNode change : proposal.path("changes")) {
            String path = change.path("path").asText(); Original original = expected.get(path);
            if (!change.isObject() || change.size() != 3 || !change.path("path").isTextual()
                    || !change.path("sha256").isTextual() || !change.path("content").isTextual()
                    || !safePath(path) || original == null || !original.sha256().equals(change.path("sha256").asText())
                    || changes.containsKey(path)) throw new IOException("rules 提案路徑、雜湊或欄位不符");
            String content = change.path("content").textValue();
            if (content.isBlank() || content.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(content))
                throw new IOException("rules 新文不可空白或含無效文字");
            if (content.length() > MAX_FILE_BYTES) throw new IOException("rules 提案超過上限");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8); total += bytes.length;
            if (bytes.length > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) throw new IOException("rules 提案超過上限");
            changes.put(path, bytes);
        }
        return changes;
    }
    /** Validation failures write nothing. IO failures after a write abort the stage; never silently fall back. */
    static List<String> apply(Path workspace, List<Original> originals, Map<String, byte[]> changes) throws IOException {
        List<Target> opened = new ArrayList<>();
        try {
            for (Original original : originals) {
                Target target = Target.open(workspace, original.path()); opened.add(target);
                if (!original.sha256().equals(sha(target.read()))) throw new IOException("rules 原文已變更");
            }
            // Prepare every replacement before replacing a destination. No executable payload is evaluated.
            for (int i = 0; i < originals.size(); i++) opened.get(i).prepare(changes.get(originals.get(i).path()));
            List<String> changed = new ArrayList<>();
            for (int i = 0; i < originals.size(); i++) {
                Original original = originals.get(i); Target target = opened.get(i);
                if (!original.sha256().equals(sha(target.read()))) throw new IOException("rules 套用前原文已變更");
                if (!Arrays.equals(original.content().getBytes(StandardCharsets.UTF_8), changes.get(original.path()))) {
                    target.replace(); changed.add(original.path());
                }
            }
            return List.copyOf(changed);
        } finally { for (Target target : opened) target.close(); }
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Anchored directory handles prevent a parent symlink swap from redirecting writes. */
    private static final class Target implements AutoCloseable {
        final List<DirectoryStream<Path>> handles = new ArrayList<>();
        SecureDirectoryStream<Path> parent; Path name, temp;
        static Target open(Path workspace, String path) throws IOException {
            Target target = new Target();
            try {
                if (!workspace.toRealPath().equals(workspace.toAbsolutePath().normalize())) throw new IOException("工作區不可含符號連結");
                DirectoryStream<Path> root = Files.newDirectoryStream(workspace); target.handles.add(root);
                if (!(root instanceof SecureDirectoryStream<Path> secure)) throw new IOException("平台不支援安全目錄操作，使用既有改派");
                target.parent = secure;
                Path relative = Path.of(path);
                for (int i = 0; i < relative.getNameCount() - 1; i++) {
                    target.parent = target.parent.newDirectoryStream(relative.getName(i), LinkOption.NOFOLLOW_LINKS);
                    target.handles.add(target.parent);
                }
                target.name = relative.getFileName();
                return target;
            } catch (IOException | RuntimeException e) { target.close(); throw e; }
        }
        byte[] read() throws IOException {
            var attrs = parent.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) throw new IOException("rules 必須為上限內的一般檔案");
            try (SeekableByteChannel channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.allocate(MAX_FILE_BYTES + 1);
                while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
                if (buffer.position() > MAX_FILE_BYTES) throw new IOException("rules 超過上限");
                return Arrays.copyOf(buffer.array(), buffer.position());
            }
        }
        void prepare(byte[] bytes) throws IOException {
            if (bytes == null) throw new IOException("缺少 rules 新文");
            temp = Path.of(".rules-proposal-" + UUID.randomUUID());
            try (SeekableByteChannel channel = parent.newByteChannel(temp,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
            }
        }
        void replace() throws IOException { parent.move(temp, parent, name); temp = null; }
        public void close() {
            if (temp != null) try { parent.deleteFile(temp); } catch (IOException ignored) { }
            Collections.reverse(handles);
            for (DirectoryStream<Path> handle : handles) try { handle.close(); } catch (IOException ignored) { }
        }
    }
}
