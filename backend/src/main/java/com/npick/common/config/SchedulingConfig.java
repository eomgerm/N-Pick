package com.npick.common.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 애플리케이션 전역 스케줄링 활성화. 도메인별로 중복 활성화하지 않도록 이 설정이 단일 소유한다.
 *
 * <p><b>{@code @Scheduled} 가 쓸 스케줄러를 직접 지정한다.</b> 부트의 {@code TaskSchedulingAutoConfiguration} 은 {@code TaskScheduler}
 * 타입 빈이 하나라도 있으면 물러난다. 그런데 {@code @EnableWebSocketMessageBroker}(STOMP)가 {@code messageBrokerTaskScheduler} 를 등록하므로, 맡겨
 * 두면 {@code @Scheduled} 작업이 <b>브로커 스케줄러로 흘러가고 {@code spring.task.scheduling.pool.size} 가 조용히 무시된다.</b>
 *
 * <p>그 상태가 위험한 이유는 브로커 스케줄러의 크기가 {@code availableProcessors()} 라서다. 코어가 많은 개발 장비에서는 오히려 커 보여 문제가 드러나지 않지만, 코어가 적은 배포
 * 장비에서는 설정값보다 작아진다 — 무음 패딩이 굶지 않게 하려고 스케줄 작업 수에 맞춰 올려 둔 그 설정이다.
 *
 * <p>여기에 숫자를 적지 않는 이유: 스케줄 작업이 늘 때마다 {@code application.yaml} 의 값이 오르는데, 주석에 박아 두면 그때마다 같이 고쳐야 하고 잊으면 문서가 거짓말을 한다.
 *
 * <p>등록기에 직접 넘기므로 이 스케줄러는 빈이 아니다. 빈으로 두면 {@code TaskScheduler} 후보가 둘이 되어, 타입으로 주입받는 다른 코드가 어느 쪽을 얻을지 알 수 없게 된다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler scheduler;

    public SchedulingConfig(
            @Value("${spring.task.scheduling.pool.size:1}") int poolSize,
            @Value("${spring.task.scheduling.thread-name-prefix:scheduling-}") String threadNamePrefix) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.afterPropertiesSet();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
    }

    /** 테스트가 실제로 쓰이는 스케줄러를 확인할 수 있게 연다. 빈이 아니라 주입으로는 닿을 수 없다. */
    public ThreadPoolTaskScheduler scheduler() {
        return scheduler;
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
