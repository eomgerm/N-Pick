package com.npick.common.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfigurationSource;

import com.npick.common.security.handler.RestAccessDeniedHandler;
import com.npick.common.security.handler.RestAuthenticationEntryPoint;

/** 보안 필터 체인. deny-by-default: 명시적으로 공개/역할 지정한 경로 외에는 모두 인증을 요구한다. */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.spa())
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .addFilterBefore(new AccessSessionExpiryFilter(), SecurityContextHolderFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // API의 401을 서버 세션에 저장하지 않는다. 복귀 URL은 FE가 소유한다.
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/csrf",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout")
                        .permitAll()
                        .requestMatchers("/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/api/v1/review/**", "/api/v1/clips/**")
                        .hasRole("REVIEWER")
                        // 검색 결과의 Preview 와 장면 대표 이미지는 검수자 전용이 아니다 (FRD F-07).
                        .requestMatchers("/api/v1/search/**", "/api/v1/media/**", "/api/v1/scenes/*/thumbnail")
                        .authenticated()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(authenticationEntryPoint).accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    public CookieSerializer sessionCookieSerializer(
            @Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseSecureCookie(secure);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Strict");
        return serializer;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
