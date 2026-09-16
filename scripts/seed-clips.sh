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
set -euo pipefail

# 기본값을 두지 않는다. 인자 하나 잘못 주고 엔터 치면 운영 정본에 들어간다.
BASE="${NPICK_API_BASE:?NPICK_API_BASE 를 준다 (예: https://호스트/api/v1)}"
DIR="${1:?영상 폴더를 인자로 준다}"
JAR="$(mktemp)"; BODY="$(mktemp)"; trap 'rm -f "$JAR" "$BODY"' EXIT
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }  # Netscape jar: $6 이름, $7 값
field() { jq -r --arg k "$1" '.[$k] // empty' "$2"; }

# 세션은 JSESSIONID cookie 다. 검수자 계정으로 한 번 로그인해 jar 를 재사용한다.
# 비밀번호는 env 로 넘긴다 — --arg 로 주면 argv 에 실려 `ps` 로 새어 나간다.
curl -fsS -c "$JAR" "$BASE/auth/csrf" >/dev/null
curl -fsS -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
  -d "$(jq -n '{loginId: env.NPICK_LOGIN_ID, password: env.NPICK_PASSWORD}')" \
  "$BASE/auth/login" >/dev/null

# nocaseglob 으로 대소문자를 한 번에 받는다. 확장자를 둘로 나열하면 대소문자를
# 구분하지 않는 파일시스템에서 같은 영상이 두 번 매칭된다.
shopt -s nullglob nocaseglob
failed=0
for video in "$DIR"/*.{mp4,mov,mkv}; do
  meta="${video%.*}.json"
  [ -f "$meta" ] || { echo "사이드카 없음, 건너뜀: $video" >&2; failed=$((failed + 1)); continue; }

  source_type=$(field clipType "$meta")
  rights=$(field rightsConfirmed "$meta")
  if [ -z "$source_type" ] || [ "$rights" != "true" ]; then
    echo "사이드카에 clipType 또는 rightsConfirmed=true 가 없다, 건너뜀: $meta" >&2
    failed=$((failed + 1)); continue
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
      echo "자료 영상에는 방송일을 넣을 수 없다, 건너뜀: $meta" >&2
      failed=$((failed + 1)); continue
    fi
    form+=(--form-string "broadcast_date=$broadcast")
  fi

  echo "등록: $video"
  # -f 를 쓰지 않는다. 본문을 버려서 어떤 오류인지 알 수 없고, set -e 가 첫 실패에서
  # 배치를 통째로 죽인다. 상태로 직접 갈라 나머지 영상을 계속 등록한다.
  code=$(curl -sS -o "$BODY" -w '%{http_code}' -b "$JAR" -c "$JAR" \
    -H "X-XSRF-TOKEN: $(xsrf)" \
    -H "Idempotency-Key: $(sha256sum "$video" | cut -d' ' -f1)" \
    "${form[@]}" "$BASE/clips")
  case "$code" in
    2*) echo "  ok $code";;
    # 409_002 는 같은 요청이 처리 중이라는 정상적인 재실행 경로다. 001·003 은 같은
    # 키에 다른 내용이거나 지워진 등록이라 새 키가 필요하다 — 사람이 판단할 일이다.
    409) echo "  건너뜀 $(jq -r '.code // "409"' "$BODY"): $(jq -r '.message // ""' "$BODY")" >&2;;
    *) echo "  실패 $code: $(head -c 400 "$BODY")" >&2; failed=$((failed + 1));;
  esac
done

[ "$failed" -eq 0 ] || { echo "$failed 건 실패" >&2; exit 1; }
