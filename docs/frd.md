# N-Pick-FRD-v2.2

# N-Pick 기능 요구사항 정의서 (FRD)

> 버전: **2.2**
> 
> 
> 작성일: **2026-08-31**
> 
> 최종 수정일: **2026-09-01**
> 
> 상태: **P0 최종 구현 기준본 — Gate A 마감**
> 
> 제품 성격: **검색 시점 해석과 reactive override를 사용하는 뉴스 장면 검색 시스템**
> 

## 목차

- [0. 문서 관리](about:blank#0-%EB%AC%B8%EC%84%9C-%EA%B4%80%EB%A6%AC)
- [1. 제품 정의와 범위](about:blank#1-%EC%A0%9C%ED%92%88-%EC%A0%95%EC%9D%98%EC%99%80-%EB%B2%94%EC%9C%84)
- [2. 시스템 컨텍스트와 사용자 흐름](about:blank#2-%EC%8B%9C%EC%8A%A4%ED%85%9C-%EC%BB%A8%ED%85%8D%EC%8A%A4%ED%8A%B8%EC%99%80-%EC%82%AC%EC%9A%A9%EC%9E%90-%ED%9D%90%EB%A6%84)
- [3. 공통 도메인 규칙](about:blank#3-%EA%B3%B5%ED%86%B5-%EB%8F%84%EB%A9%94%EC%9D%B8-%EA%B7%9C%EC%B9%99)
- [4. 영상 등록](about:blank#4-%EC%98%81%EC%83%81-%EB%93%B1%EB%A1%9D)
- [5. 장면·메타데이터 처리와 색인](about:blank#5-%EC%9E%A5%EB%A9%B4%EB%A9%94%ED%83%80%EB%8D%B0%EC%9D%B4%ED%84%B0-%EC%B2%98%EB%A6%AC%EC%99%80-%EC%83%89%EC%9D%B8)
- [6. Query Resolver와 Pinned Override](about:blank#6-query-resolver%EC%99%80-pinned-override)
- [7. 검색·False-hit Guard](about:blank#7-%EA%B2%80%EC%83%89false-hit-guard)
- [8. 결과 카드와 Preview](about:blank#8-%EA%B2%B0%EA%B3%BC-%EC%B9%B4%EB%93%9C%EC%99%80-preview)
- [9. 문의·검수·Reactive Override](about:blank#9-%EB%AC%B8%EC%9D%98%EA%B2%80%EC%88%98reactive-override)
- [10. 데이터 요구사항과 논리 모델](about:blank#10-%EB%8D%B0%EC%9D%B4%ED%84%B0-%EC%9A%94%EA%B5%AC%EC%82%AC%ED%95%AD%EA%B3%BC-%EB%85%BC%EB%A6%AC-%EB%AA%A8%EB%8D%B8)
- [11. 논리 API 계약](about:blank#11-%EB%85%BC%EB%A6%AC-api-%EA%B3%84%EC%95%BD)
- [12. UI 상태·오류·사용성](about:blank#12-ui-%EC%83%81%ED%83%9C%EC%98%A4%EB%A5%98%EC%82%AC%EC%9A%A9%EC%84%B1)
- [13. 비기능·보안·운영 요구사항](about:blank#13-%EB%B9%84%EA%B8%B0%EB%8A%A5%EB%B3%B4%EC%95%88%EC%9A%B4%EC%98%81-%EC%9A%94%EA%B5%AC%EC%82%AC%ED%95%AD)
- [14. 평가·완료 기준·추적성](about:blank#14-%ED%8F%89%EA%B0%80%EC%99%84%EB%A3%8C-%EA%B8%B0%EC%A4%80%EC%B6%94%EC%A0%81%EC%84%B1)
- [15. Gate와 최종 결정 기록](about:blank#15-gate%EC%99%80-%EC%B5%9C%EC%A2%85-%EA%B2%B0%EC%A0%95-%EA%B8%B0%EB%A1%9D)
- [16. 구현·완료 체크리스트와 한계](about:blank#16-%EA%B5%AC%ED%98%84%EC%99%84%EB%A3%8C-%EC%B2%B4%ED%81%AC%EB%A6%AC%EC%8A%A4%ED%8A%B8%EC%99%80-%ED%95%9C%EA%B3%84)

---

## 0. 문서 관리

### 0.1 목적

이 문서는 N-Pick P0의 기능, 데이터, 상태, API, UI, 실패 처리, 보안, 평가와 수용 기준을 구현 가능한 수준으로 정의한다. 팀은 본 문서의 `BR-*`, `FR-*`, `DR-*`, `NFR-*`, `AC-*`를 P0 구현과 완료 판정의 공통 계약으로 사용한다.

### 0.2 기준 문서와 우선순위

본 v2.2의 직접 기준은 다음과 같다.

1. 사용자가 승인한 본 대화의 최종 결정
2. `N-Pick 확정 설계 기준본 (2026-08-31)`
3. 본 FRD v2.2
4. `N-Pick-PRD-v4.md` 중 상위 기준과 충돌하지 않는 요구사항
5. `N-Pick-FRD-v2.1.md` 중 상위 기준과 충돌하지 않는 세부 계약

확정 설계 문서에는 “PRD v5 갱신 후 FRD 재작성”이라는 문서 순서가 적혀 있으나, 이번 사용자 요청은 FRD v2.2를 먼저 최종 작성하는 것이다. 따라서 본 문서는 확정 설계를 직접 상위 기준으로 사용하며, PRD v5 작성 후에는 범위 추가가 아니라 **일관성 대조**만 수행한다. PRD v5가 본 기준과 충돌하면 조용히 혼합하지 않고 변경 승인 후 문서 버전을 올린다.

### 0.3 v2.1 대비 핵심 변경

| 구분 | v2.2 확정 내용 |
| --- | --- |
| 제품 목표 | 메타데이터 완전성보다 검색 만족도 향상과 편집·검수 총업무량 유지·감소 |
| 처리 철학 | 사전 전수검수 대신 lazy extraction과 검색 후 reactive 검수 |
| 삭제 | Event catalog, Event Type, Event candidate Top 3, assignment, confirmed gate, Event [Recall@3](mailto:Recall@3) |
| 삭제 | `review_required`, 사전 필수검수 큐, scene 전체 승인 gate |
| 검색 해석 | 검색마다 임시 Query Resolution 생성; snapshot은 감사·재현용이며 재사용하지 않음 |
| 검색 확장 | 별도 “결과 부족 시 LLM 질의 재작성 1회” 삭제; `expanded_terms`를 resolver 출력에 통합 |
| 안전 규칙 | 검증된 일치/정보 없음·불확실/검증된 충돌의 3값 판정과 명시 anchor guard |
| Reactive 조치 | exact `resolution_patch`, exact `exclude_scene` 두 pinned override만 P0 |
| 피처 교정 | Entity·OCR·Caption 등 `field_override`와 교정 재색인은 P1 |
| 문의 | 편집기자 `이상해요` 원클릭, 내부 검수 큐, 선택적 서술, 당시 검색 context 자동 첨부 |
| 저장소 | PostgreSQL 정본 + JSONB snapshot + 재생성 가능한 검색 인덱스 |
| 외부 처리 | GMS 최소 전송·권리 확인·미승인 fail-closed 정책 |

`N-Pick-FRD-v2.1.md`는 변경 이력으로 보존하며 덮어쓰거나 삭제하지 않는다.

### 0.4 문장과 상태의 의미

- “해야 한다”와 `확정`은 P0 필수 계약이다.
- `파생`은 상위 확정 요구를 충족하기 위해 필요한 필수 계약이다.
- `Gate B/C`는 Gold Set·실측값이 있어야 숫자를 동결할 수 있는 항목이며, 빈칸을 임의 숫자로 채우지 않는다.
- `Gate D`는 제품 범위를 바꾸지 않는 구현 기본값이다. 팀은 본 문서의 권장 기본값으로 진행하고 실측 근거가 생기면 같은 기능 범위 안에서 버전화할 수 있다.
- `P1`은 P0 완료 판정에 포함하지 않는다.

### 0.5 요구사항 ID

| 접두어 | 의미 |
| --- | --- |
| `BR-*` | 도메인·업무 규칙 |
| `FR-ING-*` | 영상 등록 |
| `FR-PRC-*` | 장면·메타데이터 처리 |
| `FR-QRY-*` | Query Resolver·정규화 |
| `FR-OVR-*` | Pinned Override |
| `FR-SRH-*` | 검색·guard |
| `FR-RES-*` | 결과·Preview |
| `FR-FBK-*` | 문의·검수 |
| `DR-*` | 데이터·무결성 |
| `FR-API-*` | API 계약 |
| `FR-UI-*` | UI 계약 |
| `NFR-*` | 비기능·보안·운영 |
| `FR-EVL-*`, `AC-*` | 평가·수용 기준 |
| `DEC-*` | 승인된 결정 기록 |

---

## 1. 제품 정의와 범위

### 1.1 제품 정의

N-Pick은 기방영 뉴스 영상을 장면 단위로 처리하고, 사용자가 검색할 때 질의를 good-enough 구조로 임시 해석하여 관련 장면을 찾도록 돕는 로컬 중심 검색 시스템이다. 명시적으로 잘못 쓰일 가능성이 확인된 결과만 보수적으로 차단하고, 검색 중 발견된 오류는 편집기자의 원클릭 문의와 검수자의 exact override로 같은 검색에서 반복되지 않게 한다.

제품 목표는 다음 제약 최적화다.

> 사용할 장면을 찾는 성공률과 만족도를 높이되, 편집기자와 검수자의 합산 업무량을 늘리지 않고 명백한 오사용을 보수적으로 제한한다.
> 

완벽한 메타데이터, AI 모델의 우수성, 자동학습은 P0의 목표나 주장이 아니다.

### 1.2 사용자와 권한

| 역할 | 내부 role | P0 책임 | 할 수 없는 것 |
| --- | --- | --- | --- |
| 편집기자 | `editor` | 한국어 검색, 근거·타임코드 확인, Preview, `이상해요` 문의, 송출 전 최종 확인 | override 생성·해제, 원천·피처 직접 수정 |
| 검수자/아카이빙 담당자 | `reviewer` | 영상 등록·처리 상태 확인, 접수 문의 진단, exact override 등록·버전 갱신·해제, replay 확인 | 문의 없이 전수 사전검수, P0 피처 교정·재색인 |

P0는 로컬 서비스와 고정된 데모 사용자 식별을 허용하지만 서버는 역할별 capability를 구분해야 한다. 이는 완전한 인증·인가 시스템을 뜻하지 않으며 제품이 운영급 접근 통제를 제공한다고 주장해서는 안 된다. `reviewer_id`와 `editor_id`는 클라이언트 자유 문자열이 아니라 서버가 현재 actor에서 기록한다.

### 1.3 P0 포함 범위

| 기능군 | P0 내용 |
| --- | --- |
| Ingestion | 방송분 영상, nullable 방송일·촬영일, 사용권, 선택적 timestamp transcript·CC·일반 대본 등록 |
| Scene processing | 장면 분할, start/end, 다중 keyframe, thumbnail, 안정 ID·processing version |
| Extraction | VLM 구조화 metadata, OCR 원문·confidence·frame, transcript 우선순위와 ASR fallback, typed entity/tag 후보 |
| Provenance | 원천·파생 피처·파생 해석·사람 override 구분, 필드별 source·confidence·evidence·verification 상태 |
| Query resolution | 날짜 window, incident name, entity, location, term, intent를 JSON schema로 임시 구조화 |
| Retrieval | BM25 backbone, dense 보조, 구조화 soft score, RRF, B-roll 보조 정책 |
| Safety | 명시 anchor와 검증된 동일 필드 충돌의 false-hit guard, 미검증 표시 |
| Snapshot | query resolution과 guard 전후 검색 결과의 immutable 기록 |
| Reactive review | `이상해요` 문의, 내부 검수 큐, exact `resolution_patch`, exact `exclude_scene`, replay |
| Result | 장면 카드, 두 날짜, provenance·근거·미검증·degraded 표시, scene timecode Preview |
| Evaluation | [Recall@10](mailto:Recall@10), [nDCG@10](mailto:nDCG@10)/MRR, explicit-anchor [false-hit@10](mailto:false-hit@10), resolver·fallback, 편집+검수 총업무시간 |

### 1.4 P0 제외와 P1

다음은 P0에서 구현하거나 완료 기준으로 주장하지 않는다.

- Event/Event Type catalog, alias, fingerprint, candidate Top 3, clip assignment, confirmed gate
- Event [Recall@3](mailto:Recall@3), Event Top-1, Event Graph
- scene·clip 전수 사전검수와 검색 전 승인 gate
- Entity·OCR·Caption·Date 등 피처의 `field_override`, 정본 교정, 교정 재색인
- 문의만으로 자동 변경·자동학습·개인화·전역 가중치 변경
- semantic cache, 유사 질의 override, cache 무효화
- 별도 LLM 질의 재작성 1회
- 화자 분리·신원 판별, ASR 학습·파인튜닝
- frame image embedding 검색, YOLO 객체 탐지, camera work 검출
- 가편집, 원고 전체 B-roll 배치, Premiere·OTIO·FCP XML 내보내기
- MAM 실연동, 공개 서버, 모바일, 다중 사용자 운영, 운영급 로그인·회원관리

P1 우선순위는 다음과 같다.

1. 문의로 발견한 Entity·OCR·Caption 오류의 versioned field correction과 대상 재색인
2. semantic cache와 명시적 cache invalidation
3. 유사 질의 override 확장
4. 충분한 데이터와 별도 승인 후 학습·개인화·가중치 자동 조정

P1 피처 교정은 “시간이 남으면 P0”가 아니다. 별도 schema, 권한, 적용 이력, 재색인, 실패 복구와 수용 기준을 승인한 뒤 시작한다.

### 1.5 데이터·환경 경계

- 정량 평가 코퍼스 기본안은 명절 교통축 183클립과 distractor 150클립, 합계 333클립·약 2,200장면이다.
- 범용성 smoke test는 화재·건물·군중 등 20~30클립이며 정식 성능 주장에 사용하지 않는다.
- 입력·출력 언어는 한국어이며 영어 번역을 경유하지 않는다.
- P0 품질 보장 환경은 최신 Chrome 데스크톱, 로컬 서비스, 동시 사용자 1명이다.
- AI Hub 원본·저작권 영상은 저장소나 공개 서버에 포함하지 않는다.
- 외부 GMS 호출은 §13.4의 Gate S 실행 조건을 만족한 데이터만 허용한다.

### 1.6 허용·금지 주장

| 허용 | 금지 |
| --- | --- |
| 현행 수기 키워드 baseline보다 [Recall@10이](mailto:Recall@10%EC%9D%B4) 개선됨 | 의미검색보다 항상 정확함 |
| 명시 anchor의 검증된 충돌을 보수적으로 차단함 | 완벽한 메타데이터를 제공함 |
| 낮은 추가 업무로 검색 실패를 발견함 | 문의만으로 자동 개선됨 |
| 검수자가 고정한 exact query·filter 오류가 같은 조건에서 반복되지 않음 | 유사 질의와 전체 검색이 자동으로 좋아짐 |
| 구성 교체를 통해 자체 호스팅 가능 | 데모 데이터가 외부로 전혀 전송되지 않음 |
| 송출 후보 탐색을 보조함 | 송출 적합성과 최신성을 보증함 |

### 1.7 핵심 용어

| 용어 | 정의 |
| --- | --- |
| clip | 등록한 하나의 뉴스 영상 파일 |
| scene | 장면 분할로 생성된 `[start_time, end_time)` 구간 |
| source/evidence | 변경하지 않고 보존하는 입력·관측 근거 |
| derived feature | ASR, OCR 정규화값, tag, embedding 등 계산 결과 |
| query resolution | 한 검색 실행에서만 쓰는 질의의 임시 구조화 결과 |
| snapshot | 당시 상태를 감사·재현하기 위한 불변 기록. cache나 권위가 아님 |
| pinned override | 사람이 exact query scope에 명시적으로 고정한 우선 규칙 |
| effective resolution | explicit filter와 활성 resolution patch 또는 resolver 결과를 조합한 실제 검색 해석 |
| explicit anchor | 사용자 필터 또는 원문 query에 직접 나타나고 원문 span으로 검증되는 날짜·사건명 단서 |
| verified conflict | 같은 필드의 검증된 후보 값이 explicit anchor와 충돌하는 상태 |
| unverified | 정보가 없거나 신뢰·출처 기준이 부족해 확정할 수 없는 상태 |
| degraded search | 일부 구성요소 실패 후 허용된 fallback 채널로 반환한 검색 |
| inquiry | 편집기자가 특정 검색 결과가 이상하다고 알리는 기록. 교정값 자체가 아님 |

---

## 2. 시스템 컨텍스트와 사용자 흐름

### 2.1 논리 구성

```
[등록·처리 UI] ──▶ [Ingestion API] ──▶ [Pipeline Worker]
       │                                      │
       │                                      ├─ Scene/Frame
       │                                      ├─ VLM/OCR
       │                                      ├─ CC/Transcript/ASR
       │                                      └─ Entity/Embedding
       │                                              │
       ▼                                              ▼
[PostgreSQL + JSONB 정본] ──────────────────▶ [파생 검색 인덱스]
       ▲                                              ▲
       │                                              │
[검수·문의 UI] ◀── [Inquiry/Override API]      [Search Service]
       │                                      ▲       │
       └── exact pinned override ─────────────┘       │
                                                     ▼
                                        [Query Resolver Adapter]
                                        local 또는 승인된 GMS
```

PostgreSQL은 ID·관계·상태·override·문의와 snapshot의 정본이다. BM25·dense 인덱스는 PostgreSQL 정본에서 재생성 가능한 파생 저장소다. Query Resolver와 추출 모델의 외부 호출은 adapter 경계 뒤에 두어 자체 호스팅 구현으로 교체 가능해야 한다.

### 2.2 화면 경계

| 화면 | 사용자 | P0 기능 |
| --- | --- | --- |
| `/review` 처리 | reviewer | 영상 등록, pipeline·index 상태, 실패·재시도 확인 |
| `/review` 문의 | reviewer | `pending/reviewing/resolved/dismissed/deferred` 문의 조회, 근거 확인, 두 override 또는 P1 이관, replay |
| `/search` | editor | query·명시 필터 입력, 결과·경고·근거 확인, Preview, `이상해요` 제출 |

사전 메타데이터 검수 탭과 scene 승인 UI는 P0에 두지 않는다.

### 2.3 UC-ING-001 등록부터 검색 가능까지

1. reviewer가 방송분 영상, 사용권, 선택적 날짜·transcript를 등록한다.
2. 시스템은 `clip_id`와 pipeline run을 만들고 비동기 처리를 시작한다.
3. scene, frame, VLM/OCR, transcript/ASR, entity, embedding을 단계별로 생성한다.
4. 각 값은 provenance와 verification 상태를 가진다.
5. 치명 단계가 성공하면 사용 가능한 필드를 색인한다. 사전검수는 요구하지 않는다.
6. 비치명 단계 누락은 `ready` 결과와 index 문서에 누락 상태로 표시한다.
7. 색인 성공 후 clip은 검색 가능해진다.

### 2.4 UC-SRH-001 검색부터 Preview까지

1. editor가 한국어 query와 선택적 명시 필터를 제출한다.
2. 시스템은 query를 정규화하고 exact override를 조회한다.
3. 활성 resolution patch가 있으면 사용하고, 없으면 Query Resolver를 호출한다.
4. resolver 출력 schema가 유효하지 않거나 timeout이면 raw query BM25 fallback을 사용한다.
5. resolution snapshot을 저장한다.
6. BM25·dense·구조화 soft score를 융합해 후보를 순위화한다.
7. explicit anchor의 verified conflict를 차단하고 exact exclude_scene을 적용한다.
8. 다음 유효 후보로 Top 10을 보충하고 결과 snapshot을 저장한다.
9. UI는 일치 근거, 두 날짜, provenance, 미검증·degraded 상태를 표시한다.
10. editor는 scene start부터 Preview하고 송출 전 최종 확인한다.

### 2.5 UC-FBK-001 문의부터 exact override까지

1. editor가 원하지 않은 scene의 `이상해요`를 누른다.
2. 시스템은 선택 scene과 당시 query, filter, resolution, rank, guard, override, search/index version을 자동 첨부한다.
3. editor는 선택적으로 서술 내용을 입력한다.
4. 문의는 내부 `/review` 큐에 `pending`으로 저장된다. 접수만으로 결과·정본·index를 변경하지 않는다.
5. reviewer는 문의를 `reviewing`으로 전환하고 근거를 확인한다.
6. 질의 해석 오류면 전체 `resolution_patch`, 특정 결과 부적합이면 `exclude_scene`을 등록한다.
7. 피처 자체 오류는 P0에서 수정하지 않고 `deferred_p1`로 이관한다.
8. 동일 query·filter를 replay하여 활성 override와 개선 결과를 확인한다.
9. replay가 성공하면 문의를 `resolved`로 닫는다. 조치 불필요는 사유와 함께 `dismissed` 처리한다.

---

## 3. 공통 도메인 규칙

### 3.1 Provenance와 불변 원천

| 계층 | 예시 | P0 규칙 |
| --- | --- | --- |
| 원천 | 사용자 입력, 원본 metadata, CC, OCR verbatim | 값을 덮어쓰지 않고 불변 보존 |
| 파생 피처 | ASR, tag, normalized OCR, embedding, VLM metadata | model/pipeline version과 근거 보존 |
| 파생 해석 | LLM query resolution | 검색 실행별 snapshot; 정본 사실로 승격 금지 |
| 사람 override | resolution patch, exclude scene | exact scope에서 모델보다 우선, append-only version 이력 |

| ID | 규칙 |
| --- | --- |
| `BR-SRC-001` | 모든 검색 가능 값은 source, 생성 주체, 생성 version과 근거 위치를 역추적할 수 있어야 한다. |
| `BR-SRC-002` | override는 원천과 파생 피처를 덮어쓰지 않는다. P0에서는 effective query resolution 또는 결과 포함 여부에만 우선 적용한다. |
| `BR-SRC-003` | snapshot이나 cache에서 읽었다는 사실은 값의 검증 근거가 아니다. |
| `BR-SRC-004` | LLM 단독 해석은 verified evidence가 아니며 hard conflict를 만들 수 없다. |
| `BR-SRC-005` | OCR은 verbatim·confidence·frame이 함께 있을 때만 검증 후보가 된다. ASR은 동결된 confidence 기준을 만족할 때만 제한적으로 검증 후보가 된다. |

### 3.2 필드 검증 3상태

P0는 scene 전체 승인 상태를 사용하지 않고 각 evidence 또는 effective field에 다음 상태를 사용한다.

| 상태 | 의미 | 검색 처리 |
| --- | --- | --- |
| `verified` | 허용 출처와 필드별 검증 규칙을 충족 | match boost 또는 명시 anchor conflict 판정 가능 |
| `unverified` | 값 부재, confidence 부족, 출처 불충분 | 제외하지 않음, boost 없음, UI 배지 |
| `rejected` | 근거가 잘못되었거나 사용할 수 없다고 판정 | 검색 신호에서 제외, 원문 evidence는 보존 |

scene이나 clip에 종합 `review_status`를 두어 검색을 gate하지 않는다. 사람의 query/result override는 `pinned_override`에서 별도로 관리한다.

### 3.3 Transcript 우선순위

| 우선순위 | 입력 | 처리 |
| --- | --- | --- |
| 1 | 사용자 제공 timestamp transcript | 원래 segment time·source를 보존하고 scene과 overlap 매핑 |
| 2 | 유효한 내장 CC | segment time·source를 보존하고 scene과 overlap 매핑 |
| 3 | ASR | 위 두 timestamp source가 없을 때 자동 실행 |
| 별도 | timestamp 없는 일반 대본 | clip `reference_text`로만 저장; 모든 scene에 복제 금지 |

`BR-TRN-001`: 하나의 segment를 scene에 매핑할 때 시간 겹침만 사용하며, timestamp 없는 대본을 장면별 발화처럼 취급해서는 안 된다.

`BR-TRN-002`: ASR 실패는 VLM·OCR·scene 산출물을 폐기하지 않는다.

`BR-TRN-003`: 실제 사용한 source와 fallback 이유를 clip과 scene에서 확인할 수 있어야 한다.

### 3.4 날짜 의미

| 입력·상황 | P0 의미 |
| --- | --- |
| `broadcast_date` | 방송된 날짜, nullable |
| `filming_date` | 촬영된 날짜, nullable |
| bare 연도·날짜 query | 기본 `broadcast_date` anchor |
| “촬영”, “현장 촬영”이 명시된 날짜 | `filming_date` anchor |
| “최근 영상” | `broadcast_date` 기준 최신성 soft 정렬 |
| 같은 날짜 field의 복수 window | OR |
| 서로 다른 field가 모두 명시됨 | 각 명시 field를 독립 판정; verified conflict가 하나라도 있으면 차단 가능 |
| 날짜 없음·미검증 | 검색 유지 + `미상/미검증` |

`BR-DATE-001`: 한 날짜를 다른 의미의 날짜에 복사해서는 안 된다.

`BR-DATE-002`: “Hard”는 누락을 제외한다는 의미가 아니라 explicit anchor와 verified same-field value의 충돌만 제외한다는 의미다.

`BR-DATE-003`: OCR·ASR에 날짜 문자열이 있다는 이유만으로 clip의 방송일·촬영일로 승격해서는 안 된다. field-specific provenance가 필요하다.

### 3.5 사건명·Entity 안전 경계

P0에는 Event 정본이나 clip assignment가 없다. 사건명은 필요한 경우 `field_evidence.field=incident_name`인 source-backed 문자열 evidence로만 저장한다.

| anchor | verified match | missing/unverified | verified mismatch |
| --- | --- | --- | --- |
| 날짜·기간 | boost | 유지+미검증 | explicit이면 hard 가능 |
| 구체적 사건명 | boost | 유지+미검증 | 승인된 deterministic conflict 규칙 또는 사람 exclude가 있을 때만 hard |
| person·organization·location·facility | boost | 유지+미검증 | soft mismatch; hard 금지 |

`BR-GRD-001`: 원문 metadata, 사람 override, 승인된 deterministic rule처럼 source-backed incident label만 hard 판정의 후보가 된다.

`BR-GRD-002`: transcript·OCR·ASR 문자열에서 LLM이 사건명을 추출한 것만으로 배타적 다른 사건을 확정해서는 안 된다.

`BR-GRD-003`: 사건명 동의어·상하 관계·배타 관계가 승인된 versioned rule에 없으면 mismatch를 soft로 유지한다.

`BR-GRD-004`: 촬영 장소와 사건 장소, 복수 인물·기관 공존 가능성 때문에 person·organization·location mismatch는 P0 hard exclude 근거가 아니다.

### 3.6 Snapshot·Cache·Override 구분

| 대상 | 목적 | 재사용 | 검색 권위 |
| --- | --- | --- | --- |
| snapshot | 당시 실행 감사·재현 | 금지 | 없음 |
| semantic cache | 반복 계산 생략 | P1 | 없음 |
| pinned override | 검수자 명시 결정 | exact scope에서 허용 | 모델·cache보다 우선 |

P0는 query resolution snapshot을 다음 query의 해석으로 재사용하지 않는다. 동일 query라도 활성 override가 없으면 새 resolver 실행 또는 raw fallback을 수행한다.

### 3.7 공통 시간·식별 규칙

- ID는 외부에 의미를 노출하지 않는 안정 식별자를 사용한다.
- 저장 시각은 UTC ISO 8601로 기록하고 UI에서 Asia/Seoul로 표시한다.
- scene 시간 구간은 `[start_time, end_time)`이다.
- 과거 snapshot·문의가 참조하는 ID를 조용히 다른 대상에 재사용하지 않는다.
- 모든 상태 변경 API는 idempotency key 또는 expected version을 사용해 중복 적용과 lost update를 막는다.

---

## 4. 영상 등록

### 4.1 입력 계약

| 필드 | 필수 | 규칙 |
| --- | --- | --- |
| 영상 파일 | 예 | P0 source type은 `broadcast`; 허용 format·size·duration은 deployment profile로 검증 |
| 제목 | 아니오 | 입력값은 source metadata로 보존; 비어 있으면 UI 표시용 안전한 filename stem을 사용할 수 있으나 사실 metadata로 승격 금지 |
| 방송일 | 아니오 | `broadcast_date`, nullable |
| 촬영일 | 아니오 | `filming_date`, nullable |
| 사용권 정보 | 예 | 원천·사용 범위·표시 문구를 보존 |
| 외부 처리 허용 | 예 | `yes`, `no`, `unknown`; `no/unknown`은 외부 GMS 전송 금지 |
| timestamp transcript | 아니오 | SRT, VTT 또는 승인된 timestamp JSON; cue 시간과 source 보존 |
| 일반 대본 | 아니오 | clip `reference_text`; scene transcript로 복제 금지 |

### 4.2 기능 요구사항

| ID | 요구사항 |
| --- | --- |
| `FR-ING-001` | 한 등록 요청은 영상 파일 하나와 필수 사용권·외부 처리 정보를 받아야 한다. |
| `FR-ING-002` | P0 UI와 API는 source type을 `broadcast`로 고정하고 `raw`, `submitted`를 선택지로 제공하지 않아야 한다. |
| `FR-ING-003` | 두 날짜는 독립 nullable field로 저장하고 둘 다 없는 등록을 허용해야 한다. |
| `FR-ING-004` | 입력·format·MIME/magic bytes·media decode 사전 검증 실패 시 clip이나 pipeline run을 검색 가능한 상태로 남기지 않아야 한다. |
| `FR-ING-005` | 등록 성공 시 `clip_id`, `serving_status=queued`, `pipeline_run_id`, 생성 시각을 반환해야 한다. |
| `FR-ING-006` | 제공 transcript·CC·ASR의 실제 우선순위와 source를 기록해야 한다. |
| `FR-ING-007` | timestamp 없는 reference text는 clip 단위로만 저장하고 scene 검색 신호에 포함할 경우 별도 search version 설정으로 명시해야 한다. 기본값은 직접 색인하지 않음이다. |
| `FR-ING-008` | 중단·중복 요청은 idempotency key와 media content hash로 탐지하되, 같은 파일의 새 processing version 생성 여부를 사용자에게 명시해야 한다. |
| `FR-ING-009` | media root 밖 경로나 클라이언트가 지정한 임의 server path를 받아서는 안 된다. |
| `FR-ING-010` | 등록 actor와 license provenance를 감사 이력에 기록해야 한다. |

파일 크기·길이·codec의 정확한 수치는 실제 시연 데이터와 장비로 동결하는 Gate D 설정이다. 미확정 숫자를 코드에 산재시키지 않고 versioned media profile 하나에서 관리한다.

### 4.3 상태와 오류

| 조건 | UI | 서버 |
| --- | --- | --- |
| 초기 | 필수·선택 입력 구분 | 없음 |
| 검사 중 | 제출 비활성, 검사 표시 | format·권리 field 검사 |
| 등록 중 | 중복 제출 방지 | 단일 clip·run transaction |
| 성공 | `queued`, 처리 화면 이동 | pipeline enqueue |
| 입력 오류 | field 근처 한국어 오류, 입력 보존 | 4xx, 작업 미생성 |
| 저장·queue 오류 | 성공으로 표시하지 않고 재시도 | 불완전 row 비검색 상태, error code |
| 외부 처리 불가 | 로컬 처리 또는 명시적 처리 불가 | 외부 adapter 호출 금지 |

### 4.4 수용 기준

- `AC-ING-001`: 유효한 방송분과 필수 사용권 정보를 등록하면 clip·pipeline run이 한 번만 생성된다.
- `AC-ING-002`: 방송일과 촬영일을 각각 또는 모두 비워도 의미가 섞이지 않고 저장된다.
- `AC-ING-003`: timestamp 없는 대본이 모든 scene transcript로 복제되지 않는다.
- `AC-ING-004`: 동일 idempotency key 재전송으로 중복 clip·run이 생기지 않는다.
- `AC-ING-005`: `external_processing_allowed=no/unknown`인 clip의 frame·audio·text가 GMS mock endpoint로도 전송되지 않는다.

---

## 5. 장면·메타데이터 처리와 색인

### 5.1 Pipeline 단계

| 순서 | 단계 | 필수 출력 | 실패 분류 |
| --- | --- | --- | --- |
| 1 | `scene_detection` | scene boundary·index | 치명 |
| 2 | `frame_extraction` | 복수 keyframe·thumbnail | 치명 |
| 3 | `vlm_metadata` | schema-valid metadata·confidence·frame evidence | 비치명, 누락 표시 |
| 4 | `ocr` | frame별 verbatim·confidence·box·evidence | 비치명, 누락 표시 |
| 5 | `transcript_selection` | provided/CC 선택 또는 ASR 필요 판정 | 비치명 |
| 6 | `asr` | segment start/end/text/confidence | 비치명 |
| 7 | `scene_transcript_mapping` | overlap 기반 scene segment 연결 | 해당 신호 누락 |
| 8 | `entity_extraction` | typed tag 후보·source·confidence·evidence | 비치명 |
| 9 | `text_embedding` | searchable dense vector·model version | 비치명 |
| 10 | `indexing` | BM25·dense·structured index document | 치명: 검색 불가 |

Event candidate 생성과 review routing 단계는 존재하지 않는다.

### 5.2 Pipeline·Index 상태

`clip.serving_status`는 현재 검색 제공 가능 여부를 나타내며 최초 처리에서는 다음 상태를 사용한다.

```
queued → processing → ready
   └───────────────→ failed
failed → queued 또는 processing  (명시적 재시도)
```

active run이 이미 있는 재처리는 clip serving status를 `ready`로 유지하고 새 `pipeline_run.status`로 진행·실패를 표시한다. 새 generation promotion이 성공하면 active run pointer만 교체하며, 실패하면 기존 pointer와 검색 제공 상태를 유지한다.

| 객체 | 상태 |
| --- | --- |
| pipeline run/stage | `queued`, `running`, `succeeded`, `failed`, `skipped` |
| index state | `pending`, `indexing`, `searchable`, `failed`, `superseded` |
| clip summary | `queued`, `processing`, `ready`, `failed` |

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-001` | clip serving 상태, pipeline run/stage 상태, index 상태를 분리해 저장해야 한다. |
| `FR-PRC-002` | `ready`는 허용된 모든 필드가 검수됐다는 뜻이 아니라 치명 처리와 활성 index build가 성공했다는 뜻이어야 한다. |
| `FR-PRC-003` | stage마다 attempt, 시작·종료, 입력·출력 version, error code, retry 가능 여부를 기록해야 한다. |
| `FR-PRC-004` | 비치명 단계 실패는 사용 가능한 다른 신호의 처리를 계속하고 결과에 누락 channel을 기록해야 한다. |
| `FR-PRC-005` | 최초 처리에서 index가 실패해 active run이 없는 clip은 `ready`로 표시해서는 안 된다. 기존 active run이 있는 재처리 실패는 `ready`를 유지하되 latest run 실패와 기존 version 제공 중임을 표시해야 한다. |
| `FR-PRC-006` | 실패 재시도는 성공 산출물을 중복 생성하거나 과거 scene ID의 의미를 바꾸지 않아야 한다. |
| `FR-PRC-007` | 333클립 batch의 일부 실패가 나머지 clip 처리를 중단시키지 않아야 하며 성공·실패·재시도 수를 집계해야 한다. |
| `FR-PRC-008` | active run이 있는 clip의 재처리 시작·실패는 serving status를 ready에서 processing/failed로 내리지 않고 latest run·stage·warning으로 표현해야 한다. |
| `FR-PRC-009` | 재처리 성공 시 검증된 generation promotion transaction에서만 active run pointer를 새 succeeded run으로 교체해야 한다. |

자동 retry 횟수와 transient error 목록은 실행 환경 측정 후 pipeline profile로 동결한다. 무한 retry는 금지한다.

### 5.3 Scene·Frame

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-010` | scene detection으로 clip을 한 개 이상의 `[start_time,end_time)` 구간으로 분할해야 한다. |
| `FR-PRC-011` | scene은 clip 내 `scene_index`, 시작·종료, `processing_version`, 안정 `scene_id`를 가져야 한다. |
| `FR-PRC-012` | 각 scene에서 복수 keyframe과 대표 thumbnail 하나를 생성해야 한다. keyframe은 고정 개수가 아니라 **scene 내 변화량 기반 적응형 선택**으로 뽑는다(정적 scene은 적게, 동적 scene은 많게). |
| `FR-PRC-013` | frame asset은 scene, timestamp, 추출 방식·version과 연결되어야 한다. |
| `FR-PRC-014` | 작은 OCR 대상에는 thumbnail이 아닌 원본 해상도 기반 frame을 사용할 수 있어야 한다. |
| `FR-PRC-015` | scene 경계 임계값·최소 길이·frame 선정 파라미터는 pipeline version에 포함해야 한다. |

**적응형 keyframe 선택 (FR-PRC-012 구체화)**

scene마다 균등 후보 프레임 N개(초기 5개: 0/25/50/75/100%)를 뽑고, 중앙(50%) 프레임을 시드로 시작해, 나머지 후보를 이미 선택된 keyframe들과 비교하여 **변화량이 임계값 이상일 때만** 새 keyframe으로 추가한다. 결과적으로 정적 scene은 1~2장, 동적 scene은 3장 이상이 자동으로 선택된다.

> **초기 파라미터(init) — 정밀 측정 후 재정의 예정.** 아래 값은 소표본 실측(화재 카테고리, VLM 프록시 판정) 기준의 시작값이며, 정식 gold set·실제 검색 평가로 재튜닝한 뒤 pipeline version에 동결한다(FR-PRC-015).
>
> | 파라미터 | 초기값 | 근거·비고 |
> | --- | --- | --- |
> | scene 경계 임계값 | ~20 (PySceneDetect ContentDetector) | 하드컷 감지. 비슷한 톤 전환은 놓칠 수 있어 하향 여지 |
> | keyframe 후보 수 | 5 (0/25/50/75/100%) | scene당 균등 샘플 |
> | keyframe 변화량 임계 | ~12 (HSV 히스토그램 코사인거리 기반) | 커버리지 실측 기준. scene 경계 임계와 척도 통일 필요 |
> | scene당 평균 keyframe | ~2.0장 (실측) | 고정 2장과 유사 처리량, 분배는 적응형 |
>
> 변화량 척도는 scene 분할이 계산하는 content score와 통일하는 것을 권장한다(재활용).

### 5.4 VLM metadata

VLM 출력은 자유 문장이 아니라 versioned JSON schema여야 한다.

| 필드 | 형식·정책 |
| --- | --- |
| `caption` | 화면에서 관찰 가능한 내용 중심, nullable |
| `scene_type` | Gate B에서 동결한 닫힌 어휘 또는 `unknown` |
| `shot_type` | `anchor`, `field_interview`, `b_roll`, `unknown` |
| `visible_text` | 후보 문자열; OCR verbatim을 대체하지 않음 |
| `season`, `weather`, `crowd_density` | nullable soft field |
| field metadata | confidence, evidence frame, model/prompt/schema version |

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-020` | schema validation 실패 출력을 정본 field나 검색 index에 부분 적용해서는 안 된다. |
| `FR-PRC-021` | 불확실한 값은 `null` 또는 허용된 `unknown`으로 반환할 수 있어야 한다. |
| `FR-PRC-022` | VLM 단독 person·organization·location·incident 해석은 verified evidence가 아니어야 한다. |
| `FR-PRC-023` | model, prompt, schema version이 다른 출력은 같은 산출물로 취급해서는 안 된다. |

### 5.5 OCR

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-030` | OCR은 scene의 모든 추출 frame을 대상으로 실행해야 한다. |
| `FR-PRC-031` | 각 관측은 verbatim text, normalized text, confidence, bounding box, frame ID를 보존해야 한다. |
| `FR-PRC-032` | frame 간 중복 병합 후에도 원본 관측으로 역추적할 수 있어야 한다. |
| `FR-PRC-033` | OCR confidence 기준 미달 값은 검색 후보로 사용할 수 있어도 `unverified`이며 hard 근거가 될 수 없다. |
| `FR-PRC-034` | VLM visible_text와 OCR이 충돌할 때 OCR 원문을 덮어쓰지 않고 두 evidence를 구분해야 한다. |

OCR 병합 규칙과 verified confidence 값은 개발셋으로 동결하는 Gate B 항목이다.

### 5.6 Transcript·ASR

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-040` | 유효한 timestamp transcript 또는 CC가 있으면 ASR보다 우선해야 한다. |
| `FR-PRC-041` | 위 source가 없으면 사용자 toggle 없이 자동 ASR을 실행해야 한다. |
| `FR-PRC-042` | ASR segment는 start, end, verbatim text, confidence, model version을 가져야 한다. |
| `FR-PRC-043` | scene에는 시간상 겹치는 segment만 연결해야 한다. |
| `FR-PRC-044` | ASR 실패는 VLM·OCR·index 가능한 텍스트 경로를 폐기하지 않아야 한다. |
| `FR-PRC-045` | 평가 cohort에서 WER가 40%를 초과하면 해당 ASR 신호의 boost를 낮추고 변경을 search version에 기록해야 한다. clip별 강등 기준은 Gate B에서 동결한다. |

### 5.7 Entity·Tag

P0 tag type은 `person | organization | location | facility | loose`다.

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-050` | tag 후보마다 type, canonical/display value, source, confidence, evidence와 verification 상태를 저장해야 한다. |
| `FR-PRC-051` | 사용자 입력·원본 metadata·CC·OCR·기준 충족 ASR·승인된 rule을 출처별로 구분해야 한다. |
| `FR-PRC-052` | VLM/LLM 단독 후보는 soft unverified 신호로만 사용할 수 있고 hard filter 근거가 될 수 없다. |
| `FR-PRC-053` | scene_tag와 clip_tag 연결은 독립 안정 ID를 가져야 하며 배열 위치를 식별자로 사용해서는 안 된다. |
| `FR-PRC-054` | P0 UI는 tag 원문 교정·add/remove와 field reindex를 제공하지 않아야 한다. |
| `FR-PRC-055` | 자동 추출 tag는 processing version이 연결된 scene_tag로 저장해야 한다. clip_tag는 사용자 입력·원본 metadata의 clip-level tag에만 사용하고 자동 scene tag의 clip 집계는 파생값으로 계산해야 한다. |

### 5.8 Indexing·재처리

| ID | 요구사항 |
| --- | --- |
| `FR-PRC-060` | 검색 index 문서는 caption, OCR, scene transcript, 허용 tag, 두 날짜, shot/scene type, provenance·verification 요약을 포함할 수 있어야 한다. |
| `FR-PRC-061` | index 문서는 `index_version`, source record version, embedding model version과 연결되어야 한다. |
| `FR-PRC-062` | index 전체를 PostgreSQL 정본과 asset manifest에서 재구축할 수 있어야 한다. |
| `FR-PRC-063` | 재처리는 새 processing version·scene ID 집합과 비활성 index generation을 만들고, generation 검증 성공 뒤에만 PostgreSQL의 active run/index pointer를 전환해야 한다. 외부 index와 DB 사이의 단일 transaction을 가정해서는 안 된다. |
| `FR-PRC-064` | 교체된 scene을 참조하는 active exclude_scene은 `stale`로 비활성화하고 새 scene에 자동 이전하지 않아야 한다. |
| `FR-PRC-065` | 과거 scene·snapshot·문의 record를 in-place로 새 경계에 맞춰 바꾸지 않아야 한다. |
| `FR-PRC-066` | 처리 산출물 commit과 해당 scene의 index `pending` 등록은 같은 DB transaction 또는 동등한 transactional outbox로 묶어 index 작업 유실을 막아야 한다. |
| `FR-PRC-067` | index generation은 `building→ready→active` 또는 `building→failed`로 전이하며, active generation은 P0 검색 scope에서 최대 1개여야 한다. |
| `FR-PRC-068` | generation 활성화 전에 source manifest hash, 문서 수, scene/version 대응과 index configuration을 검증해야 한다. |
| `FR-PRC-069` | 검색 실행은 시작 시 active index version을 캡처하고 실행 도중 alias·pointer가 바뀌어도 같은 version만 조회해야 한다. |
| `FR-PRC-070` | 새 generation build·검증·pointer 전환이 실패하면 기존 active generation과 active pipeline run을 유지하고 실패 generation을 검색에 노출하지 않아야 한다. |
| `FR-PRC-071` | 외부 index 쓰기와 pointer 전환은 transactional outbox, idempotent consumer, version filter와 reconciliation job으로 복구 가능해야 한다. |
| `FR-PRC-072` | generation promotion transaction은 새 active run/generation에 속하지 않는 scene을 참조하는 active exclude_scene을 `stale`로 전환하고 lifecycle history를 함께 기록해야 한다. |
| `FR-PRC-073` | superseded generation의 문서와 searchable membership은 그 version을 캡처한 running execution이 0이 될 때까지 조회 가능하게 유지하고, drain 완료 뒤에만 state supersede와 물리 GC를 수행해야 한다. |

### 5.9 처리 수용 기준

- `AC-PRC-001`: 짧은 유효 clip이 scene 경계, 복수 keyframe, thumbnail과 index 문서를 만든다.
- `AC-PRC-002`: VLM schema invalid 시 해당 출력이 field에 섞이지 않고 다른 stage는 정책대로 계속된다.
- `AC-PRC-003`: 제공 timestamp transcript가 ASR보다 우선하고 겹치는 scene에만 연결된다.
- `AC-PRC-004`: transcript가 없으면 ASR이 실행되며 실패해도 VLM·OCR 결과를 사용할 수 있다.
- `AC-PRC-005`: 사전검수나 `review_required` 없이 searchable index가 생성되고 unverified field는 표시된다.
- `AC-PRC-006`: 재처리 후 과거 문의가 새 scene을 가리키지 않고 이전 exclude override가 stale 된다.
- `AC-PRC-007`: Event 관련 table·candidate·assignment·stage가 생성되지 않는다.
- `AC-PRC-008`: 새 index generation build 또는 활성화가 실패해도 직전 active generation으로 검색되며 새 문서와 구버전 문서가 한 execution에 섞이지 않는다.
- `AC-PRC-009`: 동일 outbox event 재처리 뒤에도 scene별 index state와 generation manifest가 중복되지 않고 PostgreSQL 정본으로 같은 generation을 재구축할 수 있다.
- `AC-PRC-010`: ready clip 재처리 중에는 기존 version 검색이 계속되고 UI는 serving ready와 latest run running을 함께 표시한다.
- `AC-PRC-011`: ready clip 재처리 실패 시 기존 active run·generation·serving ready가 유지되고 latest run failed 경고와 retry가 표시된다.
- `AC-PRC-012`: generation promotion과 동시에 이전 scene의 active exclude가 stale/history로 기록되고 새 scene에는 자동 이전되지 않는다.
- `AC-PRC-013`: promotion 직전에 시작한 검색은 캡처한 구 generation으로 완료되고, 마지막 running execution 종료 전에는 해당 문서·membership이 GC되지 않는다.

---

## 6. Query Resolver와 Pinned Override

### 6.1 Exact query scope

동일 검색은 화면 문자열만으로 판정하지 않는다.

```
canonical_query = NFKC(query)
                  → 앞뒤 공백 제거
                  → 연속 공백 하나로 축약
                  → Latin 소문자화
                  → 한국어·숫자·의미 있는 문장부호 보존

canonical_filters = 명시 filter를 key 정렬하고 순서 의미가 없는 다중값을 정렬한 canonical JSON
canonical_scope_payload = normalization_version + canonical_query + canonical_filters의 결정적 직렬화
query_fingerprint = SHA-256(canonical_scope_payload)
```

| ID | 요구사항 |
| --- | --- |
| `FR-QRY-001` | 원문 query와 canonical query를 모두 보존해야 한다. |
| `FR-QRY-002` | query fingerprint에는 사용자가 직접 선택한 방송일·촬영일·기타 명시 filter가 포함되어야 한다. |
| `FR-QRY-003` | hash 일치 후 canonical payload 전체도 비교해 hash만을 유일한 동등성 근거로 사용하지 않아야 한다. |
| `FR-QRY-004` | normalization 변경 시 새 `normalization_version`을 부여해야 한다. |
| `FR-QRY-005` | P0는 단건 검색이며 이전 session이나 대화 turn을 fingerprint와 resolver 입력에 암묵적으로 포함하지 않아야 한다. |
| `FR-QRY-006` | 값이 없는 선택 filter의 omitted와 `null`은 같은 canonical 표현으로 정규화하고 빈 문자열은 validation 단계에서 제거하거나 거부해야 한다. |

### 6.2 Resolution schema

권장 논리 schema는 다음과 같다. 물리 JSON Schema는 같은 의미와 validation 조건을 보존한다.

```json
{
  "schema_version": "...",
  "intent": "scene_search | recent_scene | unknown",
  "date_windows": [
    {
      "field": "broadcast_date | filming_date",
      "start": "YYYY-MM-DD",
      "end_exclusive": "YYYY-MM-DD",
      "origin": "explicit_filter | explicit_query | inferred",
      "query_span": {"start": 0, "end": 4},
      "confidence": 0.0
    }
  ],
  "incident_names": [
    {
      "value": "...",
      "origin": "explicit_query | inferred",
      "query_span": {"start": 5, "end": 9},
      "confidence": 0.0
    }
  ],
  "entities": [
    {
      "type": "person | organization",
      "value": "...",
      "origin": "explicit_query | inferred",
      "query_span": null,
      "confidence": 0.0
    }
  ],
  "locations": [
    {
      "type": "location | facility",
      "value": "...",
      "origin": "explicit_query | inferred",
      "query_span": null,
      "confidence": 0.0
    }
  ],
  "expanded_terms": [],
  "confidence": 0.0
}
```

| ID | 요구사항 |
| --- | --- |
| `FR-QRY-010` | resolver는 날짜 window, incident name, entity, location, expanded term, intent를 표현해야 한다. |
| `FR-QRY-011` | `explicit_query` anchor는 원문 substring과 일치하는 유효 query span을 가져야 하며 span 검증 실패 시 `inferred`로 강등해야 한다. |
| `FR-QRY-012` | explicit UI filter는 resolver가 변경할 수 없고 effective resolution 조합 시 최우선이어야 한다. |
| `FR-QRY-013` | bare 연도·날짜는 broadcast date, 촬영 의미가 원문에 명시된 날짜는 filming date로 구조화해야 한다. |
| `FR-QRY-014` | resolver가 원문에 없는 사건·인물·날짜를 explicit anchor로 만들지 못하게 schema와 후처리 validation을 적용해야 한다. |
| `FR-QRY-015` | 별도 결과 부족 LLM rewrite를 실행하지 않고 동의어·관련 용어 확장은 `expanded_terms` 한 곳에서만 관리해야 한다. |
| `FR-QRY-016` | 사람·기관은 `entities`, 장소·시설은 `locations` 한 경로에만 정규화하고 같은 semantic value를 두 배열에 중복 표현하거나 이중 scoring해서는 안 된다. |

### 6.3 Resolver 실행·Fallback

```
normalize query + filters
→ override lookup
   ├─ 일반 검색: exact active override만 조회
   ├─ reviewer replay: 같은 inquiry의 pending_verification candidate를 명시 적용
   ├─ compatible resolution_patch: 전체 resolution으로 사용
   └─ 없음: LLM resolver
→ JSON schema + query span 검증
   ├─ 성공: validated resolution
   └─ timeout/schema/rate/network 오류: raw_query_bm25 fallback
→ immutable query_resolution_snapshot
```

| ID | 요구사항 |
| --- | --- |
| `FR-QRY-020` | resolution patch가 없을 때만 resolver를 호출해야 한다. |
| `FR-QRY-021` | patch는 resolver output 전체를 대체하며 부분 merge해서는 안 된다. explicit filter는 patch 대상이 아니다. |
| `FR-QRY-022` | resolver timeout, schema invalid, rate limit, network 오류는 검색 전체 실패 대신 raw query BM25 fallback으로 이어져야 한다. |
| `FR-QRY-023` | P0 동기 resolver retry는 하지 않는다. timeout 값은 검색 p95 목표를 만족하도록 benchmark에서 동결한다. |
| `FR-QRY-024` | fallback은 비어 있는 validated resolution과 raw query를 사용하고 UI·snapshot에 degraded reason을 기록해야 한다. |
| `FR-QRY-025` | 검색 계산이 결과를 만들었지만 resolution/result snapshot commit이 실패하면 persisted result ID가 없는 ephemeral degraded 결과를 반환해야 한다. 사전에 저장한 session/execution ID는 유지하고 문의·override 생성은 비활성화하며 감사 기록 누락을 표시해야 한다. BM25/index도 사용할 수 없으면 검색 실패로 처리한다. |
| `FR-QRY-026` | active override 조회 자체가 실패하면 검수 결정을 조용히 우회하지 말고 검색을 실패 처리해 재시도를 안내해야 한다. |

### 6.4 Query resolution snapshot

각 검색 실행은 다음 값을 불변 저장한다.

- session·execution ID, actor, original/canonical query, canonical filters, fingerprint
- normalization, resolution schema, model, prompt version
- source: `resolver | resolution_patch | raw_fallback`
- 전체 validated resolution 또는 fallback 상태
- explicit anchor validation 결과
- applied override ID·version
- started/finished timestamp, latency, failure category

snapshot은 다음 검색의 cache·권위·resolver 입력으로 재사용하지 않는다.

### 6.5 Pinned override schema

```
pinned_override
  override_id
  query_fingerprint
  canonical_query
  canonical_filters_json
  canonical_scope_payload
  normalization_version
  action                    resolution_patch | exclude_scene
  target_scene_id           nullable
  target_scene_processing_version nullable
  resolution_value_json     nullable
  resolution_schema_version nullable
  reason
  reviewer_id
  version
  supersedes_override_id    nullable
  source_inquiry_id         P0 required
  lifecycle_status          pending_verification | active | superseded | revoked | stale
  status_reason             nullable
  created_at
  verified_at               nullable
  activated_at              nullable
  deactivated_at            nullable
```

| action | 필수 | 금지 | 적용 위치 |
| --- | --- | --- | --- |
| `resolution_patch` | resolution value·schema version | target scene | resolver 호출 전 전체 교체 |
| `exclude_scene` | target scene ID·processing version | resolution value | ranking·verified conflict guard 후 |

### 6.6 Override lifecycle·무결성

| ID | 요구사항 |
| --- | --- |
| `FR-OVR-001` | 한 canonical query scope에는 active resolution patch가 최대 1개여야 한다. fingerprint는 조회 가속용이며 uniqueness는 full canonical scope로 판정해야 한다. |
| `FR-OVR-002` | 한 canonical scope·scene ID·scene processing version 조합에는 active exclude가 최대 1개여야 하며 서로 다른 scene exclude는 복수 허용해야 한다. |
| `FR-OVR-003` | override 수정은 in-place overwrite가 아니라 `pending_verification` 새 version 생성으로 시작해야 한다. 이 단계에서 기존 active version을 비활성화해서는 안 된다. |
| `FR-OVR-004` | override는 hard delete하지 않고 reviewer, reason, supersedes 관계와 비활성 사유를 보존해야 한다. |
| `FR-OVR-005` | patch schema version이 현재 validator와 호환되지 않으면 patch를 적용하지 않고 stale 처리한 뒤 명시적으로 resolver/fallback 경로를 기록해야 한다. |
| `FR-OVR-006` | target scene이 active processing version에서 검색 불가능해지면 exclude를 stale 처리하고 새 scene에 자동 매핑하지 않아야 한다. |
| `FR-OVR-007` | editor는 override를 생성·변경·해제할 수 없어야 한다. |
| `FR-OVR-008` | override 적용 여부와 ID·version을 결과 snapshot과 reviewer replay에서 확인할 수 있어야 한다. |
| `FR-OVR-009` | exact matching 외 semantic·유사 query 적용을 P0에서 수행해서는 안 된다. |
| `FR-OVR-010` | P0에서 사람이 만드는 새 override version은 `reviewing` inquiry에서만 생성하고 source inquiry를 필수 연결해야 한다. 시스템의 scene stale 비활성화는 예외다. |
| `FR-OVR-011` | pending candidate는 같은 inquiry의 reviewer replay에서만 명시 적용할 수 있고 일반 검색의 active lookup에는 노출해서는 안 된다. |
| `FR-OVR-012` | replay 검증 성공 후 candidate 활성화, 이전 active version supersede, inquiry terminal 전이와 status history를 한 DB transaction으로 commit해야 한다. |
| `FR-OVR-013` | replay·snapshot·검증 또는 promotion transaction 실패 시 inquiry는 `reviewing`에 남고 candidate는 pending 또는 revoked여야 하며 기존 active version은 유지되어야 한다. |
| `FR-OVR-014` | reviewer는 expected version과 사유를 사용해 active override를 revoke할 수 있어야 한다. revoke는 새 rule을 만들지 않으며 append-only audit와 lifecycle history를 남겨야 한다. |
| `FR-OVR-015` | exclude candidate promotion 직전에 target scene/version이 현재 active run에 속하고 active generation에서 searchable인지 다시 검증해야 한다. 불일치하면 candidate를 stale 또는 revoked로 남기고 inquiry를 reviewing으로 유지해야 한다. |

### 6.7 수용 기준

- `AC-QRY-001`: 같은 문구라도 명시 filter가 다르면 서로 다른 fingerprint와 override scope를 가진다.
- `AC-QRY-002`: whitespace·Latin 대소문자 차이만 있는 query는 같은 normalization version에서 같은 fingerprint가 된다.
- `AC-QRY-003`: resolver timeout/schema 오류가 raw BM25 degraded 결과로 이어지고 이유가 저장·표시된다.
- `AC-QRY-004`: resolution patch 적용 시 resolver가 호출되지 않고 explicit UI filter는 유지된다.
- `AC-QRY-005`: 같은 장소·시설 표현이 entities와 locations에 동시에 들어온 resolver output은 validation에서 거부되거나 locations 한 건으로 정규화되며 점수는 한 번만 적용된다.
- `AC-OVR-001`: 동일 canonical scope에 두 active patch를 만들려는 동시 요청 중 하나만 성공한다.
- `AC-OVR-002`: 새 patch version은 pending 상태에서 일반 검색에 적용되지 않고, 성공 replay 후에만 이전 active version을 supersede하며 과거 이력이 조회된다.
- `AC-OVR-003`: reprocessing 후 과거 scene exclude가 새 scene에 적용되지 않고 stale로 남는다.
- `AC-OVR-004`: 유사하지만 exact fingerprint가 다른 query에는 override가 적용되지 않는다.
- `AC-OVR-005`: pending candidate replay 또는 promotion이 실패해도 기존 active override가 계속 일반 검색에 적용되고 inquiry는 resolved로 닫히지 않는다.
- `AC-OVR-006`: active override revoke 후 같은 exact query가 해당 rule을 사용하지 않으며 과거 payload·reviewer·사유는 조회된다.
- `AC-OVR-007`: exclude replay 뒤 generation이 바뀌어 target이 active/searchable하지 않으면 candidate가 active로 승격되지 않고 inquiry가 reviewing에 남는다.

---

## 7. 검색·False-hit Guard

### 7.1 검색 실행 순서

```
effective resolution 확정
→ BM25 candidate retrieval
→ dense candidate retrieval
→ structured soft score
→ RRF + configured boost로 pre-guard rank
→ explicit anchor별 3값 판정
→ verified conflict hard exclusion
→ exact exclude_scene 적용
→ 원래 순서를 보존하며 다음 유효 후보로 Top 10 보충
→ post-guard result snapshot
→ 결과 반환
```

### 7.2 Retrieval·Ranking

| ID | 요구사항 |
| --- | --- |
| `FR-SRH-001` | BM25는 caption, OCR verbatim/normalized text, scene transcript, 허용 tag를 lexical backbone으로 검색해야 한다. |
| `FR-SRH-002` | dense text embedding은 표현 차이를 보완하는 후보 채널이며 exact term과 verified guard를 대체해서는 안 된다. |
| `FR-SRH-003` | 날짜·incident·entity·location·shot/scene type은 resolution에서 활성화된 축만 점수에 사용해야 한다. |
| `FR-SRH-004` | 값 없음은 해당 축 0점이며 후보마다 분모를 줄여 상대적으로 유리하게 만들지 않아야 한다. |
| `FR-SRH-005` | BM25·dense rank는 RRF로 결합하고 candidate count, RRF constant, field boost를 `search_version`에 저장해야 한다. |
| `FR-SRH-006` | dense 장애 시 BM25+사용 가능한 구조화 신호로 degraded 검색하고 누락 channel을 표시해야 한다. |
| `FR-SRH-007` | 일반 의미 query에 최신순을 강제해서는 안 된다. `recent_scene` intent만 broadcast date 최신성을 soft tie-break로 사용할 수 있다. |
| `FR-SRH-008` | `shot_type=b_roll`은 관련성 점수 뒤의 보조 boost이며 관련 없는 B-roll을 verified match보다 앞세우면 안 된다. |
| `FR-SRH-009` | season·weather·crowd density는 P0 soft 신호이며 hard filter로 사용하지 않아야 한다. |
| `FR-SRH-010` | ranking 설정이 바뀌면 새 search version을 부여하고 기존 평가 artifact를 덮어쓰지 않아야 한다. |

일반 reference text는 기본 BM25 field에서 제외한다. 개발셋에서 유용성과 누수 위험을 측정해 포함할 경우 별도 search version으로만 활성화한다.

### 7.3 False-hit 3값 판정

각 explicit anchor와 후보의 동일 field evidence를 다음과 같이 판정한다.

| 상태 | 조건 | 처리 |
| --- | --- | --- |
| `verified_match` | 허용 출처의 verified value가 anchor와 일치 | boost |
| `unknown_or_unverified` | field 없음, confidence·출처 부족, 의미 불명확 | 유지, boost 없음, 미검증 표시 |
| `verified_conflict` | 허용 출처의 verified same-field value가 anchor와 충돌 | 날짜 또는 승인된 사건명 규칙이면 hard exclude 가능 |

| ID | 요구사항 |
| --- | --- |
| `FR-SRH-020` | hard exclusion은 original query/filter의 explicit anchor와 candidate의 verified same-field conflict가 모두 있을 때만 허용해야 한다. |
| `FR-SRH-021` | AI inferred anchor와 LLM 단독 candidate 해석은 hard exclusion에 사용할 수 없어야 한다. |
| `FR-SRH-022` | explicit 날짜가 있어도 candidate 날짜가 없거나 unverified면 결과를 유지해야 한다. |
| `FR-SRH-023` | unqualified explicit date는 broadcast date와, 촬영 의미가 명시된 날짜는 verified filming date와 비교해야 한다. |
| `FR-SRH-024` | person·organization·location·facility mismatch는 verified여도 P0에서 hard exclude하지 않아야 한다. |
| `FR-SRH-025` | 사건명 hard exclusion은 source-backed label과 승인된 deterministic conflict rule 또는 active human exclude가 있을 때만 허용해야 한다. |
| `FR-SRH-026` | 각 후보의 anchor 판정과 사용 evidence ID를 snapshot·결과 설명에 기록해야 한다. |

### 7.4 Exclude와 Top 10 보충

| ID | 요구사항 |
| --- | --- |
| `FR-SRH-030` | verified conflict guard 후 동일 fingerprint의 active exclude_scene을 적용해야 한다. |
| `FR-SRH-031` | 제외가 발생하면 pre-guard rank 순서를 유지하며 다음 유효 후보를 가져와 최대 10개를 반환해야 한다. |
| `FR-SRH-032` | 유효 후보가 부족하면 결과 수를 줄일 수 있으나 제외된 scene을 채우기 위해 다시 포함해서는 안 된다. |
| `FR-SRH-033` | 반환 결과 수가 10보다 적은 사실과 이유를 snapshot에 기록해야 한다. |
| `FR-SRH-034` | false-hit 지표를 낮추기 위해 candidate pool이나 결과 수를 임의 축소해서는 안 된다. 평가에서 result coverage를 함께 보고해야 한다. |

### 7.5 Result snapshot

각 검색 실행은 최소 다음을 immutable snapshot으로 보존한다.

- search session·execution, query fingerprint, resolution snapshot ID
- search/index/config version과 channel 성공·실패
- pre-guard 상위 후보의 scene ID·version·rank·score breakdown
- anchor별 match/unverified/conflict, evidence ID, exclusion reason
- 적용한 exclude override ID·version
- 최종 Top 10 scene ID·rank와 결과 부족 여부

snapshot은 문의 context, replay, 회귀 평가에 사용하지만 다음 검색의 ranking 입력으로 재사용하지 않는다.

### 7.6 검색 결과 계약

각 scene 결과는 다음 값을 반환한다.

| 그룹 | 필드 |
| --- | --- |
| 식별 | session ID, execution ID, persisted result ID, scene ID/processing version, clip ID, rank |
| 카드 | thumbnail, title/display name, broadcast date?, filming date?, shot/scene type |
| 구간 | start, end |
| 근거 | matched field/value, source, evidence ID, verification 상태 |
| 안전 | unverified anchors, guard 상태, degraded channel·reason |
| override | human rule 적용 여부와 사용자용 설명; 내부 ID는 reviewer detail에서 제공 |
| 재생 | ID 기반 media URL과 scene start |

위 계약은 snapshot commit이 완료된 persisted 결과 기준이다. snapshot commit 실패 시에는 scene·clip·rank·카드 정보는 보여 주되 result ID를 `null`로, `snapshot_persisted=false`, `inquiry_available=false`로 반환하는 ephemeral degraded 예외를 사용한다.

### 7.7 수용 기준

- `AC-SRH-001`: 날짜가 없는 관련 scene은 explicit 날짜 query에서도 제거되지 않고 미검증으로 표시된다.
- `AC-SRH-002`: verified 방송일 충돌 scene은 explicit 방송일/연도 query에서 제외된다.
- `AC-SRH-003`: unverified 촬영일은 filming date hard conflict 근거가 되지 않는다.
- `AC-SRH-004`: 인물·기관·장소 mismatch만으로 결과가 제외되지 않는다.
- `AC-SRH-005`: resolver inferred 날짜는 hard filter가 되지 않는다.
- `AC-SRH-006`: guard·exclude로 빠진 결과는 다음 유효 후보로 보충되고 pre/post rank가 재현된다.
- `AC-SRH-007`: dense 장애 시 BM25 degraded 결과와 경고가 제공된다.
- `AC-SRH-008`: 동일 fixture와 search/index version에서 RRF·guard 결과가 재현된다.
- `AC-SRH-009`: frame image embedding과 Event assignment를 검색 채널로 사용하지 않는다.
- `AC-SRH-010`: explicit 사건명과 승인 rule이 있는 source-backed verified conflict scene만 hard 제외된다.
- `AC-SRH-011`: 사건명이 없거나 unverified이거나 LLM-only 추정인 scene은 explicit 사건명 query에서도 유지되고 미검증으로 표시된다.

---

## 8. 결과 카드와 Preview

### 8.1 결과 카드

| ID | 요구사항 |
| --- | --- |
| `FR-RES-001` | 결과는 clip이 아니라 scene 단위 카드로 표시해야 한다. |
| `FR-RES-002` | 카드에는 thumbnail, clip display name, nullable 방송일·촬영일, shot/scene type, scene start/end를 표시해야 한다. |
| `FR-RES-003` | 어떤 caption·OCR·transcript·tag·날짜가 query와 일치했는지 field·value·source를 표시해야 한다. |
| `FR-RES-004` | 정보 없음과 unverified를 확정 불일치와 구분하고 색상만이 아닌 `미상`, `미검증` 텍스트/배지로 표시해야 한다. |
| `FR-RES-005` | resolver·dense·snapshot 등 degraded 상태와 사용한 fallback을 결과 상단에 표시해야 한다. |
| `FR-RES-006` | 사람이 고정한 규칙이 적용됐으면 `검수 규칙 적용`을 표시하되 일반 editor 화면에 내부 reviewer 정보·reason 전체를 노출할 필요는 없다. |
| `FR-RES-007` | 카드와 Preview에 “송출 전 내용·최신성 최종 확인 필요”를 상시 고지해야 한다. |
| `FR-RES-008` | 각 카드에는 `이상해요` 동작이 하나만 있어야 하며 긍정/부정·구조화 교정 UI를 함께 제공하지 않아야 한다. |

### 8.2 Preview

| ID | 요구사항 |
| --- | --- |
| `FR-RES-010` | 결과 선택 시 원본 MP4를 scene `start_time`부터 재생해야 한다. |
| `FR-RES-011` | Preview에는 scene start/end와 전체 clip 재생 위치를 확인할 수 있어야 한다. |
| `FR-RES-012` | 기본 동작은 scene end 이후 계속 재생하되 scene 경계를 표시하고 `구간 다시 재생`을 제공한다. |
| `FR-RES-013` | 브라우저에 server 절대 경로를 노출하지 않고 clip/media ID endpoint로 영상을 제공해야 한다. |
| `FR-RES-014` | media endpoint는 seek와 HTTP Range 요청을 지원해야 한다. |
| `FR-RES-015` | 파일 누락, codec 거부, Range/seek 실패를 구분한 한국어 오류와 재시도를 제공해야 한다. |
| `FR-RES-016` | 미검증·degraded·출처 상태는 Preview를 열어도 확인 가능해야 한다. |

### 8.3 수용 기준

- `AC-RES-001`: 카드에서 두 nullable 날짜와 미상·미검증 차이가 구분된다.
- `AC-RES-002`: 카드 선택 후 첫 재생 위치가 허용 오차 내 scene start와 일치한다.
- `AC-RES-003`: 검색 근거가 OCR·transcript·caption·tag 중 무엇인지 확인할 수 있다.
- `AC-RES-004`: degraded fallback을 정상 전체 기능처럼 표시하지 않는다.
- `AC-RES-005`: source asset의 실제 절대 경로가 UI나 일반 API 응답에 노출되지 않는다.

---

## 9. 문의·검수·Reactive Override

### 9.1 문의 생성

| ID | 요구사항 |
| --- | --- |
| `FR-FBK-001` | editor는 각 결과 scene에서 `이상해요` 한 버튼으로 문의를 시작할 수 있어야 한다. |
| `FR-FBK-002` | 선택한 scene·검색 context가 유효하면 comment 없이도 제출할 수 있어야 한다. |
| `FR-FBK-003` | 제출 중 중복 실행을 막고 성공 시 `inquiry_id`, `pending`, 접수 시각을 표시해야 한다. |
| `FR-FBK-004` | 문의 접수만으로 현재 결과를 숨기거나 정본·index·다른 session의 ranking을 변경해서는 안 된다. |
| `FR-FBK-005` | snapshot 저장이 실패한 execution에서는 재현 가능한 문의를 만들 수 없으므로 버튼을 비활성화하고 이유를 표시해야 한다. |

### 9.2 자동 첨부 Context

서버는 클라이언트가 임의로 보낸 rank·근거를 신뢰하지 않고 저장된 execution/result에서 다음 context를 구성한다.

- session·execution·result·scene·clip ID와 scene processing version
- original/canonical query, explicit filters, fingerprint
- 전체 query resolution snapshot과 source
- 선택 scene의 당시 rank, match evidence, verification·guard 상태
- pre/post guard Top 10 result snapshot
- applied override ID·version, degraded channel·reason
- search/index/model/prompt/schema/normalization/guard version
- editor ID, 선택적 comment, 접수 시각

P0는 채팅형 multi-turn UI가 아니므로 “채팅 기록”은 위 **단일 search session과 replay 실행 기록**을 뜻한다. 전체 transcript·OCR·frame을 문의 JSON에 복제하지 않고 필요한 evidence ID·짧은 excerpt·asset reference만 포함한다.

| ID | 요구사항 |
| --- | --- |
| `FR-FBK-010` | 문의는 저장된 `search_result_id`를 참조해야 하며 해당 execution의 결과가 아닌 scene은 거부해야 한다. |
| `FR-FBK-011` | context snapshot은 생성 후 수정하지 않아야 한다. |
| `FR-FBK-012` | 같은 actor·idempotency key의 반복 제출은 같은 inquiry를 반환해야 한다. |
| `FR-FBK-013` | 해결 뒤 동일 문제가 다시 발생하면 새 idempotency key로 새 문의를 생성할 수 있어야 한다. |

### 9.3 내부 검수 Queue와 상태

```
pending → reviewing → resolved
                    ├→ dismissed
                    └→ deferred
```

| 상태 | 의미 | 필수 기록 |
| --- | --- | --- |
| `pending` | 접수, 미착수 | inquiry·context·created_at |
| `reviewing` | reviewer가 진단 중 | reviewer_id·started_at·expected version |
| `resolved` | P0 candidate replay와 active promotion 완료 | resolution type·active override·verification execution |
| `dismissed` | 조치 불필요·재현 안 됨 | reason·reviewer·closed_at |
| `deferred` | 유효하지만 P1 피처 교정이 필요 | `deferred_p1`, target field 설명·reason |

`pending`에서 바로 `resolved/dismissed/deferred`로 건너뛰지 않고 reviewer claim을 기록한다. 같은 inquiry를 두 reviewer가 동시에 처리하지 못하도록 optimistic row version을 사용한다.

### 9.4 P0 검수 결과

| 진단 | P0 조치 | 문의 완료 조건 |
| --- | --- | --- |
| 질의 날짜·사건명·entity 해석이 잘못됨 | full `resolution_patch` | schema-valid pending candidate + exact replay 확인 + active promotion |
| 특정 scene만 해당 exact 검색에 부적합 | `exclude_scene` | pending candidate + replay에서 target 미노출 확인 + active promotion |
| 시스템 동작이 맞거나 재현 불가 | `no_action` | 근거·reason 기록 후 dismissed |
| Entity·OCR·Caption 등 피처 자체가 틀림 | `deferred_p1` | P1 이관 target·reason 기록; DB·index 미변경 |

| ID | 요구사항 |
| --- | --- |
| `FR-FBK-020` | reviewer action은 `resolution_patch`, `exclude_scene`, `no_action`, `deferred_p1` 중 하나여야 한다. |
| `FR-FBK-021` | resolution patch는 현재 resolution JSON Schema로 검증되고 inquiry fingerprint와 연결되어야 한다. |
| `FR-FBK-022` | exclude scene은 문의가 참조한 scene ID·processing version과 같은 target이어야 한다. 다른 scene을 조치하려면 별도 근거·문의 연결이 필요하다. |
| `FR-FBK-023` | pending candidate 저장 후 서버는 original query·filter로 reviewer replay execution을 만들고 그 candidate ID를 명시 적용해야 한다. |
| `FR-FBK-024` | replay 실패·snapshot 실패·candidate 미적용·검증 실패·promotion 실패이면 inquiry를 resolved로 전환해서는 안 된다. |
| `FR-FBK-025` | no_action·deferred에는 comment와 별개의 reviewer reason이 필수다. |
| `FR-FBK-026` | 문의·검수 기록은 override version history를 삭제하거나 수정하지 않아야 한다. |
| `FR-FBK-027` | override action 저장 시 inquiry는 `reviewing`을 유지하고, replay 검증과 promotion이 완료된 transaction에서만 `resolved`로 전환해야 한다. |
| `FR-FBK-028` | deferred_p1은 `entity, ocr, caption, date, other` 중 하나의 field, target/evidence ID와 설명을 담은 deferred target을 필수로 기록해야 하며 다른 action에는 이 값을 허용하지 않아야 한다. 참조 ID는 inquiry의 clip·scene·evidence context 안에 있어야 한다. |

### 9.5 문의가 변경하지 않는 것

| ID | 요구사항 |
| --- | --- |
| `FR-FBK-030` | 문의 접수는 clip·scene·tag·OCR·caption·date를 변경하지 않는다. |
| `FR-FBK-031` | P0는 generic field override, correction request, correction reindex job을 생성하지 않는다. |
| `FR-FBK-032` | 문의와 override를 모델 학습·개인화·semantic cache seed·전역 weight 변경에 사용하지 않는다. |
| `FR-FBK-033` | exact override는 현재 index를 수정하지 않고 query time에만 적용한다. |
| `FR-FBK-034` | deferred inquiry는 수정 완료를 뜻하지 않으며 editor에게 자동 개선을 약속해서는 안 된다. |

### 9.6 수용 기준

- `AC-FBK-001`: comment가 없어도 유효 result context로 문의가 한 번 생성된다.
- `AC-FBK-002`: 다른 execution의 scene/result ID 조합은 거부된다.
- `AC-FBK-003`: 문의 전후 metadata·index·현재 결과·다른 session의 순위가 바뀌지 않는다.
- `AC-FBK-004`: resolution patch 문의는 pending candidate의 exact replay 적용과 active promotion을 확인한 뒤 resolved 된다.
- `AC-FBK-005`: exclude 문의 replay에서 대상 scene이 빠지고 다음 유효 scene이 보충된 뒤 candidate가 active로 promotion 된다.
- `AC-FBK-006`: 피처 오류는 deferred로 저장되지만 field override·reindex가 생성되지 않는다.
- `AC-FBK-007`: 두 reviewer의 동시 action 중 하나만 expected version 검증을 통과한다.
- `AC-FBK-008`: 외부 ticket 서비스로 inquiry context가 전송되지 않는다.

---

## 10. 데이터 요구사항과 논리 모델

### 10.1 저장 원칙

| ID | 요구사항 |
| --- | --- |
| `DR-CMN-001` | PostgreSQL을 clip·scene·evidence·상태·search·override·inquiry의 정본으로 사용해야 한다. |
| `DR-CMN-002` | 구조가 가변적인 model output, resolution, result·inquiry context, score breakdown은 schema version이 있는 JSONB로 저장할 수 있다. |
| `DR-CMN-003` | BM25·dense 검색 저장소는 정본이 아니며 PostgreSQL과 asset manifest에서 재생성 가능해야 한다. |
| `DR-CMN-004` | source evidence, resolution snapshot, result snapshot과 override history는 append-only여야 한다. |
| `DR-CMN-005` | 안정 ID는 최소 clip, run, stage, scene, frame, segment, evidence, tag link, session, execution, result, override, inquiry에 부여해야 한다. |
| `DR-CMN-006` | P0 schema에는 Event 관련 table·column·FK, review item, field override, correction request, correction reindex job을 두지 않아야 한다. |

### 10.2 Media·Pipeline

```
media_asset
  asset_id PK
  asset_type                   source_video | frame | thumbnail | transcript_file
  storage_key                  internal, media-root relative
  content_hash
  mime_type, size_bytes
  created_at

clip
  clip_id PK
  source_type                  broadcast
  source_asset_id              FK media_asset
  media_content_hash
  title                        nullable
  broadcast_date               nullable
  filming_date                 nullable
  license_info_json
  external_processing_allowed  yes | no | unknown
  transcript_source            provided | cc | asr | none
  reference_text               nullable
  serving_status               queued | processing | ready | failed
  active_pipeline_run_id        nullable FK
  row_version
  created_at, updated_at

pipeline_run
  pipeline_run_id PK
  clip_id FK
  processing_version
  pipeline_version
  model_versions_json
  status                       queued | running | succeeded | failed
  warning_count
  started_at, finished_at

pipeline_stage
  pipeline_stage_id PK
  pipeline_run_id FK
  stage_name
  status                       queued | running | succeeded | failed | skipped
  fatal
  attempt_count
  input_version_json, output_version_json
  error_code, error_summary
  started_at, finished_at

index_generation
  index_version PK
  status                       building | ready | active | failed | superseded
  source_manifest_hash
  expected_document_count
  indexed_document_count
  index_config_json
  created_at, ready_at, activated_at, superseded_at, gc_completed_at

search_index_state
  scene_id FK
  index_version FK index_generation
  status                       pending | indexing | searchable | failed | superseded
  attempt_count
  error_code
  searchable_at, superseded_at, updated_at
  PK(scene_id, index_version)

index_outbox
  outbox_id PK
  event_key UNIQUE
  event_type                   upsert_scene | supersede_scene | validate_generation | activate_generation
  index_version FK index_generation
  scene_id                    nullable FK
  status                       pending | processing | succeeded | failed
  lease_owner, leased_until    nullable
  attempt_count, next_attempt_at
  last_error                  nullable
  created_at, processed_at
```

`searchable`은 clip boolean 하나를 정본으로 신뢰하지 않고 활성 processing version의 scene index state가 모두 정책을 만족하는지 계산한 파생 상태다.

### 10.3 Scene·Evidence·Tag

```
scene
  scene_id PK
  clip_id FK
  pipeline_run_id FK
  processing_version
  scene_index
  start_time_ms, end_time_ms
  thumbnail_asset_id          FK media_asset
  caption                     nullable
  scene_type, shot_type
  season, weather, crowd_density  nullable
  created_at

frame_asset
  frame_asset_id PK
  scene_id FK
  timestamp_ms
  asset_id FK media_asset
  role                        keyframe | thumbnail | evidence
  extraction_version

transcript_segment
  transcript_segment_id PK
  clip_id FK
  pipeline_run_id FK
  processing_version
  segment_index
  start_time_ms, end_time_ms
  verbatim_text
  source                      provided | cc | asr
  confidence                  nullable
  model_version               nullable

scene_transcript_segment
  clip_id FK
  pipeline_run_id FK
  processing_version
  scene_id FK
  transcript_segment_id FK
  overlap_ms
  PK(scene_id, transcript_segment_id)

ocr_observation
  ocr_observation_id PK
  scene_id FK
  frame_asset_id FK
  verbatim_text
  normalized_text
  confidence
  bounding_box_json
  verification_status        verified | unverified | rejected

field_evidence
  evidence_id PK
  target_type                clip | scene | scene_tag | clip_tag
  clip_id                    nullable FK
  scene_id                   nullable FK
  scene_tag_id               nullable FK
  clip_tag_id                nullable FK
  field_name
  field_value_json
  source                     user_input | original_metadata | cc | ocr | asr | vlm | rule
  confidence                 nullable
  verification_status        verified | unverified | rejected
  evidence_text              nullable
  evidence_asset_id          nullable FK media_asset
  pipeline_run_id            nullable FK
  producer_version_json
  created_at

tag
  tag_id PK
  tag_type                   person | organization | location | facility | loose
  canonical_value
  display_value

scene_tag
  scene_tag_id PK
  scene_id FK
  tag_id FK
  created_at

clip_tag
  clip_tag_id PK
  clip_id FK
  tag_id FK
  created_at
```

scene transcript와 OCR 문자열은 segment·observation이 정본이며 scene row에 거대한 중복 문자열 배열로 복제하지 않는다. 검색 index용 문서는 이 관계에서 생성한다. Tag의 source·confidence·verification은 link row가 아니라 `field_evidence`에서 파생한다. 자동 추출은 processing run이 있는 scene_tag만 만들고 clip_tag는 사용자 입력·원본 metadata의 clip-level 값만 가진다.

### 10.4 Search·Snapshot

```
search_configuration
  search_version PK
  index_version FK index_generation
  normalization_version
  resolution_schema_version
  guard_policy_version
  parameters_json
  created_at
  UNIQUE(search_version, index_version)

search_session
  search_session_id PK
  editor_id
  original_query
  canonical_query
  explicit_filters_json
  canonical_filters_json
  canonical_scope_payload
  query_fingerprint
  normalization_version
  created_at

search_execution
  search_execution_id PK
  search_session_id FK
  execution_type             initial | reviewer_replay
  status                     running | succeeded | degraded | failed
  resolver_status
  fallback_used
  degraded_reasons_json
  search_version FK
  index_version FK index_generation
  started_at, finished_at
  UNIQUE(search_execution_id, search_version, index_version)
  FK(search_version, index_version) search_configuration

search_execution_applied_override
  search_execution_id FK
  override_id FK
  application_stage          resolution | post_guard
  created_at
  PK(search_execution_id, override_id)

query_resolution_snapshot
  resolution_snapshot_id PK
  search_execution_id FK UNIQUE
  resolution_source          resolver | resolution_patch | raw_fallback
  resolution_value_json
  explicit_anchor_validation_json
  model_version              nullable
  prompt_version             nullable
  resolution_schema_version
  normalization_version
  applied_override_id        nullable FK
  failure_category           nullable
  created_at
  FK(search_execution_id, applied_override_id) search_execution_applied_override when non-null

search_result_snapshot
  result_snapshot_id PK
  search_execution_id FK UNIQUE
  pre_guard_results_json
  guard_decisions_json
  applied_excludes_json
  final_results_json
  search_version FK search_configuration
  index_version FK index_generation
  created_at
  FK(search_execution_id, search_version, index_version) search_execution

search_result
  search_result_id PK
  search_execution_id FK
  scene_id FK
  scene_processing_version
  result_rank
  score_breakdown_json
  match_snapshot_json
  guard_snapshot_json
  created_at
```

`search_result`는 guard와 active exclude 적용이 끝난 **post-guard final result 전용**이다. candidate·pre-guard row를 이 table에 저장하지 않는다.

### 10.5 Override·Inquiry

```
pinned_override
  override_id PK
  query_fingerprint
  canonical_query
  canonical_filters_json
  canonical_scope_payload
  normalization_version
  action                      resolution_patch | exclude_scene
  target_scene_id             nullable FK
  target_scene_processing_version nullable
  resolution_value_json       nullable
  resolution_schema_version   nullable
  source_inquiry_id           FK, P0 required
  reason
  reviewer_id
  version
  supersedes_override_id      nullable FK
  lifecycle_status            pending_verification | active | superseded | revoked | stale
  status_reason               nullable
  created_at, verified_at, activated_at, deactivated_at

override_lifecycle_history
  override_lifecycle_history_id PK
  override_id FK
  from_status, to_status
  actor_id, reason
  created_at

feedback_inquiry
  inquiry_id PK
  search_result_id FK
  editor_id
  context_snapshot_json
  comment                     nullable
  idempotency_key
  status                      pending | reviewing | resolved | dismissed | deferred
  reviewer_id                 nullable
  row_version
  created_at, review_started_at, closed_at

inquiry_resolution
  inquiry_id                  PK, FK feedback_inquiry
  resolution_type            resolution_patch | exclude_scene | no_action | deferred_p1
  override_id                nullable FK
  verification_execution_id  nullable FK
  deferred_target_json       nullable
  reviewer_reason
  created_at

inquiry_status_history
  inquiry_status_history_id PK
  inquiry_id FK
  from_status, to_status
  actor_id, reason
  created_at
```

### 10.6 External call audit

GMS 호출 감사에는 payload 원문 대신 metadata만 저장한다.

```
external_provider_profile
  provider_profile_version PK
  provider_name, account_alias, region
  endpoint_allowlist_json
  component_model_map_json
  payload_allowlist_json       component별 category와 max_bytes
  rights_evidence_refs_json
  retention_training_deletion_summary_json
  evidence_reviewed_at, evidence_valid_until nullable
  active, created_at

deployment_external_policy
  deployment_policy_version PK
  deployment_scope
  provider_profile_version   FK external_provider_profile
  query_external_processing_allowed yes | no
  approved_by, approved_at
  active, created_at

external_call_audit
  external_call_id PK
  pipeline_run_id            nullable FK
  search_execution_id        nullable FK
  provider_profile_version    FK external_provider_profile
  deployment_policy_version  FK deployment_external_policy
  component                  resolver | vlm | ocr | asr
  payload_categories_json
  payload_size_bytes
  license_decision
  status, error_code
  started_at, finished_at
```

query·transcript·OCR·frame 내용, secret, server 절대 경로는 이 table과 일반 로그에 저장하지 않는다.

### 10.7 필수 무결성 제약

- scene은 `UNIQUE(pipeline_run_id, scene_index)`, `UNIQUE(scene_id, processing_version)`, `UNIQUE(clip_id, pipeline_run_id, processing_version, scene_id)`를 가진다.
- pipeline run은 `UNIQUE(clip_id, processing_version)`, `UNIQUE(clip_id, pipeline_run_id)`, `UNIQUE(clip_id, pipeline_run_id, processing_version)`를 가진다. clip의 `(clip_id, active_pipeline_run_id)`는 같은 clip의 succeeded run을 가리키며 active processing version은 그 run에서만 파생한다.
- clip의 최초 `serving_status=ready` 전이와 active pointer 교체는 target run이 succeeded이고 그 run의 모든 scene이 active index generation에서 searchable이며 최소 searchable contract를 만족할 때만 허용한다. active pointer가 있으면 후속 run의 running/failed 상태가 serving ready를 바꾸지 않는다.
- scene과 transcript segment의 `(clip_id, processing_version, pipeline_run_id)`는 동일한 pipeline run을 가리키는 composite FK여야 한다.
- transcript segment는 `UNIQUE(clip_id, pipeline_run_id, processing_version, transcript_segment_id)`를 가진다. scene-transcript bridge의 `(clip_id, pipeline_run_id, processing_version, scene_id)`와 `(clip_id, pipeline_run_id, processing_version, transcript_segment_id)`가 각각 두 parent를 참조해 clip·run·version이 다른 연결을 막아야 한다.
- frame asset은 `UNIQUE(scene_id, frame_asset_id)`를 가지며 OCR observation의 `(scene_id, frame_asset_id)`는 같은 scene의 frame을 가리키는 composite FK여야 한다.
- `0 ≤ scene.start_time_ms < scene.end_time_ms`, `0 ≤ transcript_segment.start_time_ms < end_time_ms`여야 한다.
- field evidence는 `clip_id`, `scene_id`, `scene_tag_id`, `clip_tag_id` 중 정확히 하나만 non-null이고 `target_type`과 일치해야 한다.
- `(scene_id, tag_id)`와 `(clip_id, tag_id)` 연결은 각각 unique다. 여러 source·confidence·verification은 link row를 복제하지 않고 field evidence에 보존한다.
- index generation의 `status=active`는 P0 검색 scope에서 최대 1개이고, search index state의 index version은 존재하는 generation을 참조해야 한다.
- search configuration은 append-only이며 `(search_version, index_version)`이 unique다. execution은 이 pair를 composite FK로 참조하고 실행 시작 뒤 version을 바꾸지 않아야 한다. result snapshot의 `(execution, search version, index version)`도 동일 execution tuple을 composite FK로 참조한다.
- `(search_execution_id, result_rank)`와 `(search_execution_id, scene_id)`는 각각 unique다. search result의 `(scene_id, scene_processing_version)`은 존재하는 scene의 `(scene_id, processing_version)`을 참조해야 한다.
- search result는 post-guard final row만 저장한다. completion writer는 각 result가 execution이 캡처한 index version에서 queryable membership을 가졌는지 검증해야 한다. active 또는 in-flight drain 중인 generation의 `status=searchable` membership만 허용한다. 문의 생성은 `succeeded|degraded` execution에 resolution/result snapshot이 모두 있고 해당 `search_result_id`가 그 execution의 final row일 때만 허용한다.
- `(editor_id, idempotency_key)`는 unique다.
- resolution patch는 resolution value·schema version 필수이고 target scene ID/processing version은 모두 null이다. exclude scene은 target scene ID/processing version 필수이고 resolution value·schema version은 모두 null이다.
- exclude target의 `(target_scene_id, target_scene_processing_version)`은 scene의 `(scene_id, processing_version)`을 참조해야 한다.
- `canonical_scope_payload`는 normalization version·canonical query·canonical filters의 결정적 직렬화와 일치하고 fingerprint는 그 payload의 SHA-256이어야 한다. generated column 또는 쓰기 전용 DB function으로 보장한다.
- full canonical scope당 `lifecycle_status=active` resolution patch는 최대 1개다. full canonical scope·scene·processing version당 active exclude도 최대 1개이며 fingerprint 단독 partial unique를 사용하지 않는다.
- override series key는 patch의 경우 full canonical scope·action, exclude의 경우 full canonical scope·action·scene·processing version이다. `(series key, version)`은 unique이고 supersedes target은 같은 series의 직전 version이어야 한다.
- pending candidate 생성은 기존 active version을 바꾸지 않는다. replay 성공 promotion transaction만 candidate를 active로, 이전 active를 superseded로, inquiry를 resolved로 바꾸고 두 lifecycle/status history를 함께 기록한다.
- inquiry당 `pending_verification` candidate는 최대 1개다. 새 시도 전 기존 pending을 revoke해야 하며 partial unique index와 series-lock transition function으로 동시 생성을 막는다.
- override timestamp는 pending에서 verified/activated/deactivated가 모두 null, active에서 verified/activated가 non-null이고 deactivated가 null, superseded/revoked/stale에서 deactivated·status reason이 non-null이어야 한다.
- 사람이 만든 override는 source inquiry를 반드시 참조하며 ad-hoc 생성 API를 제공하지 않는다. 완료 시점의 최종 조치는 immutable `inquiry_resolution`이 override·verification execution을 연결한다.
- applied override 연결은 action과 stage가 일치해야 한다. resolution patch는 `resolution`, exclude scene은 `post_guard`이며 execution당 resolution-stage link는 최대 1개다. reviewer replay의 pending candidate도 실제 적용된 경우에만 연결한다.
- query resolution snapshot의 `(search_execution_id, applied_override_id)`는 같은 execution의 applied-override link를 composite FK로 참조해야 한다. `resolution_source=resolution_patch`와 applied override non-null은 서로 동치이고, applied excludes JSON은 `post_guard` 연결 table에서 생성한 비정규화 snapshot이어야 한다.
- snapshot JSONB에는 대응 schema/config version이 반드시 있다. snapshot의 search/index/normalization/schema version은 parent execution·configuration에서 DB writer가 복사하거나 검증하며 client가 임의 지정할 수 없다.
- terminal execution의 search result, resolution/result snapshot과 source evidence·override payload·inquiry resolution·두 history table은 update/delete할 수 없다.
- feedback inquiry의 row-local `CHECK`는 pending에서 reviewer·review_started_at·closed_at이 없고, reviewing에서 reviewer·review_started_at이 필수이며 closed_at이 없고, terminal 상태에서 reviewer·review_started_at·closed_at이 필수인지만 강제한다.
- inquiry resolution은 patch/exclude일 때 override·verification execution이 필수이고 deferred target은 null이어야 한다. no_action은 세 값이 모두 null이고, deferred_p1은 override·verification이 null이며 schema-valid deferred target이 필수다. 상태와 resolution type, override의 source inquiry·action, replay 성공과 당시 active promotion의 일치는 terminal transition function에서 검증한다. 이후 override가 superseded/revoked/stale되어도 과거 inquiry resolution은 바꾸지 않는다.
- external call audit는 pipeline run 또는 search execution 중 정확히 하나를 참조하고 실제 판정에 사용한 provider profile·deployment policy version을 참조해야 한다. resolver는 search execution만, VLM/OCR/ASR은 pipeline run만 parent로 허용한다.
- active provider profile은 HTTPS endpoint allowlist, component별 model, payload category·max bytes, dataset/provider rights refs와 retention/training/deletion 승인 결정을 모두 가져야 하며 만료된 증빙을 허용하지 않는다. query outbound는 이 profile과 별도로 active deployment policy의 `query_external_processing_allowed=yes`도 만족해야 한다.
- P0 deployment scope당 active external policy는 최대 1개다. policy는 사용할 provider profile version을 정확히 고정한다. policy activation과 이전 active 비활성화는 한 transaction이어야 하며 active policy가 없거나 무결성 이상으로 복수 감지되면 모든 provider outbound를 fail-closed 한다.
- external call audit의 provider profile version은 참조 deployment policy가 고정한 version과 같아야 한다.
- 두 날짜는 독립 nullable이며 상호 복사하지 않는다.
- tag display name만으로 전역 uniqueness를 강제하지 않는다.
- tag link와 최초 field evidence는 한 writer에서 원자 생성하고 evidence 없는 link를 허용하지 않는다. clip_tag evidence source는 `user_input|original_metadata`만 허용한다. 자동 source evidence는 pipeline run·producer version이 필수이고 target scene/scene_tag의 run과 일치해야 한다.
- P0는 clip·scene hard delete API를 제공하지 않으며 참조 중 record는 FK `RESTRICT`로 보호한다.

### 10.8 DB·외부 인덱스 강제 계층

- 단일 row 규칙은 `NOT NULL`, `CHECK`, FK, composite FK와 partial unique index로 강제한다.
- inquiry claim·상태 전이, pending override 생성, reviewer replay 검증, promotion·revoke, clip ready/active run 전환은 권한이 제한된 DB function과 transaction으로만 수행한다. 애플리케이션 role의 관련 table 직접 쓰기를 금지한다.
- override series mutation은 series key를 row/advisory lock한 transition function만 수행한다. generation의 `ready→active` promotion도 manifest·문서 수·scene/version을 검증한 뒤 이전 active→superseded, 새 ready→active, clip active run pointer 전환, 구 scene의 active exclude→stale와 lifecycle history를 한 DB transaction에서 처리하며 직접 status write를 금지한다.
- deployment external policy activation도 scope lock과 partial unique index를 사용하는 transition function으로만 수행하고 table 직접 status write를 금지한다.
- search session과 running execution은 resolver·GMS 등 외부 호출 전에 작은 DB transaction으로 먼저 저장한다. 외부 call audit는 이 execution을 참조한다.
- persisted 검색 완료는 resolution snapshot, result snapshot, final result rows, applied-override links와 execution terminal status를 한 transaction에 commit한다. 이 transaction이 실패하면 별도 실패 transaction으로 기존 execution을 `degraded/SNAPSHOT_SAVE_FAILED`로 종결하고 persisted result ID 없이 ephemeral 결과를 반환한다.
- 일반 검색 completion은 적용 override가 execution의 canonical scope와 정확히 같고 execution 시작 시 active였는지 검증한다. reviewer replay는 같은 inquiry의 명시 pending candidate인지 검증한다. action-stage·snapshot-link 제약은 constraint trigger 또는 같은 completion writer에서 강제한다.
- query snapshot의 normalization/schema version은 참조 configuration/session에서 completion writer가 복사·검증하고 search configuration·execution·snapshot table의 직접 쓰기를 제한한다.
- replay 완료 function은 execution type이 `reviewer_replay`인지, 원문의 session·canonical scope와 같은지, snapshot이 저장되었는지, candidate가 실제 적용되었는지, exclude target이 final result에서 빠졌거나 patch가 resolution snapshot에 반영되었는지를 검사한다. exclude promotion 직전에는 target이 현재 active run/version과 active generation에서 searchable인지 다시 검사한다. 성공 시에만 promotion과 immutable inquiry resolution·status history를 함께 기록한다.
- no_action·deferred_p1 terminal transition도 immutable inquiry resolution과 status history를 같은 transaction에 기록한다.
- tag link·최초 evidence는 제한된 writer 또는 deferrable constraint trigger로 함께 생성한다. 자동 evidence의 run 일치와 clip_tag source allowlist를 검사하고 link table 직접 insert를 금지한다.
- append-only table은 전용 insert function과 `UPDATE/DELETE` 차단 trigger 또는 동등한 DB role 정책으로 보호한다. API가 없다는 사실만을 무결성 수단으로 사용하지 않는다.
- index outbox는 처리 산출물과 같은 DB transaction에 insert한다. worker는 `FOR UPDATE SKIP LOCKED` 또는 동등한 lease로 claim하고 event key를 idempotency key로 사용하며 retry/backoff·stale lease 회수·reconciliation을 수행한다.
- superseded generation은 그 index version을 참조하는 running execution이 0이 될 때까지 문서와 searchable membership을 유지한다. stale execution lease를 회수해 terminal 처리한 뒤에만 membership supersede와 physical GC를 수행한다.
- 외부 index는 DB transaction 대상이 아니므로 outbox·generation manifest·idempotent consumer·active version filter·reconciliation으로 일관성을 보장한다. 강제 종료 후에도 이전 active generation을 in-flight drain 동안 조회할 수 있어야 한다.

### 10.9 저장소 선택 확정

PostgreSQL+JSONB를 P0 정본으로 확정한다. MongoDB는 snapshot 저장은 편하지만 override uniqueness, 관계, transaction, 감사 이력의 책임을 애플리케이션으로 옮겨 P0를 단순하게 만들지 않는다. Neo4j는 Event·graph traversal 요구가 없어 이점이 없다. SQLite는 별도 single-process 배포 변형의 후보일 뿐 v2.2 구현 기준이 아니다.

---

## 11. 논리 API 계약

### 11.1 공통 규칙

| ID | 요구사항 |
| --- | --- |
| `FR-API-001` | API는 `/api/v1` 같은 명시적 version namespace를 사용해야 한다. |
| `FR-API-002` | 성공 응답은 resource ID·status·version, 실패 응답은 stable error code·한국어 message·request ID를 제공해야 한다. |
| `FR-API-003` | 상태 변경 요청은 idempotency key 또는 expected row version을 받아야 한다. |
| `FR-API-004` | client가 보낸 rank, reviewer ID, source path, verification 상태를 정본으로 신뢰해서는 안 된다. |
| `FR-API-005` | JSON payload는 schema validation을 통과해야 하며 unknown field 처리 정책을 API version에서 일관되게 적용해야 한다. |
| `FR-API-006` | 원본 절대 경로, secret, 전체 model raw response를 일반 응답에 포함해서는 안 된다. |
| `FR-API-007` | 최초 검색은 새 session과 initial execution을 만들고 reviewer replay는 원래 session 아래 새 replay execution을 추가해야 하며 과거 execution을 덮어쓰지 않아야 한다. |

endpoint의 물리 이름은 구현 routing에서 달라질 수 있으나 아래 행위와 계약은 유지해야 한다.

### 11.2 Endpoint 목록

| Method | 논리 endpoint | 역할 | 행위 |
| --- | --- | --- | --- |
| `POST` | `/clips` | reviewer | 영상 등록·pipeline enqueue |
| `GET` | `/clips` | reviewer | 처리·index 상태 목록 |
| `GET` | `/clips/{clip_id}` | reviewer | clip·run·stage detail |
| `POST` | `/clips/{clip_id}/retry` | reviewer | 실패 run의 명시적 재시도 |
| `POST` | `/search` | editor/reviewer replay | 새 session/execution 또는 replay 검색 |
| `GET` | `/search/executions/{id}` | 해당 사용자/reviewer | resolution·result 상태 조회 |
| `POST` | `/search/results/{result_id}/inquiries` | editor | `이상해요` 문의 생성 |
| `GET` | `/review/inquiries` | reviewer | 상태별 문의 목록 |
| `GET` | `/review/inquiries/{id}` | reviewer | context·evidence·history 조회 |
| `POST` | `/review/inquiries/{id}/claim` | reviewer | `pending→reviewing` |
| `POST` | `/review/inquiries/{id}/actions` | reviewer | 두 override, no_action, deferred_p1 |
| `POST` | `/review/inquiries/{id}/replay` | reviewer | exact query replay·완료 검증 |
| `POST` | `/review/overrides/{id}/revoke` | reviewer | expected version·사유로 active override 해제 |
| `GET` | `/media/{clip_id}` | editor/reviewer | ID 기반 Range media |

### 11.3 등록·처리 API

- multipart 등록에서 media와 metadata validation 결과를 하나의 transaction 경계로 다룬다.
- binary 전체를 JSON·base64로 넣지 않는다.
- 상태 조회는 clip summary와 run/stage/index 상태를 분리해 반환한다.
- retry는 새 run/attempt를 만들고 기존 성공 snapshot을 덮어쓰지 않는다.
- Event·review item·field correction endpoint는 제공하지 않는다.

### 11.4 검색 API

권장 요청:

```json
{
  "query": "2022 추석 고속도로",
  "filters": {
    "broadcast_date": null,
    "filming_date": null
  },
  "replay_of_inquiry_id": null
}
```

일반 editor는 `replay_of_inquiry_id`를 지정할 수 없다. reviewer replay는 저장된 문의의 query·filter를 서버가 읽으며 client가 바꾼 값을 사용하지 않는다.

서버는 resolver·GMS 호출 전에 search session과 `running` execution을 먼저 commit한다. 이 선행 저장이 실패하면 외부 호출 없이 요청을 실패 처리한다.

권장 응답:

```json
{
  "search_session_id": "...",
  "search_execution_id": "...",
  "status": "succeeded | degraded",
  "snapshot_persisted": true,
  "inquiry_available": true,
  "resolution_summary": {},
  "degraded_reasons": [],
  "applied_human_rule": false,
  "results": [
    {
      "search_result_id": "...",
      "scene_id": "...",
      "scene_processing_version": 1,
      "rank": 1,
      "broadcast_date": null,
      "filming_date": null,
      "verification_badges": [],
      "match_explanation": [],
      "media_url": "/api/v1/media/..."
    }
  ]
}
```

검색 성공 응답 전에 resolution snapshot과 result snapshot 저장을 시도해야 한다. 저장 commit 실패지만 검색 계산 결과가 있으면 기존 execution을 `degraded/SNAPSHOT_SAVE_FAILED`로 종결하고 `snapshot_persisted=false`, `inquiry_available=false`, `search_result_id=null`인 ephemeral degraded card를 반환한다. BM25/index까지 사용할 수 없으면 실패 응답을 반환한다.

### 11.5 문의·검수 API

문의 요청은 선택적 comment와 idempotency key만 받는다. selected result, query, rank, context는 URL의 result ID와 서버 저장값에서 구성한다.

review action 공통 필드:

```json
{
  "action": "resolution_patch | exclude_scene | no_action | deferred_p1",
  "expected_inquiry_version": 2,
  "reason": "필수 검수 사유",
  "resolution_value": null,
  "deferred_target": null
}
```

- resolution patch만 전체 `resolution_value`를 받는다.
- exclude는 문의 result의 scene ID/processing version을 서버가 사용한다.
- no_action과 deferred_p1은 override를 만들지 않는다. deferred_p1만 field·target/evidence ID·description의 `deferred_target`을 받고 다른 action에서는 null이어야 한다.
- `deferred_target`은 `{field, target_id?, evidence_id?, description}` 형태이며 target ID와 evidence ID 중 하나 이상이 필요하다.
- override action 저장은 pending candidate·source inquiry link·lifecycle history를 한 transaction에 기록하고 inquiry는 reviewing으로 유지한다.
- replay 검증 성공 시 promotion·이전 active supersede·inquiry resolved·두 history를 한 transaction에 기록한다.
- revoke는 inquiry action과 별개이며 active rule만 expected version으로 해제한다. 과거 payload와 생성 inquiry는 바꾸지 않는다.

### 11.6 Media API

- media ID를 media root 안의 canonical asset으로 해석한다.
- path traversal, symlink escape, 임의 URL·UNC path 접근을 막는다.
- 허용 MIME·codec을 사용하고 `Content-Range`, `Accept-Ranges`를 지원한다.
- 권한 없는 origin에서의 media 요청을 기본 거부한다.

### 11.7 오류 코드 최소 집합

| 범주 | 코드 예시 |
| --- | --- |
| 입력 | `VALIDATION_ERROR`, `UNSUPPORTED_MEDIA`, `INVALID_TRANSCRIPT` |
| 처리 | `SCENE_DETECTION_FAILED`, `VLM_SCHEMA_INVALID`, `OCR_FAILED`, `ASR_FAILED`, `INDEX_FAILED` |
| Resolver | `RESOLVER_TIMEOUT`, `RESOLVER_SCHEMA_INVALID`, `RESOLVER_RATE_LIMITED` |
| 검색 | `OVERRIDE_LOOKUP_FAILED`, `INDEX_UNAVAILABLE`, `SEARCH_FAILED`, `SNAPSHOT_SAVE_FAILED` |
| 문의 | `INQUIRY_CONTEXT_MISSING`, `INQUIRY_CONFLICT`, `INQUIRY_SAVE_FAILED`, `REPLAY_FAILED` |
| Media | `MEDIA_NOT_FOUND`, `MEDIA_RANGE_INVALID`, `MEDIA_SEEK_FAILED` |
| 보안·권리 | `EXTERNAL_PROCESSING_NOT_ALLOWED`, `ROLE_FORBIDDEN` |

### 11.8 API 수용 기준

- `AC-API-001`: validation 실패 요청은 partial clip·override·inquiry를 남기지 않는다.
- `AC-API-002`: 같은 idempotency key 재전송이 같은 resource를 반환한다.
- `AC-API-003`: editor가 reviewer action을 호출하면 거부된다.
- `AC-API-004`: client가 rank·reviewer·target scene을 변조해도 서버 정본과 다른 조치가 생성되지 않는다.
- `AC-API-005`: incompatible patch schema와 duplicate active override가 DB constraint/API validation에서 거부된다.
- `AC-API-006`: HTTP Range로 scene start seek가 가능하고 절대 경로가 응답에 없다.
- `AC-API-007`: 두 reviewer의 동시 revoke 요청은 expected version을 통과한 하나만 lifecycle을 변경하며 같은 요청 재전송은 같은 revoked 상태를 반환한다.

---

## 12. UI 상태·오류·사용성

### 12.1 `/review` 처리 화면

| 상태 | 필수 표시 | 동작 |
| --- | --- | --- |
| empty | 등록된 영상 없음 | 영상 등록 |
| queued/processing | 현재 stage, 완료 수, 경고 | 새로고침·detail |
| ready/searchable | 처리·index version, 누락 channel | 검색 이동 |
| ready/reprocessing | 기존 제공 version, latest run·stage 진행 | 기존 검색·detail |
| ready/latest run failed | 기존 제공 version, 실패 stage·경고 | 기존 검색·retry/detail |
| failed/index | 분석 산출물 보존·검색 불가·index 오류 분리 | index retry/detail |
| failed | 실패 stage, 한국어 요약, retry 가능 여부 | 명시적 재시도 |

사전검수 대기 건수, scene 승인·보류, Event 후보 UI는 표시하지 않는다.

### 12.2 `/review` 문의 화면

| 영역 | 필수 내용 |
| --- | --- |
| 목록 | 상태, 접수 시각, query 요약, 선택 scene thumbnail, degraded 여부 |
| detail | 원문 query·filter, resolution, 선택 결과·rank, 근거, guard, pre/post Top 10, comment |
| 진단 | full resolution patch editor, exclude scene, no action, P1 이관 |
| replay | 적용 override·새 execution·전후 결과 비교 |
| history | 상태 전이, reviewer, reason, override version |

P0는 Entity·OCR·Caption 값을 수정하는 form을 제공하지 않는다.

### 12.3 `/search` 화면

| 영역 | 상태 |
| --- | --- |
| query·filter | idle, submitting, validation error |
| resolution 요약 | normal, human rule applied, raw BM25 fallback |
| 결과 | loading, populated, fewer-than-10, empty, degraded, failed |
| card | verified match, unverified, guard-safe, inquiry submitting/submitted |
| Preview | loading, playing, range/codec/file error |

| ID | 요구사항 |
| --- | --- |
| `FR-UI-001` | 검색 중 중복 제출을 막고 이전 결과와 새 loading 상태를 혼동시키지 않아야 한다. |
| `FR-UI-002` | raw BM25·dense failure·snapshot failure를 서로 구분한 경고를 표시해야 한다. |
| `FR-UI-003` | 날짜 없음, unverified, verified match를 색상만이 아닌 문구로 구분해야 한다. |
| `FR-UI-004` | 결과 없음 화면에 적용된 explicit filter·resolver 상태·제외 수를 표시해야 한다. |
| `FR-UI-005` | 문의 제출 실패를 성공으로 표시하지 않고 같은 comment로 재시도할 수 있어야 한다. |
| `FR-UI-006` | snapshot 미저장 결과는 검색할 수 있어도 문의 불가 이유를 버튼 근처에 표시해야 한다. |
| `FR-UI-007` | query resolution을 확정 사실처럼 표현하지 않고 `검색 해석`으로 표시해야 한다. |
| `FR-UI-008` | `이상해요`가 즉시 자동 개선·전역 교정을 뜻하지 않음을 짧게 안내해야 한다. |

### 12.4 접근성 기본 계약

- 키보드만으로 검색, filter, result, Preview, 문의, reviewer action을 수행할 수 있어야 한다.
- 모든 interactive element에 visible focus, accessible name과 논리적 tab order가 있어야 한다.
- 상태·confidence·오류를 색상만으로 전달하지 않는다.
- form label과 오류를 연결하고 비동기 상태 변경을 live region으로 알린다.
- 긴 한국어 기관명·OCR 문자열은 줄바꿈과 전체값 확인을 지원한다.
- `prefers-reduced-motion`을 존중한다.

### 12.5 공통 오류 원칙

- 사용자 메시지와 개발 로그를 분리한다.
- 실패를 빈 결과나 성공으로 위장하지 않는다.
- 사용자가 할 수 있는 다음 동작을 한국어로 제시한다.
- request ID는 제공하되 path·secret·전체 transcript를 노출하지 않는다.
- fallback·preprocessed data·사전 녹화를 사용하면 종류를 명시한다.

---

## 13. 비기능·보안·운영 요구사항

### 13.1 성능·환경

| ID | 대상 | 목표 | 측정 원칙 |
| --- | --- | --- | --- |
| `NFR-PERF-001` | 검색 Top 10 카드 | p95 10초 이내 | normal resolver, human patch, raw fallback을 분리 |
| `NFR-PERF-002` | Preview 첫 프레임 | p95 2초 이내 | first access와 warm access 분리 |
| `NFR-PERF-003` | 짧은 clip live 분석 | model warm 기준 30초 목표 | clip profile·stage별 시간 공개 |
| `NFR-PERF-004` | 333클립 batch | 약 2시간 목표 | 장비 profile, terminal 성공·실패 수, 원시 시간 보존 |
| `NFR-PERF-005` | 문의 저장 | 별도 p50/p95 보고 | snapshot 조회·context 생성·DB commit 포함 |
| `NFR-PERF-006` | reviewer replay | 별도 p50/p95 보고 | override 생성 시간과 검색 시간을 분리 |

검색 target 숫자는 PRD 계승 목표이며 기준 장비·코퍼스·반복 수가 없는 단일 측정으로 합격을 주장하지 않는다. benchmark profile에는 OS, CPU/GPU, memory, storage, browser, model, driver, corpus, pipeline/search/index version을 포함한다.

| ID | 환경 요구사항 |
| --- | --- |
| `NFR-ENV-001` | 지정 Chrome stable 데스크톱에서 주요 흐름을 지원해야 한다. |
| `NFR-ENV-002` | P0 보장 범위는 동시 사용자 1명이다. |
| `NFR-ENV-003` | 사용자 입력·출력은 한국어이며 영어 번역을 중간 단계로 사용하지 않는다. |
| `NFR-ENV-004` | mobile, public server, multi-user concurrency는 P0 완료 범위가 아니다. |

### 13.2 신뢰성·Fallback·복구

| 장애 | P0 동작 |
| --- | --- |
| resolver timeout/schema/rate/network | raw query BM25 degraded 검색 |
| dense channel 실패 | BM25+사용 가능한 structured score degraded 검색 |
| override lookup 실패 | known human decision 우회 방지를 위해 검색 실패·재시도 |
| resolution/result snapshot commit 실패 | 검색 계산 결과가 있으면 session/execution ID는 유지하고 persisted result ID 없는 ephemeral degraded 결과를 반드시 반환하며 문의·override 비활성; BM25/index도 없으면 실패 |
| BM25/index unavailable | 검색 실패; 빈 결과로 위장 금지 |
| VLM/OCR/ASR 개별 실패 | stage 실패·누락 신호 기록; 최소 contract를 만족하면 `pipeline_run=succeeded`, `warning_count>0`, `clip.serving_status=ready` |
| pipeline process 중단 | stale run 탐지 후 안전 stage부터 명시적 재개/재시도 |
| media 누락·disk 부족 | 명시 error, partial success 금지, 기존 정본 보호 |

최소 searchable contract는 유효한 media·scene 경계와 Preview asset, PostgreSQL에 commit된 scene provenance, `provided/CC/ASR/OCR/VLM caption` 중 하나 이상의 허용된 indexable text 신호, BM25 문서와 검증된 index generation이다. media decode·scene detection·필수 asset·DB commit·이 최소 text 신호·BM25/index generation 중 하나를 끝내 확보하지 못하면 치명 실패다. VLM·OCR·ASR·dense는 각각 단독으로는 비치명이나, 조합 실패로 최소 contract가 깨지면 최종 clip은 `failed`다.

| ID | 요구사항 |
| --- | --- |
| `NFR-REL-001` | pipeline·검색·문의의 실패 category, error code, attempt와 duration을 저장해야 한다. |
| `NFR-REL-002` | retry는 idempotent하며 중복 scene·result·inquiry·override를 만들지 않아야 한다. |
| `NFR-REL-003` | 검색 index는 PostgreSQL 정본으로 재구축할 수 있어야 한다. |
| `NFR-REL-004` | process 종료 뒤 남은 running job을 lease/heartbeat 기준으로 탐지해야 한다. 정확한 stale 시간은 운영 profile에서 동결한다. |
| `NFR-REL-005` | pipeline artifact를 재사용할 때 media hash와 pipeline/model/prompt/schema version이 모두 같아야 한다. 이는 P1 semantic query cache와 구분한다. |
| `NFR-REL-006` | 시연 preprocessed corpus·사전 녹화를 fallback으로 사용하면 실제 경로와 구분해 고지해야 한다. |
| `NFR-REL-007` | override action transaction은 pending candidate·source inquiry link·lifecycle history를 원자 저장하고 inquiry는 reviewing으로 유지해야 한다. replay와 promotion/close는 후속 transaction이며, no_action·deferred는 각각 immutable inquiry resolution·inquiry 상태·history를 원자 저장한다. |
| `NFR-REL-008` | 모든 pipeline stage와 generation은 terminal `succeeded/failed/skipped` 또는 이에 대응하는 최종 상태에 도달해야 하며 영구 `running/building`은 lease recovery 대상이어야 한다. |
| `NFR-REL-009` | 외부 provider 거부·미승인 시 구성된 local adapter가 있으면 동일 output contract로 실행하고, 없으면 stage를 skipped/failed로 기록한다. 최소 searchable contract를 만족하면 `pipeline_run=succeeded`, `warning_count>0`, `clip.serving_status=ready`로 끝낸다. 만족하지 못한 최초 처리는 failed이고, 기존 active run이 있는 재처리는 serving ready와 기존 pointer를 유지한 채 latest run failed를 표시한다. |
| `NFR-REL-010` | VLM/OCR/ASR 등 비동기 처리 stage의 timeout·rate limit·일시 network 오류만 versioned 횟수와 backoff로 재시도하고 auth·TLS·권리·allowlist 위반은 영구 오류로 즉시 fail-closed 해야 한다. 동기 resolver는 §6.3 계약에 따라 재시도 0회 후 raw BM25 fallback을 사용한다. |
| `NFR-REL-011` | resolver는 외부·local adapter가 모두 실패해도 raw BM25 fallback을 사용해야 하며 BM25 자체가 없으면 실패해야 한다. |
| `NFR-REL-012` | 강제 종료 복구는 마지막 검증된 stage 경계부터 재개하고, 최종 ready 또는 failed에 도달하며, 중복 row 없이 source manifest·index manifest가 clean run과 동등해야 한다. |

### 13.3 로컬 보안·입력 보호

| ID | 요구사항 |
| --- | --- |
| `NFR-SEC-001` | P0 서버는 기본적으로 `127.0.0.1`에 bind하고 허용 Host/Origin 외 요청과 CORS를 기본 거부해야 한다. |
| `NFR-SEC-002` | 상태 변경 요청은 허용 Origin, same-site 정책과 요청 token 등 로컬 UI에 맞는 CSRF 보호를 적용해야 한다. |
| `NFR-SEC-003` | media ID의 최종 canonical path가 승인 media root 안인지 확인하고 path traversal·symlink escape를 차단해야 한다. |
| `NFR-SEC-004` | upload는 extension만이 아니라 MIME/magic bytes와 decode 가능성을 검사해야 한다. |
| `NFR-SEC-005` | query, OCR, transcript, VLM output, inquiry comment를 비신뢰 문자열로 취급하고 실행 가능한 HTML/script로 렌더링하지 않아야 한다. |
| `NFR-SEC-006` | editor/reviewer capability를 API에서 검사하고 reviewer ID를 client 입력으로 신뢰하지 않아야 한다. |
| `NFR-SEC-007` | P0 고정 demo identity는 운영급 인증이 아니며 public/LAN 배포 전에 실제 authentication·authorization이 필요하다고 고지해야 한다. |

### 13.4 GMS 외부 전송 — 승인 정책과 실행 조건

Gate S의 **정책은 승인**되었다. 다만 실제 외부 호출은 데이터셋 권리와 provider 조건 증빙이 갖춰진 component·payload에만 허용한다.

| component | 기본 허용 후보 | 기본 금지 |
| --- | --- | --- |
| Query Resolver | query text, explicit filter | search history 전체, result media |
| VLM | 필요한 selected keyframe | full video |
| OCR | 필요한 selected keyframe | 관계없는 frame·full video |
| ASR | 필요한 audio chunk | full video와 불필요한 transcript |

위 표의 “허용 후보”도 자동 허용이 아니다. media에서 나온 frame·audio·text는 clip의 `external_processing_allowed=yes`, 데이터 이용조건, provider profile allowlist를 모두 만족해야 한다. Query Resolver의 query·filter 전송은 clip flag가 아니라 별도의 deployment-level `query_external_processing_allowed=yes`와 provider allowlist를 요구한다. 어느 쪽이든 승인되지 않으면 local adapter를 사용하거나 정해진 fallback으로 처리한다.

| ID | 요구사항 |
| --- | --- |
| `NFR-GMS-001` | GMS의 정확한 제품·계정·region, model, endpoint, component별 payload category를 versioned provider profile로 기록해야 한다. |
| `NFR-GMS-002` | active deployment policy·그 policy가 고정한 active provider profile이 없거나, media payload의 `external_processing_allowed=no/unknown`, query payload의 `query_external_processing_allowed!=yes`, 또는 component allowlist 밖 payload이면 외부 호출 전에 fail-closed 해야 한다. |
| `NFR-GMS-003` | AI Hub/KBS 등 원천 데이터의 외부 처리 가능 여부와 provider의 보관·학습·삭제 정책 승인 증빙을 provider profile에 연결해야 한다. |
| `NFR-GMS-004` | provider training/retention 조건이 확인되지 않은 실제 원본·파생 데이터를 전송해서는 안 된다. |
| `NFR-GMS-005` | P0에서는 full video·전체 transcript·전체 OCR을 외부로 전송하지 않아야 한다. 이 경계를 바꾸려면 Gate S를 재개하고 문서 version을 올려야 한다. |
| `NFR-GMS-006` | secret은 코드·문서·DB·일반 로그에 저장하지 않고 승인된 secret injection을 사용해야 한다. 외부 통신은 TLS를 사용한다. |
| `NFR-GMS-007` | 외부 call audit에는 content 대신 payload category·size·license decision·provider version·status만 남겨야 한다. |
| `NFR-GMS-008` | 발표와 UI는 데모의 GMS 사용을 숨기지 않고 “외부 전송 없음”을 주장하지 않아야 한다. |
| `NFR-GMS-009` | 자체 호스팅은 adapter 대체 가능성으로 표현하며 실제 self-host 구성의 미측정 성능·동등성을 주장하지 않아야 한다. |
| `NFR-GMS-010` | provider outbound는 공통 adapter의 allowlisted HTTPS scheme·host·port로만 나가야 하며 다른 egress와 미검증 redirect는 기본 거부해야 한다. |

### 13.5 저작권·출처

사용 데이터에 해당하는 경우 발표·시연 결과 영역에는 실제 이용조건에 맞춰 다음 출처를 표시한다.

```
과기정통부·NIA 인공지능 학습용 데이터 구축사업 결과물
「한국어 텍스트-비디오-사운드 데이터」 (AI-Hub, 71699)
원본 영상 저작권: KBS
```

| ID | 요구사항 |
| --- | --- |
| `NFR-LIC-001` | 소스코드·평가 결과 공개와 원본·파생 media 공개를 구분해야 한다. |
| `NFR-LIC-002` | AI Hub/KBS 원본 영상·frame·audio를 repository·public deployment에 포함해서는 안 된다. |
| `NFR-LIC-003` | 포트폴리오·발표 자료의 thumbnail·녹화 사용은 실제 이용조건 확인 전 허용으로 간주하지 않아야 한다. |
| `NFR-LIC-004` | preprocessed result나 사전 녹화를 사용하면 이를 실제 live inference로 표시해서는 안 된다. |

### 13.6 로그·추적성·보존

| ID | 요구사항 |
| --- | --- |
| `NFR-OBS-001` | request, clip, pipeline run/stage, scene, session, execution, resolution/result snapshot, inquiry, override ID를 연결할 수 있어야 한다. |
| `NFR-OBS-002` | pipeline/model/prompt/schema/normalization/guard/search/index version chain을 평가 artifact와 연결해야 한다. |
| `NFR-OBS-003` | 일반 로그에 secret, 원본 절대 경로, 전체 query·transcript·OCR·frame content를 남기지 않아야 한다. |
| `NFR-OBS-004` | 외부 telemetry는 P0 기본 비활성화하고 제한된 로컬 rolling log를 사용해야 한다. |
| `NFR-OBS-005` | P0 평가·시연이 끝날 때까지 필요한 snapshot·문의·override 이력을 보존하고, 실제 운영 전 보존기간·삭제 책임자를 별도 승인해야 한다. |
| `NFR-OBS-006` | raw source·derived asset·DB·index·log의 삭제 경계를 manifest로 구분해야 한다. |

P0는 참조 중인 clip·scene·snapshot의 hard delete UI를 제공하지 않는다. 실제 데이터 삭제는 승인된 대상 ID와 retention manifest를 확인한 운영 절차로만 수행한다.

### 13.7 비기능 수용 기준

- `AC-NFR-001`: 지정 Chrome·동시 사용자 1명 환경에서 주요 흐름이 한국어로 동작한다.
- `AC-NFR-002`: 지정 benchmark profile에서 normal·patch·fallback 검색 p50/p95와 원시값을 분리 보고한다.
- `AC-NFR-003`: first/warm media 접근을 분리해 Preview p95를 측정한다.
- `AC-NFR-004`: version chain만으로 평가 실행의 pipeline·model·query·guard·index 설정을 찾을 수 있다.
- `AC-SEC-001`: media traversal, hostile query/OCR/comment, forbidden role 요청이 차단된다.
- `AC-SEC-002`: 승인되지 않은 payload의 outbound 네트워크 요청이 test endpoint에서 발생하지 않는다.
- `AC-SEC-003`: repository·로그·API 응답에서 secret·원본 절대 경로·전체 민감 content가 발견되지 않는다.
- `AC-SEC-004`: clip flag가 `yes`여도 dataset rights evidence가 없거나 미승인이면 media outbound는 0건이다.
- `AC-SEC-005`: provider profile이 없거나 inactive이거나 component/model/version이 일치하지 않거나 rights refs·retention/training/deletion 승인 증빙이 누락·만료되면 outbound는 0건이다.
- `AC-SEC-006`: deployment의 query 외부 처리 flag가 `yes`가 아니면 resolver query·filter outbound는 0건이다.
- `AC-SEC-007`: allowlist 밖 category, 허용 size 초과, full video·전체 transcript·전체 OCR payload는 outbound 전에 차단된다.
- `AC-SEC-008`: synthetic 승인 fixture는 allowlisted endpoint로 TLS 요청 1건만 보내고 component 최소 허용 field만 포함한다. audit에는 provider version·category·size·license decision·status만 있으며 query·text·image·audio 원문과 secret은 없다.
- `AC-SEC-009`: 승인 payload라도 endpoint scheme·host·port가 allowlist 밖이거나 30x가 비허용 목적지를 가리키면 redirect를 따르지 않고 해당 목적지 outbound는 0건이며 policy error metadata만 audit한다.
- `AC-SEC-010`: 같은 deployment scope의 동시 policy activation은 하나만 성공한다. active policy가 없거나 복수 상태가 감지되면 resolver·VLM·OCR·ASR outbound는 모두 0건이다.
- `AC-FAIL-001`: resolver와 dense 장애가 각각 정해진 degraded 경로와 경고를 사용한다.
- `AC-FAIL-002`: snapshot 실패 결과에서 문의 생성이 차단되고 성공 저장으로 표시되지 않는다.
- `AC-FAIL-003`: VLM·OCR·ASR을 각각 단독 실패시킨 fixture는 최소 contract를 만족하면 `pipeline_run=succeeded`, `warning_count>0`, `clip.serving_status=ready`다. 모든 text 신호를 제거한 최초 처리 fixture는 failed이고 searchable로 표시되지 않으며, 재처리 fixture는 기존 serving ready를 유지하고 latest run failed를 표시한다.
- `AC-FAIL-004`: media decode·scene commit·outbox publish·index build·pointer promotion 각 kill-point에서 재시작하면 terminal ready/failed에 도달하고 row·문서가 중복되지 않는다.
- `AC-FAIL-005`: 새 generation 활성화 실패 시 직전 active generation과 run pointer가 유지되고 실패 generation은 검색 결과에 섞이지 않는다.
- `AC-FAIL-006`: VLM/OCR/ASR의 timeout·rate limit·일시 network 오류만 설정된 범위에서 retry되고 auth·TLS·권리·allowlist 오류는 재시도 없이 fail-closed 된다. resolver timeout/rate/network는 retry 없이 raw BM25로 전환된다.
- `AC-FAIL-007`: recovery 완료 generation의 source manifest hash·scene/version 집합·문서 수가 동일 입력의 clean run과 일치한다.

---

## 14. 평가·완료 기준·추적성

### 14.1 평가 원칙과 분리

| ID | 요구사항 |
| --- | --- |
| `FR-EVL-001` | 개발셋과 최종셋은 group 누수를 막아 6:4로 분리하고 최종셋은 마지막 측정에만 사용해야 한다. |
| `FR-EVL-002` | 같은 clip·near-duplicate·원천 뉴스 묶음이 개발·최종셋에 갈라져 누수되지 않도록 group split해야 한다. |
| `FR-EVL-003` | confidence, boost, resolver prompt, guard rule은 개발셋으로만 조정해야 한다. |
| `FR-EVL-004` | 검색 query는 corpus transcript 문장을 그대로 복사해 만들지 않아야 한다. |
| `FR-EVL-005` | Gold label은 평가 정답으로만 쓰고 extraction·resolver·index·override 입력으로 사용하지 않아야 한다. |
| `FR-EVL-006` | 개발 중 만든 pinned override는 일반 최종 검색 평가에서 제외하고 별도 replay set에서 측정해야 한다. |
| `FR-EVL-007` | split manifest, annotation guide, seed, config version과 원시 결과를 보존해야 한다. |
| `FR-EVL-008` | 사건명 hard guard는 Gate B에서 승인된 source-backed deterministic rule이 있을 때만 평가·활성화해야 한다. 승인 rule이 없으면 사건명 자동 hard guard를 끄고 해당 hard-guard 지표를 `N/A`로 보고해야 한다. |
| `FR-EVL-009` | result coverage의 최소 허용값은 개발셋과 manual baseline으로 Gate C에서 최종셋 열람 전에 동결해야 한다. 최종 결과가 그 값보다 낮으면 false-hit 수치와 무관하게 guard 평가는 실패다. |
| `FR-EVL-010` | paired 사용자 평가에서 usable scene 발견 성공률과 검색 만족도는 M0 manual baseline보다 높고, 편집기자+검수자 총업무시간은 유지 또는 감소해야 한다. 척도·유의미 차이 판정은 Gate C에서 사전 동결한다. |

### 14.2 Gold Set

| 정답지 | 기본 규모 | 목적 |
| --- | --- | --- |
| 장면 metadata | scene 100개 이상 | shot type, scene type, caption·근거 품질 |
| 검색 relevance | query 30개 이상 | [Recall@10](mailto:Recall@10), [nDCG@10](mailto:nDCG@10), MRR |
| explicit anchor | 날짜·사건명 query 별도 표본 | [false-hit@10](mailto:false-hit@10), guard fixture |
| Query Resolver | 날짜·사건명·entity span label | field 정확도, explicit/inferred 판정 |
| ASR | CC 보유 cohort | WER |
| Reactive replay | 확정 inquiry·override fixture | exact 재발 방지 |
| 업무 효율 | 동일 task paired session | usable scene·시간·문의·검수 업무량 |

exact 규모와 교차 라벨링 비율은 데이터 확보 후 Gate C에서 동결한다. 시스템이 만든 `verified_conflict`를 정답으로 재사용하지 않고 사람이 작성한 Gold conflict label을 사용한다.

### 14.3 Baseline·Ablation

| 구성 | 신호 |
| --- | --- |
| `M0` | 현행 수기 keyword 검색 baseline |
| `B0a` | 제공 transcript/CC BM25 |
| `B0b` | ASR transcript BM25 |
| `B0p` | 제품 source 정책: 제공 transcript/CC 우선, 없으면 ASR인 BM25 |
| `B1` | B0p + scene 분할·shot type |
| `B2` | + VLM caption·scene metadata |
| `B3` | + OCR·typed entity |
| `B4` | + dense·RRF |
| `B5` | + Query Resolver·structured soft score |
| `B6` | + explicit-anchor guard |

`B0a`는 timestamp transcript/CC 보유 cohort, `B0b`는 같은 source에 대해 ASR을 만든 paired cohort에서 transcript source 효과를 비교한다. `B0p→B6` 제품 ablation은 `provided/CC 우선, 없으면 ASR` 정책을 고정한 공통 product cohort를 사용한다. 각 비교 안에서는 split·query·Top K·index corpus를 같게 하고, cohort가 다른 B0a/B0b 원시 수치를 직접 우열로 해석하지 않는다. guard 효과는 `B5↔︎B6`의 false-hit와 Recall 변화를 함께 보고한다. 일부 결과를 덜 반환해 false-hit을 낮추는 일을 막기 위해 result coverage와 평균 결과 수를 함께 보고한다.

### 14.4 지표와 계산

#### 검색 품질

- [Recall@10](mailto:Recall@10): 현행 baseline 대비 **+5%p 이상** 목표
- [nDCG@10](mailto:nDCG@10), MRR
- 명시 연도 충돌률, 명시 사건명 충돌률
- 미검증 결과 비율과 result coverage
- guard 적용 전후 [Recall@10](mailto:Recall@10) 변화

`explicit-anchor false-hit@10`은 다음과 같다.

```
Q     = 원문 query 또는 UI filter에 explicit anchor가 있는 Gold query 집합
R(q)  = q의 최종 상위 min(10, 실제 결과 수)

false-hit@10 =
  Σ(q∈Q) R(q) 안의 Gold verified anchor conflict 수
  ───────────────────────────────────────────────────
  Σ(q∈Q) |R(q)|
```

분모 `Σ|R(q)|`가 0이면 false-hit을 0으로 기록하지 않고 `N/A`로 보고한다. result coverage가 Gate C 최소값보다 낮으면 false-hit이 낮아도 합격으로 판정하지 않는다.

P0는 근거 없이 임의 백분율 목표를 만들지 않는다. 대신 curated must-pass fixture에서 명시 날짜의 verified conflict가 모두 제외되는지 확인한다. Gate B에서 사건명 rule을 승인한 경우에는 source-backed 사건명 verified conflict 제외와 missing/unverified 유지 fixture도 통과해야 한다. 전체셋에서는 false-hit·Recall·coverage의 원시값과 신뢰구간을 함께 보고한다.

#### Resolver·Fallback

- 날짜·사건명·entity field별 precision/recall 또는 accuracy
- explicit/inferred와 query span validation 정확도
- timeout, schema invalid, rate limit, network failure율
- raw BM25 fallback 성공률·p95·결과 coverage
- resolution patch 적용률과 resolver 미호출 검증

#### Extraction

- shot type Macro F1 목표 0.80 이상
- caption 정확+부분 정확 목표 85% 이상
- ASR WER 목표 40% 이하
- OCR·tag의 source별 precision과 unverified 비율

위 supporting target은 검색 목표와 별도로 보고하며 충족했다고 완벽한 metadata를 주장하지 않는다.

#### 업무 효율

- usable scene 발견 성공률: paired M0 baseline보다 높아야 함
- 첫 usable scene까지 시간
- query 수정 횟수와 문의율
- 문의 확인 시간, override 등록·replay 시간, 검수 시간/100검색
- 사전검수 건수: 목표 `0`
- 편집기자 + 검수자 총업무시간: manual baseline 대비 유지 또는 감소
- 사용 만족도: paired M0 baseline보다 높아야 함

동일 task를 같은 사용자군에 paired protocol로 수행하고 순서 효과를 통제한다. usable scene 발견 성공률과 만족도가 모두 개선되고 편집기자+검수자 총업무시간이 유지 또는 감소해야 제품 목표 달성으로 판정한다. 척도와 유의미 차이 기준은 Gate C에서 최종셋 열람 전에 동결한다.

### 14.5 Reactive override 수용 시험

| ID | Fixture | 합격 조건 |
| --- | --- | --- |
| `AC-EVL-001` | 같은 query·filter + active patch | resolver 미호출, patch version 적용 |
| `AC-EVL-002` | 같은 query, 다른 filter | patch 미적용 |
| `AC-EVL-003` | 유사 문장, 다른 fingerprint | override 미적용 |
| `AC-EVL-004` | active target scene exclude | 해당 scene processing version 미노출, 다음 후보 보충 |
| `AC-EVL-005` | scene reprocessing | 과거 exclude stale, 새 scene 자동 제외 안 됨 |
| `AC-EVL-006` | override version N→N+1 | N 비활성·이력 보존·N+1만 적용 |
| `AC-EVL-007` | feature 오류 문의 | deferred, base field·index 불변 |
| `AC-EVL-008` | explicit 사건명 + 승인 rule의 source-backed verified conflict | conflict scene 제외 |
| `AC-EVL-009` | explicit 사건명 + 사건명 missing/unverified 또는 LLM-only 추정 | scene 유지·미검증 표시 |
| `AC-EVL-010` | explicit-anchor 결과 분모 0 | false-hit `N/A`; coverage 미달이면 guard 평가 실패 |

### 14.6 P0 End-to-End 수용 시나리오

| ID | 시나리오 | 합격 조건 |
| --- | --- | --- |
| `AC-E2E-001` | timestamp transcript 방송분 등록 | 제공 segment 우선, overlap scene만 연결, 색인 성공 |
| `AC-E2E-002` | transcript 없는 방송분 | ASR 실행; 실패해도 사용 가능한 VLM·OCR 경로 유지 |
| `AC-E2E-003` | 사전검수 없는 자동 색인 | `review_required` 없이 searchable, unverified 표시 |
| `AC-E2E-004` | 일반 한국어 검색 | BM25+dense+structured score, 근거·두 날짜·Preview 제공 |
| `AC-E2E-005` | resolver 장애 | raw BM25 degraded 결과와 명시 경고 |
| `AC-E2E-006` | explicit 2022 + verified 2023 | conflict scene 제외 |
| `AC-E2E-007` | explicit 2022 + 날짜 없음 | scene 유지, 미검증 표시 |
| `AC-E2E-008` | entity/location mismatch | hard 제외되지 않음 |
| `AC-E2E-009` | 원클릭 문의 | comment 없이 exact result context로 pending 생성 |
| `AC-E2E-010` | resolution patch | 저장·exact replay·resolved, resolver 미호출 |
| `AC-E2E-011` | exclude scene | exact replay에서 target 제외·Top 10 보충 |
| `AC-E2E-012` | feature correction 요청 | deferred_p1, metadata·index 불변 |
| `AC-E2E-013` | snapshot 저장 실패 | degraded 결과, 문의 비활성, 미저장 고지 |
| `AC-E2E-014` | 외부 전송 거부 clip | GMS outbound 0건, 로컬/failure 상태 명시 |
| `AC-E2E-015` | 주제 독립 smoke test | 명절 전용 Event 분기 없이 같은 schema·pipeline 사용 |
| `AC-E2E-016` | 승인 사건명 rule 사용 | source-backed conflict만 제외, missing/unverified·LLM-only는 유지; rule 미승인 시 hard guard 비활성 |

### 14.7 확정 설계 추적성

| 확정 설계 절 | FRD 반영 |
| --- | --- |
| §1 제품 철학 | §1.1, §14.4 업무 효율 |
| §2 허용·금지 주장 | §1.6, §13.4~13.5, §16.3 |
| §3 P0/P1 | §1.3~1.4 |
| §4 snapshot/cache/override | §3.6, §6, §7.5 |
| §5 Provenance | §3.1~3.2, §10.3 |
| §6 Resolver/Fallback | §6.2~6.4, §13.2 |
| §7~8 false-hit/anchor | §3.4~3.5, §7.3~7.4 |
| §9 Pinned Override | §6.5~6.7, §10.5 |
| §10 문의→검수 | §9; feature correction은 승인 결정에 따라 P1 |
| §11 통합 흐름 | §2.4~2.5, §7.1 |
| §12 데이터 모델 | §10; Event·review status 제거, 필요한 snapshot/version 보완 |
| §13 평가 | §14 |
| §14 실패·UI | §8, §12, §13.2 |
| §15 삭제·변경 | §0.3, §1.4, §15 |

### 14.8 시연 Runbook 기준

| 시간 | 실제 동작 |
| --- | --- |
| 0:00~0:20 | 현행 keyword 검색의 누락·명시 충돌 문제와 제품 목표 |
| 0:20~0:50 | 짧은 영상 실제 scene·VLM/OCR/ASR 처리와 provenance |
| 0:50~1:10 | 한국어 query resolution·snapshot·미검증 설명 |
| 1:10~1:35 | BM25+dense 검색, verified date conflict guard, Preview |
| 1:35~1:50 | 원하지 않은 결과 `이상해요`와 자동 context |
| 1:50~2:15 | reviewer exact override와 같은 query replay |
| 2:15~2:30 | Recall·false-hit·총업무시간, GMS·한계 고지 |

시연에 feature/tag/OCR/caption 실제 교정은 포함하지 않는다. preprocessed corpus 또는 사전 녹화 fallback을 사용하면 화면과 발표에서 구분한다.

---

## 15. Gate와 최종 결정 기록

### 15.1 Gate 상태

| Gate | 상태 | 의미 |
| --- | --- | --- |
| **Gate A** | **Closed** | P0 핵심 범위·데이터·상태·검색·override·문의 계약 확정 |
| **Gate S** | **정책 승인 / 외부 실행 전 증빙 조건** | 제한 전송 정책은 확정. 실제 dataset·provider별 권리·보관·학습·삭제 증빙 없이는 outbound 금지 |
| **Gate B** | 데이터 확보 시 동결 | confidence, taxonomy, timeout, ranking parameter 등 근거 기반 설정 |
| **Gate C** | 평가·시연 전 동결 | split manifest, 측정 profile, annotation guide, 최종 report |
| **Gate D** | 권장 기본값으로 진행 | 제품 범위를 바꾸지 않는 구현·UI 세부값 |

Gate B/C/D의 후속 수치는 Gate A 미완료가 아니다. 이들은 데이터를 보기 전 숫자를 창작하지 않기 위한 실행 단계다.

### 15.2 Gate A 최종 결정

| ID | 확정 결정 | 구현 영향 |
| --- | --- | --- |
| `DEC-A-001` | 동일 검색은 canonical query + explicit filters + normalization version fingerprint | filter가 다르면 override 공유 금지; standalone rewrite 삭제 |
| `DEC-A-002` | override는 append-only version, pending replay 후 active promotion, scene reprocessing 시 stale | active patch 1개/full scope, active exclude 1개/full scope·scene processing version; 자동 remap 금지 |
| `DEC-A-003` | Entity·OCR·Caption 등 피처 교정은 P1 | P0 field override·correction reindex 없음; valid issue는 deferred_p1 |
| `DEC-A-004` | 내부 inquiry queue와 replay 완료 조건 | pending→reviewing→resolved/dismissed/deferred; editor inquiry, reviewer action |
| `DEC-A-005` | verified conflict만 explicit 날짜·승인 사건명에서 hard | 누락·unverified 유지; entity/location mismatch soft |
| `DEC-A-006` | 사전검수 제거, field 3상태, immutable resolution/result snapshot | 최초 serving queued→processing→ready/failed; 재처리 run 상태 분리; review_required·scene review status 없음 |
| `DEC-A-007` | PostgreSQL+JSONB 정본, 검색 index 파생 | Event/field correction 없이 관계·snapshot·override 무결성 보장 |

### 15.3 Gate S 승인 정책

| ID | 확정 정책 | 실행 전 확인 |
| --- | --- | --- |
| `DEC-S-001` | component별 endpoint·category·max-bytes 최소 allowlist | GMS 제품·계정·region·model·증빙 version |
| `DEC-S-002` | `no/unknown`·allowlist 밖 전송 fail-closed | AI Hub/KBS 데이터 이용조건 |
| `DEC-S-003` | P0 full video·전체 transcript·전체 OCR 전송 금지 | provider retention/training/deletion |
| `DEC-S-004` | secret 비노출·TLS·content-free audit log | 실제 secret injection·network test |
| `DEC-S-005` | GMS 사용 고지, “외부 전송 없음” 주장 금지 | 발표·UI 문구 검수 |

### 15.4 Gate B — 근거 확보 후 설정 동결

| 항목 | 동결 근거 | 기본 원칙 |
| --- | --- | --- |
| VLM scene type taxonomy·caption guide | scene Gold Set | 임의 class 추가 금지, unknown 허용 |
| OCR 병합·verified confidence | OCR 개발셋 | verbatim·frame evidence 보존 |
| ASR verified/강등 threshold | CC cohort WER | WER 40% 초과 효과 보고 |
| resolver timeout·prompt/model | latency·field 정확도 | p95 검색 10초, 동기 retry 없음 |
| incident deterministic rule | 사람 Gold label·근거 | 승인 rule 없으면 soft |
| BM25 field·dense model·RRF·boost | B0~B6 ablation | search version으로 묶음 |
| candidate pool·clip diversity | Recall·coverage·UI | Top 10 분모 조작 금지 |

### 15.5 Gate C — 평가·시연 전 동결

- group split manifest와 최종셋 접근 통제
- annotation guide·교차 라벨링·Gold conflict 판정
- result coverage 최소 허용값과 false-hit 분모 0의 `N/A` 처리
- paired 만족도 척도·유의미 개선 판정과 총업무시간 protocol
- benchmark hardware·반복·percentile·측정 구간
- M0·B0a/B0b·B0p~B6의 cohort별 동일 조건과 report template
- editor/reviewer paired workflow protocol
- GMS·fallback·preprocessed data가 표시된 demo runbook
- source·license notice와 외부 전송 증빙

### 15.6 Gate D — 구현 기본값

- timestamp는 UTC 저장, Asia/Seoul 표시
- scene interval은 `[start,end)`
- Preview는 scene end 이후 계속 재생 + 구간 다시 재생
- loopback bind, ID 기반 media, Range 지원
- status update는 expected version, create는 idempotency key
- local rolling log, 외부 telemetry off
- 정확 file limit, retry count, page layout, endpoint 세부 이름은 versioned config로 관리

### 15.7 변경 통제

다음 변경은 Gate A 재개와 문서 version 증가가 필요하다.

- P0에 field correction·reindex를 다시 포함
- semantic 또는 유사 query에 override 확대
- Event catalog·assignment 재도입
- missing/unverified를 hard exclude로 변경
- entity·location mismatch를 hard로 변경
- snapshot을 cache처럼 재사용
- inquiry를 자동학습·전역 ranking 변경에 사용
- PostgreSQL이 아닌 다른 정본 저장소로 전환

모델·threshold·boost의 실측 조정은 같은 schema·안전 경계를 지키고 version을 올리면 Gate A를 재개하지 않는다.

---

## 16. 구현·완료 체크리스트와 한계

### 16.1 1주차 — 계약과 Vertical Slice

- [ ]  PostgreSQL migration: clip/run/stage/scene/evidence/tag/index generation·outbox/search/snapshot/override/inquiry resolution/external policy·audit
- [ ]  Event·review item·field override table이 migration에 없음
- [ ]  짧은 clip 등록→scene/frame→최소 metadata→index→검색→Preview E2E
- [ ]  두 nullable 날짜와 transcript priority
- [ ]  query normalization/fingerprint fixture
- [ ]  resolver JSON Schema와 raw BM25 fallback
- [ ]  resolution/result snapshot 저장
- [ ]  GMS adapter allowlist·mock fail-closed

### 16.2 2주차 — P0 통합

- [ ]  VLM/OCR/ASR/entity extraction과 field evidence
- [ ]  BM25+dense+structured score·RRF
- [ ]  verified match/unverified/conflict guard
- [ ]  결과 카드·근거·미검증·degraded·Preview
- [ ]  `이상해요` 문의와 immutable context
- [ ]  internal reviewer queue와 optimistic locking
- [ ]  resolution patch·exclude scene의 pending replay→active promotion lifecycle
- [ ]  exact replay와 immutable inquiry resolution·close
- [ ]  scene reprocessing·stale override test

### 16.3 3주차 — 평가·보안·시연

- [ ]  개발/최종 group split과 Gold Set manifest
- [ ]  M0·B0~B6 ablation
- [ ]  [Recall@10](mailto:Recall@10), [nDCG@10](mailto:nDCG@10), MRR, [false-hit@10](mailto:false-hit@10), coverage
- [ ]  resolver accuracy·failure·fallback
- [ ]  editor+reviewer paired work-time evaluation
- [ ]  outbound allowlist·license·provider profile evidence
- [ ]  media traversal·XSS·role·idempotency·concurrency test
- [ ]  pipeline interruption·index rebuild·snapshot failure test
- [ ]  demo runbook와 preprocessed/recorded fallback 표시

### 16.4 P0 최종 완료 판정

- [ ]  §15.2의 Gate A 최종 결정 7개가 코드·DB·UI·test에 반영됨
- [ ]  Event 관련 기능·table·API·metric·UI가 없음
- [ ]  `review_required`, 사전검수, scene 전체 review status가 없음
- [ ]  P0 field correction·correction reindex가 없음
- [ ]  standalone LLM rewrite와 semantic cache가 없음
- [ ]  exact patch는 resolver 전, exact exclude는 ranking 후 적용됨
- [ ]  verified conflict만 허용 범위에서 hard 제외됨
- [ ]  missing/unverified 결과가 유지되고 표시됨
- [ ]  resolver/dense/snapshot 실패가 정해진 경로와 경고를 사용함
- [ ]  문의는 자체로 metadata·index·전역 ranking을 변경하지 않음
- [ ]  replay 없는 override 문의를 resolved로 닫지 않음
- [ ]  PostgreSQL 정본에서 index rebuild가 가능함
- [ ]  version chain과 평가 artifact가 재현 가능함
- [ ]  Gate S 미승인 payload가 외부로 전송되지 않음
- [ ]  목표 미달·fallback·GMS 사용을 숨기지 않고 보고함

### 16.5 알려진 한계와 사용자 고지

- 검색 결과는 송출 후보 탐색을 돕지만 사실·최신성·저작권·송출 적합성을 보증하지 않는다.
- 날짜와 사건명 정보가 없거나 미검증이면 관련 없는 결과가 남을 수 있다.
- entity·기관·장소 mismatch는 P0에서 hard 제외하지 않는다.
- exact override는 문구와 explicit filter가 같은 검색에만 적용되며 유사 질의에는 적용되지 않는다.
- 피처 자체 오류는 P0에서 고치지 않고 P1로 이관한다.
- 문의가 쌓인다고 모델이 학습하거나 전역 검색이 자동 개선되지 않는다.
- P0는 local single-user demo 범위이며 운영급 인증·MAM·공개 배포를 제공하지 않는다.
- GMS를 사용하는 구성은 승인된 데이터를 외부로 전송할 수 있으며 “데이터를 보내지 않는다”고 주장하지 않는다.
- 자체 호스팅 adapter로 교체할 수 있지만 동등한 모델 품질·성능은 별도 검증 대상이다.

---

**v2.2 최종 결론:** N-Pick P0는 Event·사전검수·피처 교정 시스템이 아니다. 장면 단위 추출 결과를 검색 시점에 임시 해석하고, 불확실한 결과는 남겨 표시하며, 명시 anchor의 검증된 충돌만 차단한다. 편집기자는 원클릭으로 이상 결과를 제보하고 검수자는 exact query scope의 두 pinned override만 등록한다. 이 범위에서 Gate A는 마감되었다.
