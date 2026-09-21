import type { InquiryResolution, InquiryStatus } from '@/features/wireframes/inquiry-state';

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

const snapshotLabels: Record<string, string> = {
  score: '점수',
  reason: '이유',
  explanation: '설명',
  summary: '요약',
  match: '일치 근거',
  evidence: '근거',
  tags: '태그',
  tag_name: '태그명',
  tagName: '태그명',
  value: '값',
  text: '근거 내용',
  source: '출처',
  verified_state: '검증 상태',
  verifiedState: '검증 상태',
  scope: '적용 범위',
  rule_id: '규칙 ID',
  ruleId: '규칙 ID',
  status: '처리 상태',
  order: '적용 순서',
  applied: '적용 여부',
  scene_id: '장면 ID',
  sceneId: '장면 ID',
  conditions: '조건',
  operations: '변경 연산',
  field: '항목',
  op: '연산',
};

export function evidenceLabel(value: string | null): string {
  if (!value) return '기록 없음';
  const labels: Record<string, string> = {
    ocr: '화면 문자',
    asr: '음성 인식',
    subtitle: '자막',
    original_metadata: '원본 메타데이터',
    user_input: '사용자 입력',
    verified: '검증됨',
    unverified: '자동 인식',
    rejected: '거부됨',
    scene: '장면',
    clip: '클립',
    applied: '적용',
    skipped: '건너뜀',
    failed: '실패',
  };
  return labels[value.toLowerCase()] ?? '알 수 없는 값';
}

// Known display fields only: never render resolver output, paths, or arbitrary JSON keys.
export function getSnapshotFacts(value: string | null): FilterFact[] | null {
  if (!value) return [];
  try {
    const parsed: unknown = JSON.parse(value);
    const facts: FilterFact[] = [];
    function visit(item: unknown, path: string, depth: number) {
      if (depth > 8) return;
      if (Array.isArray(item)) {
        item.forEach((child, index) => visit(child, `${path} ${index + 1}`.trim(), depth + 1));
      } else if (item !== null && typeof item === 'object') {
        for (const [key, child] of Object.entries(item)) {
          if (Object.hasOwn(snapshotLabels, key)) {
            visit(child, [path, snapshotLabels[key]].filter(Boolean).join(' · '), depth + 1);
          }
        }
      } else if (
        path &&
        (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean')
      ) {
        const text = String(item);
        if (
          !/(?:[a-z]:[\\/]|\/(?:srv|app|home|tmp|etc)\/|<[^>]+>|Bearer\s|-----BEGIN)/i.test(text)
        ) {
          facts.push({
            label: path,
            value: typeof item === 'boolean' ? (item ? '예' : '아니요') : text,
          });
        }
      }
    }
    visit(parsed, '', 0);
    return facts.length > 0 || countSnapshotEntries(value) === 0 ? facts : null;
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
          '다른 아카이브 팀이 먼저 시작했을 수 있습니다. 최신 담당자와 상태를 다시 확인해 주세요.',
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

export function selectInquiryStatus(value: string | null): InquiryStatus | undefined {
  return value === 'open' || value === 'reviewing' || value === 'closed' ? value : undefined;
}

export function selectInquiryPage(value: string | null): number {
  const page = Number(value ?? '1');
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

export function formatInquiryDate(value: string | null): string {
  if (!value) return '기록 없음';
  const date = new Date(value);
  if (Number.isNaN(date.valueOf())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export function formatInquiryTimecode(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  return [hours, minutes, seconds].map((part) => String(part).padStart(2, '0')).join(':');
}

export function normalizeInquiryPage(page: number, totalPages: number): number {
  return totalPages === 0 ? 1 : Math.min(page, totalPages);
}

export const inquiryResolutionClasses: Record<InquiryResolution, string> = {
  exclude_scene: 'bg-(--positive-soft)',
  no_action: 'bg-(--surface-muted)',
  deferred: 'bg-(--warning-soft)',
  tag_correction: 'bg-(--positive-soft)',
  patch_parse: 'bg-(--positive-soft)',
};
