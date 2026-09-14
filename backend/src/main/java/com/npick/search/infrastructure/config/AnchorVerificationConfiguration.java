package com.npick.search.infrastructure.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.npick.search.application.resolution.AnchorVerifier;

/**
 * anchor 출처 검증기를 시간대까지 정해서 빈으로 올린다 (S15P21A501-46).
 *
 * <p>검증기 자체는 Spring 을 모르는 Policy 다 (설계 정본 §7). 그런데 {@code "작년"} 을 풀 기준 시각이 필요하고, <b>그 시간대를 틀리면 조용히 틀린다</b> —
 * {@link Clock#systemUTC()} 를 넘기면 12월 31일 09시부터 자정까지 아홉 시간 동안 UTC 가 아직 전날이라 {@code "작년"} 이 한 해 밀리고, 사용자가 맞게 쓴 날짜 조건이
 * 통째로 강등된다. 검색은 계속 되고 결과도 나오므로 아무도 눈치채지 못한다.
 *
 * <p>그래서 쓰는 쪽이 고르게 두지 않고 여기서 못박는다. 같은 저장소의 {@code PipelineConfiguration} 이 {@code systemUTC()} 를 쓰고 있어 배선할 때 그대로 따라갈
 * 여지가 큰데, 그쪽은 실행 시각 기록이라 시간대가 없어야 맞고 이쪽은 사용자가 말한 "작년" 이라 KST 여야 한다. 목적이 반대다.
 *
 * <p>{@code Clock} 을 빈으로 올리지 않고 여기서 직접 만드는 이유도 같다 — 두 모듈이 서로 다른 시간대를 원하므로 공용 {@code Clock} 빈은 둘 중 하나를 반드시 틀리게 한다.
 */
@Configuration(proxyBeanMethods = false)
public class AnchorVerificationConfiguration {

    /**
     * 검색하는 사람이 있는 시간대. {@code backend/Dockerfile} 의 {@code ENTRYPOINT} 가 {@code -Duser.timezone=UTC} 라 <b>운영에서 JVM 기본
     * 시간대는 UTC 다</b>.
     *
     * <p>그래서 {@link Clock#systemDefaultZone()} 은 쓸 수 없다. 그걸 쓰면 KST 자정부터 오전 9시까지 UTC 가 전날이라 {@code "오늘"}·{@code "어제"} 가
     * 하루씩 밀리고, 월·연 경계에서는 {@code "지난달"}·{@code "작년"} 까지 어긋난다. 예를 들어 KST 2026-01-01 00:30 의 {@code "작년"} 은 2025년인데 UTC 로는
     * 2024년이 된다.
     */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 검증기에 넣을 시계. 빈 메서드와 나눠 둔 이유는 시간대를 <b>결정적으로</b> 검증하기 위해서다 — {@link Clock#system} 은 실제 현재 시각이라 시간대를 틀려도 연말 아홉 시간
     * 동안에만 답이 갈린다. 동작으로만 확인하면 그 창 밖에서는 잘못된 배선도 통과한다.
     */
    static Clock userClock() {
        return Clock.system(USER_ZONE);
    }

    @Bean
    AnchorVerifier anchorVerifier() {
        return new AnchorVerifier(userClock());
    }
}
