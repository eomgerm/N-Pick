package com.npick.pipeline.application.port;

/**
 * 워커가 보낸 태그 후보의 유형·표기를 {@code tag.match_value} 로 바꾼다 (계약 §4.3.3).
 *
 * <p><b>이 포트가 있는 이유는 모듈 고리다.</b> 정규화 규칙은 {@code tag} 모듈에 하나만 있어야 하는데({@code TagMatchValue}), {@code pipeline} 이 그것을 직접
 * 부르면 {@code clip → pipeline → tag → clip} 순환이 닫힌다({@code ModuleBoundaryArchitectureTest}). 인터페이스를 이쪽에 두고 구현을
 * {@code tag} 에 두면 컴파일 의존이 뒤집혀 고리가 끊긴다.
 *
 * <p>쓰기는 하지 않는다. 검증을 저장 앞에서 끝내야 뒤 장면의 위반이 앞 장면의 태그를 남기지 않는다.
 */
public interface TagVocabularyPort {

    /** @return 저장할 {@code match_value}. 유형이 어휘 밖이거나, 날짜 유형이거나, 정규화 결과가 비었거나 컬럼 폭을 넘으면 {@code null}. */
    String matchValue(String type, String value);
}
