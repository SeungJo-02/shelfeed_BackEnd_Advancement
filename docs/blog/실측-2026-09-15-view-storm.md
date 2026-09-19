# 실측 결과 — 조회수 행 락 재현 (2026-09-15 21:26:28 ~ 21:36:32)

> k6 `view-storm.js` · HOT_ID=3(PUBLIC) · BG 30 VU / STORM 200 VU · 평시 2m → 램프 30s → 폭풍 6m → 회복 1m30s
> 앱: `local,mock-aladin` 프로필, HikariCP 풀 8, DB mysql-perf(3308). 전 요청 200 (111,610건), 5xx 0건.
> Grafana 캡처 범위: 2026-09-15 21:26 → 21:37 (대시보드 "Shelfeed — 서비스 운영 대시보드")

## 평시 vs 폭풍 (Prometheus, 1분 rate)

| 지표 | 평시 (21:26:50–21:28:20 평균) | 폭풍 최대 | 폭풍 평균 | 변화 |
|---|---|---|---|---|
| 유입 (전체 RPS) | 26 req/s | 289 req/s | 267 req/s | 11배 |
| 인기글 상세 RPS | 6.5 req/s | 273 req/s | 252 req/s | 42배 |
| 감상글 상세 p99 | 55 ms | 1.25 s | 0.91 s | 22배 |
| 그 외 API p99 (피드·검색·로그인·댓글·유저) | 126 ms | 1.27 s | 0.91 s | 10배 |
| 성공률 | 100% | 100% | 100% | 변화 없음 (5xx 0) |
| HikariCP Pending | 0 | 170 | 136 | +170 |
| HikariCP Active | 0.4 | 8 (= Max) | 8 | 풀 소진 |
| Max acquire | 125 ms | 1.26 s | 1.05 s | 10배 |
| Max usage | 319 ms | 350 ms | 280 ms | 1.1배 |
| 행 락 대기 발생률 | 0.07 /s | 269 /s | 247 /s | 약 4,000배 |
| 행 락 평균 대기 | 6 ms | 29 ms | 25 ms | 4.5배 |
| 현재 행 락 대기 세션 | 1 | 8 | 7.3 | = 풀 크기 |
| MySQL Threads running | 2 | 10 | 8.8 | 5배 |
| MySQL QPS | 503 | 6,027 | 5,573 | 12배 |
| 슬로우 쿼리 | 0 | 0 | 0 | 없음 |
| 호스트 CPU | 4% | 12% | 11% | +8%p |

## k6 응답 시간 (전체 10분, ms)

| API | avg | p95 | p99 | max |
|---|---|---|---|---|
| 인기글 상세 | 486 | 752 | 927 | 1,823 |
| 다른 글 상세 | 263 | 659 | 842 | 1,293 |
| 피드 | 265 | 656 | 873 | 1,291 |
| 검색 | 283 | 701 | 896 | 1,266 |
| 로그인 | 209 | 412 | 551 | 742 |
| 유저·장르 | 265 | 672 | 875 | 1,256 |

## 발화한 알람

| 룰 | 결과 |
|---|---|
| ① HikariCPConnectionsPending (pending > 0, 3m) | Firing |
| ② HikariCPAcquireSlow (acquire > 1s, 2m) | Firing |
| ③ InnodbRowLockWaitsHigh (rate > 10/s, 5m) | Firing |
| ④ InnodbRowLockWaitTimeHigh (avg > 100ms, 5m) | 미발화 (평균 29ms) |
| ⑤ Threads running > 20 | Normal (최대 10) |
| ⑥ 슬로우 쿼리 > 0 | Normal (0) |

## sys.innodb_lock_waits (폭풍 중 캡처, 21:18 1차 실행)

```
locked_table   : `shelfeed`.`reviews`   locked_index : PRIMARY   locked_type : RECORD
waiting_query  : update reviews r1_0 set view_count=(r1_0.view_count+1) where r1_0.review_id=3
blocking_query : update reviews r1_0 set view_count=(r1_0.view_count+1) where r1_0.review_id=3
Innodb_row_lock_current_waits : 7~8      Innodb_row_lock_time_avg : 8 ms     Innodb_row_lock_time_max : 518 ms
```

## 시나리오(세션 자료)와 다른 점 — 블로그를 실측으로 바꿀 때 고칠 것

