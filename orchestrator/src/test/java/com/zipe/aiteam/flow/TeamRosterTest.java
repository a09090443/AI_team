package com.zipe.aiteam.flow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/** #1002：需求整理不知道團隊有誰，一行文件修改也照完整流程走；分派節點要看得到全隊名單。 */
class TeamRosterTest {
    @TempDir Path tmp;

    @BeforeEach void repo() throws Exception { Files.createDirectories(tmp.resolve(".git")); }

    FlowRun maven(String node) throws Exception {
        FlowRun flow = new FlowRun(TeamCatalog.load(Path.of("teams/maven-library.json"), k -> tmp.toString()));
        flow.node = node;
        return flow;
    }

    @Test void dispatchingNodesSeeEveryRoleWithCapabilitiesStagesAndWriteAccess() throws Exception {
        for (String node : new String[]{"intake", "triage"}) {
            String text = TeamRoster.notice(maven(node));
            assertThat(text).as(node).contains("團隊名單", "coordinator（PM Agent）", "architect", "tester", "inspector")
                    .contains("builder（Backend Agent）runtime=claude-code", "文件實作", "implementation（可寫）")
                    .contains("design（唯讀）")
                    .contains("builder_codex（Backend Agent）runtime=codex", "受保護路徑改派", ".mvn/**");
        }
    }

    @Test void otherNodesAndTeamsWithoutCollaborationGetNothing() throws Exception {
        for (String node : new String[]{"design", "plan", "implementation", "verification", "review"})
            assertThat(TeamRoster.notice(maven(node))).as(node).isEmpty();
        // node-web 對照組沒開 collaboration：提示維持不變。
        FlowRun plain = new FlowRun(TeamCatalog.load(Path.of("teams/node-web.json"), k -> tmp.toString()));
        plain.node = "intake";
        assertThat(TeamRoster.notice(plain)).isEmpty();
        FlowRun dialogue = maven("intake");
        dialogue.dialogue = true;
        assertThat(TeamRoster.notice(dialogue)).isEmpty();
    }
}
