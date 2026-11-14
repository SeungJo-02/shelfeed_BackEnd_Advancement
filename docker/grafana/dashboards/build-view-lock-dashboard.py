#!/usr/bin/env python3
"""Shelfeed 조회수 행 락 대시보드 생성기.

행 순서 = 블로그 절 순서다. 패널을 손으로 JSON에 쓰지 않는 이유는 두 가지다.

1. 임계값을 손으로 복사하지 않기 위해서. 1s·10/s·100ms·20 같은 숫자는
   `provisioning/alerting/view-lock-rules.yaml`에만 존재하고, 이 스크립트가 그 파일을
   파싱해 대시보드 점선에 넣는다. 알람 화면의 숫자와 그림의 숫자가 어긋날 수 없다.
2. 블로그 절이 바뀌면 행 순서도 바뀌는데, 27개 패널의 gridPos를 손으로 다시 계산하는 건
   사람이 할 일이 아니다. 여기서는 행에 패널을 순서대로 넣으면 y좌표가 자동으로 잡힌다.

    python3 docker/grafana/dashboards/build-view-lock-dashboard.py
"""

import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[3]
RULES = ROOT / "docker/grafana/provisioning/alerting/view-lock-rules.yaml"
OUT = ROOT / "docker/grafana/dashboards/shelfeed-view-lock.json"
OUT_ALERTS = ROOT / "docker/grafana/dashboards/shelfeed-alerts.json"
OUT_EXP = {"a": ROOT / "docker/grafana/dashboards/shelfeed-exp-a.json",
           "b": ROOT / "docker/grafana/dashboards/shelfeed-exp-b.json",
           "c": ROOT / "docker/grafana/dashboards/shelfeed-exp-c.json",
           "e": ROOT / "docker/grafana/dashboards/shelfeed-exp-e.json",
           "d": ROOT / "docker/grafana/dashboards/shelfeed-exp-d.json",
           "g": ROOT / "docker/grafana/dashboards/shelfeed-exp-g.json",
           "h": ROOT / "docker/grafana/dashboards/shelfeed-exp-h.json"}

# 알림 패널이 보여 줄 폴더. Grafana가 관리하는 룰만 나오게 묶는다.
# docker/prometheus/rules/view-lock.yml 은 같은 룰의 Prometheus 문법판(참조용)인데
# Prometheus가 그걸 실제로 평가해서 InnodbRowLockWaitsHigh 같은 이름이 패널에 섞여 나온다.
# 사건 당시 화면은 "⓪ 하나만 등록돼 있었다"가 요점이라 그 중복이 보이면 그림이 거짓말이 된다.
ALERT_FOLDER = {"uid": "afm9jdtz6czcwe", "title": "Shelfeed"}

PROM = {"type": "prometheus", "uid": "prometheus"}
INFLUX = {"type": "influxdb", "uid": "influxdb-k6"}

HOT_URI = "/api/v1/reviews/{reviewId}"
ALL_URI = '/api/v1/.*'


# ---------------------------------------------------------------- 임계값 단일 소스

def load_thresholds() -> dict[str, float]:
    """알람 룰 파일에서 uid -> 임계값을 뽑는다. 대시보드 점선의 유일한 출처."""
    text = RULES.read_text(encoding="utf-8")
    doc = json.loads(text[text.index("{"):])
    out: dict[str, float] = {}
    for group in doc["groups"]:
        for rule in group["rules"]:
            for node in rule["data"]:
                if node["refId"] != rule["condition"]:
                    continue
                for cond in node["model"].get("conditions", []):
                    params = cond.get("evaluator", {}).get("params") or []
                    if params:
                        out[rule["uid"]] = params[0]
    missing = {"shelfeed-p99", "shelfeed-pending", "shelfeed-acquire",
               "shelfeed-rowlock-rate", "shelfeed-rowlock-time",
               "shelfeed-threads-running"} - out.keys()
    if missing:
        raise SystemExit(f"알람 룰에서 임계값을 못 찾음: {sorted(missing)}")
    return out


TH = load_thresholds()

# 알람에는 없지만 설정에서 오는 값. 여기도 손으로 쓰지 않고 application-local.yml에서 읽는다.
_local = (ROOT / "src/main/resources/application-local.yml").read_text(encoding="utf-8")
POOL_MAX = int(re.search(r"maximum-pool-size:\s*(\d+)", _local).group(1))
CONN_TIMEOUT_S = int(re.search(r"connection-timeout:\s*(\d+)", _local).group(1)) / 1000


# ---------------------------------------------------------------- 패널 빌더

_ids = iter(range(100, 400))


def _legend(calcs):
    return {"displayMode": "table", "placement": "bottom",
            "showLegend": True, "calcs": calcs}


def _thresholds(steps):
    return {"mode": "absolute", "steps": steps}


def timeseries(title, targets, *, unit=None, calcs=("max", "mean", "lastNotNull"),
               threshold=None, threshold_label=None, desc="", w=12, h=9,
               fills=None, maxval=None, minval=0):
    """점선 기준선이 붙는 시계열.

    threshold 는 숫자 하나거나 (값, 색) 튜플의 리스트다. 리스트를 받는 이유는
    acquire 패널 때문이다 — 거기엔 알람 ② 임계(1s)와 커넥션 타임아웃(5s) 두 선이
    같이 있어야 한다. 곡선이 평평해지는 지점은 타임아웃 쪽이고, 그 위로 넘어간
    요청은 지연이 아니라 5xx로 빠졌다는 게 사용자 영향 절과 이어지는 다리다.
    """
    steps = [{"color": "green", "value": None}]
    custom = {
        "drawStyle": "line", "lineWidth": 2, "fillOpacity": 12,
        "showPoints": "never", "spanNulls": False,
        "thresholdsStyle": {"mode": "off"},
    }
    if threshold is not None:
        refs = threshold if isinstance(threshold, list) else [(threshold, "red")]
        for value, color in sorted(refs, key=lambda r: r[0]):
            steps.append({"color": color, "value": value})
        custom["thresholdsStyle"] = {"mode": "dashed"}
    field = {
        "defaults": {
            "custom": custom,
            "thresholds": _thresholds(steps),
            "color": {"mode": "palette-classic"},
        },
        "overrides": fills or [],
    }
    if unit:
        field["defaults"]["unit"] = unit
    if maxval is not None:
        field["defaults"]["max"] = maxval
    if minval is not None:
        field["defaults"]["min"] = minval
    if threshold_label:
        desc = (desc + " · " if desc else "") + threshold_label
    return {
        "id": next(_ids), "type": "timeseries", "title": title,
        "description": desc, "datasource": targets[0].get("datasource", PROM),
        "targets": targets, "fieldConfig": field,
        "options": {"legend": _legend(list(calcs)),
                    "tooltip": {"mode": "multi", "sort": "desc"}},
        "_w": w, "_h": h,
    }


def stat(title, expr, *, unit=None, calc="max", desc="", w=5, h=5,
         steps=None, decimals=None, datasource=None, instant=False):
    """스파크라인이 달린 큰 숫자 타일. 히어로 행 전용."""
    return {
        "id": next(_ids), "type": "stat", "title": title, "description": desc,
        "datasource": datasource or PROM,
        "targets": [{"refId": "A", "expr": expr, "datasource": datasource or PROM,
                     "legendFormat": title,
                     "instant": instant, "range": not instant}],
        "fieldConfig": {
            "defaults": {
                **({"unit": unit} if unit else {}),
                **({"decimals": decimals} if decimals is not None else {}),
                "color": {"mode": "thresholds"},
                "thresholds": _thresholds(steps or [{"color": "green", "value": None}]),
            },
            "overrides": [],
        },
        "options": {
            "reduceOptions": {"calcs": [calc], "fields": "", "values": False},
            "graphMode": "none" if instant else "area", "colorMode": "value",
            "textMode": "auto", "justifyMode": "auto",
            "orientation": "auto",
        },
        "_w": w, "_h": h,
    }


def text_note(markdown, *, w=24, h=4):
    return {
        "id": next(_ids), "type": "text", "title": "",
        "options": {"mode": "markdown", "content": markdown},
        "transparent": True, "_w": w, "_h": h,
    }


def prom(expr, legend, ref="A"):
    return {"refId": ref, "expr": expr, "legendFormat": legend,
            "datasource": PROM, "editorMode": "code", "range": True}


def influx(query, ref="A", alias=None):
    t = {"refId": ref, "query": query, "rawQuery": True,
         "resultFormat": "time_series", "datasource": INFLUX}
    if alias:
        t["alias"] = alias
    return t


# ---------------------------------------------------------------- 행 = 블로그 절

ROWS: list[tuple[str, str, list]] = []


def row(title, blog_part, panels):
    ROWS.append((title, blog_part, panels))


# ── 파트 0 · 도입부 (히어로) ────────────────────────────────────────────────
row("개요", "블로그 도입부", [
    stat("유입 (전체 RPS)",
         f'sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}"}}[1m]))',
         unit="reqps", calc="max", decimals=0,
         desc="폭풍 구간 최대 유입. 평시 대비 배수는 캡션이 책임진다."),
    stat("감상글 상세 p99",
         f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri="{HOT_URI}"}}[1m])))',
         unit="s", calc="max",
         steps=[{"color": "green", "value": None}, {"color": "red", "value": TH["shelfeed-p99"]}],
         desc=f"알람 ⓪와 같은 식. {TH['shelfeed-p99']}초를 넘으면 빨강."),
    stat("성공률 (2xx+3xx)",
         f'sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}",status=~"2..|3.."}}[1m]))'
         f' / sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}"}}[1m]))',
         unit="percentunit", calc="min",
         steps=[{"color": "red", "value": None}, {"color": "green", "value": 0.99}],
         desc="떨어지는 지표라 Min으로 읽는다. Max로 두면 100%가 찍혀 아무 말도 못 한다."),
    stat("HikariCP Pending",
         'hikaricp_connections_pending{application="shelfeed"}',
         calc="max", decimals=0,
         steps=[{"color": "green", "value": None},
                {"color": "red", "value": TH["shelfeed-pending"] + 1}],
         desc="알람 ①은 pending > 0 이다. pending은 정수라 첫 '나쁜' 값은 0+1이고, "
              "색이 바뀌는 지점을 알람 임계에서 유도한다."),
    stat("행 락 평균 대기",
         'rate(mysql_global_status_innodb_row_lock_time[1m])'
         ' / clamp_min(rate(mysql_global_status_innodb_row_lock_waits[1m]), 1e-9)',
         unit="ms", calc="max", w=4,
         steps=[{"color": "green", "value": None},
                {"color": "red", "value": TH["shelfeed-rowlock-time"]}],
         desc=f"알람 ④. 임계 {TH['shelfeed-rowlock-time']}ms."),
])

