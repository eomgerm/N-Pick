"""대표 질의 20개를 실제 모델로 돌려 눈으로 확인하는 도구.

    NPICK_AI_RESOLVER_BACKEND=gms uv run --directory ai \
        python -m npick_worker.query_resolver.report

어느 backend 로 나가는지는 `NPICK_AI_RESOLVER_BACKEND` 가 정한다(`ollama` | `gms`).

운영 경로가 아니다. 티켓 완료 조건("대표 질의 20개에서 schema 유효 출력 + 창작 anchor
없음 육안 확인")을 사람이 확인하기 위한 개발자 도구다.

**자동 채점을 하지 않는다.** 정답지가 없기 때문이다. 기계가 볼 수 있는 것(schema 유효,
강등·삭제 발생)만 표시하고 나머지 판단은 사람에게 남긴다.
"""

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from npick_worker.query_resolver import (
    ResolutionResult,
    ResolverCallError,
    ResolverSchemaInvalidError,
    get_default_config,
    resolve_query,
)
from npick_worker.query_resolver.config import CallParams
from npick_worker.query_resolver.gms_backend import GmsResolver
from npick_worker.query_resolver.ollama_backend import OllamaResolver
from npick_worker.query_resolver.resolver import QueryResolver
from npick_worker.settings import Settings, get_settings

FIXTURE_PATH = Path(__file__).resolve().parent / "fixtures" / "representative_queries.json"


def load_queries() -> list[dict[str, Any]]:
    data = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    queries: list[dict[str, Any]] = data["queries"]
    return queries


def _anchor_summary(result: ResolutionResult) -> str:
    r = result.resolution
    parts = []
    for window in r.date_windows:
        parts.append(f"{window.field}={window.start}~{window.end_exclusive}({window.origin[:3]})")
    parts += [f"event:{i.value}" for i in r.incident_names]
    parts += [f"{e.type[:3]}:{e.value}" for e in r.entities]
    parts += [f"{loc.type[:3]}:{loc.value}" for loc in r.locations]
    parts += [f"{c.type}:{c.value}" for c in r.classifications]
    return " | ".join(parts) if parts else "-"


def render(rows: list[tuple[dict[str, Any], ResolutionResult | str]]) -> str:
    lines: list[str] = []
    for spec, outcome in rows:
        lines.append(f"[{spec['id']:>2}] {spec['query']}")
        if isinstance(outcome, str):
            lines.append(f"     실패: {outcome}")
            lines.append("")
            continue
        lines.append(f"     intent   {outcome.resolution.intent}")
        lines.append(f"     anchors  {_anchor_summary(outcome)}")
        lines.append(f"     확장어    {', '.join(outcome.resolution.expanded_terms) or '-'}")
        if outcome.findings:
            for finding in outcome.findings:
                lines.append(f"     ⚠ {finding.path} {finding.action}: {finding.reason}")
        lines.append(f"     볼 것    {spec['checks']}")
        lines.append("")
    return "\n".join(lines)


def _build_resolver(settings: Settings, params: CallParams) -> QueryResolver:
    """설정이 고른 backend 를 만든다. 여기가 유일한 backend 선택 지점이다.

    패키지 안에서 고르지 않는 이유: `query_resolver/` 는 환경 변수를 모른다.
    호출부가 구현을 주입하는 구조를 깨면 adapter 경계가 흐려진다.
    """
    if settings.resolver_backend == "gms":
        return GmsResolver(
            settings.gms_base_url,
            settings.gms_api_key.get_secret_value(),
            settings.gms_model,
            params,
            json_mode=settings.gms_json_mode,
        )
    return OllamaResolver(settings.ollama_url, settings.ollama_model, params)


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="대표 질의 20개 해석 결과를 출력한다")
    parser.add_argument("--out", type=Path, default=None, help="결과 JSON 저장 경로")
    parser.add_argument("--only", type=int, default=None, help="질의 id 하나만 실행")
    return parser.parse_args()


def main() -> None:
    args = _parse_args()
    settings = get_settings()
    config = get_default_config()
    try:
        resolver = _build_resolver(settings, config.call)
    except ValueError as exc:
        # 설정 실수는 스택트레이스가 아니라 무엇을 해야 하는지로 알려준다.
        print(f"{exc}\n\n현재 backend: {settings.resolver_backend}", file=sys.stderr)
        sys.exit(2)

    specs = load_queries()
    if args.only is not None:
        specs = [s for s in specs if s["id"] == args.only]

    rows: list[tuple[dict[str, Any], ResolutionResult | str]] = []
    failures = 0
    for spec in specs:
        try:
            rows.append((spec, resolve_query(spec["query"], resolver, config)))
        except (ResolverCallError, ResolverSchemaInvalidError) as exc:
            failures += 1
            rows.append((spec, f"{type(exc).__name__}: {exc}"))

    print(render(rows))
    demoted = sum(
        len(outcome.findings) for _, outcome in rows if isinstance(outcome, ResolutionResult)
    )
    print(f"질의 {len(rows)}개 | 실패 {failures} | 검증 조치 {demoted}건")
    print(f"prompt {config.prompt_version} | {resolver.name} {resolver.version}")

    if args.out is not None:
        payload = [
            {
                "id": spec["id"],
                "query": spec["query"],
                "result": (
                    {
                        "resolution": outcome.resolution.model_dump(mode="json"),
                        "findings": [asdict(f) for f in outcome.findings],
                    }
                    if isinstance(outcome, ResolutionResult)
                    else {"error": outcome}
                ),
            }
            for spec, outcome in rows
        ]
        args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n{args.out}")

    if failures:
        sys.exit(1)


if __name__ == "__main__":
    main()
