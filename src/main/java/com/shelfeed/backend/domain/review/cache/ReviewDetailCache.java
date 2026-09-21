package com.shelfeed.backend.domain.review.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.shelfeed.backend.global.cache.AfterCommit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 감상 상세 스냅샷을 2단으로 캐싱한다. 적중하면 이 요청은 커넥션을 한 개도 빌리지 않는다.
 *
 * <p>[실험 H] 실험 C에서 조회수 쓰기를 빼고도 GET 한 건이 커넥션을 2개 빌렸다(인증 1 + 조회 1).
 * 풀이 8개뿐이라 2,000 VU에서는 대기열만 쌓였다. 병목이 "쿼리가 느려서"가 아니라 "커넥션을
 * 빌리기 때문"이었으므로, 빌리는 횟수를 0으로 만드는 쪽으로 간다.
 *
 * <p>L1은 프로세스 안의 Caffeine, L2는 Redis다. 둘로 나눈 이유는 각자 막는 것이 다르다.
 * <ul>
 *   <li>L2(Redis, 기본 60초)는 DB 조회 자체를 없앤다. 인스턴스가 여러 대여도 공유된다.</li>
 *   <li>L1(로컬, 기본 5초)은 Redis 왕복마저 없앤다. 짧게 잡아 무효화 지연을 5초로 묶는다.</li>
 * </ul>
 *
 * <p>미스가 몰릴 때가 위험하다. TTL이 끊기는 순간 같은 글을 보던 수천 요청이 동시에 DB로
 * 쏟아지면 캐시가 없는 것보다 나빠진다({@code cache stampede}). L1을
 * {@code get(key, loader)}로 채워 같은 키의 동시 미스를 한 번의 적재로 묶는다. 한 스레드만
 * 로더를 돌고 나머지는 그 결과를 기다린다.
 *
 * <p>무효화는 Redis를 지우고 L1을 버린다. 다른 인스턴스의 L1까지 즉시 지울 수는 없어서,
 * 그쪽은 최대 {@code local-ttl-seconds}만큼 옛 값을 보여준다. 조회수·좋아요 수가 몇 초 늦는
 * 것은 감수하고 커넥션 0을 택했다.
 */
@Slf4j
@Component
public class ReviewDetailCache {

    private static final String KEY_PREFIX = "review:detail:";   // review:detail:{reviewId}

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Cache<Long, ReviewDetailSnapshot> local;

    /** 끄면 로더를 그대로 호출한다 — 실험 C를 코드 변경 없이 다시 재현하기 위한 스위치. */
    private final boolean enabled;
    private final long ttlSeconds;

    private final Counter l1Hit;
    private final Counter l2Hit;
    private final Counter miss;

    public ReviewDetailCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${shelfeed.review-cache.enabled:true}") boolean enabled,
            @Value("${shelfeed.review-cache.ttl-seconds:60}") long ttlSeconds,
            @Value("${shelfeed.review-cache.local-ttl-seconds:5}") long localTtlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
        this.local = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(localTtlSeconds, TimeUnit.SECONDS)
                .build();
        this.l1Hit = counter(meterRegistry, "l1_hit");
        this.l2Hit = counter(meterRegistry, "l2_hit");
        this.miss = counter(meterRegistry, "miss");
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("review.detail.cache")
                .description("감상 상세 캐시 조회 결과")
                .tag("result", result)
                .register(registry);
    }

    /**
     * L1 → L2 → 로더 순으로 찾는다. 로더가 던진 예외(삭제된 글 등)는 그대로 올리고 아무것도 담지 않는다.
     */
    public ReviewDetailSnapshot get(Long reviewId, Supplier<ReviewDetailSnapshot> dbLoader) {
        if (!enabled) {
            return dbLoader.get();
        }

        // get(key, loader)는 적중인지 적재인지 알려주지 않아, 적중 계측을 위해 먼저 들여다본다.
        ReviewDetailSnapshot hit = local.getIfPresent(reviewId);
        if (hit != null) {
            l1Hit.increment();
            return hit;
        }

        // 여기부터가 단일 적재 구간 — 같은 id의 동시 미스는 한 스레드만 통과한다.
        return local.get(reviewId, id -> {
            ReviewDetailSnapshot fromRedis = readRedis(id);
            if (fromRedis != null) {
                l2Hit.increment();
                return fromRedis;
            }
            ReviewDetailSnapshot loaded = dbLoader.get();
            miss.increment();
            writeRedis(id, loaded);
            return loaded;
        });
    }

    /** 스냅샷이 낡았다고 알린다. 트랜잭션 중이면 커밋 이후로 미룬다. */
    public void evict(Long reviewId) {
        if (!enabled || reviewId == null) {
            return;
        }
        AfterCommit.run(() -> {
            redis.delete(KEY_PREFIX + reviewId);
            local.invalidate(reviewId);
        });
    }

    private ReviewDetailSnapshot readRedis(Long reviewId) {
        String json;
        try {
            json = redis.opsForValue().get(KEY_PREFIX + reviewId);
        } catch (RuntimeException e) {
            // Redis가 죽어도 조회는 계속돼야 한다 — 미스로 떨어뜨려 DB로 간다.
            log.warn("[reviewCache] Redis 조회 실패 reviewId={} — DB로 우회", reviewId, e);
            return null;
        }
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReviewDetailSnapshot.class);
        } catch (Exception e) {
            // 스냅샷 모양이 바뀐 배포 직후 등 — 못 읽으면 버리고 새로 담는다.
            log.warn("[reviewCache] 스냅샷 역직렬화 실패 reviewId={} — 폐기", reviewId, e);
            redis.delete(KEY_PREFIX + reviewId);
            return null;
        }
    }

    private void writeRedis(Long reviewId, ReviewDetailSnapshot snapshot) {
        try {
            redis.opsForValue().set(
                    KEY_PREFIX + reviewId,
                    objectMapper.writeValueAsString(snapshot),
                    ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[reviewCache] 스냅샷 적재 실패 reviewId={}", reviewId, e);
        }
    }
}
