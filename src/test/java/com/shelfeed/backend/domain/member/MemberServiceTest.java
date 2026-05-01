package com.shelfeed.backend.domain.member;

import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.member.dto.internal.PasswordChangeResult;
import com.shelfeed.backend.domain.member.dto.request.*;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.enums.MemberStatus;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.service.MemberService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService 단위 테스트")
class MemberServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock GenreRepository genreRepository;
    @Mock MemberGenreRepository memberGenreRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock RedisService redisService;
    @Mock JwtProvider jwtProvider;

    @InjectMocks MemberService memberService;

    private Member localMember;
    private Member oauthMember;
    private Member onboardedMember;
    private Member withdrawnMember;

    @BeforeEach
    void setUp() {
        localMember = Member.createLocal(1L, "test@test.com", "encodedPw", "테스터", "bio");
        oauthMember = Member.createOAuth(2L, "oauth@test.com", "OAuth유저", "http://img.url");
        onboardedMember = Member.createLocal(3L, "onboarded@test.com", "encodedPw", "온보딩완료", "bio");
        onboardedMember.completeOnboarding();
        withdrawnMember = Member.createLocal(4L, "withdrawn@test.com", "encodedPw", "탈퇴자", "bio");
        withdrawnMember.withdraw();
    }

    // ────────────────────────────────────────────────────────
    // 1. completeOnboarding()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("온보딩 완료")
    class CompleteOnboarding {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.completeOnboarding(99L,onboardingRequest("닉네임", "bio", null, List.of(1L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("이미 온보딩이 완료된 회원이면 ONBOARDING_ALREADY_COMPLETED 예외가 발생한다")
        void 온보딩_중복_예외() {
            given(memberRepository.findByMemberUserId(3L)).willReturn(Optional.of(onboardedMember));

            assertThatThrownBy(() -> memberService.completeOnboarding(3L, onboardingRequest("닉네임", "bio", null, List.of(1L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }

        @Test
        @DisplayName("존재하지 않는 장르 ID가 포함되면 GENRE_NOT_FOUND 예외가 발생한다")
        void 장르없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            // 요청 2개, 반환 1개 → 크기 불일치
            given(genreRepository.findAllById(List.of(1L, 99L))).willReturn(List.of(mock(Genre.class)));

            assertThatThrownBy(() -> memberService.completeOnboarding(1L, onboardingRequest("닉네임", "bio", null, List.of(1L, 99L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.GENRE_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 회원이 사용 중인 닉네임이면 NICKNAME_ALREADY_EXISTS 예외가 발생한다")
        void 닉네임_중복_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            // 장르는 정상 통과, 닉네임 중복 검사에서 실패
            given(genreRepository.findAllById(List.of(1L)))
                    .willReturn(List.of(mock(Genre.class)));
            given(memberRepository.existsByNickname("새닉네임")).willReturn(true);

            assertThatThrownBy(() -> memberService.completeOnboarding(1L,
                    onboardingRequest("새닉네임", "bio", null, List.of(1L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("정상 온보딩 시 장르를 저장하고 onboardingCompleted 플래그를 세운다")
        void 정상_온보딩_성공() {
            Genre g1 = genre(1L, "소설");
            Genre g2 = genre(2L, "에세이");
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(genreRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(g1, g2));
            given(memberRepository.existsByNickname("새닉네임")).willReturn(false);

            memberService.completeOnboarding(1L,
                    onboardingRequest("새닉네임", "새소개", null, List.of(1L, 2L)));

            assertThat(localMember.isOnboardingCompleted()).isTrue();
            verify(memberGenreRepository).deleteAllByMember(localMember);
            verify(memberGenreRepository).saveAll(anyList());
        }
    }

    // ────────────────────────────────────────────────────────
    // 2. updateMyGenres()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("관심 장르 수정")
    class UpdateMyGenres {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.updateMyGenres(99L, updateGenresRequest(List.of(1L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 장르 ID가 포함되면 GENRE_NOT_FOUND 예외가 발생한다")
        void 장르없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(genreRepository.findAllById(List.of(1L, 99L)))
                    .willReturn(List.of(mock(Genre.class)));

            assertThatThrownBy(() -> memberService.updateMyGenres(1L, updateGenresRequest(List.of(1L, 99L))))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.GENRE_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 수정 시 기존 장르를 삭제하고 새 장르를 저장한다")
        void 정상_장르_수정_성공() {
            Genre g1 = genre(1L, "소설");
            Genre g2 = genre(2L, "에세이");
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(genreRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(g1, g2));

            memberService.updateMyGenres(1L, updateGenresRequest(List.of(1L, 2L)));

            verify(memberGenreRepository).deleteAllByMember(localMember);
            verify(memberGenreRepository).saveAll(anyList());
        }
    }

    // ────────────────────────────────────────────────────────
    // 3. getMyProfile()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("내 프로필 조회")
    class GetMyProfile {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.getMyProfile(99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 조회 시 MyProfileResponse를 반환한다")
        void 정상_프로필_조회_성공() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));

            var result = memberService.getMyProfile(1L);

            assertThat(result.getNickname()).isEqualTo("테스터");
            assertThat(result.getEmail()).isEqualTo("test@test.com");
        }
    }

    // ────────────────────────────────────────────────────────
    // 4. updateProfile()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("프로필 수정")
    class UpdateProfile {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.updateProfile(99L, updateProfileRequest("닉네임", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 회원이 사용 중인 닉네임으로 변경하면 NICKNAME_ALREADY_EXISTS 예외가 발생한다")
        void 닉네임_중복_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(memberRepository.existsByNickname("중복닉네임")).willReturn(true);

            assertThatThrownBy(() -> memberService.updateProfile(1L, updateProfileRequest("중복닉네임", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("정상 수정 시 닉네임이 변경되고 UpdateProfileResponse를 반환한다")
        void 정상_프로필_수정_성공() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(memberRepository.existsByNickname("수정닉네임")).willReturn(false);

            var result = memberService.updateProfile(1L, updateProfileRequest("수정닉네임", "새소개", null));

            assertThat(result).isNotNull();
            assertThat(localMember.getNickname()).isEqualTo("수정닉네임");
        }
    }

    // ────────────────────────────────────────────────────────
    // 5. getUserProfile()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("타 유저 프로필 조회")
    class GetUserProfile {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.getUserProfile(99L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("탈퇴한 회원이면 WITHDRAWN_MEMBER 예외가 발생한다")
        void 탈퇴회원_예외() {
            given(memberRepository.findByMemberUserId(4L)).willReturn(Optional.of(withdrawnMember));

            assertThatThrownBy(() -> memberService.getUserProfile(4L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.WITHDRAWN_MEMBER);
        }

        @Test
        @DisplayName("정상 조회 시 UserProfileResponse를 반환한다")
        void 정상_유저_프로필_조회_성공() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));

            var result = memberService.getUserProfile(1L);

            assertThat(result.getNickname()).isEqualTo("테스터");
            assertThat(result.getUserId()).isEqualTo(1L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 6. changePassword()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("비밀번호 변경")
    class ChangePassword {

        @Test
        @DisplayName("존재하지 않는 회원이면 NO_PASSWORD_ACCOUNT 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.changePassword(99L,
                    changePasswordRequest("current", "NewPass1!")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NO_PASSWORD_ACCOUNT);
        }

        @Test
        @DisplayName("소셜 로그인 계정(패스워드 null)이면 NO_PASSWORD_ACCOUNT 예외가 발생한다")
        void OAuth_계정_예외() {
            given(memberRepository.findByMemberUserId(2L)).willReturn(Optional.of(oauthMember));

            assertThatThrownBy(() -> memberService.changePassword(2L,
                    changePasswordRequest("current", "NewPass1!")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NO_PASSWORD_ACCOUNT);
        }

        @Test
        @DisplayName("현재 비밀번호가 틀리면 INVALID_CURRENT_PASSWORD 예외가 발생한다")
        void 현재_비밀번호_불일치_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(passwordEncoder.matches("wrongCurrent", "encodedPw")).willReturn(false);

            assertThatThrownBy(() -> memberService.changePassword(1L,
                    changePasswordRequest("wrongCurrent", "NewPass1!")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_CURRENT_PASSWORD);
        }

        @Test
        @DisplayName("새 비밀번호가 기존 비밀번호와 동일하면 SAME_PASSWORD 예외가 발생한다")
        void 동일_비밀번호_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(passwordEncoder.matches("currentPw", "encodedPw")).willReturn(true);
            given(passwordEncoder.matches("samePw1!", "encodedPw")).willReturn(true);

            assertThatThrownBy(() -> memberService.changePassword(1L,
                    changePasswordRequest("currentPw", "samePw1!")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SAME_PASSWORD);
        }

        @Test
        @DisplayName("정상 변경 시 새 토큰 쌍을 반환하고 Redis refreshToken을 갱신한다")
        void 정상_비밀번호_변경_성공() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(passwordEncoder.matches("currentPw", "encodedPw")).willReturn(true);
            given(passwordEncoder.matches("NewPass1!", "encodedPw")).willReturn(false);
            given(passwordEncoder.encode("NewPass1!")).willReturn("newEncodedPw");
            given(jwtProvider.generateAccessToken(any())).willReturn("newAccess");
            given(jwtProvider.generateRefreshToken(any())).willReturn("newRefresh");
            given(jwtProvider.getAccessTokenExpiresIn()).willReturn(3600L);
            given(jwtProvider.getRefreshTokenExpiresIn()).willReturn(1209600L);

            PasswordChangeResult result = memberService.changePassword(1L,
                    changePasswordRequest("currentPw", "NewPass1!"));

            assertThat(result.accessToken()).isEqualTo("newAccess");
            assertThat(result.refreshToken()).isEqualTo("newRefresh");
            assertThat(localMember.getPassword()).isEqualTo("newEncodedPw");
            verify(redisService).saveRefreshToken(eq(1L), eq("newRefresh"), anyLong());
        }
    }

    // ────────────────────────────────────────────────────────
    // 7. withdraw()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("회원 탈퇴")
    class Withdraw {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberRepository.findByMemberUserId(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberService.withdraw(99L, "accessToken", withdrawRequest("pw")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("로컬 계정에서 비밀번호가 틀리면 INVALID_PASSWORD 예외가 발생한다")
        void 비밀번호_불일치_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(passwordEncoder.matches("wrongPw", "encodedPw")).willReturn(false);

            assertThatThrownBy(() -> memberService.withdraw(1L, "accessToken", withdrawRequest("wrongPw")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        @Test
        @DisplayName("로컬 계정 정상 탈퇴 시 회원 정보를 마스킹하고 토큰을 무효화한다")
        void 로컬_정상_탈퇴_성공() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(localMember));
            given(passwordEncoder.matches("correctPw", "encodedPw")).willReturn(true);
            given(jwtProvider.getRemainingExpiryMs("accessToken")).willReturn(60000L);

            memberService.withdraw(1L, "accessToken", withdrawRequest("correctPw"));

            assertThat(localMember.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
            verify(redisService).addToBlacklist("accessToken", 60000L);
            verify(redisService).deleteRefreshToken(1L);
        }

        @Test
        @DisplayName("소셜 계정은 비밀번호 검사 없이 탈퇴하고 refreshToken을 삭제한다")
        void OAuth_계정_탈퇴_성공() {
            given(memberRepository.findByMemberUserId(2L)).willReturn(Optional.of(oauthMember));
            given(jwtProvider.getRemainingExpiryMs("accessToken")).willReturn(60000L);

            memberService.withdraw(2L, "accessToken", withdrawRequest(null));

            assertThat(oauthMember.getStatus()).isEqualTo(MemberStatus.WITHDRAWN);
            verify(passwordEncoder, never()).matches(any(), any());
            verify(redisService).deleteRefreshToken(2L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 픽스처 헬퍼 메서드
    // ────────────────────────────────────────────────────────

    private OnboardingRequest onboardingRequest(String nickname, String bio, String imageUrl, List<Long> genreIds) {
        OnboardingRequest req = new OnboardingRequest();
        ReflectionTestUtils.setField(req, "nickname", nickname);
        ReflectionTestUtils.setField(req, "bio", bio);
        ReflectionTestUtils.setField(req, "profileImageUrl", imageUrl);
        ReflectionTestUtils.setField(req, "genreIds", genreIds);
        return req;
    }

    private UpdateGenresRequest updateGenresRequest(List<Long> genreIds) {
        UpdateGenresRequest req = new UpdateGenresRequest();
        ReflectionTestUtils.setField(req, "genreIds", genreIds);
        return req;
    }

    private UpdateProfileRequest updateProfileRequest(String nickname, String bio, String imageUrl) {
        UpdateProfileRequest req = new UpdateProfileRequest();
        ReflectionTestUtils.setField(req, "nickname", nickname);
        ReflectionTestUtils.setField(req, "bio", bio);
        ReflectionTestUtils.setField(req, "profileImageUrl", imageUrl);
        return req;
    }

    private ChangePasswordRequest changePasswordRequest(String currentPassword, String newPassword) {
        ChangePasswordRequest req = new ChangePasswordRequest();
        ReflectionTestUtils.setField(req, "currentPassword", currentPassword);
        ReflectionTestUtils.setField(req, "newPassword", newPassword);
        return req;
    }

    private WithdrawRequest withdrawRequest(String password) {
        WithdrawRequest req = new WithdrawRequest();
        ReflectionTestUtils.setField(req, "password", password);
        return req;
    }

    private Genre genre(Long id, String name) {
        Genre g = mock(Genre.class);
        lenient().when(g.getGenreId()).thenReturn(id);
        lenient().when(g.getGenreName()).thenReturn(name);
        return g;
    }
}
