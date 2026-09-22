"""검색 회귀 측정기 (S15P21A501-301).

`gold.json` 의 질의를 **실제 `POST /search` 로** 돌려 Recall@10 · nDCG@10 을 내고,
통과군·실패군 목록과 함께 `results/` 에 남긴다.

**워커 런타임이 아니다** — 배포 이미지에 들어가지 않는다(기존 3종 하네스와 같은 원칙).
`npick_worker` 를 import 하지도 않는다: 재는 대상이 BE 의 검색 경로 전체(해석 → 후보 →
랭킹 → guard)라서 HTTP 바깥에서 부를 수 있는 자리가 없다.

## 자격증명은 환경변수로만 받는다

`/api/v1/search/**` 는 인증이 필요하다(`SecurityConfig`). 기본값도 예시값도 두지 않는다 —
코드·README·로그·커밋 어디에도 실제 값이 들어가지 않는다. 측정자가 직접 채운다.

    NPICK_BASE_URL   예: https://<호스트>/api/v1
    NPICK_LOGIN_ID   EDITOR 또는 REVIEWER 계정
    NPICK_PASSWORD

## 직렬로 돈다

동시 요청은 서버 큐잉을 섞는다. 정확도 지표는 동시성에 영향받지 않지만
(`eval/query_resolver/README.md`), 쿠키 자(jar)를 스레드가 나눠 쓰는 쪽이 더 위험하다.
200문항이 한 번에 몇 분 걸린다 — 해석 LLM 호출이 문항당 한 번씩이다.

## 의존

표준 라이브러리만 쓴다(`urllib` · `http.cookiejar`). 하네스 하나 때문에 `httpx` 를
프로젝트 의존에 넣지 않는다. 유의성 비교(`compare`)만 numpy 를 쓰는데 그것은 이미
프로젝트 의존이고, 부트스트랩은 `eval/embedding/compare.py` 의 것을 그대로 가져다 쓴다.

    uv run --directory ai python eval/search/search_bench.py run \\
        --out eval/search/results/baseline.json
    uv run --directory ai python eval/search/search_bench.py compare \\
        eval/search/results/a.json eval/search/results/b.json
"""

from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "embedding"))

import search_metrics as metrics

HERE = Path(__file__).resolve().parent
DEFAULT_GOLD = HERE / "gold.json"
TIMEOUT_SECONDS = 120


class BenchError(RuntimeError):
    """측정을 진행할 수 없다."""


# ── HTTP ─────────────────────────────────────────────────────────────


