package com.npick.search.infrastructure.config;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolution.DateField;
import com.npick.search.application.port.QueryResolution.DateWindow;
import com.npick.search.application.port.QueryResolution.Intent;
import com.npick.search.application.port.QueryResolution.Origin;
import com.npick.search.application.port.QueryResolution.QuerySpan;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.resolution.AnchorVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** 빈으로 올라간 검증기가 <b>사용자 시간대</b> 로 상대 표현을 푸는지 고정한다. */
class AnchorVerificationConfigurationTest {

    /**
     * 시간대를 틀려도 검색은 계속 돌고 결과도 나온다 — 사용자가 맞게 쓴 날짜 조건만 조용히 강등될 뿐이다. 그래서 배선 자체를 테스트로 묶는다.
     *
     * <p>기준 연도를 상수로 박지 않고 시스템 기본 시간대에서 계산한다. 박아두면 해가 바뀔 때 테스트가 깨진다.
     */
    @Test
    void resolvesRelativePeriodInSystemDefaultZone() {
        new ApplicationContextRunner()
                .withUserConfiguration(AnchorVerificationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AnchorVerifier verifier = context.getBean(AnchorVerifier.class);

                    int lastYear = LocalDate.now(ZoneId.systemDefault()).getYear() - 1;
                    QueryResolutionResult result = verifier.verify("작년 여름 침수 현장", resolvedLastSummer(lastYear));

                    assertThat(result.resolution().dateWindows().getFirst().origin())
                            .isEqualTo(Origin.EXPLICIT_QUERY);
                    assertThat(result.findings()).isEmpty();
                });
    }

    private static QueryResolutionResult resolvedLastSummer(int year) {
        DateWindow window = new DateWindow(
                DateField.BROADCAST_DATE,
                LocalDate.of(year, 6, 1),
                LocalDate.of(year, 9, 1),
                Origin.EXPLICIT_QUERY,
                new QuerySpan(0, 5),
                0.8);
        return new QueryResolutionResult(
                new QueryNormalization("작년 여름 침수 현장", List.of("작년", "여름", "침수", "현장"), "query-norm/v1"),
                new QueryResolution(
                        "query-resolver/v2",
                        Intent.SCENE_SEARCH,
                        List.of(window),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        0.8),
                List.of(),
                "query-resolver/v2",
                "query-resolver-prompt/v3",
                "gemma3:12b",
                null);
    }
}
