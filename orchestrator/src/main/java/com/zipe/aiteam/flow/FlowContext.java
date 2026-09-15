package com.zipe.aiteam.flow;

import com.zipe.aiteam.runtime.Diagnostics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * flow.context 是滾動的交接紀錄。
 * 舊版以 clip(context + 新內容, MAX) append，而 clip 留頭截尾；
 * context 一旦達到上限就永久凍結，之後所有受阻報告、責任路由與使用者補充都會被靜默丟棄。
 * 這裡改為以段落為單位淘汰最舊的內容，確保最新事件一定送得到下游節點。
 */
public final class FlowContext {
    private FlowContext() {}

    public static final int MAX = 20000;
    /** 單筆事件的上限；先個別截斷，才能保證總量內至少留得住最近數筆。 */
    public static final int ENTRY_MAX = 6000;
    static final String MARK = "\n〔交接紀錄〕\n";
    static final String DROPPED = "〔較舊的交接紀錄已因長度上限淘汰；完整內容見本機紀錄、已保存報告與未解除的受阻發現〕\n";

    public static String append(String context, String entry) {
        String base = context == null ? "" : context;
        // 曾經淘汰過就一直標示，否則內容縮回上限內時，讀者會誤以為這份紀錄是完整的。
        boolean dropped = base.startsWith(DROPPED);
        if (dropped) base = base.substring(DROPPED.length());
        String merged = base + MARK + Diagnostics.clip(entry, ENTRY_MAX);
        if (!dropped && merged.length() <= MAX) return merged;

        int budget = MAX - DROPPED.length();
        if (merged.length() > budget) {
            dropped = true;
            List<String> parts = new ArrayList<>(Arrays.asList(merged.split(Pattern.quote(MARK), -1)));
            // 舊 context 沒有段落標記，會整段成為單一 part，退化為保留最新尾端。
            while (parts.size() > 1 && joined(parts).length() > budget) parts.remove(0);
            merged = joined(parts);
            if (merged.length() > budget) merged = merged.substring(merged.length() - budget);
        }
        return dropped ? DROPPED + merged : merged;
    }

    private static String joined(List<String> parts) { return String.join(MARK, parts); }
}