@dataclass
class Client:
    """세션 쿠키를 들고 있는 최소 클라이언트.

    `JSESSIONID` 는 쿠키 자가 알아서 싣는다. CSRF 는 `GET /auth/csrf` 가 심어 준
    `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보내야 한다
    (`docs/contracts/web-api.md` §2.1).
    """

    base_url: str
    opener: urllib.request.OpenerDirector
    jar: http.cookiejar.CookieJar

    @classmethod
    def open(cls, base_url: str) -> Client:
        jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        return cls(base_url=base_url.rstrip("/"), opener=opener, jar=jar)

    def _xsrf(self) -> str:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN" and cookie.value:
                return cookie.value
        msg = "XSRF-TOKEN 쿠키가 없다. NPICK_BASE_URL 이 /api/v1 까지인지 확인한다"
        raise BenchError(msg)

    def request(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
            headers["X-XSRF-TOKEN"] = self._xsrf()
        req = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=headers, method=method
        )
        if req.type not in ("http", "https"):
            msg = f"http(s) 주소가 아니다: {self.base_url}"
            raise BenchError(msg)
        with self.opener.open(req, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))

    def login(self, login_id: str, password: str) -> None:
        """자격증명으로 세션을 연다.

        **사람 말로 죽는다.** 이 스크립트를 처음 돌리는 사람은 내부를 모른다 —
        스택트레이스 대신 무엇을 고쳐야 하는지 한 줄로 낸다. 비밀번호는 예외
        메시지에도 싣지 않는다.
        """
        try:
            self.request("GET", "/auth/csrf")
        except urllib.error.HTTPError as exc:
            msg = f"CSRF 준비가 HTTP {exc.code} 다. NPICK_BASE_URL 을 확인한다"
            raise BenchError(msg) from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            msg = f"서버에 닿지 못했다. NPICK_BASE_URL 을 확인한다 ({exc.__class__.__name__})"
            raise BenchError(msg) from None

        try:
            payload = self.request(
                "POST", "/auth/login", {"loginId": login_id, "password": password}
            )
        except urllib.error.HTTPError as exc:
            if exc.code in (400, 401, 403):
                msg = "자격증명을 확인한다 (NPICK_LOGIN_ID · NPICK_PASSWORD)"
            else:
                msg = f"로그인이 HTTP {exc.code} 로 실패했다. 서버 상태를 확인한다"
            raise BenchError(msg) from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            msg = f"로그인 중 연결이 끊겼다. 다시 돌린다 ({exc.__class__.__name__})"
            raise BenchError(msg) from None

        # **200 이어도 실패일 수 있다.** 계약이 "HTTP 실패와 isSuccess:false 중 하나라도
        # 실패" 라고 정했다(§2.2). 여기서 안 보면 로그인이 실패한 채로 200문항을 돌려
        # 전부 401 로 0점이 나온다.
        if payload.get("isSuccess") is False:
            msg = f"로그인이 거부됐다({payload.get('code')}). 자격증명을 확인한다"
            raise BenchError(msg)

    def search_config(self, execution_id: str) -> dict[str, Any] | None:
        """그 측정이 어느 검색 설정에서 나왔는지.

        `GET /search/executions/{id}` 가 저장 당시 `search_config`·`config_version` 을
        그대로 돌려준다(`docs/contracts/web-api.md` §5.3). 한 번만 부른다 — 나중에
        "이 숫자가 어느 설정이었나"를 되짚을 수 있어야 한다.
        """
        try:
            payload = self.request("GET", f"/search/executions/{execution_id}")
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError):
            return None
        data = payload.get("data") or {}
        return {
            "configVersion": data.get("config_version"),
            "searchConfig": data.get("search_config"),
            "normalizationVersion": data.get("normalization_version"),
        }


# ── 측정 ─────────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class Outcome:
    """문항 하나의 응답에서 채점에 필요한 것만."""

    scene_ids: tuple[str, ...]
    status: str
    degraded_reasons: tuple[str, ...]
    resolution_status: str
    error: str | None
    execution_id: str | None = None


def search_once(client: Client, query: str) -> Outcome:
    """첫 페이지 한 번. 더보기는 부르지 않는다 — k=10 이 곧 한 페이지다."""
    try:
        payload = client.request("POST", "/search", {"query": query, "explicit_filters": {}})
    except urllib.error.HTTPError as exc:
        # **실패한 호출도 채점한다.** 빼면 많이 실패한 설정이 좋아 보인다.
        detail = exc.read().decode("utf-8", errors="replace")[:200]
        return Outcome((), "http_error", (), "", f"HTTP {exc.code}: {detail}")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        return Outcome((), "transport_error", (), "", repr(exc))

    if payload.get("isSuccess") is False:
        # 200 + isSuccess:false 도 실패다(§2.2). 결과 0건의 성공과 구분해 사유를 남긴다.
        return Outcome((), str(payload.get("code") or "envelope_error"), (), "", None)

    data = payload.get("data") or {}
    execution_id = data.get("search_execution_id")
    return Outcome(
        scene_ids=tuple(str(r["scene_id"]) for r in data.get("results", ())),
        status=str(data.get("status", "")),
        degraded_reasons=tuple(str(r) for r in data.get("degraded_reasons", ())),
        resolution_status=str(data.get("query_resolution_status", "")),
        error=None,
        execution_id=str(execution_id) if execution_id else None,
    )


