import { ApiClientError, fetchJson } from '@/lib/api/client';
import { createIdempotencyKey } from '@/lib/api/idempotency';

export type ClipSourceType = 'broadcast' | 'archive';

export interface ClipRegistrationSnapshot {
  video: File;
  sourceType: ClipSourceType;
  title: string;
  broadcastDate: string;
  filmedDate: string;
  subtitle: File | null;
  script: File | null;
  rightsConfirmed: true;
  externalProcessingConfirmed: true;
}

export interface ClipRegistrationSubmission {
  key: string;
  snapshot: Readonly<ClipRegistrationSnapshot>;
}

export interface ClipRegistrationResult {
  clipId: string;
  pipelineRunId: string;
  status: 'queued';
}

export type RegistrationField =
  | 'video'
  | 'sourceType'
  | 'title'
  | 'broadcastDate'
  | 'filmedDate'
  | 'subtitle'
  | 'scriptText'
  | 'rightsConfirmed'
  | 'externalProcessingConfirmed';

export type RegistrationFieldErrors = Partial<Record<RegistrationField, string>>;
export type RegistrationRetryMode = 'none' | 'same-request' | 'new-request';

export interface ClipRegistrationErrorPresentation {
  fieldErrors: RegistrationFieldErrors;
  retryMode: RegistrationRetryMode;
  showGlobal: boolean;
}

// 원인을 한 문구로 덮으면 "UTF-8 로 고쳤는데도 안 된다" 가 된다. 고쳐야 할 것이 파일 내용인지
// 선택 자체인지 알려면 두 실패를 갈라야 한다 (S15P21A501-258).
class ScriptFileError extends Error {}

class ScriptTextDecodeError extends ScriptFileError {
  constructor() {
    super('대본 파일을 UTF-8 로 읽지 못했어요. UTF-8 로 저장한 TXT 파일을 선택해 주세요.');
    this.name = 'ScriptTextDecodeError';
  }
}

/** 파일을 고른 뒤 같은 자리에 다시 저장하거나 옮기면 브라우저가 쥔 File 핸들이 무효가 된다. */
class ScriptFileReadError extends ScriptFileError {
  constructor() {
    super('대본 파일을 읽지 못했어요. 파일이 바뀌었을 수 있으니 다시 선택해 주세요.');
    this.name = 'ScriptFileReadError';
  }
}

export function createClipRegistrationSubmission(
  snapshot: ClipRegistrationSnapshot,
  keyFactory: () => string = createIdempotencyKey,
): ClipRegistrationSubmission {
  return { key: keyFactory(), snapshot: Object.freeze({ ...snapshot }) };
}

async function readScriptText(file: File): Promise<string> {
  let bytes: ArrayBuffer;
  try {
    bytes = await file.arrayBuffer();
  } catch {
    throw new ScriptFileReadError();
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    throw new ScriptTextDecodeError();
  }
}

export async function createClipRegistrationFormData(
  snapshot: Readonly<ClipRegistrationSnapshot>,
): Promise<FormData> {
  const body = new FormData();
  body.append('video', snapshot.video);
  body.append('source_type', snapshot.sourceType);
  if (snapshot.title) body.append('title', snapshot.title);
  if (snapshot.sourceType === 'broadcast' && snapshot.broadcastDate) {
    body.append('broadcast_date', snapshot.broadcastDate);
  }
  if (snapshot.filmedDate) body.append('filmed_date', snapshot.filmedDate);
  if (snapshot.subtitle) body.append('subtitle', snapshot.subtitle);
  if (snapshot.script) body.append('script_text', await readScriptText(snapshot.script));
  body.append('rights_confirmed', String(snapshot.rightsConfirmed));
  body.append('external_processing_confirmed', String(snapshot.externalProcessingConfirmed));
  return body;
}

export function parseClipRegistrationResponse(value: unknown): ClipRegistrationResult {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiClientError('invalid-response', 201);
  }
  const payload = value as Record<string, unknown>;
  if (
    typeof payload.clip_id !== 'string' ||
    !/^[1-9]\d*$/.test(payload.clip_id) ||
    typeof payload.pipeline_run_id !== 'string' ||
    !/^[1-9]\d*$/.test(payload.pipeline_run_id) ||
    payload.status !== 'queued'
  ) {
    throw new ApiClientError('invalid-response', 201);
  }
  return {
    clipId: payload.clip_id,
    pipelineRunId: payload.pipeline_run_id,
    status: payload.status,
  };
}

