package com.zipe.aiteam.task;

import com.zipe.aiteam.slack.SlackNotifier.Identity;

/** 對話另存為唯讀子請求，不覆蓋原任務的驗收與 checkpoint。 */
public record DirectRequest(String taskId, Identity role, String parentId, String context) {}
