package com.npick.clip.application.query.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteRangeSelectionTest {

    private static final long TOTAL = 1_000;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void sendsWholeVideoWithoutRangeHeader(String header) {
        assertThat(ByteRangeSelection.of(header, TOTAL)).isEqualTo(new ByteRangeSelection(0, TOTAL, false));
    }

    @ParameterizedTest(name = "{0} -> offset {1}, length {2}")
    @CsvSource({
        "bytes=0-99, 0, 100",
        "bytes=500-, 500, 500",
        "bytes=0-, 0, 1000",
        "bytes=999-999, 999, 1",
        // 끝 위치가 파일을 넘으면 파일 끝으로 줄인다. 브라우저가 넉넉한 끝값을 보낸다.
        "bytes=900-99999, 900, 100",
        "bytes=-200, 800, 200",
        // 마지막 N 이 파일보다 크면 전체를 구간으로 보낸다.
        "bytes=-99999, 0, 1000",
        // 공백은 허용한다. 대소문자를 구분하지 않는다.
        "BYTES= 10 - 19 , 10, 10"
    })
    void resolvesSatisfiableRange(String header, long offset, long length) {
        assertThat(ByteRangeSelection.of(header, TOTAL)).isEqualTo(new ByteRangeSelection(offset, length, true));
    }

    /** RFC 9110 §14.2 — 이해할 수 없는 range unit 은 무시해야 한다(MUST). */
    @ParameterizedTest
    @ValueSource(strings = {"items=0-10", "seconds=0-3", "0-99"})
    void ignoresUnknownRangeUnit(String header) {
        assertThat(ByteRangeSelection.of(header, TOTAL)).isEqualTo(new ByteRangeSelection(0, TOTAL, false));
    }

    /** multipart/byteranges 를 제공하지 않으므로 여러 구간 요청은 무시하고 전체를 보낸다. */
    @Test
    void ignoresMultipleRanges() {
        assertThat(ByteRangeSelection.of("bytes=0-49,100-149", TOTAL))
                .isEqualTo(new ByteRangeSelection(0, TOTAL, false));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "bytes=1000-1099", // 시작이 파일 끝을 넘었다
                "bytes=1000-",
                "bytes=99-10", // 끝이 시작보다 앞이다
                "bytes=-0",
                "bytes=",
                "bytes=-",
                "bytes=abc-10",
                "bytes=10-abc",
                "bytes=-10-20",
                "bytes=+10-20",
                "bytes=99999999999999999999-" // long 범위를 넘는다
            })
    void rejectsUnsatisfiableOrMalformedRangeWithDistinctCode(String header) {
        assertThatThrownBy(() -> ByteRangeSelection.of(header, TOTAL))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).errorCode())
                .isEqualTo(ClipMediaErrorCode.RANGE_NOT_SATISFIABLE);
    }

    @Test
    void sendsEmptyFileWholeButRejectsAnyRangeOnIt() {
        assertThat(ByteRangeSelection.of(null, 0)).isEqualTo(new ByteRangeSelection(0, 0, false));
        assertThatThrownBy(() -> ByteRangeSelection.of("bytes=0-", 0)).isInstanceOf(BusinessException.class);
    }
}
