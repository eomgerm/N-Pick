package com.npick.pipeline.infrastructure.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.response.ApiResponse;
import com.npick.pipeline.application.error.WorkerIntegrationErrorCode;

@Configuration
public class WorkerJobSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain workerJobSecurity(
            HttpSecurity http, ObjectMapper mapper, @Value("${npick.worker-jobs.tokens:}") String tokens)
            throws Exception {
        List<byte[]> allowed = Arrays.stream(tokens.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> token.getBytes(StandardCharsets.UTF_8))
                .toList();
        if (allowed.stream().anyMatch(token -> token.length < 32))
            throw new IllegalArgumentException("worker token must contain at least 32 bytes");
        return http.securityMatcher("/api/v1/internal/jobs/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(
                        new OncePerRequestFilter() {
                            @Override
                            protected boolean shouldNotFilterAsyncDispatch() {
                                return false;
                            }

                            @Override
                            protected void doFilterInternal(
                                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                                    throws ServletException, IOException {
                                String authorization = request.getHeader("Authorization");
                                String worker = request.getHeader("X-Worker-Id");
                                byte[] candidate = (authorization != null && authorization.startsWith("Bearer ")
                                                ? authorization.substring(7)
                                                : "")
                                        .getBytes(StandardCharsets.UTF_8);
                                boolean accepted = false;
                                for (byte[] token : allowed) accepted |= MessageDigest.isEqual(token, candidate);
                                if (!accepted || worker == null || worker.isBlank() || worker.length() > 64) {
                                    response.setStatus(401);
                                    response.setContentType("application/json");
                                    mapper.writeValue(
                                            response.getOutputStream(),
                                            ApiResponse.failure(
                                                    WorkerIntegrationErrorCode.UNAUTHORIZED, request.getRequestURI()));
                                    return;
                                }
                                var context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(
                                        new UsernamePasswordAuthenticationToken(worker, null, List.of()));
                                SecurityContextHolder.setContext(context);
                                chain.doFilter(request, response);
                            }
                        },
                        BasicAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .build();
    }
}
