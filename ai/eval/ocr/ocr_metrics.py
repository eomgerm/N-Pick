"""OCR 화면 글자 품질 지표 (S15P21A501-260).

`ocr_bench.py` 가 쓰는 순수 함수만 둔다. 엔진도 네트워크도 부르지 않아 프로젝트 기본
venv 의 pytest 로 검증된다(`tests/test_ocr_eval_metrics.py`). `eval/query_resolver/metrics.py`
와 같은 구조다(이름이 `metrics.py` 가 아닌 이유는 `README.md` 끝에 있다).

## 왜 재현율만으로는 부족한가

`docs/ocr.md` §2·§5 의 "재현율 27/37" 은 **사람이 출력을 보고 '이건 읽어 낸 것'으로 센**
수치다. 그 세는 방식에서는 `올림픽 G-1년 페스티벌` → `-립픽G-1년페스티벌` 이 정답 쪽에
들어간다. 사람 눈에는 읽어 낸 것이 맞다.

**검색에는 아니다.** 그래서 이 모듈은 문구 하나의 결과를 셋으로 가른다.

- `exact` — 비교값이 같다.
- `misread` — 그 문구를 읽기는 했는데 글자가 다르다. 재현율은 이걸 정답으로 센다.
- `miss` — 그 문구에 대응하는 관측이 없다.

## 그러나 `exact` 는 검색 성공률이 아니다

**이 모듈의 초판은 "정확일치만 검색으로 이어진다" 로 적었고 그것은 틀렸다.** 검색이
맞대는 것은 원문이 아니라 **토큰**이다. 색인 쪽은 `korean_tokens.index_tokens` 가
만들고(`npick_worker/ocr/postprocess.py`), 질의 쪽도 같은 함수를 거치며, BE 는 그
토큰 집합으로 조회한다(`WordSceneCandidateAdapter` 의 `paradedb.term_set('tokens', …)`).
그래서 글자가 틀려도 토큰이 겹치면 검색은 된다.

| 라벨 → 관측 | 토큰 | 검색 |
| --- | --- | --- |
| `지폐를 넣고` → `지패를 넣고` | `(지폐, 넣)` → `(지패, 넣)` | `넣` 이 겹친다 |
| `버튼을 꾹 누르면` → `버른을 록누르면` | `(버튼, 누르)` → `(버른, 록, 누르)` | `누르` 가 겹친다 |
| `트리가 반짝반짝!` → `트리가 반적반데!` | `(트리,)` → `(트리가, 반적반)` | 겹치지 않는다 |
| `꿀꺽` → `꿀찍` | `()` → `(꿀,)` | **라벨 쪽이 비어 있다** |

네 줄 모두 판정은 `misread` 다. 그런데 검색 결과는 셋으로 갈린다.

마지막 줄이 이 티켓의 대표 사례다. `꿀꺽` 은 Kiwi 가 내용어로 보지 않아 색인 토큰이
**빈 튜플**이고, 질의 `꿀꺽` 도 `query_normalization` 이 "내용어가 없다" 로 거부한다.
**정확히 읽어도 검색되지 않는다** — 이 한 건에 한해 OCR 은 병목이 아니다.

그래서 `exact` 는 **문자 정확도**로만 읽는다. 검색에 닿는가는 따로 센다(`Reach`).

## 비교 규칙은 새로 만들지 않는다

`comparison_text` 는 `npick_worker.ocr.merge` 의 것을 그대로 쓴다(NFKC·casefold·공백
제거). 병합이 "같은 문구인가" 를 판정하는 규칙과 평가가 "맞게 읽었는가" 를 판정하는
규칙이 다르면, 한쪽을 고칠 때 다른 쪽이 조용히 어긋난다.

**원문은 건드리지 않는다.** 여기서 만드는 정규화 문자열은 판정용이고 저장되는 값이
아니다(`docs/ocr.md` §3 의 "raw_text 는 손대지 않는다").
"""

from __future__ import annotations

import json
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any, Final, Literal

