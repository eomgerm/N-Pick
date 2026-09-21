"""OCR 품질 지표 단위 테스트 (S15P21A501-260).

`eval/ocr/` 의 판정 함수와 사례 등록부만 검증한다. 엔진도 네트워크도 필요 없어
**프로젝트 기본 venv 의 pytest 로 돈다** — 실제 측정(`ocr_bench.py --variant all`)은
모델 가중치와 샘플 keyframe 이 있어야 하고 그건 로컬에서 따로 돌린다.
`tests/test_resolver_eval_metrics.py` 와 같은 구조다.

하네스는 `src/npick_worker` 밖에 있으므로 경로를 직접 얹어 import 한다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

_EVAL = Path(__file__).resolve().parents[1] / "eval" / "ocr"
sys.path.insert(0, str(_EVAL))

import ocr_bench  # noqa: E402
from ocr_bench import (  # noqa: E402
    VARIANTS,
    VARIANTS_BY_NAME,
    Case,
    case_status,
    check_cases,
    frame_key,
    load_cases,
)
from ocr_metrics import (  # noqa: E402
    GoldError,
    GoldFrame,
    GoldLine,
    Observation,
    character_error_rate,
    edit_distance,
    evaluate,
    load_gold,
    load_observations,
    match_frame,
    similarity,
)

CASES_PATH = _EVAL / "cases.json"


def line(text: str, *, legibility: str = "legible", frame: str = "s0000/kf-000000000") -> GoldLine:
    return GoldLine(frame=frame, text=text, kind="signage", legibility=legibility)  # type: ignore[arg-type]


def frame_of(
    *lines: GoldLine, illegible: bool = False, key: str = "s0000/kf-000000000"
) -> GoldFrame:
    return GoldFrame(key=key, has_illegible_text=illegible, lines=lines)


def observed(text: str, confidence: float = 0.9, *, key: str = "s0000/kf-000000000") -> Observation:
    return Observation(
        frame=key, raw_text=text, confidence=confidence, unverified=confidence < 0.70
    )


# ── 비교 규칙 ─────────────────────────────────────────────────────────


def test_comparison_ignores_whitespace_case_and_compatibility_forms() -> None:
    """비교값은 `merge.comparison_text` 그대로다. 이게 갈리면 병합과 평가가 어긋난다."""
    assert similarity("올림픽 G-1년", "올림픽G-1년") == 1.0
    assert similarity("Life Style", "life  style") == 1.0
    # 전각 로마자. NFKC 가 접어 주지 않으면 여기서 갈린다.
    assert similarity("ＫＢＳ", "KBS") == 1.0  # noqa: RUF001


def test_similarity_does_not_depend_on_argument_order() -> None:
    """`SequenceMatcher` 는 방향에 따라 달라진다. 라벨을 왼쪽에 둔다는 이유로 점수가
    바뀌면 안 된다."""
    left, right = "대한장애인컬링협회", "대한장인"
    assert similarity(left, right) == similarity(right, left)


def test_empty_sides() -> None:
    assert similarity("", "") == 1.0
    assert similarity("글자", "") == 0.0


@pytest.mark.parametrize(
    ("reference", "hypothesis", "distance"),
    [
        ("꿀떡", "꿀찍", 1),
        ("", "abc", 3),
        ("abc", "", 3),
        ("페스티벌", "페스티벨", 1),
        ("같다", "같다", 0),
    ],
)
def test_edit_distance(reference: str, hypothesis: str, distance: int) -> None:
    assert edit_distance(reference, hypothesis) == distance


def test_character_error_rate_uses_the_label_as_denominator() -> None:
    # 티켓을 연 사례. 두 글자 중 한 글자가 틀렸다.
    assert character_error_rate("꿀떡", "꿀찍") == pytest.approx(0.5)


def test_character_error_rate_of_an_empty_label() -> None:
    assert character_error_rate("", "") == 0.0
    assert character_error_rate("", "잡음") == 1.0


# ── 판정 ──────────────────────────────────────────────────────────────


def test_a_misread_is_not_counted_as_read() -> None:
    """이 하네스가 존재하는 이유. 재현율은 이걸 정답으로 세고 검색은 놓친다."""
    result = match_frame(frame_of(line("꿀떡")), [observed("꿀찍")])
    assert [entry.outcome for entry in result.lines] == ["misread"]
    assert result.lines[0].cer == pytest.approx(0.5)


def test_exact_match_is_unaffected_by_the_misread_floor() -> None:
    """주 지표에 문턱이 끼지 않는다는 성질. 문서의 감도 표가 기대는 사실이다."""
    for floor in (0.2, 0.5, 0.9):
        result = match_frame(frame_of(line("컬링")), [observed("컬 링")], misread_floor=floor)
        assert result.lines[0].outcome == "exact"


def test_a_pair_below_the_floor_becomes_a_miss_not_a_misread() -> None:
    result = match_frame(frame_of(line("대한컬링경기연맹")), [observed("RG")], misread_floor=0.34)
    assert result.lines[0].outcome == "miss"
    assert result.lines[0].observed is None
    # 그 관측은 어디로도 사라지지 않는다.
    assert [entry.raw_text for entry in result.unmatched] == ["RG"]


def test_partial_labels_absorb_their_observation_without_being_scored() -> None:
    """`samples/README.md`: partial 은 재현율에서 빼고 **오탐으로도 세지 않는다**.

    짝짓기에 참여하지 못하면 그 관측이 오탐 자리에 남아 엔진이 없는 글자를 만든
    것처럼 보인다.
    """
    frame = frame_of(line("컬링"), line("2018", legibility="partial"))
    result = match_frame(frame, [observed("컬링"), observed("2018")])
    assert result.unmatched == ()
    scored = [entry for entry in result.lines if entry.line.scored]
    assert len(scored) == 1


def test_hallucination_is_only_counted_on_a_frame_with_no_text() -> None:
    """`docs/ocr.md` §2 의 '명백한 오탐' 정의. 글자가 있는데 잘못 읽은 것은 사람도
    못 읽는 작은 글자와 구분할 수 없어 따로 세지 않는다."""
    textless = match_frame(frame_of(), [observed("*")])
    assert len(textless.hallucinations) == 1

    illegible = match_frame(frame_of(illegible=True), [observed("*")])
    assert illegible.hallucinations == ()
    assert len(illegible.unmatched) == 1


def test_one_observation_is_not_claimed_by_two_labels() -> None:
    frame = frame_of(line("Best Product pavilion"), line("Beauty Style"))
    result = match_frame(frame, [observed("Beauty Style")])
    outcomes = sorted(entry.outcome for entry in result.lines)
    assert outcomes == ["exact", "miss"]


def test_greedy_matching_prefers_the_closer_pair() -> None:
    """닮은 순서대로 집는다. 먼저 적힌 라벨이 더 닮은 관측을 가로채면 안 된다."""
    frame = frame_of(line("Beauty Style"), line("Life Style"))
    result = match_frame(frame, [observed("Life Style"), observed("Beauty Style")])
    assert all(entry.outcome == "exact" for entry in result.lines)


# ── 합계 ──────────────────────────────────────────────────────────────


def test_summary_separates_silent_misreads() -> None:
    """검증 표시가 붙은 오독과 붙지 않은 오독은 같은 실패가 아니다.

    표시 없는 오독만 그대로 검색 토큰이 된다(`docs/ocr.md` §5 의 unverified 는
    버리는 기준이 아니라 표시다).
    """
    frame = frame_of(line("꿀떡"), line("페스티벌"))
    _, summary = evaluate(
        [frame],
        {frame.key: [observed("꿀찍", 0.95), observed("페스티벨", 0.42)]},
    )
    assert summary.misread == 2
    assert summary.silent_misread == 1


def test_summary_counts_a_miss_as_a_full_character_error() -> None:
    frame = frame_of(line("컬링"))
    _, summary = evaluate([frame], {frame.key: []})
    assert summary.miss == 1
    assert summary.cer == pytest.approx(1.0)
    # 읽어 낸 것이 없으므로 '읽은 것들의 CER' 은 0 으로 둔다 — 분모가 없다.
    assert summary.cer_matched == 0.0


def test_exact_rate_is_the_headline() -> None:
    frame = frame_of(line("컬링"), line("꿀떡"))
    _, summary = evaluate([frame], {frame.key: [observed("컬링"), observed("꿀찍")]})
    assert summary.exact_rate == pytest.approx(0.5)


def test_missing_observations_for_a_labeled_frame_is_an_error() -> None:
    """라벨과 다른 프레임 집합을 재면 누락으로 세어져 **엔진이 나빠진 것처럼 보인다.**"""
    with pytest.raises(GoldError):
        evaluate([frame_of(line("컬링"))], {})


# ── 로드 ──────────────────────────────────────────────────────────────


def test_load_gold_rejects_an_unknown_legibility(tmp_path: Path) -> None:
    path = tmp_path / "gold.json"
    path.write_text(
        json.dumps(
            {"frames": [{"key": "s0000/kf-0", "lines": [{"text": "x", "legibility": "yes"}]}]}
        ),
        encoding="utf-8",
    )
    with pytest.raises(GoldError, match="legibility"):
        load_gold(path)


def test_load_gold_rejects_a_duplicated_keyframe(tmp_path: Path) -> None:
    path = tmp_path / "gold.json"
    path.write_text(
        json.dumps(
            {"frames": [{"key": "s0000/kf-0", "lines": []}, {"key": "s0000/kf-0", "lines": []}]}
        ),
        encoding="utf-8",
    )
    with pytest.raises(GoldError, match="두 번"):
        load_gold(path)


def test_load_gold_reads_the_committed_labels() -> None:
    """저장소에 커밋된 라벨이 이 하네스로 읽히는지. 라벨 형식이 바뀌면 여기서 걸린다."""
    frames = load_gold(
        Path(__file__).resolve().parents[1] / "samples" / "ocr-ground-truth.KNI_02205.json"
    )
    assert len(frames) == 23
    # 37 은 `docs/ocr.md` 의 재현율 분모다. 라벨이 바뀌면 그 표도 다시 재야 한다.
    assert sum(1 for frame in frames for entry in frame.lines if entry.scored) == 37


def test_load_observations_strips_the_jpg_suffix() -> None:
    """라벨의 key 에는 확장자가 없고 `storageKey` 에는 있다."""
    payload = {
        "keyframes": [
            {
                "storageKey": "s0000/kf-000004133.jpg",
                "observations": [
                    {"rawText": "KBS", "confidence": 0.99, "unverified": False},
                ],
            }
        ]
    }
    loaded = load_observations(payload)
    assert set(loaded) == {"s0000/kf-000004133"}


# ── 회귀 사례 ─────────────────────────────────────────────────────────


def test_committed_cases_load() -> None:
    cases = load_cases(CASES_PATH)
    assert {case.id for case in cases} >= {"S15P21A501-260-honeyrice"}


def test_the_reported_case_is_registered_even_without_a_frame() -> None:
    """원본 화면을 못 구했다고 사례를 빼면 다음 사람이 같은 것을 다시 발견한다."""
    case = next(case for case in load_cases(CASES_PATH) if case.id == "S15P21A501-260-honeyrice")
    assert (case.expected, case.observed) == ("꿀떡", "꿀찍")
    assert case.frame is None


def test_load_cases_rejects_a_duplicated_id(tmp_path: Path) -> None:
    path = tmp_path / "cases.json"
    entry = {"id": "dup", "reportedBy": "x", "expected": "a", "observed": "b"}
    path.write_text(json.dumps({"cases": [entry, entry]}), encoding="utf-8")
    with pytest.raises(ValueError, match="두 번"):
        load_cases(path)


def case(expected: str, observed_text: str, *, frame: str | None = "f.jpg") -> Case:
    return Case(
        id="t",
        reported_by="t",
        expected=expected,
        observed=observed_text,
        note="",
        labeled_by="t",
        frame=Path(frame) if frame else None,
    )


def test_case_status_tells_fixed_from_still_wrong() -> None:
    subject = case("꿀떡", "꿀찍")
    assert case_status(subject, [observed("꿀떡")]) == "fixed"
    assert case_status(subject, [observed("꿀찍")]) == "reproduced"
    # 여전히 틀리는데 다르게 틀린다. 고쳐진 것이 아니다.
    assert case_status(subject, [observed("꿀뚝")]) == "changed"
    # 오독이 아니라 누락으로 바뀌었다.
    assert case_status(subject, [observed("전혀 다른 문구")]) == "absent"
    assert case_status(subject, []) == "absent"


# ── 스윕 등록부 ───────────────────────────────────────────────────────


def test_variant_names_are_unique() -> None:
    assert len(VARIANTS_BY_NAME) == len(VARIANTS)


def test_every_variant_builds_a_valid_config() -> None:
    """오타 난 키는 `OcrConfig(extra='forbid')` 가 아니라 `model_copy` 를 그냥 통과한다.

    그러면 "설정을 바꿨는데 결과가 같다" 가 되고 표에 가짜 행이 한 줄 생긴다.
    실제 필드인지 여기서 본다.
    """
    for variant in VARIANTS:
        config = variant.config()
        for key, value in variant.overrides.items():
            assert key in type(config).model_fields, f"{variant.name}: 없는 설정 키 {key!r}"
            assert getattr(config, key) == value
        assert variant.upscale >= 1.0


def test_the_base_variant_does_not_change_the_shipped_config() -> None:
    """비교의 기준이 동봉 설정 그대로여야 표의 첫 줄이 의미를 가진다."""
    from npick_worker.ocr.config import get_default_config

    assert VARIANTS_BY_NAME["base"].config().version_id == get_default_config().version_id


def test_frame_key_matches_the_observation_map() -> None:
    """등록부의 경로와 `load_observations` 의 키가 같은 모양이어야 조회가 된다."""
    assert frame_key(Path("samples/out/c-frames/s0023/kf-000073267.jpg")) == (
        "samples/out/c-frames/s0023/kf-000073267"
    )


def test_a_case_outside_this_run_is_not_reported_as_absent() -> None:
    """**`absent` 와 `not-in-run` 은 다르다.**

    `absent` 는 그 프레임을 읽었는데 문구가 안 나온 것이고, `not-in-run` 은 아예 보지
    않은 것이다. 둘을 같은 값으로 두면 라벨된 클립만 돌린 측정이 다른 클립의 사례를
    "사라졌다" 로 보고한다 — 고쳐지지 않았는데 고쳐진 것처럼 읽힌다.
    """
    subject = case("꿀떡", "꿀찍", frame="other/clip/kf-0.jpg")
    assert check_cases([subject], {}) == [{"id": "t", "status": "not-in-run"}]
    assert check_cases([subject], {"other/clip/kf-0": ()}) == [{"id": "t", "status": "absent"}]


def test_a_case_without_a_frame_is_reported_as_frame_missing() -> None:
    subject = case("꿀떡", "꿀찍", frame=None)
    assert check_cases([subject], {}) == [{"id": "t", "status": "frame-missing"}]


def test_every_committed_case_records_who_labeled_it() -> None:
    """사람이 적지 않은 `expected` 가 사람 검수 라벨로 둔갑하지 않게 한다."""
    for entry in load_cases(CASES_PATH):
        assert entry.labeled_by, f"{entry.id}: labeledBy 가 비어 있다"


def test_committed_case_frames_are_under_the_sample_tree() -> None:
    """`frame` 은 `samples/` 아래를 가리켜야 한다.

    **파일이 있는지는 여기서 보지 않는다.** keyframe 은 영상에서 나온 것이라 커밋되지
    않고(`ai/.gitignore`), 클론 직후에는 없는 것이 정상이다. 경로가 엉뚱한 곳을
    가리키는 것만 잡는다 — 실제 존재 확인은 아래 테스트가 표본이 있을 때만 한다.
    """
    for entry in load_cases(CASES_PATH):
        if entry.frame is not None:
            assert entry.frame.parts[0] == "samples", f"{entry.id}: {entry.frame}"
            assert entry.frame.suffix == ".jpg", f"{entry.id}: {entry.frame}"


def test_committed_case_frames_exist_when_the_samples_are_here() -> None:
    """표본을 가진 개발자에게만 도는 검사. 등록부의 오타를 잡는다."""
    root = Path(__file__).resolve().parents[1]
    checked = 0
    for entry in load_cases(CASES_PATH):
        if entry.frame is None:
            continue
        # `<클립>-frames/s0023/kf-...jpg` 의 클립 디렉터리. 이게 없으면 그 표본을
        # 아직 안 뽑은 것이고, 있는데 파일이 없으면 등록부가 틀린 것이다.
        if not (root / entry.frame.parent.parent).is_dir():
            continue
        assert (root / entry.frame).is_file(), f"{entry.id}: {entry.frame} 가 없다"
        checked += 1
    if not checked:
        pytest.skip("샘플 keyframe 이 로컬에 없다 (영상 산출물은 커밋하지 않는다)")


def test_default_paths_point_at_real_files() -> None:
    """하네스의 기본 경로가 실제 파일을 가리키는가.

    `ai/` 에서 돌리는 것을 전제로 한 상대 경로다. 라벨이나 사례 파일을 옮기면
    측정이 아니라 여기서 먼저 걸려야 한다.
    """
    root = Path(__file__).resolve().parents[1]
    assert (root / ocr_bench.DEFAULT_GOLD).is_file()
    assert (root / ocr_bench.DEFAULT_CASES).is_file()
    assert load_cases(root / ocr_bench.DEFAULT_CASES)
