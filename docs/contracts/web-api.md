# 웹 API 계약

브라우저 FE와 서비스 서버 사이의 HTTP 계약 정본이다. 내부 파이프라인 워커 계약은 [job-api.md](job-api.md)를 따른다.

## 1. 범위와 상태

모든 경로의 기본 prefix는 `/api/v1`이다. 표의 경로는 이 prefix를 포함하지 않는다.

| 상태      | 의미                                                                           |
| --------- | ------------------------------------------------------------------------------ |
| 연결됨    | BE endpoint와 FE 호출·검증이 모두 존재한다                                     |
| 계약 확정 | 요청·응답 계약은 확정됐지만 실제 호출 또는 BE endpoint가 남아 있다             |
| BE 구현   | BE endpoint는 존재하지만 FE가 아직 데모 데이터를 사용한다                      |
| 명세 필요 | FRD가 요구하는 기능만 확정됐다. 경로와 JSON을 이 문서에서 임의로 만들지 않는다 |

| 기능                       | Method | 경로                                        | 상태      | FE 후속                             |
| -------------------------- | ------ | ------------------------------------------- | --------- | ----------------------------------- |
| CSRF 준비                  | GET    | `/auth/csrf`                                | 연결됨    | 없음                                |
| 로그인                     | POST   | `/auth/login`                               | 연결됨    | 없음                                |
| 현재 계정                  | GET    | `/auth/me`                                  | 연결됨    | 없음                                |
| 로그아웃                   | POST   | `/auth/logout`                              | 연결됨    | 없음                                |
| 영상 등록                  | POST   | `/clips`                                    | 연결됨    | 없음                                |
| 장면 검색                  | POST   | `/search`                                   | BE 구현   | 실제 호출·화면 바인딩               |
| 영상 재생                  | GET    | `/media/{clipId}`                           | BE 구현   | 공통 플레이어·문의 상세·검색 카드 연결 |
| 장면 대표 이미지           | GET    | `/scenes/{sceneId}/thumbnail`               | BE 구현   | 결과 카드·문의 큐 thumbnail 바인딩  |
| 문의 접수                  | POST   | `/search/results/{resultId}/inquiries`      | BE 구현   | 문의 생성 바인딩                    |
| 문의 설명 수정             | PATCH  | `/inquiries/{feedbackId}`                   | BE 구현   | 편집자 문의 기록 바인딩과 함께 연결 |
| 검수 문의 목록             | GET    | `/review/inquiries`                         | BE 구현   | 검수 게시판 바인딩                  |
| 검수 문의 상세             | GET    | `/review/inquiries/{feedbackId}`            | BE 구현   | 검수 상세 바인딩                    |
| 검수 시작                  | POST   | `/review/inquiries/{feedbackId}/claim`      | BE 구현   | 검수 흐름 바인딩                    |
| 처리 결과 선택             | PUT    | `/review/inquiries/{feedbackId}/resolution` | BE 구현   | 검수 흐름 바인딩                    |
| 내 문의 기록 목록          | GET    | `/inquiries`                                | 연결됨    | 없음                               |
| 내 문의 기록 상세          | GET    | `/inquiries/{feedbackId}`                   | 연결됨    | 없음                               |
| 내 검색 기록 목록·상세     | 미정   | 미정                                        | 명세 필요 | -60 선행, 편집자 하단 기록 시트 바인딩 |
| 영상 처리 목록             | GET    | `/clips`                                    | 연결됨    | 없음                               |
| 영상 처리 상세             | GET    | `/clips/{id}`                               | 연결됨    | 없음                               |
| 영상 처리 재시도           | 미정   | 미정                                        | 명세 필요 | 재처리 요청 연결                    |
| 태그 교정 후보             | POST   | `/review/inquiries/{feedbackId}/tag-correction-candidate` | BE 구현 | 검수 교정 바인딩            |
| 해석 교정 후보             | POST   | `/review/inquiries/{feedbackId}/parse-patch-candidate`    | BE 구현 | 검수 교정 바인딩            |
| 장면 제외 후보             | POST   | `/review/inquiries/{feedbackId}/scene-exclude-candidate`  | BE 구현 | 검수 교정 바인딩            |
| 교정 확정                  | POST   | `/review/inquiries/{feedbackId}/confirm`                  | BE 구현 | 검수 재검색·확정 바인딩     |
| 검색 규칙 사용 중단         | PATCH  | `/review/search-rules/{ruleId}`             | BE 구현   | 검수 규칙 관리 바인딩               |

## 2. 공통 규약

### 2.1 인증과 요청 보호

- 로그인 세션은 `JSESSIONID` cookie로 유지한다. FE는 `credentials: include`로 요청한다.
- 브라우저의 변경 요청은 먼저 `GET /auth/csrf`로 `XSRF-TOKEN` cookie를 준비하고 같은 값을 `X-XSRF-TOKEN` header로 보낸다.
- 세션 token과 비밀번호를 FE 저장소에 저장하지 않는다.
- 서버는 사용자 ID를 요청 본문에서 신뢰하지 않고 로그인 세션에서 결정한다.
- 역할은 `EDITOR`, `REVIEWER`다. 검색은 두 역할, 영상 등록·검수 API는 `REVIEWER`만 허용한다.
- FE는 API redirect를 따르지 않는다.

### 2.2 JSON envelope

영상 byte 응답을 제외한 성공 JSON은 다음 envelope를 사용한다.

```json
{
  "isSuccess": true,
  "code": "COMM_200",
  "message": "Request succeeded",
  "data": {}
}
```

본문 없는 성공은 `data`를 생략할 수 있다.

실패 JSON은 다음 모양이다.

```json
{
  "isSuccess": false,
  "code": "COMM_400_001",
  "message": "Request validation failed",
  "timestamp": "2026-09-11T03:00:00Z",
  "path": "/api/v1/example",
  "data": {
    "fieldName": "입력값을 확인해 주세요."
  }
}
```

- HTTP 실패와 `isSuccess: false` 중 하나라도 실패면 FE는 실패로 처리한다.
- `COMM_400_001`의 `data`는 field별 검증 메시지일 수 있다.
- 요청 식별자는 `X-Request-Id` header 또는 envelope의 `requestId`로 전달한다.
- 임의 JSON의 `message`, 서버 경로, 예외 문자열은 사용자 메시지로 신뢰하지 않는다.

### 2.3 값 형식

- 새로 확정하는 브라우저용 bigint ID는 양의 십진 문자열로 보낸다. 영상 등록과 검색 계약은 이 규칙을 따른다.
- 현재 인증·문의 BE 응답의 ID는 JSON number다. FE 연동 전에 §8의 ID 정합화 결정을 끝낸다.
- 날짜는 실제 존재하는 `YYYY-MM-DD`다.
- 시각은 ISO-8601 UTC 문자열, 영상 위치는 정수 millisecond다.
- 장면 구간은 시작 포함·종료 제외 `[start_time_ms, end_time_ms)`이며 종료가 시작보다 커야 한다.

## 3. 인증 API — 연결됨

### 3.1 CSRF 준비

`GET /auth/csrf`

요청 body는 없다. 성공하면 서버가 `XSRF-TOKEN` cookie를 설정하고 body 없는 성공 envelope를 보낸다.

### 3.2 로그인

`POST /auth/login`

```json
{
  "loginId": "editor",
  "password": "password"
}
```

두 필드는 빈 문자열일 수 없다. 성공하면 새 session ID를 발급하고 다음 `data`를 보낸다.

```json
{
  "memberId": 398021847361024,
  "loginId": "editor",
  "role": "EDITOR"
}
```

현재 wire의 `memberId`는 JSON number다. FE 공통 client가 원문 숫자를 십진 문자열로 보존한 뒤 화면 모델에 전달한다.

| 오류             | HTTP | 의미                        |
| ---------------- | ---- | --------------------------- |
| `COMM_400_001`   | 400  | 필수 입력 검증 실패         |
| `MEMBER_401_001` | 401  | 아이디 또는 비밀번호 불일치 |

### 3.3 현재 계정

