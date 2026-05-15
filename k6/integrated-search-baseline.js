/**
 * [Baseline] GET /api/v1/search — SearchService LIKE 스캔 성능 기준선
 *
 * 목적: ES 도입 전 MySQL LIKE '%query%' 스캔 성능을 측정한다.
 *       MockAladinApiClient(sleep 30ms)로 외부 API 차단 없이 현실적 측정.
 *       이 수치가 ES 도입 후 비교 기준(Before)이 된다.
 *
 * 시나리오:
 *   - type_book      : 비인증 book 검색 (LIKE 풀스캔 순수 측정)
 *   - type_book_auth : 인증 book 검색 (검색 히스토리 저장 + 블록 필터링 포함)
 *   - type_user      : 비인증 user 검색
 *   - type_all       : 비인증 all(book+user) 검색
 *   - pagination_cursor : cursor 페이지네이션
 *
 * 실행 전제:
 *   1. docker compose up -d mysql redis
 *   2. ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
 *   3. "[PerfBookSeeder] 시딩 완료: 100000건" 확인 후 실행
 *   4. (type_book_auth 포함 시) k6 run k6/setup-users.js 로 tokens.local.json 생성
 *
 * 실행:
 *   k6 run k6/integrated-search-baseline.js
 *
 * 결과 저장:
 *   k6 run --out json=k6/results/integrated-search-before-es.json k6/integrated-search-baseline.js
 */

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

let tokens = [];
try {
  tokens = new SharedArray('tokens', function () {
    return JSON.parse(open('./tokens.local.json'));
  });
} catch (_) {}

function getAuthHeader() {
  const token = tokens.length > 0 ? tokens[(exec.vu.idInTest - 1) % tokens.length] : null;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const searchLikeDuration     = new Trend('integrated_search_like_duration', true);
const searchLikeAuthDuration = new Trend('integrated_search_like_auth_duration', true);
const searchUserDuration     = new Trend('integrated_search_user_duration', true);
const searchAllDuration      = new Trend('integrated_search_all_duration', true);
const paginationDuration     = new Trend('integrated_pagination_duration', true);
const searchErrorRate        = new Rate('integrated_search_error_rate');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    // 시나리오 1: book LIKE 스캔 — ES 도입 후 핵심 개선 대상 (비인증)
    type_book: {
      executor: 'constant-vus',
      vus: 150,
      duration: '60s',
      startTime: '30s',
      tags: { scenario: 'type_book' },
    },
    // 시나리오 2: 인증 book 검색 — 검색 히스토리 저장(@Transactional 쓰기) + 블록 필터링 포함
    // 실서비스 사용자 기준 ES before/after 비교에 필요. tokens.local.json 없으면 skip됨
    type_book_auth: {
      executor: 'constant-vus',
      vus: 150,
      duration: '60s',
      startTime: '30s',
      tags: { scenario: 'type_book_auth' },
    },
    // 시나리오 3: user 검색
    type_user: {
      executor: 'constant-vus',
      vus: 150,
      duration: '60s',
      startTime: '30s',
      tags: { scenario: 'type_user' },
    },
    // 시나리오 4: all (book+user 합산)
    type_all: {
      executor: 'constant-vus',
      vus: 150,
      duration: '60s',
      startTime: '30s',
      tags: { scenario: 'type_all' },
    },
    // 시나리오 5: cursor 페이지네이션
    pagination_cursor: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 5,
      startTime: '100s',
      tags: { scenario: 'pagination_cursor' },
    },
  },
  thresholds: {
    http_req_failed:                      ['rate<0.05'],
    integrated_search_like_duration:      ['p(95)<5000'],
    integrated_search_like_auth_duration: ['p(95)<5000'],
    integrated_search_error_rate:         ['rate<0.05'],
  },
};

const KEYWORDS = [
  '소설', '자기계발', '역사', '과학', '철학',
  '에세이', '시집', '만화', '요리', '여행',
  '심리학', '경제', '경영', '수학', '물리',
  '국어', '영어', '일본어', '중국어', '코딩',
];

export function setup() {
  // 프로파일 가드
  const infoRes = http.get(`${BASE_URL}/actuator/info`);
  if (infoRes.status !== 200) {
    fail(`[ABORT] /actuator/info 응답 실패: status=${infoRes.status}`);
  }
  try {
    const info = JSON.parse(infoRes.body);
    if (!info.profile || !info.profile.includes('mock-aladin')) {
      fail(`[ABORT] mock-aladin 프로파일 없음: ${info.profile} — 서버를 --spring.profiles.active=mock-aladin,perf-seed 로 재시작하세요`);
    }
    console.log(`[setup] 프로파일 확인: ${info.profile}`);
  } catch (e) {
    fail(`[ABORT] /actuator/info 파싱 실패: ${e.message}`);
  }

  // 인증 시나리오 토큰 확인 — 없으면 type_book_auth가 비인증으로 실행되어 메트릭 오염
  if (tokens.length === 0) {
    fail('[ABORT] tokens.local.json 없음 — type_book_auth 시나리오는 인증 토큰이 필요합니다. k6 run k6/setup-users.js 를 먼저 실행하세요');
  }
  console.log(`[setup] 토큰 ${tokens.length}개 로드 완료 — type_book_auth 인증 활성화`);

  // Redis 워밍업 — 키워드별 첫 호출로 isAladinQuerySynced 플래그 설정
  console.log('[setup] Redis 워밍업 시작...');
  const warmupStart = Date.now();
  for (const kw of KEYWORDS) {
    const url = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(kw)}&type=book&limit=1`;
    http.get(url);
  }
  const warmupMs = Date.now() - warmupStart;
  console.log(`[setup] Redis 워밍업 완료 (${KEYWORDS.length}개 키워드, ${warmupMs}ms)`);

  return { warmupMs };
}

export default function () {
  const scenario = exec.scenario.name;
  const query = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];

  if      (scenario === 'type_book')       runSearch(query, 'book', searchLikeDuration);
  else if (scenario === 'type_book_auth')  runSearch(query, 'book', searchLikeAuthDuration, getAuthHeader());
  else if (scenario === 'type_user')       runSearch(query, 'user', searchUserDuration);
  else if (scenario === 'type_all')        runSearch(query, 'all',  searchAllDuration);
  else if (scenario === 'pagination_cursor') runPagination(query);
}

function runSearch(query, type, metric, headers = {}) {
  const url = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=${type}&limit=20`;
  const start = Date.now();
  const res = http.get(url, { headers });
  const elapsed = Date.now() - start;

  metric.add(elapsed);

  const ok = check(res, {
    '상태코드 200': (r) => r.status === 200,
    '응답 구조 정상': (r) => {
      try { return JSON.parse(r.body).status === 'SUCCESS'; }
      catch { return false; }
    },
  });

  const label = Object.keys(headers).length > 0 ? `${type}검색(auth)` : `${type}검색`;
  if (!ok) {
    searchErrorRate.add(1);
    console.error(`[${label}] 실패 query="${query}" status=${res.status} ${elapsed}ms`);
  } else {
    searchErrorRate.add(0);
    console.log(`[${label}] query="${query}" | ${elapsed}ms`);
  }
  sleep(0.05);
}

