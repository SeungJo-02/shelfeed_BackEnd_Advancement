/**
 * 테스트 유저 50명 생성 및 JWT 토큰 수집
 *
 * 실행:
 *   k6 run k6/setup-tokens.js
 *   → k6/tokens.local.json 자동 생성
 *
 * 옵션:
 *   BASE_URL=http://... k6 run k6/setup-tokens.js   (기본: localhost:8080)
 *   USER_COUNT=50       k6 run k6/setup-tokens.js   (기본: 50)
 *   BATCH_SIZE=20       k6 run k6/setup-tokens.js   (기본: 20, 병렬 처리 단위)
 *
 * 참고: k6 v2.0.0에서 setup()과 handleSummary()는 별도 JS 런타임으로 격리된다.
 *       모듈 변수 공유가 불가능하므로 setup()에서 회원가입, handleSummary()에서 로그인한다.
 */

import http from 'k6/http';

const BASE_URL   = __ENV.BASE_URL  || 'http://localhost:8080';
const USER_COUNT = Math.max(1, parseInt(__ENV.USER_COUNT || '50') || 50);
const BATCH_SIZE = Math.max(1, parseInt(__ENV.BATCH_SIZE || '20') || 20);
const PASSWORD   = 'Perf@test1!';
const HEADERS    = { 'Content-Type': 'application/json' };

export const options = {
  vus: 1,
  iterations: 1,
  setupTimeout: '300s',
};

// ── Step 1: 회원가입 (setup) ─────────────────────────────────────
export function setup() {
  console.log(`[setup-tokens] 회원가입 시작 (${USER_COUNT}명, 배치 ${BATCH_SIZE}명씩)...`);

  for (let i = 0; i < USER_COUNT; i += BATCH_SIZE) {
    const end = Math.min(i + BATCH_SIZE, USER_COUNT);

    const batch = [];
    for (let j = i; j < end; j++) {
      batch.push([
        'POST',
        `${BASE_URL}/api/v1/auth/signup`,
        JSON.stringify({ email: `perftest${j}@shelfeed.test`, password: PASSWORD, nickname: `perfuser${j}` }),
        { headers: HEADERS },
      ]);
    }

    const responses = http.batch(batch);
    let ok = 0;
    for (const r of responses) {
      if (r.status === 200 || r.status === 201 || r.status === 409) ok++;
    }
    console.log(`[setup-tokens] 회원가입 ${end}/${USER_COUNT} (이번 배치 성공: ${ok}/${responses.length})`);
  }

  console.log('[setup-tokens] 회원가입 완료 — handleSummary에서 로그인 후 토큰 저장');
}

export default function () {}

// ── Step 2: 로그인 + 토큰 파일 저장 (handleSummary) ──────────────
export function handleSummary() {
  console.log(`[setup-tokens] 로그인 시작 (${USER_COUNT}명)...`);

  const tokens = [];

  for (let i = 0; i < USER_COUNT; i += BATCH_SIZE) {
    const end = Math.min(i + BATCH_SIZE, USER_COUNT);

    const batch = [];
    for (let j = i; j < end; j++) {
      batch.push([
        'POST',
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: `perftest${j}@shelfeed.test`, password: PASSWORD }),
        { headers: HEADERS },
      ]);
    }

    const responses = http.batch(batch);
    for (const r of responses) {
      if (r.status !== 200) continue;
      try {
        const token = JSON.parse(r.body)?.data?.accessToken;
        if (token) tokens.push(token);
      } catch (_) {}
    }

    console.log(`[setup-tokens] 로그인 ${end}/${USER_COUNT} (토큰: ${tokens.length}개)`);
  }

  if (tokens.length === 0) {
    return { stdout: '❌ 토큰 수집 실패 — 서버가 실행 중인지 확인하세요\n' };
  }

  return {
    './k6/tokens.local.json': JSON.stringify(tokens, null, 2),
    stdout: `✅ ${tokens.length}개 토큰 → k6/tokens.local.json 저장 완료\n`,
  };
}