# ── 파트 2 · 유입은 2.8배인데 응답은 167배 ─────────────────────────────────
row("HTTP", "감상글 하나가 뜨자 로그인까지 느려졌다", [
    timeseries("API p99 — 감상글 상세 vs 그 외 전체", [
        prom(f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri="{HOT_URI}"}}[1m])))',
             "감상글 상세 /reviews/{id}", "A"),
        prom(f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri=~"{ALL_URI}", uri!="{HOT_URI}"}}[1m])))',
             "그 외 API 전체", "B"),
    ], unit="s", threshold=TH["shelfeed-p99"], w=24, h=10,
        desc="두 라인이 같은 시각에 같은 모양으로 꺾이는지를 본다. 그게 공유 자원 문제의 지문이다.",
        threshold_label=f"점선 = 알람 ⓪ 임계 {TH['shelfeed-p99']}s"),

    timeseries("API별 p99 — uri 개별", [
        prom(f'histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket{{uri=~"{ALL_URI}"}}[1m])))',
             "{{uri}}"),
    ], unit="s", threshold=TH["shelfeed-p99"],
        desc="본문이 인용하는 피드·검색·로그인 각각의 p99. 이 셋은 uri가 달라 서버 쪽에서 분리된다."),

    timeseries("부하 — API별 RPS", [
        prom(f'sum by (uri) (rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}"}}[1m]))',
             "{{uri}}"),
    ], unit="reqps",
        desc="한 줄만 솟고 나머지는 평탄한지 본다. 이 라인은 '인기글'이 아니라 '감상글 상세 전체'다 — "
             "Micrometer가 uri를 템플릿해 인기글과 다른 글이 합쳐진다."),

    timeseries("k6 체감 지연 — api 태그별 p99", [
        influx('SELECT percentile("value", 99) FROM "http_req_duration" '
               'WHERE $timeFilter GROUP BY time($__interval), "api" fill(none)'),
    ], unit="ms",
        desc="클라이언트가 본 지연. hot_detail(인기글 한 건)과 bg_*를 나누는 유일한 방법 — "
             "Prometheus는 두 요청이 같은 uri 라벨에 합쳐져 분리하지 못한다."),

    timeseries("k6 API별 RPS — api 태그별", [
        influx('SELECT count("value") / 10 FROM "http_reqs" '
               'WHERE $timeFilter GROUP BY time(10s), "api" fill(0)'),
    ], unit="reqps",
        desc="hot_detail만 오르고 bg_feed·bg_search·bg_login은 평탄한지 본다."),

    text_note(
        "**읽는 법** — 유입이 는 건 감상글 상세 하나뿐인데 p99는 넷이 같은 시각에 같은 모양으로 꺾인다. "
        "부하는 선형으로 느는데 지연이 비선형으로 터졌고, 조회수와 무관한 API가 같이 느려졌다. "
        "개별 API 문제가 아니라 **넷이 공유하는 자원**의 문제다.", h=3),
])

# ── 파트 3 · 사용자에게는 지연이 아니라 실패로 보였다 ──────────────────────
row("HTTP", "사용자에게는 지연이 아니라 실패로 보였다", [
    timeseries("성공률 — 2xx+3xx 비율", [
        prom(f'sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}",status=~"2..|3.."}}[1m]))'
             f' / sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}"}}[1m])) * 100',
             "성공률"),
    ], unit="percent", calcs=("max", "min", "lastNotNull"), maxval=100, minval=0,
        desc="Max/Min/Last로 읽는다. y축을 0~100으로 고정해야 하락폭이 과장되지 않는다."),

    timeseries("실패 — 5xx 비율", [
        prom(f'sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}",status=~"5.."}}[1m]))'
             f' / sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}"}}[1m])) * 100',
             "error rate"),
    ], unit="percent", w=6,
        desc="커넥션 타임아웃에 걸린 요청이 여기로 빠진다."),

    timeseries("HTTP 5xx 건수 (분당)", [
        prom(f'sum(increase(http_server_requests_seconds_count{{uri=~"{ALL_URI}",status=~"5.."}}[1m]))',
             "5xx"),
    ], w=6, desc="옆의 '커넥션 획득 타임아웃'과 같은 모양·같은 높이인지 겹쳐 본다."),

    stat("폭풍 구간 누적 5xx",
         f'sum(increase(http_server_requests_seconds_count{{uri=~"{ALL_URI}",status=~"5.."}}[$__range]))',
         calc="lastNotNull", decimals=0, w=6, h=5, instant=True,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": 1}],
         desc="본문에 쓸 총 건수. $__range 전체의 합이라 instant 질의로 한 번만 계산한다."),

    timeseries("커넥션 획득 타임아웃 건수", [
        prom('increase(hikaricp_connections_timeout_total{application="shelfeed"}[1m])',
             "acquire timeout"),
    ], w=18, h=5,
        desc="5xx의 원인이 풀 타임아웃임을 말이 아니라 그림으로 잇는 패널. "
             "위 5xx 곡선과 포개지면 인과가 증명된다."),

    text_note(
        f"**읽는 법** — 커넥션 타임아웃은 **{CONN_TIMEOUT_S:.0f}초**다. 풀에서 그 안에 커넥션을 못 받으면 예외가 나고 500으로 떨어진다. "
        "그래서 응답이 느려진 것으로 끝나지 않고 실패하는 구간이 생긴다. "
        "**p99 수 초는 사용자 눈에 지연이 아니라 장애다.**", h=3),
])

# ── 파트 5 · DB는 한가했다 ────────────────────────────────────────────────
row("MySQL", "DB는 한가했다", [
    timeseries("MySQL Threads running / connected", [
        prom("mysql_global_status_threads_connected", "Threads connected", "A"),
        prom("mysql_global_status_threads_running", "Threads running", "B"),
    ], w=6, h=8, threshold=TH["shelfeed-threads-running"],
        desc="**락을 기다리는 세션도 running으로 센다.** running이 올라도 DB가 바쁜 것과 같지 않다.",
        threshold_label=f"점선 = 알람 ⑤ 임계 {TH['shelfeed-threads-running']}"),

    timeseries("슬로우 쿼리 발생률", [
        prom("rate(mysql_global_status_slow_queries[1m])", "slow queries/sec"),
    ], unit="ops", w=6, h=8, threshold=TH["shelfeed-slow-queries"],
        desc="long_query_time을 넘긴 쿼리만 센다. 경합은 여기에 안 잡힌다.",
        threshold_label="점선 = 알람 ⑥ 임계 0"),

    timeseries("MySQL QPS", [
        prom("rate(mysql_global_status_queries[1m])", "queries/sec"),
    ], unit="ops", w=6, h=8,
        desc="처리량이 오히려 늘었는지 본다. 늘었다면 DB가 일을 못 해서 느린 게 아니다."),

    timeseries("호스트 CPU 사용률", [
        prom('100 - avg(rate(node_cpu_seconds_total{mode="idle"}[1m])) * 100', "host cpu"),
    ], unit="percent", w=6, h=8, maxval=100,
        desc="**cAdvisor가 없어 MySQL 컨테이너 CPU는 못 잰다.** 이건 node-exporter가 본 호스트 CPU다. "
             "DB 무죄의 근거는 CPU보다 QPS와 슬로우 쿼리 쪽에 둔다."),

    text_note(
        "**읽는 법** — DB가 더 많은 일을 하느라 느려진 게 아니다. 처리량은 오히려 늘었고 슬로우 쿼리는 없다. "
        "**DB는 바쁘지 않은데 앱은 느렸다. 앱이 DB를 기다리고 있었다.**", h=3),
])