`GET /auth/me`

요청 body는 없다. 성공 `data`는 로그인 응답과 같다. 인증되지 않았거나 session이 만료됐으면 `COMM_401`을 보낸다.

### 3.4 로그아웃

`POST /auth/logout`

요청 body는 없다. session을 무효화하고 body 없는 성공 envelope를 보낸다.

## 4. 영상 등록 API — 연결됨

`POST /clips`

- Content-Type: `multipart/form-data`
- Header: `Idempotency-Key` 필수, 공백 불가, 최대 128자
- 성공 HTTP: `201 Created`

| Form field                      | 타입    | 필수   | 규칙                                            |
| ------------------------------- | ------- | ------ | ----------------------------------------------- |
| `video`                         | file    | 필수   | 1개, 실제 영상 내용·형식·크기·길이 검사         |
| `source_type`                   | string  | 필수   | `broadcast` 또는 `archive`                      |
| `title`                         | string  | 선택   | 공백은 생략, 최대 500자                         |
| `broadcast_date`                | date    | 선택   | `broadcast`에서만 허용                          |
| `filmed_date`                   | date    | 선택   | 두 source 모두 허용                             |
| `subtitle`                      | file    | 선택   | 1개, UTF-8 SRT/VTT 또는 승인된 JSON             |
| `script_text`                   | string  | 선택   | UTF-8 TXT를 FE가 읽어 문자열로 전송             |
| `rights_confirmed`              | boolean | 필수   | `true`여야 등록 가능                            |
| `external_processing_confirmed` | boolean | 조건부 | 현재 처리 설정이 외부 AI 동의를 요구하면 `true` |

자료 영상 `archive`에는 `broadcast_date`를 보내지 않는다. 날짜를 모두 생략해도 등록할 수 있다.

성공 envelope의 `data`:

```json
{
  "clip_id": "398021847361024",
  "pipeline_run_id": "398021847361025",
  "status": "queued"
}
```

수동 재시도 규칙:

- 네트워크·취소·비정상 응답·5xx·`CLIP_409_002`·`CLIP_503_008`: 같은 snapshot과 `Idempotency-Key`를 사용한다.
- `CLIP_409_001`·`CLIP_409_003`: 새 요청으로 취급하고 새 key를 사용한다.
- 입력이 바뀌면 기존 snapshot과 key를 폐기한다.

FE가 직접 처리하는 주요 오류:

| 오류                                          | 입력/동작                  |
| --------------------------------------------- | -------------------------- |
| `CLIP_400_001`, `CLIP_400_005`~`CLIP_400_008` | 영상 파일                  |
| `CLIP_400_002`, `CLIP_400_003`                | 영상 종류·자료 영상 방송일 |
| `CLIP_400_004`                                | 제목                       |
| `CLIP_400_009`                                | 이용 권한 확인             |
| `CLIP_400_010`                                | 외부 처리 확인             |
| `CLIP_400_011`                                | 날짜                       |
| `CLIP_400_012`                                | 자막 내용                  |
| `CLIP_409_001`~`CLIP_409_003`                 | 멱등 요청 상태             |
| `CLIP_503_001`~`CLIP_503_010`                 | 검사·저장·등록 연계 실패   |

## 5. 장면 검색 API

`POST /search`

FE URL 상태와 wire 요청의 대응:

| FE URL                         | 요청 JSON                                     |
| ------------------------------ | --------------------------------------------- |
| `q`                            | `query`                                       |
| `broadcastFrom`, `broadcastTo` | `explicit_filters.broadcast_date.from`, `.to` |
| `filmingFrom`, `filmingTo`     | `explicit_filters.filmed_date.from`, `.to`    |

선택하지 않은 날짜 종류는 key 자체를 생략한다. `from`과 `to`는 모두 포함되는 날짜다.

```json
{
  "query": "명절 교통",
  "explicit_filters": {
    "broadcast_date": { "from": "2026-09-01", "to": "2026-09-03" },
    "filmed_date": { "from": "2026-08-28", "to": "2026-08-29" }
  }
}
```

날짜 필터가 없을 때도 `explicit_filters`는 빈 object로 보낸다.

성공 응답 전체 모양:

```json
{
  "isSuccess": true,
  "code": "COMM_200",
  "message": "Request succeeded",
  "data": {
    "search_execution_id": "398021847361024",
    "status": "succeeded",
    "degraded_reasons": [],
    "query_resolution_status": "resolved",
    "has_applied_review_rule": false,
    "guard_summary": {
      "excluded_result_count": 0,
      "reasons": []
    },
    "shortage_reasons": ["candidate_pool_exhausted"],
    "results": [
      {
        "search_result_id": "398021847361025",
        "scene_id": "398021847361026",
        "clip_id": "398021847361027",
        "rank": 1,
        "display_name": "KBC 뉴스9 · 설 연휴 교통",
        "scene_description": "서울역 귀성 인파",
        "start_time_ms": 42000,
        "end_time_ms": 49000,
        "broadcast_date": {
          "value": "2026-02-14",
          "verification_status": "verified"
        },
        "filmed_date": {
          "value": null,
          "verification_status": "unknown"
        },
        "shot_type": "b_roll",
        "scene_type": "역사 인파",
        "matched_keywords": ["서울역", "귀성객"],
        "match_evidence": [
          {
            "field": "ocr",
            "value": "서울역 · 설 연휴 귀성객",
            "source": "keyframe_ocr",
            "verification_status": "verified"
          }
        ]
      }
    ]
  }
}
```

검색 결과 배열의 정확한 위치는 `data.results`다.

`query_resolution_status`는 해석의 완료·대체 검색 상태이며 해석 내용 자체가 아니다. 현재 계약에는 인물·장소·날짜 등 실제 해석 내용을 FE에 전달하는 필드가 없다. FE 상단은 원문을 `검색어`로 표시하고 해석 상태만 서버 응답으로 안내한다. 검색어 토큰이나 결과의 `matched_keywords`로 해석 내용을 합성하지 않는다. 실제 해석 칩 연결(S15P21A501-118/167)의 선행 작업은 S15P21A501-59에서 공개 응답 필드·예시와 규칙 적용 후 최종 해석 여부, fallback·내용 없음 규칙을 확정하는 것이다. 이는 과거 기록 복원용 저장 계약(S15P21A501-60)과 구분하며, 신규 필드 이름·형식은 아직 확정하지 않는다.

### 5.1 응답 불변식

