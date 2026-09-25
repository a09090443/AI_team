package com.zipe.aiteam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不啟動整個應用程式，只檢查 Spring 能不能決定用哪個建構子。
 *
 * 單元測試都直接 new，不經過 Spring；2026-09-25 部署時 ConfiguredExecutor 的 @Autowired 被新加的常數隔開，
 * 落到欄位上，所有測試通過，服務卻在啟動時以 NoSuchMethodException 失敗。
 */
class SpringWiringTest {
    @Test void everyComponentWithSeveralConstructorsMarksExactlyOneForSpring() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> problems = new ArrayList<>();
        int scanned = 0;
        for (var candidate : scanner.findCandidateComponents("com.zipe.aiteam")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            scanned++;
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) continue;
            long marked = Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            boolean hasDefault = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (marked != 1 && !hasDefault)
                problems.add(type.getName() + "：有 " + constructors.length + " 個建構子，標了 @Autowired 的有 " + marked + " 個");
        }
        assertThat(scanned).as("應該掃到專案的元件").isGreaterThan(5);
        assertThat(problems).as("Spring 無法決定建構子，服務會啟動失敗").isEmpty();
    }
}
