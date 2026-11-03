package com.zipe.aiteam.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.RequestConfigurator;
import com.slack.api.methods.request.files.FilesUploadV2Request;
import com.slack.api.methods.response.files.FilesUploadV2Response;
import com.zipe.aiteam.flow.Attachments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentUploadTest {
    @TempDir Path tmp;

    @SuppressWarnings("unchecked")
    @Test void uploadsIntoThreadWithoutFilesRead() throws Exception {
        Path file = Files.writeString(tmp.resolve("a.csv"), "1");
        MethodsClient client = mock(MethodsClient.class);
        FilesUploadV2Response ok = new FilesUploadV2Response();
        ok.setOk(true);
        var captured = new FilesUploadV2Request[1];
        when(client.filesUploadV2(any(RequestConfigurator.class))).thenAnswer(call -> {
            RequestConfigurator<FilesUploadV2Request.FilesUploadV2RequestBuilder> configure = call.getArgument(0);
            captured[0] = configure.configure(FilesUploadV2Request.builder()).build();
            return ok;
        });
        assertThat(SlackNotifier.upload(client, "C1", "171.1", List.of(new Attachments.Candidate(file, "a.csv", "月報")))).isNull();
        assertThat(captured[0].getChannel()).isEqualTo("C1");
        assertThat(captured[0].getThreadTs()).isEqualTo("171.1");
        assertThat(captured[0].isRequestFileInfo()).isFalse();
        assertThat(captured[0].getUploadFiles()).singleElement().satisfies(f -> {
            assertThat(f.getFilename()).isEqualTo("a.csv");
            assertThat(f.getTitle()).isEqualTo("月報");
        });
    }

    @SuppressWarnings("unchecked")
    @Test void returnsSlackErrorInsteadOfThrowing() throws Exception {
        MethodsClient client = mock(MethodsClient.class);
        FilesUploadV2Response failed = new FilesUploadV2Response();
        failed.setOk(false);
        failed.setError("missing_scope");
        when(client.filesUploadV2(any(RequestConfigurator.class))).thenReturn(failed);
        assertThat(SlackNotifier.upload(client, "C1", "1.1", List.of(new Attachments.Candidate(tmp.resolve("x"), "x", "x"))))
                .isEqualTo("missing_scope");
        when(client.filesUploadV2(any(RequestConfigurator.class))).thenThrow(new java.io.IOException("連線中斷"));
        assertThat(SlackNotifier.upload(client, "C1", "1.1", List.of(new Attachments.Candidate(tmp.resolve("x"), "x", "x"))))
                .contains("連線中斷");
    }
}
