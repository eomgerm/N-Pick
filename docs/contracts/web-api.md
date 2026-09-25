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
| 원본 클립 다운로드         | GET    | `/media/{clipId}/download`                  | 연결됨    | 없음                                |
| 장면 구간 다운로드         | GET    | `/media/scenes/{sceneId}/download`          | 연결됨    | 없음                                |
| 장면 대표 이미지           | GET    | `/scenes/{sceneId}/thumbnail`               | BE 구현   | 결과 카드·문의 큐 thumbnail 바인딩  |
| 문의 접수                  | POST   | `/search/results/{resultId}/inquiries`      | BE 구현   | 문의 생성 바인딩                    |
| 문의 설명 수정             | PATCH  | `/inquiries/{feedbackId}`                   | BE 구현   | 편집자 문의 기록 바인딩과 함께 연결 |
| 검수 문의 목록             | GET    | `/review/inquiries`                         | BE 구현   | 검수 게시판 바인딩                  |
| 검수 문의 상세             | GET    | `/review/inquiries/{feedbackId}`            | BE 구현   | 검수 상세 바인딩                    |
| 검수 시작                  | POST   | `/review/inquiries/{feedbackId}/claim`      | BE 구현   | 검수 흐름 바인딩                    |
| 검수 취소                  | DELETE | `/review/inquiries/{feedbackId}/claim`      | BE 구현   | 검수 취소 버튼 바인딩               |
| 처리 결과 선택             | PUT    | `/review/inquiries/{feedbackId}/resolution` | BE 구현   | 검수 흐름 바인딩                    |
| 내 문의 기록 목록          | GET    | `/inquiries`                                | 연결됨    | 없음                               |
| 내 문의 기록 상세          | GET    | `/inquiries/{feedbackId}`                   | 연결됨    | 없음                               |
| 내 검색 기록 목록          | GET    | `/search/history`                           | BE 구현   | 편집자 하단 기록 시트 바인딩        |
| 내 검색 기록 상세          | GET    | `/search/history/{searchExecutionId}`       | BE 구현   | 기록 선택 시 당시 결과 바인딩       |
| 내 검색 기록 삭제          | DELETE | `/search/history/{searchExecutionId}`       | BE 구현   | 기록 삭제 버튼 연결                 |
| 내 검색 기록 전체 삭제     | DELETE | `/search/history`                           | BE 구현   | 전체 삭제 버튼 연결                 |
| 영상 처리 목록             | GET    | `/clips`                                    | 연결됨    | 없음                               |
| 영상 처리 상세             | GET    | `/clips/{id}`                               | 연결됨    | 없음                               |
| 영상 처리 재시도           | 미정   | 미정                                        | 명세 필요 | 재처리 요청 연결                    |
| 태그 교정 후보             | POST   | `/review/inquiries/{feedbackId}/tag-correction-candidate` | 연결됨  | 후보 검증·확정 바인딩       |
| 해석 교정 후보             | POST   | `/review/inquiries/{feedbackId}/parse-patch-candidate`    | BE 구현 | 검수 교정 바인딩            |
| 장면 제외 후보             | POST   | `/review/inquiries/{feedbackId}/scene-exclude-candidate`  | BE 구현 | 검수 교정 바인딩            |
| 태그 교정 후보 취소        | DELETE | `/review/inquiries/{feedbackId}/tag-correction-candidate` | BE 구현 | 후보 취소 바인딩            |
| 태그 교정 후보 개별 취소   | DELETE | `/review/inquiries/{feedbackId}/tag-correction-candidate/{evidenceId}` | BE 구현 | 추가 태그 취소 바인딩 |
| 해석 교정 후보 취소        | DELETE | `/review/inquiries/{feedbackId}/parse-patch-candidate`    | BE 구현 | 후보 취소 바인딩            |
| 장면 제외 후보 취소        | DELETE | `/review/inquiries/{feedbackId}/scene-exclude-candidate`  | BE 구현 | 후보 취소 바인딩            |
| 대기 교정 후보 조회        | GET    | `/review/inquiries/{feedbackId}/correction-candidates`    | BE 구현 | 새로고침 뒤 작성 중 교정 복원 |
| 후보 검증 재검색           | POST   | `/review/inquiries/{feedbackId}/verify`                   | BE 구현 | 검수 재검색·확정 바인딩     |
| 교정 확정                  | POST   | `/review/inquiries/{feedbackId}/confirm`                  | BE 구현 | 검수 재검색·확정 바인딩     |
| 검색 규칙 사용 중단         | PATCH  | `/review/search-rules/{ruleId}`             | BE 구현   | 검수 규칙 관리 바인딩               |

## 2. 공통 규약

### 2.1 인증과 요청 보호

