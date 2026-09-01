# NewsCut-PRD-v5

# NewsCut 제품 요구사항 정의서 (PRD)

> 버전: **5.0**
> 
> 
> 작성일: **2026-09-01**
> 
> 상태: **Final — FRD v2.2 정합·Gate A 마감 기준본**
> 
> 개발 조건: **6인 팀, 잔여 3주, 로컬 시연용 P0**
> 
> 제품 성격: **검색 시점 해석과 reactive override를 사용하는 뉴스 장면 검색 보조 시스템**
> 

---

## 목차

- [0. 문서 관리](about:blank#0-%EB%AC%B8%EC%84%9C-%EA%B4%80%EB%A6%AC)
- [1. v4 대비 핵심 변경](about:blank#1-v4-%EB%8C%80%EB%B9%84-%ED%95%B5%EC%8B%AC-%EB%B3%80%EA%B2%BD)
- [2. 문제와 근거](about:blank#2-%EB%AC%B8%EC%A0%9C%EC%99%80-%EA%B7%BC%EA%B1%B0)
- [3. 제품 정의와 가치](about:blank#3-%EC%A0%9C%ED%92%88-%EC%A0%95%EC%9D%98%EC%99%80-%EA%B0%80%EC%B9%98)
- [4. 사용자와 역할](about:blank#4-%EC%82%AC%EC%9A%A9%EC%9E%90%EC%99%80-%EC%97%AD%ED%95%A0)
- [5. 목표·가설·주장 경계](about:blank#5-%EB%AA%A9%ED%91%9C%EA%B0%80%EC%84%A4%EC%A3%BC%EC%9E%A5-%EA%B2%BD%EA%B3%84)
- [6. 범위](about:blank#6-%EB%B2%94%EC%9C%84)
- [7. 핵심 제품 정책](about:blank#7-%ED%95%B5%EC%8B%AC-%EC%A0%9C%ED%92%88-%EC%A0%95%EC%B1%85)
- [8. 핵심 사용자 흐름](about:blank#8-%ED%95%B5%EC%8B%AC-%EC%82%AC%EC%9A%A9%EC%9E%90-%ED%9D%90%EB%A6%84)
- [9. 화면과 경험 요구사항](about:blank#9-%ED%99%94%EB%A9%B4%EA%B3%BC-%EA%B2%BD%ED%97%98-%EC%9A%94%EA%B5%AC%EC%82%AC%ED%95%AD)
- [10. 데이터·코퍼스·저장 원칙](about:blank#10-%EB%8D%B0%EC%9D%B4%ED%84%B0%EC%BD%94%ED%8D%BC%EC%8A%A4%EC%A0%80%EC%9E%A5-%EC%9B%90%EC%B9%99)
- [11. 평가와 성공 기준](about:blank#11-%ED%8F%89%EA%B0%80%EC%99%80-%EC%84%B1%EA%B3%B5-%EA%B8%B0%EC%A4%80)
- [12. 비기능·보안·저작권](about:blank#12-%EB%B9%84%EA%B8%B0%EB%8A%A5%EB%B3%B4%EC%95%88%EC%A0%80%EC%9E%91%EA%B6%8C)
- [13. 시연 범위](about:blank#13-%EC%8B%9C%EC%97%B0-%EB%B2%94%EC%9C%84)
- [14. 개발 계획과 역할](about:blank#14-%EA%B0%9C%EB%B0%9C-%EA%B3%84%ED%9A%8D%EA%B3%BC-%EC%97%AD%ED%95%A0)
- [15. Gate와 변경 통제](about:blank#15-gate%EC%99%80-%EB%B3%80%EA%B2%BD-%ED%86%B5%EC%A0%9C)
- [16. 위험과 알려진 한계](about:blank#16-%EC%9C%84%ED%97%98%EA%B3%BC-%EC%95%8C%EB%A0%A4%EC%A7%84-%ED%95%9C%EA%B3%84)
- [17. 발표 메시지](about:blank#17-%EB%B0%9C%ED%91%9C-%EB%A9%94%EC%8B%9C%EC%A7%80)
- [18. 용어](about:blank#18-%EC%9A%A9%EC%96%B4)
- [19. 확정 결정 요약](about:blank#19-%ED%99%95%EC%A0%95-%EA%B2%B0%EC%A0%95-%EC%9A%94%EC%95%BD)

---

## 0. 문서 관리

### 0.1 목적

이 문서는 NewsCut P0에서 **무엇을, 왜, 어디까지 만드는지**를 제품 관점에서 정의한다.

- PRD v5는 문제, 사용자, 가치, 범위, 제품 정책, 성공 기준을 고정한다.
- FRD v2.2는 기능, 상태, 데이터 무결성, API, UI 예외, 실패 처리와 수용 시험을 구현 가능한 수준으로 정의한다.
- 모델, 프롬프트, 임계값, 검색 가중치와 실험 결과는 Gate B/C 및 별도 기술·평가 기록에서 관리한다.
- 본 문서는 확정 설계를 제품 언어로 정렬한 기준본이며, 새로운 기능 범위를 추가하지 않는다.

### 0.2 기준 문서와 관계

PRD v5와 FRD v2.2는 동일한 확정 설계의 제품 기준과 구현 기준이다.

| 구분 | 기준 |
| --- | --- |
| 제품 목적·사용자 가치·범위·성공 기준 | PRD v5 |
| 기능 동작·상태·API·DB·오류·수용 기준 | FRD v2.2 |
| 제품 범위에 영향을 주는 후속 변경 | Gate A 재개 후 PRD·FRD 함께 버전 증가 |
| 수치·모델·설정의 근거 기반 동결 | Gate B/C 또는 범위 내 versioned config |

두 문서가 충돌하는 것으로 보이면 임의로 섞거나 한쪽을 조용히 덮어쓰지 않는다. 확정 결정과 변경 통제 절차를 확인한 뒤 두 문서를 함께 갱신한다.

### 0.3 대체 범위

- 본 문서는 NewsCut-PRD-v4.md를 현재 제품 기준으로 대체한다.
- PRD v4는 변경 이력으로 보존하며 삭제하거나 덮어쓰지 않는다.
- FRD v2.2는 본 문서 작성 과정에서 범위를 추가하지 않았으므로 현재 구현 기준으로 유지한다.

### 0.4 문장의 의미

- **P0**는 6인·3주·로컬 시연에서 반드시 구현하고 평가할 범위다.
- **P1**은 별도 설계와 승인 뒤 진행할 후속 범위이며 P0 완료 판정에 포함하지 않는다.
- **확정**은 Gate A에서 결정이 끝난 제품 계약이다.
- **Gate B/C**는 데이터와 평가 계획이 있어야 정할 수 있어 현재 숫자를 창작하지 않는 항목이다.
- **Gate D**는 제품 범위를 바꾸지 않는 구현 기본값이다.

---

## 1. v4 대비 핵심 변경

PRD v5는 v4의 태깅·사전검수 중심 제품을 그대로 확장하지 않는다. 확정 설계와 FRD v2.2에 맞춰 제품의 중심과 P0 범위를 다음과 같이 바꾼다.

| 영역 | PRD v4 | PRD v5 확정 |
| --- | --- | --- |
| 제품 본체 | 장면 메타데이터 초안과 고위험 태그 사전검수 | 장면 검색, 근거·불확실성 표시, reactive 검수 |
| 최상위 성공 | 태깅·사건 후보 정확도와 검색 개선 | usable scene 발견 성공률·만족도 향상, 편집자+검수자 총업무량 유지·감소 |
| 사건 | Event Type/Event/NO_EVENT, 후보 Top 3, assignment | Event 정본 제거. 사건명은 검색 해석과 source-backed 문자열 evidence로만 사용 |
| 검수 시점 | 검색 전 고위험 필드 검수·승인 | 사전검수 없음. 검색 중 발견된 문제에만 반응 |
| 필드 상태 | 장면 승인·보류와 review_required | evidence별 verified/unverified/rejected |
| 피드백 | 긍정·부정 분리, 사유별 교정 요청 | 결과별 이상해요 단일 문의, 설명은 선택 |
| 문의 접수 효과 | 현재 검색 세션에서 결과를 즉시 숨기거나 후순위 처리 | 접수 자체는 현재 결과·metadata·index·ranking을 변경하지 않음. replay 검증 후 활성화된 exact override만 같은 scope에 적용 |
| P0 조치 | 피처 수정, 승인, 재색인 | normalization version·정규화 query·명시 filter가 같은 exact scope에서 resolution patch와 scene 제외만 query-time에 적용하며 metadata와 index는 변경하지 않음 |
| 피처 오류 | P0에서 DB·인덱스 교정 | P1로 이관하고 P0 정본·인덱스는 변경하지 않음 |
| 질의 처리 | 구조화 파싱 후 결과 부족 시 별도 LLM 재작성 | 검색마다 임시 Query Resolution 생성, 실패 시 raw BM25 fallback |
| 검색 기록 | 재사용 가능한 해석이 불명확 | 실행별 immutable snapshot. 감사·재현용이며 cache로 재사용하지 않음 |
| hard 제외 | event/entity/date 구조화 gate | 명시 anchor와 검증된 동일 필드 충돌에만 제한적으로 적용 |
| 날짜 | 하나의 date 중심 | 방송일과 촬영일을 독립 nullable 필드로 보존 |
| transcript | 기존 자료 우선, 없으면 ASR | timestamp 제공본 → CC → ASR 우선순위를 명시 |
| 저장소 | Event·metadata를 DB 단일 원본으로 두었으나 DBMS는 미확정 | PostgreSQL+JSONB 정본, 검색 인덱스는 정본과 검증된 asset manifest에서 재구축 가능한 파생물 |
| 재처리 | 단일 processing 상태와 재시도 중심 | 기존 활성 version으로 검색을 계속하고 새 처리·index 검증 후 전환. 과거 scene 제외는 새 장면에 이전하지 않고 stale 처리 |
| 평가 | Event [Recall@3](mailto:Recall@3), Event Top-1, RR | [Recall@10](mailto:Recall@10), nDCG/MRR, false-hit, coverage, resolver/fallback, replay, 업무효율 |
| 문의 처리 성능 | 승인 후 교정 재색인 10초 목표 | P0 교정 재색인 SLA 제거. 문의 저장과 reviewer replay의 p50·p95를 각각 측정 |
| 외부 처리 | 원본 비공개 중심 | 권리·provider 조건·최소 전송·fail-closed를 포함한 Gate S 정책 |
| 시연 | 사건 후보 승인과 피처 교정·재색인 | 검색→문의→exact override→같은 exact query scope replay |

이번 변경으로 Event 관련 데이터·화면·API·지표, 사전 승인 gate, P0 피처 교정과 교정 재색인은 모두 제거된다. 단, 자연어 검색에 포함된 사건명을 해석하고 source-backed 근거와 비교하는 기능은 유지한다.

---

## 2. 문제와 근거

### 2.1 해결하려는 문제

방송사 아카이브 검색은 제목, 수기 키워드, transcript 등 텍스트 메타데이터에 크게 의존한다. 그 결과 다음 문제가 발생한다.

- 동일하거나 유사한 이름의 기관·인물이 함께 검색된다.
- 같은 유형의 뉴스가 다른 연도와 시점에 반복되어 혼동된다.
- 화면에 보이는 건물, 정체, 인파, 현판이 제목이나 transcript에 없으면 찾기 어렵다.
- 한 뉴스 클립 안에 앵커, 인터뷰, B-roll이 섞여 있어 필요한 장면을 찾기 위해 처음부터 재생해야 한다.
- 오래된 시설 명패나 다른 계절의 장면을 현재 자료로 잘못 사용할 위험이 있다.
- 원고가 수정될 때 검색과 영상 확인을 반복해야 한다.
- 자동 추출값이 없거나 불확실하다는 이유만으로 유용한 장면을 버리면 검색 누락이 커진다.

핵심 문제는 단순히 검색창의 기능 부족이 아니다. **영상 속 몇 초짜리 장면을 찾을 수 있는 근거가 부족하고, 검색 결과의 불확실성과 명백한 충돌을 안전하게 다루는 흐름이 없다는 점**이다.

### 2.2 현재 확인한 근거

- KBS 내부자 공저 연구는 방송 아카이브 검색이 수기 키워드에 의존하고 명칭 불일치로 오매칭이 발생한다고 보고했다.
- 현직 편집기자 인터뷰에서 검색이 컷편집보다 오래 걸리고 여러 키워드 조합 검색이 실질적으로 어렵다는 점을 확인했다.
- 공기관 외경은 최신성과 계절이 중요하며 오래된 명패가 포함된 화면을 잘못 사용한 사례가 확인됐다.
- 코퍼스에서 유사 명칭 조직 513쌍과 같은 장소의 다시점 영상이 확인됐다.
- 화면 내용이 transcript에 없는 실제 사례가 존재한다.
- 표본 실측에서 25개 클립 중 24개가 다중 장면이었고, 클립당 평균 6.7장면, 장면당 평균 약 4초였다.

현재 근거의 추적 시작점은 NewsCut-PRD-v4.md §4, NewsCut-국내-유사사례와-확장고객-리서치.md, NewsCut-확장-타겟-사용자-리서치.md다. 평가·발표 전에는 각 수치의 원문, 인터뷰 기록, 표본 manifest와 계산식을 evidence register에 연결한다.

### 2.3 추가로 검증할 사업 근거

다음은 P0 기능 결정을 막는 미결 사항이 아니라 제품 가치와 후속 우선순위를 검증하기 위한 인터뷰 항목이다.

- 기방영 뉴스 영상의 실제 재사용 비율
- 영상 한 건을 찾는 평균 시간
- 하루 또는 기사 한 건당 검색 반복 횟수
- 방송본과 촬영원본의 실제 사용 비중
- 문의 한 건을 검수하고 replay하는 평균 시간

---

## 3. 제품 정의와 가치

### 3.1 한 문장 정의

> NewsCut은 기방영 뉴스 영상을 장면 단위로 검색할 수 있게 만들고, 검색 결과의 근거와 불확실성을 보여 주며, 검수자가 P0 조치 대상으로 확정한 질의 해석 오류 또는 특정 장면 부적합이 같은 exact query scope에서 반복되지 않도록 reactive override를 제공하는 로컬 중심 아카이빙 검색 보조 시스템이다.
> 

### 3.2 사용자가 기억해야 할 가치

> 기존 검색은 뉴스 영상 파일을 찾지만, 편집기자에게 필요한 것은 영상 속 몇 초짜리 장면이다.
> 

### 3.3 제품의 중심

NewsCut P0의 중심은 다음 네 가지다.

1. 영상 파일을 장면 단위로 나누고 화면·텍스트 근거를 검색 가능하게 만든다.
2. 검색 시점에 사용자의 질의를 임시 구조화하고 결과가 나온 이유를 보여 준다.
3. 정보 부족은 숨기지 않고 남기되, 명시 조건과 검증된 사실이 충돌하는 결과만 보수적으로 제한한다.
4. 검색 중 발견된 문제를 원클릭 문의로 연결하고, P0 조치 대상인 질의 해석 오류 또는 특정 장면 부적합만 같은 exact query scope에서 반복되지 않게 한다.

### 3.4 차별점

새로운 검색 알고리즘이나 독자 모델을 차별점으로 주장하지 않는다. BM25, dense embedding, RRF, ASR, VLM은 제품을 구성하는 표준 기술이다.

제품 차별점은 다음과 같다.

- **장면 단위 탐색:** 파일이 아니라 정확한 장면 구간과 Preview로 이동한다.
- **근거가 보이는 검색:** 화면 설명, OCR, transcript, 날짜, source, confidence와 검증 상태를 결과와 연결한다.
- **불확실성을 보존하는 안전장치:** 정보 없음과 미검증은 충돌로 간주하지 않으며, 검증된 명시 충돌만 제한한다.
- **낮은 추가 업무의 reactive 검수:** 전수 사전검수 대신 실제 검색에서 발견된 문제만 처리한다.
- **범위가 명확한 재발 방지:** 사람의 결정은 같은 정규화 query와 명시 filter에서만 우선 적용되며 다른 검색으로 자동 일반화하지 않는다.

### 3.5 제품이 아닌 것

NewsCut P0는 다음 제품이 아니다.

- Event catalog 또는 사건 관리 시스템
- 모든 장면과 태그를 사람이 승인하는 사전검수 시스템
- 메타데이터를 완벽하게 만드는 정본 교정 시스템
- 문의를 학습 데이터로 자동 반영하는 개인화·자동학습 시스템
- 송출 적합성, 사실성, 최신성, 저작권을 보증하는 시스템
- 운영급 MAM, 다중 사용자 서비스 또는 공개 배포 제품

---

## 4. 사용자와 역할

### 4.1 편집기자

편집기자는 NewsCut의 검색 결과를 사용하는 주 사용자다.

주요 행동:

1. 한국어 검색어나 원고의 핵심 문장을 입력한다.
2. 필요한 경우 방송일·촬영일 등 명시 filter를 선택한다.
3. 결과 카드에서 두 날짜, 일치 근거, 출처, 미검증·degraded 상태를 확인한다.
4. 해당 장면의 정확한 타임코드부터 Preview한다.
5. 원하지 않은 결과에는 이상해요 버튼으로 문의한다.
6. 송출 전 사실, 최신성, 권리와 사용 적합성을 최종 확인한다.

편집기자는 P0에서 metadata나 override를 직접 수정·활성화·해제하지 않는다.

### 4.2 검수자·아카이빙 담당자

검수자는 영상 등록과 검색 후 문의 처리를 담당한다. 별도 직군을 전제하지 않으며 기자가 이 역할을 겸할 수 있다.

주요 행동:

1. 영상과 기본 정보를 등록하고 처리 상태를 확인한다.
2. 검색 후 접수된 문의의 자동 첨부 context와 근거를 확인한다.
3. 질의 해석 오류에는 exact resolution patch를, 특정 장면의 부적합에는 exact scene 제외를 검토한다.
4. 같은 exact query scope로 replay해 후보 조치가 문제를 해결하는지 확인한다.
5. replay에 성공한 override만 활성화하고 버전 갱신·해제 이력을 관리한다.
6. 피처 자체 오류는 P1 대상으로 이관한다.

검수자는 P0에서 모든 장면이나 고위험 태그를 검색 전에 승인하지 않는다.

### 4.3 역할과 접근 경계

- P0는 최신 Chrome의 로컬 서비스이며 고정된 데모 사용자 식별을 사용할 수 있다.
- 검색 화면과 검수 화면은 역할을 구분한다.
- 서버는 편집기자와 검수자의 허용 기능을 구분해야 한다.
- 고정 데모 role은 운영급 인증·인가를 뜻하지 않는다.
- LAN·공개 서버·다중 사용자 운영 전에는 실제 인증과 권한 체계가 별도로 필요하다.

---

## 5. 목표·가설·주장 경계

### 5.1 최상위 제품 목표

> 편집기자가 사용할 장면을 찾는 성공률과 만족도를 높이되, 편집기자와 검수자의 합산 업무량을 늘리지 않고 명백한 오사용 가능성을 보수적으로 제한한다.
> 

### 5.2 보조 목표

1. transcript 중심 검색에서 찾기 어려운 화면 장면을 검색 가능하게 한다.
2. 검색 결과가 나온 이유와 날짜·출처·근거를 확인할 수 있게 한다.
3. 정보가 없거나 불확실한 장면을 자동으로 버리지 않고 상태를 표시한다.
4. 일부 AI 구성요소가 실패해도 허용된 fallback으로 검색을 지속한다.
5. 문의와 사람의 결정이 원천 데이터나 전체 검색을 조용히 오염시키지 않게 한다.
6. 동일한 exact query scope에서 확인된 질의 해석 오류와 특정 장면 부적합의 반복을 막는다.

### 5.3 핵심 가설

> 장면 분할과 구조화된 화면 설명·OCR·transcript 근거를 결합하고, 검색 시점의 임시 질의 해석과 보수적 충돌 제한을 적용하면, 현행 수기 keyword 검색보다 usable scene 발견 성공률과 만족도를 높일 수 있다.
> 

> 전수 사전검수 대신 실제 검색에서 발견되어 P0 조치 대상으로 확정된 질의 해석 오류와 특정 장면 부적합만 exact override로 처리하면, 편집기자와 검수자의 합산 업무량을 늘리지 않으면서 해당 exact scope의 반복 오류를 줄일 수 있다.
> 

### 5.4 허용하는 주장

- 현행 수기 keyword baseline보다 [Recall@10](mailto:Recall@10) 또는 paired usable scene 발견 성공률이 개선됐다.
- 명시 anchor와 검증된 동일 필드 충돌을 보수적으로 제한한다.
- 결과의 source, evidence와 불확실성을 표시한다.
- 검수자가 P0 조치 대상으로 확정한 질의 해석 오류 또는 특정 장면 부적합은 같은 exact query scope에서 반복되지 않는다.
- resolver 또는 dense가 실패해도 허용된 degraded 검색 경로를 제공한다.
- 구성 요소를 자체 호스팅 adapter로 교체할 수 있는 구조다.

위 주장은 실제 측정 또는 시연 근거가 있을 때만 사용한다.
개별 지표의 개선은 해당 지표에 한해 주장하며, 하나의 지표만으로 §11.1의 제품 목표 달성을 주장하지 않는다.

### 5.5 금지하는 주장

- 완벽한 메타데이터 또는 무오류 검색을 제공한다.
- 의미검색보다 항상 정확하다.
- 문의만으로 모델이 학습하거나 전체 검색이 자동으로 좋아진다.
- 유사 질의에도 사람의 결정이 자동으로 일반화된다.
- 송출 적합성, 최신성, 사실성 또는 저작권을 보증한다.
- 명절 교통축 결과가 모든 뉴스 주제에 그대로 적용된다.
- GMS 구성에서 데이터가 외부로 전혀 전송되지 않는다.
- adapter 교체만으로 자체 호스팅 성능이 동일하게 보장된다.

---

## 6. 범위

### 6.1 P0 환경

- 입력 데이터: 기방영 뉴스 클립
- 사용 환경: 최신 Chrome 데스크톱, 로컬 서비스
- 동시 사용자: 1명
- 입력·출력 언어: 한국어
- 영상 분석: 오프라인 또는 비동기 처리
- 검색 대상: 처리가 검증돼 검색 가능한 장면
- 평가 코퍼스 기본안: 명절 교통축 183클립 + distractor 150클립 = 333클립, 약 2,200장면

### 6.2 P0 포함 범위

#### 영상 등록

- 기방영 뉴스 영상 파일 등록
- 방송일과 촬영일을 서로 다른 nullable 값으로 입력
- 두 날짜가 모두 없어도 등록 가능
- 사용권과 media clip별 외부 처리 허용 여부 기록
- timestamp transcript, CC, 일반 대본의 선택적 입력
- 처리 상태와 검색 가능 상태 확인

P0 UI의 실제 분석 대상은 방송분이다. 촬영원본과 제보 영상은 이번 품질 보장과 평가에 포함하지 않는다.

#### 장면 처리

- 장면 분할
- 장면별 start·end 타임코드
- 장면별 다중 keyframe과 대표 thumbnail
- 처리·모델·schema version과 근거 연결
- 최초 처리와 재처리 상태 구분

#### 화면·텍스트 추출

- VLM 기반 구조화 화면 설명
- shot type과 scene type의 닫힌 어휘, unknown/null 허용
- 필드별 confidence와 근거 frame
- OCR 원문, confidence와 근거 frame
- timestamp transcript와 CC의 시간 구간 보존
- timestamp transcript와 유효한 CC가 없을 때 ASR 자동 fallback
- typed entity·tag 후보와 source
- verified/unverified/rejected 상태

season, weather, crowd density 등 soft field는 nullable로 저장할 수 있으나 P0 필수 검색·성공 지표에는 포함하지 않는다.

#### 검색

- 한국어 query와 명시 filter 입력
- 검색 실행마다 임시 Query Resolution 생성
- BM25 lexical backbone
- dense text 검색과 구조화 soft score 보조
- RRF 기반 결과 융합
- resolver 실패 시 raw query BM25 fallback
- 명시 anchor와 verified same-field conflict에 대한 보수적 false-hit guard
- guard 적용 뒤 가능한 후보를 보충해 최대 10개 반환. 유효 후보가 부족하면 실제 개수와 이유 표시
- query resolution과 guard 전후 결과 snapshot

#### 결과와 Preview

- 장면 thumbnail
- 방송일과 촬영일
- shot type
- 일치한 화면 설명·OCR·transcript
- evidence source, confidence와 검증 상태
- 미검증·degraded·사람 override 적용 여부 표시
- 장면 start·end
- 원본 영상의 해당 타임코드부터 Preview

#### 문의와 reactive 검수

- 결과별 이상해요 단일 버튼
- 서술식 문의는 선택
- 당시 query, 명시 filter, resolution, 순위, 근거, guard, 결과와 version context 자동 첨부
- 내부 문의 queue
- exact resolution patch
- exact scene 제외
- 같은 exact query scope replay
- 성공한 후보만 활성화하는 이력 관리
- 피처 자체 오류의 P1 이관

#### 평가

- 장면 metadata Gold Set
- 검색 relevance와 explicit anchor Gold Set
- Query Resolver와 ASR 평가
- M0 manual baseline과 단계별 ablation
- reactive override replay fixture
- paired usable scene·만족도·총업무시간 측정

### 6.3 P1

P1 우선순위는 다음과 같다.

1. 문의로 발견한 Entity·OCR·Caption·Date 등의 versioned field correction과 대상 재색인
2. semantic cache와 명시적 invalidation
3. 유사 질의 범위로 override 확장
4. 충분한 데이터, 권한과 별도 승인 후 학습·개인화·검색 가중치 자동 조정

피처 교정은 시간이 남으면 넣는 P0가 아니다. 별도 schema, 권한, 적용 이력, 재색인, 실패 복구와 수용 기준을 승인한 뒤 시작한다.

### 6.4 P0 제외

- Event catalog, Event Type, Event alias, Event fingerprint
- 사건 후보 Top 3, clip-event assignment, confirmed event gate
- Event [Recall@3](mailto:Recall@3), Event Top-1, Event Graph
- scene·clip 전수 사전검수와 검색 전 승인 gate
- review_required와 scene 전체 review status
- Entity·OCR·Caption·Date 등 피처의 P0 field correction과 교정 재색인
- 문의에 의한 현재 결과 즉시 숨김, 자동학습, 개인화, 전역 가중치 변경
- semantic cache와 유사 질의 override
- resolver 밖의 별도 결과 부족 LLM 질의 재작성
- 화자 분리와 화자 신원 판별
- ASR 학습·파인튜닝
- YOLO 객체 탐지, camera work 검출, frame image embedding 검색
- 가편집, 원고 전체 B-roll 자동 배치
- Premiere·OTIO·FCP XML 내보내기
- 촬영원본·제보 영상 품질 보장
- MAM 실연동
- 공개 서버, 모바일, 다중 사용자 운영, 운영급 로그인·회원관리

### 6.5 장기 확장 방향

현재 구조는 source type과 transcript fallback을 분리하고, 정본과 검색 인덱스를 분리한다. 이를 바탕으로 향후 촬영원본·제보 영상, 피처 교정, semantic cache와 MAM 연동을 검토할 수 있다. 각 확장은 데이터 특성과 권리, 성능과 운영 책임을 확인한 뒤 별도 범위로 승인한다.

---

## 7. 핵심 제품 정책

### 7.1 원천·파생값·검색 해석·사람 결정의 분리

NewsCut은 다음 네 계층을 구분한다.

| 계층 | 예시 | 제품 원칙 |
| --- | --- | --- |
| 원천·근거 | 사용자 입력, 원본 metadata, CC, OCR 원문 | 덮어쓰지 않고 보존 |
| 파생 피처 | ASR, 정규화 OCR, tag, embedding, VLM metadata | 생성 version과 근거 보존 |
| 검색 해석 | Query Resolution | 검색 실행별 임시 값이며 사실 정본으로 승격 금지 |
| 사람 결정 | resolution patch, scene 제외 | exact scope에서만 우선하며 이력 보존 |

snapshot에서 읽었거나 LLM이 생성했다는 사실만으로 값이 검증된 것은 아니다.

### 7.2 필드 검증 3상태

P0는 장면 전체의 승인 여부로 검색을 막지 않는다. 각 evidence 또는 실제 검색에 쓰이는 필드는 다음 상태를 가진다.

| 상태 | 의미 | 검색 처리 |
| --- | --- | --- |
| verified | 허용 source와 필드별 검증 규칙을 충족 | 일치 boost 또는 허용된 conflict 판정에 사용 |
| unverified | 값 없음, confidence 부족, source 불충분 | 제외하지 않고 미검증 표시 |
| rejected | 사용할 수 없는 근거로 판정 | 검색 신호에서 제외하되 원문 근거는 보존 |

정보 없음과 불확실성은 충돌이 아니다.

### 7.3 transcript 우선순위

1. 사용자가 제공한 timestamp transcript
2. 유효한 내장 CC
3. 위 두 source가 없을 때 ASR

timestamp가 있는 segment만 시간 겹침 기준으로 장면과 연결한다. timestamp가 없는 일반 대본은 clip 참고 텍스트이며 모든 장면의 실제 발화처럼 복제하지 않는다. timestamp transcript와 유효한 CC가 없는 데이터가 많을 것으로 예상되므로 ASR 경로는 P0의 실사용 빈도가 높은 fallback으로 준비하되, source 우선순위는 바꾸지 않는다.

### 7.4 두 날짜

- broadcast date는 방송된 날짜이며 nullable이다.
- filming date는 촬영된 날짜이며 nullable이다.
- 두 값은 독립적이며 한 날짜를 다른 날짜 의미로 복사하지 않는다.
- 일반적인 연도·날짜 query는 broadcast date로 해석한다.
- 촬영 또는 현장 촬영이 명시된 날짜는 filming date로 해석한다.
- 최근 영상은 broadcast date 기준의 soft 정렬이다.
- 날짜가 없거나 미검증이면 결과를 유지하고 미상·미검증으로 표시한다.

### 7.5 사건명과 entity의 경계

P0에는 Event 정본이나 clip assignment가 없다.

- 사건명은 query를 임시 해석하는 incident name 또는 source-backed 문자열 evidence로만 사용한다.
- 사건명 evidence는 catalog, alias graph, Event ID 또는 영구 assignment로 승격하지 않는다.
- LLM이 transcript·OCR에서 추측한 사건명만으로 다른 사건을 확정하거나 장면을 hard 제외하지 않는다.
- person, organization, location, facility의 불일치는 P0에서 soft signal이다.
- 사건명 hard conflict는 Gate B에서 승인된 deterministic rule과 source-backed 검증 근거가 있을 때만 허용한다.

### 7.6 Query Resolution

시스템은 검색 실행마다 query를 날짜, 사건명, entity, location, term, intent와 확장 term으로 임시 구조화한다.

- 사용자가 직접 입력한 query와 명시 filter가 최우선이다.
- LLM은 원문에 없는 날짜·사건명을 사용자의 명시 조건으로 만들 수 없다.
- 별도의 결과 부족 LLM 재작성 단계는 두지 않는다. 필요한 확장 term은 Query Resolution 안에서 생성한다.
- resolver가 timeout, schema 오류, rate limit 또는 network 오류로 실패하면 raw query BM25 검색으로 전환하고 degraded 상태를 표시한다.
- 이전 검색의 resolution snapshot을 다음 검색의 해석으로 재사용하지 않는다.

### 7.7 exact query scope

사람의 override가 적용되는 동일 검색은 다음 세 요소가 모두 같은 경우다.

- normalization version
- 정규화된 query
- 정규화된 사용자의 명시 filter

화면 문구가 비슷하거나 의미가 유사하다는 이유만으로 같은 검색으로 보지 않는다. 같은 query라도 filter가 다르면 다른 범위다. fingerprint는 조회를 빠르게 하는 식별 수단이며 그것만으로 범위 동등성을 판정하지 않는다.

### 7.8 immutable snapshot

각 검색 실행의 Query Resolution과 guard 전후 결과는 감사·재현을 위해 불변 기록으로 남긴다.

- snapshot은 cache가 아니다.
- snapshot은 다음 검색의 정답이나 검색 입력이 아니다.
- 문의는 저장된 결과와 snapshot을 기준으로 정확한 당시 context를 첨부한다.
- snapshot 저장에 실패했지만 검색 계산 결과가 있으면 저장 식별자가 없는 ephemeral degraded 결과를 반드시 반환하되, 해당 결과에서는 새 문의와 후속 override 조치를 시작할 수 없다. BM25 또는 index도 사용할 수 없으면 검색 실패로 처리한다.

### 7.9 false-hit guard

각 명시 anchor는 verified match, unknown or unverified, verified conflict 중 하나로 판정한다.

| 조건 | 결과 |
| --- | --- |
| 명시 날짜와 verified 동일 날짜 필드가 일치 | boost |
| 날짜·사건명 정보가 없거나 unverified | 결과 유지 + 미검증 표시 |
| 명시 날짜와 verified 동일 날짜 필드가 충돌 | hard 제외 |
| 명시 사건명과 Gate B 승인 rule 기반 source-backed 사건명이 충돌 | 해당 활성 rule에 따라 hard 제외 |
| 사건명 승인 rule이 없거나 LLM-only 추정 | soft 처리 |
| person·organization·location·facility 불일치 | soft 처리, hard 제외 금지 |

일부 결과를 적게 반환해 false-hit 수치만 낮추지 않도록 guard 뒤에는 결과를 보충하고 coverage를 함께 평가한다.

### 7.10 reactive inquiry와 override

편집기자가 이상해요를 누르면 문의가 생성되지만 접수 자체는 결과, metadata, index 또는 다른 검색의 ranking을 바꾸지 않는다.

문의 상태는 pending에서 시작해 검수자가 맡으면 reviewing이 되며, 검수 결과에 따라 resolved, dismissed 또는 deferred로 종료한다. pending에서 바로 종료 상태로 건너뛰지 않는다.

P0의 실제 pinned override는 두 종류뿐이다.

| 조치 | 의미 | 적용 시점과 범위 |
| --- | --- | --- |
| resolution patch | 해당 exact query scope의 질의 해석 전체를 사람이 고정 | resolver보다 먼저 적용, 사용자의 명시 filter 유지 |
| scene 제외 | 해당 exact query scope에서 특정 scene processing version을 제외 | guard와 ranking 뒤 적용 |

검수자는 reviewing 문의에 연결된 pending_verification 후보 조치를 만들고 같은 exact query scope로 replay한다. replay와 활성화에 성공한 후보만 active가 되며 기존 이력은 보존한다. replay 또는 활성화에 실패하면 문의는 reviewing에 남고 후보는 pending_verification 또는 revoked가 되며 기존 active override는 유지된다. 장면이 재처리되면 과거 scene 제외는 새 장면으로 자동 이전하지 않고 stale 상태가 된다.

피처 자체 오류는 P0에서 값을 고치지 않는다. valid issue로 기록하고 P1 대상으로 이관한다.

### 7.11 재처리와 검색 연속성

- clip의 검색 제공 상태, pipeline 실행 상태와 검색 index generation 상태는 서로 구분한다.
- 최초 clip의 검색 제공 상태는 queued → processing → ready 또는 failed로 진행하며, 검색 가능한 최소 조건과 활성 index가 준비돼야 ready가 된다.
- 이미 ready인 clip을 재처리할 때는 검색 제공 상태를 ready로 유지하고 기존 활성 version을 계속 제공한다. 최신 pipeline 실행의 queued, running, succeeded 또는 failed 상태를 별도로 표시하고 비치명 실패는 경고로 표시한다.
- 새 처리와 index가 검증된 뒤에만 활성 version을 전환한다.
- 재처리 실패는 기존 검색 가능 상태를 제거하지 않고 최신 실행 실패와 경고로 표시한다.
- 한 검색 실행은 시작 시점의 index version을 사용하며 실행 중 서로 다른 version을 섞지 않는다.
- 과거 snapshot과 문의는 불변으로 남고, 새 장면 ID에 과거 제외 결정을 자동 연결하지 않는다.

---

## 8. 핵심 사용자 흐름

### 8.1 영상 등록부터 검색 가능까지

1. 검수자가 방송분 영상, 두 nullable 날짜, 사용권과 선택 자료를 등록한다.
2. 시스템이 비동기로 장면을 나누고 다중 frame과 thumbnail을 만든다.
3. timestamp transcript 또는 CC를 우선 사용하고, 없으면 ASR을 실행한다.
4. VLM, OCR, transcript와 허용된 source에서 장면별 검색 신호를 만든다.
5. 각 값에 source, confidence, evidence와 검증 상태를 연결한다.
6. 검색 가능한 최소 조건을 충족한 장면을 PostgreSQL 정본과 파생 검색 index에 반영한다.
7. 사전 태그 승인 없이 검색 가능 상태가 되며 누락된 비치명 신호는 warning으로 표시한다.

치명적인 입력·장면·Preview·정본·BM25 index 조건을 충족하지 못하면 검색 가능으로 표시하지 않는다.

### 8.2 검색부터 Preview까지

1. 편집기자가 한국어 query와 필요한 명시 filter를 입력한다.
2. 시스템이 exact query scope에 활성 resolution patch가 있는지 확인한다.
3. patch가 없으면 새 Query Resolution을 생성하고, 실패하면 raw BM25 fallback을 사용한다.
4. BM25 후보에 dense와 구조화 soft score를 결합한다.
5. 명시 anchor의 verified conflict와 활성 exact scene 제외를 적용한다.
6. 제외된 자리를 다음 후보로 보충하고 실행별 snapshot을 저장한다.
7. 결과 카드에 근거, 두 날짜, 검증·degraded 상태와 timecode를 보여 준다.
8. 사용자는 원하는 장면을 해당 구간부터 Preview한다.

### 8.3 문의부터 exact 재발 방지까지

1. 편집기자가 원하지 않은 결과의 이상해요 버튼을 누른다.
2. 필요하면 선택적으로 설명을 입력한다.
3. 서버가 당시 query·filter·resolution·순위·근거·guard·결과·version을 자동 첨부한다.
4. 문의는 내부 queue에서 pending으로 접수된다.
5. 검수자가 문의를 맡으면 reviewing으로 전환하고 근거와 문제 유형을 확인한다.
6. 질의 해석 문제면 resolution patch, 특정 결과 문제면 scene 제외의 pending_verification 후보를 만든다.
7. 같은 exact query scope로 replay한다.
8. replay와 활성화가 성공하면 후보를 active로 전환하고 문의를 resolved로 종료한다.
9. 실패하면 문의는 reviewing에 남고 기존 active override는 유지한다.
10. 조치가 필요 없으면 dismissed, 피처 교정이 필요하면 deferred로 종료한다.

문의 화면은 멀티턴 채팅 기능이 아니다. 자동 첨부되는 기록은 해당 검색 실행과 결과, 문의 처리와 replay context다.

---

## 9. 화면과 경험 요구사항

### 9.1 검색 화면

검색 화면은 다음을 지원한다.

- 한국어 query 입력
- 방송일·촬영일 등 명시 filter
- normal, resolution patch, raw fallback 상태 구분
- 최대 10개의 결과 카드와, 유효 후보가 부족할 때 실제 개수·이유 표시
- 검색이 일부 구성요소 실패로 degraded됐다는 명시적 표시
- 결과가 없을 때 실제 0건과 검색 실패를 구분

### 9.2 결과 카드

각 결과 카드에서 사용자는 최소한 다음을 확인할 수 있어야 한다.

- 장면 thumbnail
- clip 식별 정보와 scene timecode
- broadcast date와 filming date의 값 또는 미상
- shot type
- query와 일치한 caption, OCR, transcript 또는 tag
- 해당 값의 source와 검증 상태
- 미검증, degraded, 사람 override 적용 여부
- Preview 진입
- 이상해요 문의
- 송출 전 내용·최신성·권리·사용 적합성을 최종 확인해야 한다는 상시 고지

결과의 사건명은 검증된 정본 Event처럼 표시하지 않고 검색 해석 또는 source-backed evidence임을 구분한다.

### 9.3 Preview

- 선택한 scene start에서 재생한다.
- scene end를 보여 주되 사용자가 계속 재생할 수 있다.
- 장면 구간을 다시 재생할 수 있다.
- Preview 중에도 해당 결과의 출처, 미검증과 degraded 상태를 확인할 수 있게 유지한다.
- Preview에서도 송출 전 내용·최신성·권리·사용 적합성을 최종 확인해야 한다는 고지를 유지한다.
- Preview 실패는 검색 실패나 빈 결과로 숨기지 않고 원인을 알린다.

### 9.4 문의 경험

- 문의 버튼은 결과마다 하나의 이상해요로 통합한다.
- 설명 입력은 선택이며 빈 설명으로도 접수할 수 있다.
- 사용자가 query, 순위, evidence, version을 다시 입력하게 하지 않는다.
- 접수 성공 여부와 현재 상태를 명확히 표시한다.
- snapshot이 저장되지 않은 ephemeral 결과에서는 문의가 불가능한 이유를 표시한다.
- 문의 접수만으로 현재 결과가 사라지거나 다른 사용자의 검색이 바뀐다고 오해하게 만들지 않는다.

### 9.5 검수 화면

검수 화면은 다음 두 목적만 가진다.

1. 영상 처리·검색 가능 상태와 warning 확인
2. 검색 후 접수된 문의의 진단, exact override 후보 작성과 replay

검수 화면을 장면 전수 사전승인 queue나 피처 직접 수정 화면으로 확장하지 않는다.

---

## 10. 데이터·코퍼스·저장 원칙

### 10.1 코퍼스

정량 평가와 시연의 기본 코퍼스는 다음과 같다.

| 구성 | 규모 | 목적 |
| --- | --- | --- |
| 명절 교통축 | 183클립 | 주요 검색·혼동 이웃 평가 |
| distractor | 150클립 | false positive와 ranking 평가 |
| 합계 | 333클립, 약 2,200장면 | P0 정량 평가 |
| 주제 독립 smoke test | 화재·건물·군중 등 20~30클립 | 특정 주제 하드코딩 여부 확인 |

대표 혼동 이웃은 2022 추석과 2022 설날, 2022 추석과 2021 추석이다. smoke test는 범용성 성능을 주장하기 위한 정식 평가가 아니다.

### 10.2 데이터셋 경계

- 기본 데이터셋은 AI Hub 71699 기방영 뉴스 클립이다.
- 현재 구현과 정량 평가는 방송본에 한정한다.
- AI Hub와 KBS 원본 영상, frame, audio를 source repository나 공개 서버에 포함하지 않는다.
- 코드·평가 결과의 공개와 원본·파생 media의 공개를 구분한다.
- 발표·포트폴리오의 thumbnail과 녹화 사용도 실제 이용조건 확인 전 자동 허용으로 간주하지 않는다.

### 10.3 저장 원칙

P0의 정본 저장소는 PostgreSQL이며 구조가 가변적인 snapshot과 model output은 JSONB를 함께 사용한다. 검색 인덱스는 정본이 아니라 PostgreSQL 정본과 검증된 asset manifest에서 재구축할 수 있는 파생 저장소다.

이 선택의 제품상 이유는 다음과 같다.

- clip, scene, evidence, inquiry와 override의 관계와 이력을 일관되게 지킨다.
- 검색 당시의 resolution과 결과를 형태 그대로 보존한다.
- 재처리와 index 재구축 중에도 기존 검색 가능 version을 안전하게 유지한다.
- 문의와 사람의 결정을 정확한 query·filter·scene version에 연결한다.

테이블, column, FK, transaction, index generation과 API 세부 계약은 FRD v2.2를 따른다.

### 10.4 데이터 보존과 삭제

- 원천, 파생 asset, DB, 검색 index, log의 삭제 경계를 구분한다.
- P0 평가와 시연에 필요한 snapshot, 문의와 override 이력을 보존한다.
- 실제 운영 전에는 보존기간, 삭제 책임자와 권리자 요청 절차를 별도로 승인한다.
- 참조 중인 clip·scene·snapshot을 일반 UI에서 즉시 hard delete하지 않는다.

---

## 11. 평가와 성공 기준

### 11.1 최종 제품 성공 판정

P0는 다음 세 조건을 모두 만족해야 제품 목표를 달성한 것으로 판정한다.

1. paired 평가에서 usable scene 발견 성공률이 M0 현행 수기 keyword 검색보다 높다.
2. paired 평가에서 편집기자 만족도가 M0보다 높다.
3. 편집기자와 검수자의 합산 업무시간이 M0 대비 유지되거나 감소한다.

정확한 만족도 척도와 유의미 차이 판정, 그리고 동일 task의 M0 업무시간에 포함할 수기 검색·확인 활동의 경계는 최종셋을 보기 전에 Gate C에서 동결한다.

### 11.2 평가 질문

- 장면 분할과 화면·텍스트 근거가 현행 검색보다 usable scene 탐색을 개선하는가?
- Query Resolution이 사용자의 명시 조건을 보존하면서 검색 단서를 구조화하는가?
- resolver 또는 dense 실패 시 fallback이 검색 가능성과 설명 가능성을 유지하는가?
- false-hit guard가 verified conflict를 줄이면서 유용한 결과 coverage를 과도하게 낮추지 않는가?
- exact override가 같은 exact query scope에서 확인된 질의 해석 오류 또는 특정 장면 부적합의 반복을 막는가?
- reactive 검수의 추가 시간을 포함해 총업무량이 유지되거나 감소하는가?

### 11.3 Gold Set

| 정답지 | 기본 규모 | 목적 |
| --- | --- | --- |
| 장면 metadata | scene 100개 이상 | shot type, scene type, caption·근거 |
| 검색 relevance | query 30개 이상 | [Recall@10](mailto:Recall@10), [nDCG@10](mailto:nDCG@10), MRR |
| explicit anchor | 날짜·사건명 별도 표본 | false-hit와 guard |
| Query Resolver | 날짜·사건명·entity span | field 해석과 explicit 판정 |
| ASR | CC 보유 cohort | WER |
| Reactive replay | 확정 문의·override fixture | exact 재발 방지 |
| 업무 효율 | 동일 task paired session | usable scene, 만족도, 총업무시간 |

정확한 표본 규모와 교차 라벨링 비율은 데이터 확보 후 Gate C에서 동결한다. 시스템이 만든 충돌 판정을 정답으로 재사용하지 않는다.

### 11.4 데이터 분리

- 개발셋과 최종셋은 6:4로 group split한다.
- 같은 clip, 유사 영상과 동일 원천 뉴스가 양쪽에 섞이지 않게 한다.
- 최종셋은 threshold, prompt, ranking과 guard 조정에 사용하지 않는다.
- Gold label을 pipeline, resolver 또는 검색 index의 입력으로 사용하지 않는다.
- 개발 중 만든 override는 일반 최종 검색 평가에서 제외하고 reactive replay set에서 별도로 측정한다.
- split manifest, annotation guide, seed, 설정 version과 원시 결과를 보존한다.

### 11.5 baseline과 ablation

| 구성 | 신호 |
| --- | --- |
| M0 | 현행 수기 keyword 검색 |
| B0a | 제공 transcript·CC BM25 |
| B0b | ASR transcript BM25 |
| B0p | 제공 transcript·CC 우선, 없으면 ASR인 제품 source 정책 BM25 |
| B1 | B0p + scene 분할·shot type |
| B2 | + VLM caption·scene metadata |
| B3 | + OCR·typed entity |
| B4 | + dense·RRF |
| B5 | + Query Resolution·structured soft score |
| B6 | + explicit-anchor guard |

B0a와 B0b는 timestamp transcript·CC 보유 clip에서 paired cohort로 비교한다. B0p부터 B6까지는 제품 source 정책을 고정한 같은 cohort를 사용한다. guard 효과는 B5와 B6의 false-hit, Recall과 coverage를 함께 비교한다.

### 11.6 핵심 지표

#### 제품·업무 지표

- usable scene 발견 성공률
- 첫 usable scene까지 걸린 시간
- 편집기자 만족도
- query 수정 횟수와 문의율
- 문의 확인 시간, override 작성과 replay 시간
- 검수 시간/100검색
- 편집기자 + 검수자 총업무시간
- 사전검수 건수 목표: 0

#### 검색 품질

- [Recall@10](mailto:Recall@10): M0 baseline 대비 +5%p 이상 목표
- [nDCG@10](mailto:nDCG@10)
- MRR
- explicit-anchor [false-hit@10](mailto:false-hit@10)
- result coverage와 평균 결과 수
- guard 전후 [Recall@10](mailto:Recall@10) 변화
- 미검증 결과 비율

false-hit의 분모가 0이면 0%가 아니라 N/A로 보고한다. result coverage가 Gate C에서 정한 최소값보다 낮으면 false-hit이 낮아도 guard 성공으로 판정하지 않는다.

#### resolver와 fallback

- 날짜·사건명·entity field 정확도
- explicit와 inferred 구분 정확도
- query 원문 span 검증 정확도
- timeout, schema 오류, rate limit, network 실패율
- raw BM25 fallback 성공률, p95와 result coverage
- active resolution patch의 resolver 우회 여부

#### supporting extraction

- shot type Macro F1 0.80 이상 목표
- caption 정확 + 부분 정확 85% 이상 목표
- ASR WER 40% 이하 목표
- OCR과 tag의 source별 precision과 unverified 비율

이 supporting 목표를 만족해도 완벽한 metadata를 제공한다고 주장하지 않는다.

### 11.7 reactive override 필수 검증

- 같은 exact query scope와 active patch에서는 resolver를 다시 호출하지 않고 patch를 적용한다.
- 같은 query라도 filter가 다르면 patch를 적용하지 않는다.
- 의미가 비슷한 다른 문장은 override 대상이 아니다.
- active scene 제외는 해당 scene processing version만 제거하고 다음 후보를 보충한다.
- scene 재처리 후 과거 제외는 stale이며 새 scene을 자동 제외하지 않는다.
- 피처 오류 문의는 P1로 이관되고 base field와 index는 변하지 않는다.
- 사건명 hard guard는 Gate B에서 승인한 rule이 있을 때만 source-backed conflict에 적용한다.
- 승인된 사건명 rule이 없으면 사건명 hard guard를 비활성화하고 해당 hard-guard 지표를 N/A로 보고한다.
- missing, unverified 또는 LLM-only 사건명은 결과에 남는다.

---

## 12. 비기능·보안·저작권

### 12.1 성능과 환경 목표

| 항목 | 목표 |
| --- | --- |
| 검색 Top 10 카드 | 지정 benchmark에서 p95 10초 이내 |
| Preview 첫 frame | p95 2초 이내 |
| 짧은 clip live 분석 | model warm 기준 30초 목표 |
| 333클립 batch | 지정 장비에서 약 2시간 목표 |
| 문의 저장 | p50·p95 별도 보고 |
| reviewer replay | override 작성 시간과 검색 시간을 나눠 p50·p95 보고 |
| 동시 사용자 | 1명 |
| 환경 | 최신 Chrome 데스크톱, 로컬 서비스 |
| 언어 | 한국어 입력·출력, 영어 번역 경유 금지 |

성능은 OS, CPU/GPU, memory, storage, browser, model, corpus와 version이 기록된 benchmark profile에서 측정한다. 단일 시연 측정만으로 합격을 주장하지 않는다.

### 12.2 실패와 fallback 원칙

| 장애 | 사용자에게 보이는 P0 동작 |
| --- | --- |
| resolver 실패 | raw query BM25 degraded 검색 |
| dense 실패 | BM25와 사용 가능한 구조화 신호로 degraded 검색 |
| known human override 조회 실패 | 사람 결정을 우회하지 않고 검색 실패·재시도 안내 |
| snapshot 저장 실패 | ephemeral degraded 결과 표시, 문의·override 비활성 |
| BM25 또는 index 사용 불가 | 검색 실패를 빈 결과로 위장하지 않음 |
| VLM·OCR·ASR 개별 실패 | 최소 검색 조건이 남으면 warning과 함께 검색 가능 |
| 재처리 실패 | 기존 활성 version을 계속 제공하고 최신 실행 실패 표시 |

모든 단계는 실패 원인과 version을 추적할 수 있어야 하며 안전한 재시도에서 중복 장면·문의·override를 만들지 않는다.

### 12.3 로컬 보안

- 서버는 기본적으로 loopback에서만 제공한다.
- 승인된 화면 출처 외 요청과 상태 변경 요청을 제한한다.
- media는 사용자 경로가 아니라 검증된 ID로 접근한다.
- upload는 확장자뿐 아니라 실제 파일 형식과 decode 가능성을 확인한다.
- query, OCR, transcript, VLM output과 문의 설명은 비신뢰 문자열로 렌더링한다.
- 역할별 기능은 UI 표시뿐 아니라 서버에서도 구분한다.
- secret, 원본 절대 경로와 전체 민감 content를 일반 log에 남기지 않는다.
- 외부 telemetry는 P0에서 기본 비활성화한다.

세부 보안 수용 기준은 FRD v2.2를 따른다.

### 12.4 Gate S 외부 처리 정책

Gate S의 **정책은 승인**됐지만 실제 외부 호출은 데이터 권리와 provider 조건 증빙이 완료된 component와 최소 payload에만 허용한다.

| component | 최소 허용 후보 | P0 금지 |
| --- | --- | --- |
| Query Resolver | query text와 명시 filter | 전체 검색 이력, 결과 media |
| VLM | 필요한 selected keyframe | full video |
| OCR | 필요한 selected keyframe | 무관한 frame, full video |
| ASR | 필요한 audio chunk | full video, 불필요한 전체 transcript |

최소 허용 후보도 자동 허용이 아니다. 다음 조건이 모두 맞아야 한다.

- media-derived payload는 해당 clip의 외부 처리 권리와 clip별 허용이 명시적으로 확인됨
- Query Resolver의 query·filter는 media clip 승인과 별개인 deployment-level 외부 처리 허용이 명시적으로 yes임
- provider의 보관·학습·삭제 조건이 확인되고 승인됨
- 활성 deployment policy와 provider profile이 존재함
- component, model, endpoint와 payload category가 allowlist에 포함됨
- payload size가 component별 max-bytes 이내임
- TLS와 승인된 secret 주입 방식을 사용함

media clip별 승인과 query·filter의 deployment-level 승인은 서로 대신할 수 없다.

하나라도 충족하지 않으면 외부 전송 전에 fail-closed하고 구성된 local adapter 또는 명시된 fallback을 사용한다. P0에서는 full video, 전체 transcript와 전체 OCR의 외부 전송을 금지한다.

외부 call 감사 기록에는 원문 content 대신 provider version, payload category·size, 권리 판단과 결과 상태만 남긴다.

### 12.5 저작권과 발표 고지

- AI Hub와 KBS 원본 영상·frame·audio를 repository나 public deployment에 포함하지 않는다.
- 출처와 실제 이용조건을 시연·발표 결과 영역에 표시한다.
- GMS 사용을 숨기거나 외부 전송이 없다고 주장하지 않는다.
- preprocessed 결과나 사전 녹화를 live inference로 표시하지 않는다.
- 자체 호스팅 adapter 가능성과 실제 성능 동등성을 구분한다.

적용 데이터의 출처 표시는 실제 이용조건을 최종 확인한 뒤 다음 문구를 기준으로 사용한다.

> 과기정통부·NIA 인공지능 학습용 데이터 구축사업 결과물
> 
> 
> 한국어 텍스트-비디오-사운드 데이터 (AI-Hub, 71699)
> 
> 원본 영상 저작권: KBS
> 

---

## 13. 시연 범위

### 13.1 시연 목표

시연은 모델 수나 완벽한 metadata를 보여 주는 자리가 아니다. 장면 검색, 근거·불확실성 확인, 보수적 guard와 exact 재발 방지가 실제 사용자 흐름으로 연결되는지 보여 준다.

### 13.2 2분 30초 기준 흐름

| 시간 | 내용 |
| --- | --- |
| 0:00~0:20 | 현행 keyword 검색의 누락·명시 충돌 문제와 제품 목표 |
| 0:20~0:50 | 짧은 영상의 실제 scene·VLM·OCR·ASR 처리와 provenance |
| 0:50~1:10 | 한국어 Query Resolution, snapshot과 미검증 의미 |
| 1:10~1:35 | BM25+dense 검색, verified 날짜 충돌 guard, Preview |
| 1:35~1:50 | 원하지 않은 결과의 이상해요와 자동 context |
| 1:50~2:15 | 검수자의 exact override와 같은 query replay |
| 2:15~2:30 | Recall, false-hit, coverage, 총업무시간, GMS와 한계 고지 |

### 13.3 시연 원칙

- 짧은 영상 1건의 실제 처리와 검색·Preview를 우선한다.
- ASR, VLM 또는 resolver가 외부 GMS를 사용하면 이를 고지한다.
- feature, tag, OCR 또는 caption의 실제 교정·재색인은 시연하지 않는다.
- Event 후보, Event 승인 또는 사전검수 흐름을 시연하지 않는다.
- preprocessed corpus, 동일 media·pipeline·model·prompt·schema version이 확인된 pipeline artifact 또는 녹화 fallback을 사용하면 live 경로와 화면에서 구분한다. 이는 semantic query cache를 뜻하지 않는다.
- 목표 미달 수치와 실패 사례를 숨기지 않는다.

---

## 14. 개발 계획과 역할

### 14.1 1주차 — 계약과 vertical slice

- PostgreSQL 정본과 핵심 version·snapshot·inquiry·override 계약
- 짧은 clip 등록→scene/frame→최소 metadata→index→검색→Preview
- 두 nullable 날짜와 transcript 우선순위
- Query Resolution과 raw BM25 fallback
- exact query scope와 snapshot
- GMS allowlist mock과 fail-closed 확인
- Gold Set 작성 시작

1주차 종료 조건:

> 영상 1건이 사전검수 없이 장면 처리부터 검색·Preview까지 실제로 연결된다.
> 

### 14.2 2주차 — 전체 P0 통합

- 333클립 처리
- VLM·OCR·ASR·entity extraction
- BM25·dense·structured soft score·RRF
- verified match·unverified·conflict guard
- 결과 근거·두 날짜·degraded 표시와 Preview
- 이상해요 문의와 immutable context
- 검수 queue와 exact resolution patch·scene 제외
- pending 후보의 replay 후 활성화
- 재처리와 stale override 검증
- 기능 동결

2주차 종료 후 Gate A를 재개하지 않는 새 기능은 추가하지 않는다.

### 14.3 3주차 — 평가·보안·시연

- 개발·최종 group split과 Gold Set 확정
- M0와 B0~B6 ablation
- [Recall@10](mailto:Recall@10), [nDCG@10](mailto:nDCG@10), MRR, false-hit와 coverage
- resolver 정확도·실패·fallback
- 편집기자+검수자 paired 업무시간
- 외부 전송 allowlist, 권리와 provider 증빙
- 입력·media·role·재시도·복구 검증
- 시연 runbook과 preprocessed·recorded fallback 표시
- 발표 자료와 포트폴리오 산출물

### 14.4 6인 역할 기준

| 역할 | 책임 |
| --- | --- |
| 데이터·평가 | 코퍼스, split, Gold Set, 지표, 권리·출처 |
| 영상 분석 | scene detection, 다중 frame, batch와 재처리 |
| AI 추출 | VLM, OCR, ASR, evidence와 output 품질 |
| 검색 | Query Resolution, BM25, dense, RRF, guard와 ablation |
| Backend | PostgreSQL, index, snapshot, inquiry, override, 상태·복구 |
| Frontend·시연 | 등록, 검색, 결과·Preview, 검수, 시연 안정화 |

역할은 소유권 기준이며 vertical slice, 최종 통합과 실패 시나리오는 전원이 공동 책임진다.

---

## 15. Gate와 변경 통제

### 15.1 Gate 상태

| Gate | 상태 | 의미 |
| --- | --- | --- |
| Gate A | **Closed** | P0 제품 범위, 사용자 흐름, 저장 원칙, 검색, 문의와 override 확정 |
| Gate S | **정책 승인 / 실제 외부 실행 전 증빙 조건** | 최소 전송 정책은 확정. 데이터·provider 증빙 없이는 outbound 금지 |
| Gate B | **데이터 확보 후 동결** | taxonomy, confidence, timeout, ranking과 사건명 rule |
| Gate C | **평가·시연 전 동결** | split, annotation, coverage, paired 평가와 report |
| Gate D | **권장 기본값으로 진행** | 제품 범위를 바꾸지 않는 구현·UI 기본값 |

Gate B/C/D에서 후속 값을 정해야 한다는 사실은 Gate A가 미완료라는 뜻이 아니다.

### 15.2 Gate A에서 확정된 제품 결정

1. 동일 검색 범위는 normalization version, 정규화 query와 명시 filter로 결정한다.
2. 사람 override는 version 이력을 남기고 same-scope replay 성공 후에만 활성화한다.
3. Entity·OCR·Caption·Date 등 피처 교정과 재색인은 P1이다.
4. 문의는 내부 queue에서 검수하며 resolution patch, scene 제외, 조치 없음 또는 P1 이관으로 종결한다.
5. hard 제외는 명시 날짜와 승인 사건명 규칙의 verified same-field conflict에만 제한한다.
6. 사전검수와 scene 전체 승인 상태를 제거하고 evidence 3상태와 immutable snapshot을 사용하며, clip 검색 제공 상태·pipeline 실행·index generation 상태를 분리한다.
7. PostgreSQL+JSONB를 정본으로 사용하고 검색 index는 파생 저장소로 둔다.

### 15.3 Gate B에서 근거 확보 후 정할 사항

- VLM scene type taxonomy와 caption guide
- OCR 병합과 verified confidence
- ASR verified·강등 threshold
- resolver model, prompt와 timeout
- source-backed incident deterministic conflict rule
- BM25 field, dense model, RRF와 boost
- candidate pool, clip diversity와 Top 10 coverage

승인된 사건명 rule이 없으면 사건명 자동 hard guard는 활성화하지 않는다.

### 15.4 Gate C에서 평가 전에 정할 사항

- group split manifest와 최종셋 접근 통제
- annotation guide와 Gold conflict 판정
- result coverage 최소 허용값
- 만족도 척도와 유의미 개선 판정
- editor·reviewer paired 업무시간 protocol
- benchmark hardware와 측정 profile
- cohort별 report template
- GMS, fallback과 preprocessed data를 표시한 demo runbook
- source, license notice와 외부 처리 증빙

### 15.5 Gate D 기본값

- 저장 시간은 UTC, 화면은 Asia/Seoul
- scene 구간은 start 포함·end 미포함
- Preview는 scene 이후 계속 재생 가능하고 구간 다시 재생 제공
- 로컬 loopback, ID 기반 media와 부분 재생 지원
- 중복 요청과 동시 수정을 막는 version·idempotency 적용
- 제한된 로컬 log, 외부 telemetry off
- 정확한 file limit, 비동기 처리 stage의 retry 횟수와 화면 배치는 versioned config

동기 Query Resolver는 P0에서 재시도하지 않고 실패 즉시 raw BM25 fallback으로 전환한다.

Gate D는 제품 범위를 바꾸지 않는 한 실측 근거에 따라 조정할 수 있다.

### 15.6 Gate A를 다시 열어야 하는 변경

다음 변경은 단순 구현 선택이 아니므로 Gate A 재개와 PRD·FRD version 증가가 필요하다.

- P0에 field correction과 교정 재색인을 다시 포함
- semantic 또는 유사 query에 override 확대
- Event catalog·candidate·assignment 재도입
- missing 또는 unverified를 hard 제외
- entity 또는 location 불일치를 hard 제외
- snapshot을 cache나 다음 검색의 권위로 재사용
- 문의를 자동학습 또는 전역 ranking 변경에 사용
- PostgreSQL이 아닌 저장소를 정본으로 전환

### 15.7 Gate S를 다시 열어야 하는 변경

full video, 전체 transcript 또는 전체 OCR의 외부 전송을 허용하도록 정책 경계를 바꾸는 경우에는 Gate S를 재개하고 관련 문서 version을 올려야 한다.

---

## 16. 위험과 알려진 한계

### 16.1 주요 위험과 대응

| 위험 | 대응 |
| --- | --- |
| 방송본 재사용 가치의 정량 근거 부족 | 추가 인터뷰로 재사용 비율·검색 시간을 확보하고 미확인 시 한계로 공개 |
| Query Resolution이 원문을 과해석 | explicit span 검증, 원문·명시 filter 우선, 실패 시 raw BM25 |
| VLM이 특정 인물·장소를 환각 | 특정 식별은 OCR·transcript·사용자 source 중심, 미검증 표시 |
| 단일 frame이 자막·장면을 놓침 | 장면별 다중 frame 사용 |
| OCR이 작은 명패를 읽지 못함 | 원본 해상도 근거, confidence와 unverified 처리 |
| ASR 품질이 낮음 | 제공 transcript·CC 우선, WER에 따라 보조 신호로 강등 |
| guard가 결과를 과도하게 제거 | missing·unverified 유지, entity/location soft, coverage와 Recall 동시 평가 |
| 사건명 규칙 근거가 부족 | Gate B 승인 전 사건명 hard guard 비활성 |
| 문의가 많아 검수 업무가 증가 | 원클릭 자동 context, exact 조치만 제공, paired 총업무시간으로 판정 |
| exact override가 유사 질의를 해결하지 못함 | P0 한계로 고지하고 충분한 데이터 뒤 P1 검토 |
| 피처 오류가 즉시 고쳐지지 않음 | valid issue를 P1 backlog로 보존하고 P0 정본 오염 방지 |
| 재처리 중 검색이 끊기거나 version이 섞임 | 기존 active version 유지, 새 generation 검증 후 전환 |
| GMS 전송 권리·provider 조건이 불명확 | 미확인 시 fail-closed, local adapter 또는 명시 fallback |
| 라이브 시연 지연·장애 | model prewarm, 짧은 clip, preprocessed·녹화 fallback과 명확한 표시 |
| 명절 전용 서비스처럼 보임 | 동일 schema·pipeline으로 20~30클립 주제 독립 smoke test |
| 모델 수가 팀 기여로 오해됨 | 사용자 흐름, guard, 업무효율과 검색 개선 수치 중심으로 발표 |

### 16.2 사용자에게 고지할 한계

- 결과는 송출 후보 탐색을 돕지만 사실, 최신성, 저작권과 송출 적합성을 보증하지 않는다.
- 날짜나 사건명 정보가 없거나 미검증이면 관련 없는 결과가 남을 수 있다.
- person, organization, location과 facility 불일치는 P0에서 자동 차단하지 않는다.
- exact override는 query와 명시 filter가 같은 검색에만 적용된다.
- 피처 자체 오류는 P0에서 바로 수정·재색인하지 않는다.
- 문의가 쌓여도 모델이 자동 학습하거나 전역 검색이 자동 개선되지 않는다.
- P0는 local single-user demo이며 운영급 인증, MAM과 공개 배포를 제공하지 않는다.
- GMS 구성은 승인된 최소 데이터를 외부로 전송할 수 있다.
- 자체 호스팅 adapter로 교체할 수 있지만 동등한 품질과 성능은 별도 검증 대상이다.
- 명절 교통축 정량 결과를 다른 뉴스 도메인 성능으로 일반화하지 않는다.

---

## 17. 발표 메시지

### 문제 정의

> 기존 시스템은 영상 파일을 찾는다. 편집기자에게 필요한 것은 영상 속 몇 초짜리 장면이다.
> 

### 해결

> 영상을 장면 단위로 나누고 화면·텍스트 근거를 검색 가능하게 만든 뒤, 검색 시점에 질의를 임시 해석해 필요한 구간을 바로 찾는다.
> 

### 제품 정체성

> NewsCut은 Event 관리나 전수 태그 검수 시스템이 아니라, 근거와 불확실성이 보이는 뉴스 장면 검색 보조 시스템이다.
> 

### 안전 원칙

> 정보가 없다는 이유로 장면을 버리지 않고, 사용자가 명시한 조건과 검증된 사실이 충돌할 때만 보수적으로 제한한다.
> 

### reactive 검수

> 기자가 이상한 결과를 원클릭으로 알리면 검수자가 P0 조치 대상인 질의 해석 오류 또는 특정 장면 부적합에 대해 같은 exact query scope에서 조치를 replay하고, 성공한 결정만 활성화한다.
> 

### 평가 범위

> 파이프라인은 주제에 독립적으로 설계했으며, 이번 프로젝트에서는 혼동 이웃을 정량화할 수 있는 명절 교통 영상으로 검색 품질과 총업무시간을 검증한다.
> 

---

## 18. 용어

| 용어 | 정의 |
| --- | --- |
| clip | 등록한 하나의 뉴스 영상 파일 |
| scene | 장면 분할로 생성한 시간 구간 |
| B-roll | 앵커·인터뷰가 아닌 자료화면으로 사용할 수 있는 장면 |
| source·evidence | 입력 또는 자동 값의 출처와 근거 위치 |
| derived feature | ASR, OCR 정규화, tag, embedding과 VLM metadata 같은 계산 결과 |
| Query Resolution | 한 검색 실행에서 query를 임시 구조화한 결과 |
| incident name | 검색 해석 또는 source-backed evidence로 쓰는 사건명 문자열. Event 정본이 아님 |
| explicit anchor | 사용자가 query 또는 filter에 직접 명시한 날짜·사건명 단서 |
| verified conflict | 같은 필드의 검증된 값이 explicit anchor와 충돌하는 상태 |
| unverified | 값이 없거나 confidence·source가 부족해 확정할 수 없는 상태 |
| snapshot | 당시 검색을 감사·재현하기 위한 불변 기록. cache나 정답이 아님 |
| exact query scope | normalization version, 정규화 query와 명시 filter가 모두 같은 범위 |
| pinned override | 검수자가 exact scope에 고정한 resolution patch 또는 scene 제외 |
| resolution patch | exact scope의 질의 해석 전체를 사람이 교체하는 결정 |
| scene 제외 | exact scope에서 특정 scene processing version을 결과에서 빼는 결정 |
| inquiry | 편집기자가 특정 결과가 이상하다고 알리는 기록. 교정값 자체가 아님 |
| reactive review | 검색 후 실제 문제가 발견됐을 때만 수행하는 검수 |
| degraded search | 일부 구성요소 실패 후 허용된 fallback으로 반환한 검색 |
| usable scene | 주어진 편집 과업에서 Preview 후 실제 사용 후보로 판단할 수 있는 장면. 판정 기준은 Gate C에서 평가 전에 동결 |
| Gold Set | 임계값 결정과 최종 평가에 사용하는 사람 정답 데이터 |
| result coverage | 평가 query 중 충분한 결과를 반환한 범위와 비율 |

---

## 19. 확정 결정 요약

- 6인 팀, 잔여 3주, 로컬 single-user P0로 구현한다.
- 제품의 최상위 목표는 usable scene 발견 성공률과 만족도를 높이고 편집기자+검수자 총업무량을 유지·감소시키는 것이다.
- 기방영 뉴스 영상을 장면 단위로 처리하며 방송일과 촬영일은 독립 nullable 값이다.
- timestamp 제공 transcript, CC, ASR 순서로 사용하고 일반 대본은 clip 참고 텍스트로만 둔다.
- VLM, OCR, transcript, entity·tag에는 source, confidence, evidence와 검증 상태를 연결한다.
- scene 전체 승인과 review_required를 두지 않으며 사전 전수검수 없이 검색 가능하게 한다.
- Event catalog, Event Type, 후보 Top 3, assignment와 Event 지표를 P0에서 제거한다.
- 사건명은 검색 실행의 임시 해석 또는 source-backed evidence로만 사용한다.
- 검색은 BM25를 backbone으로 하고 dense, 구조화 soft score와 RRF를 보조로 사용한다.
- resolver 실패 시 raw BM25 fallback을 제공하고 별도 결과 부족 LLM 재작성은 두지 않는다.
- snapshot은 실행별 불변 감사 기록이며 다음 검색의 cache나 정답으로 재사용하지 않는다.
- hard 제외는 명시 날짜와 승인된 사건명 규칙의 verified same-field conflict에만 허용한다.
- missing과 unverified는 결과에 남기고 person·organization·location·facility 불일치는 soft 처리한다.
- 문의는 결과별 이상해요 하나로 통합하고 설명은 선택으로 둔다.
- 문의 당시 검색과 결과 context는 서버가 자동 첨부한다.
- P0 pinned override는 exact resolution patch와 exact scene 제외 두 가지다.
- 후보 override는 같은 exact query scope replay와 활성화에 성공한 뒤에만 active가 된다.
- 문의만으로 metadata, index, 현재 결과 또는 전역 ranking을 변경하지 않는다.
- Entity·OCR·Caption·Date 등 피처 교정과 재색인은 P1이다.
- PostgreSQL+JSONB를 정본으로 사용하고 검색 index는 정본과 검증된 asset manifest에서 재구축 가능한 파생 저장소로 둔다.
- 재처리 중에는 기존 활성 version을 제공하고 새 version이 검증된 뒤 전환한다.
- 평가에서 Event 지표를 제거하고 [Recall@10](mailto:Recall@10), nDCG/MRR, false-hit, coverage, resolver/fallback, replay와 paired 업무효율을 측정한다.
- Gate A는 마감됐고, Gate B/C/D의 후속 값은 근거가 생길 때 정한다.
- Gate S 정책은 승인됐지만 권리·provider 증빙과 allowlist가 없으면 외부 전송을 fail-closed한다.
- feature correction, 유사 질의 override, Event 모델 또는 PostgreSQL 외 정본을 다시 도입하려면 Gate A를 재개한다.

---

**v5 최종 결론:** NewsCut P0는 Event·사전검수·피처 교정 시스템이 아니다. 장면 단위 추출 결과를 검색 시점에 임시 해석하고, 불확실한 결과는 남겨 표시하며, 명시 anchor의 검증된 충돌만 제한한다. 편집기자는 원클릭으로 이상한 결과를 제보하고 검수자는 exact query scope의 두 override만 replay 후 활성화한다. 이 범위에서 Gate A는 마감되었다.