/**
 * [장애 재현] 인기 감상글 조회 폭풍 — 조회수 UPDATE 행 락 경합 재현
 *
 * 시나리오 (블로그 "2026-08-25 21:47" 재현 · 총 10분 = 평시 2m → 램프 30s → 폭풍 6m → 회복 1m30s):
 *   1. background  : 평범한 배경 트래픽 (상세·댓글·유저·장르 + 피드·검색·로그인)
 *                    → 처음 BASELINE 구간은 배경 트래픽만 흐른다 = "평시" 캡처 구간
 *   2. hot_storm   : BASELINE 이후 인기글 "하나"에 조회가 집중된다 = "장애" 캡처 구간
 *
 * 관찰 포인트 (Grafana):
 *   - Shelfeed — 조회수 행 락 장애 : ①증상 ②막힌 곳 ③원인 ④무죄 증거 (uid shelfeed-view-lock)
 *   - Alerting → Alert rules      : 4종이 순서대로 발화 (①pending → ②acquire → ③④행 락)
 *   - 커스텀 메트릭                : hot_detail / bg_detail / feed / search / login duration
 *                                   → 조회수와 무관한 셋이 같이 올라가는지가 핵심
 *   - 터미널: SELECT * FROM sys.innodb_lock_waits\G
 *
 * 환경변수:
 *   BASE_URL   서버 주소            (기본: http://localhost:8080)
 *   TOKENS     토큰 파일 경로        (기본: ./tokens.local.json)
 *   IDS        ID 풀 파일 경로       (기본: ./ids.local.json — bash k6/setup-ids.sh 로 생성)
 *
 * ⚠ 인증이 재현 강도를 좌우한다.
 *   getReview() 는 memberUserId 가 null 이면 요청자 로드·차단 확인(×2)·좋아요 확인을
 *   전부 건너뛴다 → 락을 쥔 채 도는 쿼리가 6개에서 3개로 줄어 락 보유 시간이 절반이 된다.
 *   반드시 먼저:  k6 run k6/setup-tokens.js
 *   HOT_ID     집중 공격할 리뷰 ID  (기본: 3) — 반드시 PUBLIC·PUBLISHED 글이어야 한다.
 *              PRIVATE 글(예: 1, 2)을 고르면 UPDATE 직후 403으로 끝나 락 보유 시간이 짧아지고 성공률이 403으로 무너진다.
 *              확인: SELECT review_id FROM reviews WHERE review_visibility='PUBLIC' AND review_status='PUBLISHED' AND is_deleted=0 LIMIT 5;
 *   BG_VUS     배경 트래픽 VU       (기본: 30)
 *   STORM_VUS  인기글 집중 VU 피크  (기본: 200)
 *   BASELINE   평시 구간 길이       (기본: 2m)  — Grafana "평시" 캡처용
 *   RAMP       유입 급증 램프       (기본: 30s)
 *   STORM      폭풍 유지 길이       (기본: 6m)  — 알람 ③④가 rate[5m] + for 5m 이라 5분보다 길어야 Firing까지 간다
 *   RECOVERY   폭풍 종료 후 관찰    (기본: 1m30s) — Pending → 0, p99 복귀 캡처용
 *   총 실행 시간 = BASELINE + RAMP + STORM + RECOVERY = 10m (기본값 기준)
 *
 * 실행:
 *   bash k6/setup-ids.sh               # 먼저 (ids.local.json — PUBLIC 리뷰·실제 회원 ID)
 *   k6 run k6/setup-tokens.js          # 먼저 (tokens.local.json 생성 · 토큰 수명 1시간)
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          --summary-export k6/results/view-storm-before.json \
 *          k6/view-storm.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const HOT_ID    = __ENV.HOT_ID    || '3';   // PUBLIC 글. 1·2번은 PRIVATE라 403
const BG_VUS    = parseInt(__ENV.BG_VUS    || '30');
const STORM_VUS = parseInt(__ENV.STORM_VUS || '200');
const BASELINE  = __ENV.BASELINE  || '2m';
const RAMP      = __ENV.RAMP      || '30s';
const STORM     = __ENV.STORM     || '6m';
const RECOVERY  = __ENV.RECOVERY  || '1m30s';
const RECORD_VIEW = __ENV.RECORD_VIEW === '1';  // [실험 D] 조회 기록 API 호출 여부

// ── 인증 토큰 ────────────────────────────────────────────────────────
// 비인증으로 돌리면 상세 조회가 짧은 경로를 타서 락 보유 시간이 절반이 된다.
// (요청자 로드 · 차단 확인 ×2 · 좋아요 확인이 통째로 스킵됨)
let tokens = [];
try {
  tokens = new SharedArray('tokens', () => JSON.parse(open(__ENV.TOKENS || './tokens.local.json')));
} catch (e) {
  console.warn(`[tokens] 로드 실패: ${e.message}`);
  console.warn('[tokens] 비인증으로 진행합니다 — 락 보유 시간이 짧아져 재현 강도가 떨어집니다.');
  console.warn('[tokens] 권장: k6 run k6/setup-tokens.js 를 먼저 실행하세요.');
}
function authHeaders(vuId) {
  if (tokens.length === 0) return {};
  return { Authorization: `Bearer ${tokens[vuId % tokens.length]}` };
}

// 배경 트래픽 ID 풀 — k6/ids.local.json (bash k6/setup-ids.sh 로 생성) 이 있으면 실제 존재하는
// PUBLIC 리뷰·회원 ID만 쓴다. 없으면 범위 난수로 폴백하지만 PRIVATE 글(403)·없는 유저(404)가 섞여
// 지연·성공률 지표가 오염되니 재현 전에 반드시 생성할 것.
const BG_REVIEW_MIN = 2;
const BG_REVIEW_MAX = parseInt(__ENV.BG_REVIEW_MAX || '700');
let ids = { reviews: [], users: [] };
try {
  ids = new SharedArray('ids', () => [JSON.parse(open(__ENV.IDS || './ids.local.json'))])[0];
} catch (e) {
  console.warn(`[ids] ids.local.json 로드 실패: ${e.message} — 범위 난수로 폴백 (403/404 섞임). 권장: bash k6/setup-ids.sh`);
}
const pick = arr => arr[Math.floor(Math.random() * arr.length)];
function bgReviewId() {
  if (ids.reviews.length) { let id; do { id = pick(ids.reviews); } while (String(id) === String(HOT_ID) && ids.reviews.length > 1); return id; }
  return BG_REVIEW_MIN + Math.floor(Math.random() * (BG_REVIEW_MAX - BG_REVIEW_MIN));
}
function bgUserId() {
  return ids.users.length ? pick(ids.users) : 1 + Math.floor(Math.random() * 40);
}

// 검색 키워드 — 인덱스를 타는 평범한 질의
const SEARCH_TERMS = ['소설', '역사', '프로그래밍', '에세이', '과학', '심리', '경제'];
const LOGIN_PASSWORD = 'Perf@test1!';

// ── 커스텀 메트릭: "핫 글"과 "무관한 API"의 지연을 분리 측정 ─────────
const hotDetailDuration = new Trend('hot_detail_duration', true);   // 인기글 상세
const bgDetailDuration  = new Trend('bg_detail_duration', true);    // 다른 글 상세 (같은 테이블, 다른 행)
const bgOtherDuration   = new Trend('bg_other_duration', true);     // 다른 도메인 API (유저/장르)
const feedDuration      = new Trend('feed_duration', true);        // 피드 — 조회수와 무관
const searchDuration    = new Trend('search_duration', true);      // 검색 — 조회수와 무관
const loginDuration     = new Trend('login_duration', true);       // 로그인 — 사용자가 가장 먼저 체감
const viewRecordDuration = new Trend('view_record_duration', true); // 조회 기록 API (Redis 경로)
const errorRate         = new Rate('error_rate');

export const options = {
  scenarios: {
    // ① 배경 트래픽 — 테스트 전 구간 내내 흐른다 (BASELINE 구간 = 평시 캡처)
    background: {
      executor: 'constant-vus',
      exec: 'background',
      vus: BG_VUS,
      duration: sumDuration(BASELINE, RAMP, STORM, RECOVERY), // 램프까지 포함해야 폭풍 종료 후 RECOVERY 만큼 회복이 실제로 관측된다
    },
    // ② 인기글 폭풍 — BASELINE 이후 시작, 한 행에 집중
    hot_storm: {
      executor: 'ramping-vus',
      exec: 'hotStorm',
      startTime: BASELINE,
      startVUs: 0,
      stages: [
        { duration: RAMP, target: STORM_VUS },   // 커뮤니티 공유 직후 유입 급증
        { duration: STORM, target: STORM_VUS },  // 폭풍 유지
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    // 재현 목적이므로 통과 기준은 느슨하게 — 지표 기록이 목적
    error_rate: ['rate<0.5'],
  },
};

// "2m" + "30s" + "6m" + "1m30s" 를 초로 합산해 "600s" 형태로 반환 ("1m30s" 같은 복합 표기도 지원)
function sumDuration(...ds) {
  const toSec = d => {
    let sec = 0;
    for (const m of String(d).matchAll(/(\d+)(m|s)/g)) sec += parseInt(m[1]) * (m[2] === 'm' ? 60 : 1);
    return sec;
  };
  return ds.reduce((a, d) => a + toSec(d), 0) + 's';
}

// ── ① 배경 트래픽: 여러 리뷰·유저·장르에 분산 (평범한 서비스 사용 패턴) ──
export function background() {
  const dice = Math.random();
  const h = authHeaders(__VU);
  let res;

  if (dice < 0.25) {
    // 다른 감상글 상세 — 같은 reviews 테이블, 다른 행
    const id = bgReviewId();
    res = http.get(`${BASE_URL}/api/v1/reviews/${id}`, { headers: h, tags: { api: 'bg_detail' } });
    bgDetailDuration.add(res.timings.duration);

  } else if (dice < 0.40) {
    // 감상글 댓글 — 다른 테이블
    const id = bgReviewId();
    res = http.get(`${BASE_URL}/api/v1/reviews/${id}/comments`, { headers: h, tags: { api: 'bg_comments' } });
    bgOtherDuration.add(res.timings.duration);

  } else if (dice < 0.55) {
    // 유저 프로필 — 조회수와 완전히 무관한 도메인
    const uid = bgUserId();
    res = http.get(`${BASE_URL}/api/v1/users/${uid}`, { headers: h, tags: { api: 'bg_user' } });
    bgOtherDuration.add(res.timings.duration);

  } else if (dice < 0.75) {
    // 피드 — 인증 필요. 조회수와 무관하지만 같은 커넥션 풀을 쓴다
    res = http.get(`${BASE_URL}/api/v1/feed?limit=20`, { headers: h, tags: { api: 'bg_feed' } });
    feedDuration.add(res.timings.duration);

  } else if (dice < 0.92) {
    // 검색 — 조회수와 무관. 풀이 마르면 여기부터 체감된다
    const q = SEARCH_TERMS[Math.floor(Math.random() * SEARCH_TERMS.length)];
    res = http.get(`${BASE_URL}/api/v1/search?query=${encodeURIComponent(q)}&type=all&limit=20`,
                   { headers: h, tags: { api: 'bg_search' } });
    searchDuration.add(res.timings.duration);

  } else {
    // 로그인 — 사용자가 "로그인이 안 돼요"로 가장 먼저 체감하는 경로
    const uid = Math.floor(Math.random() * 50);
    res = http.post(`${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email: `perftest${uid}@shelfeed.test`, password: LOGIN_PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { api: 'bg_login' } });
    loginDuration.add(res.timings.duration);
  }

  check(res, { 'bg status < 500': r => r.status < 500 }) || errorRate.add(1);
  errorRate.add(res.status >= 500);
  sleep(0.5 + Math.random());
}

// ── ② 인기글 폭풍: 단 하나의 리뷰에 조회 집중 ──────────────────────
export function hotStorm() {
  // 인증 헤더가 있어야 요청자 로드·차단 확인·좋아요 확인까지 락 구간에 들어간다
  const res = http.get(`${BASE_URL}/api/v1/reviews/${HOT_ID}`,
                       { headers: authHeaders(__VU), tags: { api: 'hot_detail' } });
  hotDetailDuration.add(res.timings.duration);
  check(res, { 'hot status 2xx': r => r.status === 200 }) || errorRate.add(1);
  errorRate.add(res.status >= 500);

  // [실험 D] 본문을 3초 이상 본 시점에 화면이 보내는 조회 기록 요청.
  // RECORD_VIEW=1 일 때만 보낸다. X-Viewer-Key를 매번 다르게 주어 중복 제거에 걸리지 않게 하고
  // (쓰기 경로가 초당 수백 건을 받아내는지를 재는 게 목적), 비회원 경로로 보낸다.
  if (RECORD_VIEW) {
    const v = http.post(`${BASE_URL}/api/v1/reviews/${HOT_ID}/view`, null, {
      headers: { 'X-Viewer-Key': `${__VU}-${__ITER}` },
      tags: { api: 'view_record' },
    });
    viewRecordDuration.add(v.timings.duration);
    check(v, { 'view status 2xx': r => r.status === 204 || r.status === 200 }) || errorRate.add(1);
    errorRate.add(v.status >= 500);
  }

  sleep(0.1 + Math.random() * 0.4); // 글 하나를 여러 사람이 연달아 여는 패턴
}
