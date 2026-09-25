import { ApiClientError, fetchJson } from '@/lib/api/client';

/**
 * 해석 교정 규칙(`parse-rule/v1`) 후보 작성 계약.
 *
 * 어휘와 허용 키의 정본은 backend 의 `ParseRule`·`ResolutionAxis`·`ParseRuleJsonMapper` 다.
 * 서버가 모르는 키·모르는 어휘를 만나면 규칙 전체를 거부하므로 여기서도 닫아 둔다 (FRD F-11).
 */
export type ResolutionAxis =
  'intent' | 'date_windows' | 'incident_names' | 'entities' | 'locations' | 'expanded_terms';

export type ConditionOp = 'has_value' | 'has_type' | 'is_empty' | 'is_not_empty' | 'equals';

export type PatchOp = 'set' | 'unset' | 'add_item' | 'remove_item';

export interface ConditionPredicate {
  axis: ResolutionAxis;
  op: ConditionOp;
  type?: string;
  value?: string;
}

export interface PatchOperation {
  op: PatchOp;
  axis: ResolutionAxis;
  type?: string;
  value?: string;
  start?: string;
  end_exclusive?: string;
  value_from?: { axis: ResolutionAxis; type?: string; value?: string };
}

export interface ParseRuleCandidateBody {
  condition: {
    syntax_version: string;
    resolution_schema_version: string;
    all: ConditionPredicate[];
  };
  patch: { syntax_version: string; operations: PatchOperation[] };
  replacesRuleId?: string;
}

export interface ParsePatchCandidate {
  searchRuleId: string;
  feedbackId: string;
  active: boolean;
}

export const PARSE_RULE_SYNTAX_VERSION = 'parse-rule/v1';
export const RESOLUTION_SCHEMA_VERSION = 'query-resolver/v2';

export const resolutionAxisLabels: Record<ResolutionAxis, string> = {
  intent: '검색 의도',
  date_windows: '날짜 조건',
  incident_names: '사건명',
  entities: '인물·기관',
  locations: '장소·시설',
  expanded_terms: '검색 의미어',
};

export const conditionOpLabels: Record<ConditionOp, string> = {
  has_value: '이 값을 가진 항목이 있다',
  has_type: '이 유형인 항목이 있다',
  is_empty: '비어 있다',
  is_not_empty: '비어 있지 않다',
  equals: '값이 같다',
};

export const patchOpLabels: Record<PatchOp, string> = {
  set: '값 설정',
  unset: '값 해제',
  add_item: '항목 추가',
  remove_item: '항목 제거',
};

/** 목록 축이 아닌 유일한 축. 스칼라 축과 목록 축은 서로의 연산을 받지 않는다. */
const SCALAR_AXES: ReadonlySet<ResolutionAxis> = new Set<ResolutionAxis>(['intent']);
/** 값 문자열로 항목을 가리킬 수 있는 축. `date_windows` 는 구간으로 가리킨다. */
const VALUED_AXES: ReadonlySet<ResolutionAxis> = new Set<ResolutionAxis>([
  'incident_names',
  'entities',
  'locations',
  'expanded_terms',
]);
/** 항목에 유형이 있는 축. 나머지 축에 `type` 을 적으면 서버가 거부한다. */
const TYPED_AXES: ReadonlySet<ResolutionAxis> = new Set<ResolutionAxis>([
  'date_windows',
  'entities',
  'locations',
]);

const errorMessages: Record<string, string> = {
  SRCH_400_201: '규칙 후보 내용이 올바르지 않습니다. 조건과 변경 항목을 다시 확인해 주세요.',
  SRCH_400_202: '교체할 규칙을 찾을 수 없습니다. 교체 대상 규칙 번호를 확인해 주세요.',
  SRCH_403_201: '검수 권한이 없습니다. 아카이브 팀 계정으로 로그인해 주세요.',
  SRCH_403_202: '담당 검수자만 규칙 후보를 저장할 수 있습니다.',
  SRCH_404_201: '문의를 찾을 수 없습니다. 목록에서 최신 상태를 다시 확인해 주세요.',
  SRCH_409_201: '검수 중인 문의만 규칙 후보를 저장할 수 있습니다.',
  SRCH_409_202: '해석 교정으로 판정한 문의만 규칙 후보를 저장할 수 있습니다.',
  SRCH_409_203: '문의 당시 검색에 교정할 해석 결과가 없습니다.',
  SRCH_409_204:
    '이 문의에 저장해 둔 규칙 후보가 너무 많습니다. 새로 만들기 전에 지금 후보로 검증을 진행해 주세요.',
  SRCH_409_205:
    '같은 규칙을 교체하는 대기 후보가 이미 있습니다. 이전 교정 후보를 폐기한 뒤 다시 저장해 주세요.',
};