from npick_worker.korean_tokens import index_tokens
from npick_worker.ocr.merge import comparison_text

#: `samples/README.md` 가 정한 세 값. 재현율 분모는 `legible` 뿐이다.
Legibility = Literal["legible", "partial", "illegible"]

#: 문구 하나의 판정. **문자 기준이다.**
Outcome = Literal["exact", "misread", "miss"]

#: 그 문구로 들어온 질의가 이 관측에 닿는가. **토큰 기준이다.**
#:
#: - `reachable` — 라벨 토큰과 관측 토큰이 하나라도 겹친다. 글자가 틀려도 검색은 된다.
#: - `lost` — 라벨 토큰은 있는데 겹치는 것이 없다. 그 문구로는 못 찾는다.
#: - `unsearchable` — 라벨 쪽 토큰이 비어 있다. 무엇을 읽든 그 문구로는 못 찾고,
#:   질의도 `query_normalization` 에서 거부된다. **OCR 로 고칠 수 있는 자리가 아니다.**
#:
#: **낙관적인 수다.** 라벨 문구 *전체*로 물었을 때를 잰다. 사람은 낱말로 검색한다 —
#: `지폐를 넣고` → `지패를 넣고` 는 `넣` 이 겹쳐 `reachable` 이지만 질의 `지폐` 로는
#: 0 건이다. "검색되는 비율" 의 **상한**으로 읽는다(`README.md` 의 한계).
Reach = Literal["reachable", "lost", "unsearchable"]

#: 이 값 미만으로 닮은 쌍은 "그 문구를 잘못 읽었다" 로 보지 않고 누락으로 센다.
#:
#: **주 지표 `exact` 는 이 값과 무관하다.** 비교값이 같아야 `exact` 이고 그 판정에
#: 문턱이 끼지 않는다. 이 값이 가르는 것은 실패를 `misread` 로 셀지 `miss` 로 셀지
#: 뿐이다 — 어느 쪽이든 그 문구로는 검색되지 않는다.
#:
#: 그래도 임의로 고르지 않았다. 샘플 `KNI_02205` 에서 0.20~0.34 는 판정이 완전히
#: 같고 0.40 부터 경계 쌍이 누락 쪽으로 넘어간다(`README.md` 의 감도 표). **더
#: 낮춰도 달라지지 않는 구간의 위쪽 끝**을 골랐다 — 관대함을 최소로 하면서 값이
#: 조금 흔들려도 표가 바뀌지 않는 자리다.
DEFAULT_MISREAD_FLOOR: Final[float] = 0.34

#: 부동소수 비교. `ratio()` 가 1.0 을 정확히 돌려주지 않는 경우를 위한 여유다.
_EXACT: Final[float] = 1.0 - 1e-9


@dataclass(frozen=True, slots=True)
class GoldLine:
    """사람이 keyframe 을 보고 적은 문구 하나."""

    frame: str
    text: str
    kind: str
    legibility: Legibility

    @property
    def scored(self) -> bool:
        """재현율·오독률의 분모에 들어가는가. `legible` 만 들어간다."""
        return self.legibility == "legible"


@dataclass(frozen=True, slots=True)
class GoldFrame:
    key: str
    has_illegible_text: bool
    lines: tuple[GoldLine, ...]

    @property
    def is_textless(self) -> bool:
        """화면에 글자가 아예 없는 프레임.

        여기서 나온 출력만 **명백한 오탐**으로 센다(`docs/ocr.md` §2 의 같은 정의).
        글자가 있는데 잘못 읽은 것은 사람도 못 읽는 작은 글자와 구분할 수 없다.
        """
        return not self.lines and not self.has_illegible_text


@dataclass(frozen=True, slots=True)
class Observation:
    """엔진이 읽은 것 하나. `ocr.json` 의 관측에서 평가에 필요한 것만 옮긴다."""

    frame: str
    raw_text: str
    confidence: float
    unverified: bool