- `results`는 0~10개다. 서버가 정한 `rank` 오름차순을 FE가 다시 정렬하지 않는다.
- 각 `rank`는 배열 위치와 같은 1부터 시작하는 연속 정수다.
- 한 응답 안의 `scene_id`와 null이 아닌 `search_result_id`는 중복되지 않는다.
- `match_evidence`는 1개 이상이다. `field`는 `caption`, `ocr`, `transcript`, `tag` 중 하나다.
- `match_evidence[].value`는 **null일 수 있다.** 설명·대사·화면 글자·태그가 모두 없고 의미 검색 유사도만으로 올라온 장면이 있고, 그때 사람이 읽을 근거가 실제로 존재하지 않는다. 서버가 문자열을 지어내지 않는다 — 지어내면 사용자가 그 값을 근거로 판단한다. FE는 이 경우 근거 없이 카드만 보여 준다.
- `guard_summary.excluded_result_count`는 **장면 기준**이다. 제외 판정 수가 아니라 제외된 결과 수이며, 한 장면이 guard와 승인된 장면 제외에 모두 걸려도 1로 센다.
- `shot_type`은 `anchor`, `interview`, `b_roll`, `unknown` 중 하나다. 저장값이 이 넷 밖이면 응답에서만 `unknown`으로 좁히고 기록에는 원문이 남는다.
- **`display_name`은 null일 수 있다.** 출처인 `clip.title`이 nullable이라 제목 없이 등록된 영상이 있다. 서버가 임의 문자열로 메우지 않는다 — 메우면 기록에서 「제목이 없었다」와 「제목이 이랬다」를 구분할 수 없다(FRD §7.2). 대체 표기는 FE가 정한다. `scene_description`도 같은 이유로 null일 수 있다.
- 날짜 `value`가 null이면 `verification_status`는 `unknown`이다. 값이 있으면 `verified` 또는 `unverified`다.
- `status=succeeded`면 `degraded_reasons`는 비어 있다.
- **응답의 `status`와 기록의 `search_execution.status`는 다를 수 있다.** 승인된 해석 규칙이 충돌·비호환·실패로 건너뛰어지면 기록은 `degraded`지만 응답은 `succeeded`다. `degraded_reasons` 어휘가 아래 세 값으로 닫혀 있어 그 사유를 실을 자리가 없기 때문이고, 사용자가 받은 결과 자체는 온전하기 때문이다. 규칙별 적용·건너뜀·실패는 `applied_rules_json`이 남긴다. 기록을 읽는 화면은 이 차이를 알고 `search_execution.status`를 응답 `status`로 그대로 보여 주지 않는다.
- `matched_keywords`는 **확장어로 걸린 단어를 포함한다.** 어느 것이 AI가 넓힌 말인지 응답이 구분하지 않는다 — 그 표기는 아직 계약에 없다. 검색에는 쓰고 근거에는 빼면 확장어로만 걸린 장면이 「왜 나왔는지 모르는 결과」가 되므로 포함 쪽을 택했다.
- `status=degraded`면 `resolver_fallback`, `dense_unavailable`, `snapshot_save_failed` 중 하나 이상이다.
- `query_resolution_status=fallback` 여부는 `resolver_fallback` 포함 여부와 일치한다.
- `snapshot_save_failed`면 `search_execution_id`와 모든 `search_result_id`는 null이다. 이 결과로 문의할 수 없다.
- `guard_summary.excluded_result_count`가 0이면 `reasons`도 비어 있다. 허용 reason은 `explicit_date_conflict`, `approved_incident_conflict`, `approved_scene_exclusion`이다.
- 결과가 10개 미만이면 `shortage_reasons`가 1개 이상이어야 한다. 허용 reason은 `candidate_pool_exhausted`, `guard_excluded`다.
- 썸네일·영상에 서버 파일 경로나 임의 URL을 싣지 않는다. ID 기반 제공 API를 사용한다 — 썸네일은 §6.7, 영상은 §6.1이며 FE가 `scene_id`·`clip_id`로 주소를 조립한다.

### 5.2 오류 경계

검색이 **실패**했을 때만 오류로 나간다. 일부 기능만 빠진 경우는 200에 `status=degraded`다. 가르는 기준은 「결과를 줄 수 있는가」 하나이며, 실패를 결과 0건의 성공 응답으로 위장하지 않는다(FRD F-06 완료 기준).

`POST /search`가 내는 오류는 다음과 같다.

| 오류           | 의미                                        | 사용자 안내       |
| -------------- | ------------------------------------------- | ----------------- |
| `SRCH_400_101` | 검색어에서 검색할 수 있는 단어를 찾지 못함  | 검색어 수정       |
| `SRCH_400_004` | 명시 필터의 시작일이 종료일보다 늦음        | 날짜 수정         |
| `SRCH_503_011` | 승인된 해석 규칙을 읽지 못함                | 재시도            |
| `SRCH_503_012` | 기본 단어 검색을 수행하지 못함              | 재시도            |
| `SRCH_503_013` | 검색 실행을 기록하지 못해 중단              | 재시도            |
| `SRCH_400_003` | 날짜 필터에 `from`·`to` 중 하나만 옴       | 날짜 수정         |
| `SRCH_503_201` | 승인된 장면 제외 규칙을 확인하지 못함       | 재시도            |
| `SRCH_503_301` | 의미 검색 저장소를 읽지 못함                | 재시도            |
| `SRCH_500_002` `SRCH_500_003` `SRCH_500_004` | 순위 결합 불변식 위반 — 서버 결함 | 재시도 무의미, 문의 |

`SRCH_503_011`은 사람의 결정을 조용히 건너뛰지 않기 위한 실패다. 규칙 없이 검색하면 검수자가 승인한 교정이 빠진 결과가 정상인 것처럼 나간다(§6.2).

규칙 조회 어댑터는 해석 규칙과 장면 제외 규칙에 같은 코드를 던진다. 조립이 무엇을 조회했는지 알고 있으므로 해석 규칙 실패는 `SRCH_503_011`로 좁혀 올리고, 장면 제외 규칙 실패만 `SRCH_503_201`로 나간다.

`SRCH_400_003`은 한쪽만 온 날짜를 열린 구간으로 보정하지 않기 때문이다 — 보정하면 사용자가 지정하지 않은 조건을 서버가 만들고, 그 값이 F-06의 hard 제외 근거가 된다.

`SRCH_500_002~004`는 순위 결합이 자기 입력 불변식을 검사해 내는 코드다. 사용자 입력과 무관한 서버 결함이므로 재시도해도 같은 결과다.

`SRCH_503_013`은 실행을 **열지** 못한 경우다. 결과 계산 뒤의 저장 실패는 오류가 아니라 `snapshot_save_failed` degraded다 — 그때는 계산이 이미 끝나 미저장 상태로 줄 수 있다.

이미 존재하는 `SRCH_` 오류 어휘는 다음과 같다.

| 오류           | 의미                                  |
| -------------- | ------------------------------------- |
| `SRCH_400_001` | 정규화된 검색어가 비어 있음           |
| `SRCH_400_002` | 정규화 버전이 비어 있음               |
| `SRCH_400_003` | 필터에 null 포함                      |
| `SRCH_400_004` | 명시 필터의 시작일이 종료일보다 늦음  |
| `SRCH_400_101` | 검색 가능한 token을 만들 수 없음      |

리졸버·dense 검색 실패 뒤 기본 검색이 가능하면 HTTP 실패 대신 `degraded` 성공 응답을 사용한다. 기본 검색도 불가능하거나 활성 규칙을 안전하게 읽을 수 없으면 검색 실패로 처리하며, 공개 오류 코드는 BE endpoint 구현 전에 이 문서에 추가한다.

### 5.3 검색 실행 상세 기록 (S15P21A501-60)

`GET /search/executions/{executionId}`

검색을 실행한 본인 또는 `reviewer`가 당시 실행 snapshot을 조회한다. 타인 소유 실행과 없는 실행은 존재 여부를 구분하지 않고 `SRCH_404_001`로 응답한다. 응답은 snake_case이며 모든 `*_id`는 십진 문자열이다.

- `resolver_output.raw`는 규칙·anchor 교정 전 AI 해석, `resolver_output.verified`는 anchor 검증 후 해석이다.
- `parsed_query`는 실제 검색 계산에 사용한 최종 해석이다.
- `applied_rules`는 규칙 본문 snapshot, 적용 순서, 상태와 미적용·실패 사유를 보존한다.
- `filtered.returned_count`와 `filtered.shortage_reasons`는 실제 반환 수와 부족 사유다.
- `results[].explain`의 최상위 key는 `score`·`match`·`guard`·`display` 넷이다. `display`는 검색 당시 화면에 나간 표시값(제목·장면 설명·구간·방송/촬영일·`shot_type`·`scene_type`)이며, 조회 시 현재 장면 정보로 다시 계산하지 않는다. 내 문의 기록(S15P21A501-207)·신고 상세(S15P21A501-198)가 이 값을 그대로 쓴다.
- `search_config`를 포함한 중첩 JSON key도 모두 snake_case다. 설정 snapshot의 내부 키(`rrf_k`, `channel_weights`, `config_version`, `weight_status` 등)는 저장 당시 값 그대로 반환한다.
- 일반 검색의 `verification_context`는 null이다. 검수 replay에서만 값이 존재할 수 있다.
- `running` 실행은 아직 완결되지 않았으므로 후보·필터·결과 등 완료 시점 필드가 null 또는 빈 목록일 수 있다. 이를 성공으로 해석하지 않는다.
- `failed` 실행은 결과를 내지 못하고 끝난 것이라 `error_code`만 채워지고 해석·설정·결과 field는 null일 수 있다. 결과 0건의 성공과 구분한다.
- `succeeded`·`degraded` 실행은 `normalized_query`·`query_fingerprint`·`normalization_version`·`search_config`·`config_version`이 항상 채워져 있다. DB 제약 `ck_execution_completed_snapshot`이 이를 보장한다.

