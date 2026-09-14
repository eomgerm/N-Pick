"""`asr` 단계 — 계약 준수와 "무음/실패/미구현" 구분의 기계 가드.

**모델을 부르지 않는다.** 가짜 엔진으로 경계 뒤를 대신한다. 실제 인식 품질(환각·누락)은
코드로 확인할 수 있는 것이 아니라 표본 실측의 일이고, 그 결과는 `docs/asr.md` §5 에
들어간다. 여기서 지키는 것은 그 실측 결과가 어떻게 나오든 변하지 않아야 하는 것들이다 —
계약이 받는 모양, 시간의 기준, 그리고 빈 결과와 실패를 섞지 않는 것.
"""

import math
from pathlib import Path

import pytest

from npick_worker.asr import (
    AsrCallError,
    AsrConfig,
    AsrModelUnavailableError,
    AsrResult,
    SpeechSegment,
    get_default_config,
    load_config,
    transcribe_media,
)
from npick_worker.asr.faster_whisper_backend import AsrRuntimeMissingError
from npick_worker.jobs import registry
from npick_worker.jobs.errors import (
    ModelUnavailableError,
    StageUnavailableError,
    TransientStageError,
    classify,
)
from npick_worker.jobs.models import AsrOutput, AsrUpstream
from npick_worker.jobs.registry import StageContext
from npick_worker.media_errors import MediaUnreadableError

MEDIA = Path("clips/1/source.mp4")
CONFIG_FILE = Path(__file__).parent.parent / "src" / "npick_worker" / "config" / "asr.v1.toml"


class FakeEngine:
    """`AsrEngine` 자리를 대신한다. 무엇을 돌려줄지·무엇을 던질지 테스트가 정한다."""

    def __init__(
        self, segments: tuple[SpeechSegment, ...] = (), error: Exception | None = None
    ) -> None:
        self._segments = segments
        self._error = error
        self.calls = 0

    @property
    def name(self) -> str:
        return "fake-asr"

    @property
    def version(self) -> str:
        return "fake=0.0.0"

    @property
    def model_version(self) -> str:
        return "fake-model@int8"

    def transcribe(self, media_path: Path, config: AsrConfig) -> tuple[SpeechSegment, ...]:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return self._segments


def speech(
    start: float, end: float, text: str = "앵커 리포트입니다", logprob: float = -0.2
) -> SpeechSegment:
    return SpeechSegment(start=start, end=end, text=text, avg_logprob=logprob, no_speech_prob=0.01)


def run(engine: FakeEngine) -> AsrResult:
    return transcribe_media(MEDIA, engine, get_default_config())


# ── 설정 ─────────────────────────────────────────────────────────────


def test_shipped_config_keeps_vad_on() -> None:
    """`enabled` 는 실험용 키다. 동봉 기본값이 false 로 넘어가면 티켓 요구가 조용히 깨진다."""
    assert get_default_config().vad.enabled is True


def test_config_version_covers_every_value() -> None:
    """toml 의 어느 값을 바꿔도 `config_version` 이 달라져야 §7.2 기록이 사실이 된다."""
    config = get_default_config()
    changed = config.model_copy(update={"vad": config.vad.model_copy(update={"threshold": 0.9})})

    assert changed.version_id != config.version_id
    assert config.version_id.startswith("asr-config/v1:")


def test_config_file_name_and_schema_must_agree(tmp_path: Path) -> None:
    """v2 로 복사하고 schema 를 안 고치면 두 버전이 갈린다. 읽을 때 막는다."""
    copied = tmp_path / "asr.v2.toml"
    copied.write_text(CONFIG_FILE.read_text(encoding="utf-8"), encoding="utf-8")

    with pytest.raises(ValueError, match="asr-config/v2"):
        load_config(copied)


def test_unknown_config_key_is_rejected(tmp_path: Path) -> None:
    """오타가 조용히 무시되면 `config_version` 은 바뀌는데 동작은 그대로다."""
    broken = tmp_path / "asr.experiment.toml"
    broken.write_text(
        CONFIG_FILE.read_text(encoding="utf-8") + "\nunknown_key = 1\n", encoding="utf-8"
    )

    with pytest.raises(ValueError):
        load_config(broken)


# ── 구간 변환 ────────────────────────────────────────────────────────


def test_segments_carry_original_timeline_milliseconds() -> None:
    result = run(FakeEngine((speech(12.0, 15.32), speech(20.5006, 21.0))))

    assert [(s.start_ms, s.end_ms) for s in result.segments] == [(12000, 15320), (20501, 21000)]
    assert [s.index for s in result.segments] == [0, 1]


def test_negative_start_is_clamped() -> None:
    """VAD 여유(`speech_pad_ms`)가 0 앞으로 넘어가도 계약의 `s >= 0` 을 지킨다."""
    assert run(FakeEngine((speech(-0.2, 1.0),))).segments[0].start_ms == 0