# ── 파트 7 · 커넥션 풀이 비어 있었다 ───────────────────────────────────────
row("커넥션 풀", "커넥션 풀이 비어 있었다", [
    stat("Active", 'hikaricp_connections_active{application="shelfeed"}', calc="max",
         decimals=0, w=4, h=4,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": POOL_MAX}],
         desc=f"풀 상한 {POOL_MAX}에 닿으면 빨강."),
    stat("Idle", 'hikaricp_connections_idle{application="shelfeed"}', calc="min",
         decimals=0, w=4, h=4,
         steps=[{"color": "red", "value": None}, {"color": "green", "value": 1}],
         desc="0으로 떨어지는 순간이 풀이 빈 순간이다. Min으로 읽는다."),
    stat("Pending", 'hikaricp_connections_pending{application="shelfeed"}', calc="max",
         decimals=0, w=4, h=4,
         steps=[{"color": "green", "value": None},
                {"color": "red", "value": TH["shelfeed-pending"] + 1}],
         desc="풀 앞에 줄 선 요청 수. 색 기준은 알람 ①(pending > 0)에서 유도."),
    stat("Max (풀 상한)", 'hikaricp_connections_max{application="shelfeed"}', calc="lastNotNull",
         decimals=0, w=4, h=4, desc="maximumPoolSize"),
    stat("평균 acquire", 'rate(hikaricp_connections_acquire_seconds_sum{application="shelfeed"}[1m]) / clamp_min(rate(hikaricp_connections_acquire_seconds_count{application="shelfeed"}[1m]), 1e-9)',
         unit="s", calc="max", w=4, h=4,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": TH["shelfeed-acquire"]}],
         desc="커넥션을 받기까지 기다린 시간의 1분 평균(폭풍 구간 최대). "
              "_max 게이지 대신 평균을 쓰는 이유: _max는 감쇠 창이라 워밍업 스파이크가 몇 분 남아 "
              "평시 구간까지 5초로 보인다. 타임아웃 5초에 닿았다는 사실은 '커넥션 획득 타임아웃 건수' 패널이 증명한다."),
    stat("평균 usage", 'rate(hikaricp_connections_usage_seconds_sum{application="shelfeed"}[1m]) / clamp_min(rate(hikaricp_connections_usage_seconds_count{application="shelfeed"}[1m]), 1e-9)',
         unit="s", calc="max", w=4, h=4,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": 1}],
         desc="커넥션을 쥐고 있던 시간의 1분 평균. 이 값이 작은데 acquire가 크면 "
              "'오래 잡아서'가 아니라 '짧게 잡는 걸 한 줄로 세워서' 풀이 비는 것이다."),

    timeseries("HikariCP — Active / Idle / Pending", [
        prom('hikaricp_connections_pending{application="shelfeed"}', "Pending", "A"),
        prom('hikaricp_connections_active{application="shelfeed"}', "Active", "B"),
        prom('hikaricp_connections_idle{application="shelfeed"}', "Idle", "C"),
        prom('hikaricp_connections_max{application="shelfeed"}', "Max (풀 상한)", "D"),
    ], w=12, h=10,
        desc="Idle이 0으로 꺼지는 순간과 Pending이 솟는 순간이 겹치는지 본다."),

    timeseries("커넥션 획득 대기(acquire) vs 점유(usage) 시간 — 평균", [
        prom('rate(hikaricp_connections_acquire_seconds_sum{application="shelfeed"}[1m]) / clamp_min(rate(hikaricp_connections_acquire_seconds_count{application="shelfeed"}[1m]), 1e-9)', "평균 acquire (기다린 시간)", "A"),
        prom('rate(hikaricp_connections_usage_seconds_sum{application="shelfeed"}[1m]) / clamp_min(rate(hikaricp_connections_usage_seconds_count{application="shelfeed"}[1m]), 1e-9)', "평균 usage (쥐고 있던 시간)", "B"),
        prom('rate(hikaricp_connections_creation_seconds_sum{application="shelfeed"}[1m]) / clamp_min(rate(hikaricp_connections_creation_seconds_count{application="shelfeed"}[1m]), 1e-9)', "평균 creation", "C"),
    ], unit="s", w=12, h=10,
        threshold=[(TH["shelfeed-acquire"], "orange"), (CONN_TIMEOUT_S, "red")],
        desc="rate(_sum)/rate(_count) 1분 평균. _max 게이지를 쓰지 않는 이유는 감쇠 창 잔여가 "
             "평시 구간을 오염시키기 때문이다. 읽는 법: acquire는 수 초로 오르는데 usage는 수십 ms에 "
             "머물면 — 커넥션을 오래 잡는 게 아니라 짧게 잡는 걸 한 줄로 세우고 있다는 뜻이다. "
             "creation이 0이면 커넥션을 새로 만드느라 느린 것도 아니다.",
        threshold_label=f"점선 2개 = 알람 ② 임계 {TH['shelfeed-acquire']}s(주황) · "
                        f"커넥션 타임아웃 {CONN_TIMEOUT_S:.0f}s(빨강)"),

    timeseries("Tomcat busy threads", [
        prom("tomcat_threads_busy_threads", "busy threads"),
    ], w=24, h=7,
        desc="풀 앞 대기열의 정체. Pending이 크게 쌓이려면 톰캣 스레드가 그만큼 열려 있어야 한다 — "
             "재현 조건을 드러내는 패널이다."),

    text_note(
        "**읽는 법** — 풀이 작아서가 아니다. **나간 커넥션이 돌아오지 않아서 비었다.** "
        "그렇다면 무엇을 기다리고 있었나 — 다음 행이 그 답이다.", h=3),
])

# ── 파트 8 · 커넥션을 붙잡고 있던 건 한 행의 락 ────────────────────────────
row("MySQL", "커넥션을 붙잡고 있던 건 한 행의 락이었다", [
    timeseries("행 락 대기 발생률 (waits/sec)", [
        prom("rate(mysql_global_status_innodb_row_lock_waits[1m])", "row lock waits/sec"),
    ], unit="ops", w=8, threshold=TH["shelfeed-rowlock-rate"],
        desc="초당 몇 건이 락 앞에 섰나.",
        threshold_label=f"점선 = 알람 ③ 임계 {TH['shelfeed-rowlock-rate']}/s"),

    timeseries("현재 행 락 대기 세션 수", [
        prom("mysql_global_status_innodb_row_lock_current_waits", "현재 대기 중", "A"),
        prom('max(hikaricp_connections_max{application="shelfeed"})', "풀 크기", "B"),
    ], w=8,
        desc="**이 대시보드에서 가장 중요한 패널.** 최대치가 풀 크기와 같으면 "
             "'커넥션 전부가 같은 행 앞에 서 있다'가 그림 하나로 증명된다. "
             "발생률 패널과 단위가 달라 반드시 분리해야 한다."),

    timeseries("행 락 평균 대기 시간", [
        prom("rate(mysql_global_status_innodb_row_lock_time[1m])"
             " / clamp_min(rate(mysql_global_status_innodb_row_lock_waits[1m]), 1e-9)", "평균 락 대기 시간"),
    ], unit="ms", w=8, threshold=TH["shelfeed-rowlock-time"],
        desc="한 건당 대기는 짧을 수 있다. 풀 크기가 락 앞 대기열의 상한이기 때문이다.",
        threshold_label=f"점선 = 알람 ④ 임계 {TH['shelfeed-rowlock-time']}ms"),

    stat("락 대기가 풀 시간에서 차지한 비율",
         "(rate(mysql_global_status_innodb_row_lock_time[1m]) / 1000)"
         ' / scalar(max(hikaricp_connections_max{application="shelfeed"})) * 100',
         unit="percent", calc="max", w=12, h=5,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": 50}],
         desc="row_lock_time은 누적 밀리초다. rate는 ms/s → /1000 이면 s/s, 곧 평균 동시 락 대기 "
              "세션 수다. 풀 크기로 나누면 풀 시간의 몇 %가 락 대기였는지가 나온다. "
              "unit이 percent이므로 식에 *100이 있고 percentunit을 쓰면 안 된다. "
              "분모를 scalar()로 감싼 이유: 좌변은 mysqld-exporter의 instance/job 라벨을 달고 "
              "있고 집계 함수를 거친 우변은 라벨이 없어 그냥 나누면 벡터 매칭이 실패해 빈 결과가 된다."),

    stat("감상글 상세 요청 중 락 앞에 선 비율",
         "clamp_max(rate(mysql_global_status_innodb_row_lock_waits[1m])"
         f' / scalar(sum(rate(http_server_requests_seconds_count{{uri="{HOT_URI}"}}[1m]))) * 100, 100)',
         unit="percent", calc="max", w=12, h=5,
         steps=[{"color": "green", "value": None}, {"color": "red", "value": 50}],
         desc="분모는 '인기글'이 아니라 감상글 상세 전체다. Prometheus가 uri를 템플릿하기 때문이며 "
              "본문에 인용할 때도 그렇게 부른다. 100으로 clamp한 이유: 분자(mysqld-exporter)와 "
              "분모(앱)의 스크레이프 위상이 달라 1분 rate가 순간적으로 100%를 넘는다(실측 101%). "
              "한 요청은 그 행에 최대 한 번 대기하므로 참값은 100% 이하다."),

    text_note(
        f"**읽는 법** — 한 건이 오래 기다린 게 아니라 **모든 커넥션이 거기 묶여 있었다.** "
        f"락 앞에 설 수 있는 세션은 풀 크기 {POOL_MAX}개가 상한이고, 나머지는 DB에 들어가지도 못하고 "
        "앞 행의 Pending으로 쌓인다. 그래서 한 건당 대기는 짧아도 총량은 전혀 짧지 않다. "
        "**원인은 한 행인데 피해는 서비스 전체였다.**", h=3),
])


# ---------------------------------------------------------------- 레이아웃

def build() -> dict:
    panels = []
    y = 0
    for title, blog_part, items in ROWS:
        panels.append({
            "id": next(_ids), "type": "row", "title": title, "collapsed": False,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": [],
            "description": f"블로그 절: {blog_part}",
        })
        y += 1
        x = 0
        line_h = 0
        for p in items:
            w, h = p.pop("_w"), p.pop("_h")
            if x + w > 24:
                x, y = 0, y + line_h
                line_h = 0
            p["gridPos"] = {"h": h, "w": w, "x": x, "y": y}
            x += w
            line_h = max(line_h, h)
            panels.append(p)
        y += line_h
    return {
        "uid": "shelfeed-view-lock",
        "title": "Shelfeed — 서비스 운영 대시보드",
        "description": "행 순서 = 블로그 절 순서. 임계 점선은 view-lock-rules.yaml에서 생성 시점에 주입된다.",
        "tags": ["shelfeed", "view-count", "row-lock"],
        "timezone": "browser",
        "schemaVersion": 39,
        "version": 0,
        "refresh": "10s",
        "time": {"from": "now-15m", "to": "now"},
        "editable": True,
        "graphTooltip": 1,
        "annotations": {"list": [{
            "name": "알람 상태 변화",
            "datasource": {"type": "datasource", "uid": "grafana"},
            "enable": True, "hide": False, "iconColor": "red",
            "target": {"type": "dashboard", "limit": 100, "matchAny": False, "tags": []},
        }]},
        "panels": panels,
    }


