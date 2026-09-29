import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createServer, type ServerResponse } from 'node:http';
import { readFileSync } from 'node:fs';
import { searchFixture } from './search-fixture';

const host = '127.0.0.1';
const frontendPort = 3116;
const mockApiPort = 18116;
const frontendUrl = `http://${host}:${frontendPort}`;

function sendJson(response: ServerResponse, status: number, body: unknown) {
  response.writeHead(status, {
    'access-control-allow-credentials': 'true',
    'access-control-allow-origin': 'http://127.0.0.1:3116',
    'content-type': 'application/json; charset=utf-8',
  });
  response.end(JSON.stringify(body));
}

export default async function globalSetup() {
  // Synthetic, silent 8-second MP4 recorded from a canvas (no external media).
  const previewMedia = readFileSync('e2e/preview-fixture.mp4');
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', `http://${host}:${mockApiPort}`);
    if (request.method === 'OPTIONS') {
      response.writeHead(204, {
        'access-control-allow-origin': frontendUrl,
        'access-control-allow-credentials': 'true',
        'access-control-allow-methods': 'GET, POST, OPTIONS',
        'access-control-allow-headers': 'Content-Type, X-XSRF-TOKEN, Idempotency-Key',
      });
      response.end();
      return;
    }
    if (url.pathname === '/api/v1/auth/csrf') {
      response.setHeader('set-cookie', 'XSRF-TOKEN=test-csrf; Path=/');
      sendJson(response, 200, { isSuccess: true, code: 'COMM_200', message: '성공' });
      return;
    }
    if (request.method === 'POST' && url.pathname === '/api/v1/search') {
      if (
        request.headers['x-xsrf-token'] !== 'test-csrf' ||
        !request.headers.cookie?.includes('JSESSIONID=e2e-')
      ) {
        sendJson(response, 403, {
          isSuccess: false,
          code: 'COMM_403',
          message: '인증을 확인해 주세요.',
        });
      } else {
        sendJson(response, 200, {
          isSuccess: true,
          code: 'COMM_200',
          message: '성공',
          data: searchFixture,
        });
      }
      return;
    }

    if (url.pathname === '/api/v1/media/21') {
      if (!request.headers.cookie?.includes('JSESSIONID=e2e-')) {
        sendJson(response, 401, {
          isSuccess: false,
          code: 'COMM_401',
          message: '인증이 필요합니다.',
        });
        return;
      }
      const range = request.headers.range?.match(/^bytes=(\d+)-(\d*)$/);
      const start = range ? Number(range[1]) : 0;
      const end = range?.[2]
        ? Math.min(Number(range[2]), previewMedia.length - 1)
        : previewMedia.length - 1;
      if (start > end || start >= previewMedia.length) {
        response.writeHead(416, { 'content-range': `bytes */${previewMedia.length}` });
        response.end();
        return;
      }
      response.writeHead(range ? 206 : 200, {
        'access-control-allow-origin': frontendUrl,
        'access-control-allow-credentials': 'true',
        'content-type': 'video/mp4',
        'accept-ranges': 'bytes',
        'cache-control': 'private, no-store',
        'content-length': end - start + 1,
        ...(range ? { 'content-range': `bytes ${start}-${end}/${previewMedia.length}` } : {}),
      });
      response.end(previewMedia.subarray(start, end + 1));
      return;
    }

    if (request.method === 'GET' && url.pathname === '/api/v1/auth/me') {
      const isReviewer = request.headers.cookie?.includes('JSESSIONID=e2e-reviewer');
      if (!isReviewer && !request.headers.cookie?.includes('JSESSIONID=e2e-editor')) {
        sendJson(response, 401, {
          isSuccess: false,
          code: 'COMM_401',
          message: '인증이 필요합니다.',
        });
        return;
      }

      sendJson(response, 200, {
        isSuccess: true,
        code: 'COMM_200',
        message: '요청에 성공했습니다.',
        data: {
          memberId: '1',
          loginId: isReviewer ? 'e2e-reviewer' : 'e2e-editor',
          role: isReviewer ? 'REVIEWER' : 'EDITOR',
        },
      });
      return;
    }

    // 대기 교정 후보가 없는 기본 응답 (S15P21A501-317). 교정 화면은 이 조회가 성공해야 조작을 연다 —
    // 후보를 다루는 spec 은 page.route 로 덮어쓴다.
    if (
      request.method === 'GET' &&
      /^\/api\/v1\/review\/inquiries\/\d+\/correction-candidates$/.test(url.pathname)
    ) {
      sendJson(response, 200, {
        isSuccess: true,
        code: 'COMM_200',
        message: '성공',
        data: { tags: [], parsePatches: [], sceneExcludes: [] },
      });
      return;
    }

    // 실제 네트워크로 받는 영상 등록 (S15P21A501-325). page.route 로 가로채면 브라우저가 업로드
    // 진행률을 내지 않으므로, 대기 화면의 전송→서버 확인 전환은 이 경로로 검증한다. 본문을 다 받은 뒤
    // 서버 검사 시간을 흉내 내 잠시 기다렸다 답한다.
    if (request.method === 'POST' && url.pathname === '/api/v1/clips') {
      request.resume();
      request.on('end', () => {
        setTimeout(
          () =>
            sendJson(response, 200, {
              isSuccess: true,
              code: 'COMM_200',
              message: '성공',
              data: { clip_id: '21', pipeline_run_id: '32', status: 'queued' },
            }),
          2_500,
        );
      });
      return;
    }

    sendJson(response, 404, {
      isSuccess: false,
      code: 'COMM_404',
      message: '테스트 mock에 정의되지 않은 요청입니다.',
    });
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(mockApiPort, host, resolve);
  });

  const build = spawnSync(process.execPath, ['node_modules/next/dist/bin/next', 'build'], {
    cwd: process.cwd(),
    env: process.env,
    stdio: 'inherit',
  });
  if (build.error || build.status !== 0) {
    server.closeAllConnections();
    server.close();
    throw build.error ?? new Error(`E2E build failed with exit code ${build.status ?? 'unknown'}.`);
  }

  const appServer = spawn(
    process.execPath,
    [
      'node_modules/next/dist/bin/next',
      'start',
      '--hostname',
      host,
      '--port',
      String(frontendPort),
    ],
    {
      cwd: process.cwd(),
      detached: process.platform !== 'win32',
      env: process.env,
      stdio: 'inherit',
    },
  );

  try {
    await waitForAppServer(appServer);
  } catch (error) {
    stopAppServer(appServer);
    server.closeAllConnections();
    server.close();
    throw error;
  }

  return () => {
    server.closeAllConnections();
    server.close();
    stopAppServer(appServer);
  };
}

async function waitForAppServer(appServer: ChildProcess) {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (appServer.exitCode !== null) {
      throw new Error(`E2E app server exited with code ${appServer.exitCode}.`);
    }
    try {
      const response = await fetch(`${frontendUrl}/login`);
      if (response.status < 500) return;
    } catch {
      // The server has not started accepting connections yet.
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error('Timed out waiting for the E2E app server.');
}

function stopAppServer(appServer: ChildProcess) {
  if (!appServer.pid || appServer.exitCode !== null) return;
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(appServer.pid), '/T', '/F'], { stdio: 'ignore' });
    return;
  }
  try {
    process.kill(-appServer.pid, 'SIGTERM');
  } catch {
    appServer.kill('SIGTERM');
  }
}
