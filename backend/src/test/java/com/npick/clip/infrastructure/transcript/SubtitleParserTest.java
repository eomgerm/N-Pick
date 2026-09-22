package com.npick.clip.infrastructure.transcript;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubtitleParserTest {
    private final SubtitleParser parser = new SubtitleParser();

    @Test
    void sortsWithoutChangingOverlapsTextOrOriginalIds() {
        var cues = parse("""
                {"schemaVersion":"npick.subtitle/v1","segments":[
                  {"s":1000,"e":3000,"t":" 뒤  자막 "},
                  {"s":0,"e":2000,"t":"겹치는 자막"},
                  {"s":0,"e":1500,"t":"첫 줄\\n둘째 줄"}]}
                """, "json");
        assertThat(cues).extracting(SubtitleParser.Cue::originalIndex).containsExactly(2, 1, 0);
        assertThat(cues.get(2).t()).isEqualTo(" 뒤  자막 ");
        assertThat(cues.getFirst().t()).isEqualTo("첫 줄\n둘째 줄");
        assertThat(cues).extracting(SubtitleParser.Cue::e).containsExactly(1500L, 2000L, 3000L);
    }

    @Test
    void acceptsBomCrlfVttMetadataIdentifiersAndMultilinePayload() {
        assertThat(parse("1\n00:00:00,000 --> 00:00:01,000\n서울 --> 부산", "srt")
                        .getFirst()
                        .t())
                .isEqualTo("서울 --> 부산");
        var cues = parse(
                "\uFEFFWEBVTT caption\r\n\r\nNOTE ignored\r\nmetadata\r\n\r\nSTYLE\r\n::cue { color: red; }\r\n\r\n"
                        + "cue-name\r\n00:00.123 --> 00:01.234 align:start\r\n한글 <b>자막</b>\r\n둘째 줄\r\n",
                "vtt");
        assertThat(cues).containsExactly(new SubtitleParser.Cue(0, 123, 1234, "한글 <b>자막</b>\n둘째 줄"));
        assertThat(parse("\uFEFF1\r\n00:00:00,123 --> 00:00:01,234\r\n한글\r\n", "srt")
                        .getFirst()
                        .s())
                .isEqualTo(123);
    }

    @Test
    void keepsTenMillisecondGapAndRejectsEvenOneMillisecondBeyondDuration() {
        var cues = parse("1\n00:00:00,000 --> 00:00:01,000\n첫째\n\n2\n00:00:01,010 --> 00:00:03,000\n둘째", "srt");
        assertThat(cues.get(1).s() - cues.getFirst().e()).isEqualTo(10);
        assertThatThrownBy(() -> parse("1\n00:00:00,000 --> 00:00:03,001\n초과", "srt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("영상 길이");
    }

    static Stream<Arguments> boundaryFiles() {
        return Stream.of(
                Arguments.of(
                        "json",
                        json("{\"s\":2000,\"e\":2500,\"t\":\"정상\"}," + "{\"s\":0,\"e\":3000,\"t\":\"비밀 원문\"}"),
                        "segments[1].e"),
                Arguments.of(
                        "srt",
                        "1\n00:00:02,000 --> 00:00:02,500\n정상\n\n" + "2\n00:00:00,000 --> 00:00:03,000\n비밀 원문",
                        "line 6 (cue 2).e"),
                Arguments.of(
                        "vtt",
                        "WEBVTT\n\n1\n00:02.000 --> 00:02.500\n정상\n\n" + "2\n00:00.000 --> 00:03.000\n비밀 원문",
                        "line 8 (cue 2).e"));
    }

    @ParameterizedTest
    @MethodSource("boundaryFiles")
    void usesIntegerMsCeilingAsUpperBound(String format, String text, String location) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // 2.999999 는 올림하면 3000 이므로 수용한다. 1ms 미만 차이는 두 길이 축의 반올림 오차일 뿐이다.
        var sub = parser.parse(bytes, format, new BigDecimal("2.999999"));
        var exact = parser.parse(bytes, format, new BigDecimal("3.000000"));
        var within = parser.parse(bytes, format, new BigDecimal("3.000001"));
        assertThat(exact)
                .containsExactly(
                        new SubtitleParser.Cue(1, 0, 3000, "비밀 원문"), new SubtitleParser.Cue(0, 2000, 2500, "정상"));
        assertThat(sub).isEqualTo(exact);
        assertThat(within).isEqualTo(exact);
        // 1ms 이상 벌어지면 반올림 오차로 설명되지 않는다. 그대로 거절하고 원문은 오류에 싣지 않는다.
        assertThatThrownBy(() -> parser.parse(bytes, format, new BigDecimal("2.999")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_400_012"))
                .hasMessageContaining(location)
                .hasMessageContaining("구간 [0, 3000] ms")
                .hasMessageContaining("영상 길이 2999 ms의 정수 ms 상한 2999 ms")
                .hasMessageContaining("1 ms 초과")
                .hasMessageContaining("파일 전체를 거절")
                .hasMessageNotContaining("비밀 원문");
    }

    @Test
    void extractedHourCompatibilityDoesNotRelaxUploadSyntaxOrTimeValidation() {
        byte[] vtt = "WEBVTT\n\n1:00:00.100 --> 1:00:00.500\n한글\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(vtt, "vtt", new BigDecimal("3601")))
                .hasMessageContaining("시간 표기");
        assertThatThrownBy(() -> parser.parse(
                        "1\n1:00:00,100 --> 1:00:00,500\n한글".getBytes(StandardCharsets.UTF_8),
                        "srt",
                        new BigDecimal("3601")))
                .hasMessageContaining("시간 표기");
        assertThatThrownBy(() -> parser.parseExtractedVtt(vtt, new BigDecimal("3600.4")))
                .hasMessageContaining("100 ms 초과");
        for (String invalid : new String[] {"1:60:00.100", "1:00:60.100", "1:00:00.1"}) {
            byte[] text = ("WEBVTT\n\n" + invalid + " --> 1:00:00.500\n한글").getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> parser.parseExtractedVtt(text, new BigDecimal("7200")))
                    .hasMessageContaining("시간 표기");
        }
    }

    static Stream<String> invalidJson() {
        return Stream.of(
                "{}",
                "[]",
                "{\"schemaVersion\":\"v2\",\"segments\":[]}",
                "{\"schemaVersion\":\"npick.subtitle/v1\",\"segments\":{}}",
                "{\"schemaVersion\":\"npick.subtitle/v1\",\"segments\":[]}",
                json("{\"s\":0,\"e\":1}"),
                json("{\"s\":0,\"e\":1,\"t\":null}"),
                json("{\"s\":0,\"e\":1,\"t\":5}"),
                json("{\"s\":0,\"e\":1,\"t\":\"  \\n\"}"),
                json("{\"s\":-1,\"e\":1,\"t\":\"x\"}"),
                json("{\"s\":0.0,\"e\":1,\"t\":\"x\"}"),
                json("{\"s\":0,\"e\":1.5,\"t\":\"x\"}"),
                json("{\"s\":0,\"e\":0,\"t\":\"x\"}"),
                json("{\"s\":2,\"e\":1,\"t\":\"x\"}"),
                json("{\"s\":\"0\",\"e\":1,\"t\":\"x\"}"),
                json("{\"s\":0,\"e\":9223372036854775808,\"t\":\"x\"}"),
                json("{\"s\":0,\"e\":1,\"t\":\"x\",\"sourceDetail\":\"asr\"}"),
                json("{\"s\":0,\"e\":1,\"t\":\"x\",\"s\":1}"),
                json("{\"s\":0,\"e\":1,\"t\":\"x\"}") + " {}",
                "{invalid");
    }

    @ParameterizedTest
    @MethodSource("invalidJson")
    void rejectsWholeInvalidJsonFile(String text) {
        assertThatThrownBy(() -> parse(text, "json"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("CLIP_400_012"));
    }

    @Test
    void reportsOriginalIndexBeforeSortingAndDoesNotEchoText() {
        assertThatThrownBy(() -> parse(
                        json("{\"s\":0,\"e\":1000,\"t\":\"정상\"}," + "{\"s\":1500,\"e\":1400,\"t\":\"비밀 원문\"}"), "json"))
                .hasMessageContaining("segments[1].e")
                .hasMessageNotContaining("비밀 원문");
    }

    @Test
    void rejectsMalformedUtf8AndMalformedOrEmptyTimedCues() {
        assertThatThrownBy(() -> parse(
                        "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:00:00.000,MPEGTS:90000\n\n00:00.000 --> 00:01.000\nx", "vtt"))
                .hasMessageContaining("line 2")
                .hasMessageContaining("타임라인");
        assertThatThrownBy(() -> parser.parse(new byte[] {(byte) 0xc3, 0x28}, "srt", BigDecimal.TEN))
                .hasMessageContaining("UTF-8");
        for (String text : new String[] {
            "1\n00:00:61,000 --> 00:00:62,000\nx",
            "1\n00:00:00,000 --> 00:00:01,000\n",
            "1\n00:00:00,000 --> 00:00:01,000\nx\n2\n00:00:01,000 --> 00:00:02,000\ny"
        }) {
            assertThatThrownBy(() -> parse(text, "srt")).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> parse("WEBVTT\n00:00.000 --> 00:01.000\nx", "vtt"))
                .hasMessageContaining("빈 줄");
    }

    /**
     * 큐 시각은 정수 ms 이고 ffprobe 길이는 소수 ms 다. 영상 끝까지 덮는 자막은 종료를 올릴 수밖에 없으므로 두 축을 오차 0 으로 비교하면 1ms 미만 초과로 파일 전체가 거절된다
     * (S15P21A501-258). {@code KNA_02701} 은 영상 15181.833ms / 프레임 기반 15182ms 였다.
     */
    static Stream<Arguments> tailCoveringFiles() {
        return Stream.of(
                Arguments.of("json", json("{\"s\":15000,\"e\":15182,\"t\":\"끝까지\"}")),
                Arguments.of("srt", "1\n00:00:15,000 --> 00:00:15,182\n끝까지"),
                Arguments.of("vtt", "WEBVTT\n\n00:15.000 --> 00:15.182\n끝까지"));
    }

    @ParameterizedTest
    @MethodSource("tailCoveringFiles")
    void acceptsCueEndAtIntegerMsCeilingOfFractionalDuration(String format, String text) {
        assertThat(parser.parse(text.getBytes(StandardCharsets.UTF_8), format, new BigDecimal("15.181833")))
                .containsExactly(new SubtitleParser.Cue(0, 15000, 15182, "끝까지"));
    }

    @ParameterizedTest
    @MethodSource("tailCoveringFiles")
    void rejectsCueEndBeyondIntegerMsCeiling(String format, String text) {
        byte[] beyond = text.replace("15,182", "15,183")
                .replace("15.182", "15.183")
                .replace("15182", "15183")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(beyond, format, new BigDecimal("15.181833")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode().code()).isEqualTo("CLIP_400_012"))
                .hasMessageContaining("영상 길이 15181.833 ms")
                .hasMessageContaining("정수 ms 상한 15182 ms")
                .hasMessageContaining("1 ms 초과");
    }

    /** 허용오차가 1ms 상수 가산이 아니라 올림이라는 것을 고정한다. 정수 길이에서는 상한이 그대로다. */
    @Test
    void integerDurationKeepsExactUpperBound() {
        assertThat(parse("1\n00:00:00,000 --> 00:00:03,000\n정상", "srt")).hasSize(1);
        assertThatThrownBy(() -> parse("1\n00:00:00,000 --> 00:00:03,001\n초과", "srt"))
                .hasMessageContaining("정수 ms 상한 3000 ms");
    }

    /** 내장 CC 추출도 같은 큐 검사를 지나므로 상한 규칙이 갈리지 않는다. */
    @Test
    void extractedCcSharesTheSameIntegerMsCeiling() {
        byte[] vtt = "WEBVTT\n\n0:00:15.000 --> 0:00:15.182\n끝까지".getBytes(StandardCharsets.UTF_8);
        assertThat(parser.parseExtractedVtt(vtt, new BigDecimal("15.181833")))
                .containsExactly(new SubtitleParser.Cue(0, 15000, 15182, "끝까지"));
    }

    static String json(String segments) {
        return "{\"schemaVersion\":\"npick.subtitle/v1\",\"segments\":[" + segments + "]}";
    }

    private java.util.List<SubtitleParser.Cue> parse(String text, String format) {
        return parser.parse(text.getBytes(StandardCharsets.UTF_8), format, new BigDecimal("3"));
    }
}
