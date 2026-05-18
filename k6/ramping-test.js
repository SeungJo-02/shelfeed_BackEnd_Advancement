/**
 * [Step 1] 점진적 부하 테스트 — Breaking Point 탐색
 *
 * 목적: VU를 0 → 700 까지 단계적으로 올려 P95가 급등하는 지점(breaking point)을 찾는다.
 *       이 지점 아래의 VU 값을 constant-test.js 의 CONSTANT_VUS 로 설정한다.
 *
 * VU 배분 (총 700):
 *   book_auth (로그인 도서 검색)  500 VU  ← ES 도입 핵심 대상, LIKE 풀스캔 + 히스토리 저장
 *   user      (유저 검색)         100 VU  ← 소규모 테이블, 비교 기준
 *   all       (통합 검색)         100 VU  ← book + user 복합
 *
 * 단계별 hold 구간(1분)에서 Grafana P95 추이를 관찰한다.
 * P95 가 급등하거나 에러가 발생하기 시작한 단계의 VU 가 breaking point.
 *
 * 사전 준비:
 *   1. docker compose up -d
 *   2. ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
 *   3. "[PerfBookSeeder] 시딩 완료: 1000000건" 로그 확인
 *   4. k6 run k6/setup-tokens.js  (tokens.local.json 생성)
 *
 * 실행:
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          --summary-export k6/results/ramping-result.json \
 *          k6/ramping-test.js
 *
 * → Grafana에서 P50·P95·P99 급등 구간 VU를 확인 → constant-test.js 의 CONSTANT_VUS 로 설정
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

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

// ── 검색 키워드 (PerfBookSeeder 시드 키워드와 동일) ─────────────────
const KEYWORDS = [
  '소설', '자기계발', '역사', '과학', '철학',
  '에세이', '시집', '만화', '요리', '여행',
  '심리학', '경제', '경영', '수학', '물리',
  '국어', '영어', '일본어', '중국어', '코딩',
];

// ── 단계별 VU 스케줄 (book_auth 기준 최대 500) ────────────────────
const RAMP_STAGES = [
  { duration: '30s', target: 10  }, // 워밍업
  { duration: '30s', target: 50  }, // → 50
  { duration: '1m',  target: 50  }, // hold — 이 구간 Grafana 관찰
  { duration: '30s', target: 100 }, // → 100
  { duration: '1m',  target: 100 }, // hold
  { duration: '30s', target: 150 }, // → 150
  { duration: '1m',  target: 150 }, // hold
  { duration: '30s', target: 200 }, // → 200
  { duration: '1m',  target: 200 }, // hold
  { duration: '30s', target: 300 }, // → 300
  { duration: '1m',  target: 300 }, // hold
  { duration: '30s', target: 400 }, // → 400
  { duration: '1m',  target: 400 }, // hold
  { duration: '30s', target: 500 }, // → 500 (최대)
  { duration: '1m',  target: 500 }, // hold
  { duration: '30s', target: 0   }, // 종료
];

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    // 로그인 도서 검색 — 최대 500 VU (LIKE 풀스캔 + 검색 히스토리 저장)
    book_auth: {
      executor: 'ramping-vus',
      stages: RAMP_STAGES,
      tags: { scenario: 'book_auth' },
    },
    // 유저 검색 — 최대 100 VU (소규모 테이블, 비교 기준)
    user: {
      executor: 'ramping-vus',
      stages: RAMP_STAGES.map(s => ({ ...s, target: Math.round(s.target * 0.2) })),
      tags: { scenario: 'user' },
    },
    // 통합 검색 — 최대 100 VU (book + user 복합)
    all: {
      executor: 'ramping-vus',
      stages: RAMP_STAGES.map(s => ({ ...s, target: Math.round(s.target * 0.2) })),
      tags: { scenario: 'all' },
    },
  },
  thresholds: {
    http_req_failed:           ['rate<0.15'],
    search_error_rate:         ['rate<0.15'],
    search_book_error_rate:    ['rate<0.15'],
    search_user_error_rate:    ['rate<0.15'],
    search_slo_violation_rate: ['rate<0.80'],
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/actuator/info`);
  if (res.status !== 200) exec.test.abort(`[ABORT] /actuator/info 응답 실패: ${res.status}`);

  const info = JSON.parse(res.body);
  if (!info.profile?.includes('mock-aladin') || !info.profile?.includes('perf-seed')) {
    exec.test.abort(`[ABORT] mock-aladin,perf-seed 프로파일 없음 — --spring.profiles.active=mock-aladin,perf-seed 로 재시작 필요`);
  }
  if (tokens.length === 0) {
    exec.test.abort('[ABORT] tokens.local.json 없음 — k6 run k6/setup-tokens.js 를 먼저 실행하세요');
  }
  console.log(`[setup] 프로파일: ${info.profile} | 토큰: ${tokens.length}개`);

  // JVM JIT + HikariCP 워밍업
  console.log('[setup] JVM 워밍업 시작...');
  for (const kw of KEYWORDS.slice(0, 5)) {
    const token = tokens[0];
    http.get(`${BASE_URL}/api/v1/search?query=${encodeURIComponent(kw)}&type=book&limit=1`,
      { headers: { Authorization: `Bearer ${token}` } });
  }
  console.log('[setup] JVM 워밍업 완료');
}

export default function () {
  const scenario = exec.scenario.name;
  const vuId     = exec.vu.idInTest - 1;
  const query    = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];

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
  const errPct = (((m['search_error_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%');

  console.log(`
╔══════════════════════════════════════════════════════════════════╗
║  [Ramping] Breaking Point 탐색 결과                              ║
╠══════════════════════════════════════════════════════════════════╣
║ 도서 검색(인증) P50=${fmt('search_auth_duration','med').padEnd(8)} P95=${fmt('search_auth_duration','p(95)').padEnd(8)} P99=${fmt('search_auth_duration','p(99)')} ║
║ user            P50=${fmt('search_user_duration','med').padEnd(8)} P95=${fmt('search_user_duration','p(95)').padEnd(8)} P99=${fmt('search_user_duration','p(99)')} ║
║ all             P50=${fmt('search_all_duration','med').padEnd(8)} P95=${fmt('search_all_duration','p(95)').padEnd(8)} P99=${fmt('search_all_duration','p(99)')} ║
╠══════════════════════════════════════════════════════════════════╣
║ 에러율: ${errPct.padEnd(8)}                                      ║
╠══════════════════════════════════════════════════════════════════╣
║ → Grafana에서 P95 급등 구간의 VU를 확인하고                      ║
║   그 아래 값을 CONSTANT_VUS 로 constant-test.js 에 설정하세요    ║
╚══════════════════════════════════════════════════════════════════╝
`);
  return {};
}
