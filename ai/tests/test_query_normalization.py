"""질의 정규화기 계약 (S15P21A501-43).

지문(fingerprint)의 재료를 만드는 코드다. 여기서 나온 `normalized_query` 가
BE 에서 SHA-256 으로 묶여 `search_execution.query_fingerprint` 가 되고,
장면 제외 규칙의 exact 조회 키가 된다. 따라서 이 파일이 검증하는 것은
"검색 품질" 이 아니라 **같은 뜻의 질의가 같은 문자열로 수렴하는가** 다.
"""

from pathlib import Path

import pytest

from npick_worker.query_normalization import (
    QueryNormalizationConfig,
    get_default_config,
    load_config,
    normalize,
)

# ── 입력 거부 ────────────────────────────────────────────────────────


@pytest.mark.parametrize("raw", ["", "   ", "\t\n", "　"])
def test_blank_query_is_rejected(raw: str) -> None:
    """FR-QRY-006. 빈 질의는 지문을 만들 수 없으므로 정규화 단계에서 끊는다."""
    with pytest.raises(ValueError, match="질의가 비어 있다"):
        normalize(raw)


def test_query_of_only_symbols_is_rejected() -> None:
    """기호만 있는 입력은 품사 필터를 통과하는 토큰이 없다.

    조사만 늘어놓은 입력("을 를 이 가")은 여기 쓰지 않는다 — Kiwi 가 홑 '을' 을
    NNG, '가' 를 VV 로 붙여 실제로는 내용어가 생긴다.
    """
    with pytest.raises(ValueError, match="내용어가 없다"):
        normalize("!!! ???")


def test_query_of_only_stopwords_is_rejected() -> None:
    """불용어만 남으면 지문이 빈 문자열이 된다. 그 상태로 저장하면 안 된다."""
    with pytest.raises(ValueError, match="내용어가 없다"):
        normalize("영상 찾아줘")


# ── 같은 뜻 → 같은 canonical ─────────────────────────────────────────


def test_particles_and_endings_do_not_change_canonical() -> None:
    """FR-QRY-001. 조사·어미가 달라도 같은 canonical 이어야 한다."""
    a = normalize("작년 여름에 부산 침수됐던 장면 좀 찾아줘")
    b = normalize("부산 침수 작년 여름")

    assert a.normalized_query == b.normalized_query


def test_word_order_does_not_change_canonical() -> None:
    """어순만 다른 질의는 같은 검색이다. BM25 는 bag-of-words 라 손실이 없다."""
    a = normalize("부산 침수")
    b = normalize("침수 부산")

    assert a.normalized_query == b.normalized_query == "부산 침수"


def test_fullwidth_and_case_are_folded() -> None:
    """NFKC + casefold. 눈에 같아 보이는 문자가 다른 지문을 만들면 안 된다."""
    a = normalize("ＣＯＶＩＤ 확산")  # noqa: RUF001 - 전각 입력이 이 테스트의 요점이다
    b = normalize("covid 확산")

    assert a.normalized_query == b.normalized_query


def test_search_verbs_and_media_words_are_dropped() -> None:
    """'찾아줘', '영상' 같은 검색 군더더기는 검색 조건이 아니다."""
    assert normalize("태풍 영상 찾아줘").normalized_query == "태풍"


def test_normalization_is_deterministic() -> None:
    """같은 입력을 두 번 넣으면 같은 출력이어야 한다(캐시·상태 오염 감지)."""
    first = normalize("작년 여름 부산 침수")
    second = normalize("작년 여름 부산 침수")

    assert first == second


# ── 지문용과 BM25 용 분리 ────────────────────────────────────────────


def test_search_tokens_keep_order_and_stopwords() -> None:
    """`search_tokens` 는 색인 측과 같은 규칙만 적용한다.

    정렬·불용어 제거는 지문용에만 쓴다. BM25 토큰까지 뭉개면 색인된 토큰과
    어긋나 검색이 0건이 된다(`docs/architecture/02-container.md` §110).
    """
    result = normalize("태풍 영상 찾아줘")

    assert result.search_tokens == ("태풍/NNG", "영상/NNG", "찾/VV")
    assert result.normalized_query == "태풍"


