export interface DateRange {
  from: string;
  to: string;
}

export const emptyDateRange: DateRange = { from: '', to: '' };

export type RecentYearPreset = 1 | 2 | 3;

export function isCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function validateDateRange(range: DateRange, maxDate?: string): string {
  if (!range.from && !range.to) return '';
  if (!range.from || !range.to) return '시작일과 종료일을 모두 선택해 주세요.';
  if (!isCalendarDate(range.from) || !isCalendarDate(range.to))
    return '올바른 날짜를 입력해 주세요.';
  if (range.from > range.to) return '종료일은 시작일과 같거나 이후여야 해요.';
  if (maxDate && (range.from > maxDate || range.to > maxDate))
    return '시작일과 종료일은 오늘 이후 날짜로 선택할 수 없습니다.';
  return '';
}

/**
 * 기준일까지의 최근 N년을 달력 날짜 범위로 만듭니다.
 * 윤년의 2월 29일은 대상 연도에 그 날짜가 없으면 2월의 마지막 날로 맞춥니다.
 */
export function createRecentYearRange(years: RecentYearPreset, to: string): DateRange {
  if (!isCalendarDate(to)) throw new Error('최근 기간의 기준일은 올바른 날짜여야 합니다.');

  const [year, month, day] = to.split('-').map(Number);
  const fromYear = year - years;
  const lastDayOfMonth = new Date(Date.UTC(fromYear, month, 0)).getUTCDate();
  const fromDay = Math.min(day, lastDayOfMonth);

  return {
    from: `${fromYear}-${String(month).padStart(2, '0')}-${String(fromDay).padStart(2, '0')}`,
    to,
  };
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

export interface ReadDateRangeResult {
  /** 화면이 쓸 기간. 쓸 수 없는 값이면 빈 기간입니다. */
  range: DateRange;
  /** 왜 빈 기간이 되었는지. 버릴 것이 없었으면 빈 문자열입니다. */
  error: string;
}

/**
 * URL 이 실어 온 기간을 읽습니다. <b>버린 값과 버린 이유를 함께 돌려줍니다.</b>
 *
 * 쓸 수 없는 값을 빈 기간으로 접는 것은 좌측 기간 선택기를 계속 열어 두기 위해서이지 조건이
 * 없었다는 뜻이 아닙니다. 이유를 따로 읽게 하면 같은 파라미터를 두 곳이 보고 서로 다른 결론을
 * 내게 되고, 그것이 방송일·촬영일을 건 링크가 필터 없는 검색으로 조용히 돌아간 원인이었습니다.
 * 계약 §5 는 한쪽만 온 기간을 `SRCH_400_003` 으로 막습니다.
 */
export function readDateRange(from?: string, to?: string, maxDate?: string): ReadDateRangeResult {
  const range = { from: from ?? '', to: to ?? '' };
  const error = validateDateRange(range, maxDate);
  return { range: error ? emptyDateRange : range, error };
}
