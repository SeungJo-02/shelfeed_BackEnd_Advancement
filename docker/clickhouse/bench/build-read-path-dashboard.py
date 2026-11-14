#!/usr/bin/env python3
"""읽기 경로 벤치(글 하나의 최근 1시간 조회수) 결과 JSON → Grafana 대시보드.

리뷰 피드백: "event_id 단건 조회로 OLAP를 재는 건 용도가 틀린 비교다."
그래서 서비스가 실제로 묻는 질문 — 상세 화면이 초당 3천 번 묻는 "이 글의 최근 1시간 조회수" —
을 ClickHouse 집계 테이블(view_stats)·MySQL 단건·Redis GET 세 경로로 동시 접속을 올려 가며 잰다.
숫자는 read-path-results.json(clickhouse-benchmark / mysqlslap / redis-benchmark 실측)이고
이 스크립트는 TestData(csv_content) 패널로 그리기만 한다.

    python3 docker/clickhouse/bench/build-read-path-dashboard.py
"""
import json, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[3]
R = json.load(open(ROOT / "docker/clickhouse/bench/read-path-results.json", encoding="utf-8"))
OUT = ROOT / "docker/grafana/dashboards/shelfeed-bench-read-path.json"
TD = {"type": "grafana-testdata-datasource", "uid": "testdata"}
_id = iter(range(1, 100)); Y = [0]


def place(p, w, h, x):
    p["gridPos"] = {"h": h, "w": w, "x": x, "y": Y[0]}; return p


def row(title):
    p = {"id": next(_id), "type": "row", "title": title, "collapsed": False, "panels": [],
         "gridPos": {"h": 1, "w": 24, "x": 0, "y": Y[0]}}
    Y[0] += 1; return p


def csv(c):
    return [{"refId": "A", "datasource": TD, "scenarioId": "csv_content", "csvContent": c}]


def stat(title, val, unit, color, w, x, desc="", decimals=0):
    return place({"id": next(_id), "type": "stat", "title": title, "description": desc, "datasource": TD,
                  "targets": csv(f"value\n{val}"),
                  "fieldConfig": {"defaults": {"unit": unit, "decimals": decimals,
                                               "color": {"mode": "fixed", "fixedColor": color}}, "overrides": []},
                  "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                              "colorMode": "value", "graphMode": "none", "textMode": "value",
                              "justifyMode": "center"}}, w, 5, x)


