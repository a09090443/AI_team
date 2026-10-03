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
    public static void validate(TeamDefinition definition) {
        var operations = definition.project().operations();
        if (operations == null) return; // 舊快照不自動補權限。
        TeamCatalog.require(operations.size() <= 30, "操作契約數量超過上限");
        operations.forEach((id, op) -> {
            TeamCatalog.require(id.matches("[a-z][a-z0-9_-]*") && op != null, "無效操作 ID");
            TeamCatalog.require(!INSPECT_CONTENT.equals(id), "操作 ID " + INSPECT_CONTENT + " 為系統保留的內容檢視，不能宣告成指令");
            relative(op.directory());
            TeamCatalog.require(op.argv() != null && !op.argv().isEmpty() && op.argv().size() <= 32
                    && op.argv().stream().allMatch(s -> s != null && s.matches("[A-Za-z0-9_./:=+@,-]{1,200}")), "操作 argv 僅允許明確參數，不支援 shell 表達式：" + id);
            TeamCatalog.require(definition.project().requiredTools().contains(op.argv().getFirst())
                    && !Set.of("sh", "bash", "cmd", "powershell", "pwsh", "zsh").contains(op.argv().getFirst()), "操作執行檔未宣告或為 shell：" + id);
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
