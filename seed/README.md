# seed — 정보나루 공공데이터 기반 대규모 시드 파이프라인

인덱스·캐시·커넥션 풀 고도화(이슈 #71)를 실측하려면 로컬 DB에 **실제 도서 수만 권**과 **회원·팔로우·감상 수십만 건**이
필요하다. 이 디렉터리는 두 단계로 그것을 만든다.

| 단계 | 도구 | 산출물 |
|---|---|---|
| 1. 도서 수집 | `collect_data4library.py` (Python 3, 표준 라이브러리만) | `seed/data/books.csv` |
| 2. 규모 시딩 | Spring 프로파일 `scale-seed` (`ScaleSeeder`) | 성능 테스트 DB(3308)의 books·members·follows·library_books·reviews·comments·review_likes·feeds·notifications |

데이터 출처: **도서관 정보나루(국립중앙도서관) 인기대출도서 API** (`loanItemSrch`, 선택적으로 `srchDtlList`).
공공데이터이므로 수집·저장이 가능하다. 알라딘·YES24·카카오 검색 API는 약관상 대량 축적이 금지되어 쓰지 않는다.

## 1. 인증키 발급

1. https://www.data4library.kr 회원가입
2. 상단 **데이터 활용 → Open API → 인증키 신청** (활용 목적을 적는다)
3. 발급된 키를 환경변수로 둔다: `export DATA4LIBRARY_KEY=...`

**한도: 인증키 하나당 하루 500건.** 그 이상 필요하면 정보나루에 **서버 IP 등록**을 신청해야 한다.
수집기의 기본 상한 `--max-calls 450`은 이 한도의 여유분이다.

## 2. 도서 수집

```bash
python3 seed/collect_data4library.py --resume          # 기본값: kdc 0-9, 2014~2026, page 300, max-calls 450
```

- 순회 순서는 **최신 연도부터(2026→2014), 연도 안에서 KDC 0~9, 페이지 오름차순**이다. 하루 예산으로 서로 다른 책을
  최대한 빨리 모으기 위한 순서이며, 상태 파일 `seed/data/.collect_state.json`이 이 순서를 그대로 보존한다.
- 종료 시 요약 한 줄이 찍힌다: 오늘 사용한 호출 수 · 수집 행 수 · **다음 재개 지점**(kdc, 기간, 페이지).
- `--details N`: 대출 상위 N권의 소개글(`description`)을 `srchDtlList`로 채운다. 1권당 1호출이라 예산을 따로 계산하라.
- `--windows half-year`: 연 단위 대신 반기 단위로 순회한다. 호출은 2배지만 상위 5,000권 컷이 두 번 적용되어 커버리지가 는다.
- 출력 CSV가 이미 있으면 `--resume` 없이는 실행을 거부한다(덮어쓰기 사고 방지). 버리고 처음부터 하려면 `--force`.
- 한도 계산은 **실패한 시도(429·5xx 재시도)도 포함**하며 날짜별로 기록된다(`calls_by_date`). 요약에 오늘 사용/남은 호출이 찍힌다.

### 호출 예산과 일정

전체 순회는 `10 KDC × 13년 × 최대 17페이지(300건)` ≈ **최대 2,200 호출**이다. 실제로는 연도·분류마다 결과가 5,000권에
못 미쳐 훨씬 적게 끝난다. 하루 450건이면 **대략 3~5일**이 걸린다.

```bash
# 1일차
python3 seed/collect_data4library.py --resume
# [summary] 오늘 사용한 호출=450 (누적 450, 하루 한도 500) · 수집 행=18,240 · 다음 재개 지점: kdc=3 2024-01-01~2024-12-31 page=2
# 2일차 이후 — 같은 명령을 매일 한 번
python3 seed/collect_data4library.py --resume
```

**현실적인 기대치: 전체 순회 후 서로 다른 ISBN은 대략 3만~6만 권.** 인기대출 목록은 연도 간 겹침이 심하다
(스테디셀러가 매년 상위에 온다). 더 넓히려면 `--windows half-year`나 KDC 세부 분류(`dtl_kdc`)로 창을 쪼개야 한다.

### CSV 형식

`isbn13,title,author,publisher,published_year,class_no,category,genre,cover_url,loan_count,description`

- `category`는 `class_no`(KDC)를 `seed/kdc_category.csv`로 매핑한 **YES24식 문자열**(`국내도서-소설/시/희곡`)이다.
  `genres.category_pattern`(R__genres_seed.sql)과 호환되어 장르별 조회 API가 동작한다. KDC로 도출할 수 없는
  **장르소설**은 시드 데이터에서 비어 있다(만화/라이트노벨은 KDC 657 만화만 매핑). 반대로 `국내도서-총류`, `국내도서-IT 모바일`,
  `국내도서-국어 외국어 사전`은 실제 KDC 분류라 매핑은 하지만 어떤 장르 패턴에도 걸리지 않는다(장르 탐색에는 안 보이고 검색에는 보인다).
  `KdcCategoryGenreCoverageTest`가 이 세 개만 예외로 허용한다.
- `cover_url`은 정보나루가 주는 값 그대로인데 **image.aladin.co.kr**을 가리킨다. 알라딘 API 종료(2026-10-30) 이후
  깨질 수 있으니 저장은 하되 의존하지 마라.
- `seed/data/`는 `.gitignore` 대상이다(용량·재배포 방지). 저장소에는 fixture로 만든 100행 `books.sample.csv`만 있다.

### 수집기 테스트

```bash
python3 -m unittest discover -s seed/tests -v    # fixture HTTP 서버로 페이징·resume·429 백오프·중복 병합 검증
```

## 3. 규모 시딩

성능 테스트 DB(3308)와 Redis가 떠 있어야 한다: `docker compose up -d mysql-perf redis elasticsearch`

> **제목·저자 검색까지 확인하려면 Elasticsearch가 필요하다.** `scale-seed`는 기동 시 `BookIndexInitializer`로 시드 도서를 ES에 색인한다.
> ES가 꺼져 있으면 색인만 건너뛰고(WARN 로그) 시딩은 정상 진행되며, 검색은 DB LIKE 폴백으로 동작한다. 장르 탐색·피드·서재는 MySQL만으로 동작한다.
> `app.search.es-enabled=false`를 주면 ES를 아예 건드리지 않는다.
>
> **시딩 후 자동 종료**: 기본은 시딩이 끝나도 서버로 계속 떠 있다. 스크립트나 CI에서는 `--app.seed.exit-after=true`를 주면 시딩 직후 종료 코드 0으로 내려간다.

```bash
# 기본 규모: 회원 5,000 · 감상 200,000 · 허브 50명 × 팔로워 3,000 · 서재 60권/회원
./gradlew bootRun --args='--spring.profiles.active=mock-catalog,scale-seed'

# 작은 규모로 먼저 확인
./gradlew bootRun --args='--spring.profiles.active=mock-catalog,scale-seed --app.seed.members=200 --app.seed.reviews=2000 --app.seed.hub-followers=150'
```

`[ScaleSeeder] 완료: N초` 로그가 찍히면 Ctrl+C로 앱을 내린다. 파라미터는 `application-scale-seed.yml`의 `app.seed.*`이며
CLI `--app.seed.<이름>=값`으로 덮어쓴다.

| 파라미터 | 기본 | 의미 |
|---|---|---|
| `books-csv` | `seed/data/books.csv` | 없으면 `books.sample.csv`(100권)로 대체 |
| `members` / `reviews` | 5000 / 200000 | 목표 건수. `members`는 관리자 행을 포함한 총 회원 수라 합성 회원은 N-1명이다. 감상은 READING/FINISHED 서재 행(약 75%)에만 붙으므로 후보가 목표보다 커야 한다 |
| `follows-per-member` | 30 | 일반 회원 평균 팔로잉(0~2배 균등) |
| `hub-ratio` / `hub-followers` | 0.01 / 3000 | 상위 1% 회원이 허브가 되어 각 3,000명의 팔로워를 받는다 |
| `library-per-member` | 60 | 서재 도서 수. 대출 상위 도서에 편향(Zipf 근사)되어 인기 도서에 감상이 몰린다 |
| `comments-per-review` / `likes-per-review` | 2 / 5 | 공개·게시 감상 기준 평균 |
| `feeds-per-member` / `notifications-per-member` | 50 / 20 | 팔로위의 최근 공개 감상 / 좋아요·댓글·팔로우 알림 |
| `batch-size` / `seed` | 2000 / 42 | JDBC 배치 크기 / 난수 시드(같은 시드면 같은 데이터) |

동작 규칙:
- **멱등·복구**: 도서는 `isbn13` 기준 upsert. 합성 데이터는 `members`와 `reviews`가 모두 목표치 이상이면 통째로 건너뛴다.
  아니면 단계(팔로우·서재·감상·댓글·좋아요·피드·알림)마다 자기 테이블 건수를 보고 부족한 단계만 실행하므로,
  중간에 죽은 실행도 같은 명령으로 재개된다. 유니크 키 + `INSERT IGNORE`라 중복은 생기지 않는다.
- **안전장치**: `prod` 프로파일이거나 DB 호스트가 localhost/127.0.0.1/mysql/mysql-perf가 아니면 즉시 중단한다.
- **시각 정합**: 댓글·좋아요·알림은 부모 감상/댓글보다 0~30일 뒤에 생성된다. 피드와 FOLLOWING_REVIEW 알림은 감상 시각과 같다.
- **카운터 정합**: `follower_count`·`following_count`·`review_count`·`like_count`·`comment_count`를 마지막에 `COUNT(*)`로
  재계산해 실제 행 수와 정확히 맞춘다.
- 회원 번호는 `member_user_id_seq`를 앱과 같은 방식으로 한 번에 올려 확보한다. 비밀번호는 전부 `Seed1234!`,
  이메일은 `seed{회원번호}@seed.shelfeed.test`.
- 모든 INSERT는 JdbcTemplate batch이며 JDBC URL의 `rewriteBatchedStatements=true`가 필수다(없으면 수십 배 느리다).

### 소요 시간

로컬(Docker MySQL 8.0, Apple Silicon) 실측: 회원 2,000 · 감상 60,000 · 서재 72,000 · 팔로우 79,000 · 댓글 100,000 ·
좋아요 250,000 · 피드 90,000 · 알림 36,000 (약 70만 행)이 **12초**(앱 기동 제외), 약 5.8만 행/초였다.
기본 규모(회원 5,000 · 감상 200,000, 총 약 230만 행)는 **1분 안팎**을 예상한다. 좋아요·댓글이 행 수의 대부분을 차지하므로
빨리 끝내려면 `likes-per-review`·`comments-per-review`를 줄여라.
기본 규모는 중복 검사용 집합이 수백만 쌍이라 힙이 필요하다: `JAVA_TOOL_OPTIONS=-Xmx2g ./gradlew bootRun ...`

## 4. 검증

- Java: `KdcCategoryGenreCoverageTest` — KDC 매핑이 장르 패턴 14개(장르소설 제외)에 걸리는지
- 시딩 후 카운터 정합은 아래로 확인한다(0이어야 한다).

```sql
SELECT COUNT(*) FROM members m WHERE follower_count <> (SELECT COUNT(*) FROM follows WHERE followee_id = m.member_id);
SELECT COUNT(*) FROM reviews r WHERE like_count <> (SELECT COUNT(*) FROM review_likes WHERE review_id = r.review_id);
```
