package com.shelfeed.backend.domain.review.viewcount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 조회 이벤트를 배치로 받아 Redis 카운터에 누적한다. MySQL 반영은 {@link ViewCountService#flush()}가 맡는다.
 *
 * <p>Kafka는 at-least-once라 같은 이벤트가 두 번 올 수 있다. {@code eventId}를
 * {@code SET key 1 NX EX} 로 한 번만 통과시켜 중복을 거른다. 메시지에 UUID를 담는 것만으로는
 * 멱등이 되지 않고, 이렇게 소비 측이 기억하고 걸러야 성립한다.
 *
 * <p>kafka-enabled=false(기본값)이면 빈을 만들지 않는다. 그러지 않으면 브로커가 없는
 * 환경에서 리스너 컨테이너가 떠 재접속 실패 로그를 계속 남긴다.
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shelfeed.view-count.kafka-enabled", havingValue = "true")
@RequiredArgsConstructor
public class ViewEventConsumer {

    private static final String SEEN_PREFIX = "view:event:";

    private final StringRedisTemplate redis;
    private final ViewCountService viewCountService;

    private final AtomicLong consumed  = new AtomicLong();
    private final AtomicLong duplicate = new AtomicLong();

    /** 재처리 창. 이 시간을 넘긴 중복은 걸러지지 않으므로 컨슈머 재시작 간격보다 넉넉해야 한다. */
    @Value("${shelfeed.view-count.event-dedup-ttl-seconds:3600}")
    private long eventDedupTtlSeconds;

    @KafkaListener(topics = ViewEventProducer.TOPIC, groupId = "view-count", batch = "true")
    public void consume(List<ViewEvent> events) {
        for (ViewEvent e : events) {
            Boolean first = redis.opsForValue()
                    .setIfAbsent(SEEN_PREFIX + e.eventId(), "1", eventDedupTtlSeconds, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(first)) {
                duplicate.incrementAndGet();
                continue;   // 이미 처리한 이벤트
            }
            viewCountService.accumulate(e.reviewId());
            consumed.incrementAndGet();
        }
    }

    public long consumedCount()  { return consumed.get(); }
    public long duplicateCount() { return duplicate.get(); }
}