# ── 별칭 치환 ────────────────────────────────────────────────────────


def _config_with_busan_alias(tmp_path: Path) -> QueryNormalizationConfig:
    config_path = tmp_path / "query_normalization.v1.toml"
    config_path.write_text(
        """
schema = "query-norm/v1"
keep_pos = ["NNG", "NNP", "SL", "SN", "VV", "VA"]
sort_tokens = true
stopwords = []

[aliases]
"부산시" = "부산"
""",
        encoding="utf-8",
    )
    return load_config(config_path)


def test_alias_maps_a_whole_token(tmp_path: Path) -> None:
    """FR-QRY-001. 별칭은 Kiwi 토큰 하나에 정확히 일치할 때만 걸린다."""
    config = _config_with_busan_alias(tmp_path)

    assert normalize("부산시 침수", config).normalized_query == "부산 침수"


def test_alias_does_not_corrupt_a_longer_word(tmp_path: Path) -> None:
    """'부산시' 별칭이 '부산시청' 을 '부산청' 으로 만들면 안 된다.

    문자열 치환으로 구현하면 부분 일치가 걸려 실제로 '부산청' 이 나왔다(실측).
    토큰 단위로 대조하면 '부산시' 키가 '부산시청' 에 걸리지 않는다.
    """
    config = _config_with_busan_alias(tmp_path)

    assert normalize("부산시청 집회", config).normalized_query == "부산 시청 집회"


def test_alias_applies_before_the_stopword_filter(tmp_path: Path) -> None:
    """별칭을 먼저 걸어야 불용어 판정이 일관된다.

    필터가 먼저면 '영상물'(별칭 -> '영상')은 살아남아 매체어가 지문에 남고,
    '영상'은 거부된다. 같은 뜻인데 결과가 갈린다.
    """
    config_path = tmp_path / "query_normalization.v1.toml"
    config_path.write_text(
        """
schema = "query-norm/v1"
keep_pos = ["NNG", "NNP", "SL", "SN", "VV", "VA"]
sort_tokens = true
stopwords = ["영상/NNG", "찾/VV"]
user_words = []

[aliases]
"영상물" = "영상"
""",
        encoding="utf-8",
    )
    config = load_config(config_path)

    # '영상물' 은 Kiwi 가 NNG 로 잡으므로 별칭 뒤 ('영상', 'NNG') 가 되어 불용어에 걸린다.
    with pytest.raises(ValueError, match="내용어가 없다"):
        normalize("영상물 찾아줘", config)


def _write_config(tmp_path: Path, aliases: str = "", stopwords: str = "[]") -> Path:
    config_path = tmp_path / "query_normalization.v1.toml"
    config_path.write_text(
        f"""
schema = "query-norm/v1"
keep_pos = ["NNG", "NNP", "SL", "SN", "VV", "VA"]
sort_tokens = true
stopwords = {stopwords}
user_words = []

[aliases]
{aliases}
""",
        encoding="utf-8",
    )
    return config_path


def test_malformed_stopword_is_rejected_at_load(tmp_path: Path) -> None:
    """설정 오타는 기동 때 터져야 한다. 질의 때 터지면 매 요청이 400 이 된다.

    `normalize()` 는 사용자 입력 오류에도 ValueError 를 쓰므로, 호출부가 설정 오타와
    빈 질의를 구분하지 못한다.
    """
    with pytest.raises(ValueError, match="stopwords 항목은"):
        load_config(_write_config(tmp_path, stopwords='["영상"]'))


def test_alias_key_that_is_also_a_stopword_is_rejected(tmp_path: Path) -> None:
    """불용어인 토큰을 별칭 키로 쓰면 치환된 형태로 필터를 빠져나간다.

    별칭이 필터보다 먼저라서 생기는 거울상이다. 실제로 '태풍 영상' 이 '태풍' 이 아니라
    '비디오 태풍' 이 됐다.
    """
    with pytest.raises(ValueError, match="불용어"):
        load_config(_write_config(tmp_path, aliases='"영상" = "비디오"', stopwords='["영상/NNG"]'))


def test_identity_alias_is_allowed(tmp_path: Path) -> None:
    """`"부산" = "부산"` 은 아무 일도 안 하는 항목이지 연쇄가 아니다."""
    config = load_config(_write_config(tmp_path, aliases='"부산" = "부산"'))

    assert normalize("부산 침수", config).normalized_query == "부산 침수"


