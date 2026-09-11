-- #169 정규화를 안 거친 저장 경로가 생겼을 때 DB 가 잡는다.
-- TagMatchValue.normalize() 가 백엔드의 단일 지점이지만, 그 함수를 부르는 것은 호출자의 규율이다.
-- 규율이 한 번 깨지면 같은 값이 두 표기로 uq_tag_type_match_value 를 통과하고, 정확 일치 조회는 오류 없이 0건이 된다.
--
-- 세 가지를 막는다.
--
--   빈 값 — normalize() 는 널과 보이지 않는 문자만 있는 입력을 '' 로 접고 빈 값 판정은 호출자에게 맡긴다. 저장 경로가
--          그 검사를 빠뜨리면 이름 없는 태그 한 행이 조용히 생긴다. UNIQUE 때문에 tag_type 당 딱 하나만 생기고
--          이후 모든 빈 값이 그 행에 붙어, 서로 무관한 클립이 같은 태그를 공유한다.
--
--   공백 — 표기 차이 중 가장 흔하고 UNIQUE 를 가장 쉽게 뚫는다.
--
--   폭 없는 문자 — ZWSP·BOM·soft hyphen 류. 이름에 SPACE 가 붙어도 유니코드 공백이 아니라 [[:space:]] 에 안 걸리고,
--          NFKC 도 안 건드린다. 붙여넣기·OCR·워드 문서로 흔하게 섞여 들어온다.
--
-- 이 CHECK 은 정본이 아니라 보조 방어선이다. PostgreSQL 정규식에는 \p{Cf} 같은 유니코드 분류가 없어 서식 문자를
-- 전부 열거할 수 없고, [[:space:]] 도 멀티바이트에서 ctype 에 의존한다. 규칙 정본은 TagMatchValue.normalize() 고
-- 여기서는 실제로 들어올 법한 것만 못 박는다. ZWJ·ZWNJ(U+200C·U+200D) 는 이모지와 아랍·인도계 문자에서 의미를
-- 가르므로 정규화와 마찬가지로 여기서도 막지 않는다.
--
-- tag_type 을 11종으로 제한하는 CHECK 는 여기 넣지 않는다. TagErrorCode.UNKNOWN_TAG_TYPE 이 그 CHECK 가 없다는
-- 전제로 존재하므로 별도 판단이 필요하다.
ALTER TABLE npick.tag
    ADD CONSTRAINT ck_tag_match_value_no_whitespace
        CHECK (
            match_value <> ''
            AND match_value !~ '[[:space:]]'
            AND match_value !~ '[\u00A0\u00AD\u034F\u200B\u200E\u200F\u2028\u2029\u2060\u3000\uFEFF]'
        );
