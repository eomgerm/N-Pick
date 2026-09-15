FROM python:3.12-slim-bookworm AS build

# uv:python3.12-* 변형 태그는 uv 가 0.9 대로 뒤처져 있고, ai/pyproject.toml 이 >=0.12 를 요구한다.
# 그래서 베이스는 python 이미지로 두고 uv 바이너리만 버전 고정해 복사한다.
COPY --from=ghcr.io/astral-sh/uv:0.12.9 /uv /uvx /bin/

WORKDIR /app

ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy

# 의존성 레이어를 소스와 분리해 캐시가 살도록 한다.
# --no-dev: 런타임에 ruff/mypy/pytest 불필요. gpu 그룹은 opt-in 이라 애초에 안 받는다.
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
COPY README.md ./
RUN uv sync --frozen --no-dev

FROM python:3.12-slim-bookworm
WORKDIR /app

# compose healthcheck 용
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10002 npick && useradd --uid 10002 --gid npick --create-home npick

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
