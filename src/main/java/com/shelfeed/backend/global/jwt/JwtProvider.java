package com.shelfeed.backend.global.jwt;
//JWT 액세스/리프레시 토큰 생성, 검증 등등
import com.shelfeed.backend.domain.member.entity.Member;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtProvider {

    // 토큰 타입 클레임 — access 토큰과 refresh 토큰을 구분해 혼용(refresh를 access로 사용 등)을 차단한다.
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey secretKey;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtProvider( // @Value yml 파일 속 데이터 안전하게 가져오는 어노테이션
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiration}") long accessExpiration,
            @Value("${jwt.refresh-expiration}") long refreshExpiration
    ) {//Keys.hmacShaKeyFor : HMAC-SHA 알고리즘에 최적화된 객체로 뱐환, getBytes(StandardCharsets.UTF_8) 계산을 위한 타입 전환
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    // Access Token 생성
    public String generateAccessToken(Member member) {
        return Jwts.builder()
                .subject(String.valueOf(member.getMemberUserId()))
                .claim("role", member.getRole().name())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiration))
                .signWith(secretKey)
                .compact();
    }

    // Refresh Token 생성
    public String generateRefreshToken(Member member) {
        return Jwts.builder()
                .subject(String.valueOf(member.getMemberUserId()))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(secretKey)
                .compact();
    }

    // 토큰 유효성 검증
    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (ExpiredJwtException e) {// 만료라면
            throw e; // 호출한 쪽으로 에러 전송
        } catch (JwtException | IllegalArgumentException e) {//토큰 수정 | 공백 이라면
            return false;
        }
    }

    // 토큰에서 memberUserId 추출
    public Long getMemberUserId(String token) {
        return Long.parseLong(getClaims(token).getSubject());
    }

    // access 토큰 여부 — 인증 필터가 refresh 토큰을 Bearer access로 받는 것을 차단하는 데 사용
    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(getClaims(token).get(CLAIM_TYPE, String.class));
    }

    // refresh 토큰 여부 — 토큰 갱신 시 access 토큰을 refresh로 쓰는 것을 차단하는 데 사용
    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(getClaims(token).get(CLAIM_TYPE, String.class));
    }

    // 토큰 남은 만료 시간 (ms) - 로그아웃 시 Redis 블랙리스트 TTL 용도
    public long getRemainingExpiryMs(String token) {
        Date expiration = getClaims(token).getExpiration();
        return expiration.getTime() - System.currentTimeMillis();
    }

    // Access Token 만료 시간 반환 (초)
    public long getAccessTokenExpiresIn() {
        return accessExpiration / 1000;
    }

    // Refresh Token 만료 시간 반환 (초)
    public long getRefreshTokenExpiresIn() {
        return refreshExpiration / 1000;
    }

    // Claims 파싱 (내부 공통 메서드)
    private Claims getClaims(String token) {
        return Jwts.parser()// 도구 꺼내기
                .verifyWith(secretKey)// 비밀키 장착
                .build()//조립
                .parseSignedClaims(token)// 토큰 넣고 작동
                .getPayload();// 반환
    }
}
