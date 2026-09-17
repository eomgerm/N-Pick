#!/usr/bin/env bash
# 데모 시드 영상을 POST /clips 로 순차 등록한다 (docs/contracts/web-api.md §4).
# Idempotency-Key 가 파일 sha256 이라 같은 영상은 재실행해도 같은 키를 낸다.
#
#   NPICK_API_BASE=https://호스트/api/v1 NPICK_LOGIN_ID=... NPICK_PASSWORD=... \
#     scripts/seed-clips.sh <영상폴더>
#
# 영상 옆 같은 이름 .json 사이드카에서 읽는다. clipType 과 rightsConfirmed 는 필수다:
#   {"clipType":"broadcast|archive","title":"...","broadcastDate":"YYYY-MM-DD",
#    "filmedDate":"YYYY-MM-DD","subtitle":"a.srt",
#    "rightsConfirmed":true,"externalProcessingConfirmed":false}
# 권리 확인을 스크립트가 일괄로 찍지 않는 이유는 PRD §12.4 다 — clip 별 확인을
# 배포 수준 허용으로 대신할 수 없다. subtitle 경로는 영상 폴더 기준이다.
#
# 계약(web-api.md §4)의 선택 필드 `script_text` 는 **지원하지 않는다.** 자막은 파일로
# 올리면 되고, 원고 본문을 사이드카에 통째로 넣는 것은 시드 용도를 넘는다. 필요해지면
# 사이드카에 키를 하나 더 두고 --form-string 으로 실으면 된다.
#
# **한 영상의 실패가 나머지를 죽이지 않는다.** 등록되지 않은 영상은 세어서 마지막에
# 종료 코드로 알린다. `set -e` 아래에서 이걸 지키려면 실패할 수 있는 명령을 할당문에
# 그냥 두면 안 된다 — 할당문의 명령 치환 실패는 스크립트를 즉사시킨다.
set -euo pipefail

# 기본값을 두지 않는다. 인자 하나 잘못 주고 엔터 치면 운영 정본에 들어간다.
BASE="${NPICK_API_BASE:?NPICK_API_BASE 를 준다 (예: https://호스트/api/v1)}"
: "${NPICK_LOGIN_ID:?검수자 계정 아이디를 준다}" "${NPICK_PASSWORD:?검수자 계정 비밀번호를 준다}"
DIR="${1:?영상 폴더를 인자로 준다}"
JAR="$(mktemp)"; BODY="$(mktemp)"; trap 'rm -f "$JAR" "$BODY"' EXIT
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }  # Netscape jar: $6 이름, $7 값
field() { jq -r --arg k "$1" '.[$k] // empty' "$2"; }

# 세션은 JSESSIONID cookie 다. 검수자 계정으로 한 번 로그인해 jar 를 재사용한다.
# 비밀번호는 **표준 입력으로만** 넘긴다. jq 의 --arg 도, curl 의 -d "$(...)" 도
# 결국 argv 에 실려 팀 공용 서버의 `ps` 에 그대로 보인다. pipefail 이 jq 실패도 잡는다.
# 실패에 말을 붙인다. -fsS 만 두면 set -e 가 메시지 없이 죽어서 운영자에게는 curl
# 종료 코드만 남고, 원인이 주소인지 자격증명인지 구분되지 않는다.
curl -fsS -c "$JAR" "$BASE/auth/csrf" >/dev/null ||
  { echo "CSRF 준비에 실패했다. NPICK_API_BASE 를 확인한다: $BASE" >&2; exit 1; }
jq -n '{loginId: env.NPICK_LOGIN_ID, password: env.NPICK_PASSWORD}' |
  curl -fsS -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" \
    -H 'Content-Type: application/json' --data-binary @- "$BASE/auth/login" >/dev/null ||
  { echo "로그인에 실패했다. 검수자 계정과 비밀번호를 확인한다 (REVIEWER 권한 필요)" >&2; exit 1; }

# nocaseglob 으로 대소문자를 한 번에 받는다. 확장자를 둘로 나열하면 대소문자를
# 구분하지 않는 파일시스템에서 같은 영상이 두 번 매칭된다.
shopt -s nullglob nocaseglob
failed=0
fail() { echo "  $1" >&2; failed=$((failed + 1)); }

