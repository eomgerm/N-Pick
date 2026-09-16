"""장면 metadata 를 눈으로 확인하고 후보를 실측하는 도구.

    uv run --directory ai python -m npick_worker.vlm_metadata.report \
        samples/out/KNI_02205-frames --out samples/out/KNI_02205-vlm

입력은 `frame_extraction.report` 가 만든 디렉터리다(`s0000/kf-000004133.jpg` 구조).
운영 경로에서는 그 목록이 BE 의 `inputs.upstream.frameExtraction` 으로 온다.

이 도구가 티켓의 두 가지를 담당한다.

- **smoke test** (`--smoke`) — scene 10건 이상에 각 scene 의 복수 keyframe 을 넣어 schema 에
  맞는 출력이 나오는지 확인한다. **두 조건을 도구가 검사한다** — 종료 코드로 판정할 수
  있어야 그 실행이 증거가 되기 때문이다. 장면이 모자라거나 한 장짜리 장면이 섞이면
  출력이 전부 유효해도 실패로 끝낸다.
- **후보 비교** — 모델을 바꿔 가며(`--model`) 같은 입력으로 돌리고, 장면당 소요 시간과
  원시 출력을 남긴다. "평가에 사용한 설정 version 과 원시 결과를 보존한다" 가 티켓의
  요구이므로 `--out` 은 통과한 출력과 **거부된 출력의 원문을 모두** 저장한다.

운영 경로가 아니다(`ocr/report.py` 와 같은 성격의 개발자 도구).
"""

import argparse
import json
import re
import sys
import time
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Final

from npick_worker.media_errors import MediaUnreadableError
from npick_worker.vlm_metadata.client import VlmCallError, VlmClient, VlmModelUnavailableError
from npick_worker.vlm_metadata.config import VlmMetadataConfig, get_default_config, load_config
from npick_worker.vlm_metadata.describer import SceneDescription, describe_scene, select_keyframes
from npick_worker.vlm_metadata.models import KeyframeRef, SceneKeyframes
from npick_worker.vlm_metadata.prompt import prompt_version
from npick_worker.vlm_metadata.schema import SCHEMA_VERSION
from npick_worker.vlm_metadata.validator import VlmSchemaInvalidError

#: smoke test 가 요구하는 최소 장면 수. 티켓의 "scene 10건 이상" 이 근거다.
MIN_SMOKE_SCENES: Final[int] = 10

#: smoke test 가 요구하는 장면당 최소 keyframe 수. 티켓의 "각 scene 의 복수 selected
#: keyframe 을 입력" 이 근거다. 한 장으로 돌면 이 단계가 존재하는 이유(장면을 하나로
#: 이해한다)가 검증되지 않는다.
MIN_SMOKE_KEYFRAMES: Final[int] = 2

#: 그 장면만 버리고 계속할 수 있는 호출 실패. 다음 장면은 성공할 수 있고, **어느 장면에서
#: 무엇이 실패했는지가 곧 그 후보의 성적**이다. 여기 없는 예외(OOM·버그·중단)는 그대로
#: 올려보낸다 — 그때도 그때까지의 기록은 파일에 남는다(`main` 의 `finally`).
_CALL_FAILURES: Final[tuple[type[Exception], ...]] = (
    TimeoutError,
    VlmCallError,
    VlmModelUnavailableError,
    MediaUnreadableError,
)

#: `frame_extraction` 의 `FILE_NAME_TEMPLATE` 이 만든 이름을 되읽는다.
_FILE_NAME: Final[re.Pattern[str]] = re.compile(
    r"s(?P<scene>\d{4})[/\\]kf-(?P<timestamp>\d{9})\.jpg"
)


def discover_scenes(frames_dir: Path) -> tuple[tuple[SceneKeyframes, ...], dict[str, Path]]:
    """디렉터리에서 장면별 keyframe 묶음을 만든다.

    파일 이름이 곧 `(sceneIndex, timestampMs)` 다. `storage_key` 자리에는 디렉터리 기준
    상대 경로를 쓴다 — 운영 경로의 `keyframe.storage_key` 와 같은 역할이다.
    """
    by_scene: dict[int, list[KeyframeRef]] = {}
    paths: dict[str, Path] = {}
    for jpg in sorted(frames_dir.glob("s*/kf-*.jpg")):
        relative = jpg.relative_to(frames_dir).as_posix()
        match = _FILE_NAME.fullmatch(relative)
        if match is None:
            continue
        scene_index = int(match.group("scene"))
        by_scene.setdefault(scene_index, []).append(
            KeyframeRef(
                scene_index=scene_index,
                timestamp_ms=int(match.group("timestamp")),
                storage_key=relative,
            )
        )
        paths[relative] = jpg
    scenes = tuple(
        SceneKeyframes(scene_index=index, keyframes=tuple(keyframes))
        for index, keyframes in sorted(by_scene.items())
    )
    return scenes, paths


