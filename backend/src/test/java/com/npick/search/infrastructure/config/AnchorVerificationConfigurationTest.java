package com.npick.search.infrastructure.config;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.npick.search.application.resolution.AnchorVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** 빈으로 올라간 검증기가 <b>JVM 기본 시간대와 무관하게</b> KST 로 상대 표현을 푸는지 고정한다. */
class AnchorVerificationConfigurationTest {

    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    /**
     * {@code backend/Dockerfile} 의 {@code ENTRYPOINT} 가 {@code -Duser.timezone=UTC} 라 운영에서 JVM 기본 시간대는 UTC 다. 그 환경을 재현해
     * 빈에 실제로 들어간 시계가 서울인지 본다.
     *
     * <p>동작이 아니라 시간대를 직접 본다. {@code "작년"} 이 몇 년으로 풀리는지로 확인하면 UTC 시계가 들어와도 연말 아홉 시간 밖에서는 답이 같아 통과해 버린다.
     */
    @Test
    void wiresUserZoneClockEvenWhenJvmDefaultIsUtc() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        new ApplicationContextRunner()
                .withUserConfiguration(AnchorVerificationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AnchorVerifier.class);
                    assertThat(context.getBean(AnchorVerifier.class).zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
                });
    }
}
