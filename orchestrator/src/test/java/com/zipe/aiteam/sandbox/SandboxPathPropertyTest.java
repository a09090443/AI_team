package com.zipe.aiteam.sandbox;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** systemd 路徑清單以空白分隔；bestpartner #1012 的「General rule.pdf」讓獨立驗證以 Invalid ReadOnlyPaths 起不來。 */
class SandboxPathPropertyTest {
    @Test void plainPathsKeepTheOptionalPrefixWithoutQuotes() {
        assertThat(ExecutionSandbox.pathProperty("/srv/repo/docs/中文.docx"))
                .startsWith("-").doesNotContain("\"");
    }

    @Test void pathsWithSpacesAreQuotedAsOneWord() {
        // VM 實測：整段加雙引號才真的唯讀；\x20 跳脫不報錯但檔案仍可寫入。
        String value = ExecutionSandbox.pathProperty("/srv/repo/docs/General rule.pdf");
        assertThat(value).startsWith("\"-").endsWith("General rule.pdf\"").doesNotContain("\\x20");
    }

    @Test void unrepresentableCharactersAreRefusedInsteadOfSilentlyUnprotected() {
        for (String bad : new String[]{"/srv/a\"b", "/srv/a\\b", "/srv/a\nb"})
            assertThatThrownBy(() -> ExecutionSandbox.pathProperty(bad))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("拒絕執行");
    }
}
