/**
 * [장애 재현] 인기 감상글 조회 폭풍 — 조회수 UPDATE 행 락 경합 재현
 *
 * 시나리오 (PPT "10월 28일 21:47" 재현):
 *   1. background  : 평범한 배경 트래픽 (상세·댓글·유저·장르, 여러 리뷰에 분산)
 *                    → 처음 BASELINE 구간은 배경 트래픽만 흐른다 = "평시" 캡처 구간
 *   2. hot_storm   : BASELINE 이후 인기글 "하나"에 조회가 집중된다 = "장애" 캡처 구간
 *
 * 관찰 포인트 (Grafana):
 *   - Shelfeed 성능 대시보드      : p99 급등 (상세뿐 아니라 배경 API까지)
 *   - Spring Boot JDBC & HikariCP : Pending 급증, Max acquire 수 초대
 *   - MySQL Overview              : CPU·Threads Running 평온, Slow Queries 0
 *   - 터미널: SELECT * FROM sys.innodb_lock_waits\G
 *
 * 환경변수:
 *   BASE_URL   서버 주소            (기본: http://localhost:8080)
 *   HOT_ID     집중 공격할 리뷰 ID  (기본: 1)
 *   BG_VUS     배경 트래픽 VU       (기본: 30)
 *   STORM_VUS  인기글 집중 VU 피크  (기본: 200)
 *   BASELINE   평시 구간 길이       (기본: 2m)
 *   STORM      폭풍 구간 길이       (기본: 3m)
 *
 * 실행:
 *   k6 run --out influxdb=http://localhost:8086/k6 \
 *          --summary-export k6/results/view-storm-before.json \
 *          k6/view-storm.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const HOT_ID    = __ENV.HOT_ID    || '1';
const BG_VUS    = parseInt(__ENV.BG_VUS    || '30');
const STORM_VUS = parseInt(__ENV.STORM_VUS || '200');
const BASELINE  = __ENV.BASELINE  || '2m';
const STORM     = __ENV.STORM     || '3m';

// 배경 트래픽이 분산 조회할 리뷰 ID 풀 (핫 글 제외, DataSeeder 시딩 범위 내)
const BG_REVIEW_MIN = 2;
const BG_REVIEW_MAX = parseInt(__ENV.BG_REVIEW_MAX || '700');

// ── 커스텀 메트릭: "핫 글"과 "무관한 API"의 지연을 분리 측정 ─────────
const hotDetailDuration = new Trend('hot_detail_duration', true);   // 인기글 상세
const bgDetailDuration  = new Trend('bg_detail_duration', true);    // 다른 글 상세 (같은 테이블, 다른 행)
const bgOtherDuration   = new Trend('bg_other_duration', true);     // 다른 도메인 API (유저/장르)
const errorRate         = new Rate('error_rate');

export const options = {
  scenarios: {
    // ① 배경 트래픽 — 테스트 전 구간 내내 흐른다 (BASELINE 구간 = 평시 캡처)
    background: {
      executor: 'constant-vus',
      exec: 'background',
      vus: BG_VUS,
      duration: sumDuration(BASELINE, STORM, '30s'), // 폭풍 종료 후 30s 회복 관찰
    },
    // ② 인기글 폭풍 — BASELINE 이후 시작, 한 행에 집중
    hot_storm: {
      executor: 'ramping-vus',
      exec: 'hotStorm',
      startTime: BASELINE,
      startVUs: 0,
      stages: [
        { duration: '30s', target: STORM_VUS },  // 커뮤니티 공유 직후 유입 급증
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

// "2m" + "3m" + "30s" 를 초로 합산해 "330s" 형태로 반환
function sumDuration(...ds) {
  const toSec = d => {
    const m = String(d).match(/^(\d+)(s|m)$/);
    return m ? parseInt(m[1]) * (m[2] === 'm' ? 60 : 1) : 0;
  };
  return ds.reduce((a, d) => a + toSec(d), 0) + 's';
}

// ── ① 배경 트래픽: 여러 리뷰·유저·장르에 분산 (평범한 서비스 사용 패턴) ──
export function background() {
  const dice = Math.random();
  let res;
  if (dice < 0.4) {
    // 다른 감상글 상세 — 같은 reviews 테이블, 다른 행
    const id = BG_REVIEW_MIN + Math.floor(Math.random() * (BG_REVIEW_MAX - BG_REVIEW_MIN));
    res = http.get(`${BASE_URL}/api/v1/reviews/${id}`, { tags: { api: 'bg_detail' } });
    bgDetailDuration.add(res.timings.duration);
  } else if (dice < 0.7) {
    // 감상글 댓글 — 다른 테이블
    const id = BG_REVIEW_MIN + Math.floor(Math.random() * (BG_REVIEW_MAX - BG_REVIEW_MIN));
    res = http.get(`${BASE_URL}/api/v1/reviews/${id}/comments`, { tags: { api: 'bg_comments' } });
    bgOtherDuration.add(res.timings.duration);
  } else if (dice < 0.9) {
    // 유저 프로필 — 조회수와 완전히 무관한 도메인
    const uid = 1 + Math.floor(Math.random() * 40);
    res = http.get(`${BASE_URL}/api/v1/users/${uid}`, { tags: { api: 'bg_user' } });
    bgOtherDuration.add(res.timings.duration);
  } else {
    // 장르 목록 — 가장 가벼운 API
    res = http.get(`${BASE_URL}/api/v1/genres`, { tags: { api: 'bg_genre' } });
    bgOtherDuration.add(res.timings.duration);
  }
  check(res, { 'bg status 2xx/4xx': r => r.status < 500 }) || errorRate.add(1);
  errorRate.add(res.status >= 500);
  sleep(0.5 + Math.random());
}

// ── ② 인기글 폭풍: 단 하나의 리뷰에 조회 집중 ──────────────────────
export function hotStorm() {
  const res = http.get(`${BASE_URL}/api/v1/reviews/${HOT_ID}`, { tags: { api: 'hot_detail' } });
  hotDetailDuration.add(res.timings.duration);
  check(res, { 'hot status 2xx': r => r.status === 200 }) || errorRate.add(1);
  errorRate.add(res.status >= 500);
  sleep(0.1 + Math.random() * 0.4); // 글 하나를 여러 사람이 연달아 여는 패턴
}
