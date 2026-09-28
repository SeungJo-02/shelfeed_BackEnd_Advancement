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

## DB 마이그레이션 — Flyway

스키마는 **Flyway**가 관리하고 Hibernate는 검증만 한다(모든 프로파일 `ddl-auto=validate`).
앱을 띄우면 `src/main/resources/db/migration`의 스크립트가 순서대로 적용된다. 별도 명령은 없다.

| 파일 | 역할 |
|------|------|
| `V1__baseline.sql` | YES24 전환(#68) 시점 엔티티의 전체 스키마 |
| `V2__rename_aladin_item_id.sql` | 옛 덤프의 `books.aladin_item_id` → `external_item_id` 보정(멱등) |
| `V3__ensure_member_user_id_seq.sql` | #57 이전 덤프에 없는 `member_user_id_seq`를 보충(`CREATE TABLE IF NOT EXISTS`) |
| `R__genres_seed.sql` | 장르 15개 시드. 내용이 바뀌면 다시 실행된다(`ON DUPLICATE KEY UPDATE`) |

- **새 DB**: V1 → V2·V3(할 일 없음) → R 시드 순으로 적용된다.
- **기존 로컬 DB**(`flyway_schema_history`가 없는 DB, 예전 덤프 복원 포함): `baseline-on-migrate`로 V1이 이미 적용된 것으로 간주하고 V2부터 적용한다.
  `aladin_item_id`만 있으면 RENAME, `ddl-auto=update`가 먼저 돌아 두 컬럼이 공존하면 값을 옮기고 옛 컬럼을 DROP한다.
  PR #57(회원 번호 DB 시퀀스) 이후 스키마의 DB라면 수동 ALTER는 필요 없다. 그보다 오래된 덤프는 `member_user_id_seq`가 없는데 V3가 만들어 준다.
  그래도 기동 시 Hibernate validate가 실패하면(V1 이후 다른 드리프트) 스키마를 비우고 V1부터 다시 만들거나, 차이를 메우는 `V{n}__*.sql`을 추가한다.
- **새 변경**은 `V{n}__snake_case.sql`을 추가한다(V1은 고치지 않는다). 시드처럼 반복 실행이 필요한 것은 `R__이름.sql`.
- 통합 테스트(`./gradlew test`)는 Testcontainers MySQL에 같은 마이그레이션을 적용하므로 스크립트 오류가 테스트에서 잡힌다.
- 주의: V1은 `utf8mb4_0900_ai_ci`를 고정하지만 baseline된 기존 DB는 자기 collation을 유지한다(validate는 collation을 검사하지 않는다). 정렬 순서와 unique 키의 대소문자 구분이 새 DB와 다를 수 있다.
- 주의: 장르 시드(15행)는 perf 프로파일에도 들어간다(영향 미미). 다만 #68 이전 덤프로 복원한 perf DB는 기동 시 V2가 `books` 전체 UPDATE와 DROP COLUMN 재구축을 쿼리 타임아웃 밖에서 실행한다.

---

## Step 1 — Spring Boot 성능 테스트 모드로 실행

```bash
JAVA_TOOL_OPTIONS="-Xmx1g -Xms512m" \
  ./gradlew bootRun --args='--spring.profiles.active=mock-catalog,perf-seed'
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
