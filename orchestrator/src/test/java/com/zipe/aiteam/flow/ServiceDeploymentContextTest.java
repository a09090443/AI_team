package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zipe.aiteam.store.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ServiceDeploymentContextTest {
    @TempDir Path tmp;
    @Test void historyAndRecoveryAreBackgroundNotNewChecks() throws Exception {
        Path dir=Files.createDirectories(tmp.resolve("service-context"));
        Files.writeString(dir.resolve("sit.json"), """
                {"target":"sit","host":"Windows 宿主機","program":"C:/tools/broker.py",
                 "config_file":"C:/private/host.json","start_command":"python broker.py host.json",
                 "history":[{"at":"2026-10-11","task":"1036","outcome":"START_PASS","evidence":"receipt-1"}],
                 "password":"DO_NOT_INCLUDE"}
                """);
        String text=ServiceDeploymentContext.notice(tmp,"sit",List.of(new TaskStore.ServiceHistory("1036","yesterday",
                "{\"service_status\":\"RUNNING\",\"checks\":[{\"name\":\"health\",\"status\":\"PASS\",\"exit_code\":0}]}")));
        assertThat(text).contains("1036","yesterday","RUNNING","health=PASS exit=0","Windows 宿主機",
                "C:/tools/broker.py","python broker.py host.json","receipt-1","不得用歷史結果填本次 checks")
                .doesNotContain("DO_NOT_INCLUDE");
    }
    @Test void absentInvalidAndMismatchedContextNeverInventRecovery() throws Exception {
        assertThat(ServiceDeploymentContext.notice(tmp,"sit",List.of())).contains("不代表從未部署","尚無完整");
        Path dir=Files.createDirectories(tmp.resolve("service-context"));
        Files.writeString(dir.resolve("sit.json"),"{\"target\":\"other\",\"start_command\":\"WRONG_COMMAND\"}");
        assertThat(ServiceDeploymentContext.notice(tmp,"sit",List.of())).contains("目標不符").doesNotContain("WRONG_COMMAND");
        assertThat(ServiceDeploymentContext.notice(tmp,"../sit",List.of())).contains("尚無完整");
        Files.writeString(dir.resolve("sit.json"),"broken");
        assertThat(ServiceDeploymentContext.notice(tmp,"sit",List.of())).contains("無法讀取");
    }
    @Test void queryIsScopedByChannelTargetNodeAndCurrentTask() throws Exception {
        var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+tmp.resolve("db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds);var store=new TaskStore(jdbc);
        insert(jdbc,"1","channel","sit","deploy","SERVICE");
        insert(jdbc,"2","other-channel","sit","deploy","SERVICE");
        insert(jdbc,"3","channel","other-target","deploy","SERVICE");
        insert(jdbc,"4","channel","sit","triage","REPORT");
        insert(jdbc,"5","channel","sit","deploy","SERVICE");
        assertThat(store.serviceHistory("channel","sit","5")).extracting(TaskStore.ServiceHistory::taskId).containsExactly("1");
    }
    void insert(JdbcTemplate jdbc,String id,String channel,String target,String node,String gate) throws Exception {
        jdbc.update("INSERT INTO task(id,slack_channel,slack_thread_ts,requester_user_id,request_text,state,created_at,updated_at) VALUES (?,?, 'thread','user','deploy','COMPLETED','now','now')",id,channel);
        String flow=new ObjectMapper().writeValueAsString(Map.of("snapshot",Map.of("definition",Map.of(
                "project",Map.of("serviceTarget",Map.of("name",target)),"nodes",Map.of(node,Map.of("gate",gate))))));
        jdbc.update("INSERT INTO flow_run(task_id,data) VALUES (?,?)",id,flow);
        String report=new ObjectMapper().writeValueAsString(Map.of("target",target,"service_status","RUNNING"));
        jdbc.update("INSERT INTO stage_attempt(task_id,role,result,completed_at) VALUES (?,?,?,'now')",id,node,report);
    }
}
