/**
 * [Baseline] GET /api/v1/books/search — BookService 성능 측정
 *
 * 목적: ES 도입 전 현재 성능을 수치로 기록한다.
 *       MockAladinApiClient(sleep 30ms)로 외부 API 차단 없이 현실적 측정.
 *
 * 실행 전제:
 *   ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
 *   "[PerfBookSeeder] 시딩 완료: 100000건" 확인 후 실행
 *
 * 실행:
 *   k6 run k6/book-search-baseline.js
 *
 * 결과 저장:
 *   k6 run --out json=k6/results/book-search-before-es.json k6/book-search-baseline.js
 */

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

let tokens = [];
try {
  tokens = new SharedArray('tokens', function () {
    return JSON.parse(open('./tokens.local.json'));
  });
} catch (_) {}

function getToken(vuIndex) {
  return tokens.length > 0 ? tokens[vuIndex % tokens.length] : null;
}
function authHeader(token) {
  return token ? { Authorization: `Bearer ${token}` } : {};
}

const bookSearchDuration  = new Trend('book_search_duration', true);
const concurrentDuration  = new Trend('book_search_concurrent_duration', true);
const paginationDuration  = new Trend('book_search_pagination_duration', true);
const aladinErrorRate     = new Rate('book_search_aladin_error_rate');
const emptyResultCount    = new Counter('book_search_empty_results');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    // 시나리오 1: 단일 사용자 반복 검색 — 10건
    single_user_search: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 10,
      startTime: '0s',
    },
    // 시나리오 2: 동시 사용자 동일 키워드 — 10건 (shared)
    concurrent_same_keyword: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 10,
      startTime: '15s',
    },
    // 시나리오 3: 다양한 키워드 캐시 미스 — 5VU×30s
    diverse_keywords: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      startTime: '35s',
    },
    // 시나리오 4: 페이지 전환 — 3VU×3iter×3page
    pagination: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 3,
      startTime: '70s',
    },
  },
  thresholds: {
    http_req_failed:               ['rate<0.05'],
    book_search_duration:          ['p(95)<5000'],
    book_search_aladin_error_rate: ['rate<0.05'],
  },
};

const SINGLE_KEYWORDS  = ['채식주의자', '82년생 김지영', '아몬드', '해리포터', '어린왕자',
                          '소설', '자기계발', '역사', '과학', '철학'];
const SAME_KEYWORD     = '채식주의자';
const DIVERSE_KEYWORDS = ['소설', '자기계발', '역사', '과학', '철학',
                          '에세이', '시집', '만화', '요리', '여행',
                          '심리학', '경제', '경영', '수학', '물리'];
const PAGINATION_QUERY = '소설';

export function setup() {
  const res = http.get(`${BASE_URL}/actuator/info`);
  if (res.status !== 200) {
    fail(`[ABORT] /actuator/info 응답 실패: status=${res.status}`);
  }
  try {
    const info = JSON.parse(res.body);
    if (!info.profile || !info.profile.includes('mock-aladin')) {
      fail(`[ABORT] mock-aladin 프로파일 없음: ${info.profile} — 서버를 --spring.profiles.active=mock-aladin,perf-seed 로 재시작하세요`);
    }
    console.log(`[setup] 프로파일 확인: ${info.profile}`);
  } catch (e) {
    fail(`[ABORT] /actuator/info 파싱 실패: ${e.message}`);
  }
}

export default function () {
  const scenario = exec.scenario.name;
  const token = getToken(exec.vu.idInTest - 1);

  if      (scenario === 'single_user_search')      runSingleSearch(token);
  else if (scenario === 'concurrent_same_keyword') runConcurrentSearch(token);
  else if (scenario === 'diverse_keywords')        runDiverseSearch(token);
  else if (scenario === 'pagination')              runPaginationSearch(token);
}

