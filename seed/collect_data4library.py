#!/usr/bin/env python3
"""도서관 정보나루(국립중앙도서관) 인기대출도서 API로 도서 메타데이터를 수집해 CSV로 저장한다.

표준 라이브러리만 사용한다. 사용법은 seed/README.md 참고.

  python3 seed/collect_data4library.py --auth-key $DATA4LIBRARY_KEY \
      --out seed/data/books.csv --kdc 0-9 --from-year 2014 --to-year 2026 \
      --page-size 300 --max-calls 450 --resume --details 0

인증키는 하루 500건까지 호출할 수 있다(그 이상은 정보나루에 서버 IP 등록 필요). 기본 상한 450은 그 여유분이다.
순회 순서는 최신 연도부터(2026→2014), 연도 안에서 KDC 0~9, 페이지 오름차순이다 — 하루 예산으로 서로 다른 책을
최대한 빨리 모으기 위한 순서이며 상태 파일도 이 순서를 그대로 보존한다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import time
from datetime import date
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_BASE_URL = "http://data4library.kr/api"
CSV_COLUMNS = [
    "isbn13", "title", "author", "publisher", "published_year", "class_no",
    "category", "genre", "cover_url", "loan_count", "description",
]
STATE_FILE_NAME = ".collect_state.json"
POLITE_DELAY_SEC = 0.2
BACKOFF_SEC = (2, 4, 8)
ISBN13_RE = re.compile(r"^\d{13}$")


# ── KDC → 카테고리 매핑 ───────────────────────────────────────────────
def load_kdc_mapping(path: str) -> dict[str, str]:
    mapping: dict[str, str] = {}
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            prefix = row["prefix"].strip()
            if prefix:
                mapping[prefix] = row["category"].strip()
    if not mapping:
        raise ValueError(f"empty kdc mapping: {path}")
    return mapping


def map_category(class_no: str | None, mapping: dict[str, str]) -> tuple[str, str]:
    """class_no('813.7')의 숫자만 남긴 뒤 가장 긴 접두어로 매핑한다. 미매핑이면 ('','')."""
    digits = re.sub(r"\D", "", class_no or "")
    for length in range(len(digits), 0, -1):
        cat = mapping.get(digits[:length])
        if cat:
            genre = cat.split("-", 1)[1] if "-" in cat else cat
            return cat, genre
    return "", ""


# ── 응답 파싱 ─────────────────────────────────────────────────────────
def _text(v) -> str:
    """CDATA/공백 정리. dict({'#text': ..})나 list로 오는 경우도 방어."""
    if v is None:
        return ""
    if isinstance(v, dict):
        v = v.get("#text") or v.get("text") or ""
    if isinstance(v, list):
        v = v[0] if v else ""
    s = str(v).strip()
    if s.startswith("<![CDATA[") and s.endswith("]]>"):
        s = s[9:-3].strip()
    return s


def parse_docs(payload: dict) -> list[dict]:
    """loanItemSrch(format=json) 응답에서 도서 dict 목록을 꺼낸다.
    docs 항목이 {'doc': {...}}로 감싸진 형태와 감싸지지 않은 형태 모두 처리한다."""
    resp = payload.get("response", payload)
    docs = resp.get("docs") or []
    out = []
    for item in docs:
        doc = item.get("doc", item) if isinstance(item, dict) else None
        if isinstance(doc, dict):
            out.append(doc)
    return out


def doc_to_row(doc: dict, mapping: dict[str, str]) -> dict | None:
    isbn = _text(doc.get("isbn13"))
    if not ISBN13_RE.match(isbn):
        return None
    class_no = _text(doc.get("class_no"))
    category, genre = map_category(class_no, mapping)
    year = re.sub(r"\D", "", _text(doc.get("publication_year")))[:4]
    try:
        loan = int(_text(doc.get("loan_count")) or 0)
    except ValueError:
        loan = 0
    return {
        "isbn13": isbn,
        "title": _text(doc.get("bookname")),
        "author": _text(doc.get("authors")),
        "publisher": _text(doc.get("publisher")),
        "published_year": year,
        "class_no": class_no,
        "category": category,
        "genre": genre,
        "cover_url": _text(doc.get("bookImageURL")),
        "loan_count": loan,
        "description": "",
    }


def parse_detail(payload: dict) -> dict:
    """srchDtlList(format=json) 응답에서 description 등을 꺼낸다."""
    resp = payload.get("response", payload)
    detail = resp.get("detail") or []
    if isinstance(detail, dict):
        detail = [detail]
    for item in detail:
        book = item.get("book", item) if isinstance(item, dict) else None
        if isinstance(book, dict):
            return {
                "description": _text(book.get("description")),
                "class_no": _text(book.get("class_no")),
                "cover_url": _text(book.get("bookImageURL")),
            }
    return {}


# ── HTTP ─────────────────────────────────────────────────────────────
class ApiClient:
    def __init__(self, base_url: str, auth_key: str, timeout: float = 20.0, sleep=time.sleep):
        self.base_url = base_url.rstrip("/")
        self.auth_key = auth_key
        self.timeout = timeout
        self.calls = 0
        self._sleep = sleep

    def get_json(self, endpoint: str, params: dict) -> dict:
        q = dict(params)
        q["authKey"] = self.auth_key
        q["format"] = "json"
        url = f"{self.base_url}/{endpoint}?{urllib.parse.urlencode(q, doseq=True)}"
        last_err: Exception | None = None
        for attempt in range(len(BACKOFF_SEC) + 1):
            try:
                self.calls += 1
                with urllib.request.urlopen(url, timeout=self.timeout) as r:
                    body = r.read().decode("utf-8")
                self._sleep(POLITE_DELAY_SEC)
                return json.loads(body)
            except urllib.error.HTTPError as e:
                last_err = e
                if e.code == 429 or e.code >= 500:
                    if attempt < len(BACKOFF_SEC):
                        self._sleep(BACKOFF_SEC[attempt])
                        continue
                raise
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
                last_err = e
                if attempt < len(BACKOFF_SEC):
                    self._sleep(BACKOFF_SEC[attempt])
                    continue
                raise
        raise RuntimeError(f"unreachable: {last_err}")


# ── 상태 파일 ─────────────────────────────────────────────────────────
def state_path(out_csv: str) -> str:
    return os.path.join(os.path.dirname(os.path.abspath(out_csv)), STATE_FILE_NAME)


def load_state(path: str) -> dict:
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            st = json.load(f)
        st.setdefault("calls_by_date", {})
        return st
    return {"done": {}, "calls": 0, "details_done": [], "calls_by_date": {}}


def today() -> str:
    return date.today().isoformat()


def record_calls(state: dict, client: "ApiClient", calls_before: int) -> None:
    """클라이언트의 누적 시도 횟수(재시도 포함)를 상태에 반영한다. 한도는 실패한 시도도 소모한다."""
    new_total = calls_before + client.calls
    delta = new_total - state["calls"]
    state["calls"] = new_total
    d = today()
    state["calls_by_date"][d] = state["calls_by_date"].get(d, 0) + delta


def save_state(path: str, state: dict) -> None:
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


# ── CSV ──────────────────────────────────────────────────────────────
def load_existing(out_csv: str) -> dict[str, dict]:
    books: dict[str, dict] = {}
    if os.path.exists(out_csv):
        with open(out_csv, encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                try:
                    row["loan_count"] = int(row.get("loan_count") or 0)
                except ValueError:
                    row["loan_count"] = 0
                books[row["isbn13"]] = row
    return books


def write_csv(out_csv: str, books: dict[str, dict]) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(out_csv)), exist_ok=True)
    tmp = out_csv + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        w.writeheader()
        for isbn in sorted(books, key=lambda k: (-int(books[k]["loan_count"]), k)):
            row = {c: books[isbn].get(c, "") for c in CSV_COLUMNS}
            w.writerow(row)
    os.replace(tmp, out_csv)


def merge(books: dict[str, dict], row: dict) -> None:
    """같은 ISBN이면 loan_count가 큰 쪽을 남기고, 비어 있던 필드는 채운다."""
    cur = books.get(row["isbn13"])
    if cur is None:
        books[row["isbn13"]] = row
        return
    if int(row["loan_count"]) > int(cur["loan_count"]):
        keep_desc = cur.get("description") or row.get("description", "")
        cur.update(row)
        cur["description"] = keep_desc
    else:
        for k, v in row.items():
            if not cur.get(k) and v:
                cur[k] = v


# ── 수집 루프 ─────────────────────────────────────────────────────────
def parse_kdc_arg(s: str) -> list[str]:
    s = s.strip()
    if "-" in s:
        a, b = s.split("-", 1)
        return [str(i) for i in range(int(a), int(b) + 1)]
    return [x.strip() for x in s.split(",") if x.strip()]


def build_windows(years: list[int], kdcs: list[str], windows: str) -> list[tuple[str, str, str, str]]:
    """순회 창 목록. (key, kdc, startDt, endDt). 최신 연도부터, 연도(또는 반기) 안에서 KDC 순.
    같은 순서로 상태 파일에 기록되므로 --resume 시 정확히 다음 창부터 이어진다."""
    out = []
    for year in sorted(years, reverse=True):
        if windows == "half-year":
            periods = [(f"{year}H2", f"{year}-07-01", f"{year}-12-31"), (f"{year}H1", f"{year}-01-01", f"{year}-06-30")]
        else:
            periods = [(str(year), f"{year}-01-01", f"{year}-12-31")]
        for label, start, end in periods:
            for kdc in kdcs:
                out.append((f"{kdc}:{label}", kdc, start, end))
    return out


def next_resume_point(state: dict, windows: list[tuple[str, str, str, str]]) -> str:
    for key, kdc, start, end in windows:
        info = state["done"].get(key)
        if info is None or not info["finished"]:
            page = info["next_page"] if info else 1
            return f"kdc={kdc} {start}~{end} page={page}"
    return "없음 (모든 창 완료)"


def collect(client: ApiClient, books: dict[str, dict], state: dict, mapping: dict[str, str], *,
            windows: list[tuple[str, str, str, str]], page_size: int, max_calls: int,
            state_file: str, out_csv: str, calls_before: int = 0, log=print) -> int:
    """창(kdc × 기간)마다 pageNo를 증가시키며 loanItemSrch를 호출한다. 오늘 호출 상한에 닿으면 멈춘다.
    상한은 재시도를 포함한 실제 시도 횟수 기준이다(실패한 시도도 한도를 소모한다)."""
    added = 0
    for key, kdc, start, end in windows:
        info = state["done"].setdefault(key, {"next_page": 1, "finished": False})
        while not info["finished"]:
            if state["calls_by_date"].get(today(), 0) >= max_calls:
                log(f"[collect] 호출 상한 {max_calls} 도달. --resume 으로 이어서 실행하라.")
                write_csv(out_csv, books)
                save_state(state_file, state)
                return added
            page = info["next_page"]
            payload = client.get_json("loanItemSrch", {
                "startDt": start, "endDt": end,
                "kdc": kdc, "pageNo": page, "pageSize": page_size,
            })
            record_calls(state, client, calls_before)
            docs = parse_docs(payload)
            n_before = len(books)
            for doc in docs:
                row = doc_to_row(doc, mapping)
                if row:
                    merge(books, row)
            added += len(books) - n_before
            log(f"[collect] kdc={kdc} {start}~{end} page={page} docs={len(docs)} total={len(books)} calls={state['calls']}")
            if len(docs) < page_size:
                info["finished"] = True
            else:
                info["next_page"] = page + 1
            if state["calls"] % 10 == 0:
                write_csv(out_csv, books)
                save_state(state_file, state)
    write_csv(out_csv, books)
    save_state(state_file, state)
    return added


def enrich_details(client: ApiClient, books: dict[str, dict], state: dict, *,
                   top_n: int, max_calls: int, state_file: str, out_csv: str,
                   mapping: dict[str, str] | None = None, calls_before: int = 0, log=print) -> int:
    """대출 상위 N권에 srchDtlList로 소개글을 채운다(1권당 1호출). 목록에 class_no가 없던 책은 상세의 class_no로 분류를 다시 매핑한다."""
    if top_n <= 0:
        return 0
    done = set(state.get("details_done", []))
    targets = [b for b in sorted(books.values(), key=lambda b: -int(b["loan_count"]))
               if b["isbn13"] not in done and not b.get("description")][:top_n]
    filled = 0
    for b in targets:
        if state["calls_by_date"].get(today(), 0) >= max_calls:
            log(f"[details] 호출 상한 {max_calls} 도달.")
            break
        payload = client.get_json("srchDtlList", {"isbn13": b["isbn13"]})
        record_calls(state, client, calls_before)
        d = parse_detail(payload)
        if d.get("description"):
            b["description"] = d["description"]
            filled += 1
        if d.get("cover_url") and not b.get("cover_url"):
            b["cover_url"] = d["cover_url"]
        if d.get("class_no") and not b.get("class_no"):
            b["class_no"] = d["class_no"]
            if mapping is not None and not b.get("category"):
                b["category"], b["genre"] = map_category(d["class_no"], mapping)
        done.add(b["isbn13"])
        state["details_done"] = sorted(done)
        if state["calls"] % 10 == 0:
            write_csv(out_csv, books)
            save_state(state_file, state)
    write_csv(out_csv, books)
    save_state(state_file, state)
    return filled


def build_parser() -> argparse.ArgumentParser:
    here = os.path.dirname(os.path.abspath(__file__))
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--auth-key", default=os.environ.get("DATA4LIBRARY_KEY", ""), help="정보나루 인증키 (env DATA4LIBRARY_KEY)")
    p.add_argument("--out", default=os.path.join(here, "data", "books.csv"))
    p.add_argument("--kdc", default="0-9", help="'0-9' 또는 '8,9'")
    p.add_argument("--from-year", type=int, default=2014)
    p.add_argument("--to-year", type=int, default=2026)
    p.add_argument("--page-size", type=int, default=300)
    p.add_argument("--max-calls", type=int, default=450, help="이번 실행에서 허용할 API 호출 수. 인증키 한도가 하루 500건이라 기본 450")
    p.add_argument("--windows", choices=["year", "half-year"], default="year", help="순회 기간 창. half-year 는 호출은 2배, 중복은 줄고 커버리지는 늘어난다")
    p.add_argument("--resume", action="store_true", help="상태 파일이 있으면 이어서 진행")
    p.add_argument("--force", action="store_true", help="기존 CSV·상태 파일을 버리고 처음부터 (기본은 덮어쓰기 거부)")
    p.add_argument("--details", type=int, default=0, help="대출 상위 N권의 소개글을 srchDtlList로 보강")
    p.add_argument("--base-url", default=DEFAULT_BASE_URL)
    p.add_argument("--kdc-map", default=os.path.join(here, "kdc_category.csv"))
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.auth_key:
        print("--auth-key 또는 DATA4LIBRARY_KEY 가 필요하다", file=sys.stderr)
        return 2
    mapping = load_kdc_mapping(args.kdc_map)
    st_path = state_path(args.out)
    if not args.resume and not args.force and os.path.exists(args.out) and os.path.getsize(args.out) > 0:
        print(f"{args.out} 이 이미 있다. 이어서 하려면 --resume, 버리고 새로 하려면 --force 를 주어라.", file=sys.stderr)
        return 3
    state = load_state(st_path) if args.resume else load_state("/nonexistent")
    books = load_existing(args.out) if args.resume else {}
    if not args.resume and os.path.exists(st_path):
        os.remove(st_path)
    client = ApiClient(args.base_url, args.auth_key)
    years = list(range(args.from_year, args.to_year + 1))
    windows = build_windows(years, parse_kdc_arg(args.kdc), args.windows)
    calls_before = state["calls"]
    used_today_before = state["calls_by_date"].get(today(), 0)
    added = collect(client, books, state, mapping, windows=windows, page_size=args.page_size, max_calls=args.max_calls,
                    state_file=st_path, out_csv=args.out, calls_before=calls_before)
    filled = enrich_details(client, books, state, top_n=args.details, max_calls=args.max_calls,
                            state_file=st_path, out_csv=args.out, mapping=mapping, calls_before=calls_before)
    save_state(st_path, state)
    unmapped = sum(1 for b in books.values() if not b.get("category"))
    used_today = state["calls_by_date"].get(today(), 0)
    print(f"[done] books={len(books)} added={added} details_filled={filled} unmapped_category={unmapped} out={args.out}")
    print(f"[summary] 오늘 사용한 호출={used_today - used_today_before} (오늘 누적 {used_today}/{args.max_calls}, 남은 {max(0, args.max_calls - used_today)}, 전체 누적 {state['calls']}, 인증키 한도 500/일) "
          f"· 수집 행={len(books)} · 다음 재개 지점: {next_resume_point(state, windows)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
