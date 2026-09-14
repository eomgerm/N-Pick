"""text embedding 단계 (S15P21A501-100).

실제 가중치를 돌리는 것은 `smoke` 뿐이다. 나머지는 전부 가짜 인코더를 쓴다 — 이 단계에서
규약인 것은 "어떤 벡터가 나오는가" 가 아니라 **무엇을 인코더에 넣고 무엇을 기록하는가**
이고, 그건 모델 없이 검증된다(`test_ocr.py` 가 엔진 없이 행 변환을 검증하는 것과 같은
판단).

**가짜 인코더는 텍스트 내용으로 벡터를 만든다.** 길이로 만들면 같은 길이의 다른 텍스트가
같은 벡터가 되어, scene 과 벡터의 짝이 뒤바뀌어도 테스트가 통과한다. `embedded`/`texts`
병렬 리스트가 이 모듈의 유일한 off-by-one 위험 지점이라 그 구멍을 열어 둘 수 없다.
"""

import hashlib
import math
import sys
from collections.abc import Sequence
from pathlib import Path
from types import ModuleType, SimpleNamespace

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
from npick_worker.text_embedding.encoder import (
    EmbeddingCallError,
    EmbeddingModelUnavailableError,
)
from npick_worker.text_embedding.sentence_transformers_backend import (
    SentenceTransformerEncoder,
    _load,
    _resolve_revision,
)

#: `scene.embedding vector(1024)` 와 같아야 하는 값. 마이그레이션
#: `V20260907092019__baseline.sql:83` 이 정본이다.
SCENE_EMBEDDING_DIMENSION = 1024


class _FakeEncoder:
    """`TextEncoder` 구현. 텍스트 내용에 따라 달라지되 결정적인 벡터를 만든다.

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

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        self.calls.append(tuple(texts))
        return tuple(self.vector_for(text) for text in texts)

    def vector_for(self, text: str) -> tuple[float, ...]:
        """이 텍스트가 받게 될 벡터. 테스트가 짝을 대조할 때 쓴다."""
        if self._fixed is not None:
            return self._fixed
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        return tuple(float(digest[index % len(digest)] + 1) for index in range(self._dimension))


def _config(**overrides: object) -> TextEmbeddingConfig:
    """테스트용 설정. 차원을 작게 줄여 조립 규칙만 본다."""
    values: dict[str, object] = {
        "schema": "text-embedding/v1",
        "dimension": 4,
        "normalize": True,
        "document_prefix": "",
    }
    values.update(overrides)
    return TextEmbeddingConfig.model_validate(values)


def _norm(vector: Sequence[float]) -> float:
    return math.sqrt(sum(value * value for value in vector))


def _normalized(vector: Sequence[float]) -> tuple[float, ...]:
    norm = _norm(vector)
    return tuple(value / norm for value in vector)


# ── 설정 ────────────────────────────────────────────────────────────


def test_default_config_loads_and_has_a_version() -> None:
    config = get_default_config()
    assert config.schema_ == "text-embedding/v1"
    assert config.version_id.startswith("text-embedding/v1:")


def test_default_dimension_matches_the_scene_embedding_column() -> None:
    """차원이 어긋나면 BE 의 `vector(1024)` INSERT 가 통째로 실패한다.

    S15P21A501-175 가 1024 로 정했고 컬럼도 그 값이다. 설정이 앞서 가면 전체 재색인
    전에는 되돌릴 수 없으므로 여기서 못 박는다.
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


def test_batch_size_is_not_part_of_the_reproducibility_hash() -> None:
    """VRAM 때문에 배치를 줄이면 `stageVersion` 이 움직여 배정이 끊긴다(계약 §7).

    배치 크기는 결과를 바꾸지 않는 실행 설정이라 버전 붙는 설정 파일 밖(`settings.py`)에
    있어야 한다. `ocr_model_dir` 과 같은 판단이다.
    """
    assert "batch_size" not in TextEmbeddingConfig.model_fields
    assert "batch_size" not in DEFAULT_CONFIG_PATH.read_text(encoding="utf-8")


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


