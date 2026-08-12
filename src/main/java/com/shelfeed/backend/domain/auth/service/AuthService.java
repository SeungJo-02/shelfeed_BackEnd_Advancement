package com.shelfeed.backend.domain.auth.service;

import com.shelfeed.backend.domain.auth.dto.internal.AuthTokenResult;
import com.shelfeed.backend.domain.auth.dto.request.*;
import com.shelfeed.backend.domain.auth.dto.response.*;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.entity.SocialAccount;
import com.shelfeed.backend.domain.member.enums.MemberStatus;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.repository.SocialAccountRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.email.EmailSendException;
import com.shelfeed.backend.global.email.EmailService;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import com.shelfeed.backend.domain.member.service.MemberUserIdGenerator;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {
    private final MemberRepository memberRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final JwtProvider jwtProvider;
    private final RedisService redisService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    private final RestClient restClient = buildRestClient();
    private final MemberUserIdGenerator memberUserIdGenerator;

    private static RestClient buildRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    @Value("${oauth2.google.client-id}")
    private String googleClientId;
    @Value("${oauth2.google.client-secret}")
    private String googleClientSecret;
    @Value("${oauth2.google.redirect-uri}")
    private String googleRedirectUri;

    private static final int MAX_EMAIL_VERIFY_ATTEMPTS = 5;
    // 로그인 무차별 대입 방지: 15분 슬라이딩 윈도우 내 5회 실패 시 잠금
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCK_SECONDS = 900;          // 15분
    // 비밀번호 재설정 재발송 쿨다운 (이메일 폭탄·SES 비용 방지)
    private static final long PW_RESET_COOLDOWN_SECONDS = 60;

    // ── 1. 이메일 회원가입
    @Transactional(noRollbackFor = EmailSendException.class)
    public AuthTokenResult.Signup signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {//이메일 존재 시 예외 펑
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (memberRepository.existsByNickname(request.getNickname())) {//닉넴 중복 시 예외 펑
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        Long memberUserId = memberUserIdGenerator.next();//중복되지 않게 redis에서 부여한 ID
        Member member = Member.createLocal(memberUserId, request.getEmail(), passwordEncoder.encode(request.getPassword()), request.getNickname(), request.getBio());
        memberRepository.save(member); // 저장

        // 이메일 인증 코드 생성 후 Redis에 저장 (5분 TTL)
        String code = generateSixDigitCode();
        redisService.saveEmailCode(request.getEmail(), code, 300);
        // 비동기 발송 — SMTP 지연이 회원가입 응답을 막지 않도록(발송 실패는 재발송 API로 복구). 발송 실패 로깅은 비동기 메서드 내부에서 처리.
        emailService.sendVerificationEmailAsync(request.getEmail(), code);

        String accessToken = jwtProvider.generateAccessToken(member);//인증 토큰
        String refreshToken = jwtProvider.generateRefreshToken(member);// 재발급 토큰
        redisService.saveRefreshToken(memberUserId, refreshToken, jwtProvider.getRefreshTokenExpiresIn());

        return new AuthTokenResult.Signup(SignupResponse.of(member, accessToken, jwtProvider.getAccessTokenExpiresIn()), refreshToken);
    }

    // ── 2. 이메일 인증 확인
    @Transactional
    public EmailVerifyResponse verifyEmail(EmailVerifyRequest request) {
        long attempts = redisService.incrementEmailVerifyAttempts(request.getEmail());
        if (attempts > MAX_EMAIL_VERIFY_ATTEMPTS) {
            redisService.deleteEmailCode(request.getEmail());//5번 이상 넘어가면 삭제
            throw new BusinessException(ErrorCode.CODE_ATTEMPTS_EXCEEDED);
        }

        String storedCode = redisService.getEmailCode(request.getEmail());
        if (storedCode == null) { throw new BusinessException(ErrorCode.CODE_EXPIRED); }//만료 or 없으면 예외 펑

        if (!storedCode.equals(request.getCode())) {
            throw new BusinessException(ErrorCode.INVALID_EMAIL_CODE);//이메일 코드 다르면 펑
        }

        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));// 없는 이메일 이면 펑
        member.verifyEmail();
        redisService.deleteEmailCode(request.getEmail());// 인증 완료 했으니까 지우기

        return EmailVerifyResponse.of(request.getEmail(), true);
    }

    // ── 3. 이메일 인증 코드 재발송
    public void resendEmailCode(EmailResendRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.isEmailVerified()) {
            throw new BusinessException(ErrorCode.ALREADY_VERIFIED_EMAIL);//이미 인증이 되었으니까 재발송 필요 없
        }

        boolean cooldownSet = redisService.setResendCooldown(request.getEmail(), 60);// 1분간 대기
        if (!cooldownSet) {
            throw new BusinessException(ErrorCode.RESEND_COOLDOWN);
        }
        String code = generateSixDigitCode();
        redisService.saveEmailCode(request.getEmail(), code, 300);
        try {
            emailService.sendVerificationEmail(request.getEmail(), code);
        } catch (EmailSendException e) {
            log.warn("[EMAIL] 인증 이메일 재발송 실패 — cause: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    // ── 4. 이메일 로그인
    @Transactional
    public AuthTokenResult.Login login(LoginRequest request) {
        String email = request.getEmail();
        // 무차별 대입 방지: 실패 누적이 임계치를 넘으면 잠금 (계정 존재 여부와 무관하게 먼저 차단)
        if (redisService.isLoginLocked(email, MAX_LOGIN_ATTEMPTS)) {
            throw new BusinessException(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED);
        }

        // 계정 미존재/비번 불일치를 동일하게 INVALID_PASSWORD로 처리(열거 방지)하고, 두 경우 모두 실패로 기록
        Optional<Member> memberOpt = memberRepository.findByEmail(email);
        if (memberOpt.isEmpty()
                || !passwordEncoder.matches(request.getPassword(), memberOpt.get().getPassword())) {
            redisService.recordLoginFailure(email, LOGIN_LOCK_SECONDS);
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        Member member = memberOpt.get();
        // 탈퇴/정지 계정 차단 — 구글 로그인(verifyLoginableStatus)과 동일 정책으로 통일.
        // 비밀번호 검증 이후에 호출해 계정 상태가 비번 오류와 구분돼 노출되지 않게 한다.
        verifyLoginableStatus(member);

        redisService.clearLoginFailures(email); // 로그인 성공 → 실패 카운터 초기화
        member.recordLogin();//로그인 시점 기록

        String accessToken = jwtProvider.generateAccessToken(member);
        String refreshToken = jwtProvider.generateRefreshToken(member);
        redisService.saveRefreshToken(member.getMemberUserId(), refreshToken, jwtProvider.getRefreshTokenExpiresIn());

        return new AuthTokenResult.Login(
                LoginResponse.of(member, accessToken, jwtProvider.getAccessTokenExpiresIn()), refreshToken);
    }

    // ── 5. Google OAuth 로그인 URL 생성
    public OAuthLoginUrlResponse getGoogleLoginUrl() {
        String state = UUID.randomUUID().toString();
        redisService.saveOAuthState(state, 300);

        String logUrl = UriComponentsBuilder
                .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id", googleClientId)
                .queryParam("redirect_uri", googleRedirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .build().toUriString();
        return OAuthLoginUrlResponse.of(logUrl);
    }

    // ── 6. Google OAuth 로그인 완료
    @Transactional
    public AuthTokenResult.GoogleLogin googleLogin(OAuthTokenRequest request) {
        if (!redisService.validateAndDeleteOAuthState(request.getState())) {// CSRF 방어: state 검증
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE);
        }
        GoogleTokenResponse tokenResponse = exchangeGoogleCode(request.getCode(), request.getRedirectUri());
        GoogleUserInfo userInfo = getGoogleUserInfo(tokenResponse.accessToken());

        // 이메일이 검증되지 않은 구글 계정(예: Workspace 관리자가 임의 이메일로 만든 계정)은
        // 그 이메일의 소유를 보장하지 못하므로 거부한다. 미검증 이메일을 그대로 신뢰하면
        // 동일 이메일의 기존(비밀번호) 계정에 자동 연동되어 계정 탈취/권한 상승이 가능하다.
        if (!Boolean.TRUE.equals(userInfo.emailVerified())) {
            throw new BusinessException(ErrorCode.OAUTH_EMAIL_NOT_VERIFIED);
        }

        return socialAccountRepository.findByProviderAndProviderId("GOOGLE", userInfo.sub())
                .map(socialAccount -> {
                    Member member = socialAccount.getMember();
                    verifyLoginableStatus(member);
                    member.recordLogin();
                    String accessToken = jwtProvider.generateAccessToken(member);
                    String refreshToken = jwtProvider.generateRefreshToken(member);
                    redisService.saveRefreshToken(member.getMemberUserId(), refreshToken,
                            jwtProvider.getRefreshTokenExpiresIn());
                    return new AuthTokenResult.GoogleLogin(
                            GoogleLoginResponse.of(member, accessToken,
                                    jwtProvider.getAccessTokenExpiresIn(), false),
                            refreshToken);
                })
                .orElseGet(() -> {
                    // 이 구글 sub로 연결된 소셜 계정이 없는 경우.
                    // 같은 이메일로 이미 가입한 회원(이메일 가입 등)이 있으면 새 계정을 만들지 않고
                    // 그 회원에 SocialAccount만 연결한다(계정 자동 연동). 구글은 이메일 검증
                    // 제공자라 안전하며, 이를 생략하면 email unique 제약 위반으로 로그인이 실패한다.
                    boolean[] created = {false};
                    Member member = memberRepository.findByEmail(userInfo.email())
                            .map(existing -> {
                                // 기존 이메일 계정에 자동 연동하기 전 로그인 가능 상태인지 확인
                                verifyLoginableStatus(existing);
                                return existing;
                            })
                            .orElseGet(() -> {
                                created[0] = true;
                                Long memberUserId = memberUserIdGenerator.next();
                                String nickname = "Google_" + memberUserId;
                                return memberRepository.save(Member.createOAuth(memberUserId,
                                        userInfo.email(), nickname, userInfo.picture()));
                            });
                    socialAccountRepository.save(
                            SocialAccount.create(member, "GOOGLE", userInfo.sub()));
                    member.recordLogin();
                    String accessToken = jwtProvider.generateAccessToken(member);
                    String refreshToken = jwtProvider.generateRefreshToken(member);
                    redisService.saveRefreshToken(member.getMemberUserId(), refreshToken,
                            jwtProvider.getRefreshTokenExpiresIn());
                    // 기존 이메일 계정에 연동된 경우 isNewUser=false → 프론트가 onboardingCompleted
                    // 기준으로 라우팅(온보딩 스킵). 진짜 신규 가입일 때만 true.
                    return new AuthTokenResult.GoogleLogin(
                            GoogleLoginResponse.of(member, accessToken,
                                    jwtProvider.getAccessTokenExpiresIn(), created[0]),
                            refreshToken);
                });
    }

    // 로그인 가능 상태 검증 — 탈퇴/정지 계정은 OAuth 로그인도 차단(이메일 로그인 login()과 동일 정책)
    private void verifyLoginableStatus(Member member) {
        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.WITHDRAWN_MEMBER);
        }
        if (member.getStatus() == MemberStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.SUSPENDED_MEMBER);
        }
    }

    // Google code → token 교환
    private GoogleTokenResponse exchangeGoogleCode(String code, String redirectUri) {
        MultiValueMap<String, String> formBody = new LinkedMultiValueMap<>();
        formBody.add("code", code);
        formBody.add("client_id", googleClientId);
        formBody.add("client_secret", googleClientSecret);
        formBody.add("redirect_uri", redirectUri);
        formBody.add("grant_type", "authorization_code");
        try {
            return restClient.post()
                    .uri("https://oauth2.googleapis.com/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
    }

    // Google accessToken → 유저 정보 조회
    private GoogleUserInfo getGoogleUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(GoogleUserInfo.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
        }
    }

    // Google API 응답 내부 record
    private record GoogleTokenResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
            @com.fasterxml.jackson.annotation.JsonProperty("id_token") String idToken
    ) {}

    private record GoogleUserInfo(
            String sub,
            String email,
            @com.fasterxml.jackson.annotation.JsonProperty("email_verified") Boolean emailVerified,
            String name,
            String picture
    ) {}

    // ── 7. 토큰 갱신
    public AuthTokenResult.Refresh refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        Long memberUserId;
        try {
            // 서명/만료 검증 + refresh 타입 확인(access 토큰을 refresh로 사용하는 것을 차단)
            if (!jwtProvider.validateToken(refreshToken) || !jwtProvider.isRefreshToken(refreshToken)) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }
            memberUserId = jwtProvider.getMemberUserId(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.EXPIRED_TOKEN);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        String newAccessToken = jwtProvider.generateAccessToken(member);
        String newRefreshToken = jwtProvider.generateRefreshToken(member);

        if (!redisService.rotateRefreshToken(memberUserId, refreshToken, newRefreshToken, jwtProvider.getRefreshTokenExpiresIn())) {
            throw new BusinessException(ErrorCode.TOKEN_REUSE_DETECTED);
        }

        return new AuthTokenResult.Refresh(
                TokenRefreshResponse.of(newAccessToken, jwtProvider.getAccessTokenExpiresIn()), newRefreshToken);
    }

    // ── 8. 로그아웃
    public void logout(Long memberUserId, String accessToken, String refreshToken) {
        long remainingMs = jwtProvider.getRemainingExpiryMs(accessToken);
        if (remainingMs > 0) {
            redisService.addToBlacklist(accessToken, remainingMs);
        }
        redisService.deleteRefreshToken(memberUserId);
    }

    // ── 9. 비밀번호 재설정 요청
    public void sendPasswordReset(PasswordResetSendRequest request) {
        Optional<Member> memberOpt = memberRepository.findByEmail(request.getEmail());
        if (memberOpt.isPresent()) {
            // 재발송 쿨다운: 최근 발송이 있으면 무음 스킵(이메일 폭탄·SES 비용 방지).
            // 존재 여부 노출 방지를 위해 호출자에겐 항상 무음이므로 여기서도 조용히 반환.
            if (!redisService.setPasswordResetCooldown(request.getEmail(), PW_RESET_COOLDOWN_SECONDS)) {
                return;
            }
            String token = UUID.randomUUID().toString();
            redisService.savePasswordResetToken(token, request.getEmail(), 1800);
            // 비동기 fire-and-forget — 발송 실패 로깅은 비동기 메서드 내부에서 처리(존재 여부 노출 방지 위해 호출자는 항상 무음)
            emailService.sendPasswordResetEmail(request.getEmail(), token);
        }
    }

    // ── 10. 비밀번호 재설정 실행
    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        String email = redisService.getEmailByPasswordResetToken(request.getToken());
        if (email == null) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD_RESET_TOKEN);
        }

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (passwordEncoder.matches(request.getNewPassword(), member.getPassword())) {
            throw new BusinessException(ErrorCode.SAME_PASSWORD);
        }

        member.changePassword(passwordEncoder.encode(request.getNewPassword()));
        redisService.deletePasswordResetToken(request.getToken());
        redisService.deleteRefreshToken(member.getMemberUserId());
    }

    // ── 11/12. 닉네임/이메일 중복 확인
    public AvailableResponse checkNickname(String nickname) {
        return AvailableResponse.of(!memberRepository.existsByNickname(nickname));
    }

    public AvailableResponse checkEmail(String email) {
        return AvailableResponse.of(!memberRepository.existsByEmail(email));
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateSixDigitCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }
}
