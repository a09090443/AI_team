package com.zipe.aiteam.runtime;

import java.util.Locale;

/**
 * CLI 用法錯誤：指令還沒送到模型就被自己的參數解析擋下來。
 *
 * 與額度耗盡同樣是「本次沒有任何模型工作發生」，因此流程層不該把它算成一次
 * 節點重跑，也不該消耗一次性的受控恢復授權；但兩者原因完全不同，
 * 額度要等，用法錯誤要改程式，所以不共用 QuotaExceeded。
 *
 * 判定條件刻意保守，三項全中才成立：
 *   1. 事件流是空的 —— 有任何一行 JSONL 就代表模型端已經開始，不適用
 *   2. 結束碼為 clap 的用法錯誤碼 2
 *   3. stderr 有明確的用法錯誤字樣
 * 少任何一項都當成一般失敗，寧可多算一次也不要放掉真實的產品失敗。
 */
public final class RuntimeUsageError extends AgentRuntime.RuntimeExecutionException {
    public RuntimeUsageError(String message) { super(message); }

    /** clap 對無法解析的參數固定回傳 2。 */
    public static final int USAGE_EXIT_CODE = 2;

    public static boolean looksLikeUsageError(int exitCode, String events, String stderr) {
        if (exitCode != USAGE_EXIT_CODE) return false;
        if (events != null && !events.isBlank()) return false;
        if (stderr == null) return false;
        String s = stderr.toLowerCase(Locale.ROOT);
        return s.contains("unexpected argument") || s.contains("unrecognized subcommand")
                || s.contains("invalid value for") || s.contains("required arguments were not provided")
                || (s.contains("error:") && s.contains("usage:"));
    }

    /**
     * opencode（yargs）版本的同一件事。
     *
     * 判定條件與上面同構，只有兩處依實測調整：opencode 的用法錯誤結束碼是 1 而不是 2，
     * 而且不認識的旗標不會報錯，是把整份說明印到 stderr 後結束 ——
     * 所以「stderr 出現 usage 說明」本身就是訊號。
     * 事件流非空一律不算，理由與 clap 版相同：有事件就代表模型端已經開始。
     */
    public static boolean looksLikeYargsUsageError(int exitCode, String events, String stderr) {
        if (exitCode == 0) return false;
        if (events != null && !events.isBlank()) return false;
        if (stderr == null) return false;
        String s = stderr.toLowerCase(Locale.ROOT);
        return s.contains("you must provide a message")
                || s.contains("unknown argument") || s.contains("unknown option")
                || (s.contains("positionals:") && s.contains("options:"));
    }

    public static RuntimeUsageError find(Throwable error) {
        for (int i = 0; error != null && i < 10; i++, error = error.getCause())
            if (error instanceof RuntimeUsageError u) return u;
        return null;
    }
}
