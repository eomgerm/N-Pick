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
RUN mkdir -p /var/cache/npick/models && chown -R npick:npick /var/cache/npick

USER npick
EXPOSE 8000

CMD ["npick-worker"]
