import { routes } from '@/lib/routes';

import type { DateRange } from '@/features/wireframes/date-range';
import { validateDateRange } from '@/features/wireframes/date-range';

export interface SearchNavigationInput {
  query: string;
  broadcast: DateRange;
  filming: DateRange;
}

function appendDateRange(
  params: URLSearchParams,
  prefix: 'broadcast' | 'filming',
  range: DateRange,
) {
  if (!range.from || !range.to) return;
  params.set(`${prefix}From`, range.from);
  params.set(`${prefix}To`, range.to);
}

export function createSearchResultsHref({
  query,
  broadcast,
  filming,
}: SearchNavigationInput): string | null {
  const normalizedQuery = query.trim();
  if (!normalizedQuery || validateDateRange(broadcast) || validateDateRange(filming)) return null;

  const params = new URLSearchParams({ q: normalizedQuery });
  appendDateRange(params, 'broadcast', broadcast);
  appendDateRange(params, 'filming', filming);
  return `${routes.searchResults}?${params.toString()}`;
}

export function isSameSearchDestination(currentHref: string, nextHref: string): boolean {
  const baseUrl = 'https://npick.local';
  const current = new URL(currentHref, baseUrl);
  const next = new URL(nextHref, baseUrl);
  if (current.pathname !== next.pathname) return false;

  const normalizedSearch = (url: URL) =>
    new URLSearchParams(
      [...url.searchParams.entries()].sort(([leftKey, leftValue], [rightKey, rightValue]) =>
        leftKey === rightKey
          ? leftValue.localeCompare(rightValue)
          : leftKey.localeCompare(rightKey),
      ),
    ).toString();

  return normalizedSearch(current) === normalizedSearch(next);
}
