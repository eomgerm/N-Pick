"""고유명사 확장어 오염 측정 (S15P21A501-293).

`resolver_bench.py` 가 재지 못하는 것을 잰다. 그쪽 지표는 `expanded_terms` 를 점수에서
제외하는데(`metrics.py` 의 `_TYPED_ARRAYS` 주석: 확장어를 많이 내는 모델이 근거 없이
이긴다), 이 티켓이 고친 것이 바로 그 확장어다.

**오염** = 확장어가 원 고유명사의 상위 범주인 것. 확장어는 캡션·대사 BM25 에 가중치
0.3 으로 걸리므로(`SEARCH_CANDIDATE_EXPANDED_WEIGHT`), 「둘리」의 확장어 「캐릭터」는
둘리가 없는 캐릭터 장면 전부를 부른다. 색인에 찾던 것이 없으면 0 건이 나와야 할 자리가
무관한 결과로 채워진다.

대조군을 함께 잰다. 상위 범주만 막아야지 **정당한 동의어 확장까지 죽으면** 이 변경은
오탐 하나를 고치고 재현율을 잃는 것이 된다.

    cd ai
    export NPICK_AI_GMS_BASE_URL=... NPICK_AI_GMS_API_KEY=...
    .venv/Scripts/python eval/query_resolver/proper_noun_expansion.py \
        --config src/npick_worker/config/query_resolver.v2.toml \
        --config src/npick_worker/config/query_resolver.v3.toml \
        --out eval/query_resolver/results/proper-noun-expansion-S15P21A501-293.json

질의는 전부 합성이고 실제 자막·영상을 담지 않아 저장소에 커밋한다.
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "src"))

from npick_worker.query_resolver import load_config, resolve_query
from npick_worker.query_resolver.gms_backend import GmsResolver
from npick_worker.settings import get_settings

#: (질의, 이 질의에서 「상위 범주」로 판정할 말). 고유명사 하나가 중심이라 어느 anchor
#: 배열에도 배정되지 않고 확장어로 흘러가는 유형이다.
PROPER_NOUNS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("둘리", ("캐릭터", "만화", "애니메이션", "만화영화", "애니")),
    ("뽀로로", ("캐릭터", "만화", "애니메이션", "만화영화", "애니")),
    ("카카오", ("기업", "회사", "플랫폼", "it", "포털")),
    ("네이버", ("기업", "회사", "플랫폼", "it", "포털")),
    ("무한도전", ("예능", "프로그램", "방송", "tv")),
    ("소녀시대", ("그룹", "아이돌", "가수", "걸그룹")),
    ("갤럭시", ("스마트폰", "휴대폰", "전자제품", "기기")),
    ("나이키", ("브랜드", "기업", "회사", "스포츠용품")),
    ("경부선", ("철도", "노선", "기차", "교통")),
    ("호남선", ("철도", "노선", "기차", "교통")),
    ("한라산", ("산", "자연", "관광지")),
    ("첨성대", ("문화재", "유적", "건축물", "관광지")),
)

#: 대조군. 확장어가 **나와야 하는** 질의다. 고유명사가 중심이 아니다.
CONTROL: tuple[str, ...] = (
    "귀성객 몰린 기차역",
    "폭우로 침수된 도로",
    "벚꽃 구경하는 사람들",
    "출근길 지하철 혼잡",
    "산불 진화 장면",
    "코로나 선별진료소",
    "국회 본회의장 모습",
    "태풍 피해 복구 현장",
)


def measure(config_path: Path) -> dict:
    """프롬프트 하나로 두 집합을 돌린다."""
    settings = get_settings()
    config = load_config(config_path)
    backend = GmsResolver(
        base_url=settings.gms_base_url,
        api_key=settings.gms_api_key.get_secret_value(),
        model=settings.gms_model,
        params=config.call,
        json_mode=settings.gms_json_mode,
    )

    polluted, proper_detail = 0, []
    for query, categories in PROPER_NOUNS:
        terms = list(resolve_query(query, backend, config).resolution.expanded_terms)
        # 부분 문자열로 본다. 「TV 프로그램」처럼 범주어를 품은 구가 나오기 때문이다.
        hits = [term for term in terms if any(c in term.lower() for c in categories)]
        polluted += bool(hits)
        proper_detail.append({"query": query, "expanded_terms": terms, "상위범주": hits})

    empty, total_terms, control_detail = 0, 0, []
    for query in CONTROL:
        terms = list(resolve_query(query, backend, config).resolution.expanded_terms)
        empty += not terms
        total_terms += len(terms)
        control_detail.append({"query": query, "expanded_terms": terms})

    return {
        "config": config_path.name,
        "prompt_version": config.prompt_version,
        "model": settings.gms_model,
        "고유명사": {"오염": polluted, "전체": len(PROPER_NOUNS), "detail": proper_detail},
        "대조군": {
            "확장어_없음": empty,
            "전체": len(CONTROL),
            "확장어_총수": total_terms,
            "detail": control_detail,
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", action="append", required=True, type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    rows = [measure(path) for path in args.config]
    for row in rows:
        proper, control = row["고유명사"], row["대조군"]
        print(
            f"{row['config']:26} {row['prompt_version']}\n"
            f"  고유명사 상위범주 확장 {proper['오염']}/{proper['전체']}"
            f"   대조군 확장어 없음 {control['확장어_없음']}/{control['전체']}"
            f" (확장어 총 {control['확장어_총수']}개)"
        )
    if args.out:
        args.out.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n→ {args.out}")


if __name__ == "__main__":
    main()
