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
    Transcription,
    get_default_config,
    load_config,
    transcribe_media,
)
from npick_worker.asr.faster_whisper_backend import (
    AsrRuntimeMissingError,
    FasterWhisperEngine,
    model_identifier,
    speech_judgment,
)
from npick_worker.jobs import registry
from npick_worker.jobs.errors import (
    AsrFailedError,
    ModelUnavailableError,
    StageUnavailableError,
    classify,
)
from npick_worker.jobs.models import AsrOutput, AsrUpstream
from npick_worker.jobs.registry import StageContext
from npick_worker.media_errors import MediaUnreadableError

MEDIA = Path("clips/1/source.mp4")
CONFIG_FILE = Path(__file__).parent.parent / "src" / "npick_worker" / "config" / "asr.v1.toml"


class FakeEngine:
    """`AsrEngine` 자리를 대신한다. 무엇을 돌려줄지·무엇을 던질지 테스트가 정한다.

    `speech_detected` 가 구간과 **따로** 있는 것이 요점이다. 진짜 엔진도 그 둘이 따로다 —
    VAD 는 말을 찾았는데 임계에 걸려 문장이 하나도 안 나오는 실행이 있다. 기본값이 참인
    이유는 대부분의 테스트가 구간을 돌려주는 엔진을 세우기 때문이다.
    """

    def __init__(
        self,
        segments: tuple[SpeechSegment, ...] = (),
        error: Exception | None = None,
        speech_detected: bool | None = True,
        speech_audio_seconds: float | None = 12.0,
    ) -> None:
        self._segments = segments
        self._error = error
        self._speech_detected = speech_detected
        self._speech_audio_seconds = speech_audio_seconds
        self.calls = 0

    @property
    def name(self) -> str:
        return "fake-asr"

    @property
    def version(self) -> str:
        return "fake=0.0.0"

    @property
    def model_version(self) -> str:
        return "fake-asr/fake-model@int8"

    def transcribe(self, media_path: Path, config: AsrConfig) -> Transcription:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return Transcription(
            segments=self._segments,
            speech_detected=self._speech_detected,
            speech_audio_seconds=self._speech_audio_seconds,
        )


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


# ── 빈 결과에는 여러 이유가 있다. 그중 하나만 발화 미감지다 ─────────


def test_no_speech_is_reported_only_on_the_engine_judgment() -> None:
    """VAD 가 남긴 오디오가 0 이었다 — 이때만 "말이 없었다" 가 사실이다."""
    result = run(FakeEngine((), speech_detected=False, speech_audio_seconds=0.0))

    assert result.segments == ()
    assert result.no_speech_detected is True
    assert result.vad_speech_ms == 0


def test_empty_transcription_after_vad_kept_audio_is_not_no_speech() -> None:
    """**VAD 는 3 초를 남겼는데 전사가 비었다.**

    엔진이 `no_speech_threshold`·`log_prob_threshold` 에 걸린 구간과 글자가 없는 구간을
    스스로 버리므로 목록은 비어서 나온다. 그것을 무음으로 적으면 "이 3 초에 말이 없었다"
    는 거짓이 정본에 남고, 나중에 되돌릴 근거도 사라진다.
    """
    result = run(FakeEngine((), speech_detected=True, speech_audio_seconds=3.0))

    assert result.segments == ()
    assert result.no_speech_detected is False
    assert result.vad_speech_ms == 3000
    assert AsrOutput.from_result(result).reason_code is None


def test_without_a_judgment_nothing_is_claimed() -> None:
    """VAD 를 끄고 돌면 무음을 말할 근거가 없다. 모르는 것을 적지 않는다."""
    result = run(FakeEngine((), speech_detected=None, speech_audio_seconds=None))

    assert result.no_speech_detected is False
    # 0 으로 바꾸면 "발화 0 초" 라는 없던 판정이 생긴다.
    assert result.vad_speech_ms is None


def test_everything_filtered_is_not_no_speech() -> None:
    """엔진은 말을 찾았는데 계약을 못 지켜 우리가 버린 것이다. 발화 미감지가 아니다."""
    result = run(FakeEngine((speech(1.0, 2.0, text=" "),)))

    assert result.segments == ()
    assert result.no_speech_detected is False
    assert result.raw_segment_count == 1


def test_no_speech_never_rides_along_with_segments() -> None:
    """구간을 실어 보내며 "발화가 없었다" 고 적으면 봉투 하나가 스스로 어긋난다."""
    result = run(FakeEngine((speech(1.0, 2.0),), speech_detected=False))

    assert result.segments != ()
    assert result.no_speech_detected is False


def test_vad_silence_is_the_only_no_speech_judgment() -> None:
    """판정의 근거는 VAD 가 남긴 오디오 길이지 전사의 길이가 아니다."""
    config = get_default_config()

    assert speech_judgment(_Info(0.0), config) == (False, 0.0)
    assert speech_judgment(_Info(3.0), config) == (True, 3.0)


