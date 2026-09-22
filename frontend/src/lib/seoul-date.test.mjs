import assert from 'node:assert/strict';
import test from 'node:test';
import { seoulToday } from './seoul-date.ts';

test('오늘은 기기 시간대와 무관하게 한국 자정에 바뀐다', () => {
  for (const timezone of ['UTC', 'America/Los_Angeles', 'Pacific/Kiritimati']) {
    const previous = process.env.TZ;
    try {
      process.env.TZ = timezone;
      assert.equal(seoulToday(new Date('2026-09-22T14:59:59Z')), '2026-09-22');
      assert.equal(seoulToday(new Date('2026-09-22T15:00:00Z')), '2026-09-23');
      assert.equal(seoulToday(new Date('2024-02-28T15:00:00Z')), '2024-02-29');
      assert.equal(seoulToday(new Date('2026-12-31T15:00:00Z')), '2027-01-01');
    } finally {
      if (previous === undefined) delete process.env.TZ;
      else process.env.TZ = previous;
    }
  }
});
