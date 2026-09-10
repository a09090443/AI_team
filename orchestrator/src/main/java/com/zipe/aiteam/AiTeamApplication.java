package com.zipe.aiteam;

import com.zipe.aiteam.config.AiTeamProperties;
import com.zipe.aiteam.agent.StageRunner;
import com.zipe.aiteam.agent.StageSpec;
import com.zipe.aiteam.sandbox.ExecutionSandbox;
import com.zipe.aiteam.slack.SlackListener;
import com.zipe.aiteam.store.TaskStore;
import com.zipe.aiteam.task.TaskOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiTeamApplication implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiTeamApplication.class);

    private final AiTeamProperties props;
    private final SlackListener listener;
    private final TaskStore store;
    private final TaskOrchestrator orchestrator;
    private final ExecutionSandbox sandbox;
    private final StageRunner stageRunner;
    @org.springframework.beans.factory.annotation.Autowired
    private com.zipe.aiteam.flow.TeamCatalog teamCatalog;
    @org.springframework.beans.factory.annotation.Autowired
    private com.zipe.aiteam.runtime.RuntimeRegistry runtimes;
    @org.springframework.beans.factory.annotation.Autowired
    private com.zipe.aiteam.task.QuotaAutoResume quotaAutoResume;

    public AiTeamApplication(AiTeamProperties props, SlackListener listener,
                             TaskStore store, TaskOrchestrator orchestrator,
                             ExecutionSandbox sandbox, StageRunner stageRunner) {
        this.props = props;
        this.listener = listener;
        this.store = store;
        this.orchestrator = orchestrator;
        this.sandbox = sandbox;
        this.stageRunner = stageRunner;
    }

    /**
     * 啟動時印出各階段實際會用的模型。
     *
     * 這同時是設定佈線的驗證：環境變數若沒被讀到，這裡就會顯示「CLI 預設」，
     * 不必等到跑完一個任務、花掉一塊美金才發現設定沒生效。
     */
    private void logModels() {
        if (teamCatalog != null && teamCatalog.enabled()) {
            teamCatalog.snapshot().definition().roles().forEach((id, role) -> {
                runtimes.require(role.runtime());
                log.info("配置角色 {}：runtime={} model={}", id, role.runtime(), role.model());
            });
            log.info("新任務使用配置版本 {}；舊任務依其快照或 legacy 相容路徑執行", teamCatalog.snapshot().revision());
            return;
        }
        for (StageSpec s : new StageSpec[]{
                StageSpec.PM, StageSpec.PM_TRIAGE, StageSpec.ARCHITECTURE, StageSpec.QA_PLAN, StageSpec.BACKEND, StageSpec.QA,
                StageSpec.CODE_REVIEW}) {
            String m = stageRunner.resolveModel(s);
            log.info("階段模型：{} = {}（runtime {}）", s.displayName(),
                    m.isEmpty() ? "(CLI 預設)" : m, props.roleRuntime(s.role()));
        }
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--validate-team".equals(args[0])) {
            try {
                var snapshot = com.zipe.aiteam.flow.TeamCatalog.load(java.nio.file.Path.of(args[1]), System::getenv);
                System.out.println("靜態配置驗證通過：" + snapshot.definition().id() + "，角色=" + snapshot.definition().roles().keySet()
                        + "，revision=" + snapshot.revision() + "；未連線 Slack、未執行 runtime 或驗證沙箱。");
                return;
            } catch (Exception e) { throw new IllegalArgumentException("配置驗證失敗：" + e.getMessage(), e); }
        }
        SpringApplication.run(AiTeamApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        props.validate();
        log.info("設定檢查通過。repo={} claude={}", props.repoPath(), props.claudeBin());

        logModels();

        // 沙箱自檢要在恢復任務之前跑完 —— 恢復可能立刻排入會寫程式碼的階段。
        // 自檢失敗不擋啟動：PM／Architecture 是唯讀的，仍然可用；
        // 但 Backend／QA 會在 TaskOrchestrator 被擋下來。
        sandbox.selfTest();

        // 缺 GitHub token 不擋啟動：管線跑得完，只是最後推不出去。
        // 但要在這裡就講，不要等使用者按了核准才發現。
        if (!props.hasGithubToken()) {
            log.warn("未設定 AI_TEAM_GITHUB_TOKEN：管線可以完整執行，"
                   + "但核准之後無法推分支或開 PR。要啟用請在 ~/ai-team/env.sh 加上該變數後重啟。");
        }

        store.initSequence();
        int purged = store.purgeOldEvents();
        if (purged > 0) log.info("已清除 {} 筆過期的去重紀錄", purged);

        orchestrator.recoverOnStartup();
        // 部署者僅本次啟動明確指定；不掃描所有已核准任務，也不代建核准收據。
        String approvedTask = System.getenv("AI_TEAM_RESUME_APPROVED_TASK");
        if (approvedTask != null && !approvedTask.isBlank()) {
            if (!approvedTask.matches("[0-9]+")) throw new IllegalArgumentException("AI_TEAM_RESUME_APPROVED_TASK 必須是單一任務編號");
            orchestrator.resumeApprovedPlanTask(approvedTask);
        }

        // SocketModeApp.start() 會阻塞，因此不能等 ApplicationReadyEvent 才啟動。
        quotaAutoResume.start();

        Runtime.getRuntime().addShutdownHook(new Thread(listener::stop));
        listener.start();
    }
}
