import type {
  CorrectionCandidateTag,
  ReviewTagScope,
  ReviewTagType,
} from '@/features/wireframes/review-inquiry-api';

export interface AddedTag {
  id: string;
  scope: ReviewTagScope;
  tagType: ReviewTagType;
  value: string;
  /** 이 추가가 서버에 만든 대기 근거. 추가 취소는 이 근거만 지운다. */
  evidenceIds: string[];
}

/** 현재 태그 칩에 붙일 곳이 없는 대기 후보 — 개입 해제(WITHDRAW)나 현재 태그에 없는 태깅의 삭제(REJECT). */
export interface OtherTagCandidate extends AddedTag {
  action: 'REJECT' | 'WITHDRAW';
}

export interface RestoredTagCandidates {
  added: AddedTag[];
  /** 삭제 후보로 만든 현재 태그(taggingId) → 그 REJECT 대기 근거. */
  removed: Map<string, string[]>;
  other: OtherTagCandidate[];
}

/**
 * 서버의 대기 태그 후보(GET correction-candidates)를 태그 교정 화면 상태로 되돌린다 (S15P21A501-317).
 * APPROVE 는 추가 칩, 현재 태그(evidence)와 taggingId 가 맞는 REJECT 는 삭제 후보, 나머지는 기타 후보.
 */
export function restoreTagCandidates(
  tags: readonly CorrectionCandidateTag[],
  evidence: ReadonlyArray<{ taggingId: string }>,
): RestoredTagCandidates {
  const current = new Set(evidence.map((item) => item.taggingId));
  const restored: RestoredTagCandidates = { added: [], removed: new Map(), other: [] };
  for (const tag of tags) {
    const base = {
      id: `candidate-${tag.evidenceId}`,
      scope: tag.scope,
      tagType: tag.tagType,
      value: tag.displayName,
      evidenceIds: [tag.evidenceId],
    };
    if (tag.action === 'APPROVE') {
      restored.added.push(base);
    } else if (tag.action === 'REJECT' && current.has(tag.taggingId)) {
      restored.removed.set(tag.taggingId, [
        ...(restored.removed.get(tag.taggingId) ?? []),
        tag.evidenceId,
      ]);
    } else {
      restored.other.push({ ...base, action: tag.action });
    }
  }
  return restored;
}

/**
 * 새 추가 칩을 붙인다. 서버는 같은 변경안에 기존 대기 근거를 돌려주므로(자연 키 중복 제거), 이미 보이는
 * 근거로만 된 추가는 다시 붙이지 않는다 — 복원 칩과 로컬 칩이 겹쳐 두 번 세지 않게 한다.
 */
export function appendAddedTag(current: readonly AddedTag[], next: AddedTag): AddedTag[] {
  const known = new Set(current.flatMap((tag) => tag.evidenceIds));
  // 한 응답에 같은 근거 id 가 여러 번 올 수 있다(같은 변경안 반복) — 한 번만 둔다.
  const evidenceIds = [...new Set(next.evidenceIds)];
  if (evidenceIds.length > 0 && evidenceIds.every((id) => known.has(id))) {
    return [...current];
  }
  return [...current, { ...next, evidenceIds }];
}
