package com.npick.search.application.query.structured;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;

/** 질의 토큰(`형태/품사` 소문자)에서 keyword 태그 조건을 만든다 (S15P21A501-321). DB 없이 검증한다. */
class KeywordConditionExtractorTest {
    private final KeywordConditionExtractor extractor = new KeywordConditionExtractor();
    private static final KeywordTagSettings ON = new KeywordTagSettings(0.5, 12, List.of("앞", "북부"));

    @Test
    void singleNounsComeFirstThenAdjacentJoinsAndVerbsBreakRuns() {
        // 전세+사기 → 전세사기. 동사(보/vv)는 연결을 끊는다 — 「피해 보는 서울」 의 피해와 서울은 붙이지 않는다.
        var result = extractor.extract(List.of("전세/nng", "사기/nng", "피해/nng", "보/vv", "서울/nnp"), List.of(), ON);

        assertThat(values(result.query())).containsExactly("전세", "사기", "피해", "서울", "전세사기", "사기피해", "전세사기피해");
        assertThat(result.expanded()).isEmpty();
        assertThat(result.query())
                .allSatisfy(condition -> assertThat(condition.type()).isEqualTo(TagType.KEYWORD));
    }

    @Test
    void foreignWordsAndNumbersAreNouns() {
        var result = extractor.extract(List.of("kbs/sl", "9/sn", "뉴스/nng", "크/va"), List.of(), ON);

        assertThat(values(result.query())).containsExactly("kbs", "9", "뉴스", "kbs9", "9뉴스", "kbs9뉴스");
    }

    @Test
    void valuesAreNormalizedLikeTagMatchValues() {
        // FRD F-04: NFKC·보이지 않는 문자 제거. 전각 영문은 반각으로 접힌다.
        var result = extractor.extract(List.of("ＫＢＳ/sl", "서울​역/nnp"), List.of(), ON);

        assertThat(values(result.query())).containsExactly("KBS", "서울역", "KBS서울역");
    }

    @Test
    void stoplistedNounsBreakJoins() {
        // 제외 목록 명사는 연결도 끊는다. 「건물앞」 같은 키워드 태그는 거의 없고, 위치어를 붙인 값은 정보가 없다.
        var result = extractor.extract(List.of("건물/nng", "앞/nng"), List.of(), ON);

        assertThat(values(result.query())).containsExactly("건물");

        var second = extractor.extract(List.of("도시/nng", "북부/nng", "날씨/nng"), List.of(), ON);

        assertThat(values(second.query())).containsExactly("도시", "날씨");
    }

    @Test
    void stoplistOnlyQueryYieldsNoConditions() {
        // Review Focus 2: 정보 없는 값만 친 질의는 키워드 조건이 없다. 320 의 「다 빠지면 원래 토큰」 되살림을 여기 두지 않는다 —
        // 여기서 되살리면 「앞」 태그 장면 수백 개가 후보로 쏟아진다.
        var result = extractor.extract(List.of("앞/nng"), List.of(List.of("북부/nng")), ON);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.all()).isEmpty();
    }

    @Test
    void ignoresUntaggedAndMalformedTokens() {
        // Review Focus 1: 품사 없는 옛 형식(검증 DB 테스트 스텁이 이렇다)과 깨진 토큰은 명사로 치지 않는다. 대문자 품사는 받는다.
        var result = extractor.extract(List.of("원본질의", "/nng", "abc/", "서울역/NNP", "광장/nng"), List.of(), ON);

        // 옛 형식 토큰은 연결도 끊는다 — 그 자리에 무엇이 있었는지 모르기 때문이다.
        assertThat(values(result.query())).containsExactly("서울역", "광장", "서울역광장");
    }

    @Test
    void repeatedNounsAreDeduplicated() {
        // Review Focus 4: 같은 조건이 두 번 들어가면 분모가 부풀어 가산점이 깎인다.
        var result = extractor.extract(List.of("서울/nnp", "서울/nnp"), List.of(List.of("서울/nnp")), ON);

        assertThat(values(result.query())).containsOnlyOnce("서울");
        assertThat(values(result.expanded())).doesNotContain("서울");
    }

    @Test
    void expandedNounsAreAdmissionOnlyAndNeverDuplicateQueryNouns() {
        var result = extractor.extract(
                List.of("전세/nng"), List.of(List.of("전세/nng", "사기/nng"), List.of("깡통/nng", "주택/nng")), ON);

        assertThat(values(result.query())).containsExactly("전세");
        assertThat(values(result.expanded())).containsExactly("사기", "전세사기", "깡통", "주택", "깡통주택");
        assertThat(values(result.all())).containsExactly("전세", "사기", "전세사기", "깡통", "주택", "깡통주택");
    }

    @Test
    void capKeepsSingleNounsFirstAndLeavesNoRoomForExpanded() {
        // Review Focus 3: 명사 20개짜리 질의. 조건마다 SQL OR 절이 붙으므로 상한이 곧 조회 비용 상한이다.
        List<String> tokens =
                IntStream.rangeClosed(1, 20).mapToObj(i -> "명사" + i + "/nng").toList();

        var result = extractor.extract(tokens, List.of(List.of("확장/nng")), ON);

        assertThat(result.query()).hasSize(12);
        assertThat(values(result.query())).first().isEqualTo("명사1");
        assertThat(values(result.query())).allSatisfy(value -> assertThat(value).matches("명사\\d+"));
        assertThat(result.expanded()).isEmpty();
    }

    @Test
    void expandedFillsOnlyTheRemainingCap() {
        var cap3 = new KeywordTagSettings(0.5, 3, List.of());

        var result = extractor.extract(List.of("전세/nng"), List.of(List.of("깡통/nng", "주택/nng")), cap3);

        assertThat(values(result.query())).containsExactly("전세");
        assertThat(values(result.expanded())).containsExactly("깡통", "주택");
    }

    @Test
    void disabledSettingsProduceNothing() {
        var result = extractor.extract(List.of("전세/nng"), List.of(List.of("사기/nng")), KeywordTagSettings.OFF);

        assertThat(result).isEqualTo(KeywordConditionExtractor.Conditions.NONE);
    }

    private static List<String> values(List<TagCondition> conditions) {
        return conditions.stream().map(TagCondition::fromInclusive).toList();
    }
}
