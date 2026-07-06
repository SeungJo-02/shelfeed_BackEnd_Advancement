package com.shelfeed.backend.domain.review;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.dto.response.ReviewLikeResponse;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewLike;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.service.ReviewLikeService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewLikeService 단위 테스트")
class ReviewLikeServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock ReviewLikeRepository reviewLikeRepository;
    @Mock MemberLoader memberLoader;
    @Mock BlockService blockService;
    @Mock NotificationService notificationService;

    @InjectMocks ReviewLikeService reviewLikeService;

    private Member author;  // memberUserId = 1
    private Member liker;   // memberUserId = 2
    private Book book;

    @BeforeEach
    void setUp() {
        author = Member.createLocal(1L, "author@test.com", "encoded", "작성자", "bio");
        liker  = Member.createLocal(2L, "liker@test.com",  "encoded", "좋아요누른이", "bio");
        book = Book.create("9791234567890", "테스트 책", "작가", "출판사",
                null, null, null, null, null, null, null);
    }

    private Review review(ReviewVisibility visibility) {
        Review review = Review.create(author, book, null, (byte) 5, "감상 내용", null,
                false, null, visibility, ReviewStatus.PUBLISHED);
        ReflectionTestUtils.setField(review, "reviewId", 10L);
        return review;
    }

    // ────────────────────────────────────────────────────────
    // like()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 좋아요")
    class Like {

        @Test
        @DisplayName("비공개 감상에 좋아요하면 PRIVATE_REVIEW 예외가 발생한다")
        void 비공개_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PRIVATE)));
            given(memberLoader.getOrThrow(2L)).willReturn(liker);

            assertThatThrownBy(() -> reviewLikeService.like(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PRIVATE_REVIEW);
        }

        @Test
        @DisplayName("차단 관계이면 BLOCKED_USER 예외가 발생한다")
        void 차단_관계_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(memberLoader.getOrThrow(2L)).willReturn(liker);
            given(blockService.isBlockedBetween(author, liker)).willReturn(true);

            assertThatThrownBy(() -> reviewLikeService.like(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BLOCKED_USER);
        }

        @Test
        @DisplayName("본인 감상에 좋아요하면 SELF_LIKE_NOT_ALLOWED 예외가 발생한다")
        void 본인_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(memberLoader.getOrThrow(1L)).willReturn(author);
            given(blockService.isBlockedBetween(author, author)).willReturn(false);

            assertThatThrownBy(() -> reviewLikeService.like(10L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("이미 좋아요한 감상이면 ALREADY_REVIEW_LIKED 예외가 발생한다")
        void 중복_좋아요_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(memberLoader.getOrThrow(2L)).willReturn(liker);
            given(blockService.isBlockedBetween(author, liker)).willReturn(false);
            given(reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(true);

            assertThatThrownBy(() -> reviewLikeService.like(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ALREADY_REVIEW_LIKED);
        }

        @Test
        @DisplayName("정상 좋아요 시 좋아요 저장·카운트 증가 후 작성자 알림을 위임한다")
        void 정상_좋아요_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(memberLoader.getOrThrow(2L)).willReturn(liker);
            given(blockService.isBlockedBetween(author, liker)).willReturn(false);
            given(reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(false);

            ReviewLikeResponse response = reviewLikeService.like(10L, 2L);

            assertThat(response).isNotNull();
            verify(reviewLikeRepository).saveAndFlush(any());
            verify(reviewRepository).increaseLikeCount(10L);
            verify(notificationService).notifyReviewLike(author, liker, 10L);
        }
    }

    // ────────────────────────────────────────────────────────
    // unlike()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 좋아요 취소")
    class Unlike {

        @Test
        @DisplayName("좋아요한 적이 없으면(삭제 0행) REVIEW_LIKE_NOT_FOUND 예외 + 카운트 감소 없음")
        void 좋아요_없음_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(reviewLikeRepository.deleteByReviewAndMember(10L, 2L)).willReturn(0);

            assertThatThrownBy(() -> reviewLikeService.unlike(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_LIKE_NOT_FOUND);
            // 드리프트 방지 핵심: 삭제 0행이면 감소하지 않는다 (동시 중복 취소 시 이중 감소 차단)
            verify(reviewRepository, never()).decreaseLikeCount(anyLong());
        }

        @Test
        @DisplayName("정상 취소(삭제 1행) 시 카운트를 감소시킨다")
        void 정상_취소_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(review(ReviewVisibility.PUBLIC)));
            given(reviewLikeRepository.deleteByReviewAndMember(10L, 2L)).willReturn(1);

            ReviewLikeResponse response = reviewLikeService.unlike(10L, 2L);

            assertThat(response).isNotNull();
            verify(reviewRepository).decreaseLikeCount(10L);
        }
    }
}
