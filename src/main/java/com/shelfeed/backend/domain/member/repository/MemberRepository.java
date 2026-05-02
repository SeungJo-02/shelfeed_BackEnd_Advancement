package com.shelfeed.backend.domain.member.repository;

import com.shelfeed.backend.domain.member.entity.Member;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByEmail(String email);
    Optional<Member> findByMemberUserId(Long memberUserId);
    List<Member> findByMemberUserIdIn(Collection<Long> memberUserIds);
    boolean existsByEmail(String email);
    boolean existsByNickname(String nickname);

    //닉네임 검색
    @Query("""
    SELECT m FROM Member m WHERE m.nickname Like %:query%
    AND (:cursor IS NULL OR m.memberUserId < :cursor)
    ORDER BY m.memberUserId DESC
""")
    List<Member> searchMembers(@Param("query") String query,
                               @Param("cursor") Long cursor,
                               Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Member m SET m.followerCount = m.followerCount + 1 WHERE m.memberUserId = :id")
    void increaseFollowerCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Member m SET m.followerCount = m.followerCount - 1 WHERE m.memberUserId = :id AND m.followerCount > 0")
    void decreaseFollowerCount(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE Member m SET m.followingCount = m.followingCount + 1 WHERE m.memberUserId = :id")
    void increaseFollowingCount(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE Member m SET m.followingCount = m.followingCount - 1 WHERE m.memberUserId = :id AND m.followingCount > 0")
    void decreaseFollowingCount(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE Member m SET m.reviewCount = m.reviewCount + 1 WHERE m.memberUserId = :id")
    void increaseReviewCount(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("UPDATE Member m SET m.reviewCount = m.reviewCount - 1 WHERE m.memberUserId = :id AND m.reviewCount > 0")
    void decreaseReviewCount(@Param("id") Long id);

    // 탈퇴 처리용 batch 카운트 업데이트
    @Modifying
    @Transactional
    @Query("UPDATE Member m SET m.followerCount = m.followerCount - 1 WHERE m.memberUserId IN :ids AND m.followerCount > 0")
    void decreaseFollowerCountBatch(@Param("ids") List<Long> ids);

    @Modifying
    @Transactional
    @Query("UPDATE Member m SET m.followingCount = m.followingCount - 1 WHERE m.memberUserId IN :ids AND m.followingCount > 0")
    void decreaseFollowingCountBatch(@Param("ids") List<Long> ids);
}
