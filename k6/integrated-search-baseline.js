/**
 * [Baseline] GET /api/v1/search — SearchService 성능 측정
 *
 * 목적: ES 도입 전 현재 성능을 수치로 기록한다.
 *       이 결과가 ES 도입 후 비교 기준(Before)이 된다.
 *
 * 측정 대상:
 *   - Redis 캐시 히트/미스에 따른 응답시간 차이
 *   - type=all / type=book / type=user 별 응답시간
 *   - MySQL LIKE 검색 응답시간
 *   - cursor 기반 페이지네이션 성능
 *   - 로그인 시 검색 기록 저장이 응답시간에 미치는 영향
 *
 * 사전 준비:
 *   1. 서버 실행 확인: http://localhost:8080/actuator/health
 *   2. (선택) 로그인 시나리오 포함 시: k6 run k6/setup-users.js 로 토큰 생성
 *
 * 실행:
 *   k6 run k6/integrated-search-baseline.js
 *   k6 run -e BASE_URL=http://54.180.101.81:8080 k6/integrated-search-baseline.js
 *
 * 결과 저장 (ES 도입 후 비교용):
 *   k6 run --out json=k6/results/integrated-search-before-es.json k6/integrated-search-baseline.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

// ── 공통 설정 ─────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

let tokens = [];
try {
  tokens = new SharedArray('tokens', function () {
    return JSON.parse(open('./tokens.local.json'));
  });
} catch (_) {
  // tokens.local.json 없으면 비로그인으로만 진행
}

function getToken(vuIndex) {
  return tokens.length > 0 ? tokens[vuIndex % tokens.length] : null;
}

function authHeader(token) {
  return token
    ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    : { 'Content-Type': 'application/json' };
}

// ── 커스텀 메트릭 ─────────────────────────────────────────────────────────
const cacheMissDuration  = new Trend('integrated_cache_miss_duration', true);   // Redis 캐시 미스 (첫 검색)
const cacheHitDuration   = new Trend('integrated_cache_hit_duration', true);    // Redis 캐시 히트 (재검색)
const typeAllDuration    = new Trend('integrated_type_all_duration', true);     // type=all 응답시간
const typeBookDuration   = new Trend('integrated_type_book_duration', true);    // type=book 응답시간
const typeUserDuration   = new Trend('integrated_type_user_duration', true);    // type=user 응답시간
const paginationDuration          = new Trend('integrated_pagination_duration', true);            // cursor 페이지네이션
const typeAllAuthenticatedDuration = new Trend('integrated_type_all_authenticated_duration', true); // 로그인 type=all
const errorRate                   = new Rate('integrated_search_error_rate');                      // 전체 오류율
const emptyBookCount     = new Counter('integrated_empty_book_results');        // 책 결과 없음
const emptyUserCount     = new Counter('integrated_empty_user_results');        // 유저 결과 없음

// ── 테스트 시나리오 ───────────────────────────────────────────────────────
export const options = {
  scenarios: {

    /**
     * 시나리오 1: Redis 캐시 미스 vs 히트 응답시간 비교
     *
     * 같은 키워드를 짧은 간격으로 두 번 호출.
     *   - 첫 번째 호출: Redis 캐시 없음 → 알라딘 syncFromAladin() 호출 → 느림
     *   - 두 번째 호출: Redis TTL(5분) 내 재호출 → 알라딘 건너뜀 → 빠름
     *
     * 이 차이가 곧 "알라딘 동기화 비용"이고,
     * ES 도입 후에는 두 번 모두 ES가 처리 → 둘 다 빨라짐.
     */
    cache_miss_vs_hit: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 5,
      startTime: '0s',
      tags: { scenario: 'cache_miss_vs_hit' },
    },

    /**
     * 시나리오 2: type별 응답시간 비교 (all / book / user)
     *
     * type=all: 책 + 유저 동시 검색 → 쿼리 2개 + 알라딘 호출
     * type=book: 책만 검색 → 알라딘 호출 + MySQL LIKE
     * type=user: 유저만 검색 → MySQL LIKE (알라딘 호출 없음)
     *
     * type=user가 가장 빠를 것으로 예상.
     * ES 도입 후: type별 차이가 줄어듦 (모두 ES가 처리).
     */
    type_comparison: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 10,
      startTime: '20s',
      tags: { scenario: 'type_comparison' },
    },

    /**
     * 시나리오 3: 동시 사용자 부하 — MySQL LIKE 검색 병목 측정
     *
     * 여러 사용자가 동시에 다양한 키워드로 검색.
     * MySQL LIKE '%keyword%' 풀스캔 + 알라딘 동시 호출 시 응답시간 분포 기록.
     * ES 도입 후 같은 시나리오에서 응답시간이 크게 줄어야 함.
     */
    concurrent_load: {
      executor: 'constant-vus',
      vus: 20,
      duration: '30s',
      startTime: '50s',
      tags: { scenario: 'concurrent_load' },
    },

    /**
     * 시나리오 4: cursor 페이지네이션 성능
     *
     * 첫 페이지(cursor=null) → 두 번째 페이지(cursor=마지막ID) 순서로 호출.
     * 두 번째 페이지는 알라딘 호출 없음(cursor != null 조건) → 더 빠를 것.
     * ES 도입 후: 두 페이지 모두 ES가 처리 → 차이 더 줄어듦.
     */
    cursor_pagination: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 5,
      startTime: '85s',
      tags: { scenario: 'cursor_pagination' },
    },

    /**
     * 시나리오 5: 로그인 사용자 검색 — 검색 기록 저장 영향 측정
     *
     * 로그인 상태에서 검색 시 SearchHistory INSERT가 트랜잭션에 포함됨.
     * 비로그인 대비 응답시간 차이를 기록.
     * (tokens.local.json 없으면 이 시나리오는 비로그인으로 동작)
     */
    authenticated_search: {
      executor: 'constant-vus',
      vus: 5,
      duration: '20s',
      startTime: '110s',
      tags: { scenario: 'authenticated_search' },
    },
  },

  // ── Threshold: 현재(Before ES) 기준선 ──────────────────────────────────
  thresholds: {
    // 전체 HTTP 실패율 5% 미만
    http_req_failed:                         ['rate<0.05'],
    // 캐시 미스 P95: 알라딘 호출 포함 3000ms 이하면 현재 수준 통과
    // ES 도입 후 목표: P95 < 100ms (캐시 미스도 ES가 처리)
    integrated_cache_miss_duration:  ['p(95)<3000'],
    // 캐시 히트 P95: 알라딘 건너뜀 → 1500ms 이하
    // ES 도입 후 목표: P95 < 50ms
    integrated_cache_hit_duration:   ['p(95)<1500'],
    // type=all P95: 책+유저 동시 → 3000ms 이하
    integrated_type_all_duration:    ['p(95)<3000'],
    // type=book P95: 알라딘 호출 포함 → 3000ms 이하
    integrated_type_book_duration:   ['p(95)<3000'],
    // type=user P95: 알라딘 없음, MySQL만 → 500ms 이하
    integrated_type_user_duration:   ['p(95)<500'],
    // cursor 페이지네이션 P95: 2페이지는 알라딘 없음 → 1500ms 이하
    integrated_pagination_duration:  ['p(95)<1500'],
    // 전체 오류율 5% 미만
    integrated_search_error_rate:            ['rate<0.05'],
  },
};

