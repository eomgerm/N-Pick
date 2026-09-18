package com.npick.search.infrastructure.config;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import com.npick.search.application.query.search.SearchDurationTimer;
import com.npick.search.application.query.search.SearchPath;

/**
 * {@code npick.search.duration} 타이머. actuator 가 이미 있어 의존성을 더 붙이지 않는다.
 *
 * <p>경로를 태그로 두면 한 지표에서 네 분포를 각각 볼 수 있다. 지표를 넷으로 나누면 전체 분포를 보려고 다시 합쳐야 하고, 합치는 쪽이 실수하기 쉽다.
 */
@Component
class MicrometerSearchDurationTimer implements SearchDurationTimer {

    private static final String METER = "npick.search.duration";

    private final MeterRegistry registry;

    MicrometerSearchDurationTimer(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(long duration, TimeUnit unit, SearchPath path) {
        Timer.builder(METER)
                .description("검색 한 번의 소요 시간. 경로별로 나눠 잰다 (FRD 8.2)")
                .tag("path", path.tag())
                .publishPercentileHistogram()
                .register(registry)
                .record(duration, unit);
    }
}