def test_blank_text_is_dropped_and_counted() -> None:
    """계약은 비어 있지 않은 `t` 를 요구한다. 공백만 남은 구간을 보내면 결과 전체가 거부된다."""
    result = run(FakeEngine((speech(1.0, 2.0, text="   "), speech(3.0, 4.0))))

    assert len(result.segments) == 1
    assert result.dropped_blank == 1
    assert result.segments[0].index == 0  # 연번은 남은 것 기준으로 이어진다


def test_degenerate_interval_is_dropped_not_padded() -> None:
    """계약은 `e > s` 를 요구한다. 1ms 를 지어내 통과시키지 않는다."""
    result = run(FakeEngine((speech(5.0001, 5.0002),)))

    assert result.segments == ()
    assert result.dropped_degenerate == 1


def test_confidence_is_the_clamped_exponential_of_avg_logprob() -> None:
    result = run(FakeEngine((speech(1.0, 2.0, logprob=-0.5),)))

    assert result.segments[0].confidence == round(math.exp(-0.5), 4)
    assert 0.0 <= result.segments[0].confidence <= 1.0


def test_positive_logprob_cannot_exceed_one() -> None:
    assert run(FakeEngine((speech(1.0, 2.0, logprob=0.5),))).segments[0].confidence == 1.0


# ── 발화 미감지와 "걸러서 비었다" 는 다른 사실이다 ──────────────────


def test_no_speech_is_reported_only_when_the_engine_found_nothing() -> None:
    result = run(FakeEngine(()))

    assert result.segments == ()
    assert result.no_speech_detected is True
    assert result.raw_segment_count == 0


def test_everything_filtered_is_not_no_speech() -> None:
    """엔진은 말을 찾았는데 계약을 못 지켜 버린 것이다. 발화 미감지로 적으면 정본이 거짓이 된다."""
    result = run(FakeEngine((speech(1.0, 2.0, text=" "),)))

    assert result.segments == ()
    assert result.no_speech_detected is False
    assert result.raw_segment_count == 1


def test_reason_code_rides_only_on_a_real_no_speech_result() -> None:
    empty = AsrOutput.from_result(run(FakeEngine(())))
    found = AsrOutput.from_result(run(FakeEngine((speech(1.0, 2.0),))))

    assert empty.reason_code == "NO_SPEECH_DETECTED"
    assert found.reason_code is None
    # 발화를 찾은 실행의 payload 에는 키 자체가 없어야 한다 — BE 의 사유 allowlist 에
    # `null` 은 없고, "빈 segments 만으로 사유를 만들지 않는다" 는 구분이 흐려진다.
    assert "reasonCode" not in found.model_dump(by_alias=True, exclude_none=True)


# ── 와이어 모양 (계약 §4.5) ──────────────────────────────────────────


def test_wire_segments_match_the_transcript_snapshot_vocabulary() -> None:
    payload = AsrOutput.from_result(run(FakeEngine((speech(12.0, 15.32),)))).model_dump(
        by_alias=True, mode="json", exclude_none=True
    )

    assert payload == {
        "segments": [
            {
                "segmentId": "asr-0",
                "s": 12000,
                "e": 15320,
                "t": "앵커 리포트입니다",
                "sourceDetail": "asr",
                "confidence": round(math.exp(-0.2), 4),
            }
        ]
    }


def test_segment_ids_are_unique_within_the_snapshot() -> None:
    result = run(FakeEngine(tuple(speech(float(i), float(i) + 0.5) for i in range(5))))
    ids = [f"asr-{segment.index}" for segment in result.segments]

    assert len(set(ids)) == len(ids)


def test_upstream_selection_is_optional() -> None:
    """선택 단계 없이 도는 run 에서도 이 단계는 배정될 수 있다(계약 §4.5)."""
    assert AsrUpstream.model_validate({}).transcript is None

    parsed = AsrUpstream.model_validate(
        {
            "transcript": {
                "segmentsArtifact": {"kind": "transcript_segments", "storageKey": "x"},
                "asrRequired": True,
                "candidateRanges": [{"s": 20000, "e": 25000}],
                "reasonCode": "UNCOVERED_RANGES",
            }
        }
    )
    assert parsed.transcript is not None
    assert parsed.transcript.asr_required is True
    assert [(r.s, r.e) for r in parsed.transcript.candidate_ranges] == [(20000, 25000)]


# ── 잡 레이어 배선 ───────────────────────────────────────────────────


def context(tmp_path: Path) -> StageContext:
    return StageContext(
        stage="asr",
        video_path=tmp_path / "source.mp4",
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/asr/a1/",
        upstream={
            "transcript": {
                "asrRequired": True,
                "candidateRanges": [{"s": 20000, "e": 25000}],
                "reasonCode": "UNCOVERED_RANGES",
            }
        },
    )