저장 lifecycle은 검색 orchestration이 호출하는 내부 계약이다. 원문·실행자만으로 `running` 행을 독립 트랜잭션에서 먼저 시작한 뒤에 리졸버를 호출하고, 리졸버 산출물은 별도 독립 트랜잭션에서 running 행에 기록한다. 계산 종료 후 실행 갱신과 `search_result` 삽입은 또 다른 독립 트랜잭션 하나로 완결한다. 시작 저장 실패 시 AI 호출과 검색 계산을 진행하지 않는다. 완료 저장 실패 시 계산 결과는 `snapshot_save_failed` degraded 응답으로 반환하며 실행·결과 ID는 모두 null이고 문의를 비활성화한다. 완료 기록은 재시도하지 않는다.

순위 계산 전에 끊긴 실행(리졸버 장애·활성 규칙 조회 실패·후보 조회 실패)은 `running`으로 두지 않고 `failed`로 닫으며 `error_code`를 남긴다. 이 닫기 호출은 실패해도 예외를 밖으로 내보내지 않는다 — 사용자에게 돌아갈 검색 실패 사유가 기록 실패에 가려지면 안 된다 (FRD §6.2). 실패 코드는 `SRCH_503_011`(활성 규칙 조회 실패)·`SRCH_503_012`(기본 단어 검색 불가)·`SRCH_503_013`(최초 저장 실패)와 그 실행을 끊은 다른 검색 코드를 쓴다.

## 6. BE 구현 완료, FE 연결 대기 API

이 절의 모양은 현재 BE controller 기준이다. FE 바인딩 전에 ID 표현과 화면 상태 mapping을 §8에 따라 정리해야 한다.

### 6.1 영상 재생

`GET /media/{clipId}`

- 선택 header: `Range: bytes=<start>-<end>`
- 성공: 영상 byte, `Content-Type`, `Accept-Ranges: bytes`, `Cache-Control: private, no-store`
- 전체 응답은 `200`, 부분 응답은 `206`과 `Content-Range`를 사용한다.
- 성공 byte에는 공통 JSON envelope를 사용하지 않는다. 실패에는 공통 실패 envelope를 사용한다.

| 오류                           | HTTP | 의미                       |
| ------------------------------ | ---- | -------------------------- |
| `CLIP_404_001`                 | 404  | clip 없음                  |
| `CLIP_404_002`                 | 404  | 원본 file 없음             |
| `CLIP_416_001`                 | 416  | 요청 byte 범위 오류        |
| `CLIP_500_003`                 | 500  | 저장 위치 오류             |
| `CLIP_503_010`, `CLIP_503_011` | 503  | 전송 또는 저장소 설정 실패 |

### 6.2 편집자 문의 접수·수정

`POST /search/results/{resultId}/inquiries`

body는 생략하거나 다음처럼 보낸다.

```json
{ "comment": "검색 조건과 다른 장면입니다." }
```

세션 사용자가 **본인이 실행한 검색**의 결과에만 문의를 접수한다. 타인이 실행한 검색 결과에 접수하면 그 문의를 통해 원 검색자의 검색어·필터가 노출되므로, 미존재와 동일하게 `FEEDBACK_404_001`로 거부한다(존재 여부 비노출).

성공 `data`의 현재 BE 모양:

```json
{
  "feedbackId": 398021847361024,
  "status": "OPEN"
}
```

`PATCH /inquiries/{feedbackId}`

```json
{ "comment": "수정한 설명" }
```

본인이 접수했고 아직 `OPEN`인 문의만 수정한다. 성공 body에는 `data`가 없다.

### 6.3 검수 문의 목록

`GET /review/inquiries?status=OPEN&page=0&size=20`

- `status`: 생략 또는 `OPEN`, `REVIEWING`, `CLOSED`; 입력 대소문자는 무시한다.
- `page`: 0부터 시작하며 음수면 0으로 정규화한다.
- `size`: 기본 20, 최소 1, 최대 100으로 정규화한다.

성공 `data`의 현재 BE 모양:

```json
{
  "items": [
    {
      "feedbackId": 398021847361024,
      "status": "OPEN",
      "resolution": null,
      "createdAt": "2026-09-11T03:00:00Z",
      "queryText": "명절 교통",
      "sceneId": 398021847361026,
      "scene": {
        "sceneId": 398021847361026,
        "clipId": 398021847361027,
        "clipTitle": "설 연휴 교통",
        "startTimeMs": 42000,
        "endTimeMs": 49000,
        "pipelineRunId": 398021847361028,
        "processingNo": 1
      },
      "hasComment": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "statusCounts": {
    "open": 1,
    "reviewing": 0,
    "closed": 0
  }
}
```

### 6.4 검수 문의 상세·시작·처리 결과

`GET /review/inquiries/{feedbackId}`는 문의, 장면, 당시 검색 실행 snapshot, 근거, 검수 이력을 반환한다. 현재 BE 응답의 snapshot JSON 필드(`explicitFiltersJson`, `parsedQueryJson`, `resolverOutputJson`, `appliedRulesJson`, `appliedExcludesJson`)는 JSON 문자열이다. FE는 이를 개발용 원문으로 직접 노출하지 않고 사용자용 모델로 변환한다.

`POST /review/inquiries/{feedbackId}/claim`

- 선택 header: `Idempotency-Key`
- 같은 검수자가 이미 잡은 `REVIEWING` 문의의 재요청은 성공한다.
- 다른 검수자가 잡았거나 종료된 문의는 `FEEDBACK_409_001`이다.

`PUT /review/inquiries/{feedbackId}/resolution`

```json
{
  "resolution": "exclude_scene",
  "note": "이 검색 조건에서 장면을 제외해야 합니다."
}
```

| `resolution`     | 결과                                    |
| ---------------- | --------------------------------------- |
| `tag_correction` | 교정 후보 단계로 이동, `REVIEWING` 유지 |
| `patch_parse`    | 교정 후보 단계로 이동, `REVIEWING` 유지 |
| `exclude_scene`  | 교정 후보 단계로 이동, `REVIEWING` 유지 |
| `no_action`      | `note` 필수, `CLOSED` 종료              |
| `deferred`       | `note` 필수, `CLOSED` 종료              |

`note`는 최대 2000자다. 성공 body에는 `data`가 없다.

문의 주요 오류:

| 오류               | HTTP | 의미                            |
| ------------------ | ---- | ------------------------------- |
| `FEEDBACK_400_001` | 400  | 종료 처리 사유 필요             |
| `FEEDBACK_400_002` | 400  | 허용되지 않은 처리 결과         |
| `FEEDBACK_403_001` | 403  | 문의 작성자 아님                |
| `FEEDBACK_403_002` | 403  | 담당 검수자 아님                |
| `FEEDBACK_404_001` | 404  | 신고 가능한 저장 검색 결과 아님(미존재·타인이 실행한 검색 동일 취급) |
| `FEEDBACK_404_002` | 404  | 문의 없음                       |
| `FEEDBACK_409_001` | 409  | 이미 다른 검수가 시작됨         |
| `FEEDBACK_409_002` | 409  | 수정 가능한 상태 아님           |
| `FEEDBACK_409_003` | 409  | 처리 결과를 기록할 수 없는 상태 |

`PATCH /review/search-rules/{ruleId}` (S15P21A501-86)

검수자가 승인된 교정 규칙을 사용 중단한다(F-11). 규칙 본문과 과거 실행 기록은 보존되고 하드 삭제하지 않는다.

```json
{ "active": false, "reason": "장소로만 해석돼 사건 검색을 놓침" }
```

