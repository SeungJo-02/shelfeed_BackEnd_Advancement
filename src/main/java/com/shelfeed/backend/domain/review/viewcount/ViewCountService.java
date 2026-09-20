package com.shelfeed.backend.domain.review.viewcount;

import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 조회수를 읽기 경로에서 떼어낸다.
 *
 * <p>기존에는 {@code GET /reviews/{id}} 안에서 조회수 UPDATE가 돌아 한 행의 X락을 트랜잭션 끝까지
 * 붙들었고, 인기글에 요청이 몰리면 그 한 행에서 직렬화되며 커넥션 풀이 말라붙었다.
 * 여기서는 조회 이벤트를 Redis에 누적만 하고, 스케줄러가 주기적으로 리뷰당 UPDATE 한 건으로 반영한다.
 * 요청 1건이 UPDATE 1건을 만들던 1:1 관계가 끊긴다.
 *
 * <p>중복 제거는 {@code SET key 1 NX EX ttl} 한 명령으로 한다. NX(없을 때만)와 EX(만료)가 한
 * 명령에 묶여 있어, 키를 넣고 만료를 거는 사이에 죽어 만료 없는 키가 남는 일이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewCountService {

    private static final String DEDUP_PREFIX = "view:dedup:";   // view:dedup:{reviewId}:{viewer}
    private static final String COUNT_PREFIX = "view:count:";   // view:count:{reviewId}
    private static final String DIRTY_SET    = "view:dirty";    // 반영 대기 중인 reviewId 집합

    private final StringRedisTemplate redis;
    private final ReviewRepository reviewRepository;
    private final org.springframework.beans.factory.ObjectProvider<ViewEventProducer> producerProvider;

    /** Kafka를 켜면 조회 이벤트가 토픽을 거쳐 컨슈머에서 누적된다. 끄면 요청 스레드가 바로 누적한다. */
    @Value("${shelfeed.view-count.kafka-enabled:false}")
    private boolean kafkaEnabled;

    private ViewEventProducer producer;

    @jakarta.annotation.PostConstruct
    void initProducer() {
        this.producer = kafkaEnabled ? producerProvider.getIfAvailable() : null;
    }

    /** 같은 사람의 같은 글 재조회를 이 시간 동안 한 번으로 친다. 실험 시 짧게 줄여 쓰기 경로 한계를 잰다. */
    @Value("${shelfeed.view-count.dedup-ttl-seconds:1800}")
    private long dedupTtlSeconds;

    /**
     * 조회 이벤트를 받는다. MySQL을 건드리지 않으므로 쥐는 락이 없다.
     *
     * @return 새 조회로 집계되었으면 true, 중복이라 걸러졌으면 false
     */
    public boolean record(Long reviewId, String viewer) {
        String dedupKey = DEDUP_PREFIX + reviewId + ":" + viewer;
        Boolean first = redis.opsForValue()
                .setIfAbsent(dedupKey, "1", dedupTtlSeconds, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(first)) {
            return false;   // TTL 안에 이미 본 사람
        }
        if (kafkaEnabled && producer != null) {
            // 요청 스레드는 여기서 끝난다. 누적은 컨슈머가 한다.
            producer.send(new ViewEvent(UUID.randomUUID().toString(), reviewId, viewer,
                    System.currentTimeMillis()));
        } else {
            accumulate(reviewId);
        }
        return true;
    }

    /** 조회 1건을 Redis 카운터에 누적한다. 직접 경로와 Kafka 컨슈머가 함께 쓴다. */
    public void accumulate(Long reviewId) {
        redis.opsForValue().increment(COUNT_PREFIX + reviewId);
        redis.opsForSet().add(DIRTY_SET, String.valueOf(reviewId));
    }

    /**
     * 누적분을 MySQL에 반영한다. 리뷰 하나당 UPDATE 한 건이므로, 초당 수백 건이 들어와도
     * 주기당 리뷰 수만큼만 쓴다.
     *
     * <p>GETDEL로 카운터를 원자적으로 읽고 비운다. 반영에 실패하면 읽어낸 만큼을 되돌려 놓아
     * 조회수를 잃지 않는다(같은 값이 두 번 더해지는 쪽보다 유실을 피하는 쪽을 택했다).
     */
    @Scheduled(fixedDelayString = "${shelfeed.view-count.flush-interval-ms:60000}")
    @Transactional
    public void flush() {
        Set<String> dirty = redis.opsForSet().members(DIRTY_SET);
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        int reviews = 0;
        long total = 0;
        for (String id : dirty) {
            redis.opsForSet().remove(DIRTY_SET, id);
            String raw = redis.opsForValue().getAndDelete(COUNT_PREFIX + id);
            if (raw == null) {
                continue;
            }
            long delta = Long.parseLong(raw);
            if (delta <= 0) {
                continue;
            }
            try {
                reviewRepository.addViewCount(Long.valueOf(id), delta);
                reviews++;
                total += delta;
            } catch (RuntimeException e) {
                redis.opsForValue().increment(COUNT_PREFIX + id, delta);
                redis.opsForSet().add(DIRTY_SET, id);
                log.warn("[viewCount] 반영 실패 reviewId={} delta={} — Redis로 되돌림", id, delta, e);
            }
        }
        if (reviews > 0) {
            log.info("[viewCount] 반영 완료 — 리뷰 {}건, 조회 {}건 (UPDATE {}회)", reviews, total, reviews);
        }
    }
}
