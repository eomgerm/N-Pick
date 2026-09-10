package com.npick.tag.domain.model;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * {@code tag.match_value} 를 만드는 정규화 (FRD v3.1 F-04).
 *
 * <p>규칙은 <b>유니코드 NFKC + 공백 제거</b> 다. 표기가 조금만 달라도 {@code UNIQUE(tag_type, match_value)} 를 통과해 같은 값이 두 행이 되고, 정확 일치 조회는
 * 오류 없이 0건이 된다. 화면에 보일 띄어쓰기는 {@code tag.name} 이 따로 들고 있어 여기서 접혀도 잃는 것이 없다.
 *
 * <p>NFKC 인 이유는 질의 경로가 이미 끝에서 끝까지 NFKC 이기 때문이다 (Python 정규화기, {@code NormalizedSearch.canonicalText}). 태그만 다른 형태를 쓰면 세
 * 번째 정규화 형태가 생긴다. NFC 는 macOS 의 NFD 한글과 Windows 의 NFC 한글만 맞추고 호환문자({@code ㈜}→{@code (주)}, 전각→반각)는 남긴다.
 *
 * <p>하지 않는 것:
 *
 * <ul>
 *   <li><b>casefold</b> — 태그 값은 대소문자가 의미를 가를 수 있다. {@code NormalizedSearch} 가 명시 필터에서 casefold 를 뺀 것과 같은 이유다
 *   <li><b>동의어·별칭 확장</b> — 표면형만 정리하고 의미는 건드리지 않는다. 별칭은 리졸버의 {@code expandedTerms} 소관이다
 *   <li><b>형태소 분석</b> — Kiwi 는 토큰 목록을 만드는 도구고 {@code match_value} 는 쪼개지 않는 문자열 하나다
 * </ul>
 *
 * <p>이 함수는 백엔드에 하나만 있다. 워커는 DB 에 접속하지 않아(02-container §116) 태그를 쓰는 모든 경로가 여기를 지난다. 구현이 둘이 되면 둘이 어긋나는 순간이 "조용히 0건" 의 원천이
 * 된다.
 */
public final class TagMatchValue {

    /** NFKC 가 {@code U+3000} 같은 호환 공백을 보통 공백으로 편 뒤에 지워야 하므로 순서가 「NFKC → 공백 제거」 다. */
    private static final Pattern WHITESPACE = Pattern.compile("\\p{IsWhite_Space}+");

    private TagMatchValue() {}

    /**
     * 표시값을 매칭용 정규화값으로 바꾼다.
     *
     * <p><b>빈 값 검사는 호출자 몫이다.</b> 공백만 있는 입력은 여기서 빈 문자열이 되고, 그 판정은 호출자가 이미 가진 빈 값 경로가 한다 — 순서가 「정규화 → 빈 값 검사」 여야 공백만 있는
     * 입력과 처음부터 빈 입력이 같은 곳에서 걸린다. 널도 같은 이유로 빈 문자열로 접는다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return WHITESPACE
                .matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC))
                .replaceAll("");
    }
}
