package com.npick.pipeline.application.port;

/**
 * 배정된 run 이 가리키는 clip 의 입력 미디어를 찾는다.
 *
 * <p><b>이 포트가 있는 이유는 모듈 고리다.</b> 미디어 조회는 {@code clip} 의 책임인데 {@code pipeline} 이 그 UseCase 를 직접 부르면 {@code clip →
 * pipeline → clip} 순환이 닫힌다({@code ModuleBoundaryArchitectureTest}). 인터페이스를 부르는 쪽에 두고 구현을 {@code clip} 에 두면 컴파일 의존이 뒤집혀
 * 고리가 끊긴다 — {@link TagVocabularyPort} 와 같은 방식이다.
 */
public interface ClipMediaInputPort {

    /** @return 배정·산출물 접근에 쓸 저장 키와 크기. */
    MediaInput get(long clipId);

    record MediaInput(String storageKey, long sizeBytes) {}
}