def test_vad_off_produces_no_judgment_at_all() -> None:
    """VAD 를 끄면 `duration_after_vad` 는 클립 전체 길이다. 이걸로 무음을 말할 수 없다."""
    config = get_default_config()
    without_vad = config.model_copy(
        update={"vad": config.vad.model_copy(update={"enabled": False})}
    )

    assert speech_judgment(_Info(120.0), without_vad) == (None, None)


def test_missing_library_field_does_not_invent_a_judgment() -> None:
    """라이브러리가 필드를 바꾸면 판정을 비운다. 조용히 무음으로 떨어지지 않는다."""
    assert speech_judgment(object(), get_default_config()) == (None, None)


class _Info:
    """faster-whisper 의 `TranscriptionInfo` 중 판정에 쓰는 한 필드."""

    def __init__(self, duration_after_vad: float) -> None:
        self.duration_after_vad = duration_after_vad


def test_reason_code_rides_only_on_a_real_no_speech_result() -> None:
    empty = AsrOutput.from_result(run(FakeEngine((), speech_detected=False)))
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
    # 엔진 이름이 이 값 안에 있어야 한다. 정본에 남는 것은 이 문자열 하나다.
    assert outcome.versions.model_version == "fake-asr/fake-model@int8"
    # 프롬프트를 쓰지 않는 단계다. 키는 있고 값만 비어 있어야 한다(계약 §6).
    assert outcome.versions.prompt_version is None
    assert outcome.metrics["segments"] == 1
    assert outcome.metrics["vadEnabled"] is True
    assert outcome.metrics["vadSpeechMs"] == 12000
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
    monkeypatch.setattr(
        registry,
        "_asr_engine",
        lambda: FakeEngine((), speech_detected=False, speech_audio_seconds=0.0),
    )

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

    with pytest.raises(expected) as caught:
        registry.HANDLERS["asr"].run(context(tmp_path))

    # **던져진 그 예외를 분류한다.** 같은 클래스를 새로 만들어 넣으면 단계가 실제로 무엇을
    # 던졌는지는 확인되지 않고, 그 자리에서 코드가 틀려도 테스트는 통과한다.
    assert classify(caught.value, "asr")[0] == code


