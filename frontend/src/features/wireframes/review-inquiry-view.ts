import type { InquiryResolution, InquiryStatus } from '@/features/wireframes/inquiry-state';

import {
  getSearchEvidenceFieldLabel,
  getSearchEvidenceSourceLabel,
  getSearchShotTypeLabel,
} from '@/features/wireframes/search-result-labels';
import {
  getVerificationStatusLabel,
  type VerificationStatus,
} from '@/features/wireframes/demo-scenes';
import { formatMediaTime } from '@/features/wireframes/scene-preview-media';
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
  display: '표시값',
  display_name: '클립 제목',
  shot_type: '샷 유형',
  scene_type: '장면 유형',
  broadcast_date: '방송일',
  filmed_date: '촬영일',
  scene_description: '장면 설명',
  start_time_ms: '시작',
  end_time_ms: '종료',
  verification_status: '검증 상태',
  match_evidence: '일치 근거',
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

// 출처 어휘는 `tag_evidence.source` 컬럼 주석이 여덟 값으로 닫아 둔 정본이다(baseline 마이그레이션).
// 같은 값을 검색 상세(`search-results-api.ts`)와 같은 문구로 옮긴다 — 한 출처를 화면마다 다른
// 이름으로 부르면 같은 근거인지 알 수 없다. 검증 상태는 검수자 판단인 `rejected`·`withdrawn`까지
// 담는다. S15P21A501-235가 그 둘을 뭉개지 않고 상세로 내보내므로 화면에 실제로 도달한다. 검증
// 상태 문구는 같은 이유로 `demo-scenes.ts`의 `verificationStatusLabels`를 따른다 — 검색 화면이
// 쓰는 말이고, F-10의 네 작업을 부르는 이름(반려·개입 해제)도 그쪽이다.
const evidenceLabels: Record<string, string> = {
  verified: '검증됨',
  unverified: '자동 인식',
  rejected: '반려됨',
  withdrawn: '개입 해제',
  scene: '장면',
  clip: '클립',
  applied: '적용',
  skipped: '건너뜀',
  failed: '실패',
};

export function evidenceLabel(value: string | null): string {
  if (!value) return '기록 없음';
  const searchLabel = getSearchEvidenceSourceLabel(value);
  if (searchLabel !== '정보 없음') return searchLabel;
  return evidenceLabels[value.toLowerCase()] ?? '정보 없음';
}

const unsafeSnapshotTextPattern =
  /(?:[a-z]:[\\/]|\/(?:srv|app|home|tmp|etc)\/|<[^>]+>|Bearer\s|-----BEGIN)/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isSafeSnapshotText(value: string): boolean {
  return !unsafeSnapshotTextPattern.test(value);
}

function displayVerificationStatus(value: unknown): string {
  const statuses: VerificationStatus[] = [
    'verified',
    'unverified',
    'unknown',
    'rejected',
    'withdrawn',
  ];
  return typeof value === 'string' && statuses.includes(value as VerificationStatus)
    ? getVerificationStatusLabel(value as VerificationStatus)
    : '정보 없음';
}

function displayDateFact(label: string, value: unknown): FilterFact | null {
  if (!isRecord(value)) return null;
  const date =
    value.value === null
      ? '미상'
      : typeof value.value === 'string' &&
          /^\d{4}-\d{2}-\d{2}$/.test(value.value) &&
          isSafeSnapshotText(value.value)
        ? value.value
        : null;
  if (date === null) return null;
  return {
    label,
    value: `${date} · ${displayVerificationStatus(value.verification_status)}`,
  };
}

function displaySafeTextFact(
  label: string,
  value: unknown,
  nullFallback: string | null = null,
): FilterFact | null {
  if (value === null && nullFallback !== null) {
    return { label, value: nullFallback };
  }
  if (typeof value !== 'string' || !value.trim() || !isSafeSnapshotText(value)) return null;
  return { label, value: value.trim() };
}

function getDisplayFacts(value: unknown): FilterFact[] {
  if (!isRecord(value)) return [];
  const facts: FilterFact[] = [];
  const displayName = displaySafeTextFact(
    snapshotLabels.display_name,
    value.display_name,
    '제목 없는 영상',
  );
  if (displayName) facts.push(displayName);
  const sceneType = displaySafeTextFact(snapshotLabels.scene_type, value.scene_type, '정보 없음');
  if (sceneType) facts.push(sceneType);
  if (typeof value.shot_type === 'string') {
    facts.push({ label: snapshotLabels.shot_type, value: getSearchShotTypeLabel(value.shot_type) });
  }
  const broadcastDate = displayDateFact(snapshotLabels.broadcast_date, value.broadcast_date);
  if (broadcastDate) facts.push(broadcastDate);
  const filmedDate = displayDateFact(snapshotLabels.filmed_date, value.filmed_date);
  if (filmedDate) facts.push(filmedDate);
  const sceneDescription = displaySafeTextFact(
    snapshotLabels.scene_description,
    value.scene_description,
  );
  if (sceneDescription) facts.push(sceneDescription);
  const startTimeMs = value.start_time_ms;
  const endTimeMs = value.end_time_ms;
  if (
    typeof startTimeMs === 'number' &&
    typeof endTimeMs === 'number' &&
    Number.isSafeInteger(startTimeMs) &&
    Number.isSafeInteger(endTimeMs) &&
    startTimeMs >= 0 &&
    startTimeMs < endTimeMs
  ) {
    facts.push({
      label: '구간',
      value: `${formatMediaTime(startTimeMs / 1000)} – ${formatMediaTime(endTimeMs / 1000)}`,
    });
  }
  return facts;
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
            if (key === 'display') {
              facts.push(...getDisplayFacts(child));
            } else {
              visit(child, [path, snapshotLabels[key]].filter(Boolean).join(' · '), depth + 1);
            }
          }
        }
      } else if (
        path &&
        (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean')
      ) {
        const text = String(item);
        if (isSafeSnapshotText(text)) {
          facts.push({
            label: path,
            value:
              path.endsWith(snapshotLabels.field) && typeof item === 'string'
                ? getSearchEvidenceFieldLabel(item)
                : path.endsWith(snapshotLabels.source) && typeof item === 'string'
                  ? evidenceLabel(item)
                  : path.endsWith(snapshotLabels.verification_status) && typeof item === 'string'
                    ? displayVerificationStatus(item)
                    : typeof item === 'boolean'
                      ? item
                        ? '예'
                        : '아니요'
                      : text,
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
  correction: 'bg-(--positive-soft)',
};
