-- 조회 이벤트 집계 파이프라인.
-- Kafka Engine 이 토픽을 직접 읽고, Materialized View 가 두 방향으로 굴려 넣는다.
--   ① view_events        : 원본 이벤트 (필요할 때 파고들기 위한 보존)
--   ② view_stats_hourly  : 시간·글 단위 집계 (읽기용)
-- 컨슈머 코드가 없다. 수집·가공·저장이 DB 안에서 끝난다.

CREATE DATABASE IF NOT EXISTS shelfeed;

-- ── 큐: Kafka 토픽을 읽는 가상 테이블. SELECT 하면 메시지가 소비되므로 직접 조회하지 않는다.
DROP TABLE IF EXISTS shelfeed.view_events_queue;
CREATE TABLE shelfeed.view_events_queue
(
    eventId  String,
    reviewId UInt64,
    viewer   String,
    viewedAt UInt64          -- epoch millis
)
ENGINE = Kafka
SETTINGS kafka_broker_list    = 'kafka:29092',
         kafka_topic_list     = 'review.viewed',
         kafka_group_name     = 'clickhouse-view-stats',
         kafka_format         = 'JSONEachRow',
         kafka_num_consumers  = 1,
         kafka_max_block_size = 65536,
         kafka_skip_broken_messages = 100;

-- ── 원본 보존. ReplacingMergeTree 로 같은 eventId 가 두 번 와도 한 건으로 수렴한다.
--    (Kafka 는 at-least-once 라 중복 전달이 있을 수 있다)
DROP TABLE IF EXISTS shelfeed.view_events;
CREATE TABLE shelfeed.view_events
(
    event_id  String,
    review_id UInt64,
    viewer    String,
    viewed_at DateTime64(3)
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMMDD(viewed_at)
ORDER BY (review_id, event_id);

DROP TABLE IF EXISTS shelfeed.view_events_mv;
CREATE MATERIALIZED VIEW shelfeed.view_events_mv TO shelfeed.view_events AS
SELECT eventId                              AS event_id,
       reviewId                             AS review_id,
       viewer,
       toDateTime64(viewedAt / 1000, 3)     AS viewed_at
FROM shelfeed.view_events_queue;

-- ── 집계본. 조회수와 순 방문자를 분 단위로 굴려 둔다.
--    uniqState 는 HyperLogLog 상태라 원본을 다 훑지 않고도 순 방문자를 셀 수 있다.
DROP TABLE IF EXISTS shelfeed.view_stats;
CREATE TABLE shelfeed.view_stats
(
    review_id UInt64,
    minute    DateTime,
    views     AggregateFunction(count),
    uniques   AggregateFunction(uniq, String)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMMDD(minute)
ORDER BY (review_id, minute);

DROP TABLE IF EXISTS shelfeed.view_stats_mv;
CREATE MATERIALIZED VIEW shelfeed.view_stats_mv TO shelfeed.view_stats AS
SELECT reviewId                                           AS review_id,
       toStartOfMinute(toDateTime64(viewedAt / 1000, 3))  AS minute,
       countState()                                       AS views,
       uniqState(viewer)                                  AS uniques
FROM shelfeed.view_events_queue
GROUP BY review_id, minute;
