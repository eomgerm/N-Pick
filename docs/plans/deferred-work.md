- source_spec: `docs/plans/spec-s15p21a501-116-search-input-filter-ui.md`
  summary: 결과 화면에서 검색어를 편집한 뒤 날짜 필터만 적용하면 편집값이 사라지고 이전 제출 검색어로 이동한다.
  evidence: `wireframe-shell.tsx`의 검색 input은 `query`를 갱신하지만 두 DateRangePicker의 `onChange`는 기존 `submittedQuery`로 이동하며, 이 동작은 기준 커밋부터 존재한다.
