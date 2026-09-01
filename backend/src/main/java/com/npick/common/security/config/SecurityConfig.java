package com.npick.common.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 보안 필터 체인.
 *
 * <p>인증은 아직 도입되지 않아 모든 요청을 허용한다. 이 설정이 지금 하는 일은 {@link CorsConfigurationSource} 를 필터 체인에 연결하는 것이다. Spring MVC 는 해당 빈을
 * 스스로 소비하지 않으므로, 이 체인이 없으면 CORS 설정이 아무 효과를 내지 못한다.
 *
 * <p>인증 일감에서 {@code anyRequest().permitAll()} 을 실제 인가 규칙으로 바꾼다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
