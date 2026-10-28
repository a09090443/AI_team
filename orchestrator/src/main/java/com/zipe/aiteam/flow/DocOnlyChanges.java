package com.zipe.aiteam.flow;

import java.util.List;
import java.util.Set;

/**
 * 「只改了文件」由系統依 git diff 判定，不採信 agent 自陳。判定成立時獨立驗證略過建置測試；
 * QA 逐項驗收、程式審查與 PR 真人核准照常。#1012 只改一行 SKILL.md 卻要跑完 Gradle 與前端全部測試。
 *
 * 配置的 docOnlyPaths 可能寫得太寬，所以另有一份程式強制的「一定全跑」名單，配置無法覆蓋。
 */
public final class DocOnlyChanges {
    private DocOnlyChanges() {}

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties",
            "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd", "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml");
    private static final Set<String> BUILD_DIRS = Set.of("gradle", ".mvn", ".github");

    /** 不論配置怎麼寫，這些變更一定要跑建置測試。 */
    public static boolean forcesFullRun(String path) {
        String[] segments = path.split("/");
        String name = segments[segments.length - 1];
        // 目錄名稱不是內容種類；SQL、腳本、設定與文件站程式不能因 docs/** 而免驗。
        if (!(name.endsWith(".md") || name.endsWith(".mdx") || name.endsWith(".txt") || name.endsWith(".rst")
                || name.endsWith(".adoc"))) return true;
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].equals("src")) return true;
            if (i < segments.length - 1 && BUILD_DIRS.contains(segments[i])) return true;
        }
        return BUILD_FILES.contains(name) || name.startsWith("Dockerfile");
    }

    /**
     * 給實作角色的說明：純文件變更不必編譯。#1018 只改一行 Markdown，改派的 Codex 角色仍自行跑 Gradle，
     * 撞上沙箱限制後回報受阻。判定仍以系統的 git diff 為準，這裡只是讓角色不做多餘的建置。
     */
    public static String implementationNotice(List<String> docOnlyPaths) {
        if (docOnlyPaths == null || docOnlyPaths.isEmpty()) return "";
        return "\n純文件變更規則：本次變更若全部落在 " + String.join("、", docOnlyPaths)
                + "，且副檔名全部為 .md、.mdx、.txt、.rst 或 .adoc（SQL、腳本、設定與網站程式不算純文字）"
                + "，而且沒有碰到建置檔（pom.xml、build.gradle(.kts)、settings.gradle(.kts)、gradle.properties、gradlew、mvnw、"
                + "package.json、各種 lockfile、Dockerfile）、任何 src 目錄，或 gradle／.mvn／.github 目錄，"
                + "就不要執行後端／前端的編譯與測試操作，也不要因為沒有建置或測試證據而回報 BLOCKED；"
                + "系統獨立驗證會依 git diff 判定為純文件並同樣略過；QA 核對內容，必要的文件站建置、連結與生成結果仍依計畫驗證。"
                // #1004：builder 照規則略過建置後如實填 false，被實作閘門擋下，分流再要求補跑 9 分鐘的完整建置；#1002 則沒跑也填 true。
                + "判定範圍是整個任務分支相對基準的變更（含其他角色已提交的檔案）。符合時 quality_gate.verify_passed 依你實際做的文件檢查"
                + "（例如 git diff --check、計畫要求的文件站建置）判定，都通過就填 true，不因沒有執行產品建置而填 false。"
                + "只要有一個檔案不符合，就照建置規範完整執行。";
    }

    /** 配置有 docOnlyPaths、變更非空、每個檔都符合且沒有一個在強制全跑名單，才算純文件變更。 */
    public static boolean qualifies(List<String> docOnlyPaths, List<String> changed) {
        if (docOnlyPaths == null || docOnlyPaths.isEmpty() || changed == null || changed.isEmpty()) return false;
        return changed.stream().allMatch(path -> path != null && !path.isBlank()
                && !forcesFullRun(path) && PathRules.allows(docOnlyPaths, path));
    }

    /**
     * 與寫入政策（PathRules.validate）不同：這裡正是要放行 `.agents/**`、`.claude/**`；
     * 但仍不接受絕對路徑、向上跳脫、`.git`，以及等於「全部檔案」的萬用寫法。
     */
    public static void validate(String pattern) {
        TeamCatalog.require(pattern != null && !pattern.isBlank() && !pattern.startsWith("/") && !pattern.contains("\\")
                && !pattern.contains(":") && !pattern.contains("..") && !pattern.startsWith(".git")
                && !pattern.equals("*") && !pattern.equals("**") && !pattern.equals("**/*") && !pattern.equals("**/**"),
                "verification.docOnlyPaths 路徑不安全或過寬：" + pattern);
    }
}
