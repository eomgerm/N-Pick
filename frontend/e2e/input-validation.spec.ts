import { expect, test, type Locator } from '@playwright/test';
import { searchFixture } from './search-fixture';

async function paste(input: Locator, text: string) {
  return input.evaluate((element, value) => {
    const clipboardData = new DataTransfer();
    clipboardData.setData('text/plain', value);
    return element.dispatchEvent(
      new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData }),
    );
  }, text);
}

test.beforeEach(async ({ context }) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-editor', url: 'http://127.0.0.1:3116' },
  ]);
});

test('검색은 양쪽 화면과 직접 URL에서 길이를 검사하고 조합 중 Enter로 제출하지 않는다', async ({
  page,
}) => {
  let searches = 0;
  await page.route('**/api/v1/search', (route) => {
    searches++;
    return route.fulfill({
      json: { isSuccess: true, code: 'COMM_200', message: '성공', data: searchFixture },
    });
  });
  await page.goto('/search');
  const input = page.getByRole('searchbox', { name: '뉴스 장면 검색어' });
  await input.fill('비');
  await expect(page.getByRole('button', { name: '장면 찾기' })).toBeDisabled();
  await expect(page.locator('#scene-search-hint')).toHaveCount(0);
  await input.fill('화재');
  await input.dispatchEvent('compositionstart');
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  await expect(page.getByRole('button', { name: '장면 찾기' })).toBeDisabled();
  expect(searches).toBe(0);
  await input.dispatchEvent('compositionend');
  await input.fill('가'.repeat(500));
  await input.evaluate((element: HTMLInputElement) => element.setSelectionRange(500, 500));
  expect(await paste(input, '나')).toBe(false);
  await expect(input).toHaveValue('가'.repeat(500));
  await expect(page.locator('#scene-search-hint')).toHaveCount(0);
  await page.getByRole('button', { name: '장면 찾기' }).click();
  await expect.poll(() => searches).toBe(1);
  const resultsInput = page.getByRole('textbox', { name: '뉴스 장면 검색어' });
  await expect(resultsInput).toHaveAttribute('maxlength', '500');
  await resultsInput.fill('비');
  await expect(page.getByRole('button', { name: '검색', exact: true })).toBeDisabled();
  await expect(page.locator('#scene-search-hint')).toHaveCount(0);
  for (const query of ['비', '가'.repeat(501)]) {
    await page.goto(`/search/results?q=${encodeURIComponent(query)}`);
    await expect(page.getByRole('heading', { name: '검색어를 확인해 주세요' })).toBeVisible();
    await expect(page.getByRole('button', { name: '같은 조건으로 다시 시도' })).toHaveCount(0);
    expect(searches).toBe(1);
  }
});

test('빈 검색창과 정상 입력에서는 하단 안내 문구를 띄우지 않는다', async ({ page }) => {
  await page.goto('/search');
  const hint = page.locator('#scene-search-hint');
  const input = page.getByRole('searchbox', { name: '뉴스 장면 검색어' });
  // 입력 전에는 내부 제약을 노출하지 않는다(S15P21A501-284). 요소는 남아야 aria-live 가 동작한다.
  await expect(hint).toHaveText('');
  await expect(hint).toHaveCount(1);
  await expect(input).not.toHaveAttribute('aria-describedby', /./);
  await input.fill('화재');
  await expect(hint).toHaveText('');
  await input.fill('비');
  await expect(hint).toContainText('2자 이상');
  await expect(input).toHaveAttribute('aria-describedby', 'scene-search-hint');
  await input.fill('');
  await expect(hint).toHaveText('');
});

test('문의는 긴 붙여넣기를 거부하고 2000자 원문을 온전히 전송한다', async ({ page }) => {
  let comment: string | undefined;
  await page.route('**/api/v1/search/results/101/inquiries', async (route) => {
    comment = route.request().postDataJSON().comment;
    await route.fulfill({
      json: {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: { feedbackId: '987', status: 'OPEN' },
      },
    });
  });
  await page.goto('/search/results?q=화재');
  await page
    .getByRole('button', { name: /실제 응답 장면/ })
    .first()
    .click();
  await page.getByRole('button', { name: '이상해요', exact: true }).click();
  const input = page.locator('#inquiry-comment');
  await expect(input).toHaveAttribute('maxlength', '2000');
  expect(await paste(input, '가'.repeat(2001))).toBe(false);
  await expect(input).toHaveValue('');
  await expect(page.locator('#inquiry-comment-error')).toContainText('2,000자');
  await input.fill('가'.repeat(2000));
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: '문의 접수', exact: true }).click();
  await expect.poll(() => comment?.length).toBe(2000);
});

