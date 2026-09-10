package com.zipe.aiteam.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 單次執行的隔離條件。
 *
 * 這是一份「宣告」而非「請求」—— 呼叫端說明這次執行允許碰什麼、能吃多少資源，
 * 由 ExecutionSandbox 負責落實。刻意不含任何 runtime 專屬的旗標名稱：
 * 安全邊界若寫在 runtime 裡，換一個 CLI 就換一套邊界。
 *
 * @param writablePaths 允許寫入的路徑，其餘檔案系統一律唯讀。
 * @param readOnlyPins  在可寫路徑「之內」再釘回唯讀的例外，用來挖掉提權管道。
 * @param memoryMaxMb   cgroup 記憶體上限，超過即被 OOM killer 終結。
 * @param cpuQuotaPct   CPU 配額百分比，100 = 一顆核心。
 * @param tasksMax      行程／執行緒總數上限，防 fork bomb 與失控的平行編譯。
 */
public record SandboxPolicy(
        List<Path> writablePaths,
        List<Path> readOnlyPins,
        int memoryMaxMb,
        int cpuQuotaPct,
        int tasksMax,
        boolean localTestNetwork
) {
    public SandboxPolicy(List<Path> writablePaths, List<Path> readOnlyPins, int memoryMaxMb, int cpuQuotaPct, int tasksMax) {
        this(writablePaths, readOnlyPins, memoryMaxMb, cpuQuotaPct, tasksMax, false);
    }

    /**
     * 某個 runtime 自己需要的檔案系統條件。
     *
     * 由 AgentRuntime 宣告後傳進來，而不是在這裡寫死 ~/.claude ——
     * 各家 CLI 的狀態目錄不同，寫死等於讓第二個 runtime 一接上就靜默失效：
     * 它的 session 目錄不可寫，resume 會壞掉，而且不會有任何錯誤訊息。
     *
     * @param writable      該 runtime 要寫的路徑（session、憑證、快取）
     * @param readOnlyPins  在上述可寫目錄內要釘回唯讀的提權管道（hook 設定、外掛目錄）
     */
    public record AgentState(List<Path> writable, List<Path> readOnlyPins) {}


    /**
     * 唯讀角色（PM／Architecture）。
     *
     * 它們的 allowedTools 本來就不含 Write／Edit，但那是 runtime 內的防線。
     * 這裡除了 agent 自身狀態外不給任何可寫路徑，讓「唯讀」變成外部強制的事實。
     */
    public static SandboxPolicy readOnly(AgentState st) {
        return new SandboxPolicy(
                st.writable(), st.readOnlyPins(),
                2048, 200, 256);
    }

    /**
     * 會改程式碼的角色（Backend／QA）。
     *
     * 資源上限是實測後訂的：VM 共 8 GB／8 vCPU，曾因 npm 原生模組開滿核心編譯
     * 導致 soft lockup 需硬重置（見 vm-setup-manual.md）。留一半給系統與 orchestrator。
     *
     * @param workspace 該任務的隔離工作區（git worktree）
     * @param cacheDirs 建置快取，例如 ~/.m2。不給寫的話 Maven 每次都要重抓依賴。
     */
    public static SandboxPolicy writable(AgentState st, Path workspace, List<Path> cacheDirs) {
        List<Path> w = new ArrayList<>(st.writable());
        w.add(workspace);
        w.addAll(cacheDirs);
        return new SandboxPolicy(
                List.copyOf(w), st.readOnlyPins(),
                4096, 400, 512);
    }

    /**
     * 驗收角色（QA）。在 Backend 的工作區內執行，但產品程式碼釘回唯讀。
     *
     * 決策 4：用沙箱做真正的邊界，不靠提示詞。QA 就算被說服要「順手修一下」，
     * 也寫不進 src/main。它仍能寫 src/test 與 target，這是補測試與跑建置所必需。
     *
     * .git 保持可寫 —— QA 補的回歸測試要能 commit，否則進不了 PR。
     * 想繞道（例如 git checkout 覆蓋產品程式碼）一樣會被唯讀釘選擋下。
     */
    public static SandboxPolicy qaReview(AgentState st, Path workspace, List<Path> cacheDirs,
                                         List<Path> productionPins) {
        List<Path> w = new ArrayList<>(st.writable());
        w.add(workspace);
        w.addAll(cacheDirs);

        List<Path> pins = new ArrayList<>(st.readOnlyPins());
        pins.addAll(productionPins);

        return new SandboxPolicy(List.copyOf(w), List.copyOf(pins), 4096, 400, 512);
    }

    /**
     * 掃出工作區內屬於「產品程式碼」的路徑，供 QA 政策釘成唯讀。
     *
     * 判準是 Maven 慣例：任何模組的 `src/main` 與所有 `pom.xml`。
     * 這對本專案成立；換成別的建置系統要一併調整。
     *
     * 掃描失敗一律往上拋，不回傳空清單 —— 空清單代表「什麼都不釘」，
     * 那會讓 QA 悄悄取得改產品程式碼的能力，是最糟的失敗方式。
     */
    public static List<Path> scanProductionPaths(Path workspace) throws IOException {
        List<Path> found = new ArrayList<>();

        try (Stream<Path> s = Files.walk(workspace, 3)) {
            s.filter(Files::isDirectory)
             .filter(p -> p.endsWith(Path.of("src", "main")))
             .forEach(found::add);
        }
        try (Stream<Path> s = Files.walk(workspace, 2)) {
            s.filter(Files::isRegularFile)
             .filter(p -> "pom.xml".equals(p.getFileName().toString()))
             .forEach(found::add);
        }

        if (found.isEmpty()) {
            throw new IOException("在 " + workspace + " 找不到任何 src/main 或 pom.xml，"
                    + "無法判斷要保護哪些產品程式碼。工作區可能沒有正確建立。");
        }
        return List.copyOf(found);
    }
}