1. **대기의 자리가 다르다.** 시나리오는 행 락 평균 대기 3.9s, acquire 4.9s < usage 5.1s였다. 실측은 행 락 평균 29ms, acquire 1.26s > usage 0.35s다. 풀이 8개뿐이라 DB 안에서 같은 행을 기다리는 세션은 최대 8개(= 풀 크기)이고, 나머지 요청은 풀 앞에서 기다린다. "usage > acquire" 대신 **"현재 행 락 대기 세션 8 = 풀 크기 8, Active 8/8"**이 "커넥션 8개가 전부 같은 행 앞에 서 있다"의 증거다.
2. **Threads running은 3이 아니라 10.** 락을 기다리는 세션도 running으로 센다. DB가 한가했다는 근거는 CPU 12%와 슬로우 쿼리 0이고, QPS는 평탄이 아니라 12배로 늘었다(처리량이 늘었으니 당연). "DB가 느린 게 아니라 DB 안에서 서로를 기다린다"로 쓴다.
3. **5xx가 없다.** 로컬 커넥션 타임아웃 30s(운영 5s)인데 acquire 최대 1.26s라 어느 쪽이든 실패가 안 난다. 성공률 하락 서사는 빼거나, STORM_VUS를 500 이상으로 올린 별도 실행으로 만든다.
4. **유입 배수는 2.1배가 아니라 11배**(전체), 인기글만 보면 42배. 지연은 상세 22배, 그 외 10배. "부하 11배에 무관한 API가 10배 느려졌다"가 실측 문장이다.
5. **알람 ④는 안 울린다.** 평균 대기 29ms < 100ms. 풀이 작을수록 DB 안 대기는 짧고 풀 앞 대기가 길어진다. ①②③ Firing + ⑤⑥ Normal이 실측 화면이다.
6. 재현에서 잡은 함정(PRIVATE 글, 없는 유저 ID, 알라딘 실호출, 토큰 만료, 포트 충돌)은 `docs/조회수-장애재현-계획.md` 끝에 정리.

---

# 실측 2 — 1,200 VU (2026-09-15 23:01:46 ~ 23:12:18) · 블로그 본문에 쓸 실행

> `k6 run -e STORM_VUS=1200 -e RAMP=1m -e BG_VUS=60 …` · HOT_ID=3 · 평시 2m → 램프 1m → 폭풍 6m → 회복 1m30s
> 앱 `local,mock-aladin` · HikariCP 풀 8 · **connection-timeout 5s(운영과 동일)** · Tomcat threads.max 2000(로컬 재현용, 기본 200이면 대기열이 200에서 막힘)
> 요청 124,630건 = 200 124,345 + 500 285. Grafana 캡처 범위 23:01 → 23:13. 결과 `k6/results/view-storm-1200vu.json`

## 평시 vs 폭풍

| 지표 | 평시 (23:02:00–23:03:20 평균) | 폭풍 최대 | 폭풍 평균 | 변화 |
|---|---|---|---|---|
| 유입 (전체 RPS) | 51 req/s | 286 req/s | 264 req/s | 5.6배 |
| 인기글 상세 RPS | 13 req/s | 277 req/s | 255 req/s | 22배 |
| 감상글 상세 p99 | 85 ms | 7.09 s | 6.43 s | 84배 |
| 그 외 API p99 (피드·검색·로그인·댓글·유저) | 125 ms | 8.31 s | 6.44 s | 66배 |
| 5xx 비율 | 0 | 1.1% | 0.3% | 285건 (HikariCP 5초 타임아웃) |
| HikariCP Pending | 0 | 1,193 | 1,163 | +1,193 |
| HikariCP Active | 1.6 | 8 (= Max) | 7.9 | 풀 소진 |
| Max acquire | 376 ms | 5.01 s | 4.98 s | 타임아웃 5s에 고정 |
| Max usage | 257 ms | 5.05 s | 2.53 s | 20배 |
| Tomcat busy threads | 3 | 1,202 | 1,173 | 전부 풀 앞 대기 |
| 행 락 대기 발생률 | 0.14 /s | 274 /s | 252 /s | 약 1,900배 |
| 행 락 평균 대기 | 5 ms | 28 ms | 25 ms | 5배 |
| 현재 행 락 대기 세션 | 1 | 8 | 7.4 | = 풀 크기 |
| MySQL Threads running | 2 | 10 | 8.7 | 5배 |
| MySQL QPS | 1,013 | 5,973 | 5,512 | 6배 |
| 슬로우 쿼리 | 0 | 0 | 0 | 없음 |
| 호스트 CPU | 5% | 11% | 11% | +6%p |

## k6 응답 시간 p99 (전체 구간)

| API | p99 |
|---|---|
| 인기글 상세 | 6.19 s (max 9.13 s) |
| 다른 글 상세 | 5.48 s |
| 피드 | 5.62 s |
| 검색 | 5.60 s |
| 로그인 | 2.90 s |

## 시계열 모양 (20초 간격)

