package com.shelfeed.backend.domain.member.controller;

import com.shelfeed.backend.domain.member.dto.internal.PasswordChangeResult;
import com.shelfeed.backend.domain.member.dto.request.ChangePasswordRequest;
import com.shelfeed.backend.domain.member.dto.request.OnboardingRequest;
import com.shelfeed.backend.domain.member.dto.request.SettingsUpdateRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateGenresRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateProfileRequest;
import com.shelfeed.backend.domain.member.dto.request.WithdrawRequest;
import com.shelfeed.backend.domain.member.dto.response.MyProfileResponse;
import com.shelfeed.backend.domain.member.dto.response.OnboardingResponse;
import com.shelfeed.backend.domain.member.dto.response.SettingsResponse;
import com.shelfeed.backend.domain.member.dto.response.UpdateGenresResponse;
import com.shelfeed.backend.domain.member.dto.response.UpdateProfileResponse;
import com.shelfeed.backend.domain.member.dto.response.UserProfileResponse;
import com.shelfeed.backend.domain.member.service.MemberService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;
    private final JwtProvider jwtProvider;
    // 1. 온보딩  POST /api/v1/users/me/onboarding
    @PostMapping("/me/onboarding")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OnboardingResponse> completeOnboarding(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody OnboardingRequest request) {
        return ApiResponse.success(201, "온보딩이 완료되었습니다.",
                memberService.completeOnboarding(userDetails.getMember().getMemberUserId(), request));
    }

    // 1-1. 관심 장르 설정/수정  PUT /api/v1/users/me/genres
    @PutMapping("/me/genres")
    public ApiResponse<UpdateGenresResponse> updateMyGenres(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UpdateGenresRequest request) {
        return ApiResponse.success(200, "관심 장르가 설정되었습니다.",
                memberService.updateMyGenres(userDetails.getMember().getMemberUserId(), request));
    }

    // 2. 내 프로필 조회  GET /api/v1/users/me
    @GetMapping("/me")
    public ApiResponse<MyProfileResponse> getMyProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(200,
                memberService.getMyProfile(userDetails.getMember().getMemberUserId())); 
    }

    // 3. 프로필 수정  PATCH /api/v1/users/me
    @PatchMapping("/me")
    public ApiResponse<UpdateProfileResponse> updateProfile(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request){
        return ApiResponse.success(200, "프로필이 수정되었습니다.", memberService.updateProfile(userDetails.getMember().getMemberUserId(),request));
    }

    // 4. 타 유저 프로필 조회  GET /api/v1/users/{userId}
    @GetMapping("/{userId}")
    public ApiResponse<UserProfileResponse> getUserProfile(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, memberService.getUserProfile(userId, memberUserId));
    }

    // 5. 비밀번호 변경  PUT /api/v1/users/me/password
    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request, HttpServletResponse response){//HttpServletResponse response :HTTP 응답 조작 가능하게
            PasswordChangeResult result = memberService.changePassword(userDetails.getMember().getMemberUserId(), request);
        setRefreshTokenCookie(response, result.refreshToken());

        return ApiResponse.success(200,"비밀번호가 변경되었습니다.");
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken", refreshToken)
                .httpOnly(true)// XSS 보안
                .secure(true)//HTTPS 통신만 허용
                .sameSite("Strict")//CSRF 차단
                .path("/api/v1/auth")//인증 주소로 요청 보낼 때만 쿠키 따라기도록
                .maxAge(Duration.ofSeconds(jwtProvider.getRefreshTokenExpiresIn()))//토큰 유효기간
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    // 6. 설정 조회  GET /api/v1/users/me/settings
    @GetMapping("/me/settings")
    public ApiResponse<SettingsResponse> getSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(200,
                memberService.getSettings(userDetails.getMember().getMemberUserId()));
    }

    // 7. 설정 수정  PATCH /api/v1/users/me/settings
    @PatchMapping("/me/settings")
    public ApiResponse<SettingsResponse> updateSettings(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody SettingsUpdateRequest request) {
        return ApiResponse.success(200, "설정이 저장되었습니다.",
                memberService.updateSettings(userDetails.getMember().getMemberUserId(), request));
    }

    // 8. 회원 탈퇴  DELETE /api/v1/users/me
    @DeleteMapping("/me")
    public ApiResponse<Void> withdraw(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(value = "Authorization", required = false) String bearerToken,//요청헤더에서 Authorization 만 가져오기
            @Valid @RequestBody WithdrawRequest request, HttpServletResponse response){
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")){
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        String accessToken = bearerToken.substring(7);
        memberService.withdraw(userDetails.getMember().getMemberUserId(), accessToken, request);
        deleteRefreshTokenCookie(response);
        return ApiResponse.success(200,"회원 탈퇴가 완료되었습니다.");
    }

    private void deleteRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("refreshToken","")
                .httpOnly(true)// XSS 보안
                .secure(true)//HTTPS 통신만 허용
                .sameSite("Strict")//CSRF 차단
                .path("/api/v1/auth")//인증 주소로 요청 보낼 때만 쿠키 따라기도록
                .maxAge(0)//토큰 유효기간
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }













}
