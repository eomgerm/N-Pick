package com.npick.tag.application.query;

import java.time.LocalDate;

import com.npick.common.error.BusinessException;
import com.npick.tag.domain.error.TagErrorCode;
import com.npick.tag.domain.model.TagType;

/**
 * 찾을 태그의 조건 하나. 정확히 맞추기와 날짜 범위를 <b>한 형태</b> 로 합쳤다.
 *
 * <p>정확히 맞추기는 {@code fromInclusive == toInclusive} 이고, 날짜창은 두 끝이 다르다. 술어를 두 종류로 나누지 않으므로 SQL 에는 {@code tag_type = ? AND
 * match_value BETWEEN ? AND ?} 하나만 있고, {@code uq_tag_type_match_value} 를 그대로 탄다.
 *
 * <p>날짜 비교가 문자열로 되는 것은 {@code ck_tag_date} 가 {@code YYYY-MM-DD} 를 강제하기 때문이다. ISO 8601 은 사전순이 시간순이라 범위 비교가 성립한다. 그 형식이
 * 깨지면 범위 비교가 무너진다 — {@code tag.match_value} 컬럼 주석이 경고하는 내용이다.
 *
 * @param type 태그 종류
 * @param fromInclusive 시작값. 포함
 * @param toInclusive 끝값. <b>포함</b>
 */
public record TagMatchRange(TagType type, String fromInclusive, String toInclusive) {

    public TagMatchRange {
        boolean malformed = type == null
                || fromInclusive == null
                || toInclusive == null
                || fromInclusive.isBlank()
                || toInclusive.isBlank()
                || fromInclusive.compareTo(toInclusive) > 0;
        // 뒤집힌 범위는 SQL 에서 오류 없이 0건이 된다. 그러면 배선 실수가 "검색 결과 없음" 으로 위장된다.
        if (malformed) {
            throw new BusinessException(TagErrorCode.INVALID_TAG_MATCH_RANGE);
        }
    }

    /**
     * 개체·사건명·분류를 정규화값으로 정확히 맞춘다. 리졸버가 낸 값을 그대로 넣는다.
     *
     * <p><b>호출자가 빈 값을 걸러야 한다.</b> 여기 오는 값은 리졸버의 정규화 출력이고, 그것은 사용자가 친 검색어에서 파생된다 — LLM 이 정규화하지 못한 이름에 빈 문자열을 낼 수 있다. 그런
     * 값을 그대로 넣으면 {@link TagErrorCode#INVALID_TAG_MATCH_RANGE} 5xx 가 되어, 사용자 질의에서 비롯된 일을 서버 결함으로 집계한다. 옳은 동작은 그 조건 하나를
     * 빼고 나머지로 검색하는 것이다(F-05 의 축소 동작). 이 생성자의 거부는 그 필터가 빠졌을 때의 마지막 방어선이다.
     */
    public static TagMatchRange exact(TagType type, String matchValue) {
        return new TagMatchRange(type, matchValue, matchValue);
    }

    /**
     * 날짜창. 리졸버의 반열린 구간 {@code [start, endExclusive)} 을 받는다.
     *
     * <p>여기서 <b>닫힌 구간으로 한 번만</b> 바꾼다. 날짜 태그는 하루 단위가 {@code CHECK} 로 강제되므로 마지막 날을 하루 당기면 같은 집합이 된다. 변환이 이 한 곳에만 있어야
     * 호출부마다 경계 해석이 갈리지 않는다.
     *
     * @throws BusinessException 날짜 태그가 아니거나 구간이 비면 {@link TagErrorCode#INVALID_TAG_MATCH_RANGE}
     */
    public static TagMatchRange dates(TagType type, LocalDate startInclusive, LocalDate endExclusive) {
        if (type == null || !type.date() || startInclusive == null || endExclusive == null) {
            throw new BusinessException(TagErrorCode.INVALID_TAG_MATCH_RANGE);
        }
        if (!endExclusive.isAfter(startInclusive)) {
            throw new BusinessException(TagErrorCode.INVALID_TAG_MATCH_RANGE);
        }
        return new TagMatchRange(
                type, startInclusive.toString(), endExclusive.minusDays(1).toString());
    }
}