for video in "$DIR"/*.{mp4,mov,mkv}; do
  meta="${video%.*}.json"
  [ -f "$meta" ] || { fail "사이드카가 없어 등록하지 못했다: $video"; continue; }
  # 파일당 한 번 검사한다. 통과한 뒤에는 개별 field 호출이 파스로 실패할 수 없으므로
  # 할당문마다 감쌀 필요가 없다. 이게 없으면 사이드카 오타 하나가 배치를 즉사시킨다.
  jq -e 'type == "object"' "$meta" >/dev/null 2>&1 ||
    { fail "사이드카가 JSON object 가 아니다: $meta"; continue; }

  source_type=$(field clipType "$meta")
  rights=$(field rightsConfirmed "$meta")
  if [ -z "$source_type" ] || [ "$rights" != "true" ]; then
    fail "사이드카에 clipType 또는 rightsConfirmed=true 가 없다: $meta"; continue
  fi

  form=(-F "video=@$video" --form-string "source_type=$source_type"
        --form-string 'rights_confirmed=true')
  external=$(field externalProcessingConfirmed "$meta")
  form+=(--form-string "external_processing_confirmed=${external:-false}")
  # 텍스트 필드에 -F 를 쓰면 안 된다. curl 은 값이 @ 나 < 로 시작하면 파일로 읽는다.
  title=$(field title "$meta");       [ -z "$title" ]    || form+=(--form-string "title=$title")
  filmed=$(field filmedDate "$meta"); [ -z "$filmed" ]   || form+=(--form-string "filmed_date=$filmed")
  subtitle=$(field subtitle "$meta"); [ -z "$subtitle" ] || form+=(-F "subtitle=@$DIR/$subtitle")
  broadcast=$(field broadcastDate "$meta")
  if [ -n "$broadcast" ]; then
    if [ "$source_type" = "archive" ]; then
      fail "자료 영상에는 방송일을 넣을 수 없다: $meta"; continue
    fi
    form+=(--form-string "broadcast_date=$broadcast")
  fi

  echo "등록: $video"
  # -f 를 쓰지 않는다. 본문을 버려서 어떤 오류인지 알 수 없다. 대신 상태로 직접 가른다.
  # **상태를 `|| rc=$?` 로 받는 것이 핵심이다.** 연결 거부·타임아웃·DNS 실패는 curl 이
  # 비0 으로 끝나는데, 맨 할당문에 두면 set -e 가 남은 영상을 두고 배치를 죽인다.
  # `if ! ...` 로 감싸는 형태도 죽지는 않지만 본문의 $? 가 0 이라 오류 번호를 잃는다.
  # 할당문에 그냥 두면 안 된다 — 읽을 수 없는 파일이나 sha256sum 이 없는 환경
  # (macOS 는 shasum)에서 set -e 가 배치를 통째로 죽인다. 인자 위치로 되돌리는 것도
  # 답이 아니다. 실패해도 빈 키로 요청이 나가고, 빈 키끼리 서로 충돌한다.
  idem=$(sha256sum "$video" | cut -d' ' -f1) ||
    { fail "멱등 키를 만들지 못했다: $video"; continue; }
  code=$(curl -sS -o "$BODY" -w '%{http_code}' -b "$JAR" -c "$JAR" \
    -H "X-XSRF-TOKEN: $(xsrf)" -H "Idempotency-Key: $idem" \
    "${form[@]}" "$BASE/clips") && rc=0 || rc=$?
  if [ "$rc" -ne 0 ]; then
    fail "전송하지 못했다 (curl $rc): $video"; continue
  fi

  case "$code" in
    2*)
      # 계약 §2.2 — HTTP 실패와 `isSuccess: false` 중 하나라도 실패면 실패다. BE 의
      # GlobalExceptionHandler 가 오류를 전부 비2xx 로 매핑하므로 지금은 겹치지만,
      # 앞단 프록시가 2xx HTML 을 내면 이 확인이 없을 때 ok 로 찍힌다. 그래서 봉투가
      # 성공을 말할 때만 ok 로 친다 — `isSuccess` 가 없는 2xx 도 통과시키지 않는다.
      #
      # `.isSuccess // empty` 를 쓰면 안 된다. jq 의 `//` 는 `false` 도 빈 값으로
      # 취급해서 정확히 잡아야 할 경우를 놓친다.
      if [ "$(jq -r 'if .isSuccess == true then "y" else "n" end' "$BODY" 2>/dev/null ||
              echo n)" = "y" ]; then
        echo "  ok $code"
      else
        fail "$code 인데 성공 봉투가 아니다: $(head -c 200 "$BODY")"
      fi
      ;;
    409)
      # 셋을 묶으면 안 된다. 002 만 "같은 요청이 처리 중" 이라 재실행의 정상 경로이고,
      # 001(키 충돌)·003(등록 삭제됨)은 **등록되지 않은** 상태라 새 키가 필요하다.
      # 그건 사람이 판단할 일이므로 건너뛰되 빠진 것으로 센다.
      clip_code=$(jq -r '.code // empty' "$BODY" 2>/dev/null || echo "")
      if [ "$clip_code" = "CLIP_409_002" ]; then
        echo "  이미 처리 중이다. 재실행 경로이므로 건너뛴다"
      else
        fail "등록되지 않았다 (${clip_code:-409}). 새 요청 키가 필요하다: $video"
      fi
      ;;
    *) fail "실패 $code: $(head -c 400 "$BODY")";;
  esac
done

[ "$failed" -eq 0 ] || { echo "$failed 건이 등록되지 않았다" >&2; exit 1; }
