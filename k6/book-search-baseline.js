/**
 * [Baseline] GET /api/v1/books/search — BookService 성능 측정
 *
 * 목적: ES 도입 전 현재 성능을 수치로 기록한다.
 * 이 결과가 ES 도입 후 비교 기준(Before)이 된다.
 *
 * 측정 대상:
 * - 알라딘 API를 매번 직접 호출하는 구조의 응답시간
 * - 동시 요청 시 알라딘 Rate Limit 영향
 * - page 번호 기반 페이지네이션 성능
 * - 로그인/비로그인 응답 차이 (inMyLibrary 포함 여부)
 *
 * 사전 준비:
 * 1. 서버 실행 확인: http://localhost:8080/actuator/health
 * 2. (선택) 로그인 테스트 포함 시: k6 run k6/setup-users.js 로 토큰 생성
 *
 * 실행:
 * k6 run k6/book-search-baseline.js
 * k6 run -e BASE_URL=http://54.180.101.81:8080 k6/book-search-baseline.js
 *
 * 결과 저장 (나중에 ES 결과와 비교):
 * k6 run --out json=k6/results/book-search-before-es.json k6/book-search-baseline.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution'; // <--- 이 부분이 추가되었습니다.

// ── 공통 설정 ─────────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 토큰 파일이 없으면 비로그인 시나리오만 실행 (에러 발생 방지)
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
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// ── 커스텀 메트릭 ─────────────────────────────────────────────────────────
// 시나리오별로 응답시간을 분리해서 측정
const bookSearchDuration   = new Trend('book_search_duration', true);    // 단건 검색 응답시간
const concurrentDuration   = new Trend('book_search_concurrent_duration', true); // 동시 요청 응답시간
const paginationDuration   = new Trend('book_search_pagination_duration', true); // 페이지 전환 응답시간
const aladinErrorRate      = new Rate('book_search_aladin_error_rate');   // 알라딘 연동 오류율
const emptyResultCount     = new Counter('book_search_empty_results');    // 결과 없음 카운터

// ── 테스트 시나리오 ───────────────────────────────────────────────────────
export const options = {
summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {

    /**
     * 시나리오 1: 단일 사용자 반복 검색 — 기본 응답시간 Baseline
     *
     * "한 명이 여러 키워드를 순서대로 검색할 때" 응답시간 측정.
     * 알라딘 API 직접 호출 구조에서 키워드마다 ~800ms 소요 예상.
     */
    single_user_search: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 10,
      startTime: '0s',
      tags: { scenario: 'single_user' },
    },

    /**
     * 시나리오 2: 동시 사용자 동일 키워드 검색 — 알라딘 Rate Limit 확인
     *
     * 같은 키워드를 여러 명이 동시에 검색하면:
     * - 알라딘 API를 동시에 N번 호출 → Rate Limit 위험
     * - DataIntegrityViolationException 동시성 처리 발동 여부
     * VU를 10으로 제한한 이유: 50 동시에 알라딘 호출 시 일부 요청 거절됨 (기존 주석 참고)
     */
    concurrent_same_keyword: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: 10,
      startTime: '15s',  // 시나리오 1 완료 후 시작
      tags: { scenario: 'concurrent_same' },
    },

    /**
     * 시나리오 3: 다양한 키워드 검색 — 캐시 미스 반복 측정
     *
     * 매번 다른 키워드를 사용해 알라딘 API를 계속 호출.
     * "신규 키워드 검색"의 응답시간 패턴을 기록.
     */
    diverse_keywords: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      startTime: '35s',  // 시나리오 2 완료 후 시작
      tags: { scenario: 'diverse_keywords' },
    },

    /**
     * 시나리오 4: 페이지 전환 성능 — page=1 → 2 → 3 순차 로드
     *
     * 프론트에서 무한스크롤 시 page 번호를 올리며 호출하는 패턴.
     * 알라딘 API가 page마다 독립적으로 호출되는 구조 확인.
     */
    pagination: {
      executor: 'per-vu-iterations',
      vus: 3,
      iterations: 3,
      startTime: '70s',  // 시나리오 3 완료 후 시작
      tags: { scenario: 'pagination' },
    },
  },

  // ── Threshold: 현재(Before ES) 기준선 ──────────────────────────────────
  // 이 값을 기록해두고, ES 도입 후 아래 값들이 낮아지면 개선된 것.
  thresholds: {
    // 전체 HTTP 실패율 5% 미만 (알라딘 오류 포함)
    http_req_failed:                          ['rate<0.05'],
    // 단건 검색 P95: 현재 알라딘 API 포함 3000ms 이하면 통과
    // ES 도입 후 목표: P95 < 200ms
    book_search_duration:             ['p(95)<3000'],
    // 동시 검색 P95: Rate Limit 포함해도 5000ms 이하
    book_search_concurrent_duration:  ['p(95)<5000'],
    // 페이지 전환 P95: page 번호 기반 각각 알라딘 호출이므로 3000ms 이하
    book_search_pagination_duration:  ['p(95)<3000'],
    // 알라딘 연동 오류율 10% 미만 (Rate Limit 감안)
    book_search_aladin_error_rate:            ['rate<0.10'],
  },
};