@dataclass(frozen=True, slots=True)
class Rejected:
    """결과를 얻지 못한 장면 하나. **무엇을 넣었고 왜 실패했는지를 들고 있다.**

    `malformed output 이 가장 중요한 분석 대상` 이라는 것이 이 클래스의 존재 이유다 —
    통과한 출력만 남기면 프롬프트를 왜 고쳐야 하는지가 기록에서 사라진다.

    schema 거부만 담지 않는다. **timeout·OOM 같은 호출 실패도 같은 자리에 남는다** —
    "10장면 중 3장면이 120초를 넘었다" 는 그 후보를 탈락시키는 근거이고, 그것을 남기지
    않으면 실패하기 쉬운 후보일수록 비교표에서 유리해진다.
    """

    scene_index: int
    #: `schema_invalid`(형식·어휘 위반) · `call_failed`(호출 실패) · `aborted`(실행 중단).
    kind: str
    reason: str
    #: 실제로 모델에 넣은 keyframe. 실패를 재현하려면 입력이 있어야 한다.
    inputs: tuple[KeyframeRef, ...]
    #: 실패까지 걸린 시간. timeout 인지 즉시 실패인지가 여기서 갈린다.
    elapsed_seconds: float
    #: 모델이 낸 텍스트. 호출 자체가 실패했으면 없다.
    raw_output: str | None = None


def render_table(rows: Sequence[tuple[SceneDescription, float]]) -> str:
    """장면마다 한 줄. 근거가 없어 비운 값은 `-` 로 보인다."""
    header = f"{'scene':>5} {'n':>2} {'초':>6} {'shot':<10} {'scene_type':<12} 설명"
    lines: list[str] = []
    for described, elapsed in rows:
        metadata = described.metadata
        scene_type = metadata.scene_type
        caption = metadata.caption
        lines.append(
            f"{metadata.scene_index:>5} "
            f"{len(described.inputs):>2} "
            f"{elapsed:>6.1f} "
            f"{metadata.shot_type.value:<10} "
            f"{(scene_type.value if scene_type is not None else '-'):<12} "
            f"{(caption.value if caption is not None else '-')}"
        )
    return "\n".join([header, "-" * len(header), *lines])


def reset_peak_memory() -> None:
    """GPU peak 측정을 0 에서 다시 시작한다. torch·CUDA 가 없으면 아무 일도 하지 않는다."""
    try:
        import torch
    except ImportError:
        return
    if torch.cuda.is_available():
        torch.cuda.reset_peak_memory_stats()


def peak_memory() -> dict[str, int] | None:
    """이 실행이 실제로 쓴 VRAM 최댓값. 없으면 `None`.

    후보 비교에서 이 값이 없으면 "48GB 에서 돌았다" 만 남고 **얼마가 필요한지**는 남지
    않는다. `nvidia-smi` 를 사람이 눈으로 본 값은 다른 프로세스와 allocator 캐시가 섞여
    재현 가능한 근거가 아니라서, 문서(§9.2)가 `max_memory_allocated`·`max_memory_reserved`
    를 요구한다.
    """
    try:
        import torch
    except ImportError:
        return None
    if not torch.cuda.is_available():
        return None
    return {
        "peakAllocatedBytes": int(torch.cuda.max_memory_allocated()),
        "peakReservedBytes": int(torch.cuda.max_memory_reserved()),
    }


