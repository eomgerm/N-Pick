import { ApiClientError } from '@/lib/api/error';

const filterLabels: Record<string, string> = {
  broadcast_date: '방송일',
  broadcastDate: '방송일',
  filmed_date: '촬영일',
  filming_date: '촬영일',
  filmingDate: '촬영일',
  date: '날짜',
  start: '시작',
  end: '종료',
  end_exclusive: '종료',
  location: '장소',
  locations: '장소',
  incident_name: '사건·주제',
  incident_names: '사건·주제',
  shot_type: '장면 유형',
  shotType: '장면 유형',
};

export interface InquiryTagLike {
  tagName: string;
}

export interface FilterFact {
  label: string;
  value: string;
}

export type ClaimRecoveryAction = 'retry' | 'refresh' | 'back';

export interface ClaimRecovery {
  action: ClaimRecoveryAction;
  actionLabel: string;
  message: string;
}

export function displayClipTitle(value: string | null): string {
  return value?.trim() || '제목 없는 영상';
}

export function uniqueTagNames(evidence: InquiryTagLike[]): string[] {
  return [...new Set(evidence.map(({ tagName }) => tagName.trim()).filter(Boolean))];
}

function displayFilterValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '지정하지 않음';
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (typeof value === 'boolean') return value ? '예' : '아니요';
  if (Array.isArray(value)) {
    const items = value.map(displayFilterValue).filter((item) => item !== '지정하지 않음');
    return items.join(', ') || '지정하지 않음';
  }
  if (typeof value === 'object') {
    const items = Object.entries(value).map(
      ([key, item]) => `${filterLabels[key] ?? key}: ${displayFilterValue(item)}`,
    );
    return items.join(' · ') || '지정하지 않음';
  }
  return '확인할 수 없음';
}

export function getFilterFacts(value: string | null): FilterFact[] | null {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return Object.entries(parsed).map(([key, item]) => ({
      label: filterLabels[key] ?? key,
      value: displayFilterValue(item),
    }));
  } catch {
    return null;
  }
}

export function countSnapshotEntries(value: string | null): number | null {
  if (!value) return 0;
  try {
    const parsed: unknown = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.length;
    if (parsed !== null && typeof parsed === 'object') return Object.keys(parsed).length;
    return 1;
  } catch {
    return null;
  }
}

export function getClaimRecovery(error: unknown): ClaimRecovery {
  if (error instanceof ApiClientError) {
    if (error.code === 'FEEDBACK_409_001') {
      return {
        action: 'refresh',
        actionLabel: '최신 상태 확인',
        message:
          '다른 검수자가 먼저 시작했을 수 있습니다. 최신 담당자와 상태를 다시 확인해 주세요.',
      };
    }
    if (error.status === 403) {
      return {
        action: 'back',
        actionLabel: '문의 목록으로',
        message: '이 문의를 맡을 권한이 없습니다. 문의 목록으로 돌아가 접근 권한을 확인해 주세요.',
      };
    }
    if (error.status === 404) {
      return {
        action: 'back',
        actionLabel: '문의 목록으로',
        message: '문의가 더 이상 존재하지 않습니다. 목록에서 최신 문의를 확인해 주세요.',
      };
    }
    if (error.kind === 'network' || error.kind === 'aborted') {
      return {
        action: 'retry',
        actionLabel: '검수 시작 다시 시도',
        message: '연결 상태를 확인한 뒤 다시 시도해 주세요. 같은 요청으로 안전하게 재시도합니다.',
      };
    }
  }
  return {
    action: 'refresh',
    actionLabel: '최신 상태 확인',
    message: '성공 여부를 확인할 수 없습니다. 문의 상태를 다시 불러온 뒤 진행해 주세요.',
  };
}