// ── 테스트 키워드 목록 ────────────────────────────────────────────────────
const SAME_KEYWORD     = '채식주의자';   // 시나리오 2용 — 동시 요청 동일 키워드
const DIVERSE_KEYWORDS = [              // 시나리오 3용 — 매번 다른 키워드
  '소설', '자기계발', '역사', '과학', '철학',
  '에세이', '시집', '만화', '요리', '여행',
  '심리학', '경제', '경영', '수학', '물리',
];
const PAGINATION_QUERY = '한국 소설';   // 시나리오 4용 — 결과 많은 키워드

// ── 시나리오별 실행 함수 ──────────────────────────────────────────────────

export default function () {
  const scenario = __ENV.K6_SCENARIO_NAME || exec.scenario.name;
  const token = getToken(exec.vu.idInTest - 1);

  if (scenario === 'single_user_search') runSingleSearch(token);
  else if (scenario === 'concurrent_same_keyword') runConcurrentSearch(token);
  else if (scenario === 'diverse_keywords') runDiverseSearch(token);
  else if (scenario === 'pagination') runPaginationSearch(token);
}

/**
 * 시나리오 1: 단건 검색 — 기본 응답시간 측정
 */
function runSingleSearch(token) {
  group('단건 검색 (Baseline)', () => {
    const query = DIVERSE_KEYWORDS[Math.floor(Math.random() * DIVERSE_KEYWORDS.length)];
    const url   = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(query)}&page=1&limit=20`;

    const start = Date.now();
    const res   = http.get(url, { headers: authHeader(token) });
    const elapsed = Date.now() - start;

    bookSearchDuration.add(elapsed);

    const ok = check(res, {
      '상태코드 200':         (r) => r.status === 200,
      '응답 body 존재':       (r) => r.body && r.body.length > 0,
      '응답 구조 정상':       (r) => {
        try {
          const json = JSON.parse(r.body);
          return json.status === 200 && Array.isArray(json.data?.content);
        } catch { return false; }
      },
    });

    if (!ok) {
      aladinErrorRate.add(1);
      console.error(`[단건검색] 실패 | query="${query}" | status=${res.status} | ${elapsed}ms`);
    } else {
      aladinErrorRate.add(0);
      const count = JSON.parse(res.body).data?.content?.length ?? 0;
      if (count === 0) emptyResultCount.add(1);
      console.log(`[단건검색] query="${query}" | ${count}건 | ${elapsed}ms`);
    }

    sleep(1);
  });
}

/**
 * 시나리오 2: 동시 동일 키워드 — 알라딘 Rate Limit / 동시성 확인
 */
function runConcurrentSearch(token) {
  group('동시 동일 키워드 검색', () => {
    const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(SAME_KEYWORD)}&page=1&limit=20`;

    const start = Date.now();
    const res   = http.get(url, { headers: authHeader(token) });
    const elapsed = Date.now() - start;

    concurrentDuration.add(elapsed);

    const ok = check(res, {
      '상태코드 200':   (r) => r.status === 200,
      '결과 존재':      (r) => {
        try { return JSON.parse(r.body).data?.content?.length > 0; }
        catch { return false; }
      },
    });

    if (!ok) {
      aladinErrorRate.add(1);
      console.error(`[동시검색] VU${exec.vu.idInTest} 실패 | status=${res.status} | ${elapsed}ms`);
    } else {
      aladinErrorRate.add(0);
      console.log(`[동시검색] VU${exec.vu.idInTest} 성공 | ${elapsed}ms`);
    }
  });
}

/**
 * 시나리오 3: 다양한 키워드 — 알라딘 매번 호출 응답시간 분포 기록
 */
