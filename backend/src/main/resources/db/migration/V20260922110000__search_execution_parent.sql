-- S15P21A501-280. 더보기(offset)로 이어 본 페이지가 「내 검색 기록」에 같은 질의로 여러 번
-- 보이던 것을 고친다(#4). 더보기는 매 페이지 새 search_execution 을 만들므로, 한 검색이 N
-- 페이지면 기록에 같은 질의가 N 건 나왔다. 이어보기 실행에 첫 페이지(root) 실행을 가리키는
-- parent_execution_id 를 달아 기록 조회에서 root 만 보이게 한다.
--
-- 결과 재사용이 아니라 기록 그룹핑 힌트다 — 더보기 페이지는 여전히 각자 실행되고 각자
-- search_result 를 저장해 문의(search_result_id 참조)가 가능하다(제약: FRD §7.2 좁은 해석,
-- 검색코어 소유자 확인). 그래서 결과·해석 재사용이 아니며 §7.2 의 「해석 캐시」에 해당하지 않는다.
--
-- FK 를 걸지 않는다. 클라이언트가 첫 응답의 search_execution_id 를 다음 요청에 실어 보내는
-- 값이라 검증되지 않은 참조가 들어올 수 있고, 기록 조회가 소유자 범위라 잘못된 값이 남의
-- 기록을 건드리지 못한다(잘못된 값은 그 실행을 자기 기록에서 숨길 뿐이다). 소프트한 그룹핑
-- 힌트이므로 plain 컬럼으로 둔다. 참조 무결성이 필요해지면 그때 FK 를 얹는다.
ALTER TABLE "search_execution" ADD COLUMN "parent_execution_id" bigint;

COMMENT ON COLUMN "search_execution"."parent_execution_id" IS
    '더보기로 이어 본 실행이 가리키는 첫 페이지(root) 실행 id. root 실행은 null 이다. 기록 조회는 root(null)만 보인다(S15P21A501-280). 결과 재사용이 아니라 기록 그룹핑 힌트다';

-- 기록 목록 인덱스에 root 조건을 얹는다. V20260922100000 이 세운 원칙("기록에서 빠지는 행은
-- 색인에 넣지 않는다" — 숨긴 행을 뺀 것과 같은 이유)을 이어, 더보기 이어보기(child)도 색인에서
-- 뺀다. 더보기를 많이 쓰는 사용자는 child 가 자기 실행 행의 대부분을 차지할 수 있어, 조건을
-- WHERE 로만 두면 색인 스캔 뒤 필터가 그 행들을 훑는다. 부분 인덱스 조건으로 옮겨 root 만 남긴다.
DROP INDEX ix_search_execution_owner_alive;
CREATE INDEX ix_search_execution_owner_alive
    ON search_execution (searched_by_id, created_at DESC, search_execution_id DESC)
    WHERE deleted_at IS NULL AND parent_execution_id IS NULL;
