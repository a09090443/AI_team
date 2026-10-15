package com.zipe.aiteam.flow;

import com.zipe.aiteam.runtime.Diagnostics;
import com.zipe.aiteam.task.Task;

/**
 * 每則停止訊息、狀態卡與承接討論串開頭的一行任務目標。
 *
 * 對話一長、或承接成新討論串後，使用者看不出這個任務原本要做什麼；
 * 同一需求開了多個任務時也分不出來（bestpartner #1040～#1042）。只用已保存的需求原文，不呼叫模型。
 */
public final class TaskGoal {
    private TaskGoal() {}

    static final int MAX = 60;

    public static String line(Task task, FlowRun flow) {
        return line(task, flow == null ? null : flow.continuationOf);
    }

    public static String line(Task task, String continuationOf) {
        String request = task.requestText() == null ? "" : Diagnostics.safe(task.requestText()).replaceAll("\\s+", " ").strip();
        if (request.length() > MAX) request = request.substring(0, MAX) + "…";
        if (request.isEmpty()) request = "（沒有需求原文）";
        String from = continuationOf == null ? "" : "（承接自 #" + continuationOf + "）";
        return "🎯 " + request + from;
    }
}
