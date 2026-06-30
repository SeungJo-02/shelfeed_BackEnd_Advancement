package com.shelfeed.backend.domain.review.service;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.dto.response.ReviewLikeResponse;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewLike;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감상 좋아요/취소 책임을 담당한다. (ReviewService에서 분리)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewLikeService {

    private final ReviewRepository reviewRepository;
    private final ReviewLikeRepository reviewLikeRepository;
    private final MemberLoader memberLoader;
    private final BlockService blockService;
    private final NotificationService notificationService;

    // 감상 좋아요
    @Transactional
    public ReviewLikeResponse like(Long reviewId, Long memberUserId) {
        Review review = getReviewOrThrow(reviewId);
        Member member = memberLoader.getOrThrow(memberUserId);
        // 비공개 감상은 좋아요 불가
        if (review.getReviewVisibility() == ReviewVisibility.PRIVATE) {
            throw new BusinessException(ErrorCode.PRIVATE_REVIEW);
        }
        // 차단 관계 확인
        Member owner = review.getMember();
        if (blockService.isBlockedBetween(owner, member)) {
            throw new BusinessException(ErrorCode.BLOCKED_USER);
        }
        if (review.getMember().getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        }
        // 감상 아이디랑 멤버아이디 있으면 중복 방지
        if (reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(reviewId, memberUserId)) {
            throw new BusinessException(ErrorCode.ALREADY_REVIEW_LIKED);
        }
        // saveAndFlush로 INSERT를 즉시 실행해 동시 요청 경쟁(exists 동시 통과)에서 unique 위반을
        // 이 지점에서 잡는다. 미적용 시 커밋 시점에 터져 500으로 노출됨 → 409로 멱등 변환.
        try {
            reviewLikeRepository.saveAndFlush(ReviewLike.create(review, member));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.ALREADY_REVIEW_LIKED);
        }
        reviewRepository.increaseLikeCount(review.getReviewId());
        notificationService.notifyReviewLike(review.getMember(), member, review.getReviewId());
        return ReviewLikeResponse.of(getReviewOrThrow(reviewId));
    }

    // 감상 좋아요 취소
    @Transactional
    public ReviewLikeResponse unlike(Long reviewId, Long memberUserId) {
        Review review = getReviewOrThrow(reviewId);
        ReviewLike like = reviewLikeRepository.findByReview_ReviewIdAndMember_MemberUserId(reviewId, memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_LIKE_NOT_FOUND));// 좋아요 확인

        reviewLikeRepository.delete(like);
        reviewRepository.decreaseLikeCount(review.getReviewId());
        return ReviewLikeResponse.of(getReviewOrThrow(reviewId));
    }

    // 삭제 안된 리뷰 찾기
    private Review getReviewOrThrow(Long reviewId) {
        return reviewRepository.findByReviewIdAndIsDeletedFalse(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
    }
}
