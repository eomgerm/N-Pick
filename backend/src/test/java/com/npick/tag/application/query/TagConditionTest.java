package com.npick.tag.application.query;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagConditionTest {

    @Test
    @DisplayName("정확히 맞추기는 두 끝이 같다")
    void exactHasIdenticalBounds() {
        var condition = TagCondition.exact(TagType.EVENT, "포항지진");

        assertThat(condition.fromInclusive()).isEqualTo("포항지진");
        assertThat(condition.toInclusive()).isEqualTo("포항지진");
    }

    @Test
    @DisplayName("정규화되지 않은 값을 받아도 조회가 성립한다 - 조회 경로가 정규화를 잊을 수 없다")
    void exactNormalizesMatchValue() {
        var condition = TagCondition.exact(TagType.EVENT, " 이태원 참사 ");

        assertThat(condition.fromInclusive()).isEqualTo("이태원참사");
        assertThat(condition.toInclusive()).isEqualTo("이태원참사");
        assertThat(condition).isEqualTo(TagCondition.exact(TagType.EVENT, "이태원참사"));
    }

    @Test
    @DisplayName("정규화 후 빈 문자열이 되는 값은 빈 값 경로를 탄다 - 순서가 정규화 다음 빈 값 검사다")
    void exactRejectsValueEmptiedByNormalization() {
        assertThatThrownBy(() -> TagCondition.exact(TagType.EVENT, "　 "))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("TAG_500_002"));
    }

    @Test
    @DisplayName("날짜창의 반열린 끝을 하루 당겨 닫는다 - 변환은 이 한 곳에만 있다")
    void datesClosesHalfOpenEnd() {
        var condition = TagCondition.dates(
                TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"));

        assertThat(condition.fromInclusive()).isEqualTo("2026-03-01");
        assertThat(condition.toInclusive()).isEqualTo("2026-03-31");
    }

    @Test
    @DisplayName("하루짜리 날짜창도 성립한다")
    void datesAcceptsSingleDay() {
        var condition =
                TagCondition.dates(TagType.FILMED_DATE, LocalDate.parse("2026-03-15"), LocalDate.parse("2026-03-16"));

        assertThat(condition.fromInclusive()).isEqualTo(condition.toInclusive()).isEqualTo("2026-03-15");
    }

    @Test
    @DisplayName("날짜가 아닌 종류에는 날짜창을 만들 수 없다 - 문자열 범위 비교가 성립하지 않는다")
    void datesRejectsNonDateType() {
        assertThatThrownBy(() ->
                        TagCondition.dates(TagType.EVENT, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("TAG_500_002"));
    }

    @Test
    @DisplayName("빈 구간은 거부한다 - 조용히 0건이 되면 배선 실수가 검색 결과 없음으로 위장된다")
    void rejectsEmptyWindow() {
        assertThatThrownBy(() -> TagCondition.dates(
                        TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-03-01")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("뒤집힌 범위와 빈 값을 거부한다")
    void rejectsMalformedBounds() {
        assertThatThrownBy(() -> new TagCondition(TagType.EVENT, "포항지진", "가나다")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TagCondition(TagType.EVENT, " ", " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new TagCondition(null, "a", "a")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("대소문자 무시 조건은 정규화된 한 값에 플래그를 세운다 (S15P21A501-321)")
    void exactIgnoreCaseSetsFlag() {
        var condition = TagCondition.exactIgnoreCase(TagType.KEYWORD, " Ｋ Ｂ Ｓ ");

        assertThat(condition.fromInclusive()).isEqualTo("KBS");
        assertThat(condition.toInclusive()).isEqualTo("KBS");
        assertThat(condition.ignoreCase()).isTrue();
        assertThat(condition).isNotEqualTo(TagCondition.exact(TagType.KEYWORD, "KBS"));
    }

    @Test
    @DisplayName("기존 3인자 생성과 exact·dates 는 대소문자를 구분한다 - 개체 축과 날짜는 그대로 정확 일치다")
    void threeArgumentConstructorKeepsCaseSensitivity() {
        assertThat(new TagCondition(TagType.EVENT, "a", "a").ignoreCase()).isFalse();
        assertThat(TagCondition.exact(TagType.EVENT, "포항지진").ignoreCase()).isFalse();
        assertThat(TagCondition.dates(
                                TagType.BROADCAST_DATE, LocalDate.parse("2026-03-01"), LocalDate.parse("2026-04-01"))
                        .ignoreCase())
                .isFalse();
    }

    @Test
    @DisplayName("대소문자 무시는 범위와 함께 쓸 수 없다 - 대소문자를 접은 사전순 범위는 정의하지 않는다")
    void rejectsIgnoreCaseRange() {
        assertThatThrownBy(() -> new TagCondition(TagType.KEYWORD, "a", "b", true))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("TAG_500_002"));
    }
}
