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
