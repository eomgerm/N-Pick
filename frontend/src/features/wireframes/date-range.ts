export interface DateRange {
  from: string;
  to: string;
}

export const emptyDateRange: DateRange = { from: '', to: '' };

export function isCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function validateDateRange(range: DateRange): string {
  if (!range.from && !range.to) return '';
  if (!range.from || !range.to) return '시작일과 종료일을 모두 선택해 주세요.';
  if (!isCalendarDate(range.from) || !isCalendarDate(range.to))
    return '올바른 날짜를 입력해 주세요.';
  if (range.from > range.to) return '종료일은 시작일과 같거나 이후여야 해요.';
  return '';
}

export function selectRangeDate(range: DateRange, date: string): DateRange {
  if (!range.from || range.to) return { from: date, to: '' };
  return date < range.from ? { from: date, to: range.from } : { from: range.from, to: date };
}

export function formatDateRange(range: DateRange): string {
  return range.from && range.to
    ? `${range.from.replaceAll('-', '.')} – ${range.to.replaceAll('-', '.')}`
    : '전체 기간';
}

export function readDateRange(from?: string, to?: string): DateRange {
  const range = { from: from ?? '', to: to ?? '' };
  return validateDateRange(range) ? emptyDateRange : range;
}

/**
 * URL 이 실어 온 기간을 {@link readDateRange} 가 버렸다면 그 이유. 버릴 것이 없으면 빈 문자열입니다.
 *
 * 접는 것 자체는 화면을 계속 쓰게 하려는 것이지 조건이 없었다는 뜻이 아닙니다. 이유를 함께 읽지
 * 않으면 방송일·촬영일을 건 링크가 필터 없는 검색으로 조용히 돌아갑니다. 계약 §5 는 한쪽만 온
 * 기간을 `SRCH_400_003` 으로 막습니다.
 */
export function readDateRangeError(from?: string, to?: string): string {
  return validateDateRange({ from: from ?? '', to: to ?? '' });
}
