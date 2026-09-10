package com.zipe.aiteam.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** 僅準備 Codex 唯讀掛載點，不安裝 skill、不改既有 metadata 或放寬沙箱。 */
final class CodexSandboxPreparation {
    private CodexSandboxPreparation() {}

    static void prepare(Path workspace) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !root.equals(root.toRealPath())) {
            throw new IOException("Codex 工作目錄必須是既有 canonical 目錄，拒絕 symlink：" + root);
        }
        Path git = root.resolve(".git");
        if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Codex 工作目錄缺少實體 .git 目錄或 worktree 檔案，拒絕建立假 repository：" + root);
        }
        // 先驗證全部，再建立；已存在的目錄及其內容完全不動。
        for (String name : List.of(".agents", ".codex")) {
            Path path = root.resolve(name);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Codex 掛載點不是實體目錄，拒絕覆寫：" + path);
            }
        }
        for (String name : List.of(".agents", ".codex")) {
            Path path = root.resolve(name);
            try { Files.createDirectory(path); }
            catch (FileAlreadyExistsException e) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw e;
            }
        }
    }
}
