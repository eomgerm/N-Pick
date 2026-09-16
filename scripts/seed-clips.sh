#!/usr/bin/env bash
# 데모 시드 영상을 POST /clips 로 순차 등록한다 (docs/contracts/web-api.md §4).
# Idempotency-Key 가 파일 sha256 이라 재실행해도 중복 등록이 생기지 않는다.
#
#   NPICK_LOGIN_ID=... NPICK_PASSWORD=... scripts/seed-clips.sh <영상폴더>
#
# 영상 옆 같은 이름 .json 사이드카에서 읽는다:
#   {"clipType":"broadcast|archive","title":"...","filmedDate":"YYYY-MM-DD","subtitle":"a.srt"}
# title·filmedDate·subtitle 은 없으면 생략한다. subtitle 경로는 영상 폴더 기준이다.
set -euo pipefail

BASE="${NPICK_API_BASE:-https://j15a501.p.ssafy.io/api/v1}"
DIR="${1:?영상 폴더를 인자로 준다}"
JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }  # Netscape jar: $6 이름, $7 값

# 세션은 JSESSIONID cookie 다. 검수자 계정으로 한 번 로그인해 jar 를 만들어 재사용한다.
curl -fsS -c "$JAR" "$BASE/auth/csrf" >/dev/null
curl -fsS -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg i "${NPICK_LOGIN_ID:?}" --arg p "${NPICK_PASSWORD:?}" '{loginId:$i,password:$p}')" \
  "$BASE/auth/login" >/dev/null

shopt -s nullglob
for video in "$DIR"/*.{mp4,mov,mkv,MP4,MOV,MKV}; do
  meta="${video%.*}.json"
  [ -f "$meta" ] || { echo "사이드카 없음, 건너뜀: $video" >&2; continue; }
  form=(-F "video=@$video" -F "source_type=$(jq -r '.clipType' "$meta")" -F 'rights_confirmed=true')
  # 시드는 검수자가 권리를 확인한 자기 소재다. 처리 설정이 외부 AI 동의를 요구할 때만 쓰인다.
  form+=(-F 'external_processing_confirmed=true')
  title=$(jq -r '.title // empty' "$meta");       [ -z "$title" ] || form+=(-F "title=$title")
  filmed=$(jq -r '.filmedDate // empty' "$meta"); [ -z "$filmed" ] || form+=(-F "filmed_date=$filmed")
  subtitle=$(jq -r '.subtitle // empty' "$meta"); [ -z "$subtitle" ] || form+=(-F "subtitle=@$DIR/$subtitle")
  echo "등록: $video"
  curl -fsS -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" \
    -H "Idempotency-Key: $(sha256sum "$video" | cut -d' ' -f1)" \
    "${form[@]}" "$BASE/clips"
  echo
done