def test_each_scene_gets_the_vector_for_its_own_text() -> None:
    """skip 이 섞이면 scene 목록과 인코더 입력의 인덱스가 어긋난다.

    `embedded`/`texts` 병렬 리스트가 이 모듈의 유일한 off-by-one 위험 지점이다. 벡터를
    텍스트 내용에서 유도하므로 짝이 한 칸이라도 밀리면 여기서 걸린다.
    """
    encoder = _FakeEncoder()
    scenes = [
        SceneText(scene_index=0, caption="첫째 장면", dialogue=()),
        SceneText(scene_index=1, caption="", dialogue=()),
        SceneText(scene_index=2, caption="둘째 장면", dialogue=("대사가 붙는다",)),
        SceneText(scene_index=3, caption="   ", dialogue=("",)),
        SceneText(scene_index=4, caption="셋째 장면", dialogue=()),
    ]

    result = embed_scenes(scenes, encoder=encoder, config=_config())

    assert result.skipped == (1, 3)
    assert [scene.scene_index for scene in result.scenes] == [0, 2, 4]
    for scene in result.scenes:
        expected = _normalized(encoder.vector_for(scene.source_text))
        assert scene.vector == pytest.approx(expected), (
            f"scene {scene.scene_index} 의 짝이 어긋났다"
        )


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


def test_zero_vector_is_rejected_even_without_normalization() -> None:
    """0 벡터가 못 쓰는 값인 것은 정규화 여부와 무관하다.

    pgvector 의 코사인 거리는 저장된 값이 0 이면 NaN 이다. 가드가 `normalize` 분기 안에
    있으면 정규화를 끈 설정에서 그대로 DB 로 간다.
    """
    with pytest.raises(ValueError, match="0 벡터"):
        embed_scenes(
            [SceneText(scene_index=0, caption="광화문", dialogue=())],
            encoder=_FakeEncoder(vector=(0.0, 0.0, 0.0, 0.0)),
            config=_config(normalize=False),
        )


def test_non_finite_vector_is_rejected() -> None:
    """fp16 에서 NaN 이 나오면 `norm == 0.0` 을 빠져나간다.

    저장 후 증상은 0 벡터와 **정확히 같다** — 그 장면이 모든 질의에서 조용히 빠진다.
    """
    with pytest.raises(ValueError, match="유한하지 않"):
        embed_scenes(
            [SceneText(scene_index=0, caption="광화문", dialogue=())],
            encoder=_FakeEncoder(vector=(float("nan"), 1.0, 0.0, 0.0)),
            config=_config(normalize=True),
        )


