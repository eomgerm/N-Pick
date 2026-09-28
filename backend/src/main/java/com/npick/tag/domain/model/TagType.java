package com.npick.tag.domain.model;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.BusinessException;
import com.npick.tag.domain.error.TagErrorCode;

/**
 * 태그 종류 11가지 (FRD v3.1 F-04).
 *
 * <p>FRD 가 "유형을 추가하지 않는다" 로 닫은 목록이다 — 군중 밀도는 §1.2 에서 범위 밖으로 확정됐다. 그래서 enum 으로 둔다.
 *
 * <p>샷 유형은 여기 없다. 태그가 아니라 {@code scene.shot_type} 컬럼이다 (F-04).
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum TagType {

    // 개체
    PERSON("person"),
    ORGANIZATION("organization"),
    LOCATION("location"),
    FACILITY("facility"),
    KEYWORD("keyword"),
    EVENT("event"),

    // 분류. 계절·날씨·장면 유형을 별도 컬럼으로 중복 관리하지 않는다 (F-04 완료 기준)
    SEASON("season"),
    WEATHER("weather"),
    SCENE_TYPE("scene_type"),

    // 날짜. 두 값을 서로 대체하지 않는다 (F-06)
    FILMED_DATE("filmed_date"),
    BROADCAST_DATE("broadcast_date");

    /** {@code tag.tag_type} 에 실제로 들어 있는 문자열. enum 이름을 소문자로 바꾼 것과 우연히 같아도 파생시키지 않는다 — 컬럼 값이 정본이다. */
    private final String storedValue;

    private static final Map<String, TagType> BY_STORED_VALUE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(TagType::storedValue, Function.identity()));

    /**
     * 저장된 문자열을 종류로 바꾼다.
     *
     * @throws BusinessException 11종에 없는 값이면 {@link TagErrorCode#UNKNOWN_TAG_TYPE}
     */
    public static TagType from(String storedValue) {
        TagType found = BY_STORED_VALUE.get(storedValue);
        if (found == null) {
            throw new BusinessException(TagErrorCode.UNKNOWN_TAG_TYPE);
        }
        return found;
    }

    /**
     * 날짜 태그인가.
     *
     * <p>날짜만 {@code match_value} 에 {@code YYYY-MM-DD} 형식이 {@code CHECK} 로 강제된다. 그래서 이 종류에서만 문자열 범위 비교가 시간 순서와 일치한다.
     */
    public boolean date() {
        return this == FILMED_DATE || this == BROADCAST_DATE;
    }
}