- 로그인 세션은 `JSESSIONID` cookie로 유지한다. FE는 `credentials: include`로 요청한다.
- access 인증은 기본 30분의 절대 수명(`ACCESS_TOKEN_TTL`)이며 일반 요청으로 늘어나지 않는다. 별도 `NPICK_REFRESH` HttpOnly cookie로 최초 로그인부터 최대 8시간(`REFRESH_TOKEN_TTL`)까지 재발급한다. 둘 다 PostgreSQL에 보존하며 로그아웃 시 폐기한다. refresh를 사용해도 8시간 기한은 늘어나지 않는다.
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
  "message": "요청을 처리했습니다.",
  "data": {}
}
```

본문 없는 성공은 `data`를 생략할 수 있다.

실패 JSON은 다음 모양이다.

```json
{
  "isSuccess": false,
  "code": "COMM_400_001",
  "message": "입력한 내용을 확인해 주세요.",
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

두 필드는 빈 문자열일 수 없다. 성공하면 새 `JSESSIONID`와 `NPICK_REFRESH` cookie를 발급하고 다음 `data`를 보낸다. 인증 자격 증명은 응답 body에 넣지 않는다.

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

요청 body는 없다. access와 refresh를 무효화하고 refresh cookie를 삭제하며 body 없는 성공 envelope를 보낸다. access가 이미 만료돼도 가능하며 CSRF 검증은 필요하다.

### 3.5 인증 갱신

`POST /auth/refresh`

요청 body는 없다. `NPICK_REFRESH` cookie와 CSRF cookie/header를 검증한 후 짧은 access cookie를 발급한다. 성공 `data`는 로그인 응답과 같다. 아직 유효한 access가 있으면 동시 갱신 요청들이 같은 access를 받는다. refresh가 없거나 만료·폐기됐으면 `401 COMM_401`, CSRF 실패는 `403 COMM_403`이다.

FE는 일반 API의 `401 COMM_401`에 갱신을 한 번 시도하고, 성공하면 같은 body와 멱등성 키로 원래 요청을 한 번만 재전송한다. 같은 탭의 동시 실패는 갱신 요청을 공유한다. refresh 실패가 네트워크·서버 오류이면 로그인 만료로 처리하지 않는다. SSR 보호 페이지에서 access가 만료되면 브라우저 갱신 화면을 거쳐 원래 화면으로 복귀한다.

## 4. 영상 등록 API — 연결됨

`POST /clips`

- Content-Type: `multipart/form-data`
- Header: `Idempotency-Key` 필수, 공백 불가, 최대 128자
- 성공 HTTP: `201 Created`

| Form field                      | 타입    | 필수   | 규칙                                            |
| ------------------------------- | ------- | ------ | ----------------------------------------------- |
| `video`                         | file    | 필수   | 1개, 실제 영상 내용·형식·크기·길이 검사         |
| `source_type`                   | string  | 필수   | `broadcast` 또는 `archive`                      |
| `title`                         | string  | 선택   | 공백은 생략, 최대 50자(UTF-16 길이), 아래 UTF-8 규칙 |
| `broadcast_date`                | date    | 선택   | `broadcast`에서만 허용                          |
| `filmed_date`                   | date    | 선택   | 두 source 모두 허용                             |
| `subtitle`                      | file    | 선택   | 1개, UTF-8 SRT/VTT 또는 승인된 JSON             |
| `script_text`                   | string  | 선택   | UTF-8 TXT를 FE가 읽어 문자열로 전송             |
| `rights_confirmed`              | boolean | 필수   | `true`여야 등록 가능                            |
| `external_processing_confirmed` | boolean | 조건부 | 현재 처리 설정이 외부 AI 동의를 요구하면 `true` |

자료 영상 `archive`에는 `broadcast_date`를 보내지 않는다. 날짜를 모두 생략해도 등록할 수 있다. 보낸 날짜는 등록일(Asia/Seoul) 이후일 수 없고, 두 날짜를 모두 보내면 `broadcast_date`가 `filmed_date`보다 빠를 수 없다. 위반은 `COMM_400_001`의 `data.broadcastDateNotFuture`·`data.filmedDateNotFuture`·`data.broadcastDateNotBeforeFilmedDate`로 거부한다. 요청 검증을 우회한 호출에서도 같은 규칙을 `CLIP_400_013`(방송일)·`CLIP_400_014`(촬영일)로 거부한다.

제목은 FE와 BE 모두 UTF-16 길이로 검사한다. 일반 한글 50자 또는 `😀` 25개는 허용하며, 한글 51자 또는 `😀` 26개는 거절한다. 50자 제한은 신규 등록 입력에 적용하고 기존 제목·검색 기록은 자르지 않는다. 기존 데이터 보존을 위해 DB의 `clip.title varchar(500)`은 유지한다.

**자막 종료 시각의 상한은 영상 길이를 올림한 정수 ms다.** 자막 시각은 정수 ms만 표현할 수 있는데 검사 기준인 ffprobe `format.duration`은 소수 ms라, 두 축을 오차 0으로 비교하면 영상 끝까지 덮는 자막이 1ms 미만 초과로 거절된다. 영상 전체를 담는 가장 작은 정수 ms를 상한으로 삼는다. 그 상한을 넘으면 문제 구간·상한·초과량과 함께 파일 전체를 거절하며 시간 보정·잘라내기·부분 적용은 하지 않는다 (`CLIP_400_012`).

**`title`과 `script_text`는 U+FFFD를 담고 있으면 거절한다.** UTF-8이 아닌 본문을 보내면 읽지 못한 바이트마다 U+FFFD가 남고 원래 글자는 복구할 수 없다. 각각 `CLIP_400_004`·`CLIP_400_015`이다.

성공 envelope의 `data`:

```json
{
  "clip_id": "398021847361024",
  "pipeline_run_id": "398021847361025",
  "status": "queued",
  "outcome": "created"
}
```

**`outcome`은 clip 을 새로 만들었는지 이미 있던 clip 을 돌려주었는지 구분한다(S15P21A501-283).** 같은 영상 파일은 등록자와 무관하게 하나의 clip 으로 모이므로(S15P21A501-68), 재업로드는 새 clip 없이 기존 clip 의 id 를 받는다. 이 구분이 없으면 FE 가 신규 등록으로 읽어 안내 없이 남의 clip 상세로 이동한다. HTTP 는 세 경우 모두 `201`이며 나머지 필드의 형식도 같다.

| `outcome`         | 의미                                                                       |
| ----------------- | -------------------------------------------------------------------------- |
| `created`         | 이 요청이 clip 을 만들었다                                                  |
| `duplicate_own`   | 같은 영상 파일을 본인이 이미 등록해 두어 그 clip 을 돌려준다                |
| `duplicate_other` | 같은 영상 파일을 다른 사용자가 이미 등록해 두어 그 clip 을 돌려준다         |

중복이면 이번 요청의 `title`·`broadcast_date`·`filmed_date`·자막은 저장되지 않는다. FE 는 그 사실을 알리고 이동하며, 등록 직후 화면에 요청 값과 서버가 돌려준 clip 의 정보를 섞어 표시하지 않는다.

같은 `Idempotency-Key` 의 재전송은 처음 확정한 `outcome` 을 그대로 재생한다. 중복 응답을 받은 뒤 재시도해도 안내가 신규 등록으로 바뀌지 않는다.

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
| `CLIP_400_013`, `CLIP_400_014`                | 날짜 값 범위               |
| `CLIP_400_015`                                | 일반 대본 내용             |
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
| (더보기)                       | `page` (0-based, 생략 시 첫 페이지)            |
| (더보기)                       | `search_execution_id` (첫 페이지 응답의 값)   |

선택하지 않은 날짜 종류는 key 자체를 생략한다. `from`과 `to`는 모두 포함되는 날짜다.

`page`는 결과 더보기용 0-based 페이지 번호다. 첫 페이지 요청은 `page`를 생략하고(서버가 0으로 본다), 더보기가 다음 페이지를 요청할 때만 싣는다. 한 페이지는 최대 10개이며, 응답의 `has_next`가 참이면 다음 페이지가 있다(F-05 §6). 페이지마다 새 실행이며 결과 구간만 다르다 — 실행·세션 식별자가 아니라 결과 순번이므로 §7.2의 해석 캐시에 해당하지 않는다.

`search_execution_id`는 더보기 요청이 첫 페이지 응답의 `search_execution_id`를 그대로 실어 보내는 값이다. 첫 페이지 요청엔 싣지 않는다. 서버가 이어보기 실행을 첫 페이지(root) 실행 아래로 묶어 「내 검색 기록」이 한 검색을 한 줄로 보이게 하는 **기록 그룹핑 힌트**이며, 결과·해석을 재사용하지 않는다(더보기 페이지는 여전히 처음부터 다시 계산한다). 검색코어 소유자 확인상 같은 실행의 이어보기는 §7.2의 「해석 캐시」 대상이 아니다(S15P21A501-280). 형식이 아니거나 없으면 서버는 root 검색으로 본다.

```json
{
  "query": "명절 교통",
  "explicit_filters": {
    "broadcast_date": { "from": "2026-09-01", "to": "2026-09-03" },
    "filmed_date": { "from": "2026-08-28", "to": "2026-08-29" }
  }
}
```

날짜 필터가 없을 때도 `explicit_filters`는 빈 object로 보낸다. 위 예시는 첫 페이지 요청이라 `page`와 `search_execution_id`가 없다 — 둘은 더보기 요청에만 함께 싣는다.

성공 응답 전체 모양:

```json
{
  "isSuccess": true,
  "code": "COMM_200",
  "message": "요청을 처리했습니다.",
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
    "has_next": false,
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
        "matched_keywords": [
          { "keyword": "서울역", "origin": "user" },
          { "keyword": "귀성객", "origin": "expanded" }
        ],
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
- **이 `status`와 `degraded_reasons`는 기록 컬럼의 값이 아니다.** 승인된 해석 규칙이 충돌·비호환·실패로 건너뛰어지면 `search_execution.status`는 `degraded`가 되고 `degraded_reasons_json`에는 `skipped_conflict:<rule_id>` 같은 문자열이 함께 들어간다. 여기 세 값은 **사용자에게 보여 줄 수 있는 어휘만** 닫아 둔 것이라 그 사유를 실을 자리가 없고, 사용자가 받은 결과 자체는 온전하므로 응답은 `succeeded`다. 규칙별 판정은 `applied_rules_json`이 남기고 감사 화면(`GET /search/executions/{id}`)이 전량을 보여 준다. 기록을 읽는 화면이 이 두 필드를 만들 때 쓰는 파생 규칙은 「내 검색 기록」 절(S15P21A501-198)이 정한다.
- `matched_keywords`는 **확장어로 걸린 단어를 포함하고, 항목마다 `origin`으로 출처를 구분한다** (`user` | `expanded`, S15P21A501-234). 검색에는 쓰고 근거에는 빼면 확장어로만 걸린 장면이 「왜 나왔는지 모르는 결과」가 되므로 포함 쪽을 택했고, 사용자가 자기가 입력하지 않은 단어 때문에 결과가 나왔다는 것을 알 수 있어야 하므로 출처를 함께 싣는다 (F-05·F-07). 형태가 같고 품사가 다른 토큰이 겹치면 `user`가 이긴다. 이 응답의 `origin`은 항상 두 값 중 하나다 — `null`은 저장 기록 복원에서만 나온다(§6.7).
- `matched_keywords`와 `match_evidence`는 **단어 검색이 실제로 건 토큰에서만** 고른다. 캡션 범용어(기본 `장면`·`보이`·`화면`·`모습`, 설정 `npick.search.candidate.excluded-query-tokens`)는 원 질의 토큰에서 빼고 검색하고, 키워드로도 나오지 않는다 (S15P21A501-320). VLM 캡션이 「~하는 장면이다」 식이라 이 말들이 장면 3분의 1 가까이에 들어 있어, 원 질의에 남기면 그만큼이 후보가 되고 칩도 그 말 때문에 나온 것처럼 보인다. 원 질의 토큰이 전부 범용어면 빼지 않는다. **확장어 구 안의 범용어는 빼지 않는다** — 구는 AND 로 걸리므로 범용어가 매칭을 좁힐 뿐이고, 빼면 「자료 화면」이 `자료` 단독 매칭으로 풀린다. 다만 범용어로만 된 구와 사용자가 친 말로만 된 구는 걸지 않으며, 구로 걸린 범용어도 키워드 칩에는 싣지 않는다. 질의 지문(`normalized_query`)과 의미 검색 입력은 바뀌지 않는다.
- `status=degraded`면 `resolver_fallback`, `dense_unavailable`, `snapshot_save_failed` 중 하나 이상이다.
- `query_resolution_status=fallback` 여부는 `resolver_fallback` 포함 여부와 일치한다.
- `snapshot_save_failed`면 `search_execution_id`와 모든 `search_result_id`는 null이다. 이 결과로 문의할 수 없다.
- `guard_summary.excluded_result_count`가 0이면 `reasons`도 비어 있다. 허용 reason은 `explicit_date_conflict`, `approved_incident_conflict`, `approved_scene_exclusion`이다.
- 결과가 10개 미만이면 `shortage_reasons`가 1개 이상이어야 한다. 허용 reason은 `candidate_pool_exhausted`, `guard_excluded`다.
- `has_next`는 다음 페이지(더보기)가 있는지를 나타내는 boolean이다. 실시간 검색 응답에만 있고, 과거 기록 복원용 `search_snapshot`(§6.7)에는 없다 — 더보기는 실시간 검색 전용이므로 FE는 스냅샷에서 `has_next`를 항상 거짓으로 본다. `has_next`가 참이면 남은 유효(비제외) 후보가 한 페이지 몫을 넘어 더 있다는 뜻이다.
- 썸네일·영상에 서버 파일 경로나 임의 URL을 싣지 않는다. ID 기반 제공 API를 사용한다 — 썸네일은 §6.8, 영상은 §6.1이며 FE가 `scene_id`·`clip_id`로 주소를 조립한다.

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

#### 6.1.1 영상 다운로드

- `GET /media/{clipId}/download`: 등록된 원본 클립 전체를 내려받는다.
- `HEAD /media/{clipId}/download`: 원본 본문을 읽지 않고 다운로드 가능 여부와 파일 헤더를 확인한다. FE는 이 확인이 성공한 뒤 브라우저 네이티브 다운로드를 시작하여 대용량 원본을 메모리에 적재하지 않는다.
- `GET /media/scenes/{sceneId}/download`: DB에 저장된 장면의 `start_time_ms`부터 `end_time_ms`까지를 MP4로 추출해 내려받는다.
- 임의 시작·종료 시각은 입력으로 받지 않는다. 사용자가 검색 결과에서 확인한 저장 장면 경계만 다운로드할 수 있다.
- 두 응답은 `Content-Disposition: attachment`, `Cache-Control: private, no-store`와 영상 byte를 반환하며 공통 JSON envelope를 사용하지 않는다.
- 로그인한 `EDITOR`와 `REVIEWER`가 사용할 수 있다. 삭제된 클립의 원본과 장면은 반환하지 않는다.
- 장면 ffmpeg 추출은 서버 전체 동시 실행 상한을 둔다. 자리가 없으면 기다리며 요청 스레드를 쌓지 않고 즉시 `CLIP_503_012`로 실패한다.
- 장면 추출 임시 파일은 본문 전송 완료·중단뿐 아니라 응답 stream을 열기 전에 실패해도 삭제한다.

| 오류 | HTTP | 의미 |
| --- | --- | --- |
| `CLIP_404_001`, `CLIP_404_002` | 404 | 클립 또는 원본 파일 없음 |
| `CLIP_404_003` | 404 | 장면 없음 |
| `CLIP_500_003` | 500 | 저장 위치 오류 |
| `CLIP_503_010`, `CLIP_503_011` | 503 | 전송 또는 저장소 설정 실패 |
| `CLIP_503_012` | 503 | 장면 추출 포화, 구간 추출 실패 또는 시간 초과 |

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

`GET /review/inquiries/{feedbackId}`는 문의, 장면, 당시 검색 실행 snapshot, 근거, 검수 이력을 반환한다. 현재 BE 응답의 snapshot JSON 필드(`explicitFiltersJson`, `parsedQueryJson`, `resolverOutputJson`, `appliedRulesJson`, `appliedExcludesJson`)는 JSON 문자열이다. FE는 이를 개발용 원문으로 직접 노출하지 않고 사용자용 모델로 변환한다. `evidence[]`는 교정 후보를 정확히 만들 수 있도록 `taggingId`, `tagType`, `matchValue`, `tagName`, `sources`(출처 문자열 배열), `verifiedState`, `scope`를 반환한다.

`evidence[]`는 **확정 근거(`tag_evidence.confirmed=true`)가 하나 이상 있는 태깅만** 담는 "현재 태그"다(S15P21A501-317). 출처·검증 상태도 확정 근거만으로 계산한다. 확정 근거 없이 검수자의 대기 후보(`confirmed=false`)만 있는 태깅이나, 후보 취소 뒤 근거 없이 남은 태깅은 나오지 않는다 — 대기 후보는 아래 `GET /review/inquiries/{feedbackId}/correction-candidates`로 따로 읽는다.

`POST /review/inquiries/{feedbackId}/claim`

- 선택 header: `Idempotency-Key`
- 같은 검수자가 이미 잡은 `REVIEWING` 문의의 재요청은 성공한다.
- 다른 검수자가 잡았거나 종료된 문의는 `FEEDBACK_409_001`이다.

`DELETE /review/inquiries/{feedbackId}/claim` (검수 취소, S15P21A501-289)

담당 검수자가 잡은 검수를 풀어 문의를 검수 전 상태로 되돌린다. 다른 검수자가 이어서 `POST .../claim`으로 정상 시작할 수 있다.

- 담당 검수자 본인(`REVIEWING`이고 `reviewed_by = 나`)만 취소할 수 있다. 판정은 CAS로 한다.
- 교정 후보가 있어도 취소할 수 있다. 이 문의 아래 **대기 중인 교정 후보를 모두 폐기**한다 — 미확정 태그 근거(`tag_evidence.confirmed=false`), 미확정 규칙 후보(`search_rule.active=false`의 `patch_parse`·`exclude_scene`). `no_action`·`deferred` 종료와 같은 폐기 경로다. 이미 확정된 근거·활성 규칙은 건드리지 않는다.
- 신고는 `OPEN`으로 돌아가고 담당자(`reviewed_by`)·검수 시작 시각·처리 결과(`resolution`)·사유(`resolution_note`)를 비운다. 다음 검수자는 처음부터 판단한다. 과거 검증 재검색 실행 기록은 보존하지만 후보 폐기로 교정 상태 지문이 바뀌므로 확정 근거로 재사용되지 않는다.
- 후보 폐기와 상태 복귀는 후보 생성·판정 변경·확정과 같은 교정 상태 잠금 안의 한 트랜잭션이다. 확정과 겹치면 먼저 잠금을 잡은 쪽이 끝난 뒤 다른 쪽이 최신 상태로 판정한다(확정이 먼저면 취소는 `FEEDBACK_409_003`, 취소가 먼저면 확정은 `CONFIRM_403_002`).
- 다른 검수자의 `POST .../claim`은 교정 상태 잠금을 쓰지 않고 `status='OPEN'` CAS만 건다. 취소 트랜잭션이 **커밋되기 전**에 시작한 claim은 취소의 UPDATE 이전이든 이후든 대기 없이 `FEEDBACK_409_001`로 실패한다 — PostgreSQL READ COMMITTED에서 UPDATE는 커밋된 행 버전이 WHERE를 만족할 때만 행 잠금을 기다리는데, 커밋 전까지 커밋된 버전은 `REVIEWING`이라 `status='OPEN'`과 맞지 않기 때문이다. 취소 **커밋 후**의 claim(재시도 포함)은 성공한다. 어느 경우에도 이중 배정은 생기지 않는다.
- `OPEN`이 되면 편집자의 문의 설명 수정(`PATCH /inquiries/{feedbackId}`)도 다시 가능해진다.
- 성공은 `200`이고 claim과 같이 body에 `data`가 없다. FE는 상세·목록을 다시 조회해 갱신한다.

| 오류               | HTTP | 의미                                      |
| ------------------ | ---- | ----------------------------------------- |
| `COMM_403`         | 403  | 검수자 역할 아님                          |
| `FEEDBACK_403_002` | 403  | 담당 검수자 아님                          |
| `FEEDBACK_404_002` | 404  | 문의 없음                                 |
| `FEEDBACK_409_003` | 409  | 검수 중이 아님(이미 취소·종료됨, 경합 포함) |

`PUT /review/inquiries/{feedbackId}/resolution`

```json
{
  "resolution": "correction",
  "note": "이 검색 조건에서 태그·해석·장면 제외를 교정해야 합니다."
}
```

| `resolution` | 결과                                    |
| ------------ | --------------------------------------- |
| `correction` | 교정 후보 단계로 이동, `REVIEWING` 유지 |
| `no_action`  | `note` 필수, `CLOSED` 종료              |
| `deferred`   | `note` 필수, `CLOSED` 종료              |

교정은 태그 교정·해석(질의) 교정·장면 제외를 하나 또는 여럿 섞어 한 번의 확정으로 처리하므로 정본 처리 결과는 `correction` 하나다(F-09 혼합 교정, S15P21A501-281). 레거시 세부 종류(`tag_correction`/`patch_parse`/`exclude_scene`)도 하위호환으로 받되 `correction`이 정본이다.

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

검수 중(`REVIEWING`)이고 처리 결과가 교정(`correction`, 또는 레거시 교정 종류)인 신고에서, 담당 검수자가 태그 변경안 목록을 후보로 저장한다. 생성은 더 이상 특정 세부 종류가 일치할 것을 요구하지 않는다 — 하나의 교정 처리 결과 아래에서 태그·해석·장면 제외 후보를 섞어 함께 만들 수 있다(F-09/281). 저장 근거는 `confirmed=false`로 대기하며 확정(-84) 전까지 검색·해석에 반영되지 않는다.

```json
{
  "operations": [
    { "action": "APPROVE", "scope": "SCENE", "tagType": "location", "matchValue": "제주도", "displayName": "제주도" }
  ]
}
```

- `operations`는 최소 1개, 한 요청에 최대 20개. 교체는 `REJECT`+`APPROVE` 두 항목으로 보낸다. `action`은 `APPROVE|REJECT|WITHDRAW`, `scope`는 `SCENE|CLIP`.
- 한 신고에 쌓을 수 있는 검수자 판단은 누적 50개까지다. 이번 요청으로 **새로 만들** 판단을 더해 넘으면 하나도 저장하지 않고 거부한다. 아래 자연 키로 재사용하는 판단은 세지 않고, 반대 판단 정리로 지운 근거는 뺀 뒤 센다.
- `tagType`은 11종 어휘, `matchValue`는 서버가 정규화한다(NFKC·불가시 문자 제거). 범위는 신고 컨텍스트의 장면/클립으로만 한정되어 임의 대상을 지정할 수 없다.
- **중복 제거는 멱등 키가 아니라 자연 키로 한다(S15P21A501-317).** 이 endpoint는 `Idempotency-Key` header를 읽지 않는다. 대신 변경안마다 같은 신고·같은 태깅(`tagType`+정규화된 `matchValue`+장면/클립 범위)·같은 판단(`APPROVE`/`REJECT`/`WITHDRAW` = `verified`/`rejected`/`withdrawn`)으로 대기 중인(`source='reviewer_feedback'`, `confirmed=false`) 근거가 이미 있으면 새로 넣지 않고 그 근거 id를 돌려준다. 한 요청 안의 같은 변경안 둘도 근거 하나로 모인다. `displayName`은 비교하지 않는다. 레거시로 같은 판단의 대기 근거가 여러 건 쌓여 있으면 재사용할 하나(가장 먼저 만든 것)만 남기고 나머지는 지운다. 이미 확정된(`confirmed=true`) 근거나 다른 신고의 근거는 재사용하지 않는다. 그래서 새로고침 뒤 복원한 변경안을 다시 보내거나 응답을 잃고 재시도해도 근거가 쌓이지 않는다.
- **한 신고·한 태깅에 대기 판단은 하나만 남긴다(S15P21A501-317).** 변경안이 오면 같은 신고·같은 태깅에 대기 중인 검수자 근거 중 **다른 판단**의 것을 먼저 지우고, 이번 판단을 위 규칙대로 재사용하거나 새로 만든다. 확정(`/confirm`)은 대기 근거를 모두 올리고 해석은 최신 판단을 쓰므로, 옛 판단이 함께 남으면 최종 의도와 반대 판단이 이길 수 있기 때문이다(예: 승인→반려→승인 뒤 확정하면 반려가 남는 문제). 판단이 바뀌면 대기 태그 집합이 바뀌므로 이전 검증 실행으로는 확정할 수 없고 다시 검증해야 한다(`CONFIRM_409_003`).
- 한 요청 안에서 같은 태깅에 판단이 여럿 오면 **마지막 변경안이 이긴다.** 진 앞 변경안의 `evidenceIds` 자리에도 살아남은 근거 id가 들어간다.
- 성공 `201` body `data`: `{ feedbackId, created, newlyCreated, evidenceIds }`. id는 정밀도 보존을 위해 문자열(TSID)이다.
  - `evidenceIds`: 요청 `operations` 순서대로 변경안마다 하나. 그 태깅에 최종으로 남은 근거 id다 — 재사용이면 기존 근거 id, 아니면 새 근거 id. 같은 태깅의 변경안이 두 번 오면 같은 id가 두 번 나온다.
  - `created`: `evidenceIds` 길이(= `operations` 수)다. 재사용한 것도 센다 — 기존 FE 파서의 `created == evidenceIds.length` 검사와 호환하려고 의미를 유지한다.
  - `newlyCreated`: 이번 요청으로 실제로 새로 만든 근거 수. 전부 재사용이면 `0`이며 이때도 HTTP는 `201`이다.

| 오류               | HTTP | 의미                              |
| ------------------ | ---- | --------------------------------- |
| `TAG_400_001`      | 400  | 변경안이 비어 있음                |
| `TAG_400_002`      | 400  | 알 수 없는 태그 유형              |
| `TAG_400_003`      | 400  | 정규화 후 빈 태그 값              |
| `TAG_400_004`      | 400  | 한 요청의 변경안이 20개 초과      |
| `TAG_403_001`      | 403  | 검수자 아님                       |
| `TAG_403_002`      | 403  | 담당 검수자 아님                  |
| `TAG_404_001`      | 404  | 신고 없음                         |
| `TAG_409_001`      | 409  | 검수 중이 아님                    |
| `TAG_409_002`      | 409  | 태그·해석 교정으로 처리된 신고 아님 |
| `TAG_409_003`      | 409  | 신고당 누적 변경안 50개 초과      |

`DELETE /review/inquiries/{feedbackId}/tag-correction-candidate` (S15P21A501-309)

담당 검수자가 확정 전 대기 중인 태그 교정 후보를 취소한다. 가드는 검수자 role·`REVIEWING`·담당 검수자다. 이미 확정된(`confirmed=true`) 근거는 건드리지 않는다. 성공은 body 없는 `200`이다.

`DELETE /review/inquiries/{feedbackId}/tag-correction-candidate/{evidenceId}` (S15P21A501-309)

담당 검수자가 대기 중인 태그 교정 후보 근거 **하나만** 취소한다 — 같은 신고의 다른 대기 근거는 남긴다. 추가한 태그 하나를 취소할 때 쓴다(새로고침 뒤에도 다른 후보를 지우지 않는다).

- `evidenceId`는 생성 응답 `evidenceIds`의 문자열(TSID)을 그대로 넣는다.
- 가드·검사 순서·오류 코드는 위 전체 취소와 같다: 검수자 role(`TAG_403_001`) → 교정 상태 잠금 → 신고 존재(`TAG_404_001`) → `REVIEWING`(`TAG_409_001`) → 담당 검수자(`TAG_403_002`).
- 이 신고가 만든(`source_feedback_id`) 검수자 근거(`source='reviewer_feedback'`) 중 `confirmed=false`인 해당 근거만 지운다. 이미 확정됐거나, 다른 신고의 근거이거나, 없는 id면 아무것도 지우지 않고 성공한다(멱등).
- 성공은 body 없는 `200`이다.

`POST /review/inquiries/{feedbackId}/scene-exclude-candidate` (S15P21A501-82)

검수 중(`REVIEWING`)이고 처리 결과가 교정(`correction`, 또는 레거시 교정 종류)인 신고에서, 담당 검수자가 신고 장면의 제외 후보를 저장한다. 생성은 더 이상 특정 세부 종류가 일치할 것을 요구하지 않는다 — 하나의 교정 처리 결과 아래에서 태그·해석·장면 제외 후보를 섞어 함께 만들 수 있다(F-09/281). 후보는 `search_rule`에 `active=false`로 대기하며 검증(-83)·확정(-85) 전까지 검색에 반영되지 않는다.

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

`DELETE /review/inquiries/{feedbackId}/scene-exclude-candidate` (S15P21A501-281)

담당 검수자가 확정 전 대기 중인 장면 제외 후보를 취소한다. 가드는 검수자 role·`REVIEWING`·담당 검수자다. 성공은 body 없는 `200`이다.

`POST /review/inquiries/{feedbackId}/parse-patch-candidate` (S15P21A501-81)

검수 중(`REVIEWING`)이고 처리 결과가 교정(`correction`, 또는 레거시 교정 종류)인 신고에서, 담당 검수자가 AI 원본 해석에 대한 조건·패치 규칙을 후보로 저장한다(F-11). 생성은 더 이상 특정 세부 종류가 일치할 것을 요구하지 않는다 — 하나의 교정 처리 결과 아래에서 태그·해석·장면 제외 후보를 섞어 함께 만들 수 있다(F-09/281). 후보는 `search_rule`에 `active=false`로 대기하며 검증·확정(F-12~F-13) 전까지 검색·해석에 반영되지 않는다.

- Header: `Idempotency-Key` 필수, 공백 불가, 최대 64자.
- Body: `{ "condition": {…}, "patch": {…}, "replacesRuleId": "9201" }` — `condition`·`patch`는 `parse-rule/v1` JSON 객체이며 원문 그대로 보존한다(도메인 형식 정본은 규칙 스키마). `replacesRuleId`는 선택이며 교체 대상 규칙 id(정수 문자열, 소수는 거부).
- 멱등은 `Idempotency-Key` 단위다. 같은 키 재요청은 후보를 중복 생성하지 않고 기존 후보를 돌려준다.
- 후보 하나의 크기 상한(S15P21A501-290): `condition.all` 조건 10개, `patch.operations` 연산 10개까지. 연산이 새로 적는 값(`set` 값, `value_from` 없는 `add_item` 값)은 20자, AI 원본 해석과 대조하는 값(조건 `value`, `remove_item` 값, `value_from.value`)은 100자까지다. 길이는 UTF-16 코드 단위(JS `string.length`, Java `String.length()`)로 세며 한글 음절은 1자다. 문법 검사(`SRCH_400_201`)를 통과한 본문이 상한을 넘으면 `400 SRCH_400_203`으로 거부한다. 문법이 읽지 않는 필드(모르는 키, `unset`의 `type` 등)까지 막기 위해 저장되는 `condition`·`patch` JSON 문자열 길이 합도 8,192자까지로 두며, 넘으면 파싱 전에 같은 `SRCH_400_203`으로 거부한다(필드별 상한을 채운 가장 긴 정상 본문은 약 3,900자). `value_from`을 쓴 `add_item`에는 `value`를 보내지 않는다 — 빈 문자열만 없음으로 보고, 공백을 포함한 다른 값은 `SRCH_400_201`이다. 같은 `Idempotency-Key` 재요청은 이 검사보다 먼저 기존 후보를 돌려준다.
- 한 신고에서 대기 중인 후보는 10개까지다. 확정되어 활성이 된 규칙은 세지 않는다. 상한에 닿은 뒤에도 같은 키의 멱등 재요청은 기존 후보를 돌려준다.
- 같은 신고의 대기 중인 해석 후보끼리는 같은 활성 규칙을 교체 대상(`replacesRuleId`)으로 가리킬 수 없다 — 함께 확정하면 첫 교체가 그 규칙을 끈 뒤 둘째 교체가 실패해 전체가 롤백되기 때문이다. 이미 그런 대기 후보가 있으면 `409 SRCH_409_205`로 거부한다. 같은 `Idempotency-Key` 재요청은 이 검사보다 먼저 기존 후보를 돌려주므로 여전히 성공한다.
- 대기 중인 해석 후보는 아래 `DELETE /review/inquiries/{feedbackId}/parse-patch-candidate`로 취소한다. 상한에 닿으면 대기 후보를 취소하거나 남은 후보로 검증·확정하거나 판정을 다시 내려야 한다.
- 성공: 신규는 `201`, 멱등 재생은 `200`. `data`: `{ searchRuleId, feedbackId, active }`. `searchRuleId`·`feedbackId`는 정밀도 보존을 위해 문자열(TSID)이다 — §8의 신규 응답 string 규칙을 따른다(S15P21A501-202).

| 오류            | HTTP | 의미                              |
| --------------- | ---- | --------------------------------- |
| `SRCH_400_201`  | 400  | 규칙 후보 본문이 올바르지 않음    |
| `SRCH_400_202`  | 400  | 교체 대상 규칙을 찾을 수 없음     |
| `SRCH_400_203`  | 400  | 후보 크기 상한 초과(조건·연산 10개, 새 값 20자, 대조 값 100자, 본문 합 8,192자) |
| `SRCH_403_201`  | 403  | 검수자 아님                       |
| `SRCH_403_202`  | 403  | 담당 검수자 아님                  |
| `SRCH_404_201`  | 404  | 신고 없음                         |
| `SRCH_409_201`  | 409  | 검수 중이 아님                    |
| `SRCH_409_202`  | 409  | 해석 교정으로 처리된 신고 아님    |
| `SRCH_409_203`  | 409  | 원 검색에 교정할 해석 출력이 없음 |
| `SRCH_409_204`  | 409  | 신고당 후보 10개 초과             |
| `SRCH_409_205`  | 409  | 이미 같은 규칙을 교체하는 대기 후보가 있음 |

`DELETE /review/inquiries/{feedbackId}/parse-patch-candidate` (S15P21A501-309)

담당 검수자가 대기 중인 해석(`patch_parse`) 후보만 취소한다 — 같은 신고의 장면 제외 후보는 남긴다. 가드는 검수자 role·`REVIEWING`·담당 검수자로 같다. 이미 확정된 규칙은 건드리지 않는다. 성공은 body 없는 `200`이다.

`GET /review/inquiries/{feedbackId}/correction-candidates` (S15P21A501-317)

담당 검수자가 이 신고에 쌓인 **확정 전 대기 교정 후보 전체**(태그·해석·장면 제외)를 읽는다. 새로고침 뒤 작성 중이던 교정을 복원하는 용도다. 상세의 `evidence[]`는 확정 근거만 담으므로 대기 태그 후보는 이 조회로만 보인다.

- 가드: 검수자 role(보안 계층, 편집기자는 `403`) → 신고 존재(`FEEDBACK_404_002`) → 담당 검수자(`FEEDBACK_403_002`, 아직 아무도 잡지 않은 신고 포함). **검수 중이 아니면 빈 목록**이다 — 오류 없이 `200`에 세 목록 모두 `[]`. 대기 후보는 검수 중에만 존재하고(종료는 지우고 확정은 켠다), 종료 뒤의 `active=false` 규칙은 확정 뒤 사용 중단·교체된 이력이라 후보로 내보내지 않는다. 교정 상태 잠금은 잡지 않는다.
- 이 신고가 만든(`source_feedback_id`) 대기 후보만 돌려준다: 태그는 `source='reviewer_feedback'`·`confirmed=false` 근거, 규칙은 `active=false`인 `search_rule`. 이미 확정된 근거·활성 규칙·다른 신고의 후보는 빠진다. 각 목록은 만든 순서다.
- id는 모두 십진 문자열이다(§2.3·§8 신규 응답 string 규칙).

성공 `200` body `data`:

```json
{
  "tags": [
    { "evidenceId": "5001", "taggingId": "5501", "action": "REJECT", "scope": "SCENE", "tagType": "location", "matchValue": "서울", "displayName": "서울" }
  ],
  "parsePatches": [
    { "searchRuleId": "6602", "condition": { "version": "parse-rule/v1" }, "patch": { "version": "parse-rule/v1", "ops": [] }, "replacesRuleId": "6601" }
  ],
  "sceneExcludes": [
    { "searchRuleId": "6603", "targetSceneId": "9301" }
  ]
}
```

- `tags[]`: 태그 생성 요청의 변경안 한 줄로 되돌릴 수 있는 형태다. `action`은 `APPROVE|REJECT|WITHDRAW`, `scope`는 `SCENE|CLIP`(태깅의 장면 유무). `evidenceId`는 개별 취소(`DELETE …/tag-correction-candidate/{evidenceId}`)에 그대로 쓴다. `displayName`은 태그 사전의 표시 이름이다 — 이미 있던 태그를 재사용했으면 생성 때 보낸 `displayName`이 아니라 사전 값이 나온다.
- `parsePatches[]`: `condition`·`patch`는 생성 요청에 보낸 것과 같은 `parse-rule/v1` JSON **객체**다(문자열 아님, jsonb 저장이라 key 순서는 보존되지 않는다). `replacesRuleId`는 교체 대상이 없으면 `null`.
- `sceneExcludes[]`: 제외 대상 장면 id.
- 후보가 없으면 세 목록 모두 빈 배열이다.

| 오류               | HTTP | 의미                        |
| ------------------ | ---- | --------------------------- |
| `FEEDBACK_403_002` | 403  | 담당 검수자 아님            |
| `FEEDBACK_404_002` | 404  | 문의 없음                   |

`POST /review/inquiries/{feedbackId}/verify` (S15P21A501-83)

검수 중(`REVIEWING`)인 신고에서, 담당 검수자가 대기 중인 교정 후보(태그·해석 patch·장면 제외)를 실제로 재검색해 결과를 확인한다(F-12). Body 없음 — 원 신고의 검색어·명시 필터를 서버가 자동으로 다시 불러온다(F-12 2). 후보는 이 요청에만 임시로 반영되고 롤백되므로 일반 검색과 확정 데이터는 바뀌지 않는다(F-12 3). 기존 규칙을 교체하는 경우 검증 조합은 **현재 활성 규칙 − 교체 대상(R1) + 신규 후보(R2)**다. 태그와 해석을 함께 고치면 최종 조합 하나로 검증한다(F-12, 한 번의 검증 실행).

일반 검색과 같은 검색 코드(`interpret`+`rank`)를 그대로 태운다 — 이미 나온 결과의 배지만 바꾸거나 재정렬하는 것이 아니라 실제로 다시 계산한다(F-12).

성공 `200` body `data`:

```json
{
  "execution_id": "9702",
  "entered_scenes": [
    { "scene_id": "9302", "clip_id": "9202", "display_name": "제주 뉴스", "scene_description": "제주 해안 풍경", "start_time_ms": 42000, "end_time_ms": 49000, "reason": { "match": { "matched_keywords": [{ "keyword": "제주도", "origin": "expanded" }], "match_evidence": [] }, "score": { "base_score": 1.2 } } }
  ],
  "dropped_scenes": [
    { "scene_id": "9301", "clip_id": "9201", "display_name": "서울 뉴스", "scene_description": "서울역 대합실", "start_time_ms": 12000, "end_time_ms": 19000, "reason": "approved_scene_exclusion" }
  ],
  "verification_rule_set": ["8001", "8002"]
}
```

- `execution_id`는 이 검증 재검색이 남긴 replay 실행 ID다(정밀도 보존을 위해 문자열). 확정(`/confirm`, S15P21A501-84)이 이 값을 근거로 받는다.
- `entered_scenes`는 대조군(후보 미적용)에는 없다가 실험군(후보 적용) 결과에 새로 들어온 장면과 그 이유(`match`·`score` 근거, `SearchExplain`과 같은 모양)다. `dropped_scenes`는 대조군에는 있다가 실험군 결과에서 빠진 장면과 사유 문자열이다: `approved_scene_exclusion`(제외 규칙에 걸림) · `false_hit_guard`(F-06 판정에 걸림) · `score_drop`(그 외 순위·컷오프 이탈). 이 diff는 같은 롤백 트랜잭션 안에서 후보를 뺀 검색(대조군)과 넣은 검색(실험군)을 나란히 돌려 비교한 것이라 후보의 순효과만 반영하며, 원래 저장된 결과 행과 비교하지 않는다 — 저장 결과와 비교하면 재처리·코퍼스 변화 같은 후보와 무관한 drift가 섞인다. 두 목록의 `clip_id`·`start_time_ms`·`end_time_ms`는 변경된 장면 재생에 쓰고, nullable `display_name`·`scene_description`은 간결한 결과 카드에 쓴다.
- `verification_rule_set`은 이번 검증이 실제로 적용한 patch_parse 규칙 ID 집합(활성 − R1 + R2)이다. 문자열 배열이다.
- **검증 성공이 자동 승인이 아니다(F-12 5).** 이 응답을 받아도 태그·규칙은 확정되지 않는다 — 검수자가 결과를 확인하고 별도로 `/confirm`을 호출해야 한다. 변경안을 다시 수정하면 다시 검증해야 한다.

오류: 다른 교정 후보 endpoint(§6.4 태그/해석/장면 제외 후보)와 같은 성격의 담당자·상태·후보 존재 검사를 `VerificationSearchService.verify`가 수행한다. 신고 존재 확인이 가장 먼저이므로, 신고가 아예 없으면 아래 상태·후보 검사까지 가지 않고 404로 끝난다. `SRCH_409_231`(신고 상태가 `REVIEWING`이 아님)과 `SRCH_409_232`(대기 중인 교정 후보가 없음)는 서로 다른 검사다 — 신고가 검수 중이어도 대기 후보가 하나도 없으면 232로 끝난다.

| 오류            | HTTP | 의미                              |
| --------------- | ---- | --------------------------------- |
| `SRCH_403_231`  | 403  | 담당 검수자 아님 (claim 전 포함)  |
| `SRCH_404_231`  | 404  | 신고 없음                         |
| `SRCH_409_231`  | 409  | 검수 중이 아님                    |
| `SRCH_409_232`  | 409  | 대기 중인 교정 후보가 없음        |
| `SRCH_409_205`  | 409  | 이미 같은 규칙을 교체하는 대기 후보가 있음 |

`SRCH_409_205`는 해석 후보 생성의 같은 검사를 검증 직전에 한 번 더 하는 방어다 — 생성 단계에서 이미 막지만, 레거시·직접 주입 데이터로 같은 규칙을 교체하는 대기 후보가 둘 이상 남아 있으면 재검색 전에 거부한다.

동시성 노트: 이 endpoint 한 요청은 실행 시작 기록(`REQUIRES_NEW`) → 외부 롤백 트랜잭션 → 완료/실패 기록(`REQUIRES_NEW`) 순으로 커넥션을 쓴다. 트랜잭션은 겹치지 않으므로 동시에 열리는 커넥션은 최대 1개다. 그래도 검증 동시 요청 상한 또는 커넥션 풀 크기는 동시 요청 수 × 2 이상을 보수적으로 권장한다. AI 리졸버 호출은 롤백 트랜잭션을 열기 전에 끝낸다(S15P21A501-219) — 트랜잭션은 flip과 재랭킹만 담당해 후보 행 잠금·커넥션을 점유하는 시간이 짧다.

복수 규칙 후보는 모두 임시 활성화해 재검색하고 `verification_context_json.candidate_rules`에 `(approved_rule_id, replaced_rule_id, action)` 전체를 기록한다. `/confirm`은 이 `candidate_rules` 전체를 돌며 규칙마다 종류(`patch_parse`/`exclude_scene`)대로 활성화·교체를 한 트랜잭션으로 확정한다 — 하나라도 실패하면 전체를 롤백하므로 일부만 반영되지 않는다. 단수 `approved_rule_id`/`replaced_rule_id`/`approved_rule_action`은 첫 규칙 값으로 계속 기록하며(하위호환·신고의 `created_rule_id` 감사 기록), `candidate_rules`가 없는 레거시 스냅샷은 이 단수 필드로 한 건을 확정한다.

`POST /review/inquiries/{feedbackId}/confirm` (S15P21A501-84)

검수자가 확인한 검증 실행을 근거로 교정을 확정한다(F-13). 태그 근거 `confirmed=true`·규칙 활성화·교체와 신고 종료를 한 트랜잭션으로 처리한다.

```json
{ "executionId": "9702" }
```

- `executionId`는 이 신고의 성공한 재검색(replay) 실행이어야 한다 — 다른 신고·일반 검색 실행은 근거로 쓸 수 없다.
- 확정은 검증 실행에 실린 두 축을 함께 적용한다 — `correction` 실행이 태그 근거와 규칙 후보를 모두 담고 있으면 태그 근거 확정과 규칙 활성화·교체를 함께 처리하고, 신고의 처리 결과는 `correction`으로 기록한다(F-09). 장면 제외 후보가 함께 실려 있으면 제외 규칙도 활성화한다.
- **장면 제외 후보는 확정 직전에 대상 장면 유효성을 재확인한다(F-14).** 재처리로 대상 장면이 사라졌으면 `CONFIRM_409_004`로 승격을 중단하고 신고는 `reviewing`을 유지한다 — 구 장면 제외를 새 장면에 자동 적용하지 않는다.
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
- `mine=true`면 로그인한 검수자가 등록한 클립만 반환한다. 등록자는 세션에서 정하며 요청이 보낸 사용자 ID는 쓰지 않는다(§2.1). 생략·`false`면 전체다. `status`와 함께 쓸 수 있다.
- `items[].registered_by`는 그 클립을 등록한 검수자다. 공개 범위는 `login_id` **하나뿐**이며 내부 식별자 `registered_by_id`는 어떤 응답에도 싣지 않는다. 계정이 조회되지 않으면 null이다. 상세 `GET /clips/{id}`의 `clip`에도 같은 필드가 붙는다.
- `mine`은 권한 경계가 아니라 편의 필터다. 검수자는 `mine`을 빼면 다른 검수자가 등록한 클립까지 본다 — FRD §2의 역할 정책이 클립을 등록자별로 격리하지 않기 때문이다. 상세 `GET /clips/{id}`에는 이 필터가 없다.
- `total_elements`, `total_pages`, `has_next`는 필터 적용 결과다. 한 페이지의 `items`에는 같은 `clip_id`가 중복되지 않는다. `run_counts`의 `queued/running/failed/succeeded/no_run`은 `status`·페이지와 무관한 전체 건수이며 논리 삭제는 제외한다. 다만 `mine=true`면 `run_counts`도 그 검수자 범위로 좁아진다 — 화면의 상태별 건수 배지가 목록과 어긋나지 않게 하기 위해서다. 최신 run은 생성 시각, 동률이면 run ID로 결정한다.
- `items[].progress`는 해당 `latest_run`의 기록 요약이다. `current_stage`는 running 단계가 정확히 하나일 때 그 이름이며, 그 외에는 null이다. `total_steps/succeeded_steps/skipped_steps/failed_steps`는 저장된 단계 상태의 수이며 생략은 실패 수에 중복 포함하지 않는다. 필수 단계 생략으로 run 자체가 실패할 수 있다.
- run이 없으면 progress는 null이다. 기록이 부분·미확인·지원하지 않는 버전이면 `record_status`로 구분하고 단계 수는 null이다. 이 값을 0%나 완료로 추정하지 않는다. 진행 수는 소요 시간 기반 백분율이 아니다.
- `search_available`과 `active_pipeline_run_id`는 현재 검색 제공 결과, `latest_run`·`progress`·`processing_details`는 최신 처리 시도다. 논리 삭제를 제외하는 이 목록·상세 API에서 `search_available`은 `active_pipeline_run_id != null`과 동치다. 활성 처리 ID가 있으면 true, 없으면 false이며 최신 run 상태로 계산하지 않는다. 재처리 실패가 활성 결과를 무효화하지 않으며 검색 준비와 검수 완료는 별개다. 상세의 기본 대사 출처는 활성 결과 기준이다.
- 처리 상세는 단계 상태·실패 사유·누락 채널·실제 채택 대사 출처를 반환한다. `automatic_retryable`은 승인된 다음 자동 시도가 대기 중인지 나타낸다. 수동 재처리 가능 여부 `retryable`은 저장된 판정이 없어 null이며 재처리 API에서 별도로 연결한다.
- 잘못된 페이지는 `CLIP_QUERY_400`, 허용하지 않는 상태는 `CLIP_QUERY_400_001`, 없는/삭제된 클립은 `CLIP_QUERY_404`를 반환한다.

FE `/review?view=processing`은 위 목록·상세만 연결한다. 검수 중 문의는 §6.3의 문의 화면이 담당하며 이 화면에는 문의 탭이 없다. 상태 칩 4종은 서버의 다섯 상태를 접은 것으로 `clipStatus` URL 파라미터에 `processing`(=`queued,running`) · `attention`(=`failed,no_run`) · `done`(=`succeeded`)로 싣고, 전체는 파라미터를 생략한다. 「내 영상만 보기」는 `mine=true`이고, 칩·토글을 바꾸면 `progressPage`를 1로 되돌린다. 페이지네이션은 한 페이지뿐이어도 항상 표시하고 이동 가능 여부는 버튼 비활성으로 알린다(§6.3 문의 목록과 같은 규약). 칩 키가 `status`가 아닌 이유는 §6.3의 문의 목록이 같은 `/review` URL에서 `status`로 `open|reviewing|closed`를 쓰기 때문이다. 영상 등록 직후에는 `clipStatus`를 비워 새 영상이 목록에 남게 한다. 각 행에는 `registered_by.login_id`를 등록 시각 옆에 표시한다. 칩의 건수 배지는 `run_counts`를 같은 방식으로 접어 만든다. 상세에서 원본 영상은 §6.1 미디어 API로 재생한다. 등록 후에는 응답 ID로 상세를 다시 조회하며 로컬 처리·장면 데이터를 합성하지 않는다. 영상 목록은 전체 queued/running이 있으면, 상세는 해당 최신 run이 queued/running이면 5초마다 조회한다. 상세 응답의 `latest_run`이 null인 경우에도 `clip.created_at` 기준 등록 후 1분 동안은 5초마다 재조회한다. 그 이후에도 기록이 없으면 반복 조회를 멈추고 ‘상태 새로고침’으로 다시 확인하도록 안내한다. 완료·실패 및 조회 오류에서는 polling을 멈추며 사용자가 다시 조회할 수 있다.

기존 처리 화면의 mock 기능 중 다음 항목은 BE 추가·확장이 필요하다. 아래는 필요한 기능과 최소 데이터이며, 새로운 경로·method는 아직 확정하지 않는다.

| 기능 | BE 추가·확장 요구 | 현재 FE 처리 |
| --- | --- | --- |
| 수동 재처리 | 검수자 전용 command와 가능 여부·불가 사유. 대상 clip/최신 run 사전조건, 중복 요청 멱등성, 진행 중·영구 실패 거절 규칙, 기존 active run 보존, 새 pipeline run ID·상태 반환. `automatic_retryable`과 구분 | 가짜 재처리 버튼 제거. 실제 자동 재시도 이력만 표시 |
| 영상별 장면 목록 | 대상 clip과 run을 식별하는 페이지 조회. scene ID, 순서, 시작·종료 ms, 설명, 총 건수. active/latest run 중 어느 결과인지 명시 | 고정 장면 카드·장면 수 제거. 원본 영상 재생 제공 |
| 장면 썸네일 | scene ID 기반 byte 조회와 인증·cache 정책. 서버 내부 파일 경로 비노출 | 관련 없는 데모 이미지 대신 일반 영상 아이콘 |
| 영상 파일 메타데이터 | 기존 상세 응답에 공개 가능한 원본 파일명·용량·길이·방송일·촬영일을 필요에 따라 추가. nullable·단위 명시. **업로더 표시는 결정됨 — `registered_by.login_id`까지 공개하고 실명·내부 ID는 공개하지 않는다 (S15P21A501-266)** | 서버가 제공하는 제목·유형·등록 시각·등록자 표시. 요청 메모리로 누락값을 채우지 않음 |
| 문의 행의 추가 정보 | 기존 목록에 의견 미리보기·담당 검수자 등 실제 저장 정보 확장. 문의자·주제 노출은 도메인/권한 정책 확정 필요 | 실제 queryText·scene·createdAt·hasComment 표시 |

교정 흐름의 태그·해석·장면 제외 후보 생성과 최종 확정, 규칙 사용 중단은 §6.4에 공개 API가 정의되어 있다. 이를 신규 API 요구로 분류하지 않는다. 처리 화면의 문의 상세는 기존 실제 조회·claim·resolution 화면을 재사용하고 재검색 검증·확정 흐름까지 FE에 연결되어 있으며, 후보 생성 전용 편집 UI만 별도 작업으로 남아 있다.

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

- `comment`·`resolution`은 nullable이며 key를 생략하지 않고 null로 명시한다. `status`는 `OPEN`/`REVIEWING`/`CLOSED`, `resolution`은 처리 전이면 null, 처리됐으면 §6.4 표의 정본 값(`correction`/`no_action`/`deferred`) 중 하나이며 레거시 세부 종류(`tag_correction`/`patch_parse`/`exclude_scene`)도 하위호환으로 나올 수 있다.

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
- `snapshot_status`: `"available"`(당시 결과 스냅샷 복원됨) | `"unavailable"`(문의는 조회되나 복원 가능한 스냅샷 없음). 후자는 `result_snapshot: null`이다. 판정 기준은 저장된 `explain`의 `display.display_name`이 **문자열이거나 `null`**인지다 — 검색 실행 기록(S15P21A501-60)이 표시값을 `explain.display` 하위에 저장하고 생산자(S15P21A501-59)는 nullable `clip.title`을 그대로 기록하므로, `null`(제목 없는 영상)도 유효한 과거 값으로 `available`이며 원값을 보존한다(대체 표기는 표현 계층이 정한다). `display` 블록·`display_name` 키가 없거나 문자열·null 이 아니면(미기록·불완전) `unavailable`로 응답한다.
- `result_snapshot`: available일 때 `{ search_result_id, scene_id, rank, explain }`. `explain`은 검색 당시 표시값·점수 snapshot으로, 표시값은 `explain.display`(`display_name`·`scene_description` 등) 하위에 담긴다. **조회 시 현재 태그·장면으로 재계산하지 않는다**. 다만 `explain.match.matched_keywords`는 내 검색 기록(§6.7)과 **같은 규칙으로 맞춘다** — 출처를 남기지 않던 시절의 문자열 항목을 `origin: null`인 객체로 맞춰 응답과 같은 모양으로 내보낸다(S15P21A501-234). 두 복원 화면이 다른 변환을 타면 같은 기록이 화면마다 다른 출처로 보인다. 조회 오류는 unavailable로 처리하지 않고 `COMM_500`으로 응답한다.

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
  "snapshot_status": "available",
  "result_snapshot": {
    "search_result_id": "9802",
    "scene_id": "9302",
    "rank": 2,
    "explain": { "display": { "display_name": "KBC 뉴스9 · 설 연휴 교통", "scene_description": "서울역 귀성 인파" }, "score": 2 }
  }
}
```

저장된 검색 결과 snapshot이 없으면 `"snapshot_status": "unavailable", "result_snapshot": null`이다.

본인 소유가 아니거나, 참조하는 검색을 본인이 실행하지 않았거나, 존재하지 않는 `feedbackId`는 **동일하게 404**로 응답한다(존재 여부 비노출). 문의 작성자와 원 검색자가 모두 세션 사용자일 때만 조회된다 — 남의 검색 결과에 자기 명의로 만든 문의로 그 검색어·필터가 새어나가지 않게 한다.

| 오류               | HTTP | 의미                                          |
| ------------------ | ---- | --------------------------------------------- |
| `COMM_401`         | 401  | 미인증                                        |
| `COMM_400`         | 400  | `feedbackId` 형식 오류                        |
| `COMM_400_001`     | 400  | `feedbackId` 범위 오류(1 미만)                |
| `FEEDBACK_404_002` | 404  | 본인 소유 아님·타인 검색 참조·존재하지 않음(동일 취급) |
| `COMM_500`         | 500  | 서버 오류                                     |

### 6.7 내 검색 기록 (S15P21A501-198)

검색 화면 사이드바에서 로그인 사용자가 **본인이 실행한 검색**만 조회하고, 항목을 선택해 당시 결과를 다시 본다. §6.6과 같은 규약이다 — 세션 사용자 ID로만 범위를 좁히고, 응답은 snake_case, 모든 `*_id`는 십진 문자열이다. `EDITOR`와 `REVIEWER` 모두 여기서는 자기 기록만 본다.

§5의 `GET /search/executions/{executionId}`(S15P21A501-60)와 책임이 다르다. 저쪽은 소유자·검수자가 **한 실행의 해석·적용 규칙·제외 사유**를 감사하는 단건 조회이고, 이쪽은 사용자가 **자기 기록을 목록으로 훑고 당시 결과 카드를 복원**하는 화면용 조회다. 두 endpoint는 같은 `search_execution`/`search_result` 저장을 읽되 응답 모델과 대상 범위가 다르다.

**`status`는 저장값이 아니라 파생값이다.** `search_execution.status`와 응답 `status`는 다를 수 있다 — 승인된 해석 규칙이 충돌·비호환·실패로 건너뛰어지면 기록은 `degraded`로 닫히지만, 그 사유(`skipped_conflict:<rule_id>` 등)는 §5.1이 닫아 둔 공개 어휘 세 값에 없어서 `POST /search`는 같은 검색을 `succeeded`로 낸다. 저장값을 그대로 내면 **같은 검색이 검색 직후와 기록 조회에서 다른 상태로 보인다.** 이 절은 `search_snapshot`을 「§5 성공 `data`와 같은 object」로 규정하므로 공개 사유에서 파생해 두 화면을 일치시킨다. 상위 `data.status`와 `data.search_snapshot.status`도 항상 같은 값이다.

**`query_resolution_status`도 같은 출처에서 파생한다.** §5.1이 「`query_resolution_status=fallback` 여부는 `resolver_fallback` 포함 여부와 일치한다」를 요구하므로, 두 값을 각자 다른 컬럼(`parse_source`와 `degraded_reasons_json`)에서 뽑으면 그 불변식이 깨진다. 한 출처에서 파생하면 구조로 보장된다. `parse_source`는 **검증**에만 쓴다 — 스키마 어휘 밖이거나, `fallback` 여부가 `resolver_fallback` 포함 여부와 어긋나면 기록이 깨진 것이므로 `unavailable`이다. 어느 한쪽을 골라 내면 남은 한쪽이 말하는 사실을 지우게 된다.

규칙이 건너뛰어진 사실은 사라지지 않는다. `has_applied_review_rule`과 저장된 `applied_rules_json`에 남아 있고, 검수 감사용 단건 조회(§5의 `GET /search/executions/{executionId}`)로 규칙별 적용·건너뜀·실패와 사유를 볼 수 있다. 이 절은 사용자 화면용이라 공개 어휘만 낸다.

**조회 대상.** 본인의 `execution_type='original'` 중 **저장된** `status`가 `succeeded`/`degraded`인 실행이다(대상 판정에는 저장값을 쓴다 — `failed`·`running`을 걸러내는 것이 목적이다). `replay`(교정 검증 재검색)·`running`·`failed`는 DB에 보존하되 이 화면에서 제외한다. 정상 결과 0건 실행은 **포함한다.** 검색 1회가 1건이며 같은 검색어를 다시 실행하면 별도 기록이다. 대표 장면은 당시 1위 결과이며 사용자가 실제로 본 장면이라는 뜻이 아니다.

**정렬·페이지.** `created_at DESC, search_execution_id DESC` 고정(시각 동률도 결정적). `page`는 0 이상 정수 기본 0, `size`는 1~100 기본 10이며 **범위를 벗어나면 clamp하지 않고 400으로 거부한다.** 목록과 총계는 같은 범위 조건을 쓴다. 결과가 없어도 404가 아니라 200과 빈 `items: []`다. 마지막 페이지 이후 요청도 빈 `items`에 실제 `total_*`를 유지한다.

**`snapshot_status` 판정.** 목록과 상세가 **같은 규칙**을 쓴다 — 변하지 않은 기록의 판정이 두 응답에서 갈리지 않는다.

| 조건 | `snapshot_status` | `result_count`·`representative_result`·`search_snapshot` |
| --- | --- | --- |
| `filtered_json`이 object이고, `parse_source`가 스키마의 세 값 중 하나이고, 모든 결과 행에 `explain_json.display`·`.match`가 있음 | `available` | 실제 값 (결과 0건이면 `0`/`null`/`results: []`) |
| `filtered_json`이 없음 (결과 확정 전·저장 불완전) | `unavailable` | 전부 `null` |
| `filtered_json`이 object가 아님 (SQL NULL, JSON 리터럴 `null`, 배열 등) | `unavailable` | 전부 `null` |
| `parse_source`가 NULL이거나 스키마에 없는 값(`resolver`·`resolver_rule`·`fallback` 밖) | `unavailable` | 전부 `null` |
| `parse_source`가 `fallback`인지 여부와 `resolver_fallback` 포함 여부가 어긋남 | `unavailable` | 전부 `null` |
| 결과 행 하나라도 `display`/`match` 결측 | `unavailable` | 전부 `null` |
| 결과 행의 `display`에 표시 키가 빠졌거나 **타입이 어긋남**, 또는 구간이 `0 <= start < end`가 아님 | `unavailable` | 전부 `null` |
| 결과 행의 `match_evidence`가 비었거나 항목의 네 키가 없거나 **타입이 어긋남** | `unavailable` | 전부 `null` |
| 저장된 `rank`가 1부터 연속이 아님, 또는 결과가 10개 초과 | `unavailable` | 전부 `null` |
| `explicit_filters`를 읽을 수 없거나 날짜 필터가 손상됨 | `unavailable` | 전부 `null` + `explicit_filters: null` |

`filtered_json`의 유무가 **정상 0건과 저장 불완전을 가르는 유일한 근거**다. 둘을 섞지 않는다. `jsonb` 컬럼은 SQL NULL뿐 아니라 JSON 리터럴 `null`도 담을 수 있으므로 값이 비었는지가 아니라 **object인지**로 판정한다.

`parse_source`가 없으면 「어떻게 해석했는지 기록이 없다」는 뜻이므로 `resolved`로 접지 않는다. 없던 사실을 만들어 내지 않는다는 원칙은 아래 `display_name`과 같다(FRD §7.2).

`explicit_filters`는 **결과 snapshot만 손상된 경우**에는 실제 object를 유지한다. **필터 자체를 읽을 수 없거나 형태가 깨진 경우**에는 `null`이며 그때는 `snapshot_status`도 `unavailable`이다 — 둘을 구분하는 것이 이 필드의 목적이다. `null`을 `{}`나 현재 검색 화면의 필터로 보충하지 않는다. 필터 미선택(`{}`)과 「필터를 확인할 수 없다」(`null`)는 다른 사실이다.

손상 판정: object가 아니거나(SQL NULL, JSON 리터럴 `null`, 배열), 선택한 날짜 종류에 `from`/`to` 중 하나가 없거나, 값이 실제 달력 날짜가 아니거나, `from > to`인 경우다. SQL `NOT NULL` 제약은 이 중 어느 것도 막지 못하므로 읽는 쪽이 판정한다. 한쪽 경계만 있는 과거 미지원 형식이 정상 필터로 나가지 않는다.

S15P21A501-60이 `explicit_filters_json`을 `running`/`failed` 행 때문에 nullable로 완화했지만, 같은 마이그레이션의 `ck_execution_completed_snapshot`이 **결과를 낸 실행(`succeeded`/`degraded`)에는 NOT NULL을 되돌려** 보장한다. 이 절의 조회 대상이 그 두 상태뿐이므로 컬럼 자체가 비는 일은 실무상 없고, 위 판정은 그럼에도 형태가 깨진 기록을 위한 것이다.

`GET /search/history?page=0&size=10`

성공 `data`:

```json
{
  "items": [
    {
      "search_execution_id": "9701",
      "query_text": "서울역 귀성 인파",
      "explicit_filters": { "broadcast_date": { "from": "2026-09-01", "to": "2026-09-15" } },
      "created_at": "2026-09-15T03:00:00Z",
      "status": "succeeded",
      "snapshot_status": "available",
      "result_count": 1,
      "representative_result": {
        "search_result_id": "9801",
        "scene_id": "9301",
        "clip_id": "9101",
        "display_name": "예시 뉴스 · 서울역",
        "scene_description": "대합실 인파",
        "start_time_ms": 42000,
        "end_time_ms": 49000,
        "rank": 1
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

`result_count`·`representative_result`는 nullable이며 key를 생략하지 않고 null로 명시한다. 목록 항목에는 `search_snapshot`을 싣지 않는다.

| 오류           | HTTP | 의미                                   |
| -------------- | ---- | -------------------------------------- |
| `COMM_401`     | 401  | 미인증                                 |
| `COMM_400`     | 400  | `page`/`size` 형식 오류(정수 아님)     |
| `COMM_400_001` | 400  | `page`/`size` 범위 오류(size 1~100 밖) |
| `COMM_500`     | 500  | 서버 오류                              |

`GET /search/history/{searchExecutionId}`

목록 항목의 모든 필드에 `search_snapshot`을 더한다. `search_snapshot`은 **§5 `POST /search` 성공 `data`와 같은 object**이며 안에 envelope를 중첩하지 않는다. `search_execution_id`·`status`는 상위와 일치한다.

당시 기록에서만 복원한다 — 현재 태그·resolver·검색 API로 재계산하지 않는다(FRD §7.2). 복원에 쓰는 값과 출처:

| `search_snapshot` 필드 | 복원 출처 |
| --- | --- |
| `degraded_reasons` | `degraded_reasons_json` 중 **§5.1의 공개 어휘 세 값에 속하는 것만** |
| `status` | 위에서 추린 공개 사유가 비었으면 `succeeded`, 있으면 `degraded`. **`search_execution.status`를 그대로 쓰지 않는다** |
| `query_resolution_status` | 위에서 추린 공개 사유에 `resolver_fallback`이 있으면 `fallback`, 없으면 `resolved`. **`parse_source`에서 직접 뽑지 않는다** |
| `has_applied_review_rule` | `applied_rules_json`에 `status="applied"` 존재 여부 |
| `guard_summary.excluded_result_count`·`reasons` | **두 컬럼을 합친다.** `filtered_json.guard.verdicts` 중 `exclusion_reason`이 있는 것(guard 판정) + `applied_excludes_json`(승인된 장면 제외) |
| `shortage_reasons` | `filtered_json.shortage_reasons` |
| `results[]` | `search_result` 행 + `explain_json`의 `display`·`match` 블록 |

`matched_keywords`에는 AI가 넓힌 확장어가 섞일 수 있다. 검색에 쓰고 근거에서 빼면 확장어로만 걸린 장면이 「왜 나왔는지 모르는 결과」가 되므로 저장 쪽(S15P21A501-59)이 포함하기로 했고, 어느 것이 확장어인지는 항목의 `origin`으로 구분한다 (S15P21A501-234). **출처를 남기지 않던 시절에 저장된 기록은 문자열 배열이며, 복원할 때 `origin`을 `null`로 둔 객체로 맞춰 내보낸다** — `user`로 채우면 그 단어를 사용자가 실제로 쳤다고 기록이 주장하게 된다(FRD §7.2, `parse_source`를 `resolved`로 접지 않는 것과 같은 이유). 화면은 `null`을 이 변경 이전에 모든 칩이 보이던 모양 그대로 그린다 — 그 기록에는 구분이 없었으므로 당시 보이던 대로다. 응답과 `explain_json`은 같은 구조다.

`results[]` 한 항목은 `explain_json.display`·`explain_json.match`에 컬럼 4개(`search_result_id`·`scene_id`·`clip_id`·`rank`)를 얹은 것이다. 저장된 JSON을 **그대로 통과**시키며 필드별로 옮겨 담지 않는다. 키가 겹치면 **컬럼이 이긴다** — 저장 블록이 ID·순위를 덮어써 문자열 ID 규칙이 깨지지 않게 한다. `shot_type`은 저장값 원문을 그대로 낸다(§5.1의 4값 제약은 `POST /search` 응답에만 적용된다 — 기록을 소급 수정하지 않는다, FRD §7.2). `guard_summary`는 `explain_json.guard`가 아니다 — `search_result`에는 살아남은 장면만 남으므로 제외 건수를 알 수 없다. 출처가 두 컬럼인 이유는 `GuardExclusionReason`이 `explicit_date_conflict`·`approved_incident_conflict` 둘뿐이라서다. §5.1이 허용하는 세 번째 사유 `approved_scene_exclusion`은 guard가 내는 값이 아니라 `applied_excludes_json`(S15P21A501-58 승인 제외)에서만 온다. 한 컬럼만 읽으면 그 사유가 영원히 나오지 않고 건수가 `POST /search` 응답보다 작아진다.

같은 장면이 guard와 승인 제외에 모두 걸리면 **한 번만 센다.** `excluded_result_count`는 제외된 결과 수이므로 장면 기준이다.

성공 `data`(결과 1건, `available`):

```json
{
  "search_execution_id": "9701",
  "query_text": "서울역 귀성 인파",
  "explicit_filters": { "broadcast_date": { "from": "2026-09-01", "to": "2026-09-15" } },
  "created_at": "2026-09-15T03:00:00Z",
  "status": "succeeded",
  "snapshot_status": "available",
  "result_count": 1,
  "representative_result": {
    "search_result_id": "9801",
    "scene_id": "9301",
    "clip_id": "9101",
    "display_name": "예시 뉴스 · 서울역",
    "scene_description": "대합실 인파",
    "start_time_ms": 42000,
    "end_time_ms": 49000,
    "rank": 1
  },
  "search_snapshot": {
    "search_execution_id": "9701",
    "status": "succeeded",
    "degraded_reasons": [],
    "query_resolution_status": "resolved",
    "has_applied_review_rule": true,
    "guard_summary": { "excluded_result_count": 1, "reasons": ["explicit_date_conflict"] },
    "shortage_reasons": ["candidate_pool_exhausted"],
    "results": [
      {
        "search_result_id": "9801",
        "scene_id": "9301",
        "clip_id": "9101",
        "rank": 1,
        "display_name": "예시 뉴스 · 서울역",
        "scene_description": "대합실 인파",
        "start_time_ms": 42000,
        "end_time_ms": 49000,
        "shot_type": "b_roll",
        "scene_type": "역사 인파",
        "broadcast_date": { "value": "2026-09-14", "verification_status": "verified" },
        "filmed_date": { "value": null, "verification_status": "unknown" },
        "matched_keywords": [{ "keyword": "서울역", "origin": "user" }],
        "match_evidence": [
          {
            "field": "ocr",
            "value": "서울역",
            "source": "keyframe_ocr",
            "verification_status": "verified"
          }
        ]
      }
    ]
  }
}
```

`unavailable` 기록은 `result_count`·`representative_result`·`search_snapshot`이 null이며 `query_text`·`explicit_filters`·`created_at`·`status`는 유지한다.

**`display_name`은 nullable이다.** `clip.title`(`varchar(500)` nullable)을 그대로 기록하므로 제목 없는 클립이면 null이 남는다. 서버는 대체 문자열로 메우지 않는다 — 메우면 「제목이 없었다」와 「제목이 이랬다」를 나중에 구분할 수 없다(FRD §7.2). 표시 대체는 FE가 정한다. S15P21A501-185 본문의 「`display_name`은 non-empty string」은 이 스키마와 어긋나므로 nullable이 정본이다.

타인 소유·존재하지 않는 ID·이 화면 대상이 아닌 실행(`replay`/`running`/`failed`)은 **모두 같은 404**다(존재 여부 비노출).

| 오류            | HTTP | 의미                                                         |
| --------------- | ---- | ------------------------------------------------------------ |
| `COMM_401`      | 401  | 미인증                                                       |
| `COMM_400`      | 400  | `searchExecutionId` 형식 오류                                |
| `COMM_400_001`  | 400  | `searchExecutionId` 범위 오류(1 미만)                        |
| `SRCH_404_001`  | 404  | 본인 소유 아님·대상 밖 실행·존재하지 않음(동일 취급)         |
| `COMM_500`      | 500  | 서버 오류                                                    |

`DELETE /search/history/{searchExecutionId}` (S15P21A501-276)

사용자가 자기 기록 목록에서 기록 하나를 지운다. 대상 판정은 목록·상세와 **같은 범위**다 — 본인의 `execution_type='original'` 중 저장된 `status`가 `succeeded`/`degraded`인 실행. 성공은 `200`이며 `data`는 없다.

**행을 지우지 않는다.** `search_execution.deleted_at`에 시각을 남겨 목록·상세에서만 감춘다. 같은 행을 `GET /search/executions/{executionId}`(§5 감사 조회)와 신고·검수의 문의 상세가 함께 읽으므로, 물리 삭제하면 문의가 당시 결과를 잃는다. FRD §7.2도 「참조 중인 영상·장면·검색·신고·교정 기록을 지우는 하드 삭제 화면은 만들지 않는다」로 같은 원칙을 쓴다. 따라서 삭제 후에도 감사 조회와 문의 상세는 그대로 동작한다.

**멱등이다.** 이미 지운 기록에 같은 요청을 보내도 `200`이며, 처음 지운 시각을 유지한다. 두 번째만 404를 내면 FE가 재시도할 수 없고, 시각을 갱신하면 보존기간 판단의 기준이 흔들린다.

| 오류            | HTTP | 의미                                                         |
| --------------- | ---- | ------------------------------------------------------------ |
| `COMM_401`      | 401  | 미인증                                                       |
| `COMM_400`      | 400  | `searchExecutionId` 형식 오류                                |
| `COMM_400_001`  | 400  | `searchExecutionId` 범위 오류(1 미만)                        |
| `SRCH_404_001`  | 404  | 본인 소유 아님·대상 밖 실행·존재하지 않음(동일 취급)         |
| `COMM_500`      | 500  | 서버 오류                                                    |

`DELETE /search/history` (S15P21A501-291)

사용자가 자기 기록을 **한 번에 모두** 지운다. 경로 변수가 없는 컬렉션 요청이며, 대상은 단건 삭제와 **같은 범위**의 본인 가시 기록 전부다. 성공은 `200`이며 `data`는 없다.

**단건과 같은 soft delete 다.** 아직 보이는 행(`deleted_at IS NULL`)에만 시각을 남겨 목록·상세에서만 감춘다. 이미 숨긴 기록은 대상에서 빠져 지운 시각이 밀리지 않는다. 행을 지우지 않으므로 감사 조회(`GET /search/executions/{executionId}`)와 문의 상세의 「당시 검색 결과」 복원(S15P21A501-262)은 그대로 동작한다 — 이것이 하드 삭제 대신 soft delete를 쓰는 이유다.

**멱등이다.** 지울 기록이 없어도 `200`이다. 빈 목록을 다시 비우는 것은 오류가 아니라 같은 결과를 내는 재시도다.

| 오류            | HTTP | 의미      |
| --------------- | ---- | --------- |
| `COMM_401`      | 401  | 미인증    |
| `COMM_500`      | 500  | 서버 오류 |

**저장 계약과의 관계.** 이 절은 S15P21A501-60이 소유하는 `search_execution`/`search_result` 저장 형식을 **읽기만** 한다. -60은 저장 구현과 `explain_json` 4키 계약(`score`·`match`·`guard`·`display`)까지 병합 완료다. **아직 병합되지 않은 선행은 S15P21A501-59(`POST /search` 조립)뿐이다** — 실행을 만드는 쪽이 없어 현재 검증은 고정 DB fixture로 했고, 실제 검색 실행 → 기록 조회 왕복 확인은 -59 병합 후 별도로 기록한다.

읽는 쪽이 저장 형태를 **검증**하는 이유: -60의 저장 경계는 `explain_json` 내부 구조를 검사하지 않는다. 블록이 object인지만 보고 통과시키면 빈 `display`가 `available`로 나가 FE가 해석할 수 없는 `results`를 받는다. 그래서 값은 고치지 않되 아래를 확인하고, 하나라도 어긋나면 `unavailable`로 낸다.

| 대상 | 요구 |
| --- | --- |
| `display_name`, `scene_description`, `scene_type` | `null` 또는 **비어 있지 않은** string |
| `shot_type` | `anchor` \| `interview` \| `b_roll` \| `unknown` |
| `start_time_ms`, `end_time_ms` | 정수이며 `0 <= start < end` |
| `broadcast_date`, `filmed_date` | object. `value`가 `null`이면 `verification_status`는 `unknown`, 실제 달력 날짜(`YYYY-MM-DD`)면 `verified` \| `unverified` |
| `matched_keywords` | 배열 (빈 배열 자체는 허용). 항목은 `{keyword, origin}` 객체이거나, 출처를 남기지 않던 시절의 비어 있지 않은 문자열이다. `keyword`는 비어 있지 않은 문자열, `origin`은 `user`·`expanded` 중 하나이거나 `null` |
| `match_evidence` | 1개 이상. `field`는 `caption` \| `ocr` \| `transcript` \| `tag`, `verification_status`는 `verified` \| `unverified`, `source`는 비어 있지 않은 string, `value`는 `null` 또는 비어 있지 않은 string |
| 결과 행 전체 | `rank`가 1..N 연속이고 개수가 10 이하 |

**키 부재·타입 불일치·빈 문자열·어휘 밖 값을 모두 같게 다룬다** — 넷 다 「기록이 깨졌다」다. 키만 보면 `shot_type: {}`이, 타입만 보면 `shot_type: "legacy"`나 `value: null` + `verification_status: "verified"` 조합이 통과한다.

`unavailable`로 분류하는 것은 **저장값을 수정하거나 현재 태그로 재계산하는 것이 아니다.** FRD §7.2가 금하는 것은 과거 결과를 소급 수정하는 행위이고, 복원할 수 없다고 말하는 것은 그에 해당하지 않는다. 값은 그대로 두고 판정만 내린다.

**감수해야 할 위험:** 저장된 값이 나중에 어휘 밖이 되면(예: `shot_type` 어휘가 확장되고 과거 기록이 옛 값을 가진 경우) 그 기록은 영구히 `unavailable`이 된다. 어휘를 넓힐 때는 이 절의 허용 집합도 함께 넓혀야 한다. §6.6의 `result_snapshot`(S15P21A501-207)은 `display_name` 타입만 보는 더 느슨한 판정을 쓰므로, 같은 `explain_json`에 대해 두 절의 판정이 다를 수 있다.

### 6.8 장면 대표 이미지(thumbnail) — 원본 반환

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
| 2        | 검색 기록 목록·상세                  | §6.7로 확정·BE 구현. FE 연결(loading·empty·error·`unavailable` 구분)만 남음                    | `search-history.tsx` 고정 5건 |
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
| 내 검색 기록       | §6.7로 확정·BE 구현(조회·건별 삭제·전체 삭제), FE 연결 완료          | 저장(-60)·`explain_json` 4키 계약은 병합 완료. 삭제(-276 건별·-291 전체)는 모두 논리 삭제이며 감사 조회·문의 상세는 영향받지 않는다 |
| 처리 조회          | §6.5 실제 목록·상세·polling 연결, unknown/null 보존                    | 수동 재처리·장면 목록·썸네일 및 추가 메타데이터 계약 필요                                 |

미확정 항목은 FE demo model이나 Java DTO를 복사해 새 정본으로 만들지 않는다. 합의가 끝나면 이 문서를 먼저 갱신하고 양쪽 구현과 계약 테스트를 맞춘다.
