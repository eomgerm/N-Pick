import type { ResultSnapshot } from '@/features/wireframes/my-inquiry-api';

const NO_TITLE = '제목 없는 영상';

/** available 스냅샷의 당시 표시명. 빈 문자열·null·display 블록 부재는 "당시 제목 없음"으로 본다. */
function readSnapshotDisplayName(snapshot: ResultSnapshot): string | null {
  const display = snapshot.explain.display;
  if (display === null || typeof display !== 'object') return null;
  const name = (display as Record<string, unknown>).display_name;
  return typeof name === 'string' && name.length > 0 ? name : null;
}

/**
 * 결과 카드 제목을 재생성 금지(FRD §7.2) 원칙으로 결정한다.
 *
 * available(snapshot !== null): 당시 기록된 표시명만 쓰고, 없었으면 대체 문구를 쓴다 — 현재 장면 제목으로
 * 재구성하지 않는다. 기록에 제목이 없었다는 사실이 그대로 남아야 한다.
 * unavailable(snapshot === null): 스냅샷이 없으므로 현재 장면 제목으로 대체한다.
 */
export function resolveInquiryResultTitle(
  snapshot: ResultSnapshot | null,
  currentClipTitle: string | null,
): string {
  if (snapshot !== null) return readSnapshotDisplayName(snapshot) ?? NO_TITLE;
  return currentClipTitle ?? NO_TITLE;
}
