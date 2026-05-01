package com.shelfeed.backend.domain.follow.repository;

import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.member.entity.Member;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FollowRepository extends JpaRepository<Follow,Long> {

    boolean existsByFollowerAndFollowee(Member follower, Member followee);// 중복 팔로우 확인

    Optional<Follow> findByFollowerAndFollowee(Member follower, Member followee);// 삭제 대상 조회

    //팔로워 목록 페이지네이션
    @Query("""
            SELECT f FROM Follow f
            WHERE f.followee = :member
            AND (:cursor IS NULL OR f.followId< :cursor) ORDER BY f.followId DESC
""")
    List<Follow> findFollowers(@Param("member") Member member, @Param("cursor") Long cursor,
                               Pageable pageable);

    //팔로잉 목록 페이지네이션
    @Query("""
            SELECT f FROM Follow f
            WHERE f.follower = :member
            AND (:cursor IS NULL OR f.followId< :cursor) ORDER BY f.followId DESC
""")
    List<Follow> findFollowings(@Param("member") Member member, @Param("cursor") Long cursor,
                               Pageable pageable);

    // 팔로잉 목록 + followee JOIN FETCH (N+1 방지)
    @Query("""
    SELECT f FROM Follow f
    JOIN FETCH f.followee
    WHERE f.follower = :target
    AND (:cursor IS NULL OR f.followId < :cursor)
    ORDER BY f.followId DESC
""")
    List<Follow> findFollowingsWithMember(@Param("target") Member target,
                                          @Param("cursor") Long cursor,
                                          Pageable pageable);

    //패치 조인  팔로워 조회 시 멤버 정보 한 번에
    @Query("""
    SELECT f FROM Follow f
    JOIN FETCH f.follower
    WHERE f.followee = :target AND (:cursor IS NULL OR f.followId < :cursor)
    ORDER BY f.followId DESC
    """)
    List<Follow> findFollowersWithMember(@Param("target") Member target,
                                         @Param("cursor") Long cursor,
                                         Pageable pageable);

    //내가 타인을 팔로우 중인지 (Following 여부)
    @Query("""
    SELECT f.followee.memberUserId FROM Follow f
    WHERE f.follower = :me AND f.followee IN :candidates
    """)
    Set<Long> findFollowingIds(@Param("me") Member me,
                               @Param("candidates") List<Member> candidates);

    //타인이 나를 팔로우 중인지 (Follower 여부)
    @Query("""
    SELECT f.follower.memberUserId FROM Follow f
    WHERE f.followee = :me AND f.follower IN :candidates
    """)
    Set<Long> findFollowedByIds(@Param("me") Member me,
                                @Param("candidates") List<Member> candidates);





    //타 유저의 팔로워 조회
    List<Follow> findByFollowee(Member followee);

    // 탈퇴 처리용
    List<Follow> findByFollower(Member follower);
}