- `active`는 `false`만 직접 처리(중단)한다. 중단은 즉시 반영돼 이후 검색에서 적용되지 않는다.
- **재사용(`active: true`)은 검증 없이 켤 수 없다** — 현재 조건에서 재검증·승인하는 경로(-83→-84)를 거쳐야 하며, 이 엔드포인트에 오면 `409 SRCH_409_222`로 거부한다.
- `reason`은 필수(최대 500자). 받아서 검증하되 별도 감사 이력으로 저장하지 않는다(FRD가 켜기·끄기의 수행자·사유·시각 전체 복원을 de-scope).
- 성공은 body 없는 `200`.

| 오류            | HTTP | 의미                          |
| --------------- | ---- | ----------------------------- |
| `SRCH_403_221`  | 403  | 검수자 아님                   |
| `SRCH_404_221`  | 404  | 규칙 없음                     |
| `SRCH_409_221`  | 409  | 이미 사용 중단된 규칙         |
| `SRCH_409_222`  | 409  | 검증 없이 재활성화 불가       |

`POST /review/inquiries/{feedbackId}/tag-correction-candidate` (S15P21A501-160)

검수 중(`REVIEWING`)이고 처리 결과가 `tag_correction` 또는 `patch_parse`(태그·해석 모두 잘못, F-09)인 신고에서, 담당 검수자가 태그 변경안 목록을 후보로 저장한다. 저장 근거는 `confirmed=false`로 대기하며 확정(-84) 전까지 검색·해석에 반영되지 않는다.

```json
{
  "operations": [
    { "action": "APPROVE", "scope": "SCENE", "tagType": "location", "matchValue": "제주도", "displayName": "제주도" }
  ]
}
```

- `operations`는 최소 1개. 교체는 `REJECT`+`APPROVE` 두 항목으로 보낸다. `action`은 `APPROVE|REJECT|WITHDRAW`, `scope`는 `SCENE|CLIP`.
- `tagType`은 11종 어휘, `matchValue`는 서버가 정규화한다(NFKC·불가시 문자 제거). 범위는 신고 컨텍스트의 장면/클립으로만 한정되어 임의 대상을 지정할 수 없다.
- 성공 `201` body `data`: `{ feedbackId, created, evidenceIds }`. id는 정밀도 보존을 위해 문자열(TSID)이다.

| 오류               | HTTP | 의미                              |
| ------------------ | ---- | --------------------------------- |
| `TAG_400_001`      | 400  | 변경안이 비어 있음                |
| `TAG_400_002`      | 400  | 알 수 없는 태그 유형              |
| `TAG_400_003`      | 400  | 정규화 후 빈 태그 값              |
| `TAG_403_001`      | 403  | 검수자 아님                       |
| `TAG_403_002`      | 403  | 담당 검수자 아님                  |
| `TAG_404_001`      | 404  | 신고 없음                         |
| `TAG_409_001`      | 409  | 검수 중이 아님                    |
| `TAG_409_002`      | 409  | 태그·해석 교정으로 처리된 신고 아님 |

`POST /review/inquiries/{feedbackId}/scene-exclude-candidate` (S15P21A501-82)

검수 중(`REVIEWING`)이고 처리 결과가 `exclude_scene`인 신고에서, 담당 검수자가 신고 장면의 제외 후보를 저장한다. 후보는 `search_rule`에 `active=false`로 대기하며 검증(-83)·확정(-85) 전까지 검색에 반영되지 않는다.

- Header: `Idempotency-Key` 필수, 공백 불가.
- Body: `{ "targetSceneId": "9301" }` — 정수 또는 양의 정수 문자열. 신고 컨텍스트의 장면과 같아야 한다.
- 멱등은 `(feedbackId, targetSceneId)` 단위다. 내용이 전부 신고 컨텍스트에서 파생돼 장면당 후보는 하나뿐이므로, `Idempotency-Key`가 달라진 재시도도 같은 장면이면 기존 후보를 돌려준다.
- 성공: 신규는 `201`, 멱등 재생은 `200`. `data`: `{ searchRuleId, feedbackId, active }`. `searchRuleId`·`feedbackId`는 정밀도 보존을 위해 문자열(TSID)이다 — §8의 신규 응답 string 규칙을 따른다(S15P21A501-202).

| 오류            | HTTP | 의미                                    |
| --------------- | ---- | --------------------------------------- |
| `SRCH_400_211`  | 400  | 신고 장면과 다른 장면 지정              |
| `SRCH_400_212`  | 400  | 요청 형식 오류(JSON·누락·범위 초과)     |
| `SRCH_403_211`  | 403  | 검수자 아님                             |
| `SRCH_403_212`  | 403  | 담당 검수자 아님                        |
| `SRCH_404_211`  | 404  | 신고 없음                               |
| `SRCH_409_211`  | 409  | 검수 중이 아님                          |
| `SRCH_409_212`  | 409  | 장면 제외로 처리된 신고 아님            |

`POST /review/inquiries/{feedbackId}/parse-patch-candidate` (S15P21A501-81)

검수 중(`REVIEWING`)이고 처리 결과가 `patch_parse`(해석 교정, F-09)인 신고에서, 담당 검수자가 AI 원본 해석에 대한 조건·패치 규칙을 후보로 저장한다(F-11). 후보는 `search_rule`에 `active=false`로 대기하며 검증·확정(F-12~F-13) 전까지 검색·해석에 반영되지 않는다.

- Header: `Idempotency-Key` 필수, 공백 불가, 최대 64자.
- Body: `{ "condition": {…}, "patch": {…}, "replacesRuleId": "9201" }` — `condition`·`patch`는 `parse-rule/v1` JSON 객체이며 원문 그대로 보존한다(도메인 형식 정본은 규칙 스키마). `replacesRuleId`는 선택이며 교체 대상 규칙 id(정수 문자열, 소수는 거부).
- 멱등은 `Idempotency-Key` 단위다. 같은 키 재요청은 후보를 중복 생성하지 않고 기존 후보를 돌려준다.
- 성공: 신규는 `201`, 멱등 재생은 `200`. `data`: `{ searchRuleId, feedbackId, active }`. `searchRuleId`·`feedbackId`는 정밀도 보존을 위해 문자열(TSID)이다 — §8의 신규 응답 string 규칙을 따른다(S15P21A501-202).

| 오류            | HTTP | 의미                              |
| --------------- | ---- | --------------------------------- |
| `SRCH_400_201`  | 400  | 규칙 후보 본문이 올바르지 않음    |
| `SRCH_400_202`  | 400  | 교체 대상 규칙을 찾을 수 없음     |
| `SRCH_403_201`  | 403  | 검수자 아님                       |
| `SRCH_403_202`  | 403  | 담당 검수자 아님                  |
| `SRCH_404_201`  | 404  | 신고 없음                         |
| `SRCH_409_201`  | 409  | 검수 중이 아님                    |
| `SRCH_409_202`  | 409  | 해석 교정으로 처리된 신고 아님    |
| `SRCH_409_203`  | 409  | 원 검색에 교정할 해석 출력이 없음 |

`POST /review/inquiries/{feedbackId}/confirm` (S15P21A501-84)

검수자가 확인한 검증 실행을 근거로 교정을 확정한다(F-13). 태그 근거 `confirmed=true`·규칙 활성화·교체와 신고 종료를 한 트랜잭션으로 처리한다.

```json
{ "executionId": "9702" }
```

- `executionId`는 이 신고의 성공한 재검색(replay) 실행이어야 한다 — 다른 신고·일반 검색 실행은 근거로 쓸 수 없다.
- `tag_correction`은 태그 근거만, `patch_parse`(태그·해석 모두 잘못)는 규칙과 태그를 함께 확정한다(F-09). `exclude_scene`은 제외 규칙을 활성화한다.
- **`exclude_scene`은 확정 직전에 대상 장면 유효성을 재확인한다(F-14).** 재처리로 대상 장면이 사라졌으면 `CONFIRM_409_004`로 승격을 중단하고 신고는 `reviewing`을 유지한다 — 구 장면 제외를 새 장면에 자동 적용하지 않는다.
- 검증 이후 관련 상태가 바뀌면(drift) `CONFIRM_409_003`으로 거부하고 재검증을 요구한다. 같은 검증 실행으로 이미 확정된 신고의 재요청은 성공으로 간주한다(멱등).
- 성공은 body 없는 `200`(색인 갱신이 필요한 구성의 반영 상태 구분은 색인 도입 시 더한다).