// ── 테스트 키워드 ─────────────────────────────────────────────────────────
const CACHE_TEST_KEYWORD = '소설';          // 시나리오 1: 캐시 미스/히트 비교
const BOOK_KEYWORDS = [                     // 시나리오 2, 3: 책 검색
  '소설', '자기계발', '역사', '과학', '철학',
  '에세이', '시', '경제', '심리', '여행',
];
const USER_KEYWORDS = [                     // 시나리오 2, 5: 유저 검색
  '독서', '책', '소설', '리뷰', '독후감',
];
const PAGINATION_KEYWORD = '한국 소설';    // 시나리오 4: 결과 많은 키워드

// ── 시나리오별 실행 함수 ──────────────────────────────────────────────────

export default function () {
  const scenario = __ENV.K6_SCENARIO_NAME || exec.scenario.name;
  const token = getToken(__VU - 1);

  if      (scenario === 'cache_miss_vs_hit')    runCacheMissHit(token);
  else if (scenario === 'type_comparison')      runTypeComparison(token);
  else if (scenario === 'concurrent_load')      runConcurrentLoad(token);
  else if (scenario === 'cursor_pagination')    runCursorPagination(token);
  else if (scenario === 'authenticated_search') runAuthenticatedSearch(token);
}

/**
 * 시나리오 1: 캐시 미스 vs 히트 응답시간 비교
 */
