"""수집기 단위 테스트. fixture JSON을 돌려주는 로컬 HTTP 서버로 API를 대신한다.

  python3 -m unittest seed/tests/test_collector.py -v
"""
import csv
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SEED = os.path.dirname(HERE)
sys.path.insert(0, SEED)

import collect_data4library as c  # noqa: E402

FIX_LOAN = os.path.join(SEED, "fixtures", "loanItemSrch.sample.json")
FIX_DETAIL = os.path.join(SEED, "fixtures", "srchDtlList.sample.json")
KDC_MAP = os.path.join(SEED, "kdc_category.csv")


class FixtureHandler(BaseHTTPRequestHandler):
    """loanItemSrch: pageNo/pageSize로 fixture docs를 잘라 준다. kdc=8, 첫 해에만 데이터가 있고 나머지는 빈 페이지.
    한 번은 429를 돌려 백오프 경로를 태운다."""
    loan = json.load(open(FIX_LOAN, encoding="utf-8"))
    detail = json.load(open(FIX_DETAIL, encoding="utf-8"))
    calls = []
    fail_once = {"429": True}

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        FixtureHandler.calls.append((u.path, q))
        if q.get("authKey") != "test-key":
            self.send_response(401); self.end_headers(); return
        if u.path.endswith("/loanItemSrch"):
            if FixtureHandler.fail_once["429"]:
                FixtureHandler.fail_once["429"] = False
                self.send_response(429); self.end_headers(); return
            docs = FixtureHandler.loan["response"]["docs"]
            if not (q.get("kdc") == "8" and q.get("startDt", "").startswith("2014")):
                docs = []
            page, size = int(q.get("pageNo", 1)), int(q.get("pageSize", 200))
            chunk = docs[(page - 1) * size: page * size]
            body = {"response": {"request": q, "resultNum": len(chunk), "docs": chunk}}
        elif u.path.endswith("/srchDtlList"):
            body = FixtureHandler.detail
        else:
            self.send_response(404); self.end_headers(); return
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class CollectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.port}/api"
        c.POLITE_DELAY_SEC = 0
        c.BACKOFF_SEC = (0, 0, 0)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FixtureHandler.calls.clear()
        FixtureHandler.fail_once["429"] = True
        self.tmp = tempfile.TemporaryDirectory()
        self.out = os.path.join(self.tmp.name, "books.csv")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *extra):
        return c.main(["--auth-key", "test-key", "--base-url", self.base, "--out", self.out,
                       "--kdc", "8", "--from-year", "2014", "--to-year", "2015",
                       "--page-size", "50", "--kdc-map", KDC_MAP, *extra])

    def read_csv(self):
        with open(self.out, encoding="utf-8", newline="") as f:
            return list(csv.DictReader(f))

    def test_mapping_longest_prefix(self):
        m = c.load_kdc_mapping(KDC_MAP)
        self.assertEqual(c.map_category("813.7", m), ("국내도서-소설/시/희곡", "소설/시/희곡"))
        self.assertEqual(c.map_category("814.7", m), ("국내도서-에세이", "에세이"))
        self.assertEqual(c.map_category("325.211", m), ("국내도서-경제 경영", "경제 경영"))
        self.assertEqual(c.map_category("005.133", m), ("국내도서-IT 모바일", "IT 모바일"))
        self.assertEqual(c.map_category("657.1", m), ("국내도서-만화/라이트노벨", "만화/라이트노벨"))
        self.assertEqual(c.map_category("", m), ("", ""))

    def test_full_run_writes_csv_and_dedupes(self):
        self.assertEqual(self.run_cli("--max-calls", "50"), 0)
        rows = self.read_csv()
        # fixture 123건 중: ISBN 없는 1건 제외, 중복 ISBN 1건 병합, 감싸지지 않은 1건 포함 → 121건
        self.assertEqual(len(rows), 121)
        self.assertEqual(rows[0]["loan_count"], "77777", "중복 ISBN은 loan_count 큰 쪽이 남는다")
        self.assertTrue(all(len(r["isbn13"]) == 13 for r in rows))
        self.assertTrue(any(r["isbn13"] == "9791100000001" for r in rows), "감싸지지 않은 doc도 처리")
        cats = {r["category"] for r in rows}
        self.assertIn("국내도서-소설/시/희곡", cats)
        self.assertIn("국내도서-IT 모바일", cats)
        self.assertEqual(set(rows[0].keys()), set(c.CSV_COLUMNS))
        # 429 한 번 → 재시도됐으므로 loanItemSrch 호출은 (3 페이지 + 빈 2015 1회) + 1
        loan_calls = [x for x in FixtureHandler.calls if x[0].endswith("loanItemSrch")]
        self.assertEqual(len(loan_calls), 5)
        self.assertTrue(os.path.exists(c.state_path(self.out)))

    def test_resume_continues_from_state_and_respects_budget(self):
        # 순회 순서는 최신 연도 먼저: 2015(빈 페이지 1회) → 2014 1~3페이지.
        # 1차: 시도 4번만 허용 → 429(1) + 2015(1) + 2014 p1·p2 = 4 시도에서 멈춘다 (실패한 시도도 한도를 소모한다)
        self.assertEqual(self.run_cli("--max-calls", "4"), 0)
        st = json.load(open(c.state_path(self.out), encoding="utf-8"))
        self.assertEqual(st["calls"], 4)
        self.assertEqual(st["calls_by_date"][c.today()], 4)
        self.assertTrue(st["done"]["8:2015"]["finished"])
        self.assertFalse(st["done"]["8:2014"]["finished"])
        self.assertEqual(st["done"]["8:2014"]["next_page"], 3)
        self.assertEqual(list(st["done"].keys()), ["8:2015", "8:2014"], "상태 파일도 최신 연도 순")
        n1 = len(self.read_csv())
        self.assertEqual(n1, 100)
        # 2차: --resume 으로 이어서 → 2014 3페이지만 (하루 한도는 오늘 누적 기준이므로 50 으로 올려야 진행)
        FixtureHandler.calls.clear()
        self.assertEqual(self.run_cli("--max-calls", "50", "--resume"), 0)
        st = json.load(open(c.state_path(self.out), encoding="utf-8"))
        self.assertTrue(st["done"]["8:2014"]["finished"])
        self.assertEqual(st["calls"], 5, "1차 4 시도(429 포함) + 2차 1 시도")
        pages = [(q.get("startDt"), q.get("pageNo")) for p, q in FixtureHandler.calls if p.endswith("loanItemSrch")]
        self.assertEqual(pages, [("2014-01-01", "3")], "2014 3페이지부터 이어서")
        self.assertEqual(len(self.read_csv()), 121)
        # 같은 날 한도 소진 상태에서 --resume: 오늘 누적 6 ≥ max-calls 5 → 호출 0
        FixtureHandler.calls.clear(); FixtureHandler.fail_once["429"] = False
        self.assertEqual(self.run_cli("--max-calls", "4", "--resume"), 0)
        self.assertEqual(FixtureHandler.calls, [])
        # 3차: 다 끝난 상태에서 resume → 호출 0
        FixtureHandler.calls.clear()
        self.assertEqual(self.run_cli("--max-calls", "50", "--resume"), 0)
        self.assertEqual(FixtureHandler.calls, [])

    def test_details_enrich_top_n(self):
        self.assertEqual(self.run_cli("--max-calls", "50", "--details", "1"), 0)
        rows = self.read_csv()
        self.assertTrue(rows[0]["description"].startswith("섬세한 감수성"))
        self.assertEqual(sum(1 for r in rows if r["description"]), 1)
        st = json.load(open(c.state_path(self.out), encoding="utf-8"))
        self.assertEqual(st["details_done"], [rows[0]["isbn13"]])

    def test_windows_order_and_half_year(self):
        w = c.build_windows([2014, 2015], ["8", "9"], "year")
        self.assertEqual([x[0] for x in w], ["8:2015", "9:2015", "8:2014", "9:2014"])
        h = c.build_windows([2015], ["8"], "half-year")
        self.assertEqual([(x[0], x[2], x[3]) for x in h],
                         [("8:2015H2", "2015-07-01", "2015-12-31"), ("8:2015H1", "2015-01-01", "2015-06-30")])
        st = {"done": {"8:2015H2": {"next_page": 1, "finished": True}, "8:2015H1": {"next_page": 4, "finished": False}}}
        self.assertEqual(c.next_resume_point(st, h), "kdc=8 2015-01-01~2015-06-30 page=4")
        self.assertEqual(c.next_resume_point({"done": {k: {"next_page": 1, "finished": True} for k, *_ in h}}, h), "없음 (모든 창 완료)")

    def test_default_max_calls_is_450(self):
        self.assertEqual(c.build_parser().parse_args([]).max_calls, 450)

    def test_missing_key_exits_2(self):
        self.assertEqual(c.main(["--auth-key", "", "--out", self.out]), 2)

    def test_refuses_to_overwrite_without_resume_or_force(self):
        self.assertEqual(self.run_cli("--max-calls", "50"), 0)
        FixtureHandler.fail_once["429"] = False
        self.assertEqual(self.run_cli("--max-calls", "50"), 3, "기존 CSV 가 있으면 거부")
        self.assertEqual(self.run_cli("--max-calls", "50", "--force"), 0, "--force 면 새로 시작")
        st = json.load(open(c.state_path(self.out), encoding="utf-8"))
        self.assertEqual(st["calls"], 4)

    def test_details_backfills_class_no_and_category(self):
        m = c.load_kdc_mapping(KDC_MAP)
        books = {"9788900000007": {"isbn13": "9788900000007", "class_no": "", "category": "", "genre": "", "loan_count": 9, "description": ""}}
        state = {"done": {}, "calls": 0, "details_done": [], "calls_by_date": {}}
        client = c.ApiClient(self.base, "test-key")
        c.enrich_details(client, books, state, top_n=1, max_calls=10, state_file=c.state_path(self.out), out_csv=self.out, mapping=m)
        b = books["9788900000007"]
        self.assertEqual(b["class_no"], "813.7")
        self.assertEqual((b["category"], b["genre"]), ("국내도서-소설/시/희곡", "소설/시/희곡"))


if __name__ == "__main__":
    unittest.main()