def build_alerts() -> dict:
    """알림 전용 대시보드.

    본문 파트 4("알림은 하나만 울렸다")와 파트 14("3분 안에 알아채도록")가 같은 화면을
    서로 다른 상태로 보여 준다. 운영 대시보드와 섞으면 그 행만 잘라 캡처해야 하고
    시간 범위도 따로 놀기 때문에 대시보드를 분리한다.
    """
    alertlist = {
        "id": next(_ids), "type": "alertlist", "title": "Alert rules",
        "description": "Shelfeed 폴더의 Grafana 관리 룰만 보여 준다. "
                       "어떤 룰이 울렸나보다 어떤 룰이 목록에 아예 없나를 먼저 본다. "
                       "주의: 이 패널은 대시보드 시간 범위를 따르지 않고 '지금' 상태만 보여 준다.",
        "gridPos": {"h": 13, "w": 24, "x": 0, "y": 0},
        "options": {
            "viewMode": "list", "groupMode": "default", "groupBy": [],
            "maxItems": 20, "sortOrder": 1,
            "dashboardAlerts": False, "alertName": "", "dashboardTitle": "",
            "folder": ALERT_FOLDER, "datasource": "grafana",
            "tags": [], "alertInstanceLabelFilter": "",
            "stateFilter": {"firing": True, "pending": True, "normal": True,
                            "error": True, "noData": False},
        },
    }
    note = {
        "id": next(_ids), "type": "text", "title": "", "transparent": True,
        "gridPos": {"h": 5, "w": 24, "x": 0, "y": 13},
        "options": {"mode": "markdown", "content":
            "**사건 당시 화면으로 읽는 법** — 이 목록이 그때 본 전부다. "
            "커넥션 풀·행 락·슬로우 쿼리 룰은 **Normal이어서 조용했던 게 아니라, 아예 등록돼 있지 않았다.**\n\n"
            "알람 하나로는 \"이 API가 느리다\"까지밖에 못 읽는다 — 그래서 상세 조회 쿼리부터 열어 보게 된다.\n\n"
            "**탐지의 한계는 지표가 아니라 등록된 질문의 목록이 정한다.** "
            "`innodb_row_lock_waits`는 그때도 exporter가 뱉고 있었다. 아무도 안 물었을 뿐이다.\n\n"
            "---\n\n"
            "**재발 방지 화면으로 읽는 법** — 룰 7개가 다 등록되면 발화 순서가 곧 진단 순서가 된다. "
            "①② 가 먼저 울려 *공유 자원이 막혔다*를 알리고, 2~3분 뒤 ③④ 가 따라 올라와 *행 락 때문이다*까지 좁힌다. "
            "그 사이 **⑤⑥ 이 Normal로 남아 DB 리소스를 용의선상에서 지운다** — 일부러 안 울리게 둔 대조군이다.\n\n"
            "> **캡처 시점 주의** — 이 패널은 대시보드 시간 범위를 따르지 않는다. 언제나 *지금* 상태만 "
            "보여 주므로, Firing 화면은 **부하가 도는 중에** 캡처해야 한다. 부하가 끝나면 곧 Normal로 돌아간다.\n\n"
            "> 알람 프로비저닝은 **Grafana 기동 시 1회만** 적재된다. 룰 파일을 고쳤다면 "
            "`docker compose restart grafana` 후에야 목록에 반영된다.\n\n"
            "> 이 패널은 Shelfeed 폴더의 **Grafana 관리 룰만** 보여 준다. "
            "`docker/prometheus/rules/view-lock.yml` 은 같은 룰의 Prometheus 문법판(참조용)이라 "
            "여기서는 일부러 제외한다 — 섞이면 \"그때 ⓪ 하나뿐이었다\"는 그림이 거짓이 된다."}
    }
    return {
        "uid": "shelfeed-alerts",
        "title": "Shelfeed — 알림",
        "description": "블로그 파트 4·14용. 등록된 알람 룰과 상태.",
        "tags": ["shelfeed", "view-count", "row-lock", "alerting"],
        "timezone": "browser", "schemaVersion": 39, "version": 0,
        "refresh": "10s", "time": {"from": "now-15m", "to": "now"},
        "editable": True,
        "panels": [alertlist, note],
    }



# ---------------------------------------------------------------- 실험 대시보드 (해결 대안 검증)

# 기준값 = 실측 11 (2026-09-19 21:35 실행 · 캡처 범위 21:37→21:44). 타일 제목에 박아 두어
# 실험 실행을 캡처하면 한 장에서 "기준 → 실험" 이 읽히게 한다.
BASE_FULL = {  # 실측 11 · 2,065 VU · 정착 폭풍 09-19 21:38:30~21:44:30
    "p99": "9.99s", "succ": "49.63%", "pending": "1,991", "waiters": "7 (풀 8)",
    "share": "80.1%", "lock_avg": "27ms", "lock_rate": "297/s", "hot_rps": "307/s",
    "upd_rate": "302/s", "acq": "4.28s",
}
BASE = BASE_FULL
# 실험 H(상세 조회 캐시)의 기준은 원본 사건이 아니라 실험 C다. 블로그에 실린 C 캡처의 타일 값 그대로.
BASE_C = {  # 실험 C · 같은 부하(2,065 VU) · 09-20 22:27:30~22:33:30
    "p99": "11.6s", "succ": "82.7%", "pending": "1,990", "waiters": "0 (풀 8)",
    "share": "0%", "lock_avg": "0ms", "lock_rate": "0/s", "hot_rps": "525/s",
    "upd_rate": "7/s", "acq": "—",
}

_A = {"application": "shelfeed"}
ACQ_AVG = ('rate(hikaricp_connections_acquire_seconds_sum{application="shelfeed"}[1m])'
           ' / clamp_min(rate(hikaricp_connections_acquire_seconds_count{application="shelfeed"}[1m]), 1e-9)')
LOCK_AVG_MS = ('rate(mysql_global_status_innodb_row_lock_time[1m])'
               ' / clamp_min(rate(mysql_global_status_innodb_row_lock_waits[1m]), 1e-9)')
LOCK_RATE = 'rate(mysql_global_status_innodb_row_lock_waits[1m])'
HOT_RPS = f'sum(rate(http_server_requests_seconds_count{{uri="{HOT_URI}"}}[1m]))'
UPD_RATE = 'rate(mysql_global_status_commands_total{command="update"}[1m])'
HOT_P99 = f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri="{HOT_URI}"}}[1m])))'
# 조회 기록 API는 Redis만 거쳐 12ms로 끝나고 초당 수백 건이 들어온다. "그 외 API"에 섞으면
# 피드·검색의 p99가 희석돼 7.75s가 3.88s로 보인다(실측). 비교 집합을 실험 전후로 같게 유지한다.
VIEW_URI = "/api/v1/reviews/{reviewId}/view"
VIEW_RPS = f'sum(rate(http_server_requests_seconds_count{{uri="{VIEW_URI}"}}[1m]))'
VIEW_P99 = f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri="{VIEW_URI}"}}[1m])))'
# 중복 제거 카운터 — 앱이 Micrometer Counter "view.dedup"(tag result=hit|miss)를 내보내야 값이 생긴다.
DEDUP_HIT  = 'sum(rate(view_dedup_total{application="shelfeed",result="hit"}[1m]))'
DEDUP_MISS = 'sum(rate(view_dedup_total{application="shelfeed",result="miss"}[1m]))'
DEDUP_RATIO = f'({DEDUP_HIT}) / clamp_min(({DEDUP_HIT}) + ({DEDUP_MISS}), 1e-9)'
OTHER_P99 = f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{uri=~"{ALL_URI}", uri!="{HOT_URI}", uri!="{VIEW_URI}"}}[1m])))'
SUCC = (f'sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}",uri!="{VIEW_URI}",status=~"2..|3.."}}[1m]))'
        f' / sum(rate(http_server_requests_seconds_count{{uri=~"{ALL_URI}",uri!="{VIEW_URI}"}}[1m]))')
# 요청 1건이 커넥션을 두 번 빌린다(인증 필터의 회원 조회 + 서비스 트랜잭션). 풀 포화도는
# HTTP 요청이 아니라 "커넥션 대여 횟수"로 봐야 상한과 같은 단위가 된다.
# Kafka 클라이언트가 스스로 계산해 내보내는 비율이다. rate()로 다시 감싸면 안 된다.
# ClickHouse 파이프라인. Kafka Engine 이 토픽을 직접 읽고 Materialized View 가 굴려 넣는다.
CH_ROWS_READ  = 'rate(ClickHouseProfileEvents_KafkaRowsRead{job="clickhouse"}[1m])'
# KafkaRowsWritten 은 ClickHouse 가 토픽으로 '쓸' 때 올라가는 값이라 여기서는 늘 0이다.
# 실제로 저장된 행은 MergeTree 총 행수의 증가분으로 본다.
CH_ROWS_WRITE = 'sum(rate(ClickHouseAsyncMetrics_TotalRowsOfMergeTreeTables{job="clickhouse"}[1m]))'
CH_REJECTED   = 'rate(ClickHouseProfileEvents_KafkaRowsRejected{job="clickhouse"}[1m])'
CH_ERRORS     = ('rate(ClickHouseProfileEvents_KafkaConsumerErrors{job="clickhouse"}[1m])'
                 ' + rate(ClickHouseProfileEvents_KafkaCommitFailures{job="clickhouse"}[1m])')
CH_STORED     = 'ClickHouseAsyncMetrics_TotalRowsOfMergeTreeTables{job="clickhouse"}'
KAFKA_SEND = 'sum(kafka_producer_record_send_rate{application="shelfeed"})'
KAFKA_CONSUME = 'sum(kafka_consumer_fetch_manager_records_consumed_rate{application="shelfeed"})'
KAFKA_LAG = 'max(kafka_consumer_fetch_manager_records_lag_max{application="shelfeed"})'
KAFKA_QUEUE_MS = 'max(kafka_producer_record_queue_time_avg{application="shelfeed"})'
BORROW_RATE = 'rate(hikaricp_connections_usage_seconds_count{application="shelfeed"}[1m])'
CACHE_HIT  = 'sum(rate(review_detail_cache_total{application="shelfeed",result=~"l1_hit|l2_hit"}[1m]))'
CACHE_MISS = 'sum(rate(review_detail_cache_total{application="shelfeed",result="miss"}[1m]))'
CACHE_RATIO = f'({CACHE_HIT}) / clamp_min(({CACHE_HIT}) + ({CACHE_MISS}), 1e-9)'
USAGE_AVG = ('rate(hikaricp_connections_usage_seconds_sum{application="shelfeed"}[1m])'
             ' / clamp_min(rate(hikaricp_connections_usage_seconds_count{application="shelfeed"}[1m]), 1e-9)')
# 직렬화가 사라지면 커넥션 8개가 비로소 병렬로 돈다. 그때의 상한 = 풀 크기 ÷ 커넥션 1회 점유 시간.
POOL_CEIL = ('scalar(max(hikaricp_connections_max{application="shelfeed"}))'
             f' / clamp_min({USAGE_AVG}, 1e-9)')
SHARE = ('(rate(mysql_global_status_innodb_row_lock_time[1m]) / 1000)'
         ' / scalar(max(hikaricp_connections_max{application="shelfeed"})) * 100')


def _layout(rows):
    """(행 제목, 행 설명, 패널 목록) 리스트 → gridPos 가 채워진 패널 배열."""
    panels, y = [], 0
    for title, desc, items in rows:
        panels.append({"id": next(_ids), "type": "row", "title": title, "collapsed": False,
                       "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": [],
                       "description": desc})
        y += 1; x = 0; line_h = 0
        for p in items:
            w, h = p.pop("_w"), p.pop("_h")
            if x + w > 24:
                x, y, line_h = 0, y + line_h, 0
            p["gridPos"] = {"h": h, "w": w, "x": x, "y": y}
            x += w; line_h = max(line_h, h); panels.append(p)
        y += line_h
    return panels


