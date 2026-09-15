package com.zipe.aiteam.runtime;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimeUsageErrorTest {
    private static final String USAGE = """
            error: unexpected argument '-P' found

              tip: to pass '-P' as a value, use '-- -P'

            Usage: codex exec [OPTIONS] [PROMPT]
            """;

    @Test void recognisesArgumentRejectionWithNoModelEvents() {
        assertThat(RuntimeUsageError.looksLikeUsageError(2, "", USAGE)).isTrue();
        assertThat(RuntimeUsageError.looksLikeUsageError(2, "   \n", USAGE)).isTrue();
    }

    /** 有事件流代表模型已經開始工作，那是真實失敗，必須照常計次。 */
    @Test void refusesWhenAnyModelEventWasEmitted() {
        assertThat(RuntimeUsageError.looksLikeUsageError(2,
                "{\"type\":\"thread.started\",\"thread_id\":\"abc\"}\n", USAGE)).isFalse();
    }

    @Test void refusesOtherExitCodesAndUnrelatedStderr() {
        assertThat(RuntimeUsageError.looksLikeUsageError(1, "", USAGE)).isFalse();
        assertThat(RuntimeUsageError.looksLikeUsageError(2, "", "mvn: BUILD FAILURE")).isFalse();
        assertThat(RuntimeUsageError.looksLikeUsageError(2, "", null)).isFalse();
    }

    @Test void recognisesSchemaRejectionBeforeAgentStarts() {
        String rejected = """
                {"error":{"message":"Invalid schema","type":"invalid_request_error","code":"invalid_json_schema"}}
                """;
        assertThat(RuntimeUsageError.looksLikeRequestContractError(1, rejected, "")).isTrue();
        assertThat(RuntimeUsageError.looksLikeRequestContractError(1,
                "{\"type\":\"thread.started\"}\n" + rejected, "")).isFalse();
        assertThat(RuntimeUsageError.looksLikeRequestContractError(0, rejected, "")).isFalse();
        assertThat(RuntimeUsageError.looksLikeRequestContractError(1, "unrelated", "")).isFalse();
    }

    @Test void findsWrappedCause() {
        var usage = new RuntimeUsageError("用法錯誤");
        assertThat(RuntimeUsageError.find(new IllegalStateException("外層", usage))).isSameAs(usage);
        assertThat(RuntimeUsageError.find(new IllegalStateException("無關"))).isNull();
    }
}
