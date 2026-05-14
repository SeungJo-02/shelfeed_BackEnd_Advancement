/**
 * 테스트 유저 일괄 생성 + 토큰 수집 스크립트
 *
 * 실행:
 *   k6 run k6/setup-users.js 2>&1 | tee /tmp/k6-setup.log
 *
 * 토큰 추출 (실행 후):
 *   grep '\[TOKEN\]' /tmp/k6-setup.log \
 *     | sed 's/.*\[TOKEN\] //' \
 *     | jq -R -s '[split("\n")[] | select(length > 0)]' \
 *     > k6/tokens.local.json
 *
 * jq 없는 경우:
 *   grep '\[TOKEN\]' /tmp/k6-setup.log | sed 's/.*\[TOKEN\] //'
 *   → 출력된 값을 ["토큰1","토큰2",...] 형식으로 k6/tokens.local.json 에 저장
 *
 * Bug fixes (기존 버전 대비):
 *   1. k6 context isolation: module-level tokens[] 는 handleSummary() 에서
 *      항상 비어있음 → 실행 중 console.log('[TOKEN] ...') 로 즉시 기록
 *   2. maxDuration '60s' → '3m': 50명 signup+login 에 충분한 시간 확보
 */

import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './config.js';

const USER_COUNT = 50;
const PASSWORD = 'Test1234!@';

export const options = {
  scenarios: {
    create_users: {
      executor: 'shared-iterations',
      vus: 1,           // 순차 생성 (중복 방지)
      iterations: USER_COUNT,
      maxDuration: '3m',  // FIX: 60s → 3m
    },
  },
};

export default function () {
  const index = __ITER + 1;
  const email = `testuser${index}@shelfeed.test`;
  const nickname = `테스터${index}`;

  // 1. 회원가입 (이미 가입됐으면 409 → 무시하고 로그인만)
  http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password: PASSWORD, nickname }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  // 2. 로그인 — signup 반환 토큰보다 로그인 토큰이 안정적
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(loginRes, { '로그인 성공': (r) => r.status === 200 });

  try {
    const token = JSON.parse(loginRes.body)?.data?.accessToken;
    if (token) {
      // FIX: 즉시 로그로 기록 → handleSummary() context isolation 우회
      // grep '[TOKEN]' 로 추출 가능
      console.log(`[TOKEN] ${token}`);
      console.log(`[OK] user ${index}/${USER_COUNT} (${email})`);
    } else {
      console.error(`[FAIL] user ${index}/${USER_COUNT} (${email}) — status=${loginRes.status}`);
    }
  } catch (_) {
    console.error(`[FAIL] user ${index}/${USER_COUNT} parse error`);
  }
}

export function handleSummary(data) {
  const total   = data.metrics['iterations']?.values?.count ?? 0;
  const failed  = data.metrics['checks']?.values?.fails ?? 0;

  console.log(`
=================================================================
 setup-users.js 완료
 총 반복: ${total} / ${USER_COUNT}  |  실패: ${failed}

 토큰 추출 명령어 (위 출력이 /tmp/k6-setup.log 에 저장된 경우):

   grep '\\[TOKEN\\]' /tmp/k6-setup.log \\
     | sed 's/.*\\[TOKEN\\] //' \\
     | jq -R -s '[split("\\n")[] | select(length > 0)]' \\
     > k6/tokens.local.json

 저장 확인:
   cat k6/tokens.local.json | jq 'length'
=================================================================
`);
  return {};
}
