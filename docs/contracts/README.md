# 인터페이스 계약

서비스 서버와 AI 컨테이너 사이의 계약을 담는다. FRD가 "API 주소·전체 JSON 스키마·HTTP 오류 코드 목록은 API 명세에서 정한다"([docs/frd.md](../frd.md) §6.3)로 위임한 그 명세가 여기다.

C4 문서 세 종([docs/architecture/](../architecture/))은 배포 단위와 통신 프로토콜까지를 정본으로 삼는다. 그 안의 요청·응답 스키마와 오류 코드는 이 디렉터리가 정본이다.

## 계약 목록

| 계약 | 문서 | 당사자 | 성격 |
| --- | --- | --- | --- |
| 잡 API | [job-api.md](job-api.md) | 서비스 서버 ↔ 파이프라인 워커 | 워커가 발신자, long-poll |
| 리졸버 API | *(미작성)* | 서비스 서버 ↔ 질의 리졸버 | 서비스 서버가 발신자, 동기 호출 전용 |

출력 JSON 키와 schema는 확정됐다. 정본은 `ai/src/npick_worker/query_resolver/schema.py`이고 버전은 `query-resolver/v2`다([docs/frd.md](../frd.md) §11). 그 파일이 스스로 정본임을 적고 있으므로 여기에 옮겨 적지 않는다. HTTP 계층의 계약 문서(주소·오류 코드·요청 형식)는 아직 쓰지 않았고, 작성 여부는 별건으로 판단한다.

## 왜 문서를 나누는가

AI 컨테이너는 둘이다 — 질의 리졸버와 파이프라인 워커([02-container.md](../architecture/02-container.md)의 *요소* 표). 둘의 호출 성질이 반대다.

| | 파이프라인 워커 | 질의 리졸버 |
| --- | --- | --- |
| 누가 부르는가 | 워커가 서비스 서버를 부른다 | 서비스 서버가 리졸버를 부른다 |
| 대기 | long-poll (초 단위) | 동기 (p95 10초 예산 안) |
| 재시도 | lease·attempt로 관리 | **없음** |
| 상태 | `pipeline_run`에 남는다 | 없다 |

그래서 잡 API의 절반 — long-poll, lease와 heartbeat, 멱등성 키, 일시/영구 재시도 분류 — 은 리졸버에 해당 사항이 없다. 한 문서로 합치면 리졸버 구현자가 자기와 무관한 lease 상태 기계를 먼저 읽어야 한다.

실제로 공유되는 것은 아래 둘뿐이고, 그 둘만 이 문서에 둔다.

## 공용 규약 1 — 버전 필드

두 계약 모두 산출물에 버전을 싣는다. 형식이 다르면 BE가 파서를 두 벌 들어야 하므로 형식만은 같다.

**schema 이름**은 `<이름>/v<정수>` 다.

```
scene-detect/v1
npick.stage.scene_detection.output/v1
query-resolver/v1
```

**값 해시**는 `<schema>:<정규화 JSON의 sha256 앞 N자>` 다.

```
scene-detect/v1:20dfc0a6
```

정규화 규칙은 하나다. 같은 값이면 어느 프로세스에서 계산해도 같은 바이트열이 나와야 한다.

```python
json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
```

구현은 `ai/src/npick_worker/versioning.py` 하나다. BE가 같은 값을 Java로 계산하는 경우(`pipeline_version` 롤업)를 위해 각 계약이 고정 테스트 벡터를 싣는다.

기본 해시 길이는 8자다. 사람이 로그에서 읽는 값이므로 충돌 확률보다 가독성을 택했다. DB 키로 등가 비교되는 값(`pipeline_version`)만 12자를 쓴다.

## 공용 규약 2 — 오류 코드 접두

BE의 오류 코드는 `<PREFIX>_<HTTPSTATUS>[_<NNN>]` 형식이다(`backend/src/main/java/com/npick/common/error/CommonErrorCode.java`). 계약마다 접두를 하나씩 점유한다.

| 접두 | 소유 |
| --- | --- |
| `COMM_` | 공통 (`CommonErrorCode`) |
| `JOB_` | 잡 API |
| *(미정)* | 리졸버 API |

HTTP 계층의 코드와 별개로, 단계 산출물에 실리는 도메인 오류 어휘(`stage_states_json`의 `errorCode` 등)는 접두 없이 대문자 스네이크로 쓴다. 계약마다 목록을 자기 문서에 둔다.

## 새 계약을 추가할 때

1. `docs/contracts/<이름>.md`를 만들고 위 표에 한 줄 추가한다.
2. 오류 코드 접두를 위 표에 등록한다. 다른 계약과 겹치지 않아야 한다.
3. 버전 필드는 공용 규약을 따른다. 새 해시 방식을 만들지 않는다.
4. 계약이 요구하는 스키마 변경은 **요구사항으로만** 적고 마이그레이션을 여기서 설계하지 않는다.
