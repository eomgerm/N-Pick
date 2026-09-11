import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createServer, type ServerResponse } from 'node:http';

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
  const server = createServer((request, response) => {
    const url = new URL(request.url ?? '/', `http://${host}:${mockApiPort}`);

    if (request.method === 'GET' && url.pathname === '/api/v1/auth/me') {
      if (!request.headers.cookie?.includes('JSESSIONID=e2e-editor')) {
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
          loginId: 'e2e-editor',
          role: 'EDITOR',
        },
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