| 오류              | HTTP | 의미                                    |
| ----------------- | ---- | --------------------------------------- |
| `CONFIRM_403_001` | 403  | 검수자 아님                             |
| `CONFIRM_403_002` | 403  | 담당 검수자 아님                        |
| `CONFIRM_404_001` | 404  | 신고 없음                               |
| `CONFIRM_404_002` | 404  | 확정할 검증 실행 없음                   |
| `CONFIRM_409_001` | 409  | 검수 중이 아님                          |
| `CONFIRM_409_002` | 409  | 태그·해석 교정으로 처리된 신고 아님     |
| `CONFIRM_409_003` | 409  | 검증 이후 상태 변경 — 재검증 필요       |
| `CONFIRM_409_004` | 409  | 대상 장면이 재처리로 사라짐 — 재검증 필요 |

### 6.5 영상 처리 조회

검수자 전용 `GET /clips`, `GET /clips/{id}`. 필드별 스키마는 서버 OpenAPI의 `ClipPageResponse`, `ClipSummaryResponse`, `ClipDetailResponse`, `ProcessingDetailsResponse`, `ProcessingProgressResponse`를 따른다.

- 목록은 `page=0`, `size=20` 기본값이며 size는 1~100이다. `status=queued,running` 또는 반복 status 파라미터로 최신 run 상태를 OR 필터링한다. 허용값은 `queued/running/failed/succeeded/no_run`, 생략하면 전체다. `no_run`은 run이 없는 클립이다.
- `total_elements`, `total_pages`, `has_next`는 필터 적용 결과다. 한 페이지의 `items`에는 같은 `clip_id`가 중복되지 않는다. `run_counts`의 `queued/running/failed/succeeded/no_run`은 필터·페이지와 무관한 전체 건수이며 논리 삭제는 제외한다. 최신 run은 생성 시각, 동률이면 run ID로 결정한다.
- `items[].progress`는 해당 `latest_run`의 기록 요약이다. `current_stage`는 running 단계가 정확히 하나일 때 그 이름이며, 그 외에는 null이다. `total_steps/succeeded_steps/skipped_steps/failed_steps`는 저장된 단계 상태의 수이며 생략은 실패 수에 중복 포함하지 않는다. 필수 단계 생략으로 run 자체가 실패할 수 있다.
- run이 없으면 progress는 null이다. 기록이 부분·미확인·지원하지 않는 버전이면 `record_status`로 구분하고 단계 수는 null이다. 이 값을 0%나 완료로 추정하지 않는다. 진행 수는 소요 시간 기반 백분율이 아니다.
- `search_available`과 `active_pipeline_run_id`는 현재 검색 제공 결과, `latest_run`·`progress`·`processing_details`는 최신 처리 시도다. 논리 삭제를 제외하는 이 목록·상세 API에서 `search_available`은 `active_pipeline_run_id != null`과 동치다. 활성 처리 ID가 있으면 true, 없으면 false이며 최신 run 상태로 계산하지 않는다. 재처리 실패가 활성 결과를 무효화하지 않으며 검색 준비와 검수 완료는 별개다. 상세의 기본 대사 출처는 활성 결과 기준이다.
- 처리 상세는 단계 상태·실패 사유·누락 채널·실제 채택 대사 출처를 반환한다. `automatic_retryable`은 승인된 다음 자동 시도가 대기 중인지 나타낸다. 수동 재처리 가능 여부 `retryable`은 저장된 판정이 없어 null이며 재처리 API에서 별도로 연결한다.
- 잘못된 페이지는 `CLIP_QUERY_400`, 허용하지 않는 상태는 `CLIP_QUERY_400_001`, 없는/삭제된 클립은 `CLIP_QUERY_404`를 반환한다.

FE `/review?view=processing`은 위 목록·상세와 §6.3의 `REVIEWING` 문의 목록을 연결한다. 영상 탭은 `queued,running,failed,no_run`과 `succeeded`를 서버에서 필터링하고, `progressPage` URL로 10건 단위 페이지를 유지한다. 영상 요약은 필터·페이지와 무관한 `run_counts`, 문의 요약은 `statusCounts`를 사용한다. 상세에서 원본 영상은 §6.1 미디어 API로 재생한다. 등록 후에는 응답 ID로 상세를 다시 조회하며 로컬 처리·장면 데이터를 합성하지 않는다. 영상 목록은 전체 queued/running이 있으면, 상세는 해당 최신 run이 queued/running이면 5초마다 조회한다. 상세 응답의 `latest_run`이 null인 경우에도 `clip.created_at` 기준 등록 후 1분 동안은 5초마다 재조회한다. 그 이후에도 기록이 없으면 반복 조회를 멈추고 ‘상태 새로고침’으로 다시 확인하도록 안내한다. 완료·실패 및 조회 오류에서는 polling을 멈추며 사용자가 다시 조회할 수 있다.

기존 처리 화면의 mock 기능 중 다음 항목은 BE 추가·확장이 필요하다. 아래는 필요한 기능과 최소 데이터이며, 새로운 경로·method는 아직 확정하지 않는다.

| 기능 | BE 추가·확장 요구 | 현재 FE 처리 |
| --- | --- | --- |
| 수동 재처리 | 검수자 전용 command와 가능 여부·불가 사유. 대상 clip/최신 run 사전조건, 중복 요청 멱등성, 진행 중·영구 실패 거절 규칙, 기존 active run 보존, 새 pipeline run ID·상태 반환. `automatic_retryable`과 구분 | 가짜 재처리 버튼 제거. 실제 자동 재시도 이력만 표시 |
| 영상별 장면 목록 | 대상 clip과 run을 식별하는 페이지 조회. scene ID, 순서, 시작·종료 ms, 설명, 총 건수. active/latest run 중 어느 결과인지 명시 | 고정 장면 카드·장면 수 제거. 원본 영상 재생 제공 |
| 장면 썸네일 | scene ID 기반 byte 조회와 인증·cache 정책. 서버 내부 파일 경로 비노출 | 관련 없는 데모 이미지 대신 일반 영상 아이콘 |
| 영상 파일 메타데이터 | 기존 상세 응답에 공개 가능한 원본 파일명·용량·길이·방송일·촬영일을 필요에 따라 추가. nullable·단위 명시. 업로더 표시는 계정 공개 범위 결정 필요 | 서버가 제공하는 제목·유형·등록 시각 표시. 요청 메모리로 누락값을 채우지 않음 |
| 문의 행의 추가 정보 | 기존 목록에 의견 미리보기·담당 검수자 등 실제 저장 정보 확장. 문의자·주제 노출은 도메인/권한 정책 확정 필요 | 실제 queryText·scene·createdAt·hasComment 표시 |

교정 흐름의 태그·해석·장면 제외 후보 생성과 최종 확정, 규칙 사용 중단은 §6.4에 공개 API가 정의되어 있다. 이를 신규 API 요구로 분류하지 않는다. 처리 화면의 문의 상세는 기존 실제 조회·claim·resolution 화면을 재사용하며, 후보 생성 전용 편집 UI와 재검색 검증·확정 흐름의 FE 연결은 별도 작업이다.

### 6.6 내 문의 기록 (S15P21A501-185)

검색 화면 사이드바에서 로그인 사용자가 **본인이 작성했고 본인이 실행한 검색에 대한 문의**만 조회한다. §6.3~6.4 검수 문의 API(`/review/inquiries`)와 달리 세션 사용자 ID로만 범위를 좁히며 파라미터로 다른 사용자 ID를 받지 않는다. 남의 검색 결과에 자기 명의로 만든 문의는 목록·카운트에서 제외해 그 검색어가 새어나가지 않게 한다. 신규 계약이라 응답은 snake_case, 모든 `*_id`는 십진 문자열이다.

