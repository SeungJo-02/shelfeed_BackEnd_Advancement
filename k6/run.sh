#!/bin/bash
# 환경별 부하 테스트 실행 래퍼
#
# 사용법:
#   ./k6/run.sh <환경> <스크립트>
#
# 예시:
#   ./k6/run.sh local setup-tokens.js
#   ./k6/run.sh local ramping-test.js
#   ./k6/run.sh local constant-test.js
#   ./k6/run.sh aws   ramping-test.js
#   ./k6/run.sh aws   constant-test.js

set -e

ENV=${1:-local}
SCRIPT=${2:-ramping-test.js}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/env/${ENV}.sh"

if [ ! -f "$ENV_FILE" ]; then
  echo "❌ 환경 파일 없음: $ENV_FILE"
  echo "   k6/env/ 아래에 ${ENV}.sh 를 생성하세요"
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

mkdir -p "${SCRIPT_DIR}/results"

RESULT_NAME="${ENV}-$(basename "$SCRIPT" .js)"

echo "─────────────────────────────────────────────"
echo "  ENV        : $ENV"
echo "  SCRIPT     : $SCRIPT"
echo "  BASE_URL   : $BASE_URL"
echo "  CONSTANT_VUS: $CONSTANT_VUS"
echo "  DURATION   : $DURATION"
echo "  RESULT     : k6/results/${RESULT_NAME}.json"
echo "─────────────────────────────────────────────"

# setup-tokens.js 는 InfluxDB 출력 없이 단순 실행
if [ "$SCRIPT" = "setup-tokens.js" ]; then
  k6 run \
    -e BASE_URL="$BASE_URL" \
    -e USER_COUNT="$USER_COUNT" \
    "${SCRIPT_DIR}/${SCRIPT}"
  exit 0
fi

k6 run \
  -e BASE_URL="$BASE_URL" \
  -e CONSTANT_VUS="$CONSTANT_VUS" \
  -e DURATION="$DURATION" \
  -e SKIP_PROFILE_CHECK="$SKIP_PROFILE_CHECK" \
  --out "influxdb=${INFLUXDB_URL}" \
  --summary-export "${SCRIPT_DIR}/results/${RESULT_NAME}.json" \
  "${SCRIPT_DIR}/${SCRIPT}"
