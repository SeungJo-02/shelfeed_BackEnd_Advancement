#!/usr/bin/env bash
# 배경 트래픽이 실제로 존재하는 ID만 치도록 mysql-perf에서 ID 풀을 뽑아 k6/ids.local.json 에 저장한다.
#   - reviews : PUBLIC · PUBLISHED · 삭제 안 됨 (PRIVATE 글은 403이라 지연/성공률 지표를 오염시킨다)
#   - users   : 존재하는 member_user_id (없는 ID는 404)
# 사용: bash k6/setup-ids.sh   (mysql-perf 컨테이너가 떠 있어야 한다)
set -euo pipefail
C=${MYSQL_CONTAINER:-backend_project-mysql-perf-1}
Q() { docker exec "$C" mysql -uroot -proot1234 shelfeed -N -e "$1" 2>/dev/null | tr '\n' ',' | sed 's/,$//'; }
REVIEWS=$(Q "SELECT review_id FROM reviews WHERE review_visibility='PUBLIC' AND review_status='PUBLISHED' AND is_deleted=0 ORDER BY review_id")
USERS=$(Q "SELECT member_user_id FROM members ORDER BY member_user_id")
printf '{ "reviews": [%s], "users": [%s] }\n' "$REVIEWS" "$USERS" > k6/ids.local.json
echo "✅ k6/ids.local.json — reviews $(echo "$REVIEWS" | tr ',' '\n' | wc -l | tr -d ' ')개, users $(echo "$USERS" | tr ',' '\n' | wc -l | tr -d ' ')개"