def _bstat(title, base, expr, **kw):
    """기준값이 제목에 붙은 stat. '상세 p99 · 기준 9.99s' 처럼 읽힌다."""
    return stat(f"{title} · 기준 {base}", expr, w=4, h=5, **kw)


def build_experiment(key: str) -> dict:
    red = lambda v: [{"color": "green", "value": None}, {"color": "red", "value": v}]
    grn = lambda v: [{"color": "red", "value": None}, {"color": "green", "value": v}]
    extra = []  # 실험별 추가 행

    if key == "a":
        title = "Shelfeed — 실험 A · 풀 8 → 20"
        card = text_note(
            "**가설** — 풀을 늘려도 **처리량 천장은 그대로**다. 한 행의 처리량 = 1 ÷ 락 보유 시간이고, "
            "풀 크기는 그 식에 없다. 늘어나는 건 락 앞에 선 세션 수뿐이다.\n\n"
            "**바꾼 것** — `application-local.yml` `maximum-pool-size: 8 → 20`. 나머지(부하·코드)는 기준 실행과 동일.\n\n"
            "**결과** (정착 폭풍 6분끼리 비교 · 기준 21:38:30~21:44:30, 실험 23:05:30~23:11:30)\n\n"
            "① **천장은 그대로였다** — 상세 처리량 277 → 267/s. 풀을 2.5배로 늘렸는데 처리량은 오히려 3.6% 내려갔다.\n\n"
            "② **줄은 옮겨간 게 아니라 하나 더 생겼다** — 처음엔 Pending이 내려갈 것으로 봤지만 1,883 → 1,832로 거의 안 움직였고, "
            "락 앞 세션만 6.2 → 18.3으로 3배가 됐다. 동시 요청 총량은 Tomcat 스레드 상한(2,000 · 실측 busy ≈1,950)이 정하고 있어서, "
            "풀을 12개 늘린 것은 그 줄에서 보이지도 않는다.\n\n"
            "③ **모델은 맞았다** — 대기 ≈ 보유 시간 × 앞선 세션 수. 27.0ms ÷ 6.16 = 4.4ms, 96.3ms ÷ 18.32 = 5.3ms. "
            "락 보유 시간은 풀 크기와 무관하게 그대로다.\n\n"
            "④ **중립이 아니라 손해였다** — 커넥션 점유 시간 15.6 → 44.0ms, p99 최대 9.99 → 22.2s, MySQL QPS 4,615 → 4,062.", h=9)
        tiles = [
            _bstat("감상글 상세 처리량", BASE["hot_rps"], HOT_RPS, unit="reqps", decimals=0,
                   desc="천장. 락 보유 시간이 정하고 풀 크기는 못 바꾼다 — 실측 277 → 267/s."),
            _bstat("감상글 상세 p99", BASE["p99"], HOT_P99, unit="s", steps=red(TH["shelfeed-p99"]),
                   desc="안 내려가면 사용자에게 이득이 없다 — 실측은 오히려 악화(평균 9.09 → 10.63s)."),
            _bstat("성공률 최저", BASE["succ"], SUCC, unit="percentunit", calc="min", steps=grn(0.99)),
            _bstat("HikariCP Pending", BASE["pending"], 'hikaricp_connections_pending{application="shelfeed"}',
                   decimals=0, steps=red(1), desc="예상과 달리 거의 안 내려갔다(1,883 → 1,832). 대기 총량은 Tomcat 스레드 상한이 정한다."),
            _bstat("락 앞에 선 세션", BASE["waiters"], "mysql_global_status_innodb_row_lock_current_waits",
                   decimals=0, desc="풀 크기 − 1 까지 올라간다 — 실측 평균 18.3, 최대 19(풀 20)."),
            _bstat("행 락 평균 대기", BASE["lock_avg"], LOCK_AVG_MS, unit="ms", calc="mean",
                   steps=red(TH["shelfeed-rowlock-time"]),
                   desc="앞에 선 세션이 3배가 된 만큼 3.6배 길어졌다(27.0 → 96.3ms). 대기 ≈ 보유 시간 × 앞선 세션 수."),
        ]
        charts = [
            timeseries("감상글 상세 처리량 vs 행 락 대기 발생률", [
                prom(HOT_RPS, "감상글 상세 처리량 (req/s)", "A"),
                prom(LOCK_RATE, "행 락 대기 발생률 (ops/s)", "B"),
            ], unit="short", w=8, h=10,
                desc="두 선이 기준 높이에서 안 움직였다. 풀 크기는 한 행의 처리량과 무관하다."),
            timeseries("락 대기 세션 vs 풀 크기", [
                prom("mysql_global_status_innodb_row_lock_current_waits", "락 앞에 선 세션", "A"),
                prom('max(hikaricp_connections_max{application="shelfeed"})', "풀 크기", "B"),
            ], w=8, h=10, desc="세션 수가 풀 크기 선에 붙어 올라간다(풀 20 → 19개). 앱 앞 Pending은 그대로였으니, 줄이 옮겨간 게 아니라 하나 더 생긴 것."),
            timeseries("커넥션 획득 대기(acquire) vs 행 락 대기", [
                prom(ACQ_AVG, "평균 acquire (앱 앞에서 기다림)", "A"),
                prom(f"({LOCK_AVG_MS}) / 1000", "행 락 평균 대기 (DB 안에서 기다림)", "B"),
            ], unit="s", w=8, h=10,
                desc="acquire는 줄지 않았고(3.47 → 3.50s) 행 락 대기만 3.6배가 됐다. 앞줄은 그대로인데 DB 안 줄이 추가된 것."),
        ]
        desc = "해결 대안 A 검증. 풀 크기를 늘리는 것이 왜 답이 아닌지를 실측으로 보인다."
    elif key == "g":
        title = "Shelfeed — 실험 G · ClickHouse가 집계를 가져간다"
        card = text_note(
            "**가설** — 조회 이벤트를 ClickHouse가 **컨슈머 코드 없이** 직접 받아 집계까지 끝낸다. "
            "그러면 '최근 1시간 인기글'·'순 방문자 수' 같은 무거운 질문을 MySQL이 더는 떠안지 않는다.\n\n"
            "**바꾼 것** — Kafka Engine 테이블이 `review.viewed`를 직접 소비하고, Materialized View 둘이 "
            "`view_events`(ReplacingMergeTree · 원본 보존, 같은 eventId 수렴)와 "
            "`view_stats`(AggregatingMergeTree · 분 단위 countState/uniqState)로 굴려 넣는다. "
            "컨슈머 그룹을 앱(`view-count`)과 다르게(`clickhouse-view-stats`) 둬서 **같은 이벤트를 둘이 각자 다 받는다** "
            "— Kafka를 넣은 진짜 이유인 팬아웃이 여기서 쓰인다.\n\n"
            "**앱은 아무것도 안 한다.** 프로듀서만 그대로고, 집계 코드도 컨슈머 서버도 없다.\n\n"
            "**같은 질문, 같은 385,983행 (서버 측 실행 시간)**\n\n"
            "| 질문 | MySQL | ClickHouse 원본 | ClickHouse 집계본 |\n"
            "|---|---|---|---|\n"
            "| 최근 1시간 TOP10 인기글 | **982 ms** | 10~15 ms | **9 ms** |\n"
            "| 최근 1시간 순 방문자 수 | **399 ms** | 8~9 ms | **8 ms** |\n"
            "| 저장 크기 | **110.4 MiB** | 16.74 MiB | 1.48 MiB |\n\n"
            "TOP10은 109배, 순 방문자는 50배 빠르고 저장은 6.6배 작다. "
            "덧붙여 **순 방문자 수는 원래 MySQL에서 불가능한 질문이었다** — `reviews.view_count`는 누적 숫자 하나뿐이라 "
            "누가 봤는지가 없다. 위 MySQL 값은 비교를 위해 이벤트 테이블을 일부러 만들어 넣은 것이고, "
            "실제로 그렇게 하면 **매일 110 MiB씩 쓰기 부하가 늘고 집계 1초 동안 커넥션 하나가 묶인다** — "
            "이 글이 내내 다룬 바로 그 문제다.\n\n"
            "**읽는 법** — ① 수집률이 앱 프로듀서 전송률과 겹치면 실시간으로 따라온 것이다. "
            "② 거부·오류가 0이어야 한다. ③ 그동안 MySQL UPDATE와 행 락은 계속 바닥이어야 한다.", h=16)
        tiles = [
            _bstat("ClickHouse 수집률", "앱 전송률과 같아야", CH_ROWS_READ, unit="rowsps", decimals=0,
                   desc="Kafka Engine 이 토픽에서 읽어들이는 행 수."),
            _bstat("테이블에 저장되는 행", "수집률과 같아야", CH_ROWS_WRITE, unit="rowsps", decimals=0,
                   desc="Materialized View 를 거쳐 MergeTree 에 실제로 쌓이는 속도. 수집률과 같아야 유실이 없다."),
            _bstat("거부된 행", "0이어야", CH_REJECTED, decimals=0, steps=red(1),
                   desc="파싱 실패 등으로 버려진 행."),
            _bstat("Kafka 오류", "0이어야", CH_ERRORS, decimals=0, steps=red(1),
                   desc="소비 오류 + 커밋 실패."),
            _bstat("MySQL UPDATE 실행률", BASE["upd_rate"], UPD_RATE, unit="ops", decimals=2,
                   desc="집계를 넘겼으니 MySQL 은 계속 한가해야 한다."),
            _bstat("행 락 대기 발생률", BASE["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"])),
        ]
        charts = [
            timeseries("Kafka 전송 vs ClickHouse 수집·기록", [
                prom(KAFKA_SEND, "앱 프로듀서 전송 (rec/s)", "A"),
                prom(CH_ROWS_READ, "ClickHouse 수집 (rows/s)", "B"),
                prom(CH_ROWS_WRITE, "ClickHouse 기록 (rows/s)", "C"),
            ], unit="short", w=8, h=10,
                desc="세 선이 겹치면 이벤트가 하나도 안 밀리고 집계까지 끝났다는 뜻이다. "
                     "앱 컨슈머와 ClickHouse 가 같은 토픽을 각자 다 받는다 — 컨슈머 그룹이 다르기 때문."),
            timeseries("MySQL UPDATE 실행률 · 행 락 대기 · Threads running", [
                prom(UPD_RATE, "MySQL UPDATE 실행률 (ops/s)", "A"),
                prom(LOCK_RATE, "행 락 대기 발생률 (ops/s)", "B"),
                prom("mysql_global_status_threads_running", "MySQL Threads running (개)", "C"),
            ], unit="short", w=8, h=10,
                desc="집계를 ClickHouse 가 가져갔으니 MySQL 은 폭풍 중에도 바닥에 머물러야 한다. "
                     "이벤트 테이블을 MySQL 에 뒀다면 여기가 초당 수백 건의 INSERT 로 올라간다."),
            timeseries("ClickHouse 거부된 행 · Kafka 오류", [
                prom(CH_REJECTED, "거부된 행 (rows/s)", "A"),
                prom(CH_ERRORS, "Kafka 오류 (회/s)", "B"),
            ], unit="short", w=8, h=10,
                desc="둘 다 0이어야 한다. 조회수를 세는 파이프라인이라 유실·오류가 그대로 통계 오차가 된다."),
        ]
        desc = "해결안 3단계 검증. ClickHouse 가 Kafka 를 직접 소비해 집계까지 끝내고, MySQL 은 그동안 한가한지 본다."
    elif key == "e":
        title = "Shelfeed — 실험 E · 조회 이벤트를 Kafka로"
        card = text_note(
            "**가설** — 실험 D는 요청 스레드가 Redis에 바로 누적했다. 여기서는 그 사이에 Kafka를 끼운다. "
            "조회 기록 API는 토픽에 던지고 끝나고, 컨슈머가 배치로 받아 Redis에 누적한 뒤 "
            "스케줄러가 분당 UPDATE 1건으로 반영한다. "
            "**질문은 하나다 — 단을 하나 더 껴도 느려지지 않는가, 그리고 이벤트가 밀리지 않는가.**\n\n"
            "**바꾼 것** — `review.viewed` 토픽(파티션 키 = reviewId로 같은 글의 순서 보장), "
            "배치 컨슈머(`max-poll-records 2000`, `linger-ms 20`, lz4). "
            "Kafka는 유실을 막는 대신 중복을 허용하는 at-least-once라, 메시지마다 UUID를 담고 "
            "**소비 측이 `SET key 1 NX EX`로 이미 본 eventId인지 확인해 거른다.** "
            "담는 것만으로는 멱등이 되지 않는다.\n\n"
            "**읽는 법** — ① 조회 기록 p99가 실험 D(12ms)와 자릿수가 같으면 Kafka를 껴도 사용자는 못 느낀다. "
            "② 프로듀서 전송률과 컨슈머 소비율이 겹치고 **랙이 0 근처면 파이프라인이 부하를 따라잡은 것**이다. "
            "랙이 우상향하면 컨슈머가 뒤처진 것이고, 그때는 조회수가 실시간이 아니게 된다. "
            "③ MySQL UPDATE는 실험 D와 같이 바닥이어야 한다 — 단을 껴도 쓰기 총량은 안 바뀐다.", h=10)
        tiles = [
            _bstat("조회 기록 처리량", "실험 D 465/s", VIEW_RPS, unit="reqps", decimals=0),
            _bstat("조회 기록 p99", "실험 D 12ms", VIEW_P99, unit="s", steps=red(TH["shelfeed-p99"]),
                   desc="**이 실험의 숫자.** Kafka를 껴도 자릿수가 같아야 한다."),
            _bstat("컨슈머 랙 최대", "없던 값", KAFKA_LAG, decimals=0, steps=red(10000),
                   desc="밀린 이벤트 수. 0 근처면 따라잡은 것, 우상향이면 뒤처진 것."),
            _bstat("MySQL UPDATE 실행률", BASE["upd_rate"], UPD_RATE, unit="ops", decimals=2,
                   desc="단을 껴도 쓰기 총량은 안 바뀐다 — 실험 D와 같아야 한다."),
            _bstat("행 락 대기 발생률", BASE["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"])),
            _bstat("감상글 상세 p99", BASE["p99"], HOT_P99, unit="s", steps=red(TH["shelfeed-p99"])),
        ]
        charts = [
            timeseries("Kafka 프로듀서 전송 vs 컨슈머 소비", [
                prom(KAFKA_SEND, "프로듀서 전송 (rec/s)", "A"),
                prom(KAFKA_CONSUME, "컨슈머 소비 (rec/s)", "B"),
                prom(KAFKA_LAG, "컨슈머 랙 (건)", "C"),
            ], unit="short", w=8, h=10,
                desc="두 선이 겹치고 랙이 바닥에 붙어 있으면 이벤트가 밀리지 않는다. "
                     "소비 선이 전송 선 아래로 벌어지면 그 간격만큼 조회수 반영이 늦어진다."),
            timeseries("API p99 — 조회 기록 vs 감상글 상세", [
                prom(VIEW_P99, "조회 기록 p99 (s)", "A"),
                prom(HOT_P99, "감상글 상세 p99 (s)", "B"),
            ], unit="s", w=8, h=10, threshold=TH["shelfeed-p99"],
                desc="조회 기록은 토픽에 던지고 끝나므로 DB를 기다리는 상세 조회와 자릿수가 달라야 한다. "
                     "여기가 상세 조회 쪽으로 올라붙으면 Kafka가 새 병목이 된 것이다."),
            timeseries("조회 기록 처리량 vs MySQL UPDATE 실행률", [
                prom(VIEW_RPS, "조회 기록 요청 (req/s)", "A"),
                prom(UPD_RATE, "MySQL UPDATE 실행률 (ops/s)", "B"),
            ], unit="short", w=8, h=10,
                desc="실험 D와 같은 그림이어야 한다. 중간에 Kafka가 들어가도 MySQL이 받는 쓰기는 그대로다."),
        ]
        desc = "해결안 2단계 검증. 조회 이벤트를 Kafka로 비동기화했을 때 지연이 늘지 않고 이벤트가 밀리지 않는지 본다."
    elif key == "d":
        title = "Shelfeed — 실험 D · 조회수를 Redis로, MySQL엔 분당 배치"
        card = text_note(
            "**가설** — 실험 C는 조회수를 아예 세지 않아 락을 없앴다. 실제 설계는 세지 않는 게 아니라 "
            "**다른 곳에 센다.** 조회 이벤트를 Redis에 누적하고 스케줄러가 1분마다 리뷰당 UPDATE 한 건으로 "
            "반영하면, 요청 1건이 UPDATE 1건을 만들던 1:1이 끊긴다. "
            "**옮긴 그 경로가 초당 수백 건을 받아내는지가 이 실험의 질문이다.**\n\n"
            "**바꾼 것** — `POST /reviews/{reviewId}/view` 신설(`SET key 1 NX EX` 중복 제거 → `INCR`), "
            "`@Scheduled` 1분 주기 배치 반영. 상세 조회(GET)는 실험 C와 같이 순수 읽기. 풀은 8, 부하도 동일.\n\n"
            "**읽는 법** — ① 조회 기록 처리량이 상세 조회와 같은 높이로 붙어 있으면 쓰기 경로가 부하를 다 받아낸 것이다. "
            "② 그런데 **MySQL UPDATE 실행률은 바닥**이어야 한다 — 이 두 선의 간격이 1:1이 끊어진 폭이다. "
            "③ 행 락 발생률 0은 실험 C와 같다. 다만 C는 조회수를 버렸고 **D는 하나도 잃지 않는다.**", h=9)
        tiles = [
            _bstat("감상글 상세 처리량", BASE["hot_rps"], HOT_RPS, unit="reqps", decimals=0),
            _bstat("조회 기록 처리량", "없던 API", VIEW_RPS, unit="reqps", decimals=0,
                   desc="Redis만 건드리는 경로. 상세 조회와 같은 높이여야 부하를 다 받아낸 것이다."),
            _bstat("MySQL UPDATE 실행률", BASE["upd_rate"], UPD_RATE, unit="ops", decimals=2,
                   desc="**이 실험의 숫자.** 리뷰당 1분에 1건이므로 바닥이어야 한다."),
            _bstat("행 락 대기 발생률", BASE["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"])),
            _bstat("성공률 최저", BASE["succ"], SUCC, unit="percentunit", calc="min", steps=grn(0.99)),
            _bstat("조회 기록 p99", "없던 API", VIEW_P99, unit="s", steps=red(TH["shelfeed-p99"]),
                   desc="Redis 왕복만 하므로 상세 조회보다 훨씬 빨라야 한다."),
        ]
        charts = [
            timeseries("1:1이 끊어졌다 — 조회 기록 처리량 vs MySQL UPDATE 실행률", [
                prom(VIEW_RPS, "조회 기록 요청 (/s)", "A"),
                prom(UPD_RATE, "MySQL UPDATE 실행률 (/s)", "B"),
            ], unit="short", w=8, h=10,
                desc="위 선과 아래 선의 간격이 이 설계의 전부다. 조회는 초당 수백 건 들어오는데 "
                     "MySQL 쓰기는 분당 리뷰 수만큼만 나간다. 기준 실행에서는 두 선이 겹쳐 있었다(302/s 대 302/s)."),
            timeseries("지연 — 상세 조회 vs 조회 기록(Redis 경로)", [
                prom(HOT_P99, "감상글 상세 p99", "A"),
                prom(VIEW_P99, "조회 기록 p99", "B"),
            ], unit="s", w=8, h=10, threshold=TH["shelfeed-p99"],
                desc="조회 기록은 DB를 안 거치므로 상세 조회와 자릿수가 달라야 한다. "
                     "여기가 느리면 병목을 Redis로 옮기기만 한 것이다."),
            timeseries("행 락 · HikariCP", [
                prom(LOCK_RATE, "행 락 대기 발생률 (/s)", "A"),
                prom('hikaricp_connections_pending{application="shelfeed"}', "Pending", "B"),
                prom('hikaricp_connections_active{application="shelfeed"}', "Active", "C"),
            ], w=8, h=10, desc="행 락은 0이다. Pending은 남는다 — 병목이 풀로 옮겨간 실험 C와 같은 상태."),
        ]
        desc = "해결안 1단계 검증. 조회수를 Redis로 옮기고 분당 배치로 반영했을 때 쓰기 경로가 부하를 받아내는지 본다."
    elif key == "c":
        title = "Shelfeed — 실험 C · GET에서 조회수 쓰기 제거"
        card = text_note(
            "**가설** — 조회수 UPDATE를 상세 조회 경로에서 아예 빼면 쥘 행 락이 없다. "
            "한 행의 직렬화 천장(1 ÷ 락 보유 시간) 자체가 사라지고, 천장을 정하는 주체가 "
            "**행 락에서 커넥션 풀로** 넘어간다.\n\n"
            "**바꾼 것** — `ReviewService.getReview()`에서 `incrementViewCount()` 한 줄 삭제. "
            "풀은 8 그대로, 부하도 기준 실행과 동일. 조회수는 본문을 3초 이상 본 시점에 "
            "별도 요청으로 올린다는 정의 변경을 전제한다.\n\n"
            "**결과** (정착 폭풍 6분 · 기준 21:38:30~21:44:30, 실험 22:27:30~22:33:30)\n\n"
            "① **락이 완전히 사라졌다** — 행 락 발생률·락 앞 세션·평균 대기·풀 점유율이 모두 **정확히 0**이다"
            "(6분 내내 mean·max·min 전부 0). UPDATE 실행률도 245 → 3.4/s로, 남은 건 배경 트래픽의 다른 쓰기뿐이다.\n\n"
            "② **처리량은 277 → 462/s(+67%)**, MySQL QPS 4,615 → 8,592(+86%), "
            "MySQL Threads running 8.6 → 2.6. DB가 한가해졌다.\n\n"
            "③ **그런데 p99 8.00s, Pending 1,816은 남았다.** 병목이 사라진 게 아니라 **옮겨갔다.** "
            "커넥션 대여율을 이론 상한과 대보면 기준은 521.8/523.8→**99.6% 포화**, 실험 C는 911.0/925.9→**98.4% 포화**다. "
            "풀은 두 경우 다 100% 포화였고, 달라진 건 회전 속도다 — 커넥션 점유가 15.6 → 8.8ms로 줄어 "
            "같은 8개가 522/s → 911/s로 돈다.\n\n"
            "④ **그래서 실험 A의 결론이 뒤집힌다.** 락이 있을 땐 점유 시간의 77%가 락 대기라 커넥션을 늘려도 "
            "다 같이 락 앞에 섰다. 지금은 점유 시간이 순수한 일이라 풀을 늘리면 처리량이 따라 늘어난다 — "
            "실험 F에서 8 → 40으로 늘리자 412 → 512/s(+24%). **A는 틀린 답이 아니라 순서가 틀린 답이었다.** "
            "다만 선형은 아니다. 커넥션 점유가 10 → 46ms로 같이 늘어 상한은 804 → 868/s(+8%)에 그쳤고, "
            "p99(8.5 → 8.7s)와 Pending(1,836 → 1,860)은 그대로였다. 풀 증설은 처리량을 올리지 지연을 내리진 못한다.\n\n"
            "⑤ **덤** — HTTP 요청 472/s인데 커넥션 대여는 911/s, 요청당 1.93회다. "
            "`JwtAuthenticationFilter`가 매 요청 회원을 DB에서 조회한다(digest: `SELECT members` 418,661회 ÷ 요청 214,451건 = 1.95배). "
            "여기를 캐시로 돌리면 같은 풀로 처리량이 한 번 더 두 배가 된다.", h=13)
        tiles = [
            _bstat("감상글 상세 처리량", BASE["hot_rps"], HOT_RPS, unit="reqps", decimals=0,
                   desc="락이 사라졌을 때 이 방향의 상한."),
            _bstat("감상글 상세 p99", BASE["p99"], HOT_P99, unit="s", steps=red(TH["shelfeed-p99"])),
            _bstat("성공률 최저", BASE["succ"], SUCC, unit="percentunit", calc="min", steps=grn(0.99)),
            _bstat("UPDATE 실행률", BASE["upd_rate"], UPD_RATE, unit="ops", decimals=0,
                   desc="0으로 떨어져야 한다. 읽기 경로에서 쓰기가 사라졌다는 뜻."),
            _bstat("행 락 대기 발생률", BASE["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"]), desc="쥘 락이 없으니 0이어야 한다."),
            _bstat("HikariCP Pending", BASE["pending"], 'hikaricp_connections_pending{application="shelfeed"}',
                   decimals=0, steps=red(1)),
        ]
        charts = [
            timeseries("UPDATE 실행률 vs 행 락 대기 발생률", [
                prom(UPD_RATE, "UPDATE 실행률 (ops/s)", "A"),
                prom(LOCK_RATE, "행 락 대기 발생률 (ops/s)", "B"),
            ], unit="short", w=8, h=10, threshold=TH["shelfeed-rowlock-rate"],
                desc="두 선이 바닥에 붙으면 읽기 경로에 쓰기가 없다. 실험 B에서 두 선이 300~400/s였던 것과 대비된다.",
                threshold_label=f"점선 = 알람 ③ 임계 {TH['shelfeed-rowlock-rate']}/s"),
            timeseries("커넥션 대여율 vs 풀 ÷ 점유 시간 (이론 상한)", [
                prom(BORROW_RATE, "커넥션 대여 (회/s)", "A"),
                prom(POOL_CEIL, "풀 크기 ÷ 커넥션 점유 시간 (이론 상한)", "B"),
            ], unit="short", w=8, h=10,
                desc="두 선이 겹치면 풀이 100% 포화라는 뜻이다. 기준(풀 8, 락 있음)도 99.6% 포화였고 "
                     "실험 C도 98.4% 포화다 — 달라진 건 회전 속도다. 커넥션 점유가 15.6ms에서 8.8ms로 "
                     "줄어 같은 8개가 522/s → 911/s로 돈다. 점유 시간이 순수한 일이 된 지금은 풀을 늘리면 "
                     "처리량이 따라 늘어난다(실험 F: 8 → 40, +24%). 다만 점유 시간이 함께 늘어 선형은 아니고, p99는 내려오지 않았다. "
                     "참고: HTTP 요청 1건이 커넥션을 약 2회 빌린다(인증 필터의 회원 조회 + 서비스 트랜잭션)."),
            timeseries("API p99 — 감상글 상세 vs 그 외 전체", [
                prom(HOT_P99, "감상글 상세 p99 (s)", "A"), prom(OTHER_P99, "그 외 API 전체 p99 (s)", "B"),
            ], unit="s", w=8, h=10, threshold=TH["shelfeed-p99"],
                desc="조회수와 무관했던 피드·검색·로그인이 같이 내려오면, 공유 자원을 막던 원인이 제거된 것."),
        ]
        desc = "해결 방향 검증. 읽기 경로에서 쓰기를 빼면 천장이 어디까지 가고, 그다음 병목은 무엇이 되는가."
    elif key == "h":
        B = BASE_C
        title = "Shelfeed — 실험 H · 상세 조회를 캐시에 올리다"
        card = text_note(
            "**가설** — 실험 C에서 락은 사라졌지만 GET 한 건이 여전히 커넥션을 두 번(인증 필터의 회원 조회 + 상세 트랜잭션) "
            "빌려 풀 8개 앞에 줄이 그대로였다. 상세 본문을 캐시에 올리고 인증 회원도 캐시하면 **GET이 커넥션을 아예 빌리지 않는다.** "
            "그러면 커넥션 대여율이 바닥으로, Pending이 0 근처로, p99가 1초 아래로 내려와야 한다.\n\n"
            "**바꾼 것** — 상세 스냅샷을 로컬(5초·단일 실행) → Redis(60초) 두 단으로 캐시. isMine/isLiked/차단은 캐시된 스냅샷과 "
            "회원별 Redis 집합으로 판단해 DB를 안 본다. 인증 필터의 회원 조회도 60초 로컬 캐시. 수정·삭제·좋아요·댓글·차단·조회수 반영 시 무효화. "
            "풀 8, 부하는 실험 C와 동일.\n\n"
            "**읽는 법** — ① 커넥션 대여율이 상세 처리량과 **따로 놀아야** 한다(대여는 배경 트래픽 몫만). "
            "② Pending 타일이 0 근처면 줄 자체가 없어진 것이다. ③ 그 외 API p99가 같이 내려오면 풀을 나눠 쓰던 다른 기능까지 풀린 것이다.", h=8)
        tiles = [
            _bstat("감상글 상세 처리량", B["hot_rps"], HOT_RPS, unit="reqps", decimals=0,
                   desc="캐시 히트는 커넥션을 안 빌리니 상한은 풀이 아니라 톰캣·CPU가 정한다."),
            _bstat("감상글 상세 p99", B["p99"], HOT_P99, unit="s", steps=red(TH["shelfeed-p99"])),
            _bstat("성공률 최저", B["succ"], SUCC, unit="percentunit", calc="min", steps=grn(0.99)),
            _bstat("UPDATE 실행률", B["upd_rate"], UPD_RATE, unit="ops", decimals=0,
                   desc="실험 C와 같아야 한다. 캐시는 읽기만 바꾼다."),
            _bstat("행 락 대기 발생률", B["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"]), desc="실험 C에서 이미 0. 그대로 0."),
            _bstat("HikariCP Pending", B["pending"], 'hikaricp_connections_pending{application="shelfeed"}',
                   decimals=0, steps=red(1), desc="이 실험이 내려야 할 숫자. 0 근처면 GET이 풀을 안 쓴다는 뜻."),
        ]
        charts = [
            timeseries("UPDATE 실행률 vs 행 락 대기 발생률", [
                prom(UPD_RATE, "UPDATE 실행률 (ops/s)", "A"),
                prom(LOCK_RATE, "행 락 대기 발생률 (ops/s)", "B"),
            ], unit="short", w=8, h=10, threshold=TH["shelfeed-rowlock-rate"],
                desc="실험 C와 같은 그림이어야 한다. 캐시는 쓰기 경로를 건드리지 않는다.",
                threshold_label=f"점선 = 알람 ③ 임계 {TH['shelfeed-rowlock-rate']}/s"),
            timeseries("커넥션 대여율 vs 풀 ÷ 점유 시간 (이론 상한)", [
                prom(BORROW_RATE, "커넥션 대여 (회/s)", "A"),
                prom(POOL_CEIL, "풀 크기 ÷ 커넥션 점유 시간 (이론 상한)", "B"),
            ], unit="short", w=8, h=10,
                desc="실험 C에서는 두 선이 겹쳐 있었다(풀 100% 포화). 캐시가 먹히면 대여율이 상한선 훨씬 아래로 떨어져 "
                     "두 선이 벌어진다. 남는 대여는 배경 트래픽(피드·검색·로그인)과 캐시 미스뿐이다."),
            timeseries("API p99 — 감상글 상세 vs 그 외 전체", [
                prom(HOT_P99, "감상글 상세 p99 (s)", "A"), prom(OTHER_P99, "그 외 API 전체 p99 (s)", "B"),
            ], unit="s", w=8, h=10, threshold=TH["shelfeed-p99"],
                desc="상세는 캐시에서 나오니 ms 단위로 떨어져야 하고, 그 외 API도 풀을 독차지하던 상세가 빠졌으니 같이 내려와야 한다."),
        ]
        extra = [("캐시", "GET이 실제로 DB를 안 봤는지", [
            timeseries("캐시 적중률", [prom(CACHE_RATIO, "적중률", "A")], unit="percentunit", w=8, h=8, maxval=1,
                       desc="L1(로컬 5초)+L2(Redis 60초) 적중 ÷ 전체. 폭풍 중 99% 이상이어야 한다. "
                            "미스는 60초마다 한 번(TTL 만료)과 조회수 반영(분당 1회) 무효화 직후에만 난다."),
            timeseries("캐시 적중 vs 미스 (회/s)", [
                prom(CACHE_HIT, "적중 (회/s)", "A"), prom(CACHE_MISS, "미스 (회/s)", "B"),
            ], unit="short", w=8, h=8, desc="미스 선이 바닥에 붙어 있어야 한다. 미스 1건 = DB 읽기 1회."),
            timeseries("커넥션 대여율 vs 감상글 상세 처리량", [
                prom(BORROW_RATE, "커넥션 대여 (회/s)", "A"), prom(HOT_RPS, "감상글 상세 요청 (req/s)", "B"),
            ], unit="short", w=8, h=8,
                desc="실험 C에서는 대여가 요청의 약 2배였다. 캐시가 먹히면 요청은 올라가고 대여는 바닥에 남는다 — 두 선이 갈라지는 폭이 캐시가 대신 받아낸 양이다."),
        ])]
        desc = "해결안 검증. 상세 조회가 커넥션을 빌리지 않게 했을 때 풀 앞의 줄(Pending)과 p99가 실제로 내려오는가."
    else:
        title = "Shelfeed — 실험 B · UPDATE를 트랜잭션 끝으로"
        card = text_note(
            "**가설** — 락 보유 시간 = UPDATE 이후 트랜잭션 나머지 전체다. UPDATE를 커밋 직전으로 옮기면 "
            "보유 시간이 줄고 한 행의 처리량 천장이 올라간다.\n\n"
            "**바꾼 것** — `ReviewService.getReview()`의 `incrementViewCount()`를 첫 줄에서 return 직전으로. "
            "풀은 8 그대로, 부하도 동일.\n\n"
            "**결과** (정착 폭풍 6분 · 기준 21:38:30~21:44:30, 실험 23:37:30~23:43:30)\n\n"
            "① **효과는 컸다** — 처리량 277 → 372/s(+34%), k6 실패율 19.7 → 2.5%(8배 감소), "
            "행 락 평균 대기 27.0 → 11.6ms, 커넥션 점유 15.6 → 10.6ms, MySQL QPS 4,615 → 6,650.\n\n"
            "② **그래도 장애는 사라지지 않았다** — p99 8.18s, Pending 1,860. 천장이 올라갔을 뿐 "
            "2,000 VU는 새 천장도 한참 넘는다. 큐 모델 그대로: 1,860 ÷ 372 ≈ 5.0s.\n\n"
            "③ **경합 건수는 오히려 늘었다** — 행 락 발생률 241 → 357/s. 인기글 요청당 락 대기 0.87 → 0.96건. "
            "**10건 중 9건은 여전히 락 앞에 선다. 달라진 건 서 있는 시간이다.**\n\n"
            "④ **보유 시간은 0.1ms가 아니라 2.7ms까지만 줄었다** — digest 기준 UPDATE 실제 일 0.14ms + COMMIT 0.85ms ≈ 1.0ms인데 "
            "실측 보유 시간은 2.7ms다. 나머지는 앱에 있다 — 응답 객체를 만들고 스프링이 커밋을 보내기까지, "
            "Tomcat 스레드 1,869개가 CPU를 다투는 사이 이 스레드가 밀린다. "
            "**락 보유 시간에는 앱이 커밋을 보내기까지의 시간도 포함된다.**\n\n"
            "⑤ **그리고 UPDATE 실행률은 245 → 363/s로 늘었다.** 쓰기가 줄어서 좋아진 게 아니라 락을 쥐는 시간이 짧아져서 좋아졌다. "
            "읽기 요청이 쓰기를 만드는 구조는 그대로고, 조회수 정의(봇·중복·체류)도 손대지 못했다. "
            "**효과가 없어서 기각하는 게 아니라, 효과가 커서 임시 완화책으로 채택하되 근본 조치는 따로 필요하다.**", h=13)
        tiles = [
            _bstat("감상글 상세 p99", BASE["p99"], HOT_P99, unit="s", steps=red(TH["shelfeed-p99"]),
                   desc="9.09 → 8.18s. 좋아졌지만 평시(수십 ms)와는 거리가 멀다 — 부하가 새 천장도 넘는다."),
            _bstat("성공률 최저", BASE["succ"], SUCC, unit="percentunit", calc="min", steps=grn(0.99)),
            _bstat("HikariCP Pending", BASE["pending"], 'hikaricp_connections_pending{application="shelfeed"}',
                   decimals=0, steps=red(1)),
            _bstat("행 락 대기 발생률", BASE["lock_rate"], LOCK_RATE, unit="ops", decimals=0,
                   steps=red(TH["shelfeed-rowlock-rate"]), desc="예상과 반대로 241 → 357/s로 늘었다. 처리량이 는 만큼 경합 건수도 늘었다 — 짧아진 건 건당 대기(27.0 → 11.6ms)다."),
            _bstat("락 대기 풀 점유율", BASE["share"], SHARE, unit="percent", decimals=0, steps=red(50)),
            _bstat("UPDATE 실행률", BASE["upd_rate"], UPD_RATE, unit="ops", decimals=0,
                   desc="**줄기는커녕 245 → 363/s로 늘었다.** 읽기 경로의 쓰기는 그대로다 — 이 실험이 근본 해결이 아닌 이유."),
        ]
        charts = [
            timeseries("UPDATE 실행률 vs 행 락 대기 발생률", [
                prom(UPD_RATE, "UPDATE 실행률 (ops/s)", "A"),
                prom(LOCK_RATE, "행 락 대기 발생률 (ops/s)", "B"),
            ], unit="short", w=8, h=10, threshold=TH["shelfeed-rowlock-rate"],
                desc="두 선이 나란히 올라간다. 쓰기도 경합도 늘었는데 서비스는 좋아졌다 — "
                     "짧아진 것은 건수가 아니라 락을 쥐고 있는 시간(27.0 → 11.6ms)이다.",
                threshold_label=f"점선 = 알람 ③ 임계 {TH['shelfeed-rowlock-rate']}/s"),
            timeseries("API p99 — 감상글 상세 vs 그 외 전체", [
                prom(HOT_P99, "감상글 상세 p99 (s)", "A"), prom(OTHER_P99, "그 외 API 전체 p99 (s)", "B"),
            ], unit="s", w=8, h=10, threshold=TH["shelfeed-p99"],
                desc="두 선이 9.09/9.26s → 8.18/8.03s로 같이 내려왔지만 임계 1s 위에 남아 있다. 피해가 줄었을 뿐 사라지지 않았다."),
            timeseries("HikariCP 커넥션 — Active / Idle / Pending", [
                prom('hikaricp_connections_pending{application="shelfeed"}', "Pending (개)", "A"),
                prom('hikaricp_connections_active{application="shelfeed"}', "Active (개)", "B"),
                prom('hikaricp_connections_idle{application="shelfeed"}', "Idle (개)", "C"),
            ], w=8, h=10, desc="Active는 여전히 8(=Max), Pending 1,860으로 기준과 같다. 풀은 그대로 고갈됐고 회전만 빨라졌다."),
        ]
        desc = "해결 대안 B 검증. 락 보유 시간이 변수임을 보이되, 쓰기가 남고 장애가 사라지지 않는다는 한계도 같이 보인다."

    rows = [
        (f"실험 {key.upper()} — 가설과 읽는 법", "블로그 절: 어떻게 해결할까", [card]),
        ("기준(실험 C) → 실험 H" if key == "h" else "기준(실측 11) → 실험",
         "타일 제목의 '기준' 값과 비교해서 읽는다", tiles),
        ("증거", "이 실험이 증명해야 할 그림", charts),
    ] + extra
    return {
        "uid": f"shelfeed-exp-{key}", "title": title, "description": desc,
        "tags": ["shelfeed", "view-count", "row-lock", "experiment"],
        "timezone": "browser", "schemaVersion": 39, "version": 0,
        "refresh": "10s", "time": {"from": "now-15m", "to": "now"}, "editable": True,
        "annotations": {"list": [{
            "name": "알람 상태 변화", "datasource": {"type": "datasource", "uid": "grafana"},
            "enable": True, "hide": False, "iconColor": "red",
            "target": {"type": "dashboard", "limit": 100, "matchAny": False, "tags": []},
        }]},
        "panels": _layout(rows),
    }