def test_recognition_failure_is_transient_not_an_empty_result(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """실패를 빈 결과로 바꾸면 "무음이었다" 는 거짓이 정본에 남는다.

    코드는 `ASR_FAILED` 여야 한다. `STAGE_FAILED` 는 "분류를 미룬다" 는 뜻인데 이 실패는
    미룰 것이 없고, 계약 §9.2 가 이 자리에 코드를 하나 주고 있다.
    """
    monkeypatch.setattr(
        registry, "_asr_engine", lambda: FakeEngine(error=AsrCallError("cuda blew up"))
    )

    with pytest.raises(AsrFailedError) as caught:
        registry.HANDLERS["asr"].run(context(tmp_path))

    assert classify(caught.value, "asr") == ("ASR_FAILED", True)


def test_undecodable_audio_is_permanent(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """같은 파일은 다시 열어도 안 열린다. 오디오 트랙 부재도 여기로 온다."""
    monkeypatch.setattr(
        registry, "_asr_engine", lambda: FakeEngine(error=MediaUnreadableError("no audio"))
    )

    with pytest.raises(MediaUnreadableError):
        registry.HANDLERS["asr"].run(context(tmp_path))

    assert classify(MediaUnreadableError("x"), "asr") == ("UNSUPPORTED_MEDIA", False)


def test_model_version_names_the_engine_that_produced_it() -> None:
    """`versions.modelVersion` 은 정본에 한 번 실리는 문자열이다.

    `large-v3-turbo@float16` 만으로는 무엇이 그것을 돌렸는지 알 수 없다 — 같은 가중치를
    여러 런타임이 돌리고 결과가 서로 다르다. `ocr` 도 같은 모양으로 적는다(계약 §4.5).
    """
    assert model_identifier("large-v3-turbo", "float16") == "faster-whisper/large-v3-turbo@float16"


def test_unknown_asr_failure_is_reported_as_asr_failed() -> None:
    """정체 모를 실패는 분류를 미룰 뿐 숨기지 않는다(계약 §9.2)."""
    assert classify(RuntimeError("?"), "asr") == ("ASR_FAILED", True)


def test_unused_candidate_ranges_cannot_kill_the_stage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """`asr` 은 이 값을 세기만 한다. 세기만 하는 값이 단계를 죽이면 안 된다.

    `transcript_selection` 은 아직 없다. 그것이 붙을 때 모양이 조금 어긋나면 —
    `e` 가 float 이거나 길이 0 구간이 섞이면 — 쓰지도 않는 필드 때문에
    `UpstreamOutputInvalidError`(영구 `VALIDATION_ERROR`)로 ASR 전체가 죽는다.
    구간을 실제로 읽는 쪽이 생기면 그때 그 자리에서 검증한다.
    """
    engine = FakeEngine((speech(1.0, 2.0),))
    monkeypatch.setattr(registry, "_asr_engine", lambda: engine)
    ctx = context(tmp_path)
    ctx = StageContext(
        stage=ctx.stage,
        video_path=ctx.video_path,
        storage_key=ctx.storage_key,
        work_dir=ctx.work_dir,
        output_key_prefix=ctx.output_key_prefix,
        upstream={
            "transcript": {
                "asrRequired": True,
                "candidateRanges": [
                    {"s": 20000.5, "e": 25000.5},  # 정수가 아니다
                    {"s": 0, "e": 0},  # 길이 0
                ],
            }
        },
    )

    outcome = registry.HANDLERS["asr"].run(ctx)

    assert outcome.metrics["candidateRanges"] == 2


# ── 폴링 경로는 가중치를 올리지 않는다 ───────────────────────────────


class _FakeWhisperModel:
    """`WhisperModel` 자리. 만들어진 횟수를 세고 무엇을 던질지 테스트가 정한다."""

    built = 0
    error: Exception | None = None

    def __init__(self, *args: object, **kwargs: object) -> None:
        type(self).built += 1
        error = type(self).error
        if error is not None:
            raise error


def _engine_with_fake_weights(monkeypatch: pytest.MonkeyPatch) -> FasterWhisperEngine:
    import faster_whisper

    _FakeWhisperModel.built = 0
    _FakeWhisperModel.error = None
    monkeypatch.setattr(faster_whisper, "WhisperModel", _FakeWhisperModel)
    return FasterWhisperEngine("small", "int8", "cpu", None)


def test_constructing_the_engine_does_not_load_the_weights(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """생성이 싸야 `capability_versions()` 가 폴링마다 수 GB 를 건드리지 않는다."""
    engine = _engine_with_fake_weights(monkeypatch)

    assert _FakeWhisperModel.built == 0
    assert engine.is_loaded is False
    # 버전 축 셋 다 설정에서 온다. 읽는 것만으로 로딩이 일어나면 안 된다.
    assert engine.model_version == "faster-whisper/small@int8"
    assert _FakeWhisperModel.built == 0


def test_warm_up_loads_once_and_flips_is_loaded(monkeypatch: pytest.MonkeyPatch) -> None:
    engine = _engine_with_fake_weights(monkeypatch)

    engine.warm_up()
    engine.warm_up()

    assert _FakeWhisperModel.built == 1
    assert engine.is_loaded is True


def test_asr_is_not_declared_before_the_warm_up_finishes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`capability_versions()` 는 claim long-poll 한 바퀴마다·실패마다 동기로 불린다.

    가중치가 아직 없는 엔진을 선언에 쓰면 그 폴링이 내려받기를 트리거하고, 실패 기록
    경로에서는 lease 를 든 채 heartbeat 가 못 뛴다. `vlm_metadata` 와 같은 가드다.
    """
    engine = _engine_with_fake_weights(monkeypatch)
    monkeypatch.setattr(registry, "_asr_engine", lambda: engine)

    assert "asr" not in registry.capability_versions()
    assert _FakeWhisperModel.built == 0

    engine.warm_up()

    assert "asr" in registry.capability_versions()
    # 선언을 두 번 더 해도 로딩은 한 번뿐이다.
    registry.capability_versions()
    assert _FakeWhisperModel.built == 1


@pytest.mark.parametrize(
    "raised",
    [
        MemoryError("cuda out of memory"),
        RuntimeError("CUDA failed with error out of memory"),
    ],
)
def test_oom_while_loading_is_not_disguised_as_model_unavailable(
    monkeypatch: pytest.MonkeyPatch, raised: Exception
) -> None:
    """VLM 이 먼저 VRAM 을 잡은 파드에서 ASR 가중치를 올리다 나는 실패다.

    같은 원인이 로딩 시점이냐 인식 시점이냐에 따라 다른 코드로 정본에 남으면 안 된다 —
    `transcribe()` 는 이미 이 둘을 그대로 올려보내 `OUT_OF_MEMORY` 로 분류되게 해 뒀다.
    """
    engine = _engine_with_fake_weights(monkeypatch)
    _FakeWhisperModel.error = raised

    with pytest.raises(type(raised)) as caught:
        engine.warm_up()

    assert classify(caught.value, "asr") == ("OUT_OF_MEMORY", True)


def test_other_load_failures_stay_model_unavailable(monkeypatch: pytest.MonkeyPatch) -> None:
    """캐시 볼륨 미마운트·내려받기 실패는 이 클립의 문제가 아니다(계약 §9.2, 일시)."""
    engine = _engine_with_fake_weights(monkeypatch)
    _FakeWhisperModel.error = OSError("HTTP 503")

    with pytest.raises(AsrModelUnavailableError):
        engine.warm_up()