def to_json(
    rows: Sequence[tuple[SceneDescription, float]],
    rejected: Sequence[Rejected],
    config: VlmMetadataConfig,
    client: VlmClient,
    memory: dict[str, int] | None = None,
) -> dict[str, object]:
    """평가 기록. **원시 출력과 설정 version 을 함께 남긴다**(티켓 요구).

    `rawOutput` 을 넣는 이유는 프롬프트를 고칠 근거가 거기에만 있기 때문이다. 검증을
    통과한 값만 남기면 "왜 이런 답이 나왔나" 를 나중에 볼 수 없다.

    **거부된 장면도 같은 파일에 남는다.** 통과한 것만 저장하면 후보의 성적에서 가장 중요한
    부분 — 무엇을 어떻게 틀렸나 — 이 카운트 하나로 줄어든다.
    """
    return {
        "versions": {
            "schemaVersion": SCHEMA_VERSION,
            "configVersion": config.version_id,
            "promptVersion": prompt_version(config),
            "engine": client.name,
            "engineVersion": client.version,
            "modelVersion": client.model_version,
            # 후보 비교에서 이 값이 cpu 면 시간·VRAM 수치를 쓸 수 없다.
            "device": getattr(client, "device", None),
        },
        # torch·CUDA 가 없으면 null 이다. **0 으로 적지 않는다** — "재지 못했다" 와
        # "0 바이트를 썼다" 는 다르고, 비교표에서 뒤엣것은 거짓이다.
        "memory": memory,
        "rejected": [
            {
                "sceneIndex": item.scene_index,
                "kind": item.kind,
                "reason": item.reason,
                "inputs": [
                    {"timestampMs": keyframe.timestamp_ms, "storageKey": keyframe.storage_key}
                    for keyframe in item.inputs
                ],
                "elapsedSeconds": round(item.elapsed_seconds, 2),
                "rawOutput": item.raw_output,
            }
            for item in rejected
        ],
        "scenes": [
            {
                "sceneIndex": described.metadata.scene_index,
                "inputs": [
                    {"timestampMs": keyframe.timestamp_ms, "storageKey": keyframe.storage_key}
                    for keyframe in described.inputs
                ],
                "elapsedSeconds": round(elapsed, 2),
                "shotType": {
                    "value": described.metadata.shot_type.value,
                    "confidence": described.metadata.shot_type.confidence,
                },
                "caption": (
                    None
                    if described.metadata.caption is None
                    else {
                        "value": described.metadata.caption.value,
                        "confidence": described.metadata.caption.confidence,
                    }
                ),
                "tagCandidates": [
                    {"type": tag.type, "value": tag.value, "confidence": tag.confidence}
                    for tag in described.metadata.tag_candidates
                ],
                # 정규화한 자리. 비어 있지 않으면 그 후보가 계약과 다른 표기로 '없음' 을
                # 말했다는 뜻이라 후보 비교에서 읽을 값이다(`normalize.py`).
                "normalizations": list(described.normalizations),
                "rawOutput": described.raw_output,
            }
            for described, elapsed in rows
        ],
    }


