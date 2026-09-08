package com.npick.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI apiDocs() {
        Info info = new Info().title("N-Pick API").description("""
                        N-Pick 백엔드 API 문서입니다.

                        **응답 형식**
                        모든 응답은 `{ isSuccess, code, message, timestamp, path, data }` 로 감싸집니다.
                        값이 없는 필드는 응답에서 생략됩니다.

                        성공은 `isSuccess: true` 와 `COMM_200`(조회·수정) 또는 `COMM_201`(생성) 코드로,
                        실패는 `isSuccess: false` 와 `COMM_400` · `COMM_401` · `COMM_403` · `COMM_500` 같은
                        코드로 내려옵니다. 요청 값 검증 실패는 `COMM_400_001` 입니다.

                        화면에서 분기할 때는 HTTP 상태 대신 `code` 값을 쓰세요.

                        **인증**
                        세션 기반 인증을 사용합니다. `POST /api/v1/auth/login` 으로 로그인하면 JSESSIONID 쿠키가 발급되고,
                        이후 요청은 이 쿠키로 인증됩니다. 상태 변경 요청(GET 외)에는 CSRF 토큰이 필요합니다: 먼저
                        `GET /api/v1/auth/csrf` 등으로 `XSRF-TOKEN` 쿠키를 받은 뒤, 그 값을 `X-XSRF-TOKEN` 헤더에 실어
                        보내야 합니다. 엔드포인트는 역할(예: `REVIEWER`)에 따라 접근이 제한될 수 있습니다.
                        """).version("0.0.1");

        return new OpenAPI().addServersItem(new Server().url("/")).info(info);
    }
}
