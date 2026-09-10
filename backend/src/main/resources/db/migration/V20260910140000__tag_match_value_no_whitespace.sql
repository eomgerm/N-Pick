-- #169 정규화를 안 거친 저장 경로가 생겼을 때 DB 가 잡는다.
-- TagMatchValue.normalize() 가 백엔드의 단일 지점이지만, 그 함수를 부르는 것은 호출자의 규율이다.
-- 규율이 한 번 깨지면 같은 값이 두 표기로 uq_tag_type_match_value 를 통과하고, 정확 일치 조회는 오류 없이 0건이 된다.
--
-- 두 가지를 막는다.
--
--   공백 — 표기 차이 중 가장 흔하고 UNIQUE 를 가장 쉽게 뚫는다. NFKC 전체를 SQL 로 강제할 수는 없다.
--          \s 는 [[:space:]] 라 U+00A0·U+3000 같은 호환 공백은 ctype 에 따라 안 걸릴 수 있다. 그것들은
--          normalize() 의 NFKC 가 보통 공백으로 편 뒤 지우므로, 이 CHECK 이 덮는 범위는
--          "함수를 안 거친 값의 ASCII 공백" 이다.
--
--   빈 값 — normalize() 는 널과 공백만 있는 입력을 '' 로 접고 빈 값 판정은 호출자에게 맡긴다. 저장 경로가 그
--          검사를 빠뜨리면 이름 없는 태그 한 행이 조용히 생긴다. UNIQUE 때문에 tag_type 당 딱 하나만 생기고
--          이후 모든 빈 값이 그 행에 붙어, 서로 무관한 클립이 같은 태그를 공유한다.
--
-- tag_type 을 11종으로 제한하는 CHECK 는 여기 넣지 않는다. TagErrorCode.UNKNOWN_TAG_TYPE 이 그 CHECK 가 없다는
-- 전제로 존재하므로 별도 판단이 필요하다.
ALTER TABLE npick.tag
    ADD CONSTRAINT ck_tag_match_value_no_whitespace
        CHECK (match_value <> '' AND match_value !~ '\s');