FE의 기존 `문의 사항` 패널은 이 목록·상세 API에 연결되어 있다. 패널을 열 때 10건 단위로 조회하고 항목 선택 시 상세를 다시 읽는다. 문의 접수 성공 후 캐시를 갱신하며 loading·empty·error·재시도를 구분한다. 상세의 미제공 snapshot·nullable 처리 결과는 예시 근거로 채우지 않는다. 문의 설명 수정 UI는 이번 조회 연결에 포함하지 않는다.

`GET /inquiries?page=0&size=10`

- `page`: 0 이상 정수, 기본값 0.
- `size`: 1~100 정수, 기본값 10. **범위를 벗어나면 clamp하지 않고 400으로 거부한다.**
- 정렬은 `created_at DESC, feedback_id DESC` 고정(최신순, 동률은 ID 역순)이며 변경 불가.
- 결과가 없어도 404가 아니라 200과 빈 `items: []`를 반환한다.

성공 `data`:

```json
{
  "items": [
    {
      "feedback_id": "9902",
      "search_execution_id": "9701",
      "search_result_id": "9802",
      "created_at": "2026-09-11T03:00:00Z",
      "updated_at": "2026-09-11T03:05:00Z",
      "query_text": "테스트 질의",
      "comment": null,
      "status": "REVIEWING",
      "resolution": "no_action",
      "scene": {
        "scene_id": "9302",
        "clip_id": "9101",
        "clip_title": "설 연휴 교통",
        "start_time_ms": 49000,
        "end_time_ms": 55000
      }
    }
  ],
  "page": 0,
  "size": 10,
  "total_elements": 1,
  "total_pages": 1,
  "has_next": false
}
```

- `comment`·`resolution`은 nullable이며 key를 생략하지 않고 null로 명시한다. `status`는 `OPEN`/`REVIEWING`/`CLOSED`, `resolution`은 처리 전이면 null, 처리됐으면 §6.4 표의 다섯 값(`tag_correction`/`patch_parse`/`exclude_scene`/`no_action`/`deferred`) 중 하나다.

| 오류           | HTTP | 의미                               |
| -------------- | ---- | ---------------------------------- |
| `COMM_401`     | 401  | 미인증                             |
| `COMM_400`     | 400  | `page`/`size` 형식 오류(정수 아님) |
| `COMM_400_001` | 400  | `page`/`size` 범위 오류(size 1~100 밖) |
| `COMM_500`     | 500  | 서버 오류                           |

`GET /inquiries/{feedbackId}`

목록 항목의 모든 필드에 아래를 더한다.

- `explicit_filters`: 검색 실행 당시 명시 filter(JSON object). 값이 없어도 빈 object `{}`이며 null이 아니다.
- `resolution_note`, `review_started_at`, `closed_at`: 검수 처리 사유·시작·종료 시각. `OPEN` 상태면 셋 다 null이다.
- `snapshot_status`, `result_snapshot`: 문의 당시 검색 결과 snapshot 복원 여부. **현재 구현은 항상 `snapshot_status: "unavailable"`, `result_snapshot: null`이다** — `search_result`에 snapshot을 복원할 저장 컬럼이 아직 없고, 그 저장 계약은 S15P21A501-60(미착수)이 소유한다. -60이 저장 형식을 확정하면 `available` 경로를 채운다.

성공 `data` 예시(`CLOSED`·`no_action`):

```json
{
  "feedback_id": "9902",
  "search_execution_id": "9701",
  "search_result_id": "9802",
  "created_at": "2026-09-11T03:00:00Z",
  "updated_at": "2026-09-11T03:10:00Z",
  "query_text": "테스트 질의",
  "comment": null,
  "status": "CLOSED",
  "resolution": "no_action",
  "scene": {
    "scene_id": "9302",
    "clip_id": "9101",
    "clip_title": "설 연휴 교통",
    "start_time_ms": 49000,
    "end_time_ms": 55000
  },
  "explicit_filters": {},
  "resolution_note": "사유 없음으로 처리",
  "review_started_at": "2026-09-11T03:05:00Z",
  "closed_at": "2026-09-11T03:10:00Z",
  "snapshot_status": "unavailable",
  "result_snapshot": null
}
```

본인 소유가 아니거나, 참조하는 검색을 본인이 실행하지 않았거나, 존재하지 않는 `feedbackId`는 **동일하게 404**로 응답한다(존재 여부 비노출). 문의 작성자와 원 검색자가 모두 세션 사용자일 때만 조회된다 — 남의 검색 결과에 자기 명의로 만든 문의로 그 검색어·필터가 새어나가지 않게 한다.

| 오류               | HTTP | 의미                                          |
| ------------------ | ---- | --------------------------------------------- |
| `COMM_401`         | 401  | 미인증                                        |
| `COMM_400`         | 400  | `feedbackId` 형식 오류                        |
| `COMM_400_001`     | 400  | `feedbackId` 범위 오류(1 미만)                |
| `FEEDBACK_404_002` | 404  | 본인 소유 아님·타인 검색 참조·존재하지 않음(동일 취급) |
| `COMM_500`         | 500  | 서버 오류                                     |

**내 검색 기록**(`GET /search/history` 목록·상세)은 별도 작업이다. S15P21A501-60은 실행 snapshot 저장과 소유자/검수자용 단건 상세 조회(`/search/executions/{executionId}`)를 제공한다.

### 6.7 장면 대표 이미지(thumbnail) — 원본 반환

`GET /scenes/{sceneId}/thumbnail`

결과 카드와 검수 문의 큐가 장면을 눈으로 알아보게 하는 이미지다(FRD F-03·F-07). 로그인한 `EDITOR`와 `REVIEWER`가 모두 조회한다.

- 대표 이미지는 AI가 선명도로 골라 목록 첫 원소로 보낸 프레임이며, BE가 그 순서대로 저장하므로 그 장면의 최소 `keyframe_id`다([job-api.md](job-api.md) §4.3.1). **`timestamp_ms`가 가장 이른 프레임이 아니다** — 장면 앞머리에는 디졸브·암전이 오기 쉬워 대표가 시각상 첫 장이 아니다. AI가 장면마다 뽑는 프레임 수도 고정이 아니다(FRD F-03).
- 성공: 이미지 byte, 파일 머리글에 맞는 `Content-Type`(`image/jpeg`·`image/png`·`image/webp`), `Content-Length`, `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`. 지원하지 않는 머리글은 `SCENE_503_001`로 거부한다.
- 성공 byte에는 공통 JSON envelope를 사용하지 않는다. 실패에는 공통 실패 envelope를 사용한다.
- Range를 지원하지 않는다. 한 장을 통째로 보낸다.
- **응답은 축소하지 않은 원본 해상도 keyframe이다.** 서버는 resize·crop·재인코딩하지 않는다. 장당 크기는 1080p 기본 화질에서 약 420 KiB다([job-api.md](job-api.md) §4.3.1의 실측). 카드 10장이면 한 화면이 4 MB 급이므로 FE는 이 값을 전제로 지연 로딩·동시 요청 수를 잡고 표시 크기는 UI에서 조절한다.

**응답 cache.** `Cache-Control: private, no-cache`와 이미지 byte에서 뽑은 `ETag`다. 브라우저는 byte를 보관하되 쓰기 전에 매번 재검증한다. `If-None-Match`가 맞으면 `304`로 끝나고 byte는 오가지 않는다.

`max-age`를 주지 않는 이유는 **권한과 삭제를 다시 판정할 자리가 사라지기 때문**이다. 그 시간 동안은 요청 자체가 오지 않으므로 로그아웃·계정 전환 뒤에도, 클립을 논리 삭제한 뒤에도 인증과 `deleted_at` 조회를 거치지 않은 옛 프레임이 화면에 남는다 — 아래 「세대와 삭제」가 정한 「논리 삭제한 클립의 장면은 없는 장면과 같은 응답을 준다」와 어긋난다. `no-cache`는 보관을 막는 것이 아니라 재검증을 강제하는 지시이므로, 카드 10장이 화면을 오갈 때 아끼려던 전송은 `304`로 그대로 아낀다.

