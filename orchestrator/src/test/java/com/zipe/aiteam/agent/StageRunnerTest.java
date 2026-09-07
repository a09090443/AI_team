package com.zipe.aiteam.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StageRunner 讀取 AGENTS.md 的行為驗證。
 *
 * 只驗 readAgentsMap：其餘方法都得起 runtime 或沙箱，那屬於端對端的範圍。
 * 建構子傳 null 是刻意的 —— 這個方法不碰任何相依物件，若哪天碰了，測試會立刻炸開。
 */
class StageRunnerTest {

    @TempDir Path tmp;

    private final StageRunner runner = new StageRunner(null, null, null);

    @Test
    void 讀得到根目錄的_AGENTS_md() throws Exception {
        Files.writeString(tmp.resolve("AGENTS.md"),
                "# AGENTS.md\n\n相依方向：iam 到 logon 單向，不可反向。\n",
                StandardCharsets.UTF_8);

        assertThat(runner.readAgentsMap(tmp))
                .contains("相依方向")
                .contains("iam 到 logon 單向");
    }

    @Test
    void 檔案不存在時回傳空字串而不是丟例外() {
        assertThat(runner.readAgentsMap(tmp)).isEmpty();
    }

    @Test
    void 工作目錄本身不存在時也不丟例外() {
        assertThat(runner.readAgentsMap(tmp.resolve("不存在的目錄"))).isEmpty();
    }

    @Test
    void 過長的檔案會被截斷並標註() throws Exception {
        String huge = "行\n".repeat(20_000);   // 遠超過 16000 字上限
        Files.writeString(tmp.resolve("AGENTS.md"), huge, StandardCharsets.UTF_8);

        String result = runner.readAgentsMap(tmp);

        assertThat(result).hasSizeLessThan(huge.length());
        assertThat(result).endsWith("已截斷）");
    }

    @Test
    void 未超過上限的檔案不會被動到() throws Exception {
        String content = "# AGENTS.md\n\n剛好的長度\n";
        Files.writeString(tmp.resolve("AGENTS.md"), content, StandardCharsets.UTF_8);

        assertThat(runner.readAgentsMap(tmp)).isEqualTo(content);
    }
}