/** 이 화면이 직접 안내할 수 있는 오류인지. 아니면 공통 오류 문구를 그대로 쓴다. */
export function parseRuleErrorMessage(error: unknown): string | null {
  return error instanceof ApiClientError ? (errorMessages[error.code] ?? null) : null;
}

function blank(value: string | undefined): boolean {
  return value === undefined || !value.trim();
}

function predicateProblem(predicate: ConditionPredicate, index: number): string | null {
  const where = `조건 ${index + 1}번`;
  const label = resolutionAxisLabels[predicate.axis];
  const isScalar = SCALAR_AXES.has(predicate.axis);
  if (isScalar !== (predicate.op === 'equals')) {
    return `${where}: ${label} 항목에 '${conditionOpLabels[predicate.op]}' 조건은 쓸 수 없습니다.`;
  }
  const usesValue = predicate.op === 'equals' || predicate.op === 'has_value';
  const usesType = predicate.op === 'has_type';
  if (!usesValue && !blank(predicate.value)) return `${where}: 이 조건은 값을 쓰지 않습니다.`;
  if (!usesType && !blank(predicate.type)) return `${where}: 이 조건은 유형을 쓰지 않습니다.`;
  if (usesValue && blank(predicate.value)) return `${where}: 비교할 값을 입력해 주세요.`;
  if (usesType) {
    if (!TYPED_AXES.has(predicate.axis)) return `${where}: ${label} 항목에는 유형이 없습니다.`;
    if (blank(predicate.type)) return `${where}: 찾을 유형을 입력해 주세요.`;
  }
  if (predicate.op === 'has_value' && !VALUED_AXES.has(predicate.axis)) {
    return `${where}: ${label} 항목은 값으로 찾을 수 없습니다.`;
  }
  return null;
}

function operationProblem(operation: PatchOperation, index: number): string | null {
  const where = `변경 ${index + 1}번`;
  const label = resolutionAxisLabels[operation.axis];
  const forList = operation.op === 'add_item' || operation.op === 'remove_item';
  if (SCALAR_AXES.has(operation.axis) === forList) {
    return `${where}: ${label} 항목에 '${patchOpLabels[operation.op]}' 는 쓸 수 없습니다.`;
  }
  if (operation.value_from) {
    if (operation.op !== 'add_item')
      return `${where}: 원본 값 재사용은 항목 추가에만 쓸 수 있습니다.`;
    if (!VALUED_AXES.has(operation.axis) || !VALUED_AXES.has(operation.value_from.axis)) {
      return `${where}: 날짜 조건은 원본 값으로 재사용할 수 없습니다.`;
    }
    if (blank(operation.value_from.value)) return `${where}: 가져올 원본 값을 입력해 주세요.`;
    if (!blank(operation.value)) return `${where}: 값과 원본 값 재사용은 함께 쓸 수 없습니다.`;
    if (TYPED_AXES.has(operation.value_from.axis) === blank(operation.value_from.type)) {
      return `${where}: 가져올 원본 항목의 유형을 확인해 주세요.`;
    }
  }
  if (operation.op === 'unset') {
    return blank(operation.value) && blank(operation.type)
      ? null
      : `${where}: 값 해제는 다른 입력을 쓰지 않습니다.`;
  }
  if (operation.op === 'set') {
    return blank(operation.value) ? `${where}: 설정할 값을 입력해 주세요.` : null;
  }
  if (TYPED_AXES.has(operation.axis) === blank(operation.type)) {
    return TYPED_AXES.has(operation.axis)
      ? `${where}: ${label} 항목의 유형을 입력해 주세요.`
      : `${where}: ${label} 항목에는 유형이 없습니다.`;
  }
  if (operation.axis === 'date_windows') {
    if (!blank(operation.value)) return `${where}: 날짜 조건은 값이 아니라 구간으로 가리킵니다.`;
    return blank(operation.start) || blank(operation.end_exclusive)
      ? `${where}: 시작일과 종료일을 모두 입력해 주세요.`
      : null;
  }
  if (!blank(operation.start) || !blank(operation.end_exclusive)) {
    return `${where}: ${label} 항목에는 날짜 구간을 쓰지 않습니다.`;
  }
  return blank(operation.value) && !operation.value_from
    ? `${where}: 대상 값을 입력해 주세요.`
    : null;
}

/**
 * 서버가 확실히 거부할 본문을 전송 전에 막는다. 사유 문구를 돌려주고, 보낼 수 있으면 `null`.
 *
 * 유형·intent 어휘 대조는 하지 않는다 — 정본이 backend 에 있고 여기서 복제하면 두 벌이 어긋난다.
 */
