// k6 테스트 공통 설정
//
// 사전 준비:
//   1. k6 run k6/setup-users.js  → 테스트 유저 50명 생성
//   2. 출력된 토큰 배열을 k6/tokens.local.json 에 저장 (예: ["eyJ...", "eyJ..."])
//   3. tokens.local.json 은 .gitignore 에 포함되어 있으므로 커밋되지 않습니다.
//
// 실행 예시:
//   k6 run -e BASE_URL=http://54.180.101.81:8080 k6/review-like.js

import { SharedArray } from 'k6/data';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// tokens.local.json 이 없으면 k6가 명확한 오류를 출력합니다.
// setup-users.js 를 먼저 실행해 파일을 생성하세요.
const tokens = new SharedArray('tokens', function () {
  return JSON.parse(open('./tokens.local.json'));
});

export function getToken(vuIndex) {
  if (tokens.length === 0) {
    throw new Error('tokens.local.json 이 비어있습니다. setup-users.js 를 먼저 실행하세요.');
  }
  return tokens[vuIndex % tokens.length];
}

export function authHeader(token) {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}