test('파일 선택과 DnD에서 자막 상한을 막고 제목 제한·자막 서버 오류를 안내한다', async ({
  page,
  context,
}) => {
  await context.addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
  ]);
  let uploads = 0;
  let submittedTitle: string | undefined;
  await page.route('**/api/v1/clips', (route) => {
    uploads++;
    submittedTitle = route
      .request()
      .postDataBuffer()
      ?.toString('utf8')
      .match(/name="title"\r\n\r\n([^\r\n]*)\r\n/)?.[1];
    return route.fulfill({
      status: 400,
      json: {
        isSuccess: false,
        code: 'CLIP_400_012',
        message: 'segments[0].e: 자막 입력 오류',
        requestId: 'private-upload-id',
      },
    });
  });
  await page.goto('/review?view=upload');
  await page.locator('#video-file').setInputFiles('e2e/preview-fixture.mp4');
  const title = page.locator('#registration-title');
  await expect(title).toHaveAttribute('maxlength', '50');
  await title.fill('기존 제목');
  expect(await paste(title, '가'.repeat(51))).toBe(false);
  await expect(title).toHaveValue('기존 제목');
  await expect(page.locator('#title-error')).toContainText('50자');
  await title.fill('가'.repeat(50));
  await expect(page.locator('#title-hint')).toContainText('50/50자');
  await page.locator('#rights-confirmed').check();
  await page.locator('#external-processing-confirmed').check();
  const subtitle = page.locator('#subtitle-file');
  await subtitle.setInputFiles({
    name: 'large.srt',
    mimeType: 'text/plain',
    buffer: Buffer.alloc(10 * 1024 * 1024 + 1),
  });
  await expect(page.locator('#subtitle-error')).toContainText('10 MiB');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  expect(uploads).toBe(0);
  await subtitle.setInputFiles({
    name: 'valid.srt',
    mimeType: 'text/plain',
    buffer: Buffer.from('1\n00:00:00,000 --> 00:00:01,000\n대사'),
  });
  const dataTransfer = await page.evaluateHandle(() => {
    const data = new DataTransfer();
    data.items.add(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.vtt'));
    return data;
  });
  await page.locator('label[data-kind="subtitle"]').dispatchEvent('drop', { dataTransfer });
  await dataTransfer.dispose();
  await expect(page.locator('#subtitle-error')).toContainText('10 MiB');
  await page.getByRole('button', { name: '등록', exact: true }).click();
  expect(uploads).toBe(0);
  await subtitle.setInputFiles({
    name: 'valid.srt',
    mimeType: 'text/plain',
    buffer: Buffer.from('1\n00:00:00,000 --> 00:00:01,000\n대사'),
  });
  await page.getByRole('button', { name: '등록', exact: true }).click();
  await expect.poll(() => uploads).toBe(1);
  expect(submittedTitle).toBe('가'.repeat(50));
  await expect(page.locator('#subtitle-error')).toHaveText(
    '자막 파일의 형식, 인코딩과 시간 정보를 확인해 주세요.',
  );
  await expect(title).toHaveValue('가'.repeat(50));
  await expect(page.getByRole('main')).not.toContainText(
    /CLIP_400_012|private-upload-id|segments\[0\]/,
  );
});

test('화면은 진단 문자열을 숨기고 기존 API 로그는 코드·요청 ID를 보존한다', async ({ page }) => {
  const logs: { code?: string; requestId?: string }[] = [];
  await page.route('**/client-logs', async (route) => {
    logs.push(route.request().postDataJSON());
    await route.fulfill({ status: 204 });
  });
  await page.route('**/api/v1/search', (route) =>
    route.fulfill({
      status: 400,
      json: {
        isSuccess: false,
        code: 'COMM_400_001',
        message: '검색 실패: validation_error',
        requestId: 'diagnostic-only-123',
      },
    }),
  );
  await page.goto('/search/results?q=화재');
  await expect(
    page.getByRole('alert').filter({ hasText: '요청을 처리하지 못했습니다.' }),
  ).toBeVisible();
  await expect(page.locator('body')).not.toContainText(
    /validation_error|COMM_400_001|diagnostic-only-123|오류 코드|요청 ID/,
  );
  await expect
    .poll(() =>
      logs.some(
        (entry) => entry.code === 'COMM_400_001' && entry.requestId === 'diagnostic-only-123',
      ),
    )
    .toBe(true);
});