```
23:03:46  평시   RPS 59   상세 p99 0.06s   그외 0.11s   Pending 0
23:04:46  램프   RPS 274  상세 p99 4.21s   그외 3.86s   Pending 1,090   ← 두 라인이 같은 시각에 같이 꺾인다
23:05:46  폭풍   RPS 275  상세 p99 6.50s   그외 5.79s   Pending 1,155
23:08:46  폭풍   RPS 240  상세 p99 7.07s   그외 6.85s   Pending 1,184   5xx 0.9%
23:10:46  폭풍   RPS 259  상세 p99 6.48s   그외 6.55s   Pending 1,163
23:11:46  회복   RPS 59   상세 p99 0.03s   그외 0.10s   Pending 0
```

## 발화한 알람
① HikariCPConnectionsPending Firing · ② HikariCPAcquireSlow Firing · ③ InnodbRowLockWaitsHigh Firing · ④ 평균 대기 100ms 미발화(28ms) · ⑤⑥ Normal

## 폭풍 중 터미널 (23:07:07)
```
sys.innodb_lock_waits : waiting_sessions 28, locked_table `shelfeed`.`reviews`, locked_index PRIMARY, locked_type RECORD
  waiting_query  = update reviews r1_0 set view_count=(r1_0.view_count+1) where r1_0.review_id=3
  blocking_query = update reviews r1_0 set view_count=(r1_0.view_count+1) where r1_0.review_id=3
Innodb_row_lock_current_waits 8 · Innodb_row_lock_time_avg 17 ms · Innodb_row_lock_time_max 518 ms · Threads_running 9
```

## 블로그 수치 교체 안 (시나리오 → 실측 2)
- 유입 142 → 298 req/s (2.1배)  →  **51 → 286 req/s (5.6배)**, 인기글만 13 → 277 (22배)
- 상세 p99 180ms → 6.2s (34배)  →  **85ms → 7.1s (84배)**, k6 p99 6.19s
- 나머지 API p99 90ms → 3.4s  →  **125ms → 8.3s (66배)** (그림과 달리 인기글과 같은 높이 — 모두 같은 풀 앞에서 기다리므로)
- 성공률 99.98 → 91.6%  →  **5xx 0.3%(최대 1.1%), 285건**. 타임아웃 5초가 p99와 거의 같아 꼬리만 실패
- Pending 24  →  **1,193** (Tomcat 스레드를 풀어 줬기 때문. 기본 200이면 ~140에서 막힘)
- acquire 4.90s / usage 5.10s  →  **acquire 5.01s(타임아웃) / usage 5.05s**
- 행 락 평균 대기 3.91s  →  **28ms**, 대신 현재 대기 세션 8 = 풀 크기
- Threads running 3  →  **10**, QPS 평탄 → 6배 증가. DB 무죄 근거는 CPU 11%·슬로우 쿼리 0

---

# 실측 3 — 유입 2.7배 (2026-09-16 03:25:51 ~ 03:36:21) · **블로그 도입부용**

> `k6 run -e STORM_VUS=1200 -e RAMP=1m -e BG_VUS=100 …` · HOT_ID=3 · 평시 2m → 램프 1m → 폭풍 6m → 회복 1m30s
> 앱 `local,mock-aladin` · HikariCP 풀 8 · connection-timeout 5s · Tomcat threads 2000 · 요청 109,613건 = 200 94,905 + 500 14,708
> Grafana 캡처 범위 03:25 → 03:37 · 결과 `k6/results/view-storm-3x.json`
> 배경 VU를 170으로 올려 2.0배를 맞추려던 실행은 평시부터 풀이 포화(p99 2.2s, 5xx 발생)해 폐기. 풀 8로 평시가 건강한 상한이 약 100 req/s라 배수는 2.7배가 한계.

## 평시 vs 폭풍

| 지표 | 평시 (03:26:00–03:27:20 평균) | 폭풍 최대 | 폭풍 평균 | 변화 |
|---|---|---|---|---|
| 유입 (전체 RPS) | 94 req/s | 252 req/s | 200 req/s | 2.7배 |
| 인기글 상세 RPS | 24 req/s | 239 req/s | 189 req/s | 10배 |
| 감상글 상세 p99 | 67 ms | 11.2 s | 9.1 s | 169배 |
| 그 외 API p99 | 111 ms | 12.2 s | 9.5 s | 110배 |
| 피드 p99 | 57 ms | 11 s | 9.3 s | 197배 |
| 검색 p99 | 98 ms | 14 s | 9.8 s | 140배 |
| 로그인 p99 | 131 ms | 8.4 s | 5.6 s | 64배 |
| 성공률 | 100% | 최저 약 60% | 79% | 5xx 14,708건 (13.4%) |
| HikariCP Pending | 0 | 1,295 | 1,205 | +1,295 |
| HikariCP Active | 0.8 | 8 (= Max) | 7.9 | 풀 소진 |
| Max acquire | 1.36 s* | 5.18 s | 5.10 s | 타임아웃 5s에 고정 |
| Max usage | 1.42 s* | 5.07 s | 3.34 s | |
| 행 락 대기 발생률 | 0.5 /s | 234 /s | 152 /s | 약 430배 |
| 행 락 평균 대기 | 3 ms | 94 ms | 48 ms | 32배 |
| 현재 행 락 대기 세션 | 1 | 8 | 6.6 | = 풀 크기 |
| MySQL Threads running | 2 | 10 | 7.8 | 5배 |
| MySQL QPS | 1,749 | 5,245 | 3,566 | 3배 |
| 슬로우 쿼리 | 0 | 0.1 /s | 0.01 /s | 사실상 0 |
| 호스트 CPU | 5% | 14% | 12% | +9%p |