def test_stage_reports_versions_and_metrics(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    engine = FakeEngine((speech(12.0, 15.32),))
    monkeypatch.setattr(registry, "_asr_engine", lambda: engine)

    outcome = registry.HANDLERS["asr"].run(context(tmp_path))

    assert outcome.output["segments"][0]["segmentId"] == "asr-0"
    assert outcome.versions.stage_version.startswith("npick.stage.asr/v1:")
    assert outcome.versions.output_schema_version == "npick.stage.asr.output/v1"
    assert outcome.versions.model_version == "fake-model@int8"
    # 프롬프트를 쓰지 않는 단계다. 키는 있고 값만 비어 있어야 한다(계약 §6).
    assert outcome.versions.prompt_version is None
    assert outcome.metrics["segments"] == 1
    assert outcome.metrics["vadEnabled"] is True
    assert outcome.metrics["candidateRanges"] == 1
    assert outcome.metrics["asrRequired"] is True
    # 산출물을 올리지 않는다. 대사는 파일이 아니라 payload 다.
    assert outcome.uploads == ()
    assert outcome.artifacts == ()


def test_declared_version_matches_the_produced_one(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """선언과 실제가 갈리면 BE 의 배정 필터가 하는 일이 없어진다(계약 §7)."""
    monkeypatch.setattr(registry, "_asr_engine", lambda: FakeEngine((speech(1.0, 2.0),)))

    declared = registry.capability_versions()["asr"]
    produced = registry.HANDLERS["asr"].run(context(tmp_path)).versions.stage_version

    assert declared == produced


def test_stage_runs_even_when_selection_said_it_was_not_needed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """배정이 곧 실행 지시다. 무엇을 채택할지는 우선순위를 아는 하류가 정한다(계약 §4.5)."""
    engine = FakeEngine((speech(1.0, 2.0),))
    monkeypatch.setattr(registry, "_asr_engine", lambda: engine)
    ctx = context(tmp_path)
    ctx = StageContext(
        stage=ctx.stage,
        video_path=ctx.video_path,
        storage_key=ctx.storage_key,
        work_dir=ctx.work_dir,
        output_key_prefix=ctx.output_key_prefix,
        upstream={"transcript": {"asrRequired": False, "reasonCode": "SUBTITLE_COVERED"}},
    )

    outcome = registry.HANDLERS["asr"].run(ctx)

    assert engine.calls == 1
    assert outcome.metrics["asrRequired"] is False


def test_empty_result_is_a_success_with_a_reason(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """발화 미감지는 정상 종료다. 실패로 바꾸지 않는다."""
    monkeypatch.setattr(registry, "_asr_engine", lambda: FakeEngine(()))

    outcome = registry.HANDLERS["asr"].run(context(tmp_path))

    assert outcome.output == {"segments": [], "reasonCode": "NO_SPEECH_DETECTED"}
    # `status=succeeded` 봉투가 요구하는 "비어 있지 않은 output" 을 만족한다.
    assert outcome.output


@pytest.mark.parametrize(
    ("raised", "expected", "code"),
    [
        # 라이브러리가 없다 = 이 이미지에 구현이 없다. 재시도가 고칠 수 없다.
        (AsrRuntimeMissingError("no lib"), StageUnavailableError, "NO_ADAPTER"),
        # 가중치가 없다 = 지금 없다. 다른 파드나 다음 시도에서 성공할 수 있다.
        (AsrModelUnavailableError("no weights"), ModelUnavailableError, "MODEL_UNAVAILABLE"),
    ],
)
def test_engine_failures_are_split_by_what_retrying_can_fix(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    raised: Exception,
    expected: type[Exception],
    code: str,
) -> None:
    def explode() -> FakeEngine:
        raise raised

    # `_asr_engine` 을 그대로 두고 그 **안쪽**을 바꾼다. 번역이 실제로 거기서 일어나는지가
    # 이 테스트의 관심사이므로, 함수를 통째로 갈면 확인하려던 것이 사라진다.
    monkeypatch.setattr("npick_worker.asr.faster_whisper_backend.shared_engine", explode)

    with pytest.raises(expected):
        registry.HANDLERS["asr"].run(context(tmp_path))

    assert classify(expected("x"), "asr")[0] == code


def test_recognition_failure_is_transient_not_an_empty_result(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """실패를 빈 결과로 바꾸면 "무음이었다" 는 거짓이 정본에 남는다."""
    monkeypatch.setattr(
        registry, "_asr_engine", lambda: FakeEngine(error=AsrCallError("cuda blew up"))
    )

    with pytest.raises(TransientStageError):
        registry.HANDLERS["asr"].run(context(tmp_path))

    assert classify(TransientStageError("x"), "asr") == ("STAGE_FAILED", True)


def test_undecodable_audio_is_permanent(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """같은 파일은 다시 열어도 안 열린다. 오디오 트랙 부재도 여기로 온다."""
    monkeypatch.setattr(
        registry, "_asr_engine", lambda: FakeEngine(error=MediaUnreadableError("no audio"))
    )

    with pytest.raises(MediaUnreadableError):
        registry.HANDLERS["asr"].run(context(tmp_path))

    assert classify(MediaUnreadableError("x"), "asr") == ("UNSUPPORTED_MEDIA", False)


def test_unknown_asr_failure_is_reported_as_asr_failed() -> None:
    """정체 모를 실패는 분류를 미룰 뿐 숨기지 않는다(계약 §9.2)."""
    assert classify(RuntimeError("?"), "asr") == ("ASR_FAILED", True)
