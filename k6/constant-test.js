/**
 * [Step 2] Constant 부하 테스트 — AS-IS 기준값 측정 / TO-BE 비교
 *
 * 목적: ramping-test.js 에서 찾은 breaking point 아래의 VU 를 고정하고
 *       3분간 안정적인 부하를 걸어 AS-IS 수치를 기록한다.
 *       ES 도입 후 동일 조건으로 재실행 → before/after 비교.
 *
 * 환경변수:
 *   CONSTANT_VUS  고정 VU 수 (기본: 700 — book_auth 500 / user 100 / all 100)
 *   DURATION      테스트 지속 시간 (기본: 3m)
 *   BASE_URL      서버 주소 (기본: http://localhost:8080)
 *
 * VU 배분 (CONSTANT_VUS 기준):
 *   book_auth (로그인 도서 검색)  5/7 ≈ 71%
 *   user      (유저 검색)         1/7 ≈ 14%
 *   all       (통합 검색)         1/7 ≈ 14%
 *
 * 사전 준비:
 *   1. docker compose up -d
 *   2. ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
 *   3. "[PerfBookSeeder] 시딩 완료: 1000000건" 로그 확인
 *   4. k6 run k6/setup-tokens.js  (tokens.local.json 생성)
 *
 * 실행 — AS-IS (ES 도입 전):
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          -e CONSTANT_VUS=700 \
 *          --summary-export k6/results/before-es.json \
 *          k6/constant-test.js
 *
 * 실행 — TO-BE (ES 도입 후):
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          -e CONSTANT_VUS=700 \
 *          --summary-export k6/results/after-es.json \
 *          k6/constant-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { KEYWORDS, pickQuery } from './keywords.js';

const BASE_URL     = __ENV.BASE_URL     || 'http://localhost:8080';
const CONSTANT_VUS = parseInt(__ENV.CONSTANT_VUS || '700');
const DURATION     = __ENV.DURATION     || '3m';

// ── 커스텀 메트릭 (Grafana 대시보드 측정 이름과 동일) ──────────────
const searchAuthDuration = new Trend('search_auth_duration', true);
const searchUserDuration = new Trend('search_user_duration', true);
const searchAllDuration  = new Trend('search_all_duration',  true);
const searchErrorRate    = new Rate('search_error_rate');
const bookErrorRate      = new Rate('search_book_error_rate');
const userErrorRate      = new Rate('search_user_error_rate');
const sloViolationRate   = new Rate('search_slo_violation_rate');
const SLO_MS = 1000;

// ── 토큰 로드 ─────────────────────────────────────────────────────
let tokens = [];
try {
  tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.local.json')));
} catch (e) {
  console.warn(`[tokens] 로드 실패: ${e.message} — k6 run k6/setup-tokens.js 를 먼저 실행하세요`);
}

function getToken(vuId) {
  return tokens.length > 0 ? tokens[vuId % tokens.length] : null;
}

// ── 검색 키워드는 keywords.js 에서 import — 200개 풀 + 20% cache-buster ─────

// ── VU 배분 계산 (book_auth 3/5, user 1/5, all 1/5) ────────────
//   예: CONSTANT_VUS=50 → book_auth 30, user 10, all 10
//   floor 사용으로 비율 유지 + 합계가 CONSTANT_VUS 초과하지 않도록 보장
const VU_BOOK_AUTH = Math.floor(CONSTANT_VUS * 3 / 5);
const VU_USER      = Math.floor(CONSTANT_VUS * 1 / 5);
const VU_ALL       = CONSTANT_VUS - VU_BOOK_AUTH - VU_USER;

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    book_auth: {
      executor: 'constant-vus',
      vus: VU_BOOK_AUTH,
      duration: DURATION,
      tags: { scenario: 'book_auth' },
    },
    user: {
      executor: 'constant-vus',
      vus: VU_USER,
      duration: DURATION,
      tags: { scenario: 'user' },
    },
    all: {
      executor: 'constant-vus',
      vus: VU_ALL,
      duration: DURATION,
      tags: { scenario: 'all' },
    },
  },
  // AS-IS 에서 실패 → ES 도입 후 동일 조건 재실행 시 통과 → 개선 증명
  thresholds: {
    http_req_failed:           ['rate<0.01'],
    search_auth_duration:      ['p(95)<500'],  // P95 500ms 미만 (ES 전 실패 예상)
    search_error_rate:         ['rate<0.01'],
    search_book_error_rate:    ['rate<0.01'],   // 도서 에러율 (user보다 높으면 LIKE 풀스캔 증거)
    search_user_error_rate:    ['rate<0.005'],  // user는 더 엄격 (작은 테이블)
    search_slo_violation_rate: ['rate<0.05'],   // SLO(1s) 위반 5% 미만
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/actuator/info`);
  if (res.status !== 200) exec.test.abort(`[ABORT] /actuator/info 응답 실패: ${res.status}`);

  const info = JSON.parse(res.body);
  if (__ENV.SKIP_PROFILE_CHECK !== '1') {
    if (!info.profile?.includes('mock-aladin') || !info.profile?.includes('perf-seed')) {
      exec.test.abort('[ABORT] mock-aladin,perf-seed 프로파일 없음 — --spring.profiles.active=mock-aladin,perf-seed 로 재시작 필요 (실서버는 SKIP_PROFILE_CHECK=1)');
    }
  }
  if (tokens.length === 0) {
    exec.test.abort('[ABORT] tokens.local.json 없음 — k6 run k6/setup-tokens.js 를 먼저 실행하세요');
  }

  console.log(`[setup] 프로파일: ${info.profile}`);
  console.log(`[setup] VU 배분 — book_auth:${VU_BOOK_AUTH} user:${VU_USER} all:${VU_ALL} (합계:${CONSTANT_VUS})`);
  console.log(`[setup] 토큰: ${tokens.length}개 | 지속시간: ${DURATION}`);
}

export default function () {
  const scenario = exec.scenario.name;
  const vuId     = exec.vu.idInTest - 1;
  const query    = pickQuery();

  switch (scenario) {
    case 'book_auth': runSearch(query, 'book', getToken(vuId), searchAuthDuration, bookErrorRate); break;
    case 'user':      runSearch(query, 'user', getToken(vuId), searchUserDuration, userErrorRate); break;
    case 'all':       runSearch(query, 'all',  getToken(vuId), searchAllDuration,  searchErrorRate); break;
  }
}

function runSearch(query, type, token, durationMetric, scenarioErrorRate) {
  const headers = token ? { Authorization: `Bearer ${token}` } : {};
  const url     = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=${type}&limit=20`;

  const res = http.get(url, { headers });
  const ms  = res.timings.duration;

  durationMetric.add(ms);

  const ok = check(res, {
    '상태코드 200': r => r.status === 200,
    '응답 정상':   r => {
      try { return JSON.parse(r.body).status === 'SUCCESS'; }
      catch { return false; }
    },
  });

  scenarioErrorRate.add(ok ? 0 : 1);
  // all 시나리오는 scenarioErrorRate === searchErrorRate이므로 중복 add 방지
  if (scenarioErrorRate !== searchErrorRate) {
    searchErrorRate.add(ok ? 0 : 1);
  }
  sloViolationRate.add(ms >= SLO_MS ? 1 : 0);
  if (!ok) console.error(`[${type}] 실패 query="${query}" status=${res.status} ${ms}ms`);

  sleep(0.5 + Math.random() * 1); // think time 0.5~1.5s (avg 1s)
}

export function handleSummary(data) {
  const m   = data.metrics;
  const fmt = (key, stat) => {
    const v = m[key]?.values?.[stat];
    return v != null ? `${Math.round(v)}ms` : 'N/A';
  };
  const errPct  = (((m['search_error_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%');
  const httpErr = (((m['http_req_failed']?.values?.rate ?? 0) * 100).toFixed(1) + '%');
  const rps     = (m['http_reqs']?.values?.rate ?? 0).toFixed(1);

  console.log(`
╔════════════════════════════════════════════════════════════════════╗
║  [Constant ${String(CONSTANT_VUS).padEnd(3)} VU] 검색 성능 기준선 측정                   ║
║  duration=${DURATION}  avg RPS=${rps}                              ║
╠════════════════════════════════════════════════════════════════════╣
║                    P50          P95          P99                   ║
║ 도서 검색(인증) ${fmt('search_auth_duration','med').padEnd(12)} ${fmt('search_auth_duration','p(95)').padEnd(12)} ${fmt('search_auth_duration','p(99)')}     ║
║ user            ${fmt('search_user_duration','med').padEnd(12)} ${fmt('search_user_duration','p(95)').padEnd(12)} ${fmt('search_user_duration','p(99)')}     ║
║ all             ${fmt('search_all_duration','med').padEnd(12)} ${fmt('search_all_duration','p(95)').padEnd(12)} ${fmt('search_all_duration','p(99)')}     ║
╠════════════════════════════════════════════════════════════════════╣
║ 검색 에러율: ${errPct.padEnd(8)}  HTTP 에러율: ${httpErr}          ║
╠════════════════════════════════════════════════════════════════════╣
║ 결과 저장: --summary-export k6/results/before-es.json              ║
║ ES 도입 후:  --summary-export k6/results/after-es.json             ║
╚════════════════════════════════════════════════════════════════════╝
`);
  return {};
}
