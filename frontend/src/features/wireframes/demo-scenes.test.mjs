import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '@/features/wireframes/wireframe.module.css') {
      return {
        url: `data:text/javascript,${encodeURIComponent(
          'export default { imageOne: "imageOne", imageTwo: "imageTwo", imageThree: "imageThree" };',
        )}`,
        shortCircuit: true,
      };
    }
    if (specifier.startsWith('@/')) {
      return {
        url: new URL(`../../${specifier.slice(2)}.ts`, import.meta.url).href,
        shortCircuit: true,
      };
    }
    return nextResolve(specifier, context);
  },
});

const { getVerificationStatusLabel, results } = await import('./demo-scenes.ts');
const { formatMediaTime } = await import('./scene-preview-media.ts');

test('기본 검색 결과는 고유 ID와 순위 1~10을 가진 유효한 장면 10개다', () => {
  assert.equal(results.length, 10);
  assert.deepEqual(
    results.map(({ rank }) => rank),
    Array.from({ length: 10 }, (_, index) => index + 1),
  );
  assert.equal(new Set(results.map(({ id }) => id)).size, 10);
  assert.equal(new Set(results.map(({ rank }) => rank)).size, 10);

  for (const result of results) {
    assert.ok(result.sceneStart >= 0);
    assert.ok(result.sceneStart < result.sceneEnd);
    assert.ok(result.sceneEnd <= result.totalSeconds);
    assert.equal(
      result.time,
      `${formatMediaTime(result.sceneStart)} - ${formatMediaTime(result.sceneEnd)}`,
    );
  }
});

test('방송일과 촬영일 정보 없음 경로를 각각 제공한다', () => {
  assert.ok(results.some(({ broadcastDate }) => broadcastDate === null));
  assert.ok(results.some(({ filmedDate }) => filmedDate === null));
});

test('모든 검색 결과는 필드·값·출처와 자동 근거 검증 상태를 명시한다', () => {
  const statuses = new Set();

  for (const { matchEvidence } of results) {
    assert.ok(matchEvidence.field.length > 0);
    assert.ok(matchEvidence.value.length > 0);
    assert.ok(matchEvidence.source.length > 0);
    assert.ok(['verified', 'unverified'].includes(matchEvidence.status));
    statuses.add(matchEvidence.status);
  }

  assert.deepEqual(statuses, new Set(['verified', 'unverified']));
});

test('검증 상태는 색상 없이도 구분되는 한국어 텍스트를 제공한다', () => {
  assert.deepEqual(
    ['verified', 'unverified', 'unknown', 'rejected', 'withdrawn'].map(getVerificationStatusLabel),
    ['검증됨', '자동 인식', '미상', '반려됨', '개입 해제'],
  );
});

test('검색 기록과 Preview가 참조하는 기존 장면 1~3의 값은 보존한다', () => {
  assert.deepEqual(
    results.slice(0, 3).map((result) => ({
      id: result.id,
      title: result.title,
      clip: result.clip,
      time: result.time,
      duration: result.duration,
      sceneStart: result.sceneStart,
      sceneEnd: result.sceneEnd,
      totalDuration: result.totalDuration,
      totalSeconds: result.totalSeconds,
      evidenceType: result.evidenceType,
      evidence: result.evidence,
      matchedKeywords: result.matchedKeywords,
      source: result.source,
      score: result.score,
      imageClass: result.imageClass,
      imageLabel: result.imageLabel,
    })),
    [
      {
        id: 1,
        title: '설 연휴 첫날, 서울역 귀성 인파',
        clip: 'KBC_20260214_뉴스9_교통.mp4',
        time: '00:42 - 00:49',
        duration: '7초',
        sceneStart: 42,
        sceneEnd: 49,
        totalDuration: '02:18',
        totalSeconds: 138,
        evidenceType: 'OCR',
        evidence: '서울역 · 설 연휴 귀성객',
        matchedKeywords: [
          { keyword: '서울역', origin: 'user' },
          { keyword: '귀성객', origin: 'expanded' },
        ],
        source: 'Keyframe OCR · 검증됨',
        score: 96,
        imageClass: 'imageOne',
        imageLabel: '명절 귀성객으로 붐비는 서울역 대합실',
      },
      {
        id: 2,
        title: '경부고속도로 양방향 정체',
        clip: 'KBC_20250930_추석교통.mp4',
        time: '01:13 - 01:20',
        duration: '7초',
        sceneStart: 73,
        sceneEnd: 80,
        totalDuration: '02:45',
        totalSeconds: 165,
        evidenceType: '화면 설명',
        evidence: '해 질 무렵 정체된 고속도로',
        matchedKeywords: [
          { keyword: '정체', origin: 'user' },
          { keyword: '고속도로', origin: 'expanded' },
        ],
        source: 'VLM caption · 미검증',
        score: 89,
        imageClass: 'imageTwo',
        imageLabel: '해 질 무렵 차량이 정체된 고속도로',
      },
      {
        id: 3,
        title: '한국도로공사 교통상황실',
        clip: 'KBC_20251002_도로공사.mp4',
        time: '00:18 - 00:27',
        duration: '9초',
        sceneStart: 18,
        sceneEnd: 27,
        totalDuration: '02:04',
        totalSeconds: 124,
        evidenceType: 'Transcript',
        evidence: '귀성길 주요 구간 소통 상황입니다',
        matchedKeywords: [
          { keyword: '귀성길', origin: 'user' },
          { keyword: '소통 상황', origin: 'expanded' },
        ],
        source: '방송 자막 · 미검증',
        score: 84,
        imageClass: 'imageThree',
        imageLabel: '도로 CCTV 화면을 확인하는 교통상황실',
      },
    ],
  );
});
