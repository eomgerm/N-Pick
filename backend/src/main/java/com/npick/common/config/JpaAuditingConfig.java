package com.npick.common.config;

import org.springframework.context.annotation.Configuration;

/**
 * JPA Auditing 설정.
 *
 * <p>엔티티가 하나도 없는 상태에서 {@code @EnableJpaAuditing} 을 켜면 {@code jpaMappingContext} 생성이 "JPA metamodel must not be empty" 로
 * 실패해 애플리케이션이 기동되지 않는다. 첫 엔티티를 추가하는 DB 연동 일감에서 아래 두 줄의 주석을 풀고 이 안내를 지운다.
 *
 * <pre>
 * import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
 * &#64;EnableJpaAuditing
 * </pre>
 */
@Configuration
public class JpaAuditingConfig {}
