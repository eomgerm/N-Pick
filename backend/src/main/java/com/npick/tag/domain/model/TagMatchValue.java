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
 * <p>이 함수는 백엔드에 하나만 있다. 워커는 DB 에 접속하지 않아(02-container.md:116) 태그를 쓰는 모든 경로가 여기를 지난다. 구현이 둘이 되면 둘이 어긋나는 순간이 "조용히 0건" 의
 * 원천이 된다.
 */
public final class TagMatchValue {

    private static final Pattern WHITESPACE = Pattern.compile("\\p{IsWhite_Space}+");

    private TagMatchValue() {}

    /**
     * 표시값을 매칭용 정규화값으로 바꾼다.
     *
     * <p>순서는 <b>NFKC → 공백 제거 → NFKC</b> 다. 세 걸음이 각각 필요하다.
     *
     * <ol>
     *   <li>첫 NFKC — {@code U+3000} 같은 호환 공백을 보통 공백으로 펴야 다음 걸음이 지울 수 있다
     *   <li>공백 제거 — 표기 차이의 대부분이다
     *   <li><b>두 번째 NFKC</b> — 공백을 지우면 그 공백이 막고 있던 결합이 성립한다. 다시 접지 않으면 결과가 정규형이 아니고, 그러면 같은 값이 두 표기로
     *       {@code UNIQUE(tag_type, match_value)} 를 통과한다
     * </ol>
     *
     * <p>두 번째 NFKC 가 없으면 이렇게 깨진다 — {@code "ㄱ ㅏ"} 는 {@code U+1100 U+1161} 로 남는데 {@code "ㄱㅏ"} 는 {@code U+AC00} 이 되고,
     * {@code "a¨"} 는 {@code U+0061 U+0308} 로 남는데 {@code "ä"} 는 {@code U+00E4} 가 된다. 호환문자가 공백을 <i>만들어내기도</i>
     * 하므로({@code ¨} → 공백 + 결합 분음) 흔한 입력에서도 걸린다.
     *
     * <p>결과는 <b>멱등</b> 하다. 저장된 값에 이 함수를 다시 걸어도 값이 바뀌지 않아야 재정규화 배치가 태그를 옮기지 않는다.
     *
     * <p><b>빈 값 검사는 호출자 몫이다.</b> 공백만 있는 입력은 여기서 빈 문자열이 되고, 그 판정은 호출자가 이미 가진 빈 값 경로가 한다 — 순서가 「정규화 → 빈 값 검사」 여야 공백만 있는
     * 입력과 처음부터 빈 입력이 같은 곳에서 걸린다. 널도 같은 이유로 빈 문자열로 접는다. DB 쪽은 {@code ck_tag_match_value_no_whitespace} 가 같이 막는다.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return Normalizer.normalize(WHITESPACE.matcher(folded).replaceAll(""), Normalizer.Form.NFKC);
    }
}
