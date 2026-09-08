package com.npick.clip.application.port;

import java.util.function.Supplier;

import com.npick.clip.application.command.register.RegisterClipResult;

/** #68 구현: 키·요청 지문을 비교하고 동시 요청을 직렬화한다. 재전송/동일 콘텐츠는 기존 결과를 반환하며 create를 실행하지 않는다. */
public interface RegistrationDeduplicationPort {
    RegisterClipResult register(
            String requestKey,
            long actorId,
            String contentHash,
            RequestData request,
            Supplier<RegisterClipResult> create);

    record RequestData(
            String sourceType,
            String title,
            java.time.LocalDate broadcastDate,
            java.time.LocalDate filmedDate,
            String scriptText,
            String subtitleHash,
            boolean rightsConfirmed,
            boolean externalProcessingConfirmed) {}
}
