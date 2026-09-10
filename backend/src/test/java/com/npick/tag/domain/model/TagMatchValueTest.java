package com.npick.tag.domain.model;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagMatchValueTest {

    @Test
    @DisplayName("같은 값의 다른 표기가 한 값으로 모인다 - UNIQUE(tag_type, match_value) 가 중복 저장을 막을 수 있는 형태")
    void foldsSurfaceVariantsIntoOneValue() {
        assertThat(TagMatchValue.normalize("이태원 참사"))
                .isEqualTo(TagMatchValue.normalize("이태원참사"))
                .isEqualTo(TagMatchValue.normalize(" 이태원  참사 "))
                .isEqualTo(TagMatchValue.normalize("이태원\t참사"))
                // 전각 공백(U+3000) 과 줄바꿈 없는 공백(U+00A0). NFKC 가 보통 공백으로 편 뒤에 지워진다
                .isEqualTo(TagMatchValue.normalize("이태원　참사"))
                .isEqualTo(TagMatchValue.normalize("이태원 참사"))
                .isEqualTo("이태원참사");
    }

    @Test
    @DisplayName("macOS 의 NFD 한글과 Windows 의 NFC 한글이 같은 값이 된다")
    void foldsDecomposedHangul() {
        String decomposed = Normalizer.normalize("이태원참사", Normalizer.Form.NFD);

        assertThat(decomposed).isNotEqualTo("이태원참사");
        assertThat(TagMatchValue.normalize(decomposed)).isEqualTo("이태원참사");
    }

    @Test
    @DisplayName("공백을 지운 뒤 성립하는 결합까지 접는다 - 공백 제거 다음의 두 번째 NFKC 가 하는 일")
    void foldsCompositionsUnblockedByWhitespaceRemoval() {
        // 공백이 막고 있던 결합이 공백 제거로 성립한다. 다시 접지 않으면 같은 값이 두 표기로 UNIQUE 를 통과한다.
        assertThat(TagMatchValue.normalize("ㄱ ㅏ")).isEqualTo(TagMatchValue.normalize("ㄱㅏ"));
        // 호환문자가 공백을 만들어내기도 한다 — U+00A8 은 NFKC 에서 공백 + 결합 분음이 된다.
        assertThat(TagMatchValue.normalize("a¨")).isEqualTo(TagMatchValue.normalize("ä"));
    }

    @Test
    @DisplayName("멱등하다 - 저장된 값에 다시 걸어도 값이 바뀌지 않는다")
    void isIdempotent() {
        for (String raw : new String[] {"ㄱ ㅏ", "a¨", "이태원 참사", "㈜한국", "2026-03-15"}) {
            String once = TagMatchValue.normalize(raw);

            assertThat(TagMatchValue.normalize(once)).isEqualTo(once);
        }
    }

    @Test
    @DisplayName("NFKC 가 안 건드리는 공백도 지운다 - \\s 가 아니라 \\p{IsWhite_Space} 를 쓰는 이유")
    void stripsWhitespaceUntouchedByNfkc() {
        // U+2028 LINE SEPARATOR. NFKC 가 안 건드리므로 \s 였다면 살아남는다. 리터럴은 눈에 안 보여 코드포인트로 만든다.
        String withLineSeparator = "이태원" + Character.toString(0x2028) + "참사";

        assertThat(TagMatchValue.normalize(withLineSeparator)).isEqualTo("이태원참사");
    }

    @Test
    @DisplayName("호환문자를 접는다 - NFC 로는 남는 차이다")
    void foldsCompatibilityCharacters() {
        assertThat(TagMatchValue.normalize("ＫＢＳ")).isEqualTo("KBS");
        assertThat(TagMatchValue.normalize("㈜한국")).isEqualTo("(주)한국");
        assertThat(TagMatchValue.normalize("Ⅳ호기")).isEqualTo("IV호기");
    }

    @Test
    @DisplayName("대소문자를 보존한다 - casefold 를 걸지 않는다")
    void preservesCase() {
        assertThat(TagMatchValue.normalize("KBS 뉴스")).isEqualTo("KBS뉴스");
        assertThat(TagMatchValue.normalize("kbs")).isNotEqualTo(TagMatchValue.normalize("KBS"));
    }

    @Test
    @DisplayName("동의어·별칭을 확장하지 않는다 - 표면형만 정리하고 의미는 건드리지 않는다")
    void doesNotExpandAliases() {
        assertThat(TagMatchValue.normalize("포항")).isEqualTo("포항");
        assertThat(TagMatchValue.normalize("포항시")).isNotEqualTo(TagMatchValue.normalize("포항"));
        assertThat(TagMatchValue.normalize("서울역")).isEqualTo("서울역");
    }

    @Test
    @DisplayName("공백만 있는 입력과 널은 빈 문자열이 된다 - 빈 값 판정은 호출자 몫이다")
    void emptiesBlankInput() {
        assertThat(TagMatchValue.normalize("   ")).isEmpty();
        assertThat(TagMatchValue.normalize("　")).isEmpty();
        assertThat(TagMatchValue.normalize("")).isEmpty();
        assertThat(TagMatchValue.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("날짜 태그 값은 그대로 남는다 - ck_tag_date 의 YYYY-MM-DD 가 깨지지 않는다")
    void keepsDateValueIntact() {
        assertThat(TagMatchValue.normalize("2026-03-15")).isEqualTo("2026-03-15");
    }
}
