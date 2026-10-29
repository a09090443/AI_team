package com.zipe.aiteam.flow;

import java.nio.file.*;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/** 技術棧無關的操作契約；宣告不等於授權，也不等於操作已成功。 */
public final class OperationContract {
    private OperationContract() {}
    /**
     * 系統內建、所有 v2 配置的測試計畫與驗收都可引用的唯讀驗收方式：以角色自己的 Read／Grep 讀已提交的檔案內容判定。
     * 它**不是指令**：不執行任何程式、不寫檔，所以不經派工前檢查或指令白名單。只適用於從檔案內容就能判斷的驗收項目；
     * 程式行為仍須引用真正的操作。#1010／#1011 純文件修改因任何配置都寫不出「讀某檔某行」的固定指令而永遠卡在測試計畫。
     */
    public static final String INSPECT_CONTENT = "inspect-content";
    public static boolean inspectAvailable(TeamDefinition definition) { return definition.version() >= 2; }
    public static Map<String, Operation> forNode(TeamDefinition definition, String node) {
        Map<String, Operation> selected = new LinkedHashMap<>();
        if (definition.project().operations() != null) definition.project().operations().forEach((id, operation) -> {
            if (operation.nodes().contains(node)) selected.put(id, operation);
        });
        return selected;
    }
    /** 所有固定操作宣告的輸出目錄（writes）；舊快照沒有操作時為空。 */
    public static Set<String> outputDirs(TeamDefinition definition) {
        Set<String> dirs = new TreeSet<>();
        if (definition.project().operations() != null)
            definition.project().operations().values().forEach(op -> { if (op.writes() != null) dirs.addAll(op.writes()); });
        return dirs;
    }
    public static void validate(TeamDefinition definition) {
        var operations = definition.project().operations();
        if (operations == null) return; // 舊快照不自動補權限。
        TeamCatalog.require(operations.size() <= 30, "操作契約數量超過上限");
        operations.forEach((id, op) -> {
            TeamCatalog.require(id.matches("[a-z][a-z0-9_-]*") && op != null, "無效操作 ID");
            TeamCatalog.require(!INSPECT_CONTENT.equals(id), "操作 ID " + INSPECT_CONTENT + " 為系統保留的內容檢視，不能宣告成指令");
            TeamCatalog.require(!FileComparison.OPERATION.equals(id), "操作 ID " + FileComparison.OPERATION + " 為系統保留的檔案比對");
            relative(op.directory());
            TeamCatalog.require(op.argv() != null && !op.argv().isEmpty() && op.argv().size() <= 32
                    // ${teams} 在 TeamCatalog.load 展開成實際路徑；未展開的範本也要能通過靜態驗證。
                    && op.argv().stream().allMatch(s -> s != null && s.replace(TeamCatalog.TEAMS_DIR, "/teams").matches("[A-Za-z0-9_./:=+@,-]{1,200}")), "操作 argv 僅允許明確參數，不支援 shell 表達式：" + id);
            // #1043：Codex CLI（0.156.1 實測）在啟動前拒絕 rm -f 類指令，任務跑到這步才失敗；載入配置時就擋。
            TeamCatalog.require(!"rm".equals(op.argv().getFirst()),
                    "操作不可使用 rm（Codex 會拒絕執行）：" + id + "；清空目錄請改用 find <目錄> -mindepth 1 -delete");
            TeamCatalog.require(definition.project().requiredTools().contains(op.argv().getFirst())
                    && (!Set.of("sh", "bash", "cmd", "powershell", "pwsh", "zsh").contains(op.argv().getFirst())
                        || pinnedScript(op)), "操作執行檔未宣告或為未釘選的 shell：" + id);
            TeamCatalog.require(op.scriptSha256() == null || pinnedScript(op), "scriptSha256 只支援固定的 bash 相對腳本：" + id);
            TeamCatalog.require(op.nodes() != null && !op.nodes().isEmpty() && op.writes() != null, "操作缺 nodes/writes：" + id);
            op.writes().forEach(OperationContract::relative);
            for (String nodeId : op.nodes()) {
                Node node = definition.nodes().get(nodeId);
                TeamCatalog.require(node != null && node.kind() == Kind.AGENT, "操作引用不存在的 agent 節點：" + id);
                TeamCatalog.require(node.scope() != Scope.READ_ONLY || op.writes().isEmpty(), "唯讀節點不能宣告寫入操作：" + id);
                if (node.scope() == Scope.LIMITED) for (String path : op.writes()) {
                    TeamCatalog.require(PathRules.allows(node.writablePaths(), path + "/")
                            && !PathRules.allows(definition.project().protectedPaths(), path), "操作寫入超出節點政策：" + id + "／" + path);
                }
            }
        });
    }
    static boolean pinnedScript(Operation op) {
        return op.scriptSha256() != null && op.scriptSha256().matches("[0-9a-f]{64}")
                && op.argv() != null && op.argv().size() == 2 && "bash".equals(op.argv().getFirst())
                && op.argv().get(1).matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+\\.sh")
                && Arrays.stream(op.argv().get(1).split("/")).noneMatch(s -> s.equals("..") || s.equals("."));
    }
    static List<Path> scriptPins(TeamDefinition definition, String node, Path cwd) throws Exception {
        List<Path> pins = new ArrayList<>();
        for (Operation op : forNode(definition, node).values()) if (op.scriptSha256() != null) {
            if (!pinnedScript(op)) throw new IllegalStateException("未核准的腳本操作");
            Path script = resolve(resolve(cwd, op.directory()), op.argv().get(1));
            if (!Files.isRegularFile(script, LinkOption.NOFOLLOW_LINKS)
                    || !IndependentVerifier.hash(script).equals(op.scriptSha256()))
                throw new IllegalStateException("腳本內容與核准版本不符，禁止執行：" + script);
            pins.add(script);
        }
        return pins;
    }
    static void relative(String value) {
        TeamCatalog.require(value != null && (value.equals(".") || value.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*"))
                && Arrays.stream(value.split("/")).noneMatch(".."::equals), "操作路徑須為安全相對路徑");
    }
    static Path resolve(Path root, String relative) throws Exception {
        relative(relative);
        Path canonical = root.toRealPath();
        Path target = canonical.resolve(relative).normalize();
        Path existing = target;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (!target.startsWith(canonical) || !existing.toRealPath().equals(existing))
            throw new IllegalStateException("操作路徑含 symlink 或越界：" + relative);
        return target;
    }
}