function runSingleSearch(token) {
  const keyword = SINGLE_KEYWORDS[Math.floor(Math.random() * SINGLE_KEYWORDS.length)];
  const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(keyword)}&page=1&limit=10`;

  const start = Date.now();
  const res = http.get(url, { headers: authHeader(token) });
  const elapsed = Date.now() - start;
  bookSearchDuration.add(elapsed);

  const ok = check(res, {
    '상태코드 200': (r) => r.status === 200,
    '응답 구조 정상': (r) => {
      try {
        const j = JSON.parse(r.body);
        return j.status === 'SUCCESS' && Array.isArray(j.data?.content);
      } catch { return false; }
    },
  });

  if (!ok) {
    aladinErrorRate.add(1);
    console.error(`[단건] 실패 "${keyword}" status=${res.status} ${elapsed}ms`);
  } else {
    aladinErrorRate.add(0);
    const count = JSON.parse(res.body).data?.content?.length ?? 0;
    if (count === 0) emptyResultCount.add(1);
    console.log(`[단건] "${keyword}" | ${count}건 | ${elapsed}ms`);
  }
  sleep(0.5);
}

function runConcurrentSearch(token) {
  const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(SAME_KEYWORD)}&page=1&limit=10`;

  const start = Date.now();
  const res = http.get(url, { headers: authHeader(token) });
  const elapsed = Date.now() - start;
  concurrentDuration.add(elapsed);

  const ok = check(res, {
    '상태코드 200': (r) => r.status === 200,
    '결과 있음': (r) => {
      try { return JSON.parse(r.body).status === 'SUCCESS'; }
      catch { return false; }
    },
  });

  if (!ok) {
    aladinErrorRate.add(1);
    console.error(`[동시] 실패 status=${res.status} ${elapsed}ms`);
  } else {
    aladinErrorRate.add(0);
    console.log(`[동시] "${SAME_KEYWORD}" | ${elapsed}ms`);
  }
  sleep(0.5);
}

function runDiverseSearch(token) {
  const keyword = DIVERSE_KEYWORDS[Math.floor(Math.random() * DIVERSE_KEYWORDS.length)];
  const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(keyword)}&page=1&limit=10`;

  const start = Date.now();
  const res = http.get(url, { headers: authHeader(token) });
  const elapsed = Date.now() - start;
  bookSearchDuration.add(elapsed);

  check(res, { '상태코드 200': (r) => r.status === 200 });

  if (res.status !== 200) {
    aladinErrorRate.add(1);
    console.error(`[다양] 실패 "${keyword}" status=${res.status} ${elapsed}ms`);
  } else {
    aladinErrorRate.add(0);
    console.log(`[다양] "${keyword}" | ${elapsed}ms`);
  }
  sleep(0.5);
}

function runPaginationSearch(token) {
  for (let page = 1; page <= 3; page++) {
    const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(PAGINATION_QUERY)}&page=${page}&limit=10`;

    const start = Date.now();
    const res = http.get(url, { headers: authHeader(token) });
    const elapsed = Date.now() - start;
    paginationDuration.add(elapsed);

    const ok = check(res, { [`page=${page} 상태코드 200`]: (r) => r.status === 200 });
    aladinErrorRate.add(ok ? 0 : 1);
    console.log(`[페이지] page=${page} | ${elapsed}ms`);
    sleep(0.5);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const fmt = (key, sub) => {
    const val = m[key]?.values?.[sub];
    return val != null ? `${Math.round(val)}ms` : 'N/A';
  };
  const errRate = ((m['book_search_aladin_error_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%';

  console.log(`
╔════════════════════════════════════════════════════════════════╗
║  [Before ES] BookService 검색 성능 기준선                      ║
║  (MockAladinApiClient, sleep 30ms — 외부 API 차단 없음)       ║
╠════════════════════════════════════════════════════════════════╣
║ 단건/다양 검색 (book_search_duration)                          ║
║   P50 : ${fmt('book_search_duration','med').padEnd(8)}                                 ║
║   P95 : ${fmt('book_search_duration','p(95)').padEnd(8)}                               ║
║   P99 : ${fmt('book_search_duration','p(99)').padEnd(8)}                               ║
╠════════════════════════════════════════════════════════════════╣
║ 동시 요청 (book_search_concurrent_duration)                    ║
║   P95 : ${fmt('book_search_concurrent_duration','p(95)').padEnd(8)}                    ║
╠════════════════════════════════════════════════════════════════╣
║ 페이지네이션 (book_search_pagination_duration)                 ║
║   P95 : ${fmt('book_search_pagination_duration','p(95)').padEnd(8)}                    ║
╠════════════════════════════════════════════════════════════════╣
║ 오류율: ${errRate.padEnd(8)}                                   ║
╠════════════════════════════════════════════════════════════════╣
║ ES 도입 후 동일하게 재실행 → before/after 비교 가능           ║
╚════════════════════════════════════════════════════════════════╝
`);
  return {};
}
