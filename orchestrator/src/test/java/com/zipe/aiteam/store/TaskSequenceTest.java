package com.zipe.aiteam.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import com.zipe.aiteam.task.Task;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class TaskSequenceTest {
    @TempDir Path tmp;
    @Test void deletingLatestTaskAndRestartingNeverReusesSlackButtonId() {
        var ds = new org.sqlite.SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds); TaskStore store = new TaskStore(jdbc);
        store.save(new Task("1011", "c", "t", "u", "old")); store.initSequence();
        jdbc.update("DELETE FROM task WHERE id='1011'");
        store = new TaskStore(jdbc); store.initSequence();
        assertThat(store.nextTaskId()).isEqualTo("1012");
        store = new TaskStore(jdbc); store.initSequence();
        assertThat(store.nextTaskId()).isEqualTo("1013");
    }
}
