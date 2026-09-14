"""text embedding 단계 (S15P21A501-100).

실제 가중치를 돌리는 것은 `smoke` 뿐이다. 나머지는 전부 가짜 인코더를 쓴다 — 이 단계에서
규약인 것은 "어떤 벡터가 나오는가" 가 아니라 **무엇을 인코더에 넣고 무엇을 기록하는가**
이고, 그건 모델 없이 검증된다(`test_ocr.py` 가 엔진 없이 행 변환을 검증하는 것과 같은
판단).
"""

import math
from collections.abc import Sequence
from pathlib import Path

import pytest

from npick_worker.text_embedding import (
    SceneText,
    TextEmbeddingConfig,
    compose_text,
    embed_scenes,
    get_default_config,
    load_config,
)
from npick_worker.text_embedding.config import DEFAULT_CONFIG_PATH

#: `scene.embedding vector(1024)` 와 같아야 하는 값. 마이그레이션
#: `V20260907092019__baseline.sql:83` 이 정본이다.
SCENE_EMBEDDING_DIMENSION = 1024


class _FakeEncoder:
    """`TextEncoder` 구현. 입력에 따라 달라지되 결정적인 벡터를 만든다.

    **L2 norm 이 1 이 아닌 값을 돌려준다.** 정규화를 embedder 가 하는지 확인하려면
    인코더가 이미 정규화된 값을 주면 안 된다.
    """

    name = "fake"
    version = "0"

    def __init__(
        self,
        *,
        dimension: int = 4,
        model_version: str = "fake/model@0",
        vector: Sequence[float] | None = None,
    ) -> None:
        self._dimension = dimension
        self.model_version = model_version
        self._fixed = tuple(vector) if vector is not None else None
        self.calls: list[tuple[str, ...]] = []

    @property
    def dimension(self) -> int:
        return self._dimension

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        self.calls.append(tuple(texts))
        return tuple(self._vector(text) for text in texts)

    def _vector(self, text: str) -> tuple[float, ...]:
        if self._fixed is not None:
            return self._fixed
        seed = float(len(text) + 1)
        return tuple(seed * (index + 1) for index in range(self._dimension))


def _config(**overrides: object) -> TextEmbeddingConfig:
    """테스트용 설정. 차원을 작게 줄여 조립 규칙만 본다."""
    values: dict[str, object] = {
        "schema": "text-embedding/v1",
        "dimension": 4,
        "normalize": True,
        "document_prefix": "",
        "section_separator": "\n",
        "dialogue_separator": " ",
        "batch_size": 16,
    }
    values.update(overrides)
    return TextEmbeddingConfig.model_validate(values)


def _norm(vector: Sequence[float]) -> float:
    return math.sqrt(sum(value * value for value in vector))


# ── 설정 ────────────────────────────────────────────────────────────


def test_default_config_loads_and_has_a_version() -> None:
    config = get_default_config()
    assert config.schema_ == "text-embedding/v1"
    assert config.version_id.startswith("text-embedding/v1:")


def test_default_dimension_matches_the_scene_embedding_column() -> None:
    """차원이 어긋나면 BE 의 `vector(1024)` INSERT 가 통째로 실패한다.

    S15P21A501-175 가 1024 로 확정했고 컬럼도 그 값이다. 설정이 앞서 가면 전체
    재색인 전에는 되돌릴 수 없으므로 여기서 못 박는다.
    """
    assert get_default_config().dimension == SCENE_EMBEDDING_DIMENSION


def test_unknown_key_is_rejected(tmp_path: Path) -> None:
    """toml 키 오타가 조용히 무시되면 config_version 은 바뀌는데 동작은 그대로가 된다."""
    path = tmp_path / "custom.toml"
    path.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8") + '\nnot_a_key = "x"\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="not_a_key"):
        load_config(path)


