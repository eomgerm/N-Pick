/** UI 입력 모델. 서버 필드명·사유 코드는 167의 adapter에서 변환한다. */
export interface SearchResultDetails {
  resolverStatus?: 'succeeded' | 'fallback' | 'unknown';
  excludedCount?: number | null;
  exclusionReasons?: readonly string[];
}

export function getResolverLabel(status: SearchResultDetails['resolverStatus']) {
  if (status === 'succeeded') return '정상 완료';
  if (status === 'fallback') return '해석을 사용할 수 없어 기본 단어 검색으로 전환';
  return '해석 상태가 제공되지 않았어요';
}

interface SearchExclusionDetailsProps {
  details?: SearchResultDetails;
}

export function SearchExclusionDetails({ details }: SearchExclusionDetailsProps) {
  const count = details?.excludedCount;
  const hasCount = typeof count === 'number' && Number.isSafeInteger(count) && count >= 0;
  const reasons = details?.exclusionReasons?.filter((reason) => reason.trim());

  return (
    <>
      <div>
        <dt>제외된 결과</dt>
        <dd>{hasCount ? `${count}건` : '제외 정보가 제공되지 않았어요'}</dd>
      </div>
      {reasons?.length ? (
        <div>
          <dt>제외 사유</dt>
          <dd>{reasons.join(' · ')}</dd>
        </div>
      ) : null}
    </>
  );
}