export async function registerClip(
  submission: ClipRegistrationSubmission,
  signal?: AbortSignal,
): Promise<ClipRegistrationResult> {
  const response = await fetchJson<unknown>('/clips', {
    method: 'POST',
    body: await createClipRegistrationFormData(submission.snapshot),
    idempotencyKey: submission.key,
    signal,
  });
  return parseClipRegistrationResponse(response);
}

const validationFieldMap: Record<string, RegistrationField> = {
  video: 'video',
  videoContentPresent: 'video',
  videoMediaTypeValid: 'video',
  sourceType: 'sourceType',
  broadcastDateAllowed: 'sourceType',
  title: 'title',
  broadcastDateValid: 'broadcastDate',
  filmedDateValid: 'filmedDate',
  subtitle: 'subtitle',
  subtitleContentPresent: 'subtitle',
  scriptText: 'scriptText',
  rightsConfirmed: 'rightsConfirmed',
  externalProcessingConfirmed: 'externalProcessingConfirmed',
};

const codeFieldMap: Record<string, RegistrationField> = {
  CLIP_400_001: 'video',
  CLIP_400_002: 'sourceType',
  CLIP_400_003: 'sourceType',
  CLIP_400_004: 'title',
  CLIP_400_005: 'video',
  CLIP_400_006: 'video',
  CLIP_400_007: 'video',
  CLIP_400_008: 'video',
  CLIP_400_009: 'rightsConfirmed',
  CLIP_400_010: 'externalProcessingConfirmed',
  CLIP_400_011: 'filmedDate',
  CLIP_400_012: 'subtitle',
  CLIP_400_013: 'scriptText',
};

function safeFieldMessage(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const message = value.trim();
  if (!message || message.length > 300 || !/[가-힣]/.test(message)) return undefined;
  if (
    /[<>{}]|https?:\/\/|[a-z]:[\\/]|(?:^|[\s(:])\/\S+|\\|\b\w*(?:Exception|Error)\b|\bat\s+[\w.$]+\(/i.test(
      message,
    ) ||
    /\p{Cc}/u.test(message.replace(/[\r\n\t]/g, ''))
  ) {
    return undefined;
  }
  return message;
}

interface ParsedValidationFields {
  fields: RegistrationFieldErrors;
  hasUnmapped: boolean;
}

function validationFieldsFrom(error: ApiClientError): ParsedValidationFields {
  if (error.code !== 'COMM_400_001') return { fields: {}, hasUnmapped: false };
  const response = error.diagnostics?.response;
  if (response === null || typeof response !== 'object' || Array.isArray(response)) {
    return { fields: {}, hasUnmapped: false };
  }
  const data = (response as Record<string, unknown>).data;
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return { fields: {}, hasUnmapped: false };
  }

  const fields: RegistrationFieldErrors = {};
  let hasUnmapped = false;
  for (const [serverField, value] of Object.entries(data)) {
    const field = validationFieldMap[serverField];
    const message = safeFieldMessage(value);
    if (!field || !message) {
      hasUnmapped = true;
    } else if (fields[field] === undefined) {
      fields[field] = message;
    }
  }
  return { fields, hasUnmapped };
}

export function getClipRegistrationErrorPresentation(
  error: unknown,
): ClipRegistrationErrorPresentation {
  if (error instanceof ScriptFileError) {
    return {
      fieldErrors: { scriptText: error.message },
      retryMode: 'none',
      showGlobal: false,
    };
  }
  if (!(error instanceof ApiClientError)) {
    return { fieldErrors: {}, retryMode: 'same-request', showGlobal: true };
  }

  const validation = validationFieldsFrom(error);
  const codeField = codeFieldMap[error.code];
  const fieldErrors = { ...validation.fields };
  if (codeField && fieldErrors[codeField] === undefined) fieldErrors[codeField] = error.message;

  const retryMode = ['CLIP_409_001', 'CLIP_409_003'].includes(error.code)
    ? 'new-request'
    : error.status === 403 ||
        error.kind === 'network' ||
        error.kind === 'aborted' ||
        error.kind === 'invalid-response' ||
        error.status >= 500 ||
        ['CLIP_409_002', 'CLIP_503_008'].includes(error.code)
      ? 'same-request'
      : 'none';

  return {
    fieldErrors,
    retryMode,
    showGlobal:
      validation.hasUnmapped || Object.keys(fieldErrors).length === 0 || retryMode !== 'none',
  };
}