def test_encoder_returning_wrong_count_is_rejected() -> None:
    """인코더가 입력과 다른 수의 벡터를 주면 짝이 통째로 밀린다."""

    class _ShortEncoder(_FakeEncoder):
        def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
            return super().encode(texts)[:-1]

    with pytest.raises(ValueError, match="다른 수의 벡터"):
        embed_scenes(
            [
                SceneText(scene_index=0, caption="첫째", dialogue=()),
                SceneText(scene_index=1, caption="둘째", dialogue=()),
            ],
            encoder=_ShortEncoder(),
            config=_config(),
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
    """어휘는 계약 §7 의 재현 튜플과 같아야 한다 — `engine`/`engineVersion`.

    `ocr/models.py`·`vlm_metadata/models.py` 도 같은 자리를 그 이름으로 부른다. 여기만
    다른 말을 쓰면 배선할 때 두 어휘가 로그·DB 에 섞인다.
    """
    config = _config()
    result = embed_scenes(
        [SceneText(scene_index=0, caption="광화문", dialogue=())],
        encoder=_FakeEncoder(),
        config=config,
    )
    assert result.config_version == config.version_id
    assert result.engine == "fake"
    assert result.engine_version == "0"
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


# ── 어댑터 ──────────────────────────────────────────────────────────
# sentence-transformers 없이 되는 것만 본다. 가중치를 올리는 경로는 smoke 의 몫이다.


def test_encoder_without_a_model_id_is_unavailable() -> None:
    """모델을 지정하지 않은 것은 구현이 없는 것(`NO_ADAPTER`, 영구)과 다른 사실이다."""
    with pytest.raises(EmbeddingModelUnavailableError, match="NPICK_AI_EMBEDDING_MODEL"):
        SentenceTransformerEncoder("", batch_size=16)


def test_encoder_reports_model_version_before_loading() -> None:
    """로딩 전에도 무엇을 부를지 말할 수 있어야 한다. 실패 기록에 그 값이 필요하다."""
    encoder = SentenceTransformerEncoder("some/model", batch_size=16, revision="v1.2")
    assert encoder.model_version == "some/model@v1.2"
    assert encoder.name == "sentence-transformers"


def test_encoder_defaults_revision_instead_of_leaving_it_blank() -> None:
    """ "지정하지 않았다" 와 "기록을 빠뜨렸다" 는 다르다."""
    encoder = SentenceTransformerEncoder("some/model", batch_size=16, revision="")
    assert encoder.model_version == "some/model@main"


def test_encoding_nothing_does_not_load_weights() -> None:
    """빈 배치로 수 GB 를 올리지 않는다. 모델 이름이 가짜여도 여기서는 실패하지 않는다."""
    encoder = SentenceTransformerEncoder("does/not-exist", batch_size=16)
    assert encoder.encode(()) == ()


def test_pinned_revision_is_kept_as_is() -> None:
    """40자리 SHA 는 이미 고정된 값이라 모델에 물어볼 것이 없다."""
    sha = "55ec6e9358a56d56af759bc8372e970caf8c305f"
    assert _resolve_revision(object(), sha) == sha


def test_unresolvable_revision_falls_back_to_the_declared_value() -> None:
    """SHA 를 알아내지 못해도 기록은 남긴다. 값을 비우면 재현 근거가 사라진다."""
    assert _resolve_revision(object(), "main") == "main"


# ── 어댑터 예외 분류 ────────────────────────────────────────────────
# `encode` 와 **로딩** 두 경로 모두 OOM 을 그대로 올려야 한다. 감싸면
# `jobs/errors.classify` 가 `STAGE_FAILED`(원인이 "가중치를 못 받았다" 로 기록됨)로
# 떨어뜨리는데, 계약 §9.2 는 `OUT_OF_MEMORY`(일시)를 따로 두고 있다.


class _RaisingModel:
    """`encode` 가 정해진 예외를 던지는 가짜 런타임."""

    def __init__(self, exc: BaseException) -> None:
        self._exc = exc

    def encode(self, *args: object, **kwargs: object) -> object:
        raise self._exc


def _loaded_encoder(exc: BaseException) -> SentenceTransformerEncoder:
    """가중치가 이미 올라간 상태의 어댑터. 로딩 경로를 타지 않는다."""
    encoder = SentenceTransformerEncoder("some/model", batch_size=4)
    encoder._model = _RaisingModel(exc)
    encoder._dimension = 4
    encoder._resolved_revision = "main"
    return encoder


def test_encode_lets_host_oom_through() -> None:
    with pytest.raises(MemoryError):
        _loaded_encoder(MemoryError("host")).encode(("가",))


def test_encode_lets_cuda_oom_through() -> None:
    """CUDA OOM 은 `RuntimeError("CUDA out of memory. ...")` 로 온다."""
    with pytest.raises(RuntimeError, match="out of memory"):
        _loaded_encoder(RuntimeError("CUDA out of memory. Tried to allocate 1 GiB")).encode(("가",))


def test_encode_wraps_other_runtime_errors() -> None:
    """OOM 이 아닌 런타임 오류는 어댑터 경계에서 번역한다."""
    with pytest.raises(EmbeddingCallError, match="임베딩 호출이 실패했다"):
        _loaded_encoder(RuntimeError("shape mismatch")).encode(("가",))


def test_encode_wraps_unexpected_exceptions() -> None:
    with pytest.raises(EmbeddingCallError):
        _loaded_encoder(ValueError("무언가")).encode(("가",))


def _fake_sentence_transformers(exc: BaseException) -> ModuleType:
    """`SentenceTransformer(...)` 가 정해진 예외를 던지는 가짜 모듈."""
    module = ModuleType("sentence_transformers")

    def _ctor(*args: object, **kwargs: object) -> object:
        raise exc

    module.SentenceTransformer = _ctor  # type: ignore[attr-defined]
    return module


def test_loading_lets_host_oom_through(monkeypatch: pytest.MonkeyPatch) -> None:
    """가중치를 올리다 난 OOM 도 `encode` 와 같은 정책이어야 한다.

    `SentenceTransformer(..., device="cuda")` 는 생성자에서 가중치를 GPU 로 올린다.
    1.7GB 를 VRAM 에 넣는 바로 그 자리가 정책 밖이면 `encode` 쪽만 고친 것이 무의미하다.
    """
    fake = _fake_sentence_transformers(MemoryError("vram"))
    monkeypatch.setitem(sys.modules, "sentence_transformers", fake)
    with pytest.raises(MemoryError):
        _load("some/model", "main", None, "cuda")


def test_loading_lets_cuda_oom_through(monkeypatch: pytest.MonkeyPatch) -> None:
    exc = RuntimeError("CUDA out of memory. Tried to allocate 2 GiB")
    monkeypatch.setitem(sys.modules, "sentence_transformers", _fake_sentence_transformers(exc))
    with pytest.raises(RuntimeError, match="out of memory"):
        _load("some/model", "main", None, "cuda")


def test_loading_wraps_other_failures(monkeypatch: pytest.MonkeyPatch) -> None:
    """가중치를 못 받은 것은 일시 오류다(계약 §9.2 `MODEL_UNAVAILABLE`)."""
    exc = OSError("연결이 끊겼다")
    monkeypatch.setitem(sys.modules, "sentence_transformers", _fake_sentence_transformers(exc))
    with pytest.raises(EmbeddingModelUnavailableError, match="가중치를 준비하지 못했다"):
        _load("some/model", "main", None, "cuda")


# ── 회귀 방지 ───────────────────────────────────────────────────────


def test_blank_scene_is_skipped_even_with_a_document_prefix() -> None:
    """접두가 있으면 빈 장면이 접두만 임베딩될 수 있다.

    `compose_text` 가 빈 판정을 접두보다 **먼저** 해야 한다. 그렇지 않으면 e5 계열
    (`passage: `)로 갈아 끼웠을 때 모든 빈 장면이 같은 벡터를 받아 서로 최근접이 되고,
    `scene.embedding` 이 nullable 인 설계가 통째로 무너진다.
    """
    encoder = _FakeEncoder()
    result = embed_scenes(
        [SceneText(scene_index=0, caption="   ", dialogue=("", "  "))],
        encoder=encoder,
        config=_config(document_prefix="passage: "),
    )
    assert result.scenes == ()
    assert result.skipped == (0,)
    # 접두만 남은 문자열이 인코더로 가지 않는다.
    assert encoder.calls == []


def test_resolve_revision_finds_the_commit_sha_from_the_loaded_model() -> None:
    """`main` 으로 받은 실행도 실제 가중치의 SHA 를 기록해야 한다.

    sentence-transformers 의 `Transformer.config` 는 `self.model.config`(HF
    `PretrainedConfig`)를 돌려주는 property 라 `_commit_hash` 가 거기 붙는다.
    **비공개 속성에 기대는 경로**이므로 라이브러리가 바뀌면 조용히 깨진다 — 그때
    이 테스트가 잡는다.
    """
    sha = "a1b2c3d4" * 5
    model = SimpleNamespace(
        _modules={"0": SimpleNamespace(config=SimpleNamespace(_commit_hash=sha))}
    )
    assert _resolve_revision(model, "main") == sha
