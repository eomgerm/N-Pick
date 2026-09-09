package com.npick.tag.application.query;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagMatchRangeTest {

    @Test
    @DisplayName("정확히 맞추기는 두 끝이 같다")
    void exactHasIdenticalBounds() {
        var range = TagMatchRange.exact(TagType.EVENT, "포항지진");

        assertThat(range.fromInclusive()).isEqualTo("포항지진");
        assertThat(range.toInclusive()).isEqualTo("포항지진");
    }

    @Test
    @DisplayName("날짜창의 반열린 끝을 하루 당겨 닫는다 - 변환은 이 한 곳에만 있다")
    void datesClosesHalfOpenEnd() {
        var range = TagMatchRange.dates(
                TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"));

        assertThat(range.fromInclusive()).isEqualTo("2026-03-01");
        assertThat(range.toInclusive()).isEqualTo("2026-03-31");
    }

    @Test
    @DisplayName("하루짜리 날짜창도 성립한다")
    void datesAcceptsSingleDay() {
        var range =
                TagMatchRange.dates(TagType.FILMED_DATE, LocalDate.parse("2026-03-15"), LocalDate.parse("2026-03-16"));

        assertThat(range.fromInclusive()).isEqualTo(range.toInclusive()).isEqualTo("2026-03-15");
    }

    @Test
    @DisplayName("날짜가 아닌 종류에는 날짜창을 만들 수 없다 - 문자열 범위 비교가 성립하지 않는다")
    void datesRejectsNonDateType() {
        assertThatThrownBy(() -> TagMatchRange.dates(
                        TagType.EVENT, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("TAG_500_002"));
    }

    @Test
    @DisplayName("빈 구간은 거부한다 - 조용히 0건이 되면 배선 실수가 검색 결과 없음으로 위장된다")
    void rejectsEmptyWindow() {
        assertThatThrownBy(() -> TagMatchRange.dates(
                        TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("뒤집힌 범위와 빈 값을 거부한다")
    void rejectsMalformedBounds() {
        assertThatThrownBy(() -> new TagMatchRange(TagType.EVENT, "포항지진", "가나다"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TagMatchRange(TagType.EVENT, " ", " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TagMatchRange(null, "a", "a")).isInstanceOf(BusinessException.class);
    }
}
