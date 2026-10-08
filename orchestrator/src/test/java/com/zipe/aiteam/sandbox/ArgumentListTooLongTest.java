package com.zipe.aiteam.sandbox;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** #1029：E2BIG 時錯誤紀錄要看得出是哪個參數過長，說明 agent 與管理者才查得到原因。 */
class ArgumentListTooLongTest {

    @Test void describesTheOversizedArgumentAndPinCount() {
        List<String> command = new ArrayList<>(List.of("systemd-run", "--user", "ReadOnlyPaths=-/w/a", "ReadOnlyPaths=-/w/b", "--", "codex", "exec", "-c"));
        command.add("permissions.ai_team_local_tests={filesystem={" + "x".repeat(140_000) + "}}");
        String text = ArgumentListTooLong.describe(command);
        assertThat(text).contains("啟動 systemd-run 失敗", "重試無效", "共 9 個參數", "最大的是第 8 個", "超過單一參數上限 131072",
                "開頭為「permissions.ai_team_local_tests=", "ReadOnlyPaths）2 個", "protectedPaths");
        assertThat(text.length()).isLessThan(800);
    }

    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void realE2bigCarriesTheDiagnosis() {
        var builder = new ProcessBuilder("true", "y".repeat(200_000));
        assertThatThrownBy(() -> ArgumentListTooLong.start(builder))
                .isInstanceOf(ArgumentListTooLong.class).hasMessageContaining("最大的是第 1 個，200000 位元組")
                .satisfies(e -> assertThat(e.getCause().getMessage()).contains(ArgumentListTooLong.SIGNATURE));
    }

    @Test void otherStartFailuresAreRethrownUnchanged() {
        var builder = new ProcessBuilder("/nonexistent/definitely-not-a-program");
        assertThatThrownBy(() -> ArgumentListTooLong.start(builder))
                .isInstanceOf(IOException.class).isNotInstanceOf(ArgumentListTooLong.class);
    }
}
