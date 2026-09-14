-- 태그 교정 후보(S15P21A501-160)가 확정 전까지 대기하도록 tag_evidence 에 confirmed 칸을 더한다.
-- patch_parse/exclude_scene 후보가 search_rule.active=false 로 대기하는 것과 대칭이다.
-- baseline 을 직접 고치지 않는 이유: 이미 적용된 migration 을 수정하면 checksum 이 달라져
-- validate-on-migrate 가 켜진 기존 DB 의 기동이 실패한다 (backend/README.md).

-- 기존 행(등록 근거·확정된 판단)은 모두 라이브이므로 DEFAULT true.
-- 검수자 교정 후보만 confirmed=false 로 저장되고, 확정(-84) 시 true 로 전환된다.
-- 우선순위 해석기(S15P21A501-161)는 confirmed=true 만 반영한다.
ALTER TABLE npick.tag_evidence
    ADD COLUMN confirmed boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN npick.tag_evidence.confirmed IS
    '검색에 반영되는 확정 근거인가. 검수자 교정 후보는 false 로 대기하고 확정(-84) 시 true 로 전환된다. 우선순위 해석은 true 만 본다 (S15P21A501-160).';
