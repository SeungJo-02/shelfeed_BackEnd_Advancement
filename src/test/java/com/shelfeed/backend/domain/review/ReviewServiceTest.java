package com.shelfeed.backend.domain.review;

import com.shelfeed.backend.domain.block.service.BlockService;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.dto.request.ReviewCreateRequest;
import com.shelfeed.backend.domain.review.dto.request.ReviewUpdateRequest;
import com.shelfeed.backend.domain.review.dto.response.ReviewCreateResponse;
import com.shelfeed.backend.domain.review.dto.response.ReviewLikeResponse;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.domain.review.repository.TagRepository;
import com.shelfeed.backend.domain.review.service.ReviewService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewService 단위 테스트")
class ReviewServiceTest {

    @Mock MemberRepository memberRepository;
    @Mock MemberLoader memberLoader;
    @Mock ReviewRepository reviewRepository;
    @Mock LibraryRepository libraryRepository;
    @Mock BookRepository bookRepository;
    @Mock TagRepository tagRepository;
    @Mock ReviewTagRepository reviewTagRepository;
    @Mock ReviewLikeRepository reviewLikeRepository;
    @Mock BlockService blockService;
    @Mock FeedService feedService;
    @Mock NotificationService notificationService;

    @InjectMocks ReviewService reviewService;

    private Member author;     // memberUserId = 1
    private Member liker;      // memberUserId = 2
    private Book book;

    @BeforeEach
    void setUp() {
        author = Member.createLocal(1L, "author@test.com", "encoded", "작성자", "bio");
        liker  = Member.createLocal(2L, "liker@test.com",  "encoded", "좋아요누른이", "bio");

        book = Book.create("9791234567890", "테스트 책", "작가", "출판사",
                null, null, null, null, null, null, null);
    }

    private Review review(ReviewVisibility visibility, ReviewStatus status, Long reviewId) {
        Review review = Review.create(author, book, null, (byte) 5, "감상 내용", null,
                false, null, visibility, status);
        ReflectionTestUtils.setField(review, "reviewId", reviewId);
        return review;
    }

    private ReviewCreateRequest createRequest(String content, String quote,
                                              ReviewVisibility visibility, ReviewStatus status) {
        ReviewCreateRequest req = new ReviewCreateRequest();
        ReflectionTestUtils.setField(req, "bookId", 100L);
        ReflectionTestUtils.setField(req, "rating", (byte) 5);
        ReflectionTestUtils.setField(req, "content", content);
        ReflectionTestUtils.setField(req, "quote", quote);
        ReflectionTestUtils.setField(req, "reviewVisibility", visibility);
        ReflectionTestUtils.setField(req, "reviewStatus", status);
        return req;
    }

    private ReviewUpdateRequest updateRequest(ReviewVisibility visibility, ReviewStatus status) {
        ReviewUpdateRequest req = new ReviewUpdateRequest();
        ReflectionTestUtils.setField(req, "rating", (byte) 4);
        ReflectionTestUtils.setField(req, "content", "수정된 내용");
        ReflectionTestUtils.setField(req, "reviewVisibility", visibility);
        ReflectionTestUtils.setField(req, "reviewStatus", status);
        return req;
    }

    // ────────────────────────────────────────────────────────
    // 1. createReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 작성")
    class CreateReview {

        @Test
        @DisplayName("content와 quote가 모두 없으면 CONTENT_OR_QUOTE_REQUIRED 예외가 발생한다")
        void 내용_인용구_모두_없음_예외() {
            ReviewCreateRequest request =
                    createRequest(null, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CONTENT_OR_QUOTE_REQUIRED);
        }

        @Test
        @DisplayName("같은 도서에 이미 감상이 있으면 DUPLICATE_REVIEW 예외가 발생한다")
        void 중복_감상_예외() {
            ReviewCreateRequest request =
                    createRequest("내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
            given(memberLoader.getOrThrow(1L)).willReturn(author);
            given(bookRepository.findById(100L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(author, 100L))
                    .willReturn(true);

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.DUPLICATE_REVIEW);
        }

        @Test
        @DisplayName("PUBLISHED + PUBLIC 감상 작성 시 리뷰 카운트 증가, 피드/알림 발행을 위임한다")
        void 공개_게시_감상_피드_알림_위임() {
            ReviewCreateRequest request =
                    createRequest("내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
            given(memberLoader.getOrThrow(1L)).willReturn(author);
            given(bookRepository.findById(100L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(author, 100L))
                    .willReturn(false);

            ReviewCreateResponse response = reviewService.createReview(1L, request);

            assertThat(response).isNotNull();
            verify(reviewRepository).save(any());
            verify(memberRepository).increaseReviewCount(1L);
            verify(feedService).publishToFollowers(eq(author), any());
            verify(notificationService).notifyFollowersOfNewReview(eq(author), any());
        }

        @Test
        @DisplayName("DRAFT(임시저장) 감상 작성 시 피드/알림 위임·카운트 증가가 발생하지 않는다")
        void 임시저장_감상_피드_알림_미위임() {
            ReviewCreateRequest request =
                    createRequest("내용", null, ReviewVisibility.PUBLIC, ReviewStatus.DRAFT);
            given(memberLoader.getOrThrow(1L)).willReturn(author);
            given(bookRepository.findById(100L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(author, 100L))
                    .willReturn(false);

            reviewService.createReview(1L, request);

            verify(memberRepository, never()).increaseReviewCount(anyLong());
            verify(feedService, never()).publishToFollowers(any(), any());
            verify(notificationService, never()).notifyFollowersOfNewReview(any(), any());
        }
    }

    // ────────────────────────────────────────────────────────
    // 2. updateReview() — 상태 전이
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 수정 - 상태 전이")
    class UpdateReviewStatusTransition {

        @Test
        @DisplayName("DRAFT → PUBLISHED(PUBLIC) 전이 시 리뷰 카운트 증가, 피드/알림 발행을 위임한다")
        void 임시저장에서_공개게시_전이() {
            Review draft = review(ReviewVisibility.PUBLIC, ReviewStatus.DRAFT, 10L);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(draft));

            reviewService.updateReview(10L, 1L,
                    updateRequest(ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED));

            verify(memberRepository).increaseReviewCount(1L);
            verify(feedService).publishToFollowers(eq(author), any());
            verify(notificationService).notifyFollowersOfNewReview(eq(author), any());
        }

        @Test
        @DisplayName("PUBLISHED → DRAFT 전이 시 리뷰 카운트 감소, 피드 제거를 위임한다")
        void 공개게시에서_임시저장_전이() {
            Review published = review(ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, 10L);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(published));

            reviewService.updateReview(10L, 1L,
                    updateRequest(ReviewVisibility.PUBLIC, ReviewStatus.DRAFT));

            verify(memberRepository).decreaseReviewCount(1L);
            verify(feedService).removeByReview(published);
            verify(feedService, never()).publishToFollowers(any(), any());
            verify(memberRepository, never()).increaseReviewCount(anyLong());
        }
    }
}
