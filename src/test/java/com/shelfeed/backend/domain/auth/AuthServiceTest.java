package com.shelfeed.backend.domain.auth;

import com.shelfeed.backend.domain.auth.dto.internal.AuthTokenResult;
import com.shelfeed.backend.domain.auth.dto.request.*;
import com.shelfeed.backend.domain.auth.service.AuthService;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.repository.SocialAccountRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.email.EmailService;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import com.shelfeed.backend.domain.member.service.MemberUserIdGenerator;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock SocialAccountRepository socialAccountRepository;
    @Mock JwtProvider jwtProvider;
    @Mock RedisService redisService;
    @Mock MemberUserIdGenerator memberUserIdGenerator;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailService emailService;

    @InjectMocks AuthService authService;

    private Member activeMember;
    private Member withdrawnMember;
    private Member verifiedMember;

    @BeforeEach
    void setUp() {
        activeMember = Member.createLocal(1L, "test@test.com", "encodedPw", "테스터", "bio");

        withdrawnMember = Member.createLocal(2L, "withdrawn@test.com", "encodedPw", "탈퇴자", "bio");
        withdrawnMember.withdraw();

        verifiedMember = Member.createLocal(3L, "verified@test.com", "encodedPw", "인증완료", "bio");
        verifiedMember.verifyEmail();
    }

    // ────────────────────────────────────────────────────────
    // 1. signup()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("이메일 회원가입")
    class Signup {

        @Test
        @DisplayName("이미 사용 중인 이메일이면 EMAIL_ALREADY_EXISTS 예외가 발생한다")
        void 이메일_중복_예외() {
            SignupRequest request = signupRequest("duplicate@test.com", "Pass1234!", "닉네임");
            given(memberRepository.existsByEmail("duplicate@test.com")).willReturn(true);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("이미 사용 중인 닉네임이면 NICKNAME_ALREADY_EXISTS 예외가 발생한다")
        void 닉네임_중복_예외() {
            SignupRequest request = signupRequest("new@test.com", "Pass1234!", "중복닉네임");
            given(memberRepository.existsByEmail(anyString())).willReturn(false);
            given(memberRepository.existsByNickname("중복닉네임")).willReturn(true);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("정상 회원가입 시 TokenPair를 반환하고 인증 이메일을 발송한다")
        void 정상_회원가입_성공() {
            SignupRequest request = signupRequest("new@test.com", "Pass1234!", "새닉네임");
            given(memberRepository.existsByEmail(anyString())).willReturn(false);
            given(memberRepository.existsByNickname(anyString())).willReturn(false);
            given(memberUserIdGenerator.next()).willReturn(10L);
            given(passwordEncoder.encode(anyString())).willReturn("encodedPw");
            given(memberRepository.save(any())).willReturn(activeMember);
            given(jwtProvider.generateAccessToken(any())).willReturn("accessToken");
            given(jwtProvider.generateRefreshToken(any())).willReturn("refreshToken");
            given(jwtProvider.getAccessTokenExpiresIn()).willReturn(3600L);
            given(jwtProvider.getRefreshTokenExpiresIn()).willReturn(1209600L);

            AuthTokenResult.Signup result = authService.signup(request);

            assertThat(result.refreshToken()).isEqualTo("refreshToken");
            assertThat(result.response().getAccessToken()).isEqualTo("accessToken");
            assertThat(result.response().getAccessTokenExpiresIn()).isEqualTo(3600L);
            verify(emailService).sendVerificationEmailAsync(eq("new@test.com"), anyString());
            verify(redisService).saveRefreshToken(eq(10L), eq("refreshToken"), anyLong());
        }
    }

    // ────────────────────────────────────────────────────────
    // 2. verifyEmail()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("이메일 인증")
    class VerifyEmail {

        @Test
        @DisplayName("인증 시도 횟수가 5회를 초과하면 CODE_ATTEMPTS_EXCEEDED 예외가 발생한다")
        void 시도횟수_초과_예외() {
            EmailVerifyRequest request = emailVerifyRequest("test@test.com", "123456");
            given(redisService.incrementEmailVerifyAttempts("test@test.com")).willReturn(6L);

            assertThatThrownBy(() -> authService.verifyEmail(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CODE_ATTEMPTS_EXCEEDED);
        }

        @Test
        @DisplayName("인증 코드가 만료되었으면 CODE_EXPIRED 예외가 발생한다")
        void 코드_만료_예외() {
            EmailVerifyRequest request = emailVerifyRequest("test@test.com", "123456");
            given(redisService.incrementEmailVerifyAttempts(anyString())).willReturn(1L);
            given(redisService.getEmailCode("test@test.com")).willReturn(null);

            assertThatThrownBy(() -> authService.verifyEmail(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CODE_EXPIRED);
        }

        @Test
        @DisplayName("인증 코드가 일치하지 않으면 INVALID_EMAIL_CODE 예외가 발생한다")
        void 코드_불일치_예외() {
            EmailVerifyRequest request = emailVerifyRequest("test@test.com", "111111");
            given(redisService.incrementEmailVerifyAttempts(anyString())).willReturn(1L);
            given(redisService.getEmailCode("test@test.com")).willReturn("999999");

            assertThatThrownBy(() -> authService.verifyEmail(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_EMAIL_CODE);
        }

        @Test
        @DisplayName("정상 인증 시 이메일 인증 완료 처리 후 코드를 삭제한다")
        void 정상_인증_성공() {
            EmailVerifyRequest request = emailVerifyRequest("test@test.com", "123456");
            given(redisService.incrementEmailVerifyAttempts(anyString())).willReturn(1L);
            given(redisService.getEmailCode("test@test.com")).willReturn("123456");
            given(memberRepository.findByEmail("test@test.com")).willReturn(Optional.of(activeMember));

            authService.verifyEmail(request);

            assertThat(activeMember.isEmailVerified()).isTrue();
            verify(redisService).deleteEmailCode("test@test.com");
        }
    }

    // ────────────────────────────────────────────────────────
    // 3. resendEmailCode()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("인증 코드 재발송")
    class ResendEmailCode {

        @Test
        @DisplayName("이미 인증된 이메일이면 ALREADY_VERIFIED_EMAIL 예외가 발생한다")
        void 이미_인증된_이메일_예외() {
            EmailResendRequest request = emailResendRequest("verified@test.com");
            given(memberRepository.findByEmail("verified@test.com"))
                    .willReturn(Optional.of(verifiedMember));

            assertThatThrownBy(() -> authService.resendEmailCode(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ALREADY_VERIFIED_EMAIL);
        }

        @Test
        @DisplayName("쿨다운(1분) 중에 재발송 요청하면 RESEND_COOLDOWN 예외가 발생한다")
        void 쿨다운_중_예외() {
            EmailResendRequest request = emailResendRequest("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(redisService.setResendCooldown(anyString(), anyLong())).willReturn(false);

            assertThatThrownBy(() -> authService.resendEmailCode(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.RESEND_COOLDOWN);
        }

        @Test
        @DisplayName("정상 재발송 시 새 코드를 저장하고 이메일을 발송한다")
        void 정상_재발송_성공() {
            EmailResendRequest request = emailResendRequest("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(redisService.setResendCooldown(anyString(), anyLong())).willReturn(true);

            authService.resendEmailCode(request);

            verify(redisService).saveEmailCode(eq("test@test.com"), anyString(), eq(300L));
            verify(emailService).sendVerificationEmail(eq("test@test.com"), anyString());
        }
    }

    // ────────────────────────────────────────────────────────
    // 4. login()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("이메일 로그인")
    class Login {

        @Test
        @DisplayName("존재하지 않는 이메일이면 INVALID_PASSWORD 예외가 발생한다")
        void 존재하지않는_이메일_예외() {
            LoginRequest request = loginRequest("none@test.com", "Pass1234!");
            given(memberRepository.findByEmail("none@test.com")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 INVALID_PASSWORD 예외가 발생한다")
        void 비밀번호_불일치_예외() {
            LoginRequest request = loginRequest("test@test.com", "WrongPass1!");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        @Test
        @DisplayName("탈퇴한 회원이 로그인하면 WITHDRAWN_MEMBER 예외가 발생한다")
        void 탈퇴회원_예외() {
            LoginRequest request = loginRequest("withdrawn@test.com", "Pass1234!");
            given(memberRepository.findByEmail("withdrawn@test.com"))
                    .willReturn(Optional.of(withdrawnMember));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.WITHDRAWN_MEMBER);
        }

        @Test
        @DisplayName("정상 로그인 시 LoginTokenPair를 반환하고 refreshToken을 Redis에 저장한다")
        void 정상_로그인_성공() {
            LoginRequest request = loginRequest("test@test.com", "Pass1234!");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(true);
            given(jwtProvider.generateAccessToken(any())).willReturn("accessToken");
            given(jwtProvider.generateRefreshToken(any())).willReturn("refreshToken");
            given(jwtProvider.getAccessTokenExpiresIn()).willReturn(3600L);
            given(jwtProvider.getRefreshTokenExpiresIn()).willReturn(1209600L);

            AuthTokenResult.Login result = authService.login(request);

            assertThat(result.refreshToken()).isEqualTo("refreshToken");
            verify(redisService).saveRefreshToken(eq(1L), eq("refreshToken"), anyLong());
            verify(redisService).clearLoginFailures("test@test.com"); // 성공 시 실패 카운터 초기화
        }

        @Test
        @DisplayName("실패 누적이 임계치를 넘어 잠긴 상태면 LOGIN_ATTEMPTS_EXCEEDED 예외가 발생한다")
        void 잠금상태_예외() {
            LoginRequest request = loginRequest("test@test.com", "Pass1234!");
            given(redisService.isLoginLocked(eq("test@test.com"), anyInt())).willReturn(true);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED);
            // 잠긴 상태에선 비밀번호 검증조차 하지 않는다
            verify(memberRepository, never()).findByEmail(anyString());
        }

        @Test
        @DisplayName("비밀번호가 틀리면 로그인 실패를 기록한다 (무차별 대입 카운팅)")
        void 실패_기록() {
            LoginRequest request = loginRequest("test@test.com", "WrongPass1!");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
            verify(redisService).recordLoginFailure(eq("test@test.com"), anyLong());
        }

        @Test
        @DisplayName("존재하지 않는 이메일도 실패로 기록한다 (계정 열거+무차별 대입 방지)")
        void 미존재_이메일_실패_기록() {
            LoginRequest request = loginRequest("none@test.com", "Pass1234!");
            given(memberRepository.findByEmail("none@test.com")).willReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
            verify(redisService).recordLoginFailure(eq("none@test.com"), anyLong());
        }
    }

    // ────────────────────────────────────────────────────────
    // 5. googleLogin() — state 검증만 (RestClient는 통합 테스트 대상)
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Google OAuth 로그인")
    class GoogleLogin {

        @Test
        @DisplayName("유효하지 않은 state이면 INVALID_OAUTH_STATE 예외가 발생한다")
        void state_검증_실패_예외() {
            OAuthTokenRequest request = oAuthTokenRequest("authCode", "https://redirect.uri", "invalidState");
            given(redisService.validateAndDeleteOAuthState("invalidState")).willReturn(false);

            assertThatThrownBy(() -> authService.googleLogin(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_OAUTH_STATE);
        }
    }

    // ────────────────────────────────────────────────────────
    // 6. refresh()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("토큰 갱신")
    class Refresh {

        @Test
        @DisplayName("refreshToken이 null이면 INVALID_TOKEN 예외가 발생한다")
        void refreshToken_null_예외() {
            assertThatThrownBy(() -> authService.refresh(null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("만료된 refreshToken이면 EXPIRED_TOKEN 예외가 발생한다")
        void 만료된_토큰_예외() {
            given(jwtProvider.validateToken("expiredToken"))
                    .willThrow(new ExpiredJwtException(null, null, "expired"));

            assertThatThrownBy(() -> authService.refresh("expiredToken"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXPIRED_TOKEN);
        }

        @Test
        @DisplayName("유효하지 않은 refreshToken이면 INVALID_TOKEN 예외가 발생한다")
        void 유효하지않은_토큰_예외() {
            given(jwtProvider.validateToken("badToken")).willReturn(false);

            assertThatThrownBy(() -> authService.refresh("badToken"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_TOKEN);
        }

        @Test
        @DisplayName("Redis 저장 토큰과 다르면 TOKEN_REUSE_DETECTED 예외가 발생한다")
        void Redis_토큰_불일치_예외() {
            given(jwtProvider.validateToken("token")).willReturn(true);
            given(jwtProvider.isRefreshToken("token")).willReturn(true);
            given(jwtProvider.getMemberUserId("token")).willReturn(1L);
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(activeMember));
            given(jwtProvider.generateAccessToken(any())).willReturn("newAccess");
            given(jwtProvider.generateRefreshToken(any())).willReturn("newRefresh");
            given(jwtProvider.getRefreshTokenExpiresIn()).willReturn(1209600L);
            given(redisService.rotateRefreshToken(1L, "token", "newRefresh", 1209600L)).willReturn(false);

            assertThatThrownBy(() -> authService.refresh("token"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.TOKEN_REUSE_DETECTED);
        }

        @Test
        @DisplayName("정상 갱신 시 새 토큰 쌍을 반환하고 Redis를 갱신한다")
        void 정상_토큰_갱신_성공() {
            given(jwtProvider.validateToken("validRefresh")).willReturn(true);
            given(jwtProvider.isRefreshToken("validRefresh")).willReturn(true);
            given(jwtProvider.getMemberUserId("validRefresh")).willReturn(1L);
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(activeMember));
            given(jwtProvider.generateAccessToken(any())).willReturn("newAccess");
            given(jwtProvider.generateRefreshToken(any())).willReturn("newRefresh");
            given(jwtProvider.getAccessTokenExpiresIn()).willReturn(3600L);
            given(jwtProvider.getRefreshTokenExpiresIn()).willReturn(1209600L);
            given(redisService.rotateRefreshToken(1L, "validRefresh", "newRefresh", 1209600L)).willReturn(true);

            AuthTokenResult.Refresh result = authService.refresh("validRefresh");

            assertThat(result.newRefreshToken()).isEqualTo("newRefresh");
        }
    }

    // ────────────────────────────────────────────────────────
    // 7. logout()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("정상 로그아웃 시 accessToken을 블랙리스트에 추가하고 refreshToken을 삭제한다")
        void 정상_로그아웃_성공() {
            given(jwtProvider.getRemainingExpiryMs("accessToken")).willReturn(60000L);

            authService.logout(1L, "accessToken", "refreshToken");

            verify(redisService).addToBlacklist("accessToken", 60000L);
            verify(redisService).deleteRefreshToken(1L);
        }

        @Test
        @DisplayName("이미 만료된 accessToken이면 블랙리스트 추가 없이 refreshToken만 삭제한다")
        void 만료된_accessToken_블랙리스트_미추가() {
            given(jwtProvider.getRemainingExpiryMs("expiredAccess")).willReturn(0L);

            authService.logout(1L, "expiredAccess", "refreshToken");

            verify(redisService, never()).addToBlacklist(anyString(), anyLong());
            verify(redisService).deleteRefreshToken(1L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 8. sendPasswordReset()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("비밀번호 재설정 요청")
    class SendPasswordReset {

        @Test
        @DisplayName("존재하지 않는 이메일이어도 예외 없이 조용히 성공한다 (이메일 열거 공격 방지)")
        void 존재하지않는_이메일_조용히_성공() {
            PasswordResetSendRequest request = passwordResetSendRequest("none@test.com");
            given(memberRepository.findByEmail("none@test.com")).willReturn(Optional.empty());

            authService.sendPasswordReset(request);

            verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("존재하는 이메일이면 재설정 토큰을 저장하고 메일을 발송한다")
        void 존재하는_이메일_메일발송() {
            PasswordResetSendRequest request = passwordResetSendRequest("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(redisService.setPasswordResetCooldown(eq("test@test.com"), anyLong())).willReturn(true);

            authService.sendPasswordReset(request);

            verify(redisService).savePasswordResetToken(anyString(), eq("test@test.com"), eq(1800L));
            verify(emailService).sendPasswordResetEmail(eq("test@test.com"), anyString());
        }

        @Test
        @DisplayName("재발송 쿨다운 중이면 토큰 저장·메일 발송 없이 조용히 스킵한다 (이메일 폭탄 방지)")
        void 쿨다운중_무음스킵() {
            PasswordResetSendRequest request = passwordResetSendRequest("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(redisService.setPasswordResetCooldown(eq("test@test.com"), anyLong())).willReturn(false);

            authService.sendPasswordReset(request);

            verify(redisService, never()).savePasswordResetToken(anyString(), anyString(), anyLong());
            verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
        }
    }

    // ────────────────────────────────────────────────────────
    // 9. resetPassword()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("비밀번호 재설정")
    class ResetPassword {

        @Test
        @DisplayName("유효하지 않거나 만료된 토큰이면 INVALID_PASSWORD_RESET_TOKEN 예외가 발생한다")
        void 유효하지않은_토큰_예외() {
            PasswordResetRequest request = passwordResetRequest("invalidToken", "NewPass1!");
            given(redisService.getEmailByPasswordResetToken("invalidToken")).willReturn(null);

            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }

        @Test
        @DisplayName("기존 비밀번호와 동일하면 SAME_PASSWORD 예외가 발생한다")
        void 동일한_비밀번호_예외() {
            PasswordResetRequest request = passwordResetRequest("validToken", "SamePass1!");
            given(redisService.getEmailByPasswordResetToken("validToken"))
                    .willReturn("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(passwordEncoder.matches("SamePass1!", activeMember.getPassword()))
                    .willReturn(true);

            assertThatThrownBy(() -> authService.resetPassword(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SAME_PASSWORD);
        }

        @Test
        @DisplayName("정상 재설정 시 비밀번호를 변경하고 토큰 및 refreshToken을 삭제한다")
        void 정상_재설정_성공() {
            PasswordResetRequest request = passwordResetRequest("validToken", "NewPass1!");
            given(redisService.getEmailByPasswordResetToken("validToken"))
                    .willReturn("test@test.com");
            given(memberRepository.findByEmail("test@test.com"))
                    .willReturn(Optional.of(activeMember));
            given(passwordEncoder.matches(anyString(), anyString())).willReturn(false);
            given(passwordEncoder.encode("NewPass1!")).willReturn("encodedNewPw");

            authService.resetPassword(request);

            assertThat(activeMember.getPassword()).isEqualTo("encodedNewPw");
            verify(redisService).deletePasswordResetToken("validToken");
            verify(redisService).deleteRefreshToken(1L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 10. checkNickname() / checkEmail()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("닉네임/이메일 중복 확인")
    class CheckAvailability {

        @Test
        @DisplayName("사용 가능한 닉네임이면 available: true를 반환한다")
        void 닉네임_사용가능() {
            given(memberRepository.existsByNickname("newNick")).willReturn(false);
            assertThat(authService.checkNickname("newNick").isAvailable()).isTrue();
        }

        @Test
        @DisplayName("이미 사용 중인 닉네임이면 available: false를 반환한다")
        void 닉네임_중복() {
            given(memberRepository.existsByNickname("takenNick")).willReturn(true);
            assertThat(authService.checkNickname("takenNick").isAvailable()).isFalse();
        }

        @Test
        @DisplayName("사용 가능한 이메일이면 available: true를 반환한다")
        void 이메일_사용가능() {
            given(memberRepository.existsByEmail("new@test.com")).willReturn(false);
            assertThat(authService.checkEmail("new@test.com").isAvailable()).isTrue();
        }

        @Test
        @DisplayName("이미 사용 중인 이메일이면 available: false를 반환한다")
        void 이메일_중복() {
            given(memberRepository.existsByEmail("taken@test.com")).willReturn(true);
            assertThat(authService.checkEmail("taken@test.com").isAvailable()).isFalse();
        }
    }

    // ────────────────────────────────────────────────────────
    // 픽스처 헬퍼 메서드
    // ────────────────────────────────────────────────────────
    private SignupRequest signupRequest(String email, String password, String nickname) {
        SignupRequest req = new SignupRequest();
        ReflectionTestUtils.setField(req, "email", email);
        ReflectionTestUtils.setField(req, "password", password);
        ReflectionTestUtils.setField(req, "nickname", nickname);
        return req;
    }

    private LoginRequest loginRequest(String email, String password) {
        LoginRequest req = new LoginRequest();
        ReflectionTestUtils.setField(req, "email", email);
        ReflectionTestUtils.setField(req, "password", password);
        return req;
    }

    private EmailVerifyRequest emailVerifyRequest(String email, String code) {
        EmailVerifyRequest req = new EmailVerifyRequest();
        ReflectionTestUtils.setField(req, "email", email);
        ReflectionTestUtils.setField(req, "code", code);
        return req;
    }

    private EmailResendRequest emailResendRequest(String email) {
        EmailResendRequest req = new EmailResendRequest();
        ReflectionTestUtils.setField(req, "email", email);
        return req;
    }

    private OAuthTokenRequest oAuthTokenRequest(String code, String redirectUri, String state) {
        OAuthTokenRequest req = new OAuthTokenRequest();
        ReflectionTestUtils.setField(req, "code", code);
        ReflectionTestUtils.setField(req, "redirectUri", redirectUri);
        ReflectionTestUtils.setField(req, "state", state);
        return req;
    }

    private PasswordResetSendRequest passwordResetSendRequest(String email) {
        PasswordResetSendRequest req = new PasswordResetSendRequest();
        ReflectionTestUtils.setField(req, "email", email);
        return req;
    }

    private PasswordResetRequest passwordResetRequest(String token, String newPassword) {
        PasswordResetRequest req = new PasswordResetRequest();
        ReflectionTestUtils.setField(req, "token", token);
        ReflectionTestUtils.setField(req, "newPassword", newPassword);
        return req;
    }
}