def test_chained_alias_is_rejected_at_load(tmp_path: Path) -> None:
    """A->B 와 B->C 를 함께 두면 A 는 B 에서 멈추고 B 만 C 가 된다.

    단일 패스라 연쇄가 끊긴다. 조용히 절반만 적용되느니 설정 로딩에서 막는다.
    """
    config_path = tmp_path / "query_normalization.v1.toml"
    config_path.write_text(
        """
schema = "query-norm/v1"
keep_pos = ["NNG", "NNP"]
sort_tokens = true
stopwords = []
user_words = []

[aliases]
"부산광역시" = "부산시"
"부산시" = "부산"
""",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="별칭이 연쇄"):
        load_config(config_path)


def test_alias_does_not_reach_search_tokens(tmp_path: Path) -> None:
    """별칭은 지문용 규칙이다. BM25 토큰에 적용하면 색인 측과 어긋난다."""
    config = _config_with_busan_alias(tmp_path)

    assert normalize("부산시 침수", config).search_tokens == ("부산시/NNP", "침수/NNG")


# ── 동봉 사전 ────────────────────────────────────────────────────────
#
# 아래 값들은 AI Hub 「018. 음성인식에 의한 영상 요약 데이터」의 사용자 질의
# 28,029 건에서 뽑은 것이다. 근거는 `config/query_normalization.v1.toml` 주석 참고.


@pytest.mark.parametrize(
    "raw",
    ["서울 집중호우", "서울시 집중호우", "서울특별시 집중호우"],
)
def test_shipped_aliases_collapse_region_spellings(raw: str) -> None:
    """코퍼스에서 실제로 셋 다 쓰였다(서울 1,724 / 서울시 932 / 서울특별시 19)."""
    assert normalize(raw).normalized_query == "서울 집중호우"


@pytest.mark.parametrize(
    ("full", "abbreviation"),
    [
        ("보건복지부", "복지부"),
        ("국토교통부", "국토부"),
        ("고용노동부", "고용부"),
        ("여성가족부", "여가부"),
        ("공정거래위원회", "공정위"),
        ("국가정보원", "국정원"),
    ],
)
def test_shipped_aliases_collapse_ministry_abbreviations(full: str, abbreviation: str) -> None:
    """정식 명칭과 약칭이 질의·문서 두 코퍼스에서 모두 관측된 쌍만 넣었다."""
    canonical = normalize(f"{full} 브리핑").normalized_query

    assert canonical == normalize(f"{abbreviation} 브리핑").normalized_query
    assert abbreviation in canonical
    assert full not in canonical


#: 별칭 수렴 스윕이 쓰는 꼬리말. 별칭 뒤에 무엇이 오든 수렴해야 한다.
_SWEEP_TAILS = (
    "교통사고",
    "기자회견",
    "집중호우",
    "브리핑",
    "산불",
    "폭우",
    "대책 발표",
    "현장",
    "침수",
    "태풍 피해",
    "지진",
    "회의",
    "시위",
    "화재",
    "예산안",
    "조사 결과",
    "발표",
    "논란",
    "사고",
    "대응",
)


def test_every_shipped_alias_converges_on_every_tail() -> None:
    """별칭 전수 x 꼬리말 전수. 표본 몇 개로는 안 잡히는 구멍이 있었다.

    별칭은 형태소 분석 **뒤** 에 걸리므로, 별칭 키의 표층형이 뒤따르는 단어의 분절까지
    바꾼다. 별칭 값을 `user_words` 에 넣지 않았을 때 38/680 이 갈렸다.

        "부산시 교통사고" -> "교통사고 부산"
        "부산 교통사고"   -> "교통 부산 사고"
    """
    aliases = get_default_config().aliases
    diverged = [
        (key, value, tail)
        for key, value in aliases.items()
        for tail in _SWEEP_TAILS
        if normalize(f"{key} {tail}").normalized_query
        != normalize(f"{value} {tail}").normalized_query
    ]

    assert diverged == []


