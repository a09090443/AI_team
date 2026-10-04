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
                + "，而且沒有碰到建置檔（pom.xml、build.gradle(.kts)、settings.gradle(.kts)、gradle.properties、gradlew、mvnw、"
                + "package.json、各種 lockfile、Dockerfile）、任何 src 目錄，或 gradle／.mvn／.github 目錄，"
                + "就不要執行後端／前端的編譯與測試操作，也不要因為沒有建置或測試證據而回報 BLOCKED；"
                + "系統獨立驗證會依 git diff 判定為純文件並同樣略過，QA 以讀檔驗收。文件本身的檢查（例如連結、表格格式）照常做。"
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
