package com.zipe.aiteam.flow;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** 使用 / 與 ** 的跨平台專案相對路徑；不接受絕對路徑或向上跳脫。 */
public final class PathRules {
    private PathRules() {}
    public static void validate(String pattern) {
        TeamCatalog.require(pattern != null && !pattern.isBlank() && !pattern.startsWith("/")
                && !pattern.contains("\\") && !pattern.contains(":") && !pattern.contains("..")
                && !pattern.startsWith(".git") && !pattern.startsWith(".agents") && !pattern.startsWith(".codex")
                && !pattern.equals("**") && !pattern.equals("**/*"), "不安全的專案路徑政策：" + pattern);
    }
    public static boolean matches(String glob, String path) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { regex.append("(?:.*/)?"); i++; }
                else regex.append(".*");
            } else if (c == '*') regex.append("[^/]*");
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return path.matches(regex.append('$').toString());
    }
    public static boolean allows(List<String> patterns, String path) { return patterns != null && patterns.stream().anyMatch(p -> matches(p, path)); }
    /**
     * 明確目錄（無萬用字元）不存在時先建立：它已在配置中逐字核准，建立空目錄不擴大權限。
     * #1015：只改文件的實作沒跑建置，build/ 不存在，驗收的操作預檢因此判定越界。
     * 萬用字元不猜測缺少的目錄，全部都找不到時 fail closed。
     */
    public static List<Path> writableRoots(Path workspace, List<String> patterns) throws java.io.IOException {
        Path root = workspace.toRealPath();
        List<Path> found = new ArrayList<>();
        for (String pattern : patterns) {
            validate(pattern);
            TeamCatalog.require(pattern.endsWith("/**"), "限寫政策須指向目錄並以 /** 結尾");
            String base = pattern.substring(0, pattern.length() - 3);
            if (!base.contains("*")) {
                Path p = root.resolve(base).normalize();
                TeamCatalog.require(p.startsWith(root) && !p.equals(root), "寫入路徑越界");
                // 僅明確、已核准的目錄政策可建立掛載根；先拒絕所有 symlink 祖先。
                Path existing = p;
                while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
                TeamCatalog.require(existing.toRealPath().equals(existing), "symlink 寫入政策越界");
                Files.createDirectories(p);
                found.add(p);
            } else try (var paths = Files.walk(root)) {
                paths.filter(Files::isDirectory).filter(p -> matches(base, root.relativize(p).toString().replace('\\', '/')))
                        .forEach(found::add);
            }
        }
        for (Path p : found) TeamCatalog.require(p.toRealPath().startsWith(root) && !p.toRealPath().equals(root)
                && p.toRealPath().equals(p.toAbsolutePath().normalize()), "symlink 寫入政策越界");
        TeamCatalog.require(!found.isEmpty(), "限寫政策找不到任何已存在目錄，拒絕放寬權限");
        return List.copyOf(found);
    }
}
