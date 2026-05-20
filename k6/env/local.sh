# 로컬 환경 (docker compose + bootRun)
export BASE_URL="http://localhost:8080"
export CONSTANT_VUS=50
export DURATION=3m
export USER_COUNT=50
export INFLUXDB_URL="http://localhost:8086/k6"

# 로컬은 mock-aladin,perf-seed 프로파일 체크 필요
export SKIP_PROFILE_CHECK=0