function runDiverseSearch(token) {
  group('다양한 키워드 검색', () => {
    const idx   = Math.floor(Math.random() * DIVERSE_KEYWORDS.length);
    const query = DIVERSE_KEYWORDS[idx];
    const url   = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(query)}&page=1&limit=20`;

    const start = Date.now();
    const res   = http.get(url, { headers: authHeader(token) });
    const elapsed = Date.now() - start;

    bookSearchDuration.add(elapsed);

    check(res, {
      '상태코드 200': (r) => r.status === 200,
    });

    if (res.status !== 200) {
      aladinErrorRate.add(1);
      console.error(`[다양키워드] query="${query}" | status=${res.status} | ${elapsed}ms`);
    } else {
      aladinErrorRate.add(0);
    }

    sleep(0.5);
  });
}

/**
 * 시나리오 4: 페이지 전환 — page=1 → 2 → 3 순차 호출
 */
function runPaginationSearch(token) {
  group('페이지 전환 성능', () => {
    for (let page = 1; page <= 3; page++) {
      const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(PAGINATION_QUERY)}&page=${page}&limit=20`;

      const start   = Date.now();
      const res     = http.get(url, { headers: authHeader(token) });
      const elapsed = Date.now() - start;

      paginationDuration.add(elapsed);

      const ok = check(res, {
        [`page=${page} 상태코드 200`]: (r) => r.status === 200,
        [`page=${page} 결과 존재`]:    (r) => {
          try { return JSON.parse(r.body).data?.content?.length > 0; }
          catch { return false; }
        },
      });

      if (!ok) aladinErrorRate.add(1); else aladinErrorRate.add(0);
      console.log(`[페이지전환] page=${page} | ${ok ? '성공' : '실패'} | ${elapsed}ms`);
      sleep(0.3);
    }
  });
}

// ── 결과 요약 ─────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const dur   = data.metrics['book_search_duration'];
  const conc  = data.metrics['book_search_concurrent_duration'];
  const page  = data.metrics['book_search_pagination_duration'];
  const err   = data.metrics['book_search_aladin_error_rate'];
  const empty = data.metrics['book_search_empty_results'];

  const fmt = (m, key) => m?.values?.[key] != null ? `${Math.round(m.values[key])}ms` : 'N/A';
  const pct = (m) => m?.values?.rate != null ? `${(m.values.rate * 100).toFixed(1)}%` : 'N/A';

  console.log(`
╔══════════════════════════════════════════════════════════════╗
║       [Baseline] GET /api/v1/books/search 성능 결과          ║
║              ← ES 도입 전 Before 수치로 기록하세요            ║
╠══════════════════════════════════════════════════════════════╣
║ [단건 / 다양한 키워드 검색 응답시간]                          ║
║   P50  : ${fmt(dur, 'med').padEnd(10)} (중간값)                      ║
║   P95  : ${fmt(dur, 'p(95)').padEnd(10)} ← ES 도입 후 목표: < 200ms    ║
║   P99  : ${fmt(dur, 'p(99)').padEnd(10)} ← ES 도입 후 목표: < 500ms    ║
║   최솟값: ${fmt(dur, 'min').padEnd(10)}                                 ║
║   최댓값: ${fmt(dur, 'max').padEnd(10)}                                 ║
╠══════════════════════════════════════════════════════════════╣
║ [동시 동일 키워드 검색 응답시간]                              ║
║   P50  : ${fmt(conc, 'med').padEnd(10)} (중간값)                      ║
║   P95  : ${fmt(conc, 'p(95)').padEnd(10)} (Rate Limit 영향 포함)      ║
║   P99  : ${fmt(conc, 'p(99)').padEnd(10)}                               ║
╠══════════════════════════════════════════════════════════════╣
║ [페이지 전환 응답시간]                                        ║
║   P50  : ${fmt(page, 'med').padEnd(10)} (중간값)                      ║
║   P95  : ${fmt(page, 'p(95)').padEnd(10)} (page마다 알라딘 호출)      ║
╠══════════════════════════════════════════════════════════════╣
║ [오류 및 기타]                                                ║
║   알라딘 오류율: ${pct(err).padEnd(8)}  결과 없음: ${empty?.values?.count ?? 0}건              ║
╠══════════════════════════════════════════════════════════════╣
║ ES 도입 후 이 파일을 다시 실행해 수치를 비교하세요.           ║
║ 저장: k6 run --out json=k6/results/book-search-before-es.json║
╚══════════════════════════════════════════════════════════════╝
`);
  return {};
}