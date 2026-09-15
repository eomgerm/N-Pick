package com.npick.tag.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 태그 교정 변경안 한 줄. 작업 종류·범위와 대상 태그를 담는다.
 *
 * <p>경계에서 요소 단위로 검증한다 — 목록이 비지 않았는지만 보는 상위 {@code @NotEmpty} 로는 요소 필드가 걸러지지 않아, 누락·초과가 서비스·DB 까지 흘러가 500 이 된다. {@code
 * tagType} 의 어휘(11종) 검증과 {@code matchValue} 정규화는 서비스가 한다 — 값 판정에 도메인 규칙이 필요하기 때문이다.
 *
 * @param action 승인·반려·개입 해제
 * @param scope 장면 범위인가 클립 범위인가
 * @param tagType 태그 유형
 * @param matchValue 검색용 값(서버가 정규화한다)
 * @param displayName 표시 이름
 */
public record TagOperation(
        @NotNull TagCorrectionAction action,
        @NotNull TagScope scope,
        @NotBlank @Size(max = 32) String tagType,
        @NotBlank @Size(max = 255) String matchValue,
        @NotBlank @Size(max = 255) String displayName) {}