function runCacheMissHit(token) {
  group('캐시 미스 vs 히트 비교', () => {
    const url = (cursor) =>
      `${BASE_URL}/api/v1/search?query=${encodeURIComponent(CACHE_TEST_KEYWORD)}&type=book&limit=10${cursor ? `&cursor=${cursor}` : ''}`;

    // 첫 번째 호출: Redis 캐시 미스 → 알라딘 syncFromAladin() 실행
    const start1 = Date.now();
    const res1   = http.get(url(null), { headers: authHeader(null) });
    const miss   = Date.now() - start1;
    cacheMissDuration.add(miss);

    const ok1 = check(res1, {
      '[캐시미스] 상태코드 200': (r) => r.status === 200,
      '[캐시미스] 책 결과 존재': (r) => {
        try { return JSON.parse(r.body).data?.books?.content?.length > 0; }
        catch { return false; }
      },
    });
    if (!ok1) errorRate.add(1);
    else errorRate.add(0);
    console.log(`[캐시미스] ${miss}ms | status=${res1.status}`);

    sleep(0.5);

    // 두 번째 호출: Redis TTL 5분 내 → 알라딘 건너뜀 (캐시 히트)
    const start2 = Date.now();
    const res2   = http.get(url(null), { headers: authHeader(null) });
    const hit    = Date.now() - start2;
    cacheHitDuration.add(hit);

    const ok2 = check(res2, {
      '[캐시히트] 상태코드 200': (r) => r.status === 200,
    });
    if (!ok2) errorRate.add(1); else errorRate.add(0);
    console.log(`[캐시히트] ${hit}ms | 차이: ${miss - hit}ms 단축`);

    sleep(1);
  });
}

/**
 * 시나리오 2: type=all / book / user 응답시간 비교
 */