if __name__ == "__main__":
    dash = build()
    OUT.write_text(json.dumps(dash, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rows = [p for p in dash["panels"] if p["type"] == "row"]
    texts = [p for p in dash["panels"] if p["type"] == "text"]
    viz = [p for p in dash["panels"] if p["type"] not in ("row", "text")]
    print(f"생성: {OUT.relative_to(ROOT)}")
    print(f"  행 {len(rows)} · 패널 {len(viz)} · 텍스트 {len(texts)}")
    print(f"  임계값(알람 룰에서 주입): {TH}")
    print(f"  설정에서 읽음: 풀 상한 {POOL_MAX}, 커넥션 타임아웃 {CONN_TIMEOUT_S}s")

    alerts = build_alerts()
    OUT_ALERTS.write_text(json.dumps(alerts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"생성: {OUT_ALERTS.relative_to(ROOT)}")
    print(f"  패널 {len(alerts['panels'])} (alertlist 1 · 해설 1)")

    for key, path in OUT_EXP.items():
        exp = build_experiment(key)
        path.write_text(json.dumps(exp, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        n = [p for p in exp["panels"] if p["type"] not in ("row", "text")]
        print(f"생성: {path.relative_to(ROOT)}  — {exp['title']}")
        print(f"  패널 {len(n)} (stat {sum(p['type']=='stat' for p in n)} · timeseries {sum(p['type']=='timeseries' for p in n)}) + 해설 1")