def main(argv: Sequence[str] | None = None, *, client: VlmClient | None = None) -> int:
    parser = argparse.ArgumentParser(description="scene별 복수 keyframe으로 VLM metadata 생성")
    parser.add_argument("frames_dir", type=Path, help="frame_extraction.report 산출 디렉터리")
    parser.add_argument("--out", type=Path, help="결과 JSON 을 저장할 디렉터리")
    parser.add_argument(
        "--model",
        help="가중치 식별자. 주지 않으면 NPICK_AI_VLM_MODEL 을 쓴다 (후보 비교용)",
    )
    parser.add_argument("--revision", default="", help="가중치 리비전")
    parser.add_argument("--config", type=Path, help="설정 toml 경로 (프롬프트·어휘 실험용)")
    parser.add_argument("--limit", type=int, help="앞에서 이만큼의 장면만 처리")
    parser.add_argument(
        "--smoke",
        action="store_true",
        help=(
            f"티켓의 smoke 조건을 검사한다 — 장면 {MIN_SMOKE_SCENES}개 이상, "
            f"장면마다 keyframe {MIN_SMOKE_KEYFRAMES}장 이상"
        ),
    )
    args = parser.parse_args(argv)

    config = load_config(args.config) if args.config is not None else get_default_config()
    scenes, paths = discover_scenes(args.frames_dir)
    if not scenes:
        print(f"keyframe 을 찾지 못했다: {args.frames_dir}", file=sys.stderr)
        return 1
    if args.limit is not None:
        scenes = scenes[: args.limit]

    if args.smoke:
        shortfall = _smoke_shortfall(scenes, config)
        if shortfall is not None:
            # 출력이 전부 유효해도 실패로 끝낸다. 조건을 만족하지 않은 실행은 티켓이
            # 요구한 것을 증명하지 못한다.
            print(f"smoke 조건 미달: {shortfall}", file=sys.stderr)
            return 1

    try:
        if client is None:
            client = _client(args.model, args.revision)
    except VlmModelUnavailableError as exc:
        # 모델이 없는 것은 이 도구의 버그가 아니다. 트레이스백 대신 무엇을 해야 하는지
        # 알려 준다 — 모델 이름은 실측 후 확정 대상이라 코드가 고르지 않는다(FRD §11).
        print(f"{exc}", file=sys.stderr)
        print(
            "  --model 로 후보를 주거나 NPICK_AI_VLM_MODEL 을 설정한다. "
            "가중치 실행에는 gpu 그룹이 필요하다: uv sync --group gpu --group cu130|cu128",
            file=sys.stderr,
        )
        return 1
    device = getattr(client, "device", None)
    print(
        f"장면 {len(scenes)}개 | {config.version_id} | {prompt_version(config)} | "
        f"{client.name} {client.version} | {client.model_version} | device={device}"
    )

    rows: list[tuple[SceneDescription, float]] = []
    rejected: list[Rejected] = []
    target = args.out / "vlm-metadata.json" if args.out is not None else None

    def save() -> None:
        """지금까지의 기록을 파일에 쓴다. **장면마다 부른다.**

        마지막에 한 번만 쓰면 중간에 OOM 으로 실행이 끊길 때 앞선 장면의 결과까지 함께
        사라진다. 오래 걸리고 잘 죽는 후보일수록 증거가 남지 않는다는 뜻이라, 비교표가
        그 후보에게 유리해진다.
        """
        if target is None:
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_suffix(".json.tmp")
        temporary.write_text(
            json.dumps(
                to_json(rows, rejected, config, client, peak_memory()),
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        temporary.replace(target)

    # 가중치 로딩까지 포함해 잰다. 이 값이 곧 "이 후보를 돌리려면 얼마가 필요한가" 다.
    reset_peak_memory()
    for scene in scenes:
        # 실패도 입력과 함께 남긴다. `describe_scene` 안에서 고르는 것과 같은 함수라
        # 실제로 넣은(넣으려던) 프레임이다.
        selected = select_keyframes(scene, config)
        started = time.perf_counter()
        try:
            described = describe_scene(scene, paths, client, config)
        except VlmSchemaInvalidError as exc:
            # 실측 도구는 멈추지 않는다. **운영 경로와 다른 점이고 의도적이다** —
            # 단계는 하나만 깨져도 전체 실패지만(`describer.describe_scenes`), 후보를
            # 비교할 때는 몇 장면이 왜 깨졌는지가 그 후보의 성적이다.
            rejected.append(
                _failed(scene, "schema_invalid", str(exc), selected, started, exc.raw_output)
            )
            print(f"  scene {scene.scene_index}: 거부 — {exc}", file=sys.stderr)
        except _CALL_FAILURES as exc:
            # timeout 도 여기다. "10장면 중 3장면이 상한을 넘었다" 는 그 후보를 탈락시키는
            # 근거이고, 남기지 않으면 그 후보가 "거부 0건" 으로 보인다.
            reason = f"{type(exc).__name__}: {exc}"
            rejected.append(_failed(scene, "call_failed", reason, selected, started))
            print(f"  scene {scene.scene_index}: 호출 실패 — {reason}", file=sys.stderr)
        except BaseException as exc:
            # OOM·중단·이 도구의 버그다. 호출 실패와 섞어 넘기지 않는다 — 그러면 도구가
            # 깨진 것이 후보의 성적으로 기록된다. 다만 **무엇을 하다 끊겼는지는 남긴다.**
            reason = f"{type(exc).__name__}: {exc}"
            rejected.append(_failed(scene, "aborted", reason, selected, started))
            save()
            raise
        else:
            rows.append((described, time.perf_counter() - started))
        save()

    if rows:
        print(render_table(rows))
    memory = peak_memory()
    if memory is not None:
        print(
            f"peak VRAM | allocated {memory['peakAllocatedBytes'] / 1024**3:.2f} GiB | "
            f"reserved {memory['peakReservedBytes'] / 1024**3:.2f} GiB"
        )
    total = sum(elapsed for _, elapsed in rows)
    # 정규화 자리 수를 함께 찍는다. 0 이 아니면 이 후보가 '없음' 을 계약과 다른 표기로
    # 썼다는 뜻이고(`normalize.py`), 출력이 유효해도 사람이 원문을 봐야 한다.
    normalized = sum(len(described.normalizations) for described, _ in rows)
    print(
        f"성공 {len(rows)}장면 | 거부 {len(rejected)}장면 | 정규화 {normalized}자리 | "
        f"총 {total:.1f}초 | 장면당 평균 {total / len(rows):.1f}초"
        if rows
        else f"성공 0장면 | 거부 {len(rejected)}장면"
    )

    if target is not None:
        print(f"저장: {target}")

    # 거부가 하나라도 있으면 실패로 끝낸다. smoke test 를 CI 나 스크립트에서 돌릴 때
    # 종료 코드로 판정할 수 있어야 한다.
    return 0 if rows and not rejected else 1


def _smoke_shortfall(scenes: Sequence[SceneKeyframes], config: VlmMetadataConfig) -> str | None:
    """smoke 조건을 만족하지 못한 이유. 만족하면 `None`.

    입력을 보고 판정한다 — 출력이 아니라. 장면이 9개뿐이거나 한 장짜리 장면이 섞인 실행은
    모델이 아무리 잘 답해도 티켓이 요구한 것을 증명하지 못한다.

    세는 것은 디렉터리에 있는 프레임이 아니라 **실제로 모델에 넣는 프레임**이다. 둘은
    설정 때문에 다를 수 있다 — `max_keyframes_per_scene = 1` 이면 장면마다 열 장이 있어도
    한 장씩만 들어가는데, 그 실행은 "각 scene 의 복수 keyframe 을 입력" 을 증명하지 못한다.
    """
    if len(scenes) < MIN_SMOKE_SCENES:
        return f"장면이 {len(scenes)}개다 (필요 {MIN_SMOKE_SCENES}개 이상)"
    thin = [
        scene.scene_index
        for scene in scenes
        if len(select_keyframes(scene, config)) < MIN_SMOKE_KEYFRAMES
    ]
    if thin:
        return (
            f"모델에 넣는 keyframe 이 {MIN_SMOKE_KEYFRAMES}장 미만인 장면이 있다: "
            f"{thin[:5]}{'…' if len(thin) > 5 else ''} "
            f"(max_keyframes_per_scene={config.max_keyframes_per_scene})"
        )
    return None


def _failed(
    scene: SceneKeyframes,
    kind: str,
    reason: str,
    inputs: tuple[KeyframeRef, ...],
    started: float,
    raw_output: str | None = None,
) -> Rejected:
    """실패 하나를 기록으로 만든다. 소요 시간은 여기서 잰다 — 호출마다 빠뜨리기 쉽다."""
    return Rejected(
        scene_index=scene.scene_index,
        kind=kind,
        reason=reason,
        inputs=inputs,
        elapsed_seconds=time.perf_counter() - started,
        raw_output=raw_output,
    )


def _client(model: str | None, revision: str) -> VlmClient:
    """후보 비교를 위해 모델을 인자로 바꿀 수 있게 한다.

    설정(`NPICK_AI_VLM_MODEL`)을 쓰지 않고 인자를 받는 이유는 이 도구의 목적이 **같은
    입력으로 여러 후보를 돌리는 것**이기 때문이다. 운영 경로는 그대로 설정을 쓴다.
    """
    from npick_worker.settings import get_settings
    from npick_worker.vlm_metadata.transformers_backend import build_client, shared_client

    settings = get_settings()
    if model is None:
        return shared_client(settings)
    # **`build_client` 로 만든다.** 생성자를 직접 부르면 `device` 를 빠뜨리게 되고, 그러면
    # 가중치가 CPU 에 남아 후보 비교의 추론 시간과 VRAM 수치가 통째로 무의미해진다.
    # 예외가 나지 않고 "느리다" 로만 드러나는 종류의 실수다.
    return build_client(
        model,
        revision=revision,
        model_dir=settings.vlm_model_dir,
        device_choice=settings.device,
    )


if __name__ == "__main__":
    raise SystemExit(main())
