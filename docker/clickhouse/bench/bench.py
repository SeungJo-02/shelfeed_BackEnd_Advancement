import re, subprocess, json, statistics, sys, time, datetime
M="backend_project-mysql-perf-1"; CH="backend_project-clickhouse-1"; EID=sys.argv[1]; N=7
def sh(cmd, inp=None): return subprocess.run(cmd, input=inp, capture_output=True, text=True).stdout
def my(sql): return sh(["docker","exec","-i",M,"mysql","-uroot","-proot1234","-N","-B","shelfeed"], inp=sql)
def ch(sql): return sh(["docker","exec","-i",CH,"clickhouse-client","-u","shelfeed","--password","shelfeed","--multiquery"], inp=sql).strip()
Q={
 "q1_top10_1h": ("최근 1시간 가장 많이 본 글 TOP10",
   "SELECT /*bench_q1*/ review_id, COUNT(*) c FROM view_events_bench WHERE viewed_at >= (SELECT MAX(viewed_at) FROM view_events_bench) - INTERVAL 1 HOUR GROUP BY review_id ORDER BY c DESC LIMIT 10",
   "SELECT /*bench_q1*/ review_id, count() c FROM shelfeed.view_events_bench WHERE viewed_at >= (SELECT max(viewed_at) FROM shelfeed.view_events_bench) - INTERVAL 1 HOUR GROUP BY review_id ORDER BY c DESC LIMIT 10"),
 "q2_unique_viewers": ("글별 순 방문자 수 TOP10",
   "SELECT /*bench_q2*/ review_id, COUNT(DISTINCT viewer) u FROM view_events_bench GROUP BY review_id ORDER BY u DESC LIMIT 10",
   "SELECT /*bench_q2*/ review_id, uniqExact(viewer) u FROM shelfeed.view_events_bench GROUP BY review_id ORDER BY u DESC LIMIT 10"),
 "q3_point_lookup": ("event_id 단건 조회",
   f"SELECT /*bench_q3*/ * FROM view_events_bench WHERE event_id = '{EID}'",
   f"SELECT /*bench_q3*/ * FROM shelfeed.view_events_bench WHERE event_id = '{EID}'"),
}
res={"run_at":datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),"rows":385983,"event_id":EID,"queries":{}}
for key,(label,mq,cq) in Q.items():
    # MySQL: 같은 세션에서 실행 후 performance_schema에서 서버측 시간(ps)을 읽는다
    mt=[]
    for i in range(N):
        out=my(f"SET profiling=1;\n{mq};\nSHOW PROFILES;")
        row=[l for l in out.splitlines() if re.match(r"^\d+\t[\d.]+\tSELECT", l)][-1]  # SHOW PROFILES는 주석을 지운다
        mt.append(float(row.split("\t")[1])*1000)  # 초 → ms (서버측 실행 시간)
    # ClickHouse: query_log의 query_duration_ms
    ct=[]
    for i in range(N):
        ch(cq+" FORMAT Null")
        out=ch(f"SYSTEM FLUSH LOGS; SELECT query_duration_ms FROM system.query_log WHERE type='QueryFinish' AND query LIKE '%bench_{key[:2]}%' AND query NOT LIKE '%query_log%' ORDER BY event_time_microseconds DESC LIMIT 1")
        ct.append(float(out.splitlines()[-1]))
    res["queries"][key]={"label":label,"mysql_ms":{"median":round(statistics.median(mt),1),"min":round(min(mt),1),"runs":[round(x,1) for x in mt]},
                         "clickhouse_ms":{"median":round(statistics.median(ct),1),"min":round(min(ct),1),"runs":[round(x,1) for x in ct]},
                         "mysql_sql":mq,"clickhouse_sql":cq}
    print(f"{label:28s} MySQL median {statistics.median(mt):8.1f} ms (min {min(mt):.1f})  |  ClickHouse median {statistics.median(ct):7.1f} ms (min {min(ct):.1f})")
# 저장 용량
mys=my("SELECT ROUND(data_length/1024/1024,2), ROUND(index_length/1024/1024,2) FROM information_schema.tables WHERE table_schema='shelfeed' AND table_name='view_events_bench'").split()
chs=ch("SELECT round(sum(bytes_on_disk)/1024/1024,2), round(sum(data_uncompressed_bytes)/1024/1024,2) FROM system.parts WHERE database='shelfeed' AND table='view_events_bench' AND active").split()
res["storage_mib"]={"mysql_data":float(mys[0]),"mysql_index":float(mys[1]),"mysql_total":round(float(mys[0])+float(mys[1]),2),
                    "clickhouse_on_disk":float(chs[0]),"clickhouse_uncompressed":float(chs[1])}
print(f"{'저장 용량 (MiB)':28s} MySQL {res['storage_mib']['mysql_total']} (데이터 {mys[0]} + 인덱스 {mys[1]})  |  ClickHouse {chs[0]} (압축 전 {chs[1]})")
json.dump(res,open(sys.argv[2],"w"),ensure_ascii=False,indent=1)