@dataclass(frozen=True, slots=True)
class LineResult:
    """문구 하나의 판정과 근거."""

    line: GoldLine
    outcome: Outcome
    #: `miss` 면 None.
    observed: Observation | None
    similarity: float
    #: 라벨 문구 기준 문자 오류율. `miss` 는 1.0 이다 — 한 글자도 못 얻었다.
    cer: float
    #: 라벨 문구의 색인 토큰. 워커가 `ocr_observation.tokens` 를 만드는 함수와 같다.
    gold_tokens: tuple[str, ...] = ()
    #: 읽어 낸 문구의 색인 토큰. `miss` 면 빈 튜플이다.
    observed_tokens: tuple[str, ...] = ()

    @property
    def reach(self) -> Reach:
        """그 문구로 들어온 질의가 이 관측에 닿는가. 모듈 docstring 의 표 참조."""
        if not self.gold_tokens:
            return "unsearchable"
        return "reachable" if set(self.gold_tokens) & set(self.observed_tokens) else "lost"

    @property
    def unflagged(self) -> bool:
        """`min_confidence` 를 넘겨 검증 표시가 붙지 않은 오독인가.

        **이 값은 색인 여부와 무관하다.** `unverified` 는 `ocr_observation` 에 담을
        칸이 없고(`docs/contracts/job-api.md` §4.3.2 "`unverified` 는 담을 컬럼이
        없다"), BE 는 미달 관측도 전부 저장하며 검색도 confidence 로 거르지 않는다
        (같은 문서: "BE 는 `unverified: true` 인 행을 거절하면 안 된다"). 표시는
        `tag_evidence.verification_status` 를 정할 때만 쓰인다.

        그래서 이 수는 **"검색에 새는 오독"이 아니라 "검증 표시조차 안 붙는 오독"**
        이다. 색인에 들어가는 잘못된 토큰은 `FrameResult.stray_tokens` 가 센다.
        """
        return (
            self.outcome == "misread" and self.observed is not None and not self.observed.unverified
        )


@dataclass(frozen=True, slots=True)
class FrameResult:
    frame: GoldFrame
    lines: tuple[LineResult, ...]
    #: 어느 라벨에도 붙지 못한 관측.
    unmatched: tuple[Observation, ...]
    #: 이 장의 화면 글자에서 나오는 토큰 전체.
    #:
    #: `partial`·`illegible` 라벨도 넣는다 — 점수에서 빼는 것과 "화면에 있는 글자냐"
    #: 는 다른 질문이고, 색인 오염을 세는 데 쓰는 것은 뒤엣것이다.
    gold_tokens: tuple[str, ...] = ()
    #: 이 장의 관측이 색인에 넣는 토큰 중 화면에 근거가 없는 것.
    #:
    #: **오독이 실제로 검색을 오염시키는 양이다.** 짝지어진 관측이든 오탐이든
    #: `unverified` 든 가리지 않는다 — BE 는 모든 관측의 토큰을 저장한다.
    stray_tokens: tuple[str, ...] = ()

    @property
    def hallucinations(self) -> tuple[Observation, ...]:
        """글자가 없는 프레임에서 나온 출력."""
        return self.unmatched if self.frame.is_textless else ()


