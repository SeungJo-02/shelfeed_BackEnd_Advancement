package com.shelfeed.backend.global.jwt;
//요청에서 JWT를 검증하고 SecurityContext에 사용자 정보 저장
import com.shelfeed.backend.global.redis.RedisService;
import com.shelfeed.backend.global.security.CustomUserDetailsService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final CustomUserDetailsService userDetailsService;
    private final RedisService redisService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException{
        String token = extractToken(request);

        if (StringUtils.hasText(token)){
            try{
                // validateToken(서명/만료) + access 타입 확인(refresh 토큰을 access로 사용 차단) + 블랙리스트 미등록
                if (jwtProvider.validateToken(token) && jwtProvider.isAccessToken(token) && !redisService.isBlacklisted(token)){
                    Long memberUserId = jwtProvider.getMemberUserId(token); // 회원 번호 ID 꺼내기
                    UserDetails userDetails = userDetailsService.loadUserByUsername(String.valueOf(memberUserId)); //회원 찾기

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null , userDetails.getAuthorities());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (ExpiredJwtException e){
                // 토큰 만료 시 명시적 401 처리
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // HTTP 401
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");

                // 프론트엔드가 파싱하기 쉬운 JSON 형태로 변환
                String errorMessage = "{\"errorCode\": \"TOKEN_EXPIRED\", \"message\": \"액세스 토큰이 만료되었습니다. 토큰을 재발급해 주세요.\"}";
                response.getWriter().write(errorMessage);
                // 필터 체인 진행 중단
                return;
            } catch (JwtException | IllegalArgumentException e){
                // 토큰 만료 시 명시적 401 처리
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                // 프론트엔드가 파싱하기 쉬운 JSON 형태로 변환
                String errorMessage = "{\"errorCode\": \"INVALID_TOKEN\", \"message\": \"유효하지 않은 토큰입니다.\"}";
                response.getWriter().write(errorMessage);
                // 필터 체인 진행 중단
                return;
            }
        }
        filterChain.doFilter(request,response);
    }

    private String extractToken(HttpServletRequest request){
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        } return null;
    }

}