function runTypeComparison(token) {
  const vuMod = __VU % 3;

  if (vuMod === 0) {
    // type=all: 책 + 유저 동시
    group('type=all 검색', () => {
      const query = BOOK_KEYWORDS[Math.floor(Math.random() * BOOK_KEYWORDS.length)];
      const url   = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=all&limit=10`;

      const start = Date.now();
      const res   = http.get(url, { headers: authHeader(null) });
      const elapsed = Date.now() - start;
      typeAllDuration.add(elapsed);

      const ok = check(res, {
        '[type=all] 상태코드 200':     (r) => r.status === 200,
        '[type=all] books 필드 존재':  (r) => {
          try { return 'books' in JSON.parse(r.body).data; }
          catch { return false; }
        },
        '[type=all] users 필드 존재':  (r) => {
          try { return 'users' in JSON.parse(r.body).data; }
          catch { return false; }
        },
      });
      if (!ok) errorRate.add(1); else errorRate.add(0);
      console.log(`[type=all] query="${query}" | ${elapsed}ms`);
    });

  } else if (vuMod === 1) {
    // type=book: 책만
    group('type=book 검색', () => {
      const query = BOOK_KEYWORDS[Math.floor(Math.random() * BOOK_KEYWORDS.length)];
      const url   = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=book&limit=10`;

      const start = Date.now();
      const res   = http.get(url, { headers: authHeader(null) });
      const elapsed = Date.now() - start;
      typeBookDuration.add(elapsed);

      const ok = check(res, {
        '[type=book] 상태코드 200': (r) => r.status === 200,
      });
      if (!ok) errorRate.add(1); else errorRate.add(0);

      try {
        const data = JSON.parse(res.body).data;
        if (data?.books?.content?.length === 0) emptyBookCount.add(1);
      } catch { /* ignore */ }
      console.log(`[type=book] query="${query}" | ${elapsed}ms`);
    });

  } else {
    // type=user: 유저만 (알라딘 호출 없음 — 가장 빠를 것)
    group('type=user 검색', () => {
      const query = USER_KEYWORDS[Math.floor(Math.random() * USER_KEYWORDS.length)];
      const url   = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=user&limit=10`;

      const start = Date.now();
      const res   = http.get(url, { headers: authHeader(null) });
      const elapsed = Date.now() - start;
      typeUserDuration.add(elapsed);

      const ok = check(res, {
        '[type=user] 상태코드 200': (r) => r.status === 200,
      });
      if (!ok) errorRate.add(1); else errorRate.add(0);

      try {
        const data = JSON.parse(res.body).data;
        if (data?.users?.content?.length === 0) emptyUserCount.add(1);
      } catch { /* ignore */ }
      console.log(`[type=user] query="${query}" | ${elapsed}ms`);
    });
  }

  sleep(0.5);
}

/**
 * 시나리오 3: 동시 부하 — MySQL LIKE 병목 측정
 */
function runConcurrentLoad(token) {
  group('동시 부하 (MySQL LIKE 병목)', () => {
    const query = BOOK_KEYWORDS[Math.floor(Math.random() * BOOK_KEYWORDS.length)];
    const type  = Math.random() < 0.6 ? 'all' : 'book'; // 60% all, 40% book
    const url   = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=${type}&limit=10`;

    const start = Date.now();
    const res   = http.get(url, { headers: authHeader(null) });
    const elapsed = Date.now() - start;

    if (type === 'all') typeAllDuration.add(elapsed);
    else typeBookDuration.add(elapsed);

    check(res, {
      '상태코드 200': (r) => r.status === 200,
    });

    if (res.status !== 200) {
      errorRate.add(1);
      console.error(`[동시부하] VU${__VU} 실패 | type=${type} query="${query}" | ${res.status} | ${elapsed}ms`);
    } else {
      errorRate.add(0);
    }
  });
}

/**
 * 시나리오 4: cursor 페이지네이션 — 1페이지 → 2페이지
 */
function runCursorPagination(token) {
  group('cursor 페이지네이션', () => {
    // 1페이지: cursor=null → 알라딘 syncFromAladin() 포함
    const url1  = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(PAGINATION_KEYWORD)}&type=book&limit=10`;
    const start1 = Date.now();
    const res1   = http.get(url1, { headers: authHeader(null) });
    const page1  = Date.now() - start1;
    paginationDuration.add(page1);

    let nextCursor = null;
    const ok1 = check(res1, {
      '[1페이지] 상태코드 200':  (r) => r.status === 200,
      '[1페이지] hasNext 존재':  (r) => {
        try {
          const data = JSON.parse(r.body).data;
          nextCursor = data?.books?.nextCursor ?? null;
          return data?.books?.hasNext !== undefined;
        } catch { return false; }
      },
    });
    if (!ok1) errorRate.add(1); else errorRate.add(0);
    console.log(`[1페이지] ${page1}ms | nextCursor=${nextCursor}`);

    sleep(0.3);

    if (!nextCursor) {
      console.warn('[2페이지] nextCursor 없음 — 결과가 1페이지뿐이거나 hasNext=false');
      return;
    }

    // 2페이지: cursor 있음 → 알라딘 건너뜀 (SearchService.java:85 조건)
    const url2   = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(PAGINATION_KEYWORD)}&type=book&limit=10&cursor=${nextCursor}`;
    const start2 = Date.now();
    const res2   = http.get(url2, { headers: authHeader(null) });
    const page2  = Date.now() - start2;
    paginationDuration.add(page2);

    check(res2, {
      '[2페이지] 상태코드 200': (r) => r.status === 200,
    });
    if (res2.status !== 200) errorRate.add(1); else errorRate.add(0);
    console.log(`[2페이지] ${page2}ms | 1페이지 대비 ${page1 - page2}ms 단축`);

    sleep(1);
  });
}

/**
 * 시나리오 5: 로그인 사용자 — 검색 기록 저장 영향 측정
 */
