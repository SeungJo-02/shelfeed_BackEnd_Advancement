/**
 * 테스트 유저 일괄 생성 + 토큰 수집 스크립트
 * 실행: k6 run k6/setup-users.js
 * 결과로 출력된 토큰 배열을 config.js의 TOKENS에 복사하세요.
 */
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';

const USER_COUNT = 50;
const PASSWORD = 'Test1234!@';

export const options = {
  scenarios: {
    setup: {
      executor: 'shared-iterations',
      vus: 1,          // 순차 생성 (중복 방지)
      iterations: USER_COUNT,
      maxDuration: '60s',
    },
  },
};

const tokens = [];

export default function () {
  const index = __ITER + 1;
  const email = `testuser${index}@shelfeed.test`;
  const nickname = `테스터${index}`;

  // 1. 회원가입
  const signupRes = http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password: PASSWORD, nickname }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  // 이미 가입된 경우 → 로그인만
  // 2. 로그인
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(loginRes, { '로그인 성공': (r) => r.status === 200 });

  try {
    const body = JSON.parse(loginRes.body);
    const token = body?.data?.accessToken;
    if (token) tokens.push(token);
  } catch (_) {}
}

export function handleSummary() {
  console.log('\n======= config.js의 TOKENS에 아래 배열을 붙여넣으세요 =======');
  console.log('export const TOKENS = [');
  tokens.forEach((t) => console.log(`  '${t}',`));
  console.log('];');
  console.log('=============================================================\n');
  return {};
}
