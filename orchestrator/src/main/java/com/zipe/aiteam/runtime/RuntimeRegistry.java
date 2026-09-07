package com.zipe.aiteam.runtime;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依 id 取用 runtime。
 *
 * 有第二個 runtime 之後，StageRunner 不能再直接注入單一個 AgentRuntime ——
 * 那樣 Spring 會因為兩個候選 bean 而啟動失敗，而且也表達不了「這個角色用這個引擎」。
 */
@Component
public class RuntimeRegistry {

    private final Map<String, AgentRuntime> byId = new LinkedHashMap<>();

    public RuntimeRegistry(List<AgentRuntime> runtimes) {
        for (AgentRuntime r : runtimes) {
            if (r.id() == null || r.id().isBlank() || byId.putIfAbsent(r.id(), r) != null)
                throw new IllegalArgumentException("runtime ID 為空或重複，拒絕啟動");
        }
    }

    /**
     * 找不到就直接失敗，不要默默退回預設。
     *
     * 設定值打錯（例如寫成 "codex-cli"）若安靜地退回 claude-code，
     * 使用者會看著一份以為是 codex 跑出來的結果做比較，那比啟動失敗糟得多。
     */
    public AgentRuntime require(String id) {
        AgentRuntime r = byId.get(id);
        if (r == null) {
            throw new IllegalStateException(
                    "找不到 runtime：" + id + "。可用的有 " + byId.keySet());
        }
        return r;
    }

    /** 供啟動時回報用。 */
    public java.util.Set<String> availableIds() {
        return byId.keySet();
    }
}
