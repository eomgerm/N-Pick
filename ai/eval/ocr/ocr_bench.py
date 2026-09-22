"""OCR 화면 글자 품질 측정·파라미터 스윕 (S15P21A501-260).

    uv run --directory ai python eval/ocr/ocr_bench.py --variant all

QA 가 보고한 오독(`꿀꺽` → `꿀찍`)을 계기로 만든 하네스다. 하는 일은 셋이다.

1. 라벨된 keyframe 을 실제 엔진으로 읽어 문구마다 두 축으로 가른다 — 글자가 맞았는가
   (`exact`/`misread`/`miss`)와 **그 문구로 검색이 닿는가**(`reachable`/`lost`/
   `unsearchable`). 둘이 왜 다른지는 `ocr_metrics.py` 의 모듈 docstring 에 있다.
2. `config/ocr.v1.toml` 의 값을 덮어 쓴 변형들을 같은 입력에 돌려 비교한다. 설정
   파일을 늘리지 않는다 — 실측으로 이길 때만 정본을 고친다(FRD §11).
3. QA 가 보고한 개별 오독 사례를 `cases.json` 으로 등록해 두고 회귀를 본다.

**워커 런타임이 아니다.** 배포 이미지에 들어가지 않는다. 다만 `npick_worker.ocr` 를
**import 한다** — 실제 설정·실제 backend·실제 후처리를 거치지 않으면 측정이
무의미하기 때문이다(`eval/query_resolver/` 와 같은 판단).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Final

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ocr_metrics import (
    DEFAULT_MISREAD_FLOOR,
    FrameResult,
    GoldFrame,
    Observation,
    Summary,
    evaluate,
    load_gold,
    load_observations,
    similarity,
)

from npick_worker.ocr import KeyframeRef, read_keyframes
from npick_worker.ocr.config import DEFAULT_CONFIG_PATH, OcrConfig, load_config
from npick_worker.ocr.report import discover_keyframes, to_json

#: 이 저장소에 라벨이 있는 유일한 클립. `samples/README.md` 의 1번 클립이 들어오면
#: 늘어난다.
DEFAULT_FRAMES: Final[Path] = Path("samples/out/KNI_02205-frames")
DEFAULT_GOLD: Final[Path] = Path("samples/ocr-ground-truth.KNI_02205.json")
DEFAULT_OUT: Final[Path] = Path("eval/ocr/results")
DEFAULT_CASES: Final[Path] = Path("eval/ocr/cases.json")
SUMMARY_SCHEMA: Final[str] = "ocr-eval-summary/v1"
SUMMARY_FILENAME: Final[str] = "summary.json"


@dataclass(frozen=True, slots=True)
class Variant:
    """비교할 설정 하나.

    `overrides` 는 `config/ocr.v1.toml` 의 키를 덮는다. `upscale` 은 설정 키가
    아니라 **전처리 축**이다 — 엔진에 넣기 전에 이미지를 키워 본다. 인식은 원본에서
    잘라낸 조각으로 하므로(`docs/ocr.md` §8) 이 축만 인식 입력의 픽셀 수를 바꾼다.
    """

    name: str
    why: str
    overrides: Mapping[str, Any] = field(default_factory=dict)
    upscale: float = 1.0

    def config(self, base: Path = DEFAULT_CONFIG_PATH) -> OcrConfig:
        loaded = load_config(base)
        return loaded.model_copy(update=dict(self.overrides)) if self.overrides else loaded


#: 스윕 대상. **여기 있는 것이 문서의 표를 만든 것 전부다** — 문서에 수치가 있는데
#: 여기 없는 변형이 있으면 그 수치는 재현할 수 없다.
VARIANTS: Final[tuple[Variant, ...]] = (
    Variant("base", "동봉 기본 설정. 비교의 기준"),
    Variant(
        "unclip-1.8", "상자를 더 부풀린다. 끝 글자가 잘리는 오독을 본다", {"det_unclip_ratio": 1.8}
    ),
    Variant("unclip-2.0", "같은 축을 더", {"det_unclip_ratio": 2.0}),
    Variant("unclip-2.5", "과하게 부풀렸을 때 이웃 문구가 붙는지", {"det_unclip_ratio": 2.5}),
    Variant("box-0.5", "흐린 글자를 더 잡는다. 오탐이 함께 느는 축", {"det_box_thresh": 0.5}),
    Variant("thresh-0.2", "확률 맵 이진화를 낮춘다. 획이 얇은 글자", {"det_thresh": 0.2}),
    Variant("det-server", "검출만 server 판. 인식 모델은 그대로다", {"det_model_type": "server"}),
    Variant(
        "det-min-1280",
        "검출 입력을 키운다(`min`). 작은 글자를 찾아내는가만 바뀐다",
        {"det_limit_type": "min", "det_limit_side_len": 1280},
    ),
    Variant(
        "ocr-v4",
        "인식 모델 세대를 내린다. v5 한국어 모델의 성질을 보기 위한 대조군",
        {"ocr_version": "PP-OCRv4"},
    ),
    Variant("upscale-2x", "**전처리 축.** 인식에 들어가는 픽셀을 두 배로", upscale=2.0),
    Variant("upscale-2x-unclip-2.0", "두 축을 함께", {"det_unclip_ratio": 2.0}, upscale=2.0),
    # ── 검출 입력 크기 주변. 1차 스윕에서 이 축만 크게 움직였다 ────────────
    Variant("det-min-1024", "같은 축을 덜", {"det_limit_type": "min", "det_limit_side_len": 1024}),
    Variant("det-min-1600", "같은 축을 더", {"det_limit_type": "min", "det_limit_side_len": 1600}),
    Variant(
        "det-min-1280-box-0.7",
        "1280 이 데려온 한 글자짜리 잡음을 상자 점수로 거른다",
        {"det_limit_type": "min", "det_limit_side_len": 1280, "det_box_thresh": 0.7},
    ),
    Variant(
        "det-min-1280-box-0.8",
        "같은 축을 더",
        {"det_limit_type": "min", "det_limit_side_len": 1280, "det_box_thresh": 0.8},
    ),
    Variant(
        "det-min-1280-unclip-2.0",
        "찾은 상자를 더 부풀린다",
        {"det_limit_type": "min", "det_limit_side_len": 1280, "det_unclip_ratio": 2.0},
    ),
    Variant("box-0.8", "대조군. 상자 점수만 올리면 어떻게 되는가", {"det_box_thresh": 0.8}),
    Variant(
        "det-min-1280-box-0.75",
        "0.7 과 0.8 사이",
        {"det_limit_type": "min", "det_limit_side_len": 1280, "det_box_thresh": 0.75},
    ),
    Variant(
        "det-min-1152-box-0.8",
        "입력 크기를 조금 줄여 시간을 되찾을 수 있는지",
        {"det_limit_type": "min", "det_limit_side_len": 1152, "det_box_thresh": 0.8},
    ),
    Variant(
        "det-server-min-1280",
        "오탐 0 인 server 검출에 같은 입력 크기를 준다",
        {"det_model_type": "server", "det_limit_type": "min", "det_limit_side_len": 1280},
    ),
)

VARIANTS_BY_NAME: Final[Mapping[str, Variant]] = {variant.name: variant for variant in VARIANTS}

#: 전체 스윕에서 문구별 실패까지 보존할 변형. 나머지는 `summary.json` 의 비교 행만
#: 남긴다. 기준·속도/오염 절충안·정확일치 최댓값 후보라 의사결정을 다시 검토할 때
#: 필요한 세 점이다. 단일 `--variant` 실행은 이 목록과 무관하게 해당 상세본을 쓴다.
DETAIL_VARIANT_NAMES: Final[tuple[str, ...]] = (
    "base",
    "det-min-1152-box-0.8",
    "det-min-1280-box-0.8",
)

#: `summary.json` 에 남기는 공통 재현 정보. 문구별 `lines`·토큰 목록·오탐·floor sweep은
#: 상세본에만 둔다.
SUMMARY_FIELDS: Final[tuple[str, ...]] = (
    "variant",
    "why",
    "overrides",
    "upscale",
    "configVersion",
    "engine",
    "engineVersion",
    "minConfidence",
    "misreadFloor",
    "frames",
    "elapsedSeconds",
    "msPerFrame",
    "summary",
)


# ── 전처리 축 ─────────────────────────────────────────────────────────


def upscale_frames(paths: Mapping[str, Path], factor: float, into: Path) -> dict[str, Path]:
    """keyframe 을 키워 임시 디렉터리에 둔다.

    **원본을 덮어쓰지 않는다.** `frame_extraction` 이 저장한 JPEG 은 `keyframe` 행이
    가리키는 이미지이고, 그걸 바꾸면 "읽은 이미지와 저장된 이미지가 같다" 가 깨진다
    (`docs/ocr.md` §8). 여기서 만드는 것은 측정용 사본이다.

    bbox 는 이 배율만큼 커진 좌표로 나온다. 이 하네스는 원문만 보므로 되돌리지
    않는다 — **그래서 이 축을 런타임에 들이려면 좌표 복원이 함께 와야 한다.**
    """
    import cv2  # rapidocr 가 끌어오는 것과 같은 것. 지연 import 로 비용을 미룬다.

    into.mkdir(parents=True, exist_ok=True)
    scaled: dict[str, Path] = {}
    for key, source in paths.items():
        image = cv2.imread(str(source))
        if image is None:
            msg = f"이미지를 읽지 못했다: {source}"
            raise RuntimeError(msg)
        height, width = image.shape[:2]
        # INTER_CUBIC: 확대에서 INTER_LINEAR 보다 획의 경계가 덜 뭉갠다. LANCZOS4 는
        # 링잉이 생겨 얇은 획에 불리했다(이 표본 실측).
        bigger = cv2.resize(
            image,
            (round(width * factor), round(height * factor)),
            interpolation=cv2.INTER_CUBIC,
        )
        target = into / key.replace("/", "__")
        cv2.imwrite(str(target), bigger, [cv2.IMWRITE_JPEG_QUALITY, 100])
        scaled[key] = target
    return scaled


# ── 실행 ──────────────────────────────────────────────────────────────


def run_variant(
    variant: Variant,
    frames_dir: Path,
    gold: Sequence[GoldFrame],
    *,
    misread_floor: float,
    base_config: Path = DEFAULT_CONFIG_PATH,
) -> tuple[dict[str, Any], tuple[FrameResult, ...], Summary, dict[str, tuple[Observation, ...]]]:
    """변형 하나를 돌리고 판정까지 마친다."""
    keyframes, paths = discover_keyframes(frames_dir)
    if not keyframes:
        msg = f"keyframe 을 찾지 못했다: {frames_dir}"
        raise RuntimeError(msg)

    config = variant.config(base_config)
    # **엔진을 변형마다 새로 만든다.** `shared_engine` 은 maxsize=1 이라 스윕에서는
    # 매번 버려지고 다시 만들어진다 — 캐시를 쓰는 것처럼 보이면서 안 쓰는 상태가
    # 된다. 여기서는 명시적으로 만든다.
    from npick_worker.ocr.rapidocr_backend import RapidOcrEngine

    engine = RapidOcrEngine(config)

    temp_dir: Path | None = None
    try:
        if variant.upscale != 1.0:
            temp_dir = Path(tempfile.mkdtemp(prefix="npick-ocr-eval-"))
            paths = upscale_frames(paths, variant.upscale, temp_dir)
        started = time.monotonic()
        result = read_keyframes(keyframes, paths, engine=engine, config=config)
        elapsed = time.monotonic() - started
    finally:
        if temp_dir is not None:
            shutil.rmtree(temp_dir, ignore_errors=True)

    payload = to_json(result)
    observations = load_observations(payload)
    frame_results, summary = evaluate(gold, observations, misread_floor=misread_floor)

    record: dict[str, Any] = {
        "variant": variant.name,
        "why": variant.why,
        "overrides": dict(variant.overrides),
        "upscale": variant.upscale,
        "configVersion": config.version_id,
        "engine": result.engine,
        "engineVersion": result.engine_version,
        "minConfidence": config.min_confidence,
        "misreadFloor": misread_floor,
        "frames": len(keyframes),
        "elapsedSeconds": round(elapsed, 2),
        "msPerFrame": round(elapsed / len(keyframes) * 1000),
        "summary": summary.to_json(),
        "lines": [
            {
                "frame": line.line.frame,
                "label": line.line.text,
                "outcome": line.outcome,
                "observed": line.observed.raw_text if line.observed else None,
                "confidence": line.observed.confidence if line.observed else None,
                "unverified": line.observed.unverified if line.observed else None,
                "similarity": round(line.similarity, 3),
                "cer": round(line.cer, 3),
                "reach": line.reach,
                "goldTokens": list(line.gold_tokens),
                "observedTokens": list(line.observed_tokens),
            }
            for frame in frame_results
            for line in frame.lines
            if line.line.scored and line.outcome != "exact"
        ],
        "strayTokens": sorted({token for frame in frame_results for token in frame.stray_tokens}),
        "hallucinations": [
            {"frame": obs.frame, "observed": obs.raw_text, "confidence": obs.confidence}
            for frame in frame_results
            for obs in frame.hallucinations
        ],
    }
    return record, frame_results, summary, observations


# ── 회귀 사례 ─────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class Case:
    """QA 가 보고한 오독 하나.

    라벨 파일(`ocr-ground-truth.*.json`)이 이미 덮는 문구는 여기 적지 않는다 —
    같은 것을 두 곳에 적으면 한쪽만 고쳐진다. 이 파일은 **라벨된 클립 밖에서 나온
    사례**를 담는다.
    """

    id: str
    reported_by: str
    expected: str
    observed: str
    note: str
    #: `expected` 를 누가 적었는가. 사람이 아니면 반드시 적힌다 — AI 예비 라벨을
    #: 사람 검수 Gold Set 처럼 쓰지 않기 위해서다(`samples/README.md` 의 같은 구분).
    labeled_by: str
    #: 그 화면의 keyframe. 없으면 아직 재현할 수 없는 사례다.
    frame: Path | None


def load_cases(path: Path) -> tuple[Case, ...]:
    raw: Any = json.loads(path.read_text(encoding="utf-8"))
    cases: list[Case] = []
    seen: set[str] = set()
    for entry in raw["cases"]:
        case_id = str(entry["id"])
        if case_id in seen:
            msg = f"같은 사례 id 가 두 번 있다: {case_id}"
            raise ValueError(msg)
        seen.add(case_id)
        frame = entry.get("frame")
        cases.append(
            Case(
                id=case_id,
                reported_by=str(entry["reportedBy"]),
                expected=str(entry["expected"]),
                observed=str(entry["observed"]),
                note=str(entry.get("note", "")),
                labeled_by=str(entry.get("labeledBy", "")),
                frame=Path(frame) if frame else None,
            )
        )
    return tuple(cases)


def case_status(case: Case, observations: Sequence[Observation]) -> str:
    """한 사례의 지금 상태.

    - `fixed` — 그 화면을 이제 정확히 읽는다.
    - `reproduced` — 보고된 오독이 그대로 나온다.
    - `changed` — 여전히 틀리지만 보고와 다르게 틀린다. 고쳐진 것이 아니다.
    - `absent` — 그 문구에 대응하는 관측이 없다. 오독이 아니라 누락으로 바뀌었다.
    """
    best = max(observations, key=lambda obs: similarity(case.expected, obs.raw_text), default=None)
    if best is None or similarity(case.expected, best.raw_text) < DEFAULT_MISREAD_FLOOR:
        return "absent"
    if similarity(case.expected, best.raw_text) >= 1.0 - 1e-9:
        return "fixed"
    return "reproduced" if similarity(case.observed, best.raw_text) >= 1.0 - 1e-9 else "changed"


def frame_key(frame: Path) -> str:
    """사례의 프레임을 관측 맵에서 찾을 때 쓰는 키.

    `load_observations` 가 `storageKey` 에서 `.jpg` 를 떼므로 같은 모양으로 맞춘다.
    """
    return frame.as_posix().removesuffix(".jpg")


def read_case_frames(
    cases: Sequence[Case], variant: Variant, *, base_config: Path = DEFAULT_CONFIG_PATH
) -> dict[str, tuple[Observation, ...]]:
    """사례가 가리키는 keyframe 만 읽는다.

    라벨된 클립이 없어도 도는 경로다 — 등록부의 프레임은 어느 클립에서 왔든 상관없다.
    `read_keyframes` 를 그대로 거치므로 `min_confidence` 에 따른 `unverified` 표시도
    운영과 같은 규칙으로 붙는다.

    **`Variant` 를 통째로 받는다.** 설정만 받으면 `upscale` 축이 빠지고, 그러면 그
    변형의 이름을 달고 **다른 설정을 잰 값**이 표에 들어간다.
    """
    from npick_worker.ocr.rapidocr_backend import RapidOcrEngine

    config = variant.config(base_config)
    refs: list[KeyframeRef] = []
    paths: dict[str, Path] = {}
    for index, case in enumerate(cases):
        if case.frame is None:
            continue
        if not case.frame.is_file():
            msg = f"사례 {case.id} 의 프레임이 없다: {case.frame}"
            raise FileNotFoundError(msg)
        key = case.frame.as_posix()
        if key in paths:
            continue  # 한 프레임에 사례가 여럿일 수 있다. 두 번 읽지 않는다
        # scene/timestamp 는 여기서 의미가 없다. 관측을 프레임별로 가르는 데만 쓴다.
        refs.append(KeyframeRef(scene_index=0, timestamp_ms=index, storage_key=key))
        paths[key] = case.frame
    if not refs:
        return {}

    temp_dir: Path | None = None
    try:
        if variant.upscale != 1.0:
            temp_dir = Path(tempfile.mkdtemp(prefix="npick-ocr-case-"))
            paths = upscale_frames(paths, variant.upscale, temp_dir)
        result = read_keyframes(refs, paths, engine=RapidOcrEngine(config), config=config)
    finally:
        if temp_dir is not None:
            shutil.rmtree(temp_dir, ignore_errors=True)
    return load_observations(to_json(result))


def check_cases(
    cases: Sequence[Case], observations: Mapping[str, Sequence[Observation]]
) -> list[dict[str, str]]:
    """등록된 사례의 상태.

    `not-in-run` 이 있는 이유: 이 측정이 읽은 프레임에 그 사례의 화면이 없다는 뜻이다.
    **`absent` 와 다르다** — `absent` 는 읽었는데 그 문구가 안 나온 것이고, 이쪽은
    아예 보지 않은 것이다. 둘을 같은 값으로 두면 라벨된 클립만 돌린 측정이 다른
    클립의 사례를 "사라졌다" 로 보고한다.
    """
    statuses: list[dict[str, str]] = []
    for case in cases:
        if case.frame is None:
            statuses.append({"id": case.id, "status": "frame-missing"})
            continue
        key = frame_key(case.frame)
        if key not in observations:
            statuses.append({"id": case.id, "status": "not-in-run"})
            continue
        statuses.append({"id": case.id, "status": case_status(case, observations[key])})
    return statuses


# ── 출력 ──────────────────────────────────────────────────────────────


def render_table(records: Sequence[Mapping[str, Any]]) -> str:
    header = (
        f"{'변형':<22} {'exact':>7} {'검색도달':>9} {'못찾음':>7} {'오독':>5} {'누락':>5} "
        f"{'오염토큰':>9} {'오탐':>5} {'CER':>6} {'장당':>7}"
    )
    rows = [header, "-" * len(header)]
    for record in records:
        summary = record["summary"]
        rows.append(
            f"{record['variant']:<22} "
            f"{summary['exact']:>3}/{summary['legible']:<3} "
            f"{summary['reachable']:>5}/{summary['searchable']:<3} "
            f"{summary['lost']:>7} "
            f"{summary['misread']:>5} {summary['miss']:>5} "
            f"{summary['strayTokens']:>9} {summary['hallucination']:>5} "
            f"{summary['cer']:>6.3f} {record['msPerFrame']:>6}ms"
        )
    return "\n".join(rows)


def compact_summary(records: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    """전체 변형의 비교·재현 필드만 한 파일로 모은다."""
    return {
        "schema": SUMMARY_SCHEMA,
        "detailVariants": list(DETAIL_VARIANT_NAMES),
        "variants": [{field: record[field] for field in SUMMARY_FIELDS} for record in records],
    }


def write_results(records: Sequence[Mapping[str, Any]], out: Path, *, all_variants: bool) -> None:
    """측정 결과를 쓴다.

    전체 스윕은 비교 행 20개를 `summary.json` 하나에 모으고 세 변형만 상세본으로
    보존한다. 예전 전체 스윕이 남긴 나머지 변형 상세본은 이 하네스가 만든 알려진
    파일에 한해 지운다. 단일 변형 실행은 기존처럼 그 변형의 상세본만 갱신한다.
    """
    out.mkdir(parents=True, exist_ok=True)
    if all_variants:
        (out / SUMMARY_FILENAME).write_text(
            json.dumps(compact_summary(records), ensure_ascii=False, indent=1),
            encoding="utf-8",
        )
        details = frozenset(DETAIL_VARIANT_NAMES)
        for name in VARIANTS_BY_NAME:
            if name not in details:
                (out / f"{name}.json").unlink(missing_ok=True)
    else:
        details = frozenset(str(record["variant"]) for record in records)

    for record in records:
        name = str(record["variant"])
        if name in details:
            (out / f"{name}.json").write_text(
                json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8"
            )


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--variant",
        default="base",
        help=f"{', '.join(VARIANTS_BY_NAME)} 또는 all",
    )
    parser.add_argument("--frames", type=Path, default=DEFAULT_FRAMES)
    parser.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    parser.add_argument(
        "--config", type=Path, default=DEFAULT_CONFIG_PATH, help="덮어 쓸 바탕 설정"
    )
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--misread-floor", type=float, default=DEFAULT_MISREAD_FLOOR)
    parser.add_argument(
        "--cases-only",
        action="store_true",
        help="라벨된 클립 대신 cases.json 의 프레임만 읽어 회귀 사례 상태를 본다",
    )
    parser.add_argument(
        "--floor-sweep",
        action="store_true",
        help="misread_floor 를 훑어 판정이 그 값에 얼마나 민감한지 본다",
    )
    return parser.parse_args()


def main() -> int:
    args = _parse_args()

    names = list(VARIANTS_BY_NAME) if args.variant == "all" else [args.variant]
    unknown = [name for name in names if name not in VARIANTS_BY_NAME]
    if unknown:
        print(f"모르는 변형: {', '.join(unknown)}", file=sys.stderr)
        return 1

    if args.cases_only:
        return _run_cases_only(names, args.cases, args.config)

    gold = load_gold(args.gold)
    records: list[dict[str, Any]] = []
    for name in names:
        variant = VARIANTS_BY_NAME[name]
        print(f"[{name}] {variant.why}", flush=True)
        record, _, _, observations = run_variant(
            variant,
            args.frames,
            gold,
            misread_floor=args.misread_floor,
            base_config=args.config,
        )
        if args.floor_sweep:
            record["floorSweep"] = {
                f"{floor:.2f}": evaluate(gold, observations, misread_floor=floor)[1].to_json()
                for floor in (0.20, 0.30, 0.34, 0.40, 0.50, 0.60)
            }
        if args.cases.exists():
            record["cases"] = check_cases(load_cases(args.cases), observations)
        records.append(record)

    write_results(records, args.out, all_variants=args.variant == "all")

    print()
    print(render_table(records))
    print(f"\n{args.out} 에 썼다")
    return 0


def _run_cases_only(names: Sequence[str], cases_path: Path, base_config: Path) -> int:
    """등록부의 프레임만 읽어 사례 상태를 본다. 라벨된 클립이 없어도 돈다."""
    cases = load_cases(cases_path)
    runnable = [case for case in cases if case.frame is not None]
    if not runnable:
        print("프레임이 붙은 사례가 없다. 등록부의 frame 을 채워야 돈다.", file=sys.stderr)
        return 1

    width = max(len(case.id) for case in cases)
    header = f"{'사례':<{width}}  " + "  ".join(f"{name:<14}" for name in names)
    rows: dict[str, list[str]] = {case.id: [] for case in cases}
    for name in names:
        variant = VARIANTS_BY_NAME[name]
        print(f"[{name}] {variant.why}", flush=True)
        observations = read_case_frames(runnable, variant, base_config=base_config)
        for status in check_cases(cases, observations):
            rows[status["id"]].append(status["status"])

    print()
    print(header)
    print("-" * len(header))
    for case in cases:
        print(f"{case.id:<{width}}  " + "  ".join(f"{value:<14}" for value in rows[case.id]))
    print("\n기대한 글자와 지금 읽는 글자:")
    for case in cases:
        where = case.frame.as_posix() if case.frame else "(프레임 없음)"
        print(f"  {case.id}: {case.expected!r} ← 보고된 오독 {case.observed!r}  [{where}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
