package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.zipe.aiteam.runtime.CommandRuns;
import com.zipe.aiteam.task.FailureReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把實作節點的「品質檢查通過」自陳，和 CLI 紀錄裡實際的結束碼對照。
 *
 * #1001：實作執行配置的 backend-test 得到結束碼 1，卻回報 SUCCESS／verify_passed=true，
 * 系統依自陳放行，跑了 16 分鐘注定失敗的獨立驗證才又交回實作。
 *
 * 只認「與配置操作完全相同的指令」，後面只允許接重導向或管線；agent 自己跑的子集測試、探查指令都不算，
 * 以免誤判。同一個操作以本輪最後一次執行為準：先失敗、修好後重跑成功是正常流程。
 */
public final class CommandEvidence {
    private CommandEvidence() {}

    /** 自陳成功但配置操作最後一次執行失敗時回傳原因；沒有矛盾或沒有證據時回 null。 */
    public static String contradiction(Map<String, TeamDefinition.Operation> operations,
                                       List<CommandRuns.CommandRun> runs, JsonNode report) {
        if (report == null || FailureReport.implementationIncomplete(report) || operations == null || runs == null || runs.isEmpty())
            return null;
        List<String> failed = new ArrayList<>();
        for (var entry : operations.entrySet()) {
            String expected = String.join(" ", entry.getValue().argv());
            CommandRuns.CommandRun last = null;
            for (CommandRuns.CommandRun run : runs) if (matches(run.command(), expected)) last = run;
            if (last != null && last.failed())
                failed.add(entry.getKey() + "（" + expected + "，結束碼 " + last.exitCode() + "）");
        }
        if (failed.isEmpty()) return null;
        return "實作回報品質檢查通過，但 CLI 紀錄顯示本輪最後一次執行的配置操作失敗：" + String.join("；", failed)
                + "。系統不採用這份成功回報；失敗原因若在本次修改範圍外，要回報受阻並附原始錯誤。";
    }

    /** 完全相同，或後面只接重導向、管線與指令分隔（例如 `2>&1 | tail -50`）。 */
    static boolean matches(String command, String expected) {
        String actual = normalize(command);
        if (actual.equals(expected)) return true;
        if (!actual.startsWith(expected + " ")) return false;
        String rest = actual.substring(expected.length() + 1);
        return rest.startsWith("2>") || rest.startsWith(">") || rest.startsWith("|") || rest.startsWith("&&") || rest.startsWith(";");
    }

    private static String normalize(String command) {
        return command == null ? "" : command.strip().replaceAll("\\s+", " ");
    }
}