def bars(title, content, w, x, h=10, desc="", unit="ms", log=False):
    return place({"id": next(_id), "type": "barchart", "title": title, "description": desc, "datasource": TD,
                  "targets": csv(content),
                  "fieldConfig": {"defaults": {"unit": unit, "decimals": 1, "color": {"mode": "palette-classic"}},
                                  "overrides": [
                                      {"matcher": {"id": "byName", "options": "ClickHouse"},
                                       "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "red"}}]},
                                      {"matcher": {"id": "byName", "options": "MySQL"},
                                       "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}}]},
                                      {"matcher": {"id": "byName", "options": "Redis"},
                                       "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "green"}}]}]},
                  "options": {"orientation": "horizontal", "xField": "path", "showValue": "always",
                              "barWidth": 0.7, "groupWidth": 0.7,
                              "legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                              "tooltip": {"mode": "multi"}}}, w, h, x)


rows = R["rows"]
ch = {r["conc"]: r for r in rows if r["path"].startswith("ClickHouse")}
rd = {r["conc"]: r for r in rows if r["path"].startswith("Redis")}
my = {r["conc"]: r for r in rows if r["path"].startswith("MySQL")}

panels = [row("질문 — 상세 화면이 초당 3천 번 묻는 '이 글의 최근 1시간 조회수'")]
panels.append(place({"id": next(_id), "type": "text", "title": "", "transparent": True,
                     "options": {"mode": "markdown", "content":
                         f"**질문** — {R['question']}. 상세 API가 캐시 적중 시 초당 3,000건을 받으니(실험 H) "
                         "조회수를 요청마다 저장소에 물으면 그 저장소가 초당 3,000번 답해야 한다.\n\n"
                         "**세 경로** — ClickHouse는 이벤트 원본이 아니라 분 단위 집계 테이블 `view_stats`에서 "
                         "`countMerge(views)`로 60행을 합친다(OLAP가 잘하는 질문). MySQL은 `reviews.view_count` PK 조회. "
                         "Redis는 `GET view:count:{id}`.\n\n"
                         f"**방법** — {R['note']} 실행 {R['run_at']}. 스크립트: `read-path-results.json` → `build-read-path-dashboard.py`."}},
                    24, 6, 0)); Y[0] += 6

panels.append(row("동시 접속 1 — 한 번 묻는 값 자체는 ClickHouse도 빠르다"))
panels.append(stat("ClickHouse p50 · 1 conn", ch[1]["p50_ms"], "ms", "green", 6, 0, decimals=1,
                   desc="집계 테이블에서 한 글의 60분치를 합치는 데 2ms. 단건이 느린 게 아니다."))
panels.append(stat("ClickHouse p99 · 1 conn", ch[1]["p99_ms"], "ms", "green", 6, 6, decimals=1))
panels.append(stat("ClickHouse QPS · 1 conn", ch[1]["qps"], "reqps", "blue", 6, 12))
panels.append(stat("Redis GET p99 · 50 conn", rd[50]["p99_ms"], "ms", "green", 6, 18, decimals=2))
Y[0] += 5

panels.append(row("동시 접속을 올리면 — 천장은 QPS 1,400, 200 동시엔 OOM"))
qps_csv = "path,ClickHouse,MySQL,Redis\n" + \
    f"동시 50,{ch[50]['qps']},{my[50]['qps']},{rd[50]['qps']}\n" + \
    f"동시 100,{ch[100]['qps']},,\n" + \
    f"동시 200,0,,{rd[200]['qps']}\n"
panels.append(bars("초당 처리량 (QPS) · 높을수록 좋음 — ClickHouse 200 동시는 OOM으로 0", qps_csv, 12, 0, unit="reqps",
                   desc="ClickHouse는 50→100 동시에서 1,303→1,421로 이미 천장이고, 200 동시에서 컨테이너(메모리 2GB)가 OOM으로 죽었다. "
                        "Redis는 200 동시에서도 21만 QPS. MySQL PK 조회는 8천 QPS인데 이건 누적 숫자 하나라 '최근 1시간'을 못 답한다."))
p99_csv = "path,ClickHouse,Redis\n" + \
    f"동시 50,{ch[50]['p99_ms']},{rd[50]['p99_ms']}\n" + \
    f"동시 100,{ch[100]['p99_ms']},\n" + \
    f"동시 200,{ch[200]['p99_ms']},{rd[200]['p99_ms']}\n"
panels.append(bars("p99 지연 (ms) · 낮을수록 좋음", p99_csv, 12, 12, unit="ms",
                   desc="ClickHouse p99는 50 동시에서 152ms, 200 동시에서 504ms(죽기 직전). Redis는 200 동시에서도 1.4ms. "
                        "상세 API p99 목표가 1초인데 조회수 하나 읽는 데 0.5초를 쓸 수는 없다."))
Y[0] += 10

panels.append(row("그래서"))
panels.append(place({"id": next(_id), "type": "text", "title": "", "transparent": True,
                     "options": {"mode": "markdown", "content":
                         "ClickHouse가 느려서 Redis를 덧댄 게 아니다. **한 번 묻는 값은 2ms로 충분히 빠르다.** "
                         "문제는 초당 3,000번 묻는 것이고, 그 질문에 ClickHouse는 10코어를 다 쓰고도 1,400번까지만 답하다가 "
                         "200 동시에서 죽었다. 집계는 ClickHouse가 하되, **그 결과를 읽는 건 Redis가 한다** — "
                         "스케줄러가 매 분 `view_stats`에서 글별 최근 1시간 조회수를 뽑아 Redis에 넣고, "
                         "상세 API는 GET 한 번(p99 1.4ms)으로 끝낸다. ClickHouse는 분당 쿼리 1개만 받는다."}},
                    24, 5, 0)); Y[0] += 5

dash = {"uid": "shelfeed-bench-read-path", "title": "Shelfeed — 벤치 · 글 하나의 최근 1시간 조회수를 누가 답하나",
        "description": "리뷰 피드백 반영: event_id 단건 조회 대신 서비스가 실제로 묻는 질문으로 ClickHouse·MySQL·Redis를 동시 접속별로 비교.",
        "tags": ["shelfeed", "view-count", "bench"], "timezone": "browser", "schemaVersion": 39, "version": 0,
        "editable": True, "time": {"from": "now-1h", "to": "now"}, "panels": panels}
OUT.write_text(json.dumps(dash, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"생성: {OUT.relative_to(ROOT)} — 패널 {len([p for p in panels if p['type'] != 'row'])}")
