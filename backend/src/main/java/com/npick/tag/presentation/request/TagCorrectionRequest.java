package com.npick.tag.presentation.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.npick.tag.application.TagOperation;

/**
 * 태그 교정 후보 생성 요청 본문. 변경안(추가·반려·개입 해제) 목록을 담고, 교체는 반려+추가 두 항목으로 보낸다.
 *
 * <p>{@code @Valid} 로 요소까지 캐스케이드한다 — {@code @NotEmpty} 만으로는 목록이 비었는지만 보고 {@link TagOperation} 필드는 검사되지 않는다.
 */
public record TagCorrectionRequest(@NotEmpty List<@Valid @NotNull TagOperation> operations) {}
