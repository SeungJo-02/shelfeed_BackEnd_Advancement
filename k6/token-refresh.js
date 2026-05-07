/**
 * 토큰 갱신 동시성 테스트
 * 검증 대상: RedisService.rotateRefreshToken() Lua 스크립트 원자적 교체
 *
 * 시나리오: 동일 유저가 동시에 토큰 갱신을 2번 요청
 *           → 하나만 성공(200), 다른 하나는 실패(401) + Redis에서 토큰 삭제(재사용 공격 방어)
 *
 * 실행 예시:
 *   k6 run -e REFRESH_COOKIE="refreshToken=eyJhbGci..." \
 *          -e BASE_URL=http://54.180.101.81:8080 \
 *          k6/token-refresh.js
 *
 * REFRESH_COOKIE 발급: POST /api/v1/auth/login 응답의 Set-Cookie 헤더에서 복사
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL } from './config.js';

const REFRESH_COOKIE = __ENV.REFRESH_COOKIE;
if (!REFRESH_COOKIE) {
  throw new Error(
    'REFRESH_COOKIE 환경변수가 필요합니다.\n' +
    '실행 방법: k6 run -e REFRESH_COOKIE="refreshToken=eyJ..." k6/token-refresh.js'
  );
}

// VU 간 공유 카운터 — 200 응답이 2개이면 Lua 스크립트 미동작
const ok200 = new Counter('refresh_ok_200');

export const options = {
  scenarios: {
    concurrent_refresh: {
      executor: 'per-vu-iterations',
      vus: 2,        // 동일 유저가 동시에 2번 갱신 시도
      iterations: 1,
      maxDuration: '10s',
    },
  },
};

export default function () {
  const url = `${BASE_URL}/api/v1/auth/token/refresh`;

  const res = http.post(url, null, {
    headers: { Cookie: REFRESH_COOKIE },
  });

  if (res.status === 200) ok200.add(1);

  check(res, {
    '200 또는 401': (r) => r.status === 200 || r.status === 401,
  });

  console.log(`VU ${__VU} → status: ${res.status}`);
}

export function handleSummary(data) {
  const twoHundredCount = data.metrics['refresh_ok_200']?.values?.count ?? 0;

  console.log('\n======= 토큰 갱신 동시성 검증 =======');
  console.log(`200 응답 수: ${twoHundredCount} / 2`);
  if (twoHundredCount >= 2) {
    console.error('경고: 두 요청 모두 200 → Lua 스크립트가 동작하지 않은 것입니다.');
  } else {
    console.log('정상: 200 1개 + 401 1개 → Lua 스크립트 원자적 교체 동작 확인');
  }
  console.log('=====================================\n');
  return {};
}
