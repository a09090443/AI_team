package com.zipe.aiteam.flow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.zipe.aiteam.flow.TeamDefinition.*;

/**
 * 系統獨立驗證要跑哪些指令：依 git 實際變更與配置的 commandScopes／qaOnlyPaths 判定，不採信 agent 自陳。
 * #1044 只改文件、網站與設定範本，每輪仍跑 23～30 分鐘的後端與前端全套測試，同一個 commit 也重跑。
 *
 * 規則（任何不確定都全跑）：
 * - 沒設 commandScopes、查不到變更清單、清單為空：全跑。
 * - 每個變更檔案歸類：純文件（沿用 docOnlyPaths 與純文字規則）／QA 專屬（qaOnlyPaths，含其中的建置檔）／
 *   屬於某些指令範圍／都不屬於。只要有一個「都不屬於」就全跑。
 * - 其餘只跑範圍內有變更的指令。
 * 需要跑的指令若在同一任務、同一配置版本下以相同輸入指紋通過過，就重用那次結果（見 {@link #fingerprints}）。
 */
public final class VerificationPlan {
    private VerificationPlan() {}

    public enum Action { RUN, SKIP, REUSE }
    /**
     * 每個配置指令一筆。RUN／REUSE 帶紀錄路徑與雜湊；REUSE 另帶來源 commit。matched 最多列 20 個命中檔案。
     */
    public record Decision(int index, List<String> argv, Action action, String reason, List<String> matched,
                           String fingerprint, String log, String sha256, String sourceHead) {}
    /** 同一任務內通過過的指令結果，供相同輸入指紋時重用；FlowRun.verificationHistory 保存最多 20 筆。 */
    public record HistoryEntry(List<String> argv, String fingerprint, String configuration, String head, String log, String sha256) {}
    static final int HISTORY_LIMIT = 20;
    static final int MATCHED_LIMIT = 20;

    /** fullRun 時 fullRunReason 說明原因；否則 matched.get(i) 非空的指令才需要跑。 */
    public record Selection(boolean fullRun, String fullRunReason, List<List<String>> matched) {
        public boolean needs(int index) { return fullRun || !matched.get(index).isEmpty(); }
    }

    public static boolean scoped(Verification spec) { return spec.commandScopes() != null; }

    public static Selection select(Verification spec, List<String> changed) {
        int n = spec.commands().size();
        List<List<String>> matched = new ArrayList<>();
        for (int i = 0; i < n; i++) matched.add(new ArrayList<>());
        if (!scoped(spec)) return new Selection(true, "未設定 commandScopes，依配置全跑", matched);
        if (changed == null) return new Selection(true, "查不到任務基準以來的變更檔案，全跑", matched);
        if (changed.isEmpty()) return new Selection(true, "沒有變更檔案，無法判定範圍，全跑", matched);
        for (String path : changed) {
            if (path == null || path.isBlank()) return new Selection(true, "變更清單含空白路徑，全跑", matched);
            if (qaOnly(spec, path) || docText(spec, path)) continue;
            boolean hit = false;
            for (int i = 0; i < n; i++) if (PathRules.allows(spec.commandScopes().get(i), path)) {
                hit = true;
                if (matched.get(i).size() < MATCHED_LIMIT) matched.get(i).add(path);
            }
            if (!hit) return new Selection(true, "檔案不屬於任何指令範圍，也不是純文件或 QA 專屬路徑：" + path + "，全跑", matched);
        }
        return new Selection(false, null, matched);
    }

    static boolean qaOnly(Verification spec, String path) { return spec.qaOnlyPaths() != null && PathRules.allows(spec.qaOnlyPaths(), path); }
    static boolean docText(Verification spec, String path) {
        return spec.docOnlyPaths() != null && !DocOnlyChanges.forcesFullRun(path) && PathRules.allows(spec.docOnlyPaths(), path);
    }

    /**
     * 每個指令的輸入指紋。tree 為 HEAD 的 `git ls-tree -r` 條目（「mode sha<TAB>path」）。
     * 有範圍時納入該指令範圍內的檔案，以及「共用檔」（不屬於任何範圍、也不是純文件或 QA 專屬）——共用檔一變，所有重用都失效；
     * 沒設範圍時納入整棵樹，所以只有同一份內容才會重用。
     */
    public static List<String> fingerprints(Verification spec, List<String> tree) {
        int n = spec.commands().size();
        List<StringBuilder> material = new ArrayList<>();
        for (int i = 0; i < n; i++) material.add(new StringBuilder(String.join("\u0000", spec.commands().get(i))).append('\n'));
        for (String entry : tree) {
            int tab = entry.indexOf('\t');
            if (tab < 0) throw new IllegalStateException("無法解析 git ls-tree 條目");
            String path = entry.substring(tab + 1);
            if (!scoped(spec)) { material.forEach(m -> m.append(entry).append('\n')); continue; }
            boolean any = false;
            for (int i = 0; i < n; i++) if (PathRules.allows(spec.commandScopes().get(i), path)) { material.get(i).append(entry).append('\n'); any = true; }
            if (!any && !qaOnly(spec, path) && !docText(spec, path)) material.forEach(m -> m.append(entry).append('\n'));
        }
        return material.stream().map(m -> sha256(m.toString())).toList();
    }

    /** 載入配置時的檢查；qaOnlyPaths 與指令範圍的重疊用保守的固定前綴判斷，寧可拒絕也不放寬。 */
    static void validate(Verification v) {
        if (v.commandScopes() != null) {
            TeamCatalog.require(v.commandScopes().size() == v.commands().size(), "verification.commandScopes 須與 commands 一一對應");
            for (var scope : v.commandScopes()) {
                TeamCatalog.require(scope != null && !scope.isEmpty() && scope.size() <= 20, "每個指令的 commandScopes 須為 1～20 條");
                scope.forEach(DocOnlyChanges::validate);
            }
        }
        if (v.qaOnlyPaths() != null) {
            TeamCatalog.require(v.commandScopes() != null, "verification.qaOnlyPaths 須與 commandScopes 一起設定");
            TeamCatalog.require(!v.qaOnlyPaths().isEmpty() && v.qaOnlyPaths().size() <= 20, "verification.qaOnlyPaths 若有設定須為 1～20 條");
            v.qaOnlyPaths().forEach(DocOnlyChanges::validate);
            for (String qa : v.qaOnlyPaths()) for (var scope : v.commandScopes()) for (String s : scope)
                TeamCatalog.require(!mayOverlap(qa, s), "verification.qaOnlyPaths 不能與指令範圍重疊：" + qa + "／" + s);
        }
    }
    static boolean mayOverlap(String a, String b) {
        String x = base(a), y = base(b);
        return x.isEmpty() || y.isEmpty() || x.startsWith(y) || y.startsWith(x);
    }
    /** 萬用字元前的固定目錄（含結尾 /）；沒有萬用字元就是整個路徑。 */
    private static String base(String glob) {
        int star = glob.indexOf('*');
        if (star < 0) return glob;
        int slash = glob.lastIndexOf('/', star);
        return slash < 0 ? "" : glob.substring(0, slash + 1);
    }

    public static String summary(List<Decision> decisions) {
        long run = decisions.stream().filter(d -> d.action() == Action.RUN).count();
        long skip = decisions.stream().filter(d -> d.action() == Action.SKIP).count();
        long reuse = decisions.stream().filter(d -> d.action() == Action.REUSE).count();
        return "執行 " + run + "、略過 " + skip + "、重用 " + reuse;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("無法計算輸入指紋", e); }
    }
}