def test_versioned_file_name_must_match_schema(tmp_path: Path) -> None:
    path = tmp_path / "text_embedding.v9.toml"
    path.write_text(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"), encoding="utf-8")
    with pytest.raises(ValueError, match="text-embedding/v9"):
        load_config(path)


def test_config_version_changes_when_a_value_changes() -> None:
    assert _config().version_id != _config(dimension=768).version_id


# ── 텍스트 조립 ─────────────────────────────────────────────────────


def test_caption_and_dialogue_are_joined() -> None:
    """FRD §11.4 — 캡션과 대사를 합쳐 벡터 하나를 만든다."""
    scene = SceneText(scene_index=0, caption="광화문 앞 집회", dialogue=("첫 줄", "둘째 줄"))
    assert compose_text(scene, _config()) == "광화문 앞 집회\n첫 줄 둘째 줄"


def test_caption_only_scene_has_text() -> None:
    scene = SceneText(scene_index=0, caption="광화문 앞 집회", dialogue=())
    assert compose_text(scene, _config()) == "광화문 앞 집회"


def test_dialogue_only_scene_has_text() -> None:
    """VLM 이 비치명 단계라 캡션이 없는 장면이 정상적으로 존재한다(`stages.py`)."""
    scene = SceneText(scene_index=0, caption="", dialogue=("대사만 있는 장면",))
    assert compose_text(scene, _config()) == "대사만 있는 장면"


def test_blank_only_input_composes_to_nothing() -> None:
    """공백만 있는 캡션은 텍스트가 아니다. 그대로 두면 공백을 임베딩하게 된다."""
    scene = SceneText(scene_index=0, caption="   ", dialogue=("", "  "))
    assert compose_text(scene, _config()) == ""


def test_document_prefix_is_prepended() -> None:
    """arctic-ko 는 문서측 접두가 없지만 e5 계열은 `passage: ` 를 요구한다.

    모델 동결 전이므로 접두는 설정이 정한다(S15P21A501-175 의 어댑터 경계 요구).
    """
    scene = SceneText(scene_index=0, caption="광화문", dialogue=())
    assert compose_text(scene, _config(document_prefix="passage: ")) == "passage: 광화문"


# ── 산출물 ──────────────────────────────────────────────────────────


def test_scene_without_text_is_skipped_not_given_a_vector() -> None:
    """`scene.embedding` 은 nullable 이다. 텍스트가 없으면 벡터를 지어내지 않는다.

    빈 문자열을 임베딩하면 모든 빈 장면이 서로 최근접이 되어 보조 채널이 오염된다.
    """
    encoder = _FakeEncoder()
    result = embed_scenes(
        [
            SceneText(scene_index=0, caption="광화문", dialogue=()),
            SceneText(scene_index=1, caption="", dialogue=()),
        ],
        encoder=encoder,
        config=_config(),
    )
    assert [scene.scene_index for scene in result.scenes] == [0]
    assert result.skipped == (1,)
    # 빈 장면은 인코더에 아예 들어가지 않는다.
    assert encoder.calls == [("광화문",)]


def test_dimension_mismatch_is_rejected() -> None:
    """모델을 갈아 끼웠는데 차원이 다르면 BE 의 INSERT 가 실패한다. 먼저 여기서 막는다."""
    encoder = _FakeEncoder(dimension=768)
    with pytest.raises(ValueError, match="768"):
        embed_scenes(
            [SceneText(scene_index=0, caption="광화문", dialogue=())],
            encoder=encoder,
            config=_config(dimension=4),
        )


def test_vectors_are_l2_normalized() -> None:
    """pgvector 코사인 검색의 전제다. 어댑터마다 정규화 여부가 달라 여기서 통일한다."""
    result = embed_scenes(
        [SceneText(scene_index=0, caption="광화문", dialogue=())],
        encoder=_FakeEncoder(),
        config=_config(normalize=True),
    )
    assert _norm(result.scenes[0].vector) == pytest.approx(1.0)


def test_normalization_can_be_turned_off() -> None:
    result = embed_scenes(
        [SceneText(scene_index=0, caption="광화문", dialogue=())],
        encoder=_FakeEncoder(vector=(3.0, 4.0, 0.0, 0.0)),
        config=_config(normalize=False),
    )
    assert result.scenes[0].vector == (3.0, 4.0, 0.0, 0.0)


def test_zero_vector_is_rejected() -> None:
    """0 벡터는 코사인 거리가 정의되지 않는다. pgvector 에서 NaN 이 되어 조용히 퍼진다."""
    with pytest.raises(ValueError, match="0 벡터"):
        embed_scenes(
            [SceneText(scene_index=0, caption="광화문", dialogue=())],
            encoder=_FakeEncoder(vector=(0.0, 0.0, 0.0, 0.0)),
            config=_config(normalize=True),
        )


def test_result_keeps_the_text_each_vector_came_from() -> None:
    """FRD §7.2 — 나중에 "그때 무엇을 임베딩했나" 를 물을 수 있어야 한다."""
    result = embed_scenes(
        [SceneText(scene_index=0, caption="광화문", dialogue=("집회 현장입니다",))],
        encoder=_FakeEncoder(),
        config=_config(),
    )
    assert result.scenes[0].source_text == "광화문\n집회 현장입니다"


def test_result_carries_every_reproducibility_axis() -> None:
    config = _config()
    result = embed_scenes(
        [SceneText(scene_index=0, caption="광화문", dialogue=())],
        encoder=_FakeEncoder(),
        config=config,
    )
    assert result.config_version == config.version_id
    assert result.adapter == "fake"
    assert result.adapter_version == "0"
    assert result.model_version == "fake/model@0"
    assert result.dimension == 4


def test_changing_the_model_changes_the_recorded_model_version() -> None:
    """일감 요구 — "모델 교체 시 버전 다르면 다른 산출물"."""
    scenes = [SceneText(scene_index=0, caption="광화문", dialogue=())]
    before = embed_scenes(scenes, encoder=_FakeEncoder(), config=_config())
    after = embed_scenes(
        scenes,
        encoder=_FakeEncoder(model_version="other/model@1"),
        config=_config(),
    )
    assert before.model_version != after.model_version


def test_empty_scene_list_is_not_an_error() -> None:
    """장면이 0 개인 클립은 상류의 문제다. 이 단계가 예외로 바꾸면 원인이 가려진다."""
    encoder = _FakeEncoder()
    result = embed_scenes([], encoder=encoder, config=_config())
    assert result.scenes == ()
    assert result.skipped == ()
    # 벡터가 없어도 재현 정보는 남는다.
    assert result.model_version == "fake/model@0"
    # 인코더를 부르지 않는다 — 빈 배치로 가중치를 깨우지 않는다.
    assert encoder.calls == []


def test_scene_order_is_preserved() -> None:
    result = embed_scenes(
        [
            SceneText(scene_index=2, caption="셋째", dialogue=()),
            SceneText(scene_index=0, caption="첫째", dialogue=()),
        ],
        encoder=_FakeEncoder(),
        config=_config(),
    )
    assert [scene.scene_index for scene in result.scenes] == [2, 0]


def test_duplicate_scene_index_is_rejected() -> None:
    """`scene` 은 클립 안에서 index 가 유일하다. 두 벡터가 한 행을 노리면 덮어쓴다."""
    with pytest.raises(ValueError, match="중복"):
        embed_scenes(
            [
                SceneText(scene_index=0, caption="첫째", dialogue=()),
                SceneText(scene_index=0, caption="둘째", dialogue=()),
            ],
            encoder=_FakeEncoder(),
            config=_config(),
        )
