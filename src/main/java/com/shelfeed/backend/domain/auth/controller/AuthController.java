package com.shelfeed.backend.domain.auth.controller;

import com.shelfeed.backend.domain.auth.dto.internal.AuthTokenResult;
import com.shelfeed.backend.domain.auth.dto.request.*;
import com.shelfeed.backend.domain.auth.dto.response.*;
import com.shelfeed.backend.domain.auth.service.AuthService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final JwtProvider jwtProvider;

    // 1. 이메일 회원가입  POST /api/v1/auth/signup
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request,HttpServletResponse response) {// 유효성 검사 + JSON 형태의 요청 본문 DTO로 변환
                AuthTokenResult.Signup result = authService.signup(request);
                setRefreshTokenCookie(response, result.refreshToken());
                HttpStatus status = HttpStatus.CREATED;
                return ResponseEntity
                        .status(status)
                        .body(ApiResponse.success(status.value(),"회원가입 완료", result.response()));
    }

    // 2. 이메일 인증코드 확인  POST /api/v1/auth/email/verify
    @PostMapping("/email/verify")
    public ApiResponse<EmailVerifyResponse> verifyEmail(
            @Valid @RequestBody EmailVerifyRequest request){
        return ApiResponse.success(200, "이메일 인증이 완료되었습니다.", authService.verifyEmail(request));
    }

    // 3. 이메일 인증 코드 재발송  POST /api/v1/auth/email/resend
    @PostMapping("/email/resend")
    public ApiResponse<Void>resendEmailCode(
            @Valid@RequestBody EmailResendRequest request){
        authService.resendEmailCode(request);
        return ApiResponse.success(200, "인증 코드가 재발송되었습니다.");
    }

    // 4. 이메일 로그인  POST /api/v1/auth/login
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid@RequestBody LoginRequest request, HttpServletResponse response){
        AuthTokenResult.Login result = authService.login(request);
        setRefreshTokenCookie(response, result.refreshToken());
        return ApiResponse.success(200, "로그인 성공", result.response());
    }

    // 5. Google OAuth 로그인 URL  GET /api/v1/auth/oauth2/google
    @GetMapping("/oauth2/google")
    public ApiResponse<OAuthLoginUrlResponse> getGoogleLoginUrl() {
        return ApiResponse.success(200, "구글 로그인 URL이 성공적으로 발급되었습니다.",
                authService.getGoogleLoginUrl());
    }

    // 6. Google OAuth 로그인 완료  POST /api/v1/auth/oauth2/google/login
    @PostMapping("/oauth2/google/login")
    public ApiResponse<GoogleLoginResponse> googleLogin(
            @Valid @RequestBody OAuthTokenRequest request,
            HttpServletResponse response) {
        AuthTokenResult.GoogleLogin result = authService.googleLogin(request);
        setRefreshTokenCookie(response, result.refreshToken());
        return ApiResponse.success(200, "로그인 성공", result.response());
    }

    // 7. 토큰 갱신  POST /api/v1/auth/token/refresh
    @PostMapping("/token/refresh")
    public ApiResponse<TokenRefreshResponse> refresh(
            @CookieValue(name = "refreshToken", required = false) String refreshToken,//HTTP 쿠키 값을 컨트롤러 메서드의 파라미터로 쉽게 추출
            HttpServletResponse response) {
        AuthTokenResult.Refresh result = authService.refresh(refreshToken);
        setRefreshTokenCookie(response, result.newRefreshToken());
        return ApiResponse.success(200, "토큰 갱신 성공", result.response());
    }

    // 8. 로그아웃  POST /api/v1/auth/logout
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader("Authorization") String bearerToken,
            @CookieValue(name = "refreshToken", required = false) String refreshToken,
            HttpServletResponse response) {
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")){
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        String accessToken = bearerToken.substring(7);
        authService.logout(userDetails.getMember().getMemberUserId(), accessToken, refreshToken);
        deleteRefreshTokenCookie(response);
        return ApiResponse.success(200, "로그아웃 되었습니다.");
    }

    // 9. 비밀번호 재설정 요청  POST /api/v1/auth/password/reset-request
    @PostMapping("/password/reset-request")
    public ApiResponse<Void> sendPasswordReset(
            @Valid @RequestBody PasswordResetSendRequest request) {
        authService.sendPasswordReset(request);
        return ApiResponse.success(200, "비밀번호 재설정 이메일이 발송되었습니다.");
    }

    // 10. 비밀번호 재설정  POST /api/v1/auth/password/reset
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success(200, "비밀번호가 변경되었습니다.");
    }

    // 11. 닉네임 중복 확인  GET /api/v1/auth/check-nickname
    @GetMapping("/check-nickname")
    public ApiResponse<AvailableResponse> checkNickname(@RequestParam String nickname) {
        return ApiResponse.success(200, authService.checkNickname(nickname));
    }

    // 12. 이메일 중복 확인  GET /api/v1/auth/check-email
    @GetMapping("/check-email")
    public ApiResponse<AvailableResponse> checkEmail(@RequestParam String email) {
        return ApiResponse.success(200, authService.checkEmail(email));
    }
















    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken){//HttpServletResponse 응답 메시지를 담는 객체
        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)//ResponseCookie : 쿠키 설정 빌더 객체, from : 쿠키의 이름과 그 안에 담길 값설정
                .httpOnly(true)//XSS 공격 방어
                .secure(true)// HTTPS 통신일 때만 쿠키를 전송
                .sameSite("Strict")// CSRF 공격 방어(현재 사이트와 쿠키를 요청하는 사이트가 같을 때만 전송)
                .path("/api/v1/auth")// 쿠키가 전송될 경로 제한
                .maxAge(Duration.ofSeconds(jwtProvider.getRefreshTokenExpiresIn()))//유효시간
                .build();//객체 생성
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
    private void deleteRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }







}
