package com.npick.tag.presentation.request;

import java.util.List;
import jakarta.validation.constraints.NotEmpty;

import com.npick.tag.application.TagOperation;

/** 태그 교정 후보 생성 요청 본문. 변경안(추가·반려·개입 해제) 목록을 담고, 교체는 반려+추가 두 항목으로 보낸다. */
public record TagCorrectionRequest(@NotEmpty List<TagOperation> operations) {}