export function validateParseRuleBody(body: ParseRuleCandidateBody): string | null {
  if (body.condition.syntax_version !== body.patch.syntax_version) {
    return '조건과 변경의 규칙 문법 버전이 다릅니다.';
  }
  if (body.condition.all.length === 0) return '적용 조건을 하나 이상 추가해 주세요.';
  if (body.patch.operations.length === 0) return '변경 내용을 하나 이상 추가해 주세요.';
  if (body.replacesRuleId !== undefined && !/^[1-9]\d*$/.test(body.replacesRuleId)) {
    return '교체 대상 규칙 번호는 양의 정수로 입력해 주세요.';
  }
  for (const [index, predicate] of body.condition.all.entries()) {
    const problem = predicateProblem(predicate, index);
    if (problem) return problem;
  }
  for (const [index, operation] of body.patch.operations.entries()) {
    const problem = operationProblem(operation, index);
    if (problem) return problem;
  }
  return null;
}

/** 빈 문자열 키는 아예 뺀다. `null` 이나 `""` 을 보내면 서버가 형식 위반으로 읽는다. */
function withText<Key extends string>(
  target: Record<string, unknown>,
  key: Key,
  value: string | undefined,
): void {
  if (!blank(value)) target[key] = value!.trim();
}

function toRequestBody(body: ParseRuleCandidateBody): Record<string, unknown> {
  const request: Record<string, unknown> = {
    condition: {
      syntax_version: body.condition.syntax_version,
      resolution_schema_version: body.condition.resolution_schema_version,
      all: body.condition.all.map((predicate) => {
        const sent: Record<string, unknown> = { axis: predicate.axis, op: predicate.op };
        withText(sent, 'type', predicate.type);
        withText(sent, 'value', predicate.value);
        return sent;
      }),
    },
    patch: {
      syntax_version: body.patch.syntax_version,
      operations: body.patch.operations.map((operation) => {
        const sent: Record<string, unknown> = { op: operation.op, axis: operation.axis };
        withText(sent, 'type', operation.type);
        withText(sent, 'value', operation.value);
        withText(sent, 'start', operation.start);
        withText(sent, 'end_exclusive', operation.end_exclusive);
        if (operation.value_from) {
          const from: Record<string, unknown> = { axis: operation.value_from.axis };
          withText(from, 'type', operation.value_from.type);
          withText(from, 'value', operation.value_from.value);
          sent.value_from = from;
        }
        return sent;
      }),
    },
  };
  if (body.replacesRuleId !== undefined) request.replacesRuleId = body.replacesRuleId;
  return request;
}

function identifier(value: unknown): string {
  // TSID 는 정밀도 때문에 문자열로 온다. Number 로 바꾸지 않는다.
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) {
    throw new ApiClientError('invalid-response', 200);
  }
  return value;
}

function parseCandidate(value: unknown): ParsePatchCandidate {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiClientError('invalid-response', 200);
  }
  const data = value as Record<string, unknown>;
  if (typeof data.active !== 'boolean') throw new ApiClientError('invalid-response', 200);
  return {
    searchRuleId: identifier(data.searchRuleId),
    feedbackId: identifier(data.feedbackId),
    active: data.active,
  };
}

/** 대기 중인 이 문의의 해석 교정 후보를 폐기한다. 확정된 규칙·장면 제외는 건드리지 않는다. 200 반환. */
export async function discardParsePatchCandidate(
  feedbackId: string,
  signal?: AbortSignal,
): Promise<void> {
  identifier(feedbackId);
  await fetchJson<unknown>(`/review/inquiries/${feedbackId}/parse-patch-candidate`, {
    method: 'DELETE',
    signal,
  });
}

/**
 * 해석 교정 후보를 저장한다. 신규는 `201`, 같은 멱등성 키의 재요청은 기존 후보를 `200` 으로 돌려준다 —
 * 공통 client 가 둘을 구분하지 않고 성공으로 읽는다.
 */
export async function createParsePatchCandidate(
  feedbackId: string,
  body: ParseRuleCandidateBody,
  idempotencyKey: string,
  signal?: AbortSignal,
): Promise<ParsePatchCandidate> {
  identifier(feedbackId);
  const problem = validateParseRuleBody(body);
  if (problem) {
    throw new ApiClientError('api', 0, { code: 'CLIENT_PARSE_RULE_INVALID', message: problem });
  }
  return parseCandidate(
    await fetchJson<unknown>(`/review/inquiries/${feedbackId}/parse-patch-candidate`, {
      method: 'POST',
      body: toRequestBody(body),
      idempotencyKey,
      signal,
    }),
  );
}
