package com.shelfeed.backend.global.jwt;

import com.shelfeed.backend.domain.member.entity.Member;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 jjwt 라이브러리로 토큰 서명→검증 왕복을 검증한다(모킹 없음).
 * AuthServiceTest가 JwtProvider를 mock하므로, jjwt 버전 업그레이드 시
 * 실제 서명/파싱 호환성은 이 테스트로만 보장된다.
 */
@DisplayName("JwtProvider 단위 테스트 (실제 jjwt 서명/검증)")
class JwtProviderTest {

    // HS256은 256비트(32바이트) 이상 시크릿 필요
    private static final String SECRET = "test-secret-key-for-jwt-provider-roundtrip-0123456789";
    private static final long ACCESS_EXP = 3_600_000L;       // 1시간(ms)
    private static final long REFRESH_EXP = 1_209_600_000L;  // 14일(ms)

    private final JwtProvider jwtProvider = new JwtProvider(SECRET, ACCESS_EXP, REFRESH_EXP);

    private Member member() {
        return Member.createLocal(1L, "user@test.com", "encoded", "테스터", "bio");
    }

    @Test
    @DisplayName("액세스 토큰: 서명 후 검증·subject·타입(access)이 왕복으로 일치한다")
    void 액세스_토큰_왕복() {
        String token = jwtProvider.generateAccessToken(member());

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getMemberUserId(token)).isEqualTo(1L);
        assertThat(jwtProvider.isAccessToken(token)).isTrue();
        assertThat(jwtProvider.isRefreshToken(token)).isFalse();
    }

    @Test
    @DisplayName("리프레시 토큰: 서명 후 검증·타입(refresh)이 왕복으로 일치한다")
    void 리프레시_토큰_왕복() {
        String token = jwtProvider.generateRefreshToken(member());

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.isRefreshToken(token)).isTrue();
        assertThat(jwtProvider.isAccessToken(token)).isFalse();
    }

    @Test
    @DisplayName("변조된 토큰은 서명 검증에 실패해 validateToken이 false를 반환한다")
    void 변조_토큰_검증실패() {
        String token = jwtProvider.generateAccessToken(member());
        // 페이로드(첫 '.' 직후) 한 글자를 바꿔 서명 대상 내용을 변조 → 서명 불일치 유발
        int idx = token.indexOf('.') + 3;
        char orig = token.charAt(idx);
        char repl = (orig == 'A') ? 'B' : 'A';
        String tampered = token.substring(0, idx) + repl + token.substring(idx + 1);

        assertThat(jwtProvider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰은 ExpiredJwtException을 던진다")
    void 만료_토큰_예외() {
        JwtProvider expiredProvider = new JwtProvider(SECRET, -1_000L, -1_000L);
        String expired = expiredProvider.generateAccessToken(member());

        assertThatThrownBy(() -> expiredProvider.validateToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
