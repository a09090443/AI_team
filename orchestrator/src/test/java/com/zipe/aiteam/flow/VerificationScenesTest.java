package com.zipe.aiteam.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import static org.assertj.core.api.Assertions.*;

/** 驗證現場回收：只刪 work/，log 與比對檔保留；等待中的任務留最新一份現場。 */
class VerificationScenesTest {
    @TempDir Path tmp;

    Path scene(String task, String name, long millis) throws Exception {
        Path run = tmp.resolve(task).resolve(name);
        Files.createDirectories(run.resolve("work/repo/.git/objects"));
        Path object = Files.writeString(run.resolve("work/repo/.git/objects/pack"), "x");
        object.toFile().setReadOnly();
        run.resolve("work/repo/.git/objects").toFile().setReadOnly();
        Files.writeString(run.resolve("check-0.log"), "ok");
        Files.setLastModifiedTime(run, FileTime.fromMillis(millis));
        return run;
    }

    @Test void sweepKeepsOnlyTheNewestSceneOfTasksThatMayResumeAndAlwaysKeepsLogs() throws Exception {
        Path oldWaiting = scene("1004", "run-1", 1000), newWaiting = scene("1004", "run-2", 2000);
        Path finished = scene("1002", "run-1", 3000), baseline = scene("0", "baseline-1", 4000);
        Files.createDirectories(tmp.resolve("1004/not-a-scene/work"));

        int removed = VerificationScenes.sweep(tmp, id -> id.equals("1004"));

        assertThat(removed).isEqualTo(3);
        assertThat(newWaiting.resolve("work/repo")).isDirectory();
        for (Path gone : new Path[]{oldWaiting, finished, baseline}) {
            assertThat(gone.resolve("work")).doesNotExist();
            assertThat(gone.resolve("check-0.log")).hasContent("ok");
        }
        assertThat(tmp.resolve("1004/not-a-scene/work")).isDirectory();
    }

    @Test void onlySceneDirectoriesAreDiscarded() throws Exception {
        Path other = tmp.resolve("1004/other");
        Files.createDirectories(other.resolve("work"));
        assertThat(VerificationScenes.discard(other)).isFalse();
        assertThat(other.resolve("work")).isDirectory();
    }

    @Test void enoughSpaceDoesNotBlockVerification() {
        assertThatCode(() -> VerificationScenes.requireSpace(tmp)).doesNotThrowAnyException();
    }
}
