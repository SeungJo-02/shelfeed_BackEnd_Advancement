package com.shelfeed.backend.global.redis;
//리프레시 토큰 저장, 블랙리스트 관리
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisService {
    private final StringRedisTemplate redisTemplate;
    // 데이터가 섞이지 않도록 하는 레디스의 규칙(Redis 암묵적인 룰 )
    private static final String REFRESH_PREFIX          = "auth:refresh:";
    private static final String BLACKLIST_PREFIX         = "auth:blacklist:";
    private static final String EMAIL_CODE_PREFIX        = "auth:email:code:";
    private static final String EMAIL_ATTEMPTS_PREFIX    = "auth:email:attempts:";
    private static final String EMAIL_COOLDOWN_PREFIX    = "auth:email:cooldown:";
    private static final String PW_RESET_PREFIX          = "auth:pwreset:";
    private static final String PW_RESET_COOLDOWN_PREFIX  = "auth:pwreset:cooldown:";
    private static final String LOGIN_ATTEMPTS_PREFIX     = "auth:login:attempts:";
    private static final String OAUTH_STATE_PREFIX       = "auth:oauth:state:";
    private static final String ALADIN_SYNC_PREFIX       = "search:aladin:";

    // Refresh Token : JWT는 서버 강제 무효화 불가하기에 로그인,갱신,로그아웃 시 저장·검증·삭제 형식으로 만들기
    public void saveRefreshToken(Long memberUserId, String refreshToken, long ttlSeconds){//opsForValue: Key-Value로 사용할 것을 정의
        redisTemplate.opsForValue().set(
                REFRESH_PREFIX + memberUserId, refreshToken, ttlSeconds, TimeUnit.SECONDS);
    }
    public String getRefreshToken(Long memberUserId){
        return redisTemplate.opsForValue().get(REFRESH_PREFIX + memberUserId);
    }
    public void deleteRefreshToken(Long memberUserId){
         redisTemplate.delete(REFRESH_PREFIX + memberUserId);
    }

    // Access Token 블랙리스트: 로그아웃 후 만료 전 토큰 재사용 방지
    public void addToBlacklist(String accessToken, long remainingMs){
        redisTemplate.opsForValue().set( // remainingMs 시간 만큼 못쓰게 하면서 시간 지나면 삭제 하도록
                BLACKLIST_PREFIX + accessToken,"1", remainingMs, TimeUnit.MILLISECONDS);
    }
    public boolean isBlacklisted(String accessToken){
        return redisTemplate.hasKey(BLACKLIST_PREFIX + accessToken);
    }

    /**
     * 카운터를 1 올리면서 만료 시간을 같이 건다.
     *
     * <p>INCR과 EXPIRE를 따로 호출하면 그 사이에 앱이 죽었을 때 만료 없는 키가 영구히 남는다.
     * Redis의 eviction 정책이 {@code volatile-lru}(만료 설정된 키만 정리 대상)라 그런 키는
     * 메모리에서 끝까지 빠지지 않는다. 한 스크립트로 묶어 그 틈을 없앤다.
     *
     * <p>호출할 때마다 만료 시간을 새로 걸어 슬라이딩 윈도우로 동작한다(기존 동작과 동일).
     */
    private static final RedisScript<Long> INCR_WITH_TTL_SCRIPT = RedisScript.of(
        "local v = redis.call('INCR', KEYS[1]) " +
        "redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
        "return v",
        Long.class);

    private Long incrementWithTtl(String key, long ttlSeconds) {
        return redisTemplate.execute(INCR_WITH_TTL_SCRIPT, List.of(key), String.valueOf(ttlSeconds));
    }

    //이메일 인증 코드: TTL 5분, 시도 횟수 관리 (5회 초과 시 코드 삭제)
    public void saveEmailCode(String email, String code, long ttlSeconds){
        redisTemplate.opsForValue().set(
                EMAIL_CODE_PREFIX + email, code, ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.delete(EMAIL_ATTEMPTS_PREFIX + email); // 이전 재시도 횟수를 없애기
    }
    public String getEmailCode(String email){
        return redisTemplate.opsForValue().get(EMAIL_CODE_PREFIX + email);
    }
    public void deleteEmailCode(String email) {// 인증 완료 되면 사용하는 용도
        redisTemplate.delete(EMAIL_CODE_PREFIX + email);
        redisTemplate.delete(EMAIL_ATTEMPTS_PREFIX + email);
    }
    public long incrementEmailVerifyAttempts(String email){
        Long count = incrementWithTtl(EMAIL_ATTEMPTS_PREFIX + email, TimeUnit.MINUTES.toSeconds(5));
        return count == null ? 1 : count;
    }

    //재발송 쿨다운: 무한 재시도 막기 위함
    public boolean setResendCooldown(String email, long ttlSeconds) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(EMAIL_COOLDOWN_PREFIX + email,"1",ttlSeconds,TimeUnit.SECONDS));
    }

    // ── 로그인 무차별 대입 방지 ──────────────────────────────
    // 이메일별 로그인 실패 1회 기록. 실패마다 윈도우(windowSeconds)를 갱신(슬라이딩 윈도우). 누적 실패 횟수 반환.
    public long recordLoginFailure(String email, long windowSeconds) {
        Long count = incrementWithTtl(LOGIN_ATTEMPTS_PREFIX + email, windowSeconds);
        return count == null ? 0L : count;
    }

    // 현재 누적 실패 횟수가 maxAttempts 이상이면 잠금 상태로 본다.
    public boolean isLoginLocked(String email, int maxAttempts) {
        String v = redisTemplate.opsForValue().get(LOGIN_ATTEMPTS_PREFIX + email);
        return v != null && Long.parseLong(v) >= maxAttempts;
    }

    // 로그인 성공 시 실패 카운터 초기화.
    public void clearLoginFailures(String email) {
        redisTemplate.delete(LOGIN_ATTEMPTS_PREFIX + email);
    }

    // 비밀번호 재설정 재발송 쿨다운: 쿨다운이 없어 새로 설정되면 true, 이미 쿨다운 중이면 false.
    public boolean setPasswordResetCooldown(String email, long ttlSeconds) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(PW_RESET_COOLDOWN_PREFIX + email,"1",ttlSeconds,TimeUnit.SECONDS));
    }

    //비밀번호 재설정 토큰: UUID, TTL 30분
    public void savePasswordResetToken(String token, String email, long ttlSeconds) {
        redisTemplate.opsForValue().set(PW_RESET_PREFIX + token, email, ttlSeconds, TimeUnit.SECONDS);
    }
    public String getEmailByPasswordResetToken(String token) {
        return redisTemplate.opsForValue().get(PW_RESET_PREFIX + token);
    }
    public void deletePasswordResetToken(String token) {redisTemplate.delete(PW_RESET_PREFIX + token);
    }

    //Google auth state
    public void saveOAuthState(String state, long ttlSeconds) {
        redisTemplate.opsForValue().set(
                OAUTH_STATE_PREFIX + state, "1", ttlSeconds, TimeUnit.SECONDS);
    }

    // 검증 성공 시 즉시 삭제(일회용) → true, 없으면 → false
    public boolean validateAndDeleteOAuthState(String state) {
        String key = OAUTH_STATE_PREFIX + state;
        if (redisTemplate.hasKey(key)) {
            redisTemplate.delete(key);
            return true;
        }
        return false;
    }

    // Refresh Token 원자적 교체: 저장된 토큰이 oldToken과 일치할 때만 newToken으로 교체
    // 불일치 시 저장된 토큰을 삭제(재사용 공격 방어) 후 false 반환
    private static final RedisScript<Long> ROTATE_SCRIPT = RedisScript.of(
        "local cur = redis.call('GET', KEYS[1]) " +
        "if cur == ARGV[1] then " +
        "  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
        "  return 1 " +
        "else " +
        "  redis.call('DEL', KEYS[1]) " +
        "  return 0 " +
        "end",
        Long.class);

    public boolean rotateRefreshToken(Long memberUserId, String oldToken, String newToken, long ttlSeconds) {
        String key = REFRESH_PREFIX + memberUserId;
        Long result = redisTemplate.execute(ROTATE_SCRIPT, List.of(key), oldToken, newToken, String.valueOf(ttlSeconds));
        return Long.valueOf(1L).equals(result);
    }

    // 알라딘 검색 캐싱 여부 확인
    public boolean isAladinQuerySynced(String query) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ALADIN_SYNC_PREFIX + query.toLowerCase()));
    }

    // 알라딘 검색 결과 캐싱 마킹 (TTL: 분 단위)
    public void markAladinQuerySynced(String query, long ttlMinutes) {
        redisTemplate.opsForValue().set(ALADIN_SYNC_PREFIX + query.toLowerCase(), "1", ttlMinutes, TimeUnit.MINUTES);
    }
}