function runAuthenticatedSearch(token) {
  group('로그인 사용자 검색', () => {
    const query   = BOOK_KEYWORDS[Math.floor(Math.random() * BOOK_KEYWORDS.length)];
    const url     = `${BASE_URL}/api/v1/search?query=${encodeURIComponent(query)}&type=all&limit=10`;
    const headers = authHeader(token); // 토큰 없으면 비로그인으로 동작

    const start   = Date.now();
    const res     = http.get(url, { headers });
    const elapsed = Date.now() - start;
    typeAllAuthenticatedDuration.add(elapsed);

    const isAuthed = token !== null;
    check(res, {
      [`[${isAuthed ? '로그인' : '비로그인'}] 상태코드 200`]: (r) => r.status === 200,
    });
    if (res.status !== 200) errorRate.add(1); else errorRate.add(0);
    console.log(`[${isAuthed ? '로그인' : '비로그인'}] query="${query}" | ${elapsed}ms`);

    sleep(0.5);
  });
}

// ── 결과 요약 ─────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const m  = data.metrics;
  const fmt = (key, sub) => {
    const val = m[key]?.values?.[sub];
    return val != null ? `${Math.round(val)}ms` : 'N/A';
  };
  const pct = (key) => {
    const val = m[key]?.values?.rate;
    return val != null ? `${(val * 100).toFixed(1)}%` : 'N/A';
  };

  console.log(`
╔══════════════════════════════════════════════════════════════════╗
║      [Baseline] GET /api/v1/search 성능 결과                     ║
║            ← ES 도입 전 Before 수치로 기록하세요                  ║
╠══════════════════════════════════════════════════════════════════╣
║ [Redis 캐시 미스 (첫 검색 — 알라딘 syncFromAladin 포함)]          ║
║   P50 : ${fmt('integrated_cache_miss_duration','med').padEnd(10)} P95 : ${fmt('integrated_cache_miss_duration','p(95)').padEnd(10)}    ║
║   → ES 도입 후 목표: P95 < 100ms                                  ║
╠══════════════════════════════════════════════════════════════════╣
║ [Redis 캐시 히트 (5분 내 재검색 — 알라딘 건너뜀)]                 ║
║   P50 : ${fmt('integrated_cache_hit_duration','med').padEnd(10)} P95 : ${fmt('integrated_cache_hit_duration','p(95)').padEnd(10)}    ║
║   → ES 도입 후 목표: P95 < 50ms                                   ║
╠══════════════════════════════════════════════════════════════════╣
║ [type별 응답시간 비교]                                             ║
║   type=all  P50/P95 : ${fmt('integrated_type_all_duration','med').padEnd(8)} / ${fmt('integrated_type_all_duration','p(95)').padEnd(10)} ║
║   type=book P50/P95 : ${fmt('integrated_type_book_duration','med').padEnd(8)} / ${fmt('integrated_type_book_duration','p(95)').padEnd(10)} ║
║   type=user P50/P95 : ${fmt('integrated_type_user_duration','med').padEnd(8)} / ${fmt('integrated_type_user_duration','p(95)').padEnd(10)} ║
║   (type=user가 알라딘 호출 없어 가장 빠를 것으로 예상)             ║
╠══════════════════════════════════════════════════════════════════╣
║ [cursor 페이지네이션]                                              ║
║   P50 : ${fmt('integrated_pagination_duration','med').padEnd(10)} P95 : ${fmt('integrated_pagination_duration','p(95)').padEnd(10)} ║
║   (2페이지는 알라딘 건너뜀 → 1페이지보다 빠를 것)                  ║
╠══════════════════════════════════════════════════════════════════╣
║ [오류 및 기타]                                                     ║
║   전체 오류율 : ${pct('integrated_search_error_rate').padEnd(8)}                              ║
║   책 결과 없음: ${(m['integrated_empty_book_results']?.values?.count ?? 0).toString().padEnd(4)}건   유저 결과 없음: ${(m['integrated_empty_user_results']?.values?.count ?? 0).toString().padEnd(4)}건    ║
╠══════════════════════════════════════════════════════════════════╣
║ ES 도입 후 이 파일을 다시 실행해 수치를 비교하세요.               ║
║ 저장: k6 run --out json=k6/results/integrated-before.json       ║
╚══════════════════════════════════════════════════════════════════╝
`);
  return {};
}