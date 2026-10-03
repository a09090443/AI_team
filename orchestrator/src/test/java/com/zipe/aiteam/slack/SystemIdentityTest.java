package com.zipe.aiteam.slack;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 同一台 VM 多個實例時，系統訊息必須顯示各自主 App 的名稱，不能被寫死的名字覆蓋。 */
class SystemIdentityTest {
    @Test void systemMessagesKeepTheMainAppsOwnName() {
        assertThat(SlackNotifier.overridesName(SlackNotifier.Identity.SYSTEM, false)).isFalse();
    }

    @Test void onlyRolesBorrowingTheMainTokenAreRenamed() {
        assertThat(SlackNotifier.overridesName(SlackNotifier.Identity.PM, false)).isTrue();
        assertThat(SlackNotifier.overridesName(SlackNotifier.Identity.PM, true)).isFalse();
    }

    @Test void fallbackNameIsNotAnotherProjectsApp() {
        assertThat(SlackNotifier.Identity.SYSTEM.username).doesNotContain("zipe");
    }
}
