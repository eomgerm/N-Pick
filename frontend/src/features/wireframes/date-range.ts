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

export function matchesDateRange(value: string, range: DateRange, isVerified = true): boolean {
  const date = value.replaceAll('.', '-');
  // 정보 없음·미검증은 검증된 날짜 충돌로 취급하지 않습니다.
  if (!isVerified || !isCalendarDate(date) || !range.from || !range.to) return true;
  return date >= range.from && date <= range.to;
}

export function readDateRange(from?: string, to?: string): DateRange {
  const range = { from: from ?? '', to: to ?? '' };
  return validateDateRange(range) ? emptyDateRange : range;
}
