#!/bin/sh
# RunPod 파드를 만든다. 재고가 없어 기존 파드를 못 켤 때 쓰는 복구 수단이다.
#
#   RUNPOD_API_KEY=... RUNPOD_VOLUME_ID=... infra/jenkins/runpod-create.sh [파드이름]
#
# 종료 코드 — 0 생성됨(파드 ID 를 마지막 줄에 출력) / 1 API 오류 / 2 환경 누락
#
# **정지된 파드는 특정 호스트에 묶여 있다.** 그 호스트의 GPU 가 차면 start 가
# "not enough free GPUs on the host machine" 으로 거절되고, 아무리 기다려도 그 호스트가
# 비지 않으면 안 난다. 그때는 같은 볼륨으로 파드를 새로 만들면 다른 호스트에 붙는다 —
# 볼륨에 가중치와 venv 가 그대로 있어서 재생성이 10 분 안에 끝난다 (2026-09-18 실측).
#
# **GPU 는 넓게 고르고 싼 것부터 쓴다.** 한 종류만 지정하면 그 종류의 재고에 운을 건다.
# `gpuTypePriority: custom` 은 아래 배열의 **순서를 그대로** 우선순위로 쓴다.
# 값은 2026-09-18 기준 Secure Cloud 시간당 가격이다. 가격이 바뀌면 순서를 다시 맞춘다.
#
#   A40            48GB  $0.49     ← 가장 쌈
#   RTX A6000      48GB  $0.53
#   L40            48GB  $0.82
#   RTX 6000 Ada   48GB  $0.84
#   RTX 5090       32GB  $0.99
#   L40S           48GB  $1.09
#   A100 PCIe      80GB  $1.59
#   A100 SXM       80GB  $1.59
#   RTX PRO 6000   96GB  $2.09
#   H100 PCIe      80GB  $2.89
#   H100 NVL       94GB  $3.19
#   H100 SXM       80GB  $3.49
#   H200          141GB  $4.59
#   B200          180GB  $6.79
#   B300          288GB  $7.89     ← 가장 비쌈. 그래도 없는 것보다 낫다
#
# **가격은 2026-09-18 기준 Secure Cloud 값이고, 그 표에 실린 것만 넣었다.** RTX 5000
# Ada·RTX PRO 4500/5000 Blackwell·H200 NVL 은 가격이 공개 표에 없어 순서를 정할 수
# 없으므로 뺐다. 가격표가 바뀌면 순서를 다시 맞춘다.
#
# **32GB 미만은 넣지 않는다.** VLM 9B 가 peak reserved 18.6GiB 이고
# (ai/docs/vlm-metadata.md §9.6, allocator 20GiB 상한·장면당 최대 5장 조건) 여기에
# ASR·NER·임베딩이 함께 상주해 약 22.4GiB 가 필요하다. 24GB 카드는 남는 여유가
# 1.6GB 뿐인데 그 넷 중 셋은 실측이 아니라 추정이고, keyframe 이 늘거나 해상도가
# 커지면 VLM 쪽 실측값도 올라간다. 싼 순이라 그런 카드가 **먼저** 잡히므로 아예 뺀다.
# 장면 하나만 커져도 OOM 이다.
set -eu

API_BASE="${RUNPOD_API_BASE:-https://rest.runpod.io/v1}"
NAME="${1:-npick-worker}"

fail2() { echo "$1" >&2; exit 2; }
[ -n "${RUNPOD_API_KEY:-}" ]   || fail2 "RUNPOD_API_KEY 가 없다"
[ -n "${RUNPOD_VOLUME_ID:-}" ] || fail2 "RUNPOD_VOLUME_ID 가 없다 (가중치가 든 네트워크 볼륨)"

# 볼륨은 데이터센터에 묶여 있다. 파드도 같은 곳이어야 붙는다.
DC="${RUNPOD_DATACENTER:-AP-JP-1}"
IMAGE="${RUNPOD_IMAGE:-runpod/base:1.3.1-cuda1300-ubuntu2404}"
DISK="${RUNPOD_CONTAINER_DISK_GB:-50}"
# 파드에 SSH 로 들어갈 공개키. 없으면 만들되 들어가지는 못한다.
PUBKEY="${RUNPOD_PUBLIC_KEY:-}"

BODY=$(cat <<JSON
{
  "name": "$NAME",
  "imageName": "$IMAGE",
  "gpuTypeIds": [
    "NVIDIA A40",
    "NVIDIA RTX A6000",
    "NVIDIA L40",
    "NVIDIA RTX 6000 Ada Generation",
    "NVIDIA GeForce RTX 5090",
    "NVIDIA L40S",
    "NVIDIA A100 80GB PCIe",
    "NVIDIA A100-SXM4-80GB",
    "NVIDIA RTX PRO 6000 Blackwell Server Edition",
    "NVIDIA RTX PRO 6000 Blackwell Workstation Edition",
    "NVIDIA H100 PCIe",
    "NVIDIA H100 NVL",
    "NVIDIA H100 80GB HBM3",
    "NVIDIA H200",
    "NVIDIA B200",
    "NVIDIA B300 SXM6 AC"
  ],
  "gpuTypePriority": "custom",
  "gpuCount": 1,
  "cloudType": "SECURE",
  "dataCenterIds": ["$DC"],
  "dataCenterPriority": "custom",
  "networkVolumeId": "$RUNPOD_VOLUME_ID",
  "volumeMountPath": "/workspace",
  "containerDiskInGb": $DISK,
  "supportPublicIp": true,
  "ports": ["22/tcp"],
  "env": { "PUBLIC_KEY": "$PUBKEY" }
}
JSON
)

echo "파드를 만든다 — $DC · 볼륨 $RUNPOD_VOLUME_ID · GPU 는 싼 순으로 16 종"
RESP=$(curl -sS -X POST "$API_BASE/pods" \
  -H "Authorization: Bearer $RUNPOD_API_KEY" \
  -H 'Content-Type: application/json' \
  -d "$BODY" -w '\n%{http_code}' 2>&1) || { echo "RunPod API 에 닿지 못했다" >&2; exit 1; }
CODE=$(printf '%s' "$RESP" | tail -n 1)
RESP=$(printf '%s' "$RESP" | sed '$d')

case "$CODE" in
  2*) ;;
  *)  echo "파드를 만들지 못했다 (HTTP $CODE): $RESP" >&2
      echo "목록의 GPU 가 전부 동나면 이렇게 된다. 잠시 뒤 다시 하거나 SSAFY GPU 로 돌린다" >&2
      exit 1 ;;
esac

# **grep -o 로 첫 일치를 집는다.** sed 의 .* 는 탐욕적이라 한 줄 JSON 에서 **마지막**
# "id" 를 집고, 그러면 파드가 아니라 중첩 객체의 id 가 나온다 — 실제로 볼륨 ID 가
# 나왔다. 같은 함정을 runpod.sh 의 상태 파싱에서도 한 번 겪었다.
first_json_value() { # $1=키
  printf "%s" "$RESP" |
    grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"?[A-Za-z0-9._-]+\"?" |
    head -n 1 |
    sed -e "s/^\"$1\"[[:space:]]*:[[:space:]]*//" -e 's/^"//' -e 's/"$//'
}
POD_ID=$(first_json_value id)
COST=$(first_json_value costPerHr)
echo "생성됨 — 시간당 \$${COST:-?}"
echo "**Jenkins credential runpod-pod-id 를 이 값으로 갱신한다.**"
printf '%s\n' "$POD_ID"