def test_facility_names_are_left_to_the_resolver() -> None:
    """시설명은 정규화기가 건드리지 않는다.

    `tag` 테이블이 facility 태그의 match_value/name 을 이미 분리해 들고 있고,
    FRD F-05 3번이 시설 구조화를 AI 해석기에 배정했다. 여기서 중복 관리하면
    두 사전이 어긋난다.
    """
    assert normalize("인천국제공항 활주로").normalized_query != (
        normalize("인천공항 활주로").normalized_query
    )


def test_shipped_aliases_do_not_corrupt_longer_names() -> None:
    """'서울시' 별칭이 '서울시청' 을 건드리면 안 된다."""
    assert "서울시청" in normalize("서울시청 앞 집회").normalized_query


def test_shipped_stopwords_collapse_real_corpus_phrasings() -> None:
    """코퍼스에 실재하는 두 질의 형식이 같은 canonical 로 모여야 한다."""
    a = normalize("사 차 대유행 언급 장면 보여 줘.")
    b = normalize("사 차 대유행 언급에 대해 알려 줘")

    assert a.normalized_query == b.normalized_query


def test_shipped_stopwords_keep_topic_words() -> None:
    """'사람'·'지역' 은 뉴스 검색의 주제어다. 일반 불용어 사전을 쓰면 사라진다."""
    canonical = normalize("지역 축제에 모인 사람").normalized_query

    assert "지역" in canonical
    assert "사람" in canonical


# ── 버전 ─────────────────────────────────────────────────────────────


def test_version_embeds_kiwi_version() -> None:
    """Kiwi 를 올리면 canonical 이 달라진다. 버전 문자열이 그 사실을 담아야 한다."""
    import kiwipiepy

    assert f"kiwi{kiwipiepy.__version__}" in normalize("부산 침수").normalization_version


def test_version_ignores_list_order(tmp_path: Path) -> None:
    """리스트 순서는 동작을 안 바꾸므로 버전도 안 바꿔야 한다.

    `stopwords`·`keep_pos`·`user_words` 는 소비 시점에 전부 집합이다. 가독성 때문에
    재정렬하면 동작은 그대로인데 버전만 바뀌어 쌓인 exclude_scene 이 전멸한다.
    """
    body = """
schema = "query-norm/v1"
keep_pos = {keep_pos}
sort_tokens = true
stopwords = {stopwords}
user_words = {user_words}

[aliases]
"""
    a = tmp_path / "a.toml"
    b = tmp_path / "b.toml"
    a.write_text(
        body.format(
            keep_pos='["NNG", "NNP"]',
            stopwords='["영상/NNG", "장면/NNG"]',
            user_words='["서울시", "부산시"]',
        ),
        encoding="utf-8",
    )
    b.write_text(
        body.format(
            keep_pos='["NNP", "NNG"]',
            stopwords='["장면/NNG", "영상/NNG"]',
            user_words='["부산시", "서울시"]',
        ),
        encoding="utf-8",
    )

    assert load_config(a).version_id == load_config(b).version_id


def test_version_embeds_kiwi_model_version() -> None:
    """토큰 경계를 정하는 것은 kiwipiepy 가 아니라 kiwipiepy-model 이다.

    모델만 올라가면 canonical 이 바뀌는데 `kiwipiepy.__version__` 은 그대로다.
    """
    from importlib.metadata import version

    assert f"model{version('kiwipiepy_model')}" in normalize("부산 침수").normalization_version


def test_version_changes_when_config_changes(tmp_path: Path) -> None:
    """FR-QRY-004. 규칙이 바뀌면 새 버전이어야 한다."""
    body = """
schema = "query-norm/v1"
keep_pos = ["NNG", "NNP", "SL", "SN", "VV", "VA"]
sort_tokens = true
stopwords = {stopwords}

[aliases]
"""
    a = tmp_path / "a.toml"
    b = tmp_path / "b.toml"
    a.write_text(body.format(stopwords="[]"), encoding="utf-8")
    b.write_text(body.format(stopwords='["장면/NNG"]'), encoding="utf-8")

    assert load_config(a).version_id != load_config(b).version_id


def test_version_fits_db_column() -> None:
    """`search_execution.normalization_version` 은 varchar(128) 이다."""
    assert len(normalize("부산 침수").normalization_version) <= 128
