package com.npick.search.application.query.candidate;

import java.util.List;

/**
 * 단어 검색으로 후보 장면을 찾는 계약 (FR-SRH-001, FRD F-05·§11).
 *
 * <p>검색 순위의 기본 축이다. 텍스트 의미 검색(-53)과 구조화 축 점수(-52)는 여기서 나온 후보 위에 결합된다.
 *
 * <p>Projection 조회이므로 Domain Repository 가 아니라 application 이 소유하는 QueryPort 다 (설계 정본 §9). Aggregate 를 복원하지 않고 순위가 붙은 후보
 * 목록만 돌려준다.
 *
 * <p>입력이 원문 질의가 아니라 <b>토큰 목록</b> 인 것이 핵심이다. 색인은 워커가 Kiwi 로 만든 토큰을 {@code pdb.whitespace} 로 자른 것이라, 질의도 같은 Kiwi 설정에서 나온
 * 토큰이어야 한다. 다르면 오류 없이 검색이 0건이 된다 ({@code docs/architecture/02-container.md}).
 *
 * <p>질의 리졸버(-45)에 의존하지 않는다. 리졸버가 실패해도 raw 질의 토큰으로 이 채널만 돌리는 것이 정해진 축소 동작이기 때문이다 (PRD "resolver 실패 시 raw query BM25
 * fallback").
 *
 * <h2>확장어를 어떻게 받는가 (S15P21A501-48 계약)</h2>
 *
 * 리졸버의 {@code expanded_terms} 는 <b>동의어 검색의 유일한 경로다.</b> F-04 가 「동의어·별칭은 확장하지 않는다. 그 문제는 F-05 의 검색어 해석이 담당한다」 로 위임했고
 * §1.2 가 전역 동의어 화면과 별도 LLM 재작성을 범위 밖으로 닫았다. 아직 이 포트에 연결돼 있지 않으며 배선은 조립(S15P21A501-59) 소관이다. 연결할 때 지킬 것은 아래와 같다.
 *
 * <ul>
 *   <li><b>원 질의 토큰과 분리해 받고 별도 가중 절로 건다.</b> {@code paradedb.boolean(should =&gt; …)} 은 맞은 절의 점수를 더하므로, 한 목록으로 합치면 확장어가
 *       원 질의어와 같은 가중으로 경쟁해 확장어만 맞은 장면이 질의어가 맞은 장면을 앞지를 수 있다
 *   <li><b>원 질의 토큰과 겹치는 확장어 토큰은 빼고 넘긴다.</b> 겹친 토큰은 두 절에서 각각 가산된다 (F-05 「같은 개체를 중복 계산하지 않는다」). 확장어 목록 자체의 중복도 뺀다 —
 *       {@code validator.py} 는 이 축을 중복 제거하지 않는다
 *   <li><b>토큰은 원 질의와 같은 Kiwi 설정·전처리에서 나와야 한다.</b> 교정({@code patch_parse})으로 추가된 확장어는 리졸버가 모르는 값이라 <b>규칙 적용 뒤</b> 토큰화해야
 *       하는데 백엔드에는 Kiwi 가 없다. 그 창구가 워커의 {@code POST /query/tokenize} 다 (S15P21A501-205). 계약은
 *       {@code docs/contracts/resolver-api.md} 이고, 색인 측 {@code ocr} 단계와 같은 함수를 쓰므로 토큰 경계가 어긋나지 않는다
 *   <li><b>가중치는 인자가 아니라 실행 설정이다.</b> {@code SceneCandidateProperties} 에 두고 <b>{@code LexicalSearchSettings} 와
 *       {@code SearchConfigSnapshot.lexicalPayload()} 에도 함께 더한다.</b> 그 payload 는 필드를 하나씩 열거하므로 properties 에만 넣으면 순위를
 *       바꾸는 값이 {@code config_version} 에 실리지 않아, 가중치가 다른 두 실행이 같은 버전으로 보인다. 값은 실측 전까지 정하지 않는다 (FRD §11.1)
 *   <li><b>확장어가 없거나 토큰화가 실패해도 degraded 가 아니다.</b> 확장어 없이 이어간다 — 응답 계약의 {@code degraded_reasons} 어휘가
 *       {@code resolver_fallback}·{@code dense_unavailable}·{@code snapshot_save_failed} 로 닫혀 있다
 *   <li><b>dense 채널은 확장어의 영향을 받지 않는다.</b> 질의 임베딩은 원문 기준이다 ({@code embed_query(raw_query)})
 *   <li>확장어에서 유래한 매칭은 사용자가 명시한 조건과 <b>구분해 표시</b>한다 (F-05·F-07). {@code matched_keywords} 는 항목마다 {@code origin}
 *       ({@code user} · {@code expanded} · {@code unknown}) 을 싣는다 (S15P21A501-234). 응답과 {@code explain_json} 이 같은 구조다
 * </ul>
 */
public interface FindSceneCandidatesQueryPort {

    /**
     * 후보 장면을 점수 높은 순으로 찾는다.
     *
     * <p>후보 개수 상한과 필드 가중치는 인자가 아니라 실행 설정이다. 호출자가 실행마다 다른 값을 넣으면 {@code search_execution.config_version} 으로 실행을 구분할 수 없게
     * 된다 (F-05 완료 기준).
     *
     * @param searchTokens Kiwi 토큰. 비어 있으면 질의하지 않고 빈 목록을 돌려준다
     * @return 점수 내림차순, 동점이면 {@code sceneId} 오름차순. 매칭이 없으면 빈 목록
     */
    /**
     * @param searchTokens 원 질의 토큰
     * @param expandedTokens 규칙 적용 후 확장어의 토큰. <b>원 질의 토큰과 겹치는 것은 호출부가 이미 뺐다</b> — 겹친 토큰은 두 절에서 각각 가산되어 F-05 의 「같은 개체를 중복
     *     계산하지 않는다」를 깬다. 확장어가 없거나 토큰화에 실패했으면 빈 목록이다
     */
    List<SceneCandidateResult> findByWords(List<String> searchTokens, List<String> expandedTokens);
}
