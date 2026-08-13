package com.shelfeed.backend.domain.feed.repository;

import com.shelfeed.backend.domain.feed.entity.Feed;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface FeedRepository extends JpaRepository<Feed, Long> {
    /**
     * 팔로잉 피드 후보를 감상 작성 시각 기준으로 최신순 조회한다.
     *
     * <p>정렬 기준이 feedId(피드 행 삽입 순서)가 아니라 감상의 createdAt인 이유:
     * 팔로우 시 {@code backfillOnFollow}가 상대의 과거 감상을 그 시점에 새 피드 행으로
     * 넣기 때문에, feedId로 정렬하면 몇 달 전 감상이 피드 최상단으로 올라온다.
     * 작성 시각으로 정렬해야 "최근에 쓰인 감상이 위"라는 사용자 기대와 일치한다.
     *
     * <p>커서는 (createdAt, reviewId) 복합이다. createdAt만 쓰면 같은 시각에 작성된
     * 감상들이 페이지 경계에서 유실되거나 중복된다.
     */
    @Query("""
    SELECT f FROM Feed f JOIN FETCH f.review r JOIN FETCH r.member JOIN FETCH r.book
    WHERE f.member = :member
    AND (:cursorCreatedAt IS NULL
         OR r.createdAt < :cursorCreatedAt
         OR (r.createdAt = :cursorCreatedAt AND r.reviewId < :cursorId))
    ORDER BY r.createdAt DESC, r.reviewId DESC
""")
    List<Feed> findFeedWithDetails(@Param("member") Member member,
                                   @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
                                   @Param("cursorId") Long cursorId,
                                   Pageable pageable);


    //언팔로우 한 사용자 감상 피드에서 제거
    void deleteByMemberAndReview_Member(Member follower, Member followee);

    // 감상 삭제 시 피드에서 제거
    void deleteByReview(Review review);

    // 탈퇴 처리용: 해당 멤버의 모든 피드 제거
    @Modifying
    @Query("DELETE FROM Feed f WHERE f.member = :member")
    void deleteByMember(@Param("member") Member member);

    // 탈퇴 처리용: 탈퇴 멤버의 감상이 타인 피드에 남아있는 것 제거
    @Modifying
    @Query("DELETE FROM Feed f WHERE f.review.member = :member")
    void deleteByReviewMember(@Param("member") Member member);
}
