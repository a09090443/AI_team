package com.zipe.aiteam.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class OperationJobsTest {
    @TempDir Path home;
    ObjectMapper json = new ObjectMapper();
    ObjectNode job(String id, String state) throws Exception {
        ObjectNode record = json.createObjectNode();
        record.put("version",1).put("id",id).put("task","42").put("node","build").put("action","compile")
                .put("target","other-project").put("source_head","head1").put("state",state).put("verified",true).put("updated",1).put("deadline",9999999999L);
        if (state.equals("SUCCEEDED") || state.equals("FAILED")) record.putObject("result").put("id",id)
                .put("action","compile").put("target","other-project").put("exit_code",state.equals("SUCCEEDED")?0:1)
                .putArray("evidence").add("verified operation result");
        return record;
    }
    void write(ObjectNode record) throws Exception {
        Path dir=home.resolve("operation-jobs/42");Files.createDirectories(dir);
        Files.writeString(dir.resolve(record.path("id").asText()+".json"),record.toString());
    }
    @Test void reusableAcrossProjectsAndOperationKinds() throws Exception {
        write(job("a".repeat(32),"RUNNING"));
        var batch=new OperationJobs(home).read("42","build","head1");
        assertThat(batch.pending()).isTrue();
        assertThat(batch.notice()).contains("compile","OPERATION_PENDING");
        assertThat(new OperationJobs(home).read("42","build","head2").empty()).isTrue();
        assertThat(new OperationJobs(home).read("42","different-node","head1").empty()).isTrue();
    }
    @Test void aDifferentTasksReceiptCannotBecomeACompletedCheckpoint() throws Exception {
        ObjectNode record=job("a".repeat(32),"SUCCEEDED");((ObjectNode)record.path("result")).put("id","b".repeat(32));write(record);
        assertThat(new OperationJobs(home).read("42","build","head1").empty()).isTrue();
    }
    @Test void aNonzeroResultCannotClaimSuccess() throws Exception {
        ObjectNode record=job("a".repeat(32),"SUCCEEDED");((ObjectNode)record.path("result")).put("exit_code",1);write(record);
        assertThat(new OperationJobs(home).read("42","build","head1").empty()).isTrue();
    }
    @Test void progressDoesNotCreateUnlimitedRecoveryAttempts() throws Exception {
        ObjectNode record=job("a".repeat(32),"RUNNING");write(record);
        String first=new OperationJobs(home).read("42","build","head1").fingerprint();
        record.put("updated",999);write(record);
        assertThat(new OperationJobs(home).read("42","build","head1").fingerprint()).isEqualTo(first);
        write(job("a".repeat(32),"SUCCEEDED"));
        assertThat(new OperationJobs(home).read("42","build","head1").fingerprint()).isNotEqualTo(first);
    }    @Test void cancellingPendingJobDoesNotChangeSuccessfulCheckpoint() throws Exception {
        write(job("a".repeat(32),"RUNNING"));write(job("b".repeat(32),"SUCCEEDED"));
        new OperationJobs(home).requestCancellation("42","build","head1");
        assertThat(home.resolve("operation-cancellations/42/"+"a".repeat(32)+".json")).exists();
        assertThat(home.resolve("operation-cancellations/42/"+"b".repeat(32)+".json")).doesNotExist();
        assertThat(new OperationJobs(home).read("42","build","head1").jobs()).hasSize(2);
    }
    @Test void unverifiedSuccessIsNeverARecoveryCheckpoint() throws Exception {
        ObjectNode record=job("a".repeat(32),"SUCCEEDED");record.put("verified",false);write(record);
        assertThat(new OperationJobs(home).read("42","build","head1").empty()).isTrue();
    }

}