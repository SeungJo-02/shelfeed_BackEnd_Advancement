package com.shelfeed.backend.domain.review.cache;

import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 캐시가 비었을 때만 도는 DB 적재 경로. 커넥션을 빌리는 유일한 지점이다.
 *
 * <p>ReviewService 안의 private 메서드로 두지 않고 별도 빈으로 뽑았다. 같은 빈의 메서드를
 * 자기 자신이 호출하면 프록시를 타지 않아 {@code @Transactional}이 걸리지 않는다. getReview는
 * 트랜잭션을 아예 열지 않는 쪽으로 갔으므로, 적재만 별도 빈에서 트랜잭션을 연다.
 */
@Component
@RequiredArgsConstructor
public class ReviewDetailLoader {

    private final ReviewRepository reviewRepository;
    private final ReviewTagRepository reviewTagRepository;

    @Transactional(readOnly = true)
    public ReviewDetailSnapshot load(Long reviewId) {
        Review review = reviewRepository.findByReviewIdAndIsDeletedFalse(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
        List<String> tags = reviewTagRepository.findByReview(review).stream()
                .map(reviewTag -> reviewTag.getTag().getTagName())
                .toList();
        return ReviewDetailSnapshot.from(review, tags);
    }
}