function runPagination(query) {
  const url1 = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=book&limit=10`;
  const start1 = Date.now();
  const res1 = http.get(url1);
  const page1 = Date.now() - start1;
  paginationDuration.add(page1);

  let nextCursor = null;
  const ok1 = check(res1, {
    '[page1] 상태코드 200': (r) => r.status === 200,
    '[page1] 응답 정상': (r) => {
      try {
        const data = JSON.parse(r.body).data;
        nextCursor = data?.nextCursor ?? null;
        return data !== undefined;
      } catch { return false; }
    },
  });
  searchErrorRate.add(ok1 ? 0 : 1);
  console.log(`[pagination] page1 query="${query}" | ${page1}ms`);
  sleep(0.3);

  if (!nextCursor) {
    console.warn(`[pagination] nextCursor 없음 (query="${query}")`);
    return;
  }

  const url2 = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=book&limit=10&cursor=${nextCursor}`;
  const start2 = Date.now();
  const res2 = http.get(url2);
  const page2 = Date.now() - start2;
  paginationDuration.add(page2);

  const ok2 = check(res2, { '[page2] 상태코드 200': (r) => r.status === 200 });
  searchErrorRate.add(ok2 ? 0 : 1);
  console.log(`[pagination] page2 | ${page2}ms`);
  sleep(0.5);
}

export function handleSummary(data) {
  const m = data.metrics;
  const fmt = (key, sub) => {
    const val = m[key]?.values?.[sub];
    return val != null ? `${Math.round(val)}ms` : 'N/A';
  };

  const errRate = (((m['integrated_search_error_rate']?.values?.rate ?? 0) * 100).toFixed(1) + '%').padEnd(8);

  console.log(`
╔══════════════════════════════════════════════════════════════════╗
║  [Before ES] SearchService LIKE 스캔 성능 기준선                 ║
║  (MockAladinApiClient sleep 30ms + 100k 시드 데이터)            ║
╠══════════════════════════════════════════════════════════════════╣
║ [type=book 비인증 — ES 도입 후 핵심 개선 대상]                   ║
║   P50 : ${fmt('integrated_search_like_duration','med').padEnd(8)} ← ES 도입 후 목표: <30ms  ║
║   P95 : ${fmt('integrated_search_like_duration','p(95)').padEnd(8)} ← ES 도입 후 목표: <100ms ║
║   P99 : ${fmt('integrated_search_like_duration','p(99)').padEnd(8)}                         ║
╠══════════════════════════════════════════════════════════════════╣
║ [type=book 인증 — 히스토리 저장+블록 필터링 포함]                ║
║   P50 : ${fmt('integrated_search_like_auth_duration','med').padEnd(8)}                      ║
║   P95 : ${fmt('integrated_search_like_auth_duration','p(95)').padEnd(8)} ← ES 도입 후 목표: <100ms ║
║   P99 : ${fmt('integrated_search_like_auth_duration','p(99)').padEnd(8)}                    ║
╠══════════════════════════════════════════════════════════════════╣
║ [type=user]                                                      ║
║   P50 : ${fmt('integrated_search_user_duration','med').padEnd(8)}                           ║
║   P95 : ${fmt('integrated_search_user_duration','p(95)').padEnd(8)}                         ║
╠══════════════════════════════════════════════════════════════════╣
║ [type=all]                                                       ║
║   P50 : ${fmt('integrated_search_all_duration','med').padEnd(8)}                            ║
║   P95 : ${fmt('integrated_search_all_duration','p(95)').padEnd(8)}                          ║
╠══════════════════════════════════════════════════════════════════╣
║ [pagination cursor]                                              ║
║   P50 : ${fmt('integrated_pagination_duration','med').padEnd(8)}                            ║
║   P95 : ${fmt('integrated_pagination_duration','p(95)').padEnd(8)}                          ║
╠══════════════════════════════════════════════════════════════════╣
║ 오류율: ${errRate}                                               ║
╠══════════════════════════════════════════════════════════════════╣
║ ES 도입 후 이 파일 동일하게 재실행 → before/after 비교           ║
╚══════════════════════════════════════════════════════════════════╝
`);
  return {};
}
