"""Frame 간 문구 병합. 원본은 유지하고 출력 관측 배열의 인덱스를 참조한다."""

import re
import tomllib
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass
from difflib import SequenceMatcher
from functools import lru_cache
from pathlib import Path
from typing import TYPE_CHECKING, Literal

from pydantic import BaseModel, ConfigDict, Field

from npick_worker.versioning import version_id

if TYPE_CHECKING:
    from npick_worker.ocr.models import OcrObservation

DEFAULT_MERGE_CONFIG = Path(__file__).resolve().parent.parent / "config" / "ocr-merge.v1.toml"


class OcrMergeConfig(BaseModel):
    """1.0은 정규화 일치만 허용한다. 낮은 값은 개발셋으로 평가한 뒤 사용한다."""

    model_config = ConfigDict(frozen=True, extra="forbid")
    schema_: Literal["ocr-merge/v1"] = Field(alias="schema")
    similarity_threshold: float = Field(gt=0, le=1)

    @property
    def version_id(self) -> str:
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_merge_config(path: Path = DEFAULT_MERGE_CONFIG) -> OcrMergeConfig:
    return OcrMergeConfig.model_validate(tomllib.loads(path.read_text(encoding="utf-8")))


@lru_cache(maxsize=1)
def get_merge_config() -> OcrMergeConfig:
    return load_merge_config()


@dataclass(frozen=True, slots=True)
class OcrTextGroup:
    scene_index: int
    observation_indices: tuple[int, ...]
    representative_index: int


def comparison_text(text: str) -> str:
    """검색 토큰과 독립된 비교값. 숫자·구두점은 없애지 않는다."""
    return "".join(unicodedata.normalize("NFKC", text).casefold().split())


def _compatible(left: str, right: str, threshold: float) -> bool:
    if left == right:
        return True
    if threshold == 1 or not left or not right:
        return False
    # 숫자/날짜/소수점/부호가 다른 자막은 높은 문자열 유사도로도 합치지 않는다.
    if re.findall(r"\d+", left) != re.findall(r"\d+", right):
        return False
    if [c for c in left if not c.isalnum()] != [c for c in right if not c.isalnum()]:
        return False
    # SequenceMatcher는 방향에 따라 달라질 수 있다. 양방향 모두 통과해야 한다.
    return (
        min(
            SequenceMatcher(None, left, right, autojunk=False).ratio(),
            SequenceMatcher(None, right, left, autojunk=False).ratio(),
        )
        >= threshold
    )


def merge_observations(
    observations: Sequence["OcrObservation"], config: OcrMergeConfig | None = None
) -> tuple[OcrTextGroup, ...]:
    """Scene별 complete-link 병합. 같은 frame은 중복 제거 대상이 아니다.

    모든 구성원과 호환되는 그룹이 정확히 하나일 때만 추가한다. 여러 그룹에
    붙을 수 있으면 독립 관측으로 남겨 같은 화면의 동명 문구를 임의로 매칭하지 않는다.
    대표는 confidence 최대 관측이며 동률은 시각·위치·원문 순서로 고른다.
    참조 인덱스는 입력 배열 기준이고 원본을 재배열하거나 수정하지 않는다.
    """
    settings = config if config is not None else get_merge_config()
    texts = [comparison_text(obs.raw_text) for obs in observations]
    frame_members: dict[tuple[int, int], list[int]] = {}
    for index, obs in enumerate(observations):
        frame_members.setdefault((obs.keyframe.scene_index, obs.keyframe.timestamp_ms), []).append(
            index
        )
    ambiguous: set[int] = set()
    for members in frame_members.values():
        for offset, left in enumerate(members):
            for right in members[offset + 1 :]:
                if _compatible(texts[left], texts[right], settings.similarity_threshold):
                    ambiguous.update((left, right))

    def order(index: int) -> tuple[object, ...]:
        obs = observations[index]
        return (
            obs.keyframe.scene_index,
            obs.keyframe.timestamp_ms,
            obs.keyframe.storage_key,
            obs.box.points,
            obs.raw_text,
            -obs.confidence,
            obs.tokens,
            obs.unverified,
            index,
        )

    by_scene: dict[int, list[list[int]]] = {}
    for index in sorted(range(len(observations)), key=order):
        obs = observations[index]
        groups = by_scene.setdefault(obs.keyframe.scene_index, [])
        candidates = [
            group
            for group in groups
            if index not in ambiguous
            and all(
                member not in ambiguous
                and observations[member].keyframe.timestamp_ms != obs.keyframe.timestamp_ms
                and _compatible(texts[index], texts[member], settings.similarity_threshold)
                for member in group
            )
        ]
        if len(candidates) == 1:
            candidates[0].append(index)
        else:
            groups.append([index])
    return tuple(
        OcrTextGroup(
            scene_index=scene,
            observation_indices=tuple(group),
            representative_index=min(
                group, key=lambda index: (-observations[index].confidence, order(index))
            ),
        )
        for scene, groups in sorted(by_scene.items())
        for group in groups
    )
