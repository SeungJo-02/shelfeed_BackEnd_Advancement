/**
 * 팔로우 동시성 테스트
 * 검증 대상: MemberRepository의 UPDATE SET followerCount = followerCount + 1 원자적 업데이트
 *
 * 시나리오: 50명의 다른 유저가 동시에 같은 유저를 팔로우
 * 기대값: 테스트 후 대상 유저의 follower_count = 50
 * 확인 쿼리: SELECT follower_count FROM members WHERE member_user_id = TARGET_USER_ID;
 */
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getToken, authHeader } from './config.js';

// ── 테스트 대상 설정 ──────────────────────────────────────────
const TARGET_USER_ID = 1; // 팔로우 받을 유저 ID (TOKENS 배열의 유저들과 달라야 함)
const VU_COUNT = 50;
// ─────────────────────────────────────────────────────────────

export const options = {
  scenarios: {
    concurrent_follows: {
      executor: 'shared-iterations',
      vus: VU_COUNT,
      iterations: VU_COUNT,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const token = getToken(__VU - 1);
  const url = `${BASE_URL}/api/v1/users/${TARGET_USER_ID}/follow`;

  const res = http.post(url, null, { headers: authHeader(token) });

  check(res, {
    '팔로우 성공 (201)': (r) => r.status === 201,
  });
}

export function handleSummary(data) {
  const passed = data.metrics.checks?.values?.passes ?? 0;
  console.log('\n======= 동시성 검증 =======');
  console.log(`요청 성공: ${passed} / ${VU_COUNT}`);
  console.log(`\nDB 확인 쿼리:`);
  console.log(`  SELECT follower_count FROM members WHERE member_user_id = ${TARGET_USER_ID};`);
  console.log(`  기대값: ${VU_COUNT}`);
  console.log('============================\n');
  return {};
}
