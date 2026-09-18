package com.npick.feedback.presentation.response;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.npick.feedback.application.query.InquiryScene;
import com.npick.feedback.application.query.MyInquiryDetail;

import static org.assertj.core.api.Assertions.assertThat;

class MyInquiryDetailResponseTest {

    private static final InquiryScene SCENE = new InquiryScene(9302L, 9101L, "설 연휴 교통", 49000L, 55000L, 9201L, 3);

    private MyInquiryDetail detailWithExplain(String explainJson) {
        return new MyInquiryDetail(
                9902L,
                9701L,
                9802L,
                Instant.now(),
                Instant.now(),
                "테스트 질의",
                null,
                "OPEN",
                null,
                SCENE,
                2,
                explainJson,
                "{}",
                null,
                null,
                null);
    }

    @Test
    @DisplayName("explain 의 display.display_name(-60 생산자 형식)이 있으면 available 과 당시 순위·explain 을 담은 result_snapshot 을 만든다")
    void availableWhenDisplayNamePresent() {
        MyInquiryDetailResponse res = MyInquiryDetailResponse.from(detailWithExplain(
                "{\"display\":{\"display_name\":\"KBC 뉴스9\",\"scene_description\":\"서울역 인파\"},\"score\":2}"));

        assertThat(res.snapshotStatus()).isEqualTo("available");
        assertThat(res.resultSnapshot()).isNotNull();
        MyInquiryDetailResponse.ResultSnapshot snap = (MyInquiryDetailResponse.ResultSnapshot) res.resultSnapshot();
        assertThat(snap.searchResultId()).isEqualTo("9802");
        assertThat(snap.sceneId()).isEqualTo("9302");
        assertThat(snap.rank()).isEqualTo(2);
        assertThat(snap.explain().at("/display/display_name").asText()).isEqualTo("KBC 뉴스9");
    }

    @Test
    @DisplayName("최상위 display_name(생산자 형식 아님)만 있으면 unavailable 이다 — display.display_name 경로만 인정")
    void unavailableWhenDisplayNameOnlyAtTopLevel() {
        MyInquiryDetailResponse res =
                MyInquiryDetailResponse.from(detailWithExplain("{\"display_name\":\"KBC 뉴스9\",\"score\":2}"));

        assertThat(res.snapshotStatus()).isEqualTo("unavailable");
        assertThat(res.resultSnapshot()).isNull();
    }

    @Test
    @DisplayName("display 객체는 있으나 display_name 이 없으면 unavailable 이다")
    void unavailableWhenDisplayWithoutName() {
        MyInquiryDetailResponse res =
                MyInquiryDetailResponse.from(detailWithExplain("{\"display\":{\"scene_description\":\"서울역 인파\"}}"));

        assertThat(res.snapshotStatus()).isEqualTo("unavailable");
        assertThat(res.resultSnapshot()).isNull();
    }

    @Test
    @DisplayName("explain 이 score-only 면 unavailable 과 null result_snapshot 이다")
    void unavailableWhenNoDisplayName() {
        MyInquiryDetailResponse res = MyInquiryDetailResponse.from(detailWithExplain("{\"score\":2}"));

        assertThat(res.snapshotStatus()).isEqualTo("unavailable");
        assertThat(res.resultSnapshot()).isNull();
    }

    @Test
    @DisplayName("explain_json 이 빈 객체여도 unavailable 로 취급한다")
    void unavailableWhenEmptyObject() {
        MyInquiryDetailResponse res = MyInquiryDetailResponse.from(detailWithExplain("{}"));

        assertThat(res.snapshotStatus()).isEqualTo("unavailable");
        assertThat(res.resultSnapshot()).isNull();
    }

    @Test
    @DisplayName("explain_json 이 null·공백이어도(방어적) unavailable 로 취급한다")
    void unavailableWhenExplainNullOrBlank() {
        assertThat(MyInquiryDetailResponse.from(detailWithExplain(null)).snapshotStatus())
                .isEqualTo("unavailable");
        assertThat(MyInquiryDetailResponse.from(detailWithExplain("   ")).snapshotStatus())
                .isEqualTo("unavailable");
    }

    @Test
    @DisplayName(
            "display.display_name 이 null(제목 없는 영상)이면 available 이며 null 을 보존한다 — 생산자는 nullable clip.title 을 그대로 기록한다")
    void availableWhenDisplayNameNull() {
        MyInquiryDetailResponse res = MyInquiryDetailResponse.from(detailWithExplain(
                "{\"display\":{\"display_name\":null,\"scene_description\":\"서울역 인파\"},\"score\":2}"));

        assertThat(res.snapshotStatus()).isEqualTo("available");
        MyInquiryDetailResponse.ResultSnapshot snap = (MyInquiryDetailResponse.ResultSnapshot) res.resultSnapshot();
        assertThat(snap.explain().at("/display/display_name").isNull()).isTrue();
    }

    @Test
    @DisplayName("display.display_name 이 빈 문자열이어도 available 이며 원값을 보존한다 — 대체 문구는 표현 계층이 정한다")
    void availableWhenDisplayNameBlank() {
        MyInquiryDetailResponse res =
                MyInquiryDetailResponse.from(detailWithExplain("{\"display\":{\"display_name\":\"\"}}"));

        assertThat(res.snapshotStatus()).isEqualTo("available");
        MyInquiryDetailResponse.ResultSnapshot snap = (MyInquiryDetailResponse.ResultSnapshot) res.resultSnapshot();
        assertThat(snap.explain().at("/display/display_name").asText()).isEmpty();
    }

    @ParameterizedTest(name = "display.display_name={0} 이면 생산자 타입(문자열·null) 이탈이라 unavailable")
    @DisplayName("display.display_name 이 문자열도 null 도 아니면 — 숫자·객체 — 불완전 스냅샷으로 unavailable 이다")
    @ValueSource(
            strings = {"{\"display\":{\"display_name\":123}}", "{\"display\":{\"display_name\":{\"nested\":\"x\"}}}"})
    void unavailableWhenDisplayNameNotStringOrNull(String explainJson) {
        MyInquiryDetailResponse res = MyInquiryDetailResponse.from(detailWithExplain(explainJson));

        assertThat(res.snapshotStatus()).isEqualTo("unavailable");
        assertThat(res.resultSnapshot()).isNull();
    }
}
