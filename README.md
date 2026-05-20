# BackEnd_Project — 부하 테스트 가이드

## 사전 준비: k6 설치

```bash
brew install k6
k6 version
```

---

## Step 0 — 인프라 기동

```bash
cd /Users/seungjo/Desktop/BackEnd_Project
docker compose up -d
docker compose ps
```

---

## Step 1 — Spring Boot 성능 테스트 모드로 실행

```bash
JAVA_TOOL_OPTIONS="-Xmx1g -Xms512m" \
  ./gradlew bootRun --args='--spring.profiles.active=mock-aladin,perf-seed'
```

로그에서 아래 메시지가 나올 때까지 대기:

```
[PerfBookSeeder] 시딩 완료: 1000000건
```

---

## Step 2 — 테스트 유저 500명 + JWT 토큰 생성

```bash
k6 run k6/setup-tokens.js
```

성공 시: `✅ 500개 토큰 → k6/tokens.local.json 저장 완료`

---

## Step 3-A — Ramping 테스트 (Breaking Point 탐색)

VU를 0 → 700으로 단계적으로 올려 P95가 급등하는 지점을 찾습니다.

```bash
mkdir -p k6/results

k6 run \
  --out influxdb=http://localhost:8086/k6 \
  --summary-export k6/results/ramping-result.json \
  k6/ramping-test.js
```

Grafana에서 `search_auth_duration` P95가 급등하기 시작하는 VU 구간을 확인합니다.
그 아래 값을 `CONSTANT_VUS`로 사용합니다.

---

## Step 3-B — Constant 테스트 (AS-IS / TO-BE 비교)

Breaking Point 아래 VU 값을 `-e CONSTANT_VUS=<값>`에 설정합니다.

```bash
# AS-IS (ES 도입 전)
k6 run \
  --out influxdb=http://localhost:8086/k6 \
  -e CONSTANT_VUS=200 \
  -e DURATION=3m \
  --summary-export k6/results/before-es.json \
  k6/constant-test.js

# TO-BE (ES 도입 후 — 동일 조건으로 재실행)
k6 run \
  --out influxdb=http://localhost:8086/k6 \
  -e CONSTANT_VUS=200 \
  -e DURATION=3m \
  --summary-export k6/results/after-es.json \
  k6/constant-test.js
```

---

## 모니터링 주소

| 서비스 | 주소 | 계정 |
|--------|------|------|
| **Grafana** (메인 대시보드) | http://localhost:3001 | admin / admin |
| **Prometheus** (메트릭 원본) | http://localhost:9090 | |
| **InfluxDB** (k6 결과) | http://localhost:8086 | DB: `k6` |
| **Tempo** (분산 트레이싱) | http://localhost:3200 | |
| **Loki** (로그) | http://localhost:3100 | |
| **Node Exporter** (시스템 메트릭) | http://localhost:9100 | |
| **Spring Actuator** (앱 메트릭) | http://localhost:8080/actuator/prometheus | |

---

## Grafana에서 k6 결과 보는 법

1. http://localhost:3001 접속
2. **Connections → Data Sources → Add** → `InfluxDB` 선택
   - URL: `http://influxdb:8086`
   - Database: `k6`
3. **Dashboards → Import** → ID `2587` 입력 (공식 k6 대시보드)

---

## DB 분리 구조 (운영 vs 부하 테스트)

| 환경 | 일반 기능 테스트 | 부하 테스트 |
|------|-----------------|-------------|
| **로컬** | `mysql:3307` | `mysql-perf:3308` |
| **AWS** | 운영 RDS | **별도 perf-RDS** (반드시 분리) |

### 프로파일 매트릭스

| 시나리오 | 프로파일 | DB |
|---------|---------|-----|
| 로컬 기능 테스트 | `local` | 3307 |
| 로컬 부하 테스트 | `local,perf-seed` | 3308 |
| AWS 운영 | `prod` | 운영 RDS (`DB_URL`) |
| AWS 부하 테스트 | `prod,prod-perf,perf-seed` | perf-RDS (`PERF_DB_URL`) |

### AWS 부하 테스트 서버 기동

```bash
# 부하 테스트용 EC2 (또는 운영 EC2 별도 인스턴스)
SPRING_PROFILES_ACTIVE=prod,prod-perf,perf-seed \
PERF_DB_URL=jdbc:mysql://<perf-rds>:3306/shelfeed \
PERF_DB_USERNAME=shelfeed \
PERF_DB_PASSWORD=*** \
docker compose -f docker-compose.prod.yml up -d
```

> ⚠️ `prod-perf` 프로파일은 datasource를 `PERF_DB_URL`로 덮어씁니다. 운영 RDS와 다른 인스턴스를 가리키도록 반드시 환경변수를 분리하세요.