def gold_hash(path: Path) -> str:
    """결과 파일이 어느 골드셋으로 나왔는지. 섞이면 짝이 깨진다(compare)."""
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def _case_row(case: metrics.Case, outcome: Outcome, k: int) -> dict[str, Any]:
    rank = next(
        (i for i, s in enumerate(outcome.scene_ids[:k], start=1) if s == case.scene_id), None
    )
    return {
        "id": case.id,
        "query": case.query,
        "sceneId": case.scene_id,
        "clipId": case.clip_id,
        "tier": case.tier,
        "rank": rank,
        "recallAt10": metrics.recall_at_k(outcome.scene_ids, case.scene_id, k),
        "ndcgAt10": round(metrics.ndcg_at_k(outcome.scene_ids, case.scene_id, k), 4),
        "returned": list(outcome.scene_ids),
        "status": outcome.status,
        "degradedReasons": list(outcome.degraded_reasons),
        "queryResolutionStatus": outcome.resolution_status,
        "error": outcome.error,
    }


def _listing(cases: tuple[metrics.Case, ...], rows: dict[str, dict[str, Any]]) -> list[dict]:
    """통과군·실패군 목록. 실패군이 다음 작업의 입력이라 질의 원문까지 담는다."""
    return [
        {
            "id": c.id,
            "query": c.query,
            "sceneId": c.scene_id,
            "tier": c.tier,
            "rank": rows[c.id]["rank"],
            "status": rows[c.id]["status"],
        }
        for c in cases
    ]


def run(args: argparse.Namespace) -> None:
    base_url = os.environ.get("NPICK_BASE_URL", "").strip()
    login_id = os.environ.get("NPICK_LOGIN_ID", "").strip()
    password = os.environ.get("NPICK_PASSWORD", "")
    missing = [
        name
        for name, value in (
            ("NPICK_BASE_URL", base_url),
            ("NPICK_LOGIN_ID", login_id),
            ("NPICK_PASSWORD", password),
        )
        if not value
    ]
    if missing:
        msg = f"환경변수를 채운다: {', '.join(missing)}"
        raise BenchError(msg)

    if args.out.exists() and not args.force:
        msg = f"이미 있는 결과다. 다른 --out 을 주거나 --force 로 덮어쓴다: {args.out}"
        raise BenchError(msg)

    cases = metrics.load_gold(args.gold)
    if args.limit:
        cases = cases[: args.limit]

    client = Client.open(base_url)
    client.login(login_id, password)

    started = datetime.now(UTC)
    print(
        f"{len(cases)}문항. 이 측정은 {login_id} 계정의 검색 기록에 문항 수만큼 행을 남긴다"
        " (README 의 경고).",
        flush=True,
    )

    results: dict[str, list[str]] = {}
    rows: dict[str, dict[str, Any]] = {}
    config: dict[str, Any] | None = None
    for index, case in enumerate(cases, start=1):
        outcome = search_once(client, case.query)
        results[case.id] = list(outcome.scene_ids)
        rows[case.id] = _case_row(case, outcome, args.k)
        if config is None and outcome.execution_id:
            # 한 번만. 나중에 "이 숫자가 어느 설정에서 나왔나"를 되짚는 자리다.
            config = client.search_config(outcome.execution_id)
        mark = "o" if rows[case.id]["rank"] else "x"
        print(f"[{index:>3}/{len(cases)}] {mark} {case.id} {case.query}", flush=True)

    summary = metrics.summarize(cases, results, args.k)
    passed, failed = metrics.split(cases, results, args.k)

    payload = {
        "params": {
            "baseUrlHost": urllib.parse.urlparse(base_url).hostname,
            "k": args.k,
            "gold": str(args.gold.name),
            "goldHash": gold_hash(args.gold),
            "cases": len(cases),
            "startedAt": started.isoformat(timespec="seconds"),
            "finishedAt": datetime.now(UTC).isoformat(timespec="seconds"),
            "label": args.label,
            # 저장 당시 설정 snapshot. 첫 성공 실행에서 한 번 읽는다. 읽지 못하면 null 이고,
            # 그러면 이 결과는 "어느 설정이었는지 모르는 숫자"다 — 표에 넣기 전에 확인한다.
            "searchConfig": config,
        },
        "summary": summary.to_json(),
        "passed": _listing(passed, rows),
        "failed": _listing(failed, rows),
        "cases": [rows[c.id] for c in cases],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"\n{args.out}")
    print(f"  Recall@{args.k} {summary.recall:.4f}   nDCG@{args.k} {summary.ndcg:.4f}")
    for tier, scores in summary.by_tier.items():
        print(
            f"  {tier:<5} {scores.hits}/{scores.cases}"
            f"  Recall {scores.recall:.4f}  nDCG {scores.ndcg:.4f}"
        )
    print(f"  통과군 {len(passed)} / 실패군 {len(failed)}")


