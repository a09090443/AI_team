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
