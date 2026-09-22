import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice('@/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { registrationDateBounds, registrationToday, validateRegistrationDates } =
  await import('./registration-dates.ts');

const TODAY = '2026-09-21';

function dates(overrides = {}) {
  return { sourceType: 'broadcast', broadcastDate: '', filmedDate: '', ...overrides };
}

test('미래 촬영일을 촬영일 오류로 안내한다', () => {
  assert.deepEqual(validateRegistrationDates(dates({ filmedDate: '2026-09-22' }), TODAY), {
    broadcastDate: undefined,
    filmedDate: '촬영일은 오늘 이후 날짜로 입력할 수 없습니다.',
  });
});

test('미래 방송일을 방송일 오류로 안내한다', () => {
  assert.deepEqual(validateRegistrationDates(dates({ broadcastDate: '2026-09-22' }), TODAY), {
    broadcastDate: '방송일은 오늘 이후 날짜로 입력할 수 없습니다.',
    filmedDate: undefined,
  });
});

test('촬영일보다 빠른 방송일을 방송일 오류로 안내한다', () => {
  assert.deepEqual(
    validateRegistrationDates(
      dates({ broadcastDate: '2026-09-06', filmedDate: '2026-09-07' }),
      TODAY,
    ),
    { broadcastDate: '방송일은 촬영일보다 빠를 수 없습니다.', filmedDate: undefined },
  );
});

test('오늘 촬영해 오늘 방송한 영상은 통과시킨다', () => {
  assert.deepEqual(
    validateRegistrationDates(dates({ broadcastDate: TODAY, filmedDate: TODAY }), TODAY),
    { broadcastDate: undefined, filmedDate: undefined },
  );
});

test('날짜를 모두 비우면 검사하지 않는다', () => {
  assert.deepEqual(validateRegistrationDates(dates(), TODAY), {
    broadcastDate: undefined,
    filmedDate: undefined,
  });
});

// 한쪽 날짜를 고치면 반대쪽에 붙어 있던 안내는 더는 사실이 아니다. 두 키를 항상 내보내야
// 호출부의 이전 오류를 덮어쓴다.
test('통과한 날짜의 오류 키도 비워서 내보낸다', () => {
  assert.deepEqual(Object.keys(validateRegistrationDates(dates(), TODAY)), [
    'broadcastDate',
    'filmedDate',
  ]);
});

test('자료 영상에서는 남아 있는 방송일 값을 검사하지 않는다', () => {
  assert.deepEqual(
    validateRegistrationDates(
      dates({ sourceType: 'archive', broadcastDate: '2026-09-22', filmedDate: '2026-09-07' }),
      TODAY,
    ),
    { broadcastDate: undefined, filmedDate: undefined },
  );
});

test('마운트 전에는 날짜 입력 범위를 내보내지 않는다', () => {
  assert.deepEqual(registrationDateBounds(dates({ filmedDate: '2026-09-07' }), ''), {});
});

test('날짜 선택 범위의 위 끝은 오늘이다', () => {
  assert.deepEqual(registrationDateBounds(dates(), TODAY), {
    broadcastMax: TODAY,
    broadcastMin: undefined,
    filmedMax: TODAY,
  });
});

test('촬영일을 고르면 그날부터만 방송일로 고를 수 있다', () => {
  assert.deepEqual(registrationDateBounds(dates({ filmedDate: '2026-09-07' }), TODAY), {
    broadcastMax: TODAY,
    broadcastMin: '2026-09-07',
    filmedMax: TODAY,
  });
});

test('방송일을 먼저 고르면 그날까지만 촬영일로 고를 수 있다', () => {
  assert.deepEqual(registrationDateBounds(dates({ broadcastDate: '2026-09-07' }), TODAY), {
    broadcastMax: TODAY,
    broadcastMin: undefined,
    filmedMax: '2026-09-07',
  });
});

// 촬영일을 미래로 타이핑하면 min > max 가 되어 방송일 피커에 고를 수 있는 날이 사라진다.
test('미래 촬영일은 방송일의 아래 끝으로 쓰지 않는다', () => {
  assert.deepEqual(registrationDateBounds(dates({ filmedDate: '2026-09-22' }), TODAY), {
    broadcastMax: TODAY,
    broadcastMin: undefined,
    filmedMax: TODAY,
  });
});

test('자료 영상에서는 방송일 범위를 내보내지 않고 촬영일만 오늘로 막는다', () => {
  assert.deepEqual(
    registrationDateBounds(
      dates({ sourceType: 'archive', broadcastDate: '2026-09-07', filmedDate: '2026-09-08' }),
      TODAY,
    ),
    { broadcastMax: undefined, broadcastMin: undefined, filmedMax: TODAY },
  );
});

// 서버가 Asia/Seoul 로 판정하므로 화면도 같은 날짜를 써야 한다. 기기 시간대가 KST 보다 앞서면
// 브라우저 로컬 오늘은 서버가 거부할 날짜다.
test('오늘은 기기 시간대와 무관하게 Asia/Seoul 날짜다', () => {
  assert.equal(
    registrationToday(),
    new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date()),
  );
  assert.match(registrationToday(), /^\d{4}-\d{2}-\d{2}$/);
});

test('오늘 기준값을 생략하면 Asia/Seoul 날짜로 검사한다', () => {
  const [year, month, day] = registrationToday().split('-').map(Number);
  const tomorrow = new Date(Date.UTC(year, month - 1, day + 1));
  const filmedDate = tomorrow.toISOString().slice(0, 10);

  assert.deepEqual(validateRegistrationDates(dates({ filmedDate })), {
    broadcastDate: undefined,
    filmedDate: '촬영일은 오늘 이후 날짜로 입력할 수 없습니다.',
  });
});
