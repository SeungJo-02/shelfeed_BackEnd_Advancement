/**
 * 도서 검색 동시성 테스트
 * 검증 대상: BookService.upsertAndGetBooks()의 DataIntegrityViolationException 처리
 *           (동시 요청 시 같은 ISBN unique 위반 → 재조회로 복구)
 *
 * 시나리오: 50명이 동시에 DB에 없는 책을 검색 → INSERT 충돌이 발생해도 모두 정상 응답
 * 기대값: 50개 요청 모두 200 응답, DB에 해당 ISBN 책이 1건만 저장
 */
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getToken, authHeader } from './config.js';

// ── 테스트 대상 설정 ──────────────────────────────────────────
// DB에 아직 없는 책 검색어를 사용해야 INSERT 경쟁이 발생합니다.
const SEARCH_QUERY = '채식주의자';
// Aladin 외부 API 레이트리밋 때문에 VU를 10으로 제한
// (50 동시 요청 시 Aladin이 일부 요청을 거절함)
const VU_COUNT = 10;
// ─────────────────────────────────────────────────────────────

export const options = {
  tags: { testid: 'shelfeed' },
  scenarios: {
    concurrent_search: {
      executor: 'shared-iterations',
      vus: VU_COUNT,
      iterations: VU_COUNT,
      maxDuration: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<3000'],
  },
};

export default function () {
  const url = `${BASE_URL}/api/v1/books/search?query=${encodeURIComponent(SEARCH_QUERY)}&page=1&limit=10`;

  // 도서 검색은 비인증 허용 엔드포인트 — 토큰 없이 요청
  const res = http.get(url);

  // 실패 시 응답 내용 출력 (원인 파악용)
  if (res.status !== 200) {
    console.error(`VU ${__VU} 실패 → status: ${res.status}, body: ${res.body}`);
  }

  check(res, {
    '검색 성공 (200)': (r) => r.status === 200,
    '결과 있음': (r) => {
      try { return JSON.parse(r.body).data?.books?.length > 0; } catch { return false; }
    },
  });
}

export function handleSummary(data) {
  const passed = data.metrics.checks?.values?.passes ?? 0;
  const failed = data.metrics.checks?.values?.fails ?? 0;
  console.log('\n======= 동시성 검증 =======');
  console.log(`검색 성공: ${passed / 2} / ${VU_COUNT}`);
  console.log(`검색 실패: ${failed / 2} (0이어야 정상)`);
  console.log(`\nDB 확인 쿼리:`);
  console.log(`  SELECT COUNT(*) FROM books WHERE title LIKE '%${SEARCH_QUERY}%';`);
  console.log(`  → unique 위반 없이 정상 저장되었는지 확인`);
  console.log('============================\n');
  return {};
}