@dataclass(frozen=True, slots=True)
class Summary:
    """한 번의 측정 전체. `results/*.json` 에 이 모양으로 쓴다."""

    legible: int
    exact: int
    misread: int
    miss: int
    #: 검증 표시가 붙지 않은 오독. **색인 여부와 무관하다**(`LineResult.unflagged`).
    unflagged_misread: int
    #: 라벨 토큰이 비어 있어 무엇을 읽든 그 문구로는 못 찾는 문구.
    unsearchable: int
    #: 라벨 토큰이 있고 관측과 겹치는 문구. **글자가 틀려도 여기 들어올 수 있다.**
    reachable: int
    #: 라벨 토큰이 있는데 겹치는 것이 없는 문구. 그 문구로는 0 건이다.
    lost: int
    #: 화면에 근거가 없는데 색인에 들어가는 토큰(중복 제외).
    stray_tokens: int
    #: 글자 없는 프레임에서 나온 출력.
    hallucination: int
    #: 라벨에 붙지 못한 관측 전체(오탐 포함). 판정하지 않고 세기만 한다.
    unmatched: int
    observations: int
    #: 라벨 문구 전체 기준 문자 오류율. 누락은 전부 오류로 센다.
    cer: float
    #: 읽어 내기는 한 문구들만의 문자 오류율.
    cer_matched: float

    @property
    def exact_rate(self) -> float:
        """글자까지 맞은 비율. **문자 정확도이지 검색 성공률이 아니다**(모듈 docstring)."""
        return self.exact / self.legible if self.legible else 0.0

    @property
    def searchable(self) -> int:
        """애초에 그 문구로 검색이 가능한 라벨의 수. `reach_rate` 의 분모다."""
        return self.reachable + self.lost

    @property
    def reach_rate(self) -> float:
        """**검색에 닿는 비율.** 검색 관점의 주 지표다.

        분모에서 `unsearchable` 을 뺀다 — 라벨 토큰이 비어 있는 문구는 OCR 이
        무엇을 해도 결과가 같아서, 분모에 넣으면 이 단계를 고쳐도 오르지 않는
        비율이 된다.
        """
        return self.reachable / self.searchable if self.searchable else 0.0

    def to_json(self) -> dict[str, Any]:
        return {
            "legible": self.legible,
            "exact": self.exact,
            "misread": self.misread,
            "miss": self.miss,
            "unflaggedMisread": self.unflagged_misread,
            "unsearchable": self.unsearchable,
            "reachable": self.reachable,
            "lost": self.lost,
            "strayTokens": self.stray_tokens,
            "hallucination": self.hallucination,
            "unmatched": self.unmatched,
            "observations": self.observations,
            "exactRate": round(self.exact_rate, 4),
            "searchable": self.searchable,
            "reachRate": round(self.reach_rate, 4),
            "cer": round(self.cer, 4),
            "cerMatched": round(self.cer_matched, 4),
        }


# ── 로드 ──────────────────────────────────────────────────────────────


class GoldError(ValueError):
    """라벨 파일이 규약과 다르다.

    로드 단계에서 거부한다. 잘못된 라벨은 조용히 "엔진이 틀렸다" 로 둔갑하고,
    이 하네스의 수치가 문서의 표로 들어가기 때문이다(FRD §11).
    """


def load_gold(path: Path) -> tuple[GoldFrame, ...]:
    """`samples/ocr-ground-truth.<클립>.json` 을 읽는다.

    형식은 `samples/README.md` 의 "OCR ground truth" 절이 정본이다.
    """
    raw: Any = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, Mapping) or "frames" not in raw:
        msg = f"ground truth 형식이 아니다: {path}"
        raise GoldError(msg)

    frames: list[GoldFrame] = []
    seen: set[str] = set()
    for entry in raw["frames"]:
        key = str(entry["key"])
        if key in seen:
            msg = f"같은 keyframe 이 두 번 있다: {key}"
            raise GoldError(msg)
        seen.add(key)
        lines: list[GoldLine] = []
        for line in entry.get("lines", ()):
            legibility = line["legibility"]
            if legibility not in ("legible", "partial", "illegible"):
                msg = f"알 수 없는 legibility: {legibility!r} ({key})"
                raise GoldError(msg)
            text = str(line["text"])
            if not text.strip():
                msg = f"빈 라벨 문구가 있다: {key}"
                raise GoldError(msg)
            lines.append(
                GoldLine(
                    frame=key,
                    text=text,
                    kind=str(line.get("kind", "")),
                    legibility=legibility,
                )
            )
        frames.append(
            GoldFrame(
                key=key,
                has_illegible_text=bool(entry.get("hasIllegibleText", False)),
                lines=tuple(lines),
            )
        )
    if not frames:
        msg = f"라벨된 프레임이 없다: {path}"
        raise GoldError(msg)
    return tuple(frames)


