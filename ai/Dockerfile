FROM python:3.12-slim-bookworm AS build

# uv:python3.12-* 변형 태그는 uv 가 0.9 대로 뒤처져 있고, ai/pyproject.toml 이 >=0.12 를 요구한다.
# 그래서 베이스는 python 이미지로 두고 uv 바이너리만 버전 고정해 복사한다.
COPY --from=ghcr.io/astral-sh/uv:0.12.9 /uv /uvx /bin/

WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# 이 Dockerfile 이 이미지 **둘**을 낸다. 바뀌는 것은 이 인자 하나뿐이다 (S15P21A501-187):
#
#   ""                              EC2 CPU 워커 — ocr 만 맡는다. torch 를 받지 않는다
#   "--group embedding --group cpu" EC2 질의 리졸버 — 임베딩 런타임 + CPU torch
#
# GPU 노드(SSAFY GPU 서버·RunPod 파드)는 이 이미지를 쓰지 않는다. 소스에서 직접
# `uv sync --group gpu --group cu128|cu130` 한다 — CUDA torch 가 4.2GB 라 GPU 를 쓰지
# 않는 EC2 컨테이너 둘이 그것을 이고 갈 이유가 없다.
ARG UV_GROUPS=""

# 의존성 레이어를 소스와 분리해 캐시가 살도록 한다.
# --no-dev: 런타임에 ruff/mypy/pytest 불필요.
COPY pyproject.toml uv.lock .python-version ./
# shellcheck 대상이 아니다 — 인용하면 빈 문자열이 인자 하나로 들어가 uv 가 거부한다.
RUN uv sync --frozen --no-dev --no-install-project $UV_GROUPS

COPY src ./src
COPY README.md ./
RUN uv sync --frozen --no-dev $UV_GROUPS

FROM python:3.12-slim-bookworm
WORKDIR /app

# compose healthcheck 용
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# uid 는 backend/Dockerfile 과 같아야 한다. 두 컨테이너가 media 볼륨을 공유하는데
# BE 가 만드는 원본이 0600 이라, uid 가 다르면 워커가 원본을 열지 못한다
# (NPICK_WORKER_JOBS_SHARED_MEDIA_VOLUME=true 에서 PermissionError). S15P21A501-189.
RUN groupadd --gid 10001 npick && useradd --uid 10001 --gid npick --create-home npick

COPY --from=build --chown=npick:npick /app/.venv /app/.venv
COPY --from=build --chown=npick:npick /app/src /app/src

ENV PATH="/app/.venv/bin:$PATH"

# 모델 캐시 위치. HuggingFace·torch 계열이 기본으로 홈 디렉터리에 받는데,
# 볼륨으로 잡아야 컨테이너를 다시 만들어도 재다운로드하지 않는다.
ENV HF_HOME=/var/cache/npick/models
ENV TORCH_HOME=/var/cache/npick/models
# rapidocr(OCR 단계)는 기본으로 site-packages 안에 모델을 받는다. 그건 이미지 레이어라
# 컨테이너를 다시 만들 때마다 다시 받게 되므로 같은 캐시 볼륨으로 돌린다.
ENV NPICK_AI_OCR_MODEL_DIR=/var/cache/npick/models/rapidocr
# VLM 가중치도 같은 볼륨에 둔다. 이쪽은 수 GB 라 파드를 띄울 때마다 다시 받으면 배치마다
# 수십 분을 버린다(03-deployment.md: 모델 가중치는 네트워크 볼륨에 상주).
# HF_HOME 과 같은 곳을 가리키지만 명시한다 — 어느 변수가 이겼는지 나중에 알 수 있어야 한다.
ENV NPICK_AI_VLM_MODEL_DIR=/var/cache/npick/models/vlm
# ASR 가중치도 같은 이유로 볼륨에 둔다. faster-whisper 는 기본으로 HF 캐시에 받는데,
# 모델 크기가 정해지기 전이라 수백 MB~수 GB 사이다(docs/asr.md §2).
ENV NPICK_AI_ASR_MODEL_DIR=/var/cache/npick/models/asr
RUN mkdir -p /var/cache/npick/models && chown -R npick:npick /var/cache/npick

USER npick
EXPOSE 8000

CMD ["npick-worker"]
