// Actual HTTP MP4 → decoded browser frame baseline, not search/card E2E latency.
// Supply a temporary Playwright storage state; this script never saves credentials.
import { chromium } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

const mediaUrl = process.env.NPICK_MEDIA_URL;
const storageState = process.env.NPICK_STORAGE_STATE;
const origin = process.env.NPICK_MEASURE_ORIGIN ?? new URL(mediaUrl ?? 'http://127.0.0.1').origin;
const output = process.env.NPICK_MEASURE_OUTPUT;
if (!mediaUrl || !storageState || !output)
  throw new Error('Set NPICK_MEDIA_URL, NPICK_STORAGE_STATE, NPICK_MEASURE_OUTPUT.');
const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
const report = {
  scope: 'actual MP4 endpoint to decoded frame; excludes search and product card UI',
  mediaUrl,
  browserOrigin: origin,
  browser: browser.version(),
  samples: { firstLoad: [], warm: [] },
  failures: [],
};
let context;
try {
  async function open() {
    const context = await browser.newContext({ storageState });
    const page = await context.newPage();
    // A real, script-free loopback document avoids app hydration and preserves
    // the browser network address-space classification. Media is never intercepted.
    await page.goto(origin + '/actuator/health');
    await page.evaluate(() => {
      document.body.innerHTML = '<video muted playsinline></video>';
    });
    return { context, page };
  }
  async function sample(page) {
    return page.evaluate(
      (url) =>
        new Promise((resolve, reject) => {
          const old = document.querySelector('video');
          old.pause();
          old.removeAttribute('src');
          old.load();
          old.remove();
          const video = document.createElement('video');
          video.muted = true;
          video.playsInline = true;
          video.crossOrigin = 'use-credentials';
          document.body.append(video);
          const timer = setTimeout(() => reject(new Error('first frame timeout')), 15000);
          video.onerror = () => {
            clearTimeout(timer);
            reject(new Error(`media error ${video.error?.code}`));
          };
          const start = performance.now();
          video.requestVideoFrameCallback((now, frame) => {
            clearTimeout(timer);
            video.pause();
            resolve({
              ms: now - start,
              mediaTime: frame.mediaTime,
              width: video.videoWidth,
              height: video.videoHeight,
            });
          });
          video.src = url;
          video.play().catch((error) => {
            clearTimeout(timer);
            reject(error);
          });
        }),
      mediaUrl,
    );
  }
  // Preflight actual endpoint, using the same session, before producing any timings.
  let opened = await open();
  context = opened.context;
  const response = await context.request.get(mediaUrl, { headers: { Range: 'bytes=0-1023' } });
  report.http = {
    status: response.status(),
    contentRange: response.headers()['content-range'],
    acceptRanges: response.headers()['accept-ranges'],
    cacheControl: response.headers()['cache-control'],
  };
  if (response.status() !== 206)
    throw new Error(`actual media Range returned HTTP ${response.status()}`);
  await context.close();
  context = undefined;
  for (let i = 0; i < 30; i++) {
    opened = await open();
    context = opened.context;
    report.samples.firstLoad.push(await sample(opened.page));
    await context.close();
    context = undefined;
  }
  opened = await open();
  context = opened.context;
  await sample(opened.page); // Establish one warm browser session.
  for (let i = 0; i < 30; i++) report.samples.warm.push(await sample(opened.page));
  const p95 = (samples) =>
    samples.map((s) => s.ms).sort((a, b) => a - b)[Math.ceil(samples.length * 0.95) - 1];
  report.p95 = { firstLoadMs: p95(report.samples.firstLoad), warmMs: p95(report.samples.warm) };
} catch (error) {
  report.failures.push(error.message);
  process.exitCode = 1;
} finally {
  await context?.close();
  await browser.close();
  await writeFile(output, JSON.stringify(report, null, 2));
  console.log(
    JSON.stringify({
      http: report.http,
      counts: { firstLoad: report.samples.firstLoad.length, warm: report.samples.warm.length },
      p95: report.p95,
      failures: report.failures,
    }),
  );
}