`private`인 이유는 프레임이 공유 캐시·중간 프록시에 남으면 안 되기 때문이다(FRD §6.4). `ETag`는 `scene_id`가 아니라 이미지 byte에서 뽑는다 — ID로 만들면 파일이 교체됐을 때 값이 그대로여서 브라우저가 옛 장을 계속 쓴다.

**검색·문의 응답과의 관계.** 검색·문의 응답은 이미지도 URL도 싣지 않는다. `scene_id`만 주고 FE가 `/api/v1/scenes/{scene_id}/thumbnail`을 조립해 브라우저가 따로 요청한다 — §5.1의 「썸네일·영상에 서버 파일 경로나 임의 URL을 싣지 않는다」를 이 endpoint로 구현한 것이다. JSON에 byte나 Base64를 실으면 결과 10건이 한 응답에 수 MB를 얹고 그 byte가 캐시되지 못한다.

**노출하지 않는 것.** `keyframe.storage_key`, 서버 절대 경로, 내부 예외 문자열은 성공 응답에도 실패 응답에도 나가지 않는다(FRD §6.4). 정규화 후 또는 심볼릭 링크를 따라간 뒤 media root를 벗어나는 key는 파일이 있어도 거부하며, 이때 파일이 있었는지도 알려 주지 않는다.

**세대와 삭제.** 재처리로 활성 `pipeline_run`이 바뀌어도 옛 세대 장면의 이미지는 계속 제공한다. 검수 문의 큐가 접수 당시의 장면을 그대로 보여 주기 때문이다(§6.3). 논리 삭제한 클립의 장면은 없는 장면과 같은 응답을 준다.

| 오류                           | HTTP | 의미                            |
| ------------------------------ | ---- | ------------------------------- |
| `SCENE_404_001`                | 404  | 장면 없음 (또는 삭제된 클립)    |
| `SCENE_404_002`                | 404  | 장면에 keyframe이 아직 없음     |
| `SCENE_404_003`                | 404  | keyframe 행은 있으나 파일 누락  |
| `SCENE_500_001`                | 500  | 저장 위치 오류 (경로 이탈 차단) |
| `SCENE_503_001`, `SCENE_503_002` | 503  | 이미지 읽기 실패 또는 저장소 설정 누락 |

`SCENE_404_001`과 `SCENE_404_002`는 화면 안내가 다르다. 앞은 「없는 장면」, 뒤는 「아직 처리 중」이다(FRD §6.2). `SCENE_404_003`은 저장소 사고이므로 재시도 안내가 아니라 운영 확인 대상이다.

영상 재생(§6.1)과 책임이 다르다. 저쪽은 큰 파일을 Range로 흘려보내며 중간 캐시에 남기지 않고(`private, no-store`), 이쪽은 한 장을 통째로 주며 브라우저가 보관하고 매 사용마다 재검증하기를 바란다(`private, no-cache` + `ETag`).

## 7. 앞으로 명세·구현할 API

아래는 [FRD](../frd.md) F-03, F-05, F-08~F-14와 현재 FE 화면이 요구하는 기능 목록이다. 경로·method·JSON·오류 코드는 담당 이슈에서 확정한 뒤 이 문서의 별도 절로 승격한다.

| 우선순위 | API 기능                             | 최소 계약 요구                                                                                 | 현재 FE 대체 상태             |
| -------- | ------------------------------------ | ---------------------------------------------------------------------------------------------- | ----------------------------- |
| 1        | 검색 실행                            | §5 계약 그대로 구현하고 실제 결과·loading·empty·degraded·failed를 연결                         | 고정 장면과 URL demo          |
| 2        | 검색 기록 목록·상세                  | 로그인 사용자 실행만 조회, 원문 query·명시 filter·시각·선택 장면·snapshot 식별자, pagination(S15P21A501-60 저장 계약 선행, 미착수) | `search-history.tsx` 고정 5건 |
| 3        | 편집자 문의 기록 목록·상세           | §6.6 목록·상세 FE 연결 완료. 본인 조회·페이지 이동·접수 후 갱신                                | 실제 API 연결                 |
| 4        | 문의 접수·수정                       | §6.2 기존 BE 계약에 멱등 재전송 정책을 확정하고 FE dialog 연결                                 | memory 상태 변경              |
| 5        | 영상 재생                            | §6.1 공통 Preview·문의 상세·검색 결과 clipId 바인딩 완료                               | 배포 BE endpoint 확인 필요      |
| 6        | 검수 문의 목록·상세·claim·resolution | §6.3~6.4 응답을 검수 화면 모델로 mapping                                                       | 23건 고정 문의                |
| 7        | 영상 처리 목록 FE 연결               | §6.5 상태 필터·진행 요약·전체 상태 건수·pagination 연결 완료                                  | 실제 API 연결                 |
| 8        | 영상 처리 상세·polling               | §6.5 연결 완료. terminal 상태에서 polling 종료. 장면 목록은 별도 조회 계약 필요                | 실제 API 연결                 |
| 9        | 처리 재시도                          | 일시/영구 실패 구분, 중복 클릭 방지, 기존 제공 run 보존, 새 run 식별자 반환                    | mock 동작 제거, command 미제공 |
| 10       | 교정 후보 작성·검증                  | §6.4로 명세·BE 구현 완료(태그·해석 patch·장면 제외 후보 3종). 원 문의 검색 조건 서버 재사용·분리된 검증 실행 ID 포함. 남은 작업은 FE 바인딩 | 로컬 검수 state               |
| 11       | 교정 확정                            | 검증 실행 ID만 입력받고 서버에 저장된 후보를 원자적으로 확정, stale 검증 거부                  | 로컬 완료 state               |
| 12       | 규칙 사용 중단·재검증                | 수행자·사유, 이후 검색 미적용, 재활성화 전 검증                                                | 로컬 toggle/state             |

검색 기록은 cache가 아니다. 과거 실행의 immutable snapshot이며 새 검색에 해석을 몰래 재사용하지 않는다.

## 8. 구현 전 해결할 계약 차이

| 항목               | 현재 상태                                                            | 결정 필요                                                                                 |
| ------------------ | -------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| bigint ID          | clip/search는 string, auth/feedback은 number                         | 모든 신규 응답은 string. auth/feedback을 migration할지 endpoint별 legacy 예외로 둘지 결정 |
| 성공 code          | 영상 등록은 HTTP 201이지만 현재 envelope helper는 `COMM_200`을 사용  | `COMM_201`로 맞출지 현재 wire를 정본으로 둘지 결정                                        |
| 문의 상태          | BE는 `OPEN/REVIEWING/CLOSED`, FE demo는 `pending/reviewing/resolved` | BE 상태를 정본으로 하고 FE adapter에서 사용자 문구로 변환                                 |
| 문의 상세 snapshot | BE는 여러 JSON 값을 문자열로 반환                                    | 구조화 object로 바꿀지 FE가 안전하게 parse할지 결정                                       |
| 검색 오류          | `SRCH_` 내부 오류 일부만 존재                                        | 공개 endpoint의 4xx/5xx와 degraded 경계를 확정                                            |
| 내 문의 기록       | §6.6으로 확정·BE 구현·FE 목록과 상세 연결 완료                       | 없음                                                                                      |
| 내 검색 기록       | 화면 필드는 있으나 목록 endpoint 없음, S15P21A501-60 저장 계약 선행  | -60이 `search_execution`/`search_result` snapshot 저장 형식을 확정한 뒤 pagination·정렬·상세 분리·ID/nullable 규칙 확정 |
| 처리 조회          | §6.5 실제 목록·상세·polling 연결, unknown/null 보존                    | 수동 재처리·장면 목록·썸네일 및 추가 메타데이터 계약 필요                                 |

미확정 항목은 FE demo model이나 Java DTO를 복사해 새 정본으로 만들지 않는다. 합의가 끝나면 이 문서를 먼저 갱신하고 양쪽 구현과 계약 테스트를 맞춘다.
