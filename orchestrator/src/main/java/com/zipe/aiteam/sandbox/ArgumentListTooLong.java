package com.zipe.aiteam.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 子行程因 E2BIG（Argument list too long）啟動失敗時，附上參數大小診斷。
 *
 * JDK 只回「Exec failed, error: 7 (Argument list too long)」，看不出是哪個參數過長。
 * bestpartner #1029 的 QA 因此停下時，說明 agent 只能寫「無法確定原因」，系統又把它當暫時性失敗重試兩次
 * （每次先重跑約 25 分鐘的獨立驗證）。實際原因是 node_modules 內 1643 個 package.json 被釘成唯讀，
 * 併進 Codex 的單一 permissions 參數，約 187 KB，超過 Linux 單一參數 128 KB 上限。
 * 這個錯誤每次必定重現，{@code SystemFailure} 據此不自動重試。
 */
public final class ArgumentListTooLong extends IOException {
    /** Linux 單一參數上限（MAX_ARG_STRLEN，32 頁）。 */
    static final int SINGLE_ARGUMENT_LIMIT = 131072;
    public static final String SIGNATURE = "Argument list too long";

    ArgumentListTooLong(String message, IOException cause) { super(message, cause); }

    /** 啟動子行程；E2BIG 時改拋帶診斷的例外，其他錯誤原樣拋出。 */
    public static Process start(ProcessBuilder builder) throws IOException {
        try {
            return builder.start();
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains(SIGNATURE)) throw e;
            throw new ArgumentListTooLong(describe(builder.command()), e);
        }
    }

    static String describe(List<String> command) {
        long total = 0; int largest = -1; int largestBytes = -1, pins = 0;
        for (int i = 0; i < command.size(); i++) {
            int bytes = command.get(i).getBytes(StandardCharsets.UTF_8).length;
            total += bytes + 1;
            if (bytes > largestBytes) { largestBytes = bytes; largest = i; }
            if (command.get(i).startsWith("ReadOnlyPaths=")) pins++;
        }
        String head = largest < 0 ? "" : command.get(largest);
        head = head.length() > 120 ? head.substring(0, 120) + "…" : head;
        String program = command.isEmpty() ? "?" : command.get(0);
        return "啟動 " + program + " 失敗：參數超過作業系統上限（每次都會重現，重試無效）。共 " + command.size() + " 個參數、約 " + total
                + " 位元組；最大的是第 " + largest + " 個，" + largestBytes + " 位元組"
                + (largestBytes > SINGLE_ARGUMENT_LIMIT ? "，超過單一參數上限 " + SINGLE_ARGUMENT_LIMIT : "")
                + "，開頭為「" + head + "」；沙箱唯讀釘選（ReadOnlyPaths）" + pins + " 個。"
                + "常見原因是唯讀釘選路徑太多：工作區內符合 protectedPaths 的檔案逐一列入，工作區若有大量相依或建置產物目錄就會暴增。";
    }
}