def load_observations(payload: Mapping[str, Any]) -> dict[str, tuple[Observation, ...]]:
    """`ocr.report` 가 쓴 `ocr.json` 에서 관측을 꺼낸다.

    라벨의 `key` 는 확장자가 없고(`s0000/kf-000004133`) `storageKey` 에는 `.jpg` 가
    붙는다. 여기서 한 번 맞춰 둔다.
    """
    by_frame: dict[str, tuple[Observation, ...]] = {}
    for keyframe in payload["keyframes"]:
        key = str(keyframe["storageKey"]).removesuffix(".jpg")
        by_frame[key] = tuple(
            Observation(
                frame=key,
                raw_text=str(obs["rawText"]),
                confidence=float(obs["confidence"]),
                unverified=bool(obs["unverified"]),
            )
            for obs in keyframe["observations"]
        )
    return by_frame


# ── 판정 ──────────────────────────────────────────────────────────────


def similarity(left: str, right: str) -> float:
    """비교값끼리의 닮은 정도. 0~1.

    `merge._compatible` 과 같이 **양방향 중 낮은 쪽**을 쓴다. `SequenceMatcher` 는
    인자 순서에 따라 값이 달라지고, 평가에서 그 차이를 그대로 두면 라벨과 관측 중
    어느 쪽을 먼저 넣었는지가 점수를 바꾼다.
    """
    a, b = comparison_text(left), comparison_text(right)
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return min(
        SequenceMatcher(None, a, b, autojunk=False).ratio(),
        SequenceMatcher(None, b, a, autojunk=False).ratio(),
    )


def edit_distance(reference: str, hypothesis: str) -> int:
    """Levenshtein 거리. 외부 패키지를 끌어오지 않는다 — 문구가 짧아 그럴 이유가 없다."""
    previous = list(range(len(hypothesis) + 1))
    for i, ref_char in enumerate(reference, start=1):
        current = [i]
        for j, hyp_char in enumerate(hypothesis, start=1):
            current.append(
                min(
                    previous[j] + 1,  # 삭제
                    current[j - 1] + 1,  # 삽입
                    previous[j - 1] + (ref_char != hyp_char),  # 치환
                )
            )
        previous = current
    return previous[-1]


