/**
 * [Step 1-2] 점진적 부하 테스트 (확장판) — 최대 처리 한계 탐색
 *
 * 목적: VU를 0 → 300 까지 단계적으로 올려 시스템의 최대 동시 사용자 수용 한계를 측정한다.
 *       ramping-test.js(최대 50 VU)는 일반 부하 측정용,
 *       이 스크립트는 ES 도입 후 "어디까지 버티는가"를 확인하는 한계 탐색용.
 *
 * VU 배분 (총 300):
 *   book_auth (로그인 도서 검색)  180 VU  ← 핵심 부하 대상
 *   user      (유저 검색)          60 VU
 *   all       (통합 검색)          60 VU
 *
 * 단계별 hold 구간(30초)에서 Grafana P95 추이를 관찰한다.
 * P95 가 SLO(1초)를 처음 넘는 단계의 VU 가 시스템 한계.
 *
 * 사전 준비:
 *   1. docker compose up -d
 *   2. ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
 *   3. "[PerfBookSeeder] 시딩 완료: 1000000건" 로그 확인
 *   4. k6 run k6/setup-tokens.js  (tokens.local.json 생성)
 *
 * 실행:
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          --summary-export k6/results/ramping2-result.json \
 *          k6/ramping-test2.js
 *
 * 총 소요 시간: 약 5분 40초
 *
 * → Grafana에서 P95가 1초를 처음 넘는 VU 단계 = 시스템 동시 사용자 한계
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';
import { KEYWORDS, pickQuery } from './keywords.js';

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

// ── 검색 키워드는 keywords.js 에서 import — 200개 풀 + 20% cache-buster ─────

// ── 단계별 VU 스케줄 (book_auth target 기준, 최대 180 → 총 300 VU) ─────
//   각 hold 구간 30초로 충분한 측정 시간 확보.
//   book_auth 3/5, user 1/5, all 1/5 비율 유지.
const RAMP_STAGES = [
  { duration: '10s', target: 10  }, // 워밍업
  { duration: '30s', target: 10  }, // hold @ 10  (총 ~17 VU)
  { duration: '10s', target: 30  },
  { duration: '30s', target: 30  }, // hold @ 30  (총 ~50 VU)
  { duration: '10s', target: 60  },
  { duration: '30s', target: 60  }, // hold @ 60  (총 ~100 VU)
  { duration: '10s', target: 90  },
  { duration: '30s', target: 90  }, // hold @ 90  (총 ~150 VU)
  { duration: '10s', target: 120 },
  { duration: '30s', target: 120 }, // hold @ 120 (총 ~200 VU)
  { duration: '10s', target: 150 },
  { duration: '30s', target: 150 }, // hold @ 150 (총 ~250 VU)
  { duration: '10s', target: 180 },
  { duration: '30s', target: 180 }, // hold @ 180 (총 ~300 VU) — 최대
  { duration: '10s', target: 0   }, // 종료
];

// 비율 적용 (book_auth target 그대로, user/all 1/3) — book 300 → user 100 / all 100
const userStages = RAMP_STAGES.map(s => ({ ...s, target: Math.round(s.target / 3) }));
const allStages  = RAMP_STAGES.map(s => ({ ...s, target: Math.round(s.target / 3) }));

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    // 로그인 도서 검색 — 최대 180 VU
    book_auth: {
      executor: 'ramping-vus',
      stages: RAMP_STAGES,
      tags: { scenario: 'book_auth' },
    },
    // 유저 검색 — 최대 60 VU
    user: {
      executor: 'ramping-vus',
      stages: userStages,
      tags: { scenario: 'user' },
    },
    // 통합 검색 — 최대 60 VU
    all: {
      executor: 'ramping-vus',
      stages: allStages,
      tags: { scenario: 'all' },
    },
  },
  thresholds: {
    // 한계 탐색용 — threshold 위반해도 테스트 끝까지 실행되도록 느슨하게 설정
    http_req_failed:           ['rate<1.0'],
    search_error_rate:         ['rate<1.0'],
    search_book_error_rate:    ['rate<1.0'],
    search_user_error_rate:    ['rate<1.0'],
    search_slo_violation_rate: ['rate<1.0'],
  },
};

export function setup() {
  const res = http.get(`${BASE_URL}/actuator/info`);
  if (res.status !== 200) exec.test.abort(`[ABORT] /actuator/info 응답 실패: ${res.status}`);

  const info = JSON.parse(res.body);
  if (__ENV.SKIP_PROFILE_CHECK !== '1') {
    if (!info.profile?.includes('mock-aladin') || !info.profile?.includes('perf-seed')) {
      exec.test.abort(`[ABORT] mock-aladin,perf-seed 프로파일 없음 — --spring.profiles.active=mock-aladin,perf-seed 로 재시작 필요 (실서버는 SKIP_PROFILE_CHECK=1)`);
    }
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
  const errPct = (((m['search_error_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%');
  const sloPct = (((m['search_slo_violation_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%');

  console.log(`
╔══════════════════════════════════════════════════════════════════╗
║  [Ramping-2] 최대 처리 한계 탐색 결과 (총 300 VU)                ║
╠══════════════════════════════════════════════════════════════════╣
║ 도서 검색(인증) P50=${fmt('search_auth_duration','med').padEnd(8)} P95=${fmt('search_auth_duration','p(95)').padEnd(8)} P99=${fmt('search_auth_duration','p(99)')} ║
║ user            P50=${fmt('search_user_duration','med').padEnd(8)} P95=${fmt('search_user_duration','p(95)').padEnd(8)} P99=${fmt('search_user_duration','p(99)')} ║
║ all             P50=${fmt('search_all_duration','med').padEnd(8)} P95=${fmt('search_all_duration','p(95)').padEnd(8)} P99=${fmt('search_all_duration','p(99)')} ║
╠══════════════════════════════════════════════════════════════════╣
║ 에러율: ${errPct.padEnd(8)}  SLO 위반(>1s): ${sloPct.padEnd(8)}              ║
╠══════════════════════════════════════════════════════════════════╣
║ → Grafana에서 P95가 1초를 처음 넘는 VU 구간을 확인하세요         ║
║   그 단계가 현 시스템의 동시 사용자 처리 한계                    ║
╚══════════════════════════════════════════════════════════════════╝
`);
  return {};
}
