package com.npick.common.security.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인가 규칙 검증 전용 테스트 컨트롤러. 실 컨트롤러는 Task 7·8에서 만든다.
 *
 * <p>{@code /api/v1/review/ping} 은 REVIEWER 역할, {@code /api/v1/search/ping} 은 인증만 요구한다(SecurityConfig 인가 규칙과 짝을 이룬다).
 */
@RestController
public class PingController {

    @GetMapping("/api/v1/review/ping")
    public String reviewPing() {
        return "pong";
    }

    @GetMapping("/api/v1/search/ping")
    public String searchPing() {
        return "pong";
    }
}
