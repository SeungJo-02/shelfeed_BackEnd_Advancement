package com.shelfeed.backend.domain.member.entity;

import com.shelfeed.backend.domain.member.converter.NotificationPreferencesConverter;
import com.shelfeed.backend.domain.member.enums.LibraryVisibility;
import com.shelfeed.backend.domain.member.enums.MemberRole;
import com.shelfeed.backend.domain.member.enums.MemberStatus;
import com.shelfeed.backend.domain.member.vo.NotificationPreferences;
import com.shelfeed.backend.global.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column
    private Long memberId;

    @Column(nullable = false, unique = true)
    private Long memberUserId;

    @Column(nullable = false, unique = true)
    private String email;

    private String password; // 구글 로그인 Null로

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(length = 500)
    private String profileImageUrl;

    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false)
    private boolean onboardingCompleted;

    @Column(length = 300)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LibraryVisibility libraryVisibility = LibraryVisibility.PUBLIC;

    @Convert(converter = NotificationPreferencesConverter.class)
    @Column(nullable = false, columnDefinition = "JSON")
    private NotificationPreferences notificationPreferences = new NotificationPreferences();

    @Column(nullable = false)
    private int followerCount = 0;

    @Column(nullable = false)
    private int followingCount = 0;

    @Column(nullable = false)
    private int reviewCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role = MemberRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;

    private LocalDateTime lastLoginAt;
    private LocalDateTime withdrawnAt;

    // 계정 생성하고 수정 안되는 것들
    public static Member createLocal(Long memberUserId, String email, String encodedPassword, String nickname, String bio) {
        Member member = new Member();
        member.memberUserId = memberUserId;
        member.email = email;
        member.password = encodedPassword;
        member.nickname = nickname;
        member.bio = bio;
        member.emailVerified = false;
        member.onboardingCompleted = false;
        return member;
    }

    // Google OAuth 신규 회원 생성
    public static Member createOAuth(Long memberUserId, String email,
                                     String nickname, String profileImageUrl) {
        Member member = new Member();
        member.memberUserId = memberUserId;
        member.email = email;
        member.nickname = nickname;
        member.profileImageUrl = profileImageUrl;
        member.emailVerified = true;   // 소셜 로그인은 이메일 인증 생략
        member.onboardingCompleted = false;
        return member;
    }

    // 비즈니스 메서드
    // 온보딩 전용: null 포함 값 그대로 설정 (초기 정보 주입)
    public void onboard(String nickname, String bio, String profileImageUrl) {
        this.nickname = nickname;
        this.bio = bio;
        if (profileImageUrl != null) this.profileImageUrl = profileImageUrl; // OAuth 기본 이미지 보호
    }
    // 프로필 수정 전용: null이면 기존 값 유지 (PATCH 의미)
    public void updateProfile(String nickname, String bio, String profileImageUrl) {
        if (nickname != null) this.nickname = nickname;
        if (bio != null) this.bio = bio;
        if (profileImageUrl != null) this.profileImageUrl = profileImageUrl;
    }
    public void completeOnboarding() { this.onboardingCompleted = true; }
    public void recordLogin() { this.lastLoginAt = LocalDateTime.now(); }
    public void changePassword(String encodedPassword) {this.password = encodedPassword;}
    public void withdraw() {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = LocalDateTime.now();
    }
    public void verifyEmail(){this.emailVerified = true;}

    public void updateLibraryVisibility(LibraryVisibility visibility) {
        this.libraryVisibility = visibility;
    }

    public void updateNotificationPreferences(NotificationPreferences prefs) {
        this.notificationPreferences = prefs;
    }

    public void promoteToAdmin() {
        this.role = MemberRole.ADMIN;
    }

    public void maskUserInfo() {
        this.email = "withdrawn_" + this.memberUserId + "@deleted.com";
        this.nickname = "탈퇴한 사용자";
        this.profileImageUrl = null;
        this.bio = null;
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = LocalDateTime.now();
    }
}

