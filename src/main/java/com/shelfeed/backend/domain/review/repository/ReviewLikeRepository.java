package com.shelfeed.backend.domain.review.repository;

import com.shelfeed.backend.domain.review.entity.ReviewLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * member는 더 이상 연관관계가 아니므로 공개 ID(memberUserId)를 타고 들어갈 수 없다.
 * 호출측이 memberUserId를 PK(memberId)로 해석해 넘긴다.
 */
public interface ReviewLikeRepository extends JpaRepository<ReviewLike,Long> {

    boolean existsByReview_ReviewIdAndMemberId(Long reviewId, Long memberId);// 좋아요 중복확인

    Optional<ReviewLike> findByReview_ReviewIdAndMemberId(Long reviewId, Long memberId);// 좋아요 취소 할 때 삭제 대상 조회용도

    // 좋아요 취소: 벌크 DELETE로 실제 삭제 행 수 반환 → 동시 중복 요청 시 한 트랜잭션만 1행 삭제(나머지 0행)라
    // 이 값으로 카운트 감소를 게이팅하면 이중 감소(드리프트)를 막는다.
    @Modifying
    @Query("DELETE FROM ReviewLike rl WHERE rl.review.reviewId = :reviewId AND rl.memberId = :memberId")
    int deleteByReviewAndMember(@Param("reviewId") Long reviewId, @Param("memberId") Long memberId);

    //감상 좋아요 in절
    @Query("""
    SELECT rl.review.reviewId FROM ReviewLike rl
    WHERE rl.review.reviewId IN :reviewIds AND rl.memberId = :memberId
""")
    Set<Long> findLikedReviewIds(@Param("reviewIds") List<Long> reviewIds,
                                 @Param("memberId") Long memberId);
}