def character_error_rate(reference: str, hypothesis: str) -> float:
    """비교값 기준 CER. 라벨이 분모다."""
    ref, hyp = comparison_text(reference), comparison_text(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def match_frame(
    frame: GoldFrame,
    observations: Sequence[Observation],
    *,
    misread_floor: float = DEFAULT_MISREAD_FLOOR,
) -> FrameResult:
    """한 장의 라벨과 관측을 짝지어 문구마다 판정한다.

    **`partial`·`illegible` 라벨도 짝짓기에는 참여한다.** 점수에는 들어가지 않지만
    자기 관측을 데려가지 않으면 그 관측이 오탐 자리에 남는다 — `samples/README.md` 가
    "재현율에서 제외하고 오탐으로도 세지 않는다" 로 정한 것을 지키는 자리다.

    짝짓기는 닮은 순서대로 집는 탐욕법이다. 문구가 한 프레임에 열 몇 개라 최적
    매칭과 갈릴 일이 드물고, 갈리는 쌍은 어차피 `misread_floor` 근처의 쓰레기다.
    """
    pairs = sorted(
        (
            (-similarity(line.text, obs.raw_text), line_index, obs_index)
            for line_index, line in enumerate(frame.lines)
            for obs_index, obs in enumerate(observations)
        ),
        key=lambda item: (item[0], item[1], item[2]),
    )

    taken_lines: dict[int, tuple[int, float]] = {}
    taken_obs: set[int] = set()
    for negative, line_index, obs_index in pairs:
        score = -negative
        if score < misread_floor:
            break
        if line_index in taken_lines or obs_index in taken_obs:
            continue
        taken_lines[line_index] = (obs_index, score)
        taken_obs.add(obs_index)

    results: list[LineResult] = []
    for line_index, line in enumerate(frame.lines):
        matched = taken_lines.get(line_index)
        if matched is None:
            results.append(
                LineResult(
                    line=line,
                    outcome="miss",
                    observed=None,
                    similarity=0.0,
                    cer=1.0,
                    gold_tokens=index_tokens(line.text),
                )
            )
            continue
        obs_index, score = matched
        observed = observations[obs_index]
        outcome: Outcome = "exact" if score >= _EXACT else "misread"
        results.append(
            LineResult(
                line=line,
                outcome=outcome,
                observed=observed,
                similarity=score,
                cer=character_error_rate(line.text, observed.raw_text),
                gold_tokens=index_tokens(line.text),
                observed_tokens=index_tokens(observed.raw_text),
            )
        )

    unmatched = tuple(obs for index, obs in enumerate(observations) if index not in taken_obs)
    # 색인 오염은 판정과 따로 센다 — BE 는 짝이 맞았는지도 `unverified` 도 보지 않고
    # 모든 관측의 토큰을 저장한다(`docs/contracts/job-api.md` §4.3.2).
    gold_tokens = {token for line in frame.lines for token in index_tokens(line.text)}
    observed_tokens = {token for obs in observations for token in index_tokens(obs.raw_text)}
    return FrameResult(
        frame=frame,
        lines=tuple(results),
        unmatched=unmatched,
        gold_tokens=tuple(sorted(gold_tokens)),
        stray_tokens=tuple(sorted(observed_tokens - gold_tokens)),
    )


def evaluate(
    frames: Iterable[GoldFrame],
    observations: Mapping[str, Sequence[Observation]],
    *,
    misread_floor: float = DEFAULT_MISREAD_FLOOR,
) -> tuple[tuple[FrameResult, ...], Summary]:
    """라벨 전체를 판정하고 합계를 낸다.

    Raises:
        GoldError: 라벨에 있는 keyframe 의 관측이 없다. 측정 대상이 라벨과 다른
            프레임 집합이라는 뜻이고, 그대로 두면 누락으로 세어져 **엔진이 나빠진
            것처럼 보인다.**
    """
    results: list[FrameResult] = []
    for frame in frames:
        if frame.key not in observations:
            msg = f"라벨된 keyframe 의 관측이 없다: {frame.key}"
            raise GoldError(msg)
        results.append(match_frame(frame, observations[frame.key], misread_floor=misread_floor))

    scored = [line for frame in results for line in frame.lines if line.line.scored]
    exact = sum(1 for line in scored if line.outcome == "exact")
    misread = sum(1 for line in scored if line.outcome == "misread")
    miss = sum(1 for line in scored if line.outcome == "miss")
    unflagged = sum(1 for line in scored if line.unflagged)
    unsearchable = sum(1 for line in scored if line.reach == "unsearchable")
    reachable = sum(1 for line in scored if line.reach == "reachable")
    lost = sum(1 for line in scored if line.reach == "lost")

    read = [line for line in scored if line.outcome != "miss"]
    total_reference = sum(len(comparison_text(line.line.text)) for line in scored)
    total_errors = sum(
        edit_distance(
            comparison_text(line.line.text),
            comparison_text(line.observed.raw_text) if line.observed else "",
        )
        for line in scored
    )
    matched_reference = sum(len(comparison_text(line.line.text)) for line in read)
    matched_errors = sum(
        edit_distance(comparison_text(line.line.text), comparison_text(line.observed.raw_text))
        for line in read
        if line.observed is not None
    )

    summary = Summary(
        legible=len(scored),
        exact=exact,
        misread=misread,
        miss=miss,
        unflagged_misread=unflagged,
        unsearchable=unsearchable,
        reachable=reachable,
        lost=lost,
        stray_tokens=sum(len(frame.stray_tokens) for frame in results),
        hallucination=sum(len(frame.hallucinations) for frame in results),
        unmatched=sum(len(frame.unmatched) for frame in results),
        observations=sum(len(observations.get(frame.frame.key, ())) for frame in results),
        cer=total_errors / total_reference if total_reference else 0.0,
        cer_matched=matched_errors / matched_reference if matched_reference else 0.0,
    )
    return tuple(results), summary
