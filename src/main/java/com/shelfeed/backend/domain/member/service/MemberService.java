package com.shelfeed.backend.domain.member.service;

import com.shelfeed.backend.domain.genre.entity.Genre;
import com.shelfeed.backend.domain.genre.entity.MemberGenre;
import com.shelfeed.backend.domain.genre.repository.GenreRepository;
import com.shelfeed.backend.domain.genre.repository.MemberGenreRepository;
import com.shelfeed.backend.domain.member.dto.request.ChangePasswordRequest;
import com.shelfeed.backend.domain.member.dto.request.OnboardingRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateGenresRequest;
import com.shelfeed.backend.domain.member.dto.request.UpdateProfileRequest;
import com.shelfeed.backend.domain.member.dto.request.WithdrawRequest;
import com.shelfeed.backend.domain.member.dto.response.MyProfileResponse;
import com.shelfeed.backend.domain.member.dto.response.OnboardingResponse;
import com.shelfeed.backend.domain.member.dto.response.UpdateGenresResponse;
import com.shelfeed.backend.domain.member.dto.response.UpdateProfileResponse;
import com.shelfeed.backend.domain.member.dto.response.UserProfileResponse;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.enums.MemberStatus;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.jwt.JwtProvider;
import com.shelfeed.backend.global.redis.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final GenreRepository genreRepository;
    private final MemberGenreRepository memberGenreRepository;
    private final PasswordEncoder passwordEncoder;
    private final RedisService redisService;
    private final JwtProvider jwtProvider;

    // ── 1. 온보딩
    @Transactional
    public OnboardingResponse completeOnboarding(Long memberUserId, OnboardingRequest request) {
        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.isOnboardingCompleted()) {
            throw new BusinessException(ErrorCode.ONBOARDING_ALREADY_COMPLETED);
        }

        List<Genre> genres = getValidatedGenres(request.getGenreIds());

        if (!request.getNickname().equals(member.getNickname())
                && memberRepository.existsByNickname(request.getNickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        member.onboard(request.getNickname(), request.getBio(), request.getProfileImageUrl());
        memberGenreRepository.deleteAllByMember(member);

        List<MemberGenre> memberGenres = genres.stream()
                .map(genre -> MemberGenre.create(member, genre))
                .toList();

        memberGenreRepository.saveAll(memberGenres);
        member.completeOnboarding();

        return OnboardingResponse.of(member, genres);
    }

    // ── 1-1. 관심 장르 설정/수정
    @Transactional
    public UpdateGenresResponse updateMyGenres(Long memberUserId, UpdateGenresRequest request) {
    Member member = memberRepository.findByMemberUserId(memberUserId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

    // 헬퍼 메서드 호출로 중복 제거
    List<Genre> genres = getValidatedGenres(request.getGenreIds());

    memberGenreRepository.deleteAllByMember(member);
    
    List<MemberGenre> memberGenres = genres.stream()
            .map(genre -> MemberGenre.create(member, genre))
            .toList();
    memberGenreRepository.saveAll(memberGenres);

    return UpdateGenresResponse.of(genres);
}

private List<Genre> getValidatedGenres(List<Long> genreIds) {
    // 1. 요청된 ID들의 중복 제거 
    List<Long> distinctIds = genreIds.stream()
            .distinct()
            .toList();

    // 2. DB에서 한꺼번에 조회
    List<Genre> genres = genreRepository.findAllById(distinctIds);

    // 3. 조회된 개수와 요청된 개수가 다르면 예외 발생
    if (genres.size() != distinctIds.size()) {
        throw new BusinessException(ErrorCode.GENRE_NOT_FOUND);
    }

    return genres;
}

    // ── 2. 내 프로필 조회
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(Long memberUserId){
        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        List<Genre> genres = memberGenreRepository.findAllByMemberWithGenre(member)
                .stream().map(MemberGenre::getGenre).toList();

        return MyProfileResponse.of(member, genres);
    }
    // ── 3. 프로필 수정
    @Transactional
    public UpdateProfileResponse updateProfile(Long memberUserId, UpdateProfileRequest request){
        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(()-> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (request.getNickname() != null && !request.getNickname().equals(member.getNickname())&& memberRepository.existsByNickname(request.getNickname())){//빈 값이 아닌지 현재 사용 중인지, 남이 사용중인 지
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        member.updateProfile(request.getNickname(), request.getBio(), request.getProfileImageUrl());

        return UpdateProfileResponse.of(member);
    }
    // ── 4. 타 유저 프로필 조회
    public UserProfileResponse getUserProfile(Long targetUserId){
        Member member = memberRepository.findByMemberUserId(targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.getStatus() == MemberStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.WITHDRAWN_MEMBER);
        }
        return UserProfileResponse.of(member);
    }
    // ── 5. 비밀번호 변경
    @Transactional
    public NewTokenPair changePassword(Long memberUserId, ChangePasswordRequest request){
        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(()-> new BusinessException(ErrorCode.NO_PASSWORD_ACCOUNT));

        if (member.getPassword() == null) {
            throw new BusinessException(ErrorCode.NO_PASSWORD_ACCOUNT);//빈 값이면 변경 불가
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), member.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CURRENT_PASSWORD);//현재 비밀번호가 틀리면 변경불가
        }

        if (passwordEncoder.matches(request.getNewPassword(),member.getPassword())) {
            throw new BusinessException(ErrorCode.SAME_PASSWORD);//바꿀 비번이랑 지금 비번이랑 같으면 변경불가
        }

        member.changePassword(passwordEncoder.encode(request.getNewPassword()));// 비번 변경

        // 기존 토큰 없애고 새 토큰 발급
        String newAccessToken = jwtProvider.generateAccessToken(member);
        String newRefreshToken = jwtProvider.generateRefreshToken(member);
        redisService.saveRefreshToken(memberUserId, newRefreshToken, jwtProvider.getRefreshTokenExpiresIn());
        return new NewTokenPair(newAccessToken, newRefreshToken, jwtProvider.getAccessTokenExpiresIn());


    }
    public record NewTokenPair(String accessToken, String refreshToken, long accessTokenExpiresIn) {}

    // ── 6. 회원 탈퇴
    @Transactional
    public void withdraw(Long memberUserId, String accessToken, WithdrawRequest request){
        Member member = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        if (member.getPassword() != null) {//요청 비번이 없거나
            if (request.getPassword() == null || !passwordEncoder.matches(request.getPassword(), member.getPassword())){//기존 비번인증 안되면
                    throw new BusinessException(ErrorCode.INVALID_PASSWORD);
            }
        }

        member.maskUserlInfo();

        // 토큰 없애기
        long remainingMs = jwtProvider.getRemainingExpiryMs(accessToken);
        if (remainingMs > 0) {
            redisService.addToBlacklist(accessToken,remainingMs);
        }
        redisService.deleteRefreshToken(memberUserId);
    }


}
