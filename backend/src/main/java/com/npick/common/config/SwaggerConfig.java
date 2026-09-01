package com.npick.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI apiDocs() {
        Info info = new Info().title("ZANI API").description("""
                        ZANI 백엔드 API 문서입니다.

                        **인증 방법**
                        1. `POST /api/v1/auth/login/google` 로 로그인하면 Access Token 을 받습니다.
                        2. 이 페이지 오른쪽 위 **Authorize** 버튼에 Access Token 을 붙여넣으면, 이후 요청에 `Authorization: Bearer ...` 헤더가 자동으로 붙습니다.
                        3. Access Token 은 1시간 뒤 만료됩니다. 만료되면 `POST /api/v1/auth/refresh` 로 새로 발급받으세요(Refresh Token 은 쿠키로 오갑니다).

                        **응답 형식**
                        모든 응답은 `{ isSuccess, code, message, data }` 로 감싸져 있습니다. 실패 시 `code` 에 `SESSION_APP_006` 같은 식별자가 담기니, 화면에서 분기할 때는 HTTP 상태 대신 이 값을 쓰세요.
                        """).version("0.0.1");
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .info(info)
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME, bearerScheme))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