# ── 비교 ─────────────────────────────────────────────────────────────


def compare(args: argparse.Namespace) -> None:
    """두 측정의 짝지은 유의성. 부트스트랩은 임베딩 하네스 것을 그대로 쓴다.

    구간이 0 을 포함하면 "차이 없음"이 아니라 **"이 표본으로는 검출 못 함"** 이다.
    """
    import numpy as np
    from compare import BOOTSTRAP, paired_bootstrap  # eval/embedding/compare.py

    runs = [json.loads(p.read_text(encoding="utf-8")) for p in args.results]
    hashes = {r["params"]["goldHash"] for r in runs}
    if len(hashes) > 1:
        msg = f"골드셋이 섞여 있다 — goldHash {hashes}"
        raise BenchError(msg)

    by_id = [{row["id"]: row["ndcgAt10"] for row in r["cases"]} for r in runs]
    shared = sorted(set(by_id[0]) & set(by_id[1]))
    if len(shared) != len(by_id[0]) or len(shared) != len(by_id[1]):
        msg = f"두 측정의 문항이 다르다 — 공통 {len(shared)}건"
        raise BenchError(msg)

    a = np.array([by_id[0][i] for i in shared])
    b = np.array([by_id[1][i] for i in shared])
    mean, lo, hi = paired_bootstrap(a, b)
    verdict = "유의함" if lo > 0 or hi < 0 else "차이 검출 못 함"
    wins, losses = int((a > b).sum()), int((a < b).sum())
    print(f"문항 {len(shared)}건 · 부트스트랩 {BOOTSTRAP}회 · nDCG@10")
    print(f"  {args.results[0].name} {a.mean():.4f}")
    print(f"  {args.results[1].name} {b.mean():.4f}")
    print(
        f"  {mean:+.4f}  [{lo:+.4f}, {hi:+.4f}]  {verdict}"
        f"   (승 {wins} / 무 {len(shared) - wins - losses} / 패 {losses})"
    )


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description="검색 회귀 측정 (S15P21A501-301)")
    sub = parser.add_subparsers(dest="command", required=True)

    runner = sub.add_parser("run", help="골드셋을 POST /search 로 돌려 측정한다")
    runner.add_argument("--gold", type=Path, default=DEFAULT_GOLD)
    runner.add_argument("--out", type=Path, required=True)
    runner.add_argument("--k", type=int, default=metrics.DEFAULT_K)
    runner.add_argument("--limit", type=int, default=0, help="앞에서 N문항만 (연결 확인용)")
    runner.add_argument("--label", default="", help="설정 이름. 결과 파일에 남는다")
    runner.add_argument("--force", action="store_true", help="이미 있는 --out 을 덮어쓴다")
    runner.set_defaults(func=run)

    comparer = sub.add_parser("compare", help="두 측정의 짝지은 유의성")
    comparer.add_argument("results", type=Path, nargs=2)
    comparer.set_defaults(func=compare)

    args = parser.parse_args()
    try:
        args.func(args)
    except BenchError as exc:
        raise SystemExit(str(exc)) from None


if __name__ == "__main__":
    main()
