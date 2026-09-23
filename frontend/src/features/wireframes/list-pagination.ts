export const PAGE_SIZE_OPTIONS = [10, 20, 50] as const;
export const DEFAULT_PAGE_SIZE = PAGE_SIZE_OPTIONS[0];

export type PageItem = number | 'ellipsis';

// 첫·끝 번호와 현재 앞뒤 한 쪽씩을 남기고 나머지는 생략한다. 8쪽 이상이면 늘 7칸이라 넘겨도 버튼 폭이 흔들리지 않는다.
// 생략될 쪽이 하나뿐이면 「…」 대신 그 번호를 그대로 보인다.
export function getPageItems(page: number, totalPages: number): PageItem[] {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index + 1);
  const start = Math.max(3, Math.min(page - 1, totalPages - 4));
  const end = Math.min(totalPages - 2, Math.max(page + 1, 5));
  return [
    1,
    start > 3 ? 'ellipsis' : 2,
    ...Array.from({ length: end - start + 1 }, (_, index) => start + index),
    end < totalPages - 2 ? 'ellipsis' : totalPages - 1,
    totalPages,
  ];
}

export function parsePageJump(
  input: string,
  totalPages: number,
): { page: number } | { error: string } {
  const value = input.trim();
  const page = Number(value);
  return /^\d+$/.test(value) && page >= 1 && page <= totalPages
    ? { page }
    : { error: `1~${totalPages} 사이의 페이지 번호를 입력해 주세요.` };
}

export function selectPageSize(value: string | null): number {
  const size = Number(value);
  return PAGE_SIZE_OPTIONS.find((option) => option === size) ?? DEFAULT_PAGE_SIZE;
}
