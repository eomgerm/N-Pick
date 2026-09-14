-- search_rule.condition_json·patch_json 주석에서 완료된 S15P21A501-101 참조를 없앤다 (S15P21A501-49).
--
-- baseline 의 주석은 「실제 JSON 키·형식은 S15P21A501-101에서 확정한다」였다. -101 은 완료됐고
-- 그것을 정하지 않았다 — FRD v3.2 §11 이 「해석 출력 계약만 확정 · 규칙의 조건·연산 어휘는
-- S15P21A501-49 소관」으로 구분해 남겼다. 그 어휘를 이 일감이 코드로 정의했으므로 주석이 가리킬
-- 곳을 정본 타입으로 바꾼다.
--
-- baseline 을 직접 고치지 않는 이유: 이미 적용된 migration 을 수정하면 checksum 이 달라져
-- validate-on-migrate 가 켜진 기존 DB 의 기동이 실패한다 (backend/README.md).

COMMENT ON COLUMN "search_rule"."condition_json" IS 'resolver 원본 출력에서 판정할 조건과 문법·출력 계약의 호환성 정보. patch_parse에서 필수. 질의 fingerprint와 별도로 매칭한다. JSON 키·형식과 허용 조건 어휘의 정본은 com.npick.search.domain.model.ParseRule 이다 (S15P21A501-49). 조건이 거는 출력 키는 ai/src/npick_worker/query_resolver/schema.py 기준이다.';

COMMENT ON COLUMN "search_rule"."patch_json" IS '해석에 적용할 허용된 변경 연산 목록. patch_parse에서 필수. 명시적 사용자 필터·출처를 보호하고 실행 코드는 허용하지 않는다. JSON 키·형식과 허용 연산 어휘의 정본은 com.npick.search.domain.model.ParseRule 이다 (S15P21A501-49). 연산은 값 설정·해제와 목록 항목 추가·제거뿐이고 정규식·스크립트·JSON 경로는 문법에 없다.';
