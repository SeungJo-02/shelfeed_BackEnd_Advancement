/**
 * 댓글 좋아요 동시성 테스트
 * 검증 대상: CommentRepository의 UPDATE SET likeCount = likeCount + 1 원자적 업데이트
 *
 * 시나리오: 50명의 다른 유저가 동시에 같은 댓글에 좋아요
 * 기대값: 테스트 후 DB의 likeCount = 50
 * 확인 쿼리: SELECT like_count FROM comments WHERE comment_id = TARGET_COMMENT_ID;
 */
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, getToken, authHeader } from './config.js';

// ── 테스트 대상 설정 ──────────────────────────────────────────
const TARGET_REVIEW_ID  = 1; // 댓글이 속한 리뷰 ID
const TARGET_COMMENT_ID = 1; // 좋아요 0인 댓글 ID로 변경
const VU_COUNT = 50;
// ─────────────────────────────────────────────────────────────

export const options = {
  scenarios: {
    concurrent_comment_likes: {
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
  const url = `${BASE_URL}/api/v1/reviews/${TARGET_REVIEW_ID}/comments/${TARGET_COMMENT_ID}/likes`;

  const res = http.post(url, null, { headers: authHeader(token) });

  check(res, {
    '댓글 좋아요 성공 (201)': (r) => r.status === 201,
  });
}

export function handleSummary(data) {
  const passed = data.metrics.checks?.values?.passes ?? 0;
  console.log('\n======= 동시성 검증 =======');
  console.log(`요청 성공: ${passed} / ${VU_COUNT}`);
  console.log(`\nDB 확인 쿼리:`);
  console.log(`  SELECT like_count FROM comments WHERE comment_id = ${TARGET_COMMENT_ID};`);
  console.log(`  기대값: ${VU_COUNT}`);
  console.log('============================\n');
  return {};
}
