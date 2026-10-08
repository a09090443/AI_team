package com.zipe.aiteam.flow;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 分辨「系統自己沒跑起來」與「工作本身有問題」。
 *
 * 前者不該拿去問人：#1014／#1016 的 52 次停止裡，有 15 次是啟動失敗、參數過長（2026-10-08 起不重試，見 transientFailure）、
 * 要接續的對話不存在這類系統問題，每次都要人按一下才會再試。這些應該自己重試，
 * 連續失敗才交給人。後者（agent 回報受阻、越權、契約不符）維持原本的處理。
 */
final class SystemFailure {
    private SystemFailure() {}

    /** 同一個節點最多自動重試幾次；超過就交給人，避免把同一個壞掉的環境重跑到底。 */
    static final int RETRY_LIMIT = 2;

    private static final List<String> SIGNATURES = List.of(
            "Cannot run program",            // 連執行檔都起不來
            "No conversation found with session ID",
            "Temporary failure in name resolution",
            "Connection reset",
            "Connection refused",
            "Broken pipe",
            "Failed to start transient service",  // systemd-run 暫時性失敗
            "Read timed out",
            // Codex 與服務端的串流被切斷（#1019 兩次停給人、使用者手動續跑都成功）；CLI 自己重連 5 次仍失敗才會結束。
            "stream disconnected before completion",
            "websocket closed by server");

    /** 只認明確的系統層訊號；認不出來的一律當成工作問題，交給人。 */
    static boolean transientFailure(Throwable error) {
        // 參數超過作業系統上限每次必定重現；它的訊息也會提到 systemd-run，必須先排除。
        // #1029 被當暫時性失敗重試兩次，每次先重跑約 25 分鐘的獨立驗證才再失敗。
        Set<Throwable> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && checked.add(e); e = e.getCause())
            if (e instanceof com.zipe.aiteam.sandbox.ArgumentListTooLong
                    || (e.getMessage() != null && e.getMessage().contains(com.zipe.aiteam.sandbox.ArgumentListTooLong.SIGNATURE))) return false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause()) {
            String message = e.getMessage();
            if (message != null && SIGNATURES.stream().anyMatch(message::contains)) return true;
            if (e instanceof IOException && message != null && message.contains("systemd-run")) return true;
        }
        return false;
    }
}
