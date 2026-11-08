package com.zipe.aiteam.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueCommandsTest {
    @Test void parsesListAndHandleCommands() {
        assertThat(IssueCommands.parse("issues").list()).isTrue();
        assertThat(IssueCommands.parse("列出 issue").list()).isTrue();
        var handle = IssueCommands.parse("處理 issue #12 先看後端");
        assertThat(handle.number()).isEqualTo(12);
        assertThat(handle.note()).isEqualTo("先看後端");
        assertThat(IssueCommands.parse("處理Issue 7").number()).isEqualTo(7);
        var url = IssueCommands.parse("處理 <https://github.com/acme/api/issues/34|acme/api#34> 優先");
        assertThat(url.owner()).isEqualTo("acme");
        assertThat(url.repo()).isEqualTo("api");
        assertThat(url.number()).isEqualTo(34);
        assertThat(url.note()).isEqualTo("優先");
    }

    @Test void taskCommandsAndOrdinaryRequestsAreNotIssueCommands() {
        assertThat(IssueCommands.parse("處理 #12")).isNull();
        assertThat(IssueCommands.parse("幫我看 issues 列表怎麼做")).isNull();
        assertThat(IssueCommands.parse("處理 https://gitlab.com/acme/api/issues/3")).isNull();
    }
}
