package com.shelfeed.backend.global.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * "내가 누른 좋아요"와 "나와 차단 관계인 사람"을 회원별 Redis SET으로 들고 있는다.
 *
 * <p>감상 상세 응답에서 사람마다 달라지는 값은 isMine·isLiked·차단 여부 셋뿐이다. isMine은
 * 스냅샷의 소유자 ID와 비교하면 끝이라 조회가 필요 없고, 나머지 둘이 DB 조회를 남긴다.
 * 그 둘을 회원 단위로 한 번 모아두면 이후 조회는 SISMEMBER 한 번으로 끝난다.
 *
 * <p>"키가 없다"와 "빈 집합이다"를 구분해야 한다. 좋아요를 한 번도 안 누른 회원은 집합이
 * 비는데, 그걸 미적재로 읽으면 매 요청마다 DB를 친다. 센티넬 {@code "0"}을 같이 넣어
 * 빈 집합도 키가 존재하게 만든다(회원 ID는 1부터라 0과 겹치지 않는다).
 *
 * <p>존재 확인과 소속 확인을 한 스크립트로 묶어 왕복을 한 번으로 줄인다. 따로 호출하면
 * EXISTS와 SISMEMBER 사이에 키가 만료돼 "없는 회원"으로 읽히는 틈도 생긴다.
 */
@Slf4j
@Component
public class MemberRelationCache {

    private static final String LIKED_PREFIX  = "member:liked:";    // member:liked:{memberId}
    private static final String BLOCKS_PREFIX = "member:blocks:";   // member:blocks:{memberUserId}
    private static final String SENTINEL = "0";

    /** 미적재면 -1, 적재됐고 없으면 0, 있으면 1. */
    private static final RedisScript<Long> MEMBER_OF_LOADED_SET = RedisScript.of(
            "if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end " +
            "return redis.call('SISMEMBER', KEYS[1], ARGV[1])",
            Long.class);

    private final StringRedisTemplate redis;
    private final long ttlSeconds;

    public MemberRelationCache(
            StringRedisTemplate redis,
            @Value("${shelfeed.review-cache.relation-ttl-seconds:600}") long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = ttlSeconds;
    }

    /** memberId(PK) 기준. dbLoader는 그 회원이 좋아요한 reviewId 전부를 돌려준다. */
    public boolean isLiked(Long memberId, Long reviewId, Supplier<Set<Long>> dbLoader) {
        return contains(LIKED_PREFIX + memberId, reviewId, dbLoader);
    }

    /** memberUserId(공개 ID) 기준. dbLoader는 양방향 차단 상대의 공개 ID 전부를 돌려준다. */
    public boolean isBlockedWith(Long memberUserId, Long otherMemberUserId, Supplier<Set<Long>> dbLoader) {
        return contains(BLOCKS_PREFIX + memberUserId, otherMemberUserId, dbLoader);
    }

    public void evictLiked(Long memberId) {
        evict(LIKED_PREFIX + memberId);
    }

    public void evictBlocks(Long memberUserId) {
        evict(BLOCKS_PREFIX + memberUserId);
    }

    private boolean contains(String key, Long value, Supplier<Set<Long>> dbLoader) {
        Long result;
        try {
            result = redis.execute(MEMBER_OF_LOADED_SET, List.of(key), String.valueOf(value));
        } catch (RuntimeException e) {
            // Redis가 죽으면 캐시를 건너뛰고 DB로 판단한다 — 조회 자체가 막히면 안 된다.
            log.warn("[relationCache] Redis 조회 실패 key={} — DB로 우회", key, e);
            return dbLoader.get().contains(value);
        }
        if (result != null && result >= 0) {
            return result == 1L;
        }
        Set<Long> ids = dbLoader.get();
        load(key, ids);
        return ids.contains(value);
    }

    private void load(String key, Set<Long> ids) {
        try {
            String[] members = new String[ids.size() + 1];
            members[0] = SENTINEL;   // 빈 집합도 "적재됨"으로 남긴다
            int i = 1;
            for (Long id : ids) {
                members[i++] = String.valueOf(id);
            }
            redis.opsForSet().add(key, members);
            redis.expire(key, ttlSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            log.warn("[relationCache] 적재 실패 key={}", key, e);
        }
    }

    private void evict(String key) {
        AfterCommit.run(() -> {
            try {
                redis.delete(key);
            } catch (RuntimeException e) {
                log.warn("[relationCache] 무효화 실패 key={}", key, e);
            }
        });
    }
}