\* Micrometer `_max`는 감쇠 창이라 평시 값에 직전 실행(03:24)의 잔여가 남아 있음. 실제 평시 acquire는 수십 ms.

## 시계열 (1분 간격)
```
03:27:51  평시  RPS  98  상세 p99  0.03s  그외  0.09s  Pending     0
03:28:51  램프  RPS 262  상세 p99  3.91s  그외  3.89s  Pending 1,046   ← 같은 시각에 같이 꺾인다
03:29:51  폭풍  RPS 243  상세 p99  7.86s  그외  9.47s  Pending 1,250
03:31:51  폭풍  RPS 158  상세 p99 11.22s  그외 11.25s  Pending 1,265   ← 5xx로 처리량이 빠진다
03:33:51  폭풍  RPS 240  상세 p99  7.06s  그외  7.05s  Pending 1,263
03:35:51  회복  RPS  98  상세 p99  (잔여)   그외  (잔여)  Pending     0
```
발화 알람: ① Pending · ② Acquire · ③ RowLockWaits Firing, ④ 미발화(평균 48ms), ⑤⑥ Normal.

## 블로그 도입부 — 실측으로 다시 쓴 문단

> 평소 유입(94 req/s)의 약 2.7배인 252 req/s가 들어왔을 때 감상글 상세 p99가 평소(67ms) 대비 약 170배(11.2s)로 뛰었다.
> 같은 시각 호스트 CPU는 14%, 슬로우 쿼리는 0건이었다. DB는 한가했다.
> 병목이 생긴 곳은 커넥션 풀(Pending 1,295, Active 8/8)이었고, 커넥션을 붙잡고 있던 건 인기글 한 행의 락이었다(행 락 대기 234/s, 대기 세션 8 = 풀 크기).
> 곧이어 조회수와 무관한 피드·검색·로그인의 p99도 8~14초로 뛰었고, 5초 커넥션 타임아웃에 걸린 요청이 500으로 떨어져 성공률이 79%까지 내려갔다.
>
> · 빨간 표시는 판단 기준을 짚은 것

---

# 쿼리별 실행 시간 (performance_schema, 누적)

> `SELECT DIGEST_TEXT, COUNT_STAR, AVG_TIMER_WAIT/1e9 AS avg_ms, SUM_ROWS_EXAMINED
>  FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='shelfeed'`
> 「N+1인 줄 알았다」 절의 근거. 상세 조회가 날리는 SELECT는 전부 0.1ms 미만이고 검사한 행도 요청당 1행이다.

| 쿼리 | 실행 횟수 | 평균 | 검사한 행 | 판단 |
|---|---|---|---|---|
| SELECT reviews (상세 본문) | 754,742 | **0.063 ms** | 754,740 (요청당 1행) | N+1 아님 |
| SELECT members (요청자·작성자) | 1,442,718 | 0.058 ms | 1,437,937 | 정상 |
| SELECT review_tags (태그) | 501,561 | 0.042 ms | 0 | 정상 |
| SELECT review_likes (좋아요 여부) | 501,559 | 0.040 ms | 0 | 정상 |
| **UPDATE reviews SET view_count** | 736,232 | **17.532 ms** (max 1,003 ms) | 736,233 (1행) | **혼자 280배 느리다** |

읽기 네 종류는 전부 0.04~0.06ms이고 검사한 행도 1행이다. N+1이나 풀스캔이 아니다.
같은 테이블에 PK로 1행을 고치는 UPDATE만 평균 17.5ms, 최대 1초다. 쿼리가 무거운 게 아니라 기다린 시간이 실행 시간에 들어간 것이다.

> ※ 이 표는 MySQL 기동 이후 누적이라 여러 차례 재현 실행이 합산돼 있다. 비율(읽기 0.06ms vs 쓰기 17.5ms)을 보는 용도로 쓴다.
