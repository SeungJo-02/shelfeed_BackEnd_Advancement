package com.shelfeed.backend.domain.review;

import com.shelfeed.backend.domain.block.repository.BlockRepository;
import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.book.repository.BookRepository;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.library.repository.LibraryRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.global.common.helper.MemberLoader;
import com.shelfeed.backend.domain.review.dto.request.ReviewCreateRequest;
import com.shelfeed.backend.domain.review.dto.request.ReviewUpdateRequest;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.entity.ReviewLike;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
import com.shelfeed.backend.domain.review.repository.TagRepository;
import com.shelfeed.backend.domain.review.service.ReviewService;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

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
    @Mock FeedRepository feedRepository;
    @Mock BlockRepository blockRepository;

    @InjectMocks ReviewService reviewService;

    private Member ownerMember;
    private Member otherMember;
    private Book book;
    private Review publicReview;
    private Review privateReview;
    private Review draftReview;

    @BeforeEach
    void setUp() {
        ownerMember = Member.createLocal(1L, "owner@test.com", "pw", "작성자", "bio");
        otherMember = Member.createLocal(2L, "other@test.com", "pw", "다른사람", "bio");
        book = Book.create("9791234567890", "테스트 도서", "저자", "출판사",
                null, null, null, null, null, null);

        publicReview = Review.create(ownerMember, book, null, (byte) 4, "좋은 책", null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
        ReflectionTestUtils.setField(publicReview, "reviewId", 10L);

        privateReview = Review.create(ownerMember, book, null, (byte) 4, "비공개 감상", null,
                false, null, ReviewVisibility.PRIVATE, ReviewStatus.PUBLISHED);
        ReflectionTestUtils.setField(privateReview, "reviewId", 11L);

        draftReview = Review.create(ownerMember, book, null, (byte) 4, "임시저장", null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.DRAFT);
        ReflectionTestUtils.setField(draftReview, "reviewId", 12L);
    }

    // ────────────────────────────────────────────────────────
    // 1. createReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 작성")
    class CreateReview {

        @Test
        @DisplayName("내용과 인용구가 모두 null이면 CONTENT_OR_QUOTE_REQUIRED 예외가 발생한다")
        void content_quote_둘다_null_예외() {
            // content/quote 검사는 getMember 호출 전에 이루어지므로 별도 stubbing 불필요
            ReviewCreateRequest request = createRequest(1L, null, (byte) 4, null, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CONTENT_OR_QUOTE_REQUIRED);
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            ReviewCreateRequest request = createRequest(1L, null, (byte) 4, "내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(memberLoader.getOrThrow(99L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> reviewService.createReview(99L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 도서이면 BOOK_NOT_FOUND 예외가 발생한다")
        void 도서없음_예외() {
            ReviewCreateRequest request = createRequest(99L, null, (byte) 4, "내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(bookRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("동일 도서에 감상을 중복 작성하면 DUPLICATE_REVIEW 예외가 발생한다")
        void 중복_감상_작성_예외() {
            ReviewCreateRequest request = createRequest(1L, null, (byte) 4, "내용", null,
                    ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(bookRepository.findById(1L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(ownerMember, 1L)).willReturn(true);

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.DUPLICATE_REVIEW);
        }

        @Test
        @DisplayName("libraryBookId가 있지만 서재에 없으면 LIBRARY_BOOK_NOT_FOUND 예외가 발생한다")
        void 서재_도서없음_예외() {
            ReviewCreateRequest request = createRequest(1L, 99L, (byte) 4, "내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(bookRepository.findById(1L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(ownerMember, 1L)).willReturn(false);
            given(libraryRepository.findByLibraryBookIdAndMember(99L, ownerMember)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.createReview(1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.LIBRARY_BOOK_NOT_FOUND);
        }

        @Test
        @DisplayName("PUBLISHED 상태로 작성하면 감상을 저장하고 reviewCount를 증가시킨다")
        void PUBLISHED_감상_작성_성공() {
            ReviewCreateRequest request = createRequest(1L, null, (byte) 4, "내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(bookRepository.findById(1L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(ownerMember, 1L)).willReturn(false);

            reviewService.createReview(1L, request);

            verify(reviewRepository).save(any(Review.class));
            verify(memberRepository).increaseReviewCount(1L);
        }

        @Test
        @DisplayName("DRAFT 상태로 작성하면 감상을 저장하지만 reviewCount를 증가시키지 않는다")
        void DRAFT_감상_작성_성공() {
            ReviewCreateRequest request = createRequest(1L, null, (byte) 4, "내용", null, ReviewVisibility.PUBLIC, ReviewStatus.DRAFT, null);
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(bookRepository.findById(1L)).willReturn(Optional.of(book));
            given(reviewRepository.existsByMemberAndBook_BookIdAndIsDeletedFalse(ownerMember, 1L)).willReturn(false);

            reviewService.createReview(1L, request);

            verify(reviewRepository).save(any(Review.class));
            verify(memberRepository, never()).increaseReviewCount(anyLong());
        }
    }

    // ────────────────────────────────────────────────────────
    // 2. getReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 상세 조회")
    class GetReview {

        @Test
        @DisplayName("삭제된 감상이면 REVIEW_NOT_FOUND 예외가 발생한다")
        void 삭제된_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.getReview(10L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("비공개 감상을 타인이 조회하면 PRIVATE_REVIEW 예외가 발생한다")
        void 비공개_감상_타인_조회_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(11L))
                    .willReturn(Optional.of(privateReview));

            assertThatThrownBy(() -> reviewService.getReview(11L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PRIVATE_REVIEW);
        }

        @Test
        @DisplayName("비로그인 사용자가 비공개 감상을 조회하면 PRIVATE_REVIEW 예외가 발생한다")
        void 비공개_감상_비로그인_조회_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(11L))
                    .willReturn(Optional.of(privateReview));

            assertThatThrownBy(() -> reviewService.getReview(11L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PRIVATE_REVIEW);
        }

        @Test
        @DisplayName("차단 관계가 있으면 BLOCKED_USER 예외가 발생한다")
        void 차단_관계_조회_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, otherMember)).willReturn(true);

            assertThatThrownBy(() -> reviewService.getReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BLOCKED_USER);
        }

        @Test
        @DisplayName("본인의 비공개 감상은 조회할 수 있다")
        void 비공개_감상_본인_조회_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(11L)).willReturn(Optional.of(privateReview));
            given(reviewTagRepository.findByReview(privateReview)).willReturn(List.of());

            var result = reviewService.getReview(11L, 1L);

            assertThat(result.getReviewId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("차단 관계가 없으면 타인이 공개 감상을 조회할 수 있다")
        void 공개_감상_타인_조회_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, otherMember)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(otherMember, ownerMember)).willReturn(false);
            given(reviewTagRepository.findByReview(publicReview)).willReturn(List.of());

            var result = reviewService.getReview(10L, 2L);

            assertThat(result.getReviewId()).isEqualTo(10L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 3. updateReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 수정")
    class UpdateReview {

        @Test
        @DisplayName("내용과 인용구가 모두 null이면 CONTENT_OR_QUOTE_REQUIRED 예외가 발생한다")
        void 수정_content_quote_둘다_null_예외() {
            // content/quote 검사는 getReviewOrThrow 호출 전에 이루어지므로 stubbing 불필요
            ReviewUpdateRequest request = updateRequest((byte) 4, null, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);

            assertThatThrownBy(() -> reviewService.updateReview(10L, 1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.CONTENT_OR_QUOTE_REQUIRED);
        }

        @Test
        @DisplayName("삭제된 감상이면 REVIEW_NOT_FOUND 예외가 발생한다")
        void 삭제된_감상_예외() {
            ReviewUpdateRequest request = updateRequest((byte) 4, "새 내용", null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.updateReview(10L, 1L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 감상이 아닌 경우 NOT_REVIEW_OWNER 예외가 발생한다")
        void 타인_감상_수정_예외() {
            ReviewUpdateRequest request = updateRequest((byte) 4, "새 내용", null,
                    ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));

            assertThatThrownBy(() -> reviewService.updateReview(10L, 2L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NOT_REVIEW_OWNER);
        }

        @Test
        @DisplayName("DRAFT에서 PUBLISHED로 변경하면 reviewCount를 증가시킨다")
        void DRAFT에서_PUBLISHED_승격_시_카운트_증가() {
            ReviewUpdateRequest request = updateRequest((byte) 4, "수정 내용", null,
                    ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(12L))
                    .willReturn(Optional.of(draftReview));

            reviewService.updateReview(12L, 1L, request);

            verify(memberRepository).increaseReviewCount(1L);
        }

        @Test
        @DisplayName("정상 수정 시 기존 태그를 삭제하고 UpdateReviewResponse를 반환한다")
        void 정상_감상_수정_성공() {
            ReviewUpdateRequest request = updateRequest((byte) 5, "수정 내용", null,
                    ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED, null);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));

            var result = reviewService.updateReview(10L, 1L, request);

            assertThat(result).isNotNull();
            verify(reviewTagRepository).deleteByReview(publicReview);
        }
    }

    // ────────────────────────────────────────────────────────
    // 4. deleteReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 삭제")
    class DeleteReview {

        @Test
        @DisplayName("삭제된 감상이면 REVIEW_NOT_FOUND 예외가 발생한다")
        void 삭제된_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.deleteReview(10L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 감상이 아닌 경우 NOT_REVIEW_OWNER 예외가 발생한다")
        void 타인_감상_삭제_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));

            assertThatThrownBy(() -> reviewService.deleteReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NOT_REVIEW_OWNER);
        }

        @Test
        @DisplayName("PUBLISHED 감상 삭제 시 소프트 삭제하고 reviewCount를 감소시킨다")
        void PUBLISHED_감상_삭제_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));

            reviewService.deleteReview(10L, 1L);

            assertThat(publicReview.isDeleted()).isTrue();
            verify(memberRepository).decreaseReviewCount(1L);
            verify(feedRepository).deleteByReview(publicReview);
        }

        @Test
        @DisplayName("DRAFT 감상 삭제 시 소프트 삭제하지만 reviewCount를 감소시키지 않는다")
        void DRAFT_감상_삭제_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(12L)).willReturn(Optional.of(draftReview));

            reviewService.deleteReview(12L, 1L);

            assertThat(draftReview.isDeleted()).isTrue();
            verify(memberRepository, never()).decreaseReviewCount(anyLong());
            verify(feedRepository).deleteByReview(draftReview);
        }
    }

    // ────────────────────────────────────────────────────────
    // 5. getMyReviews()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("내 감상 목록 조회")
    class GetMyReviews {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberLoader.getOrThrow(99L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> reviewService.getMyReviews(99L, null, null, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 조회 시 감상 목록을 반환한다")
        void 정상_내_감상_목록_조회_성공() {
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(reviewRepository.findMyReviews(eq(ownerMember), any(), any(), any())).willReturn(List.of());

            var result = reviewService.getMyReviews(1L, null, null, 10);

            assertThat(result).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────
    // 6. getUserReviews()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("타 유저 감상 목록 조회")
    class GetUserReviews {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원없음_예외() {
            given(memberLoader.getOrThrow(99L)).willThrow(new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

            assertThatThrownBy(() -> reviewService.getUserReviews(99L, null, 10))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 조회 시 공개된 감상 목록을 반환한다")
        void 정상_유저_감상_목록_조회_성공() {
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            given(reviewRepository.findUserReviews(eq(ownerMember), any(), any()))
                    .willReturn(List.of());

            var result = reviewService.getUserReviews(1L, null, 10);

            assertThat(result).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────
    // 7. likeReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 좋아요")
    class LikeReview {

        @Test
        @DisplayName("삭제된 감상이면 REVIEW_NOT_FOUND 예외가 발생한다")
        void 삭제된_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.likeReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("비공개 감상에는 좋아요를 할 수 없다")
        void 비공개_감상_좋아요_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(11L))
                    .willReturn(Optional.of(privateReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);

            assertThatThrownBy(() -> reviewService.likeReview(11L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PRIVATE_REVIEW);
        }

        @Test
        @DisplayName("차단 관계가 있으면 BLOCKED_USER 예외가 발생한다")
        void 차단_관계_좋아요_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, otherMember)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(otherMember, ownerMember)).willReturn(true);

            assertThatThrownBy(() -> reviewService.likeReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.BLOCKED_USER);
        }

        @Test
        @DisplayName("본인 감상에 좋아요를 누르면 SELF_LIKE_NOT_ALLOWED 예외가 발생한다")
        void 본인_감상_좋아요_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(1L)).willReturn(ownerMember);
            // 본인-본인 차단 체크: false 반환
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, ownerMember)).willReturn(false);

            assertThatThrownBy(() -> reviewService.likeReview(10L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        }

        @Test
        @DisplayName("이미 좋아요한 감상에 다시 좋아요하면 ALREADY_REVIEW_LIKED 예외가 발생한다")
        void 중복_좋아요_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, otherMember)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(otherMember, ownerMember)).willReturn(false);
            given(reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(true);

            assertThatThrownBy(() -> reviewService.likeReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ALREADY_REVIEW_LIKED);
        }

        @Test
        @DisplayName("정상 좋아요 시 ReviewLike를 저장하고 likeCount를 증가시킨다")
        void 정상_좋아요_성공() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(memberLoader.getOrThrow(2L)).willReturn(otherMember);
            given(blockRepository.existsByBlockerAndBlocked(ownerMember, otherMember)).willReturn(false);
            given(blockRepository.existsByBlockerAndBlocked(otherMember, ownerMember)).willReturn(false);
            given(reviewLikeRepository.existsByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(false);

            var result = reviewService.likeReview(10L, 2L);

            assertThat(result).isNotNull();
            verify(reviewLikeRepository).save(any(ReviewLike.class));
            verify(reviewRepository).increaseLikeCount(10L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 8. unlikeReview()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("감상 좋아요 취소")
    class UnlikeReview {

        @Test
        @DisplayName("삭제된 감상이면 REVIEW_NOT_FOUND 예외가 발생한다")
        void 삭제된_감상_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.unlikeReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("좋아요 내역이 없으면 REVIEW_LIKE_NOT_FOUND 예외가 발생한다")
        void 좋아요_없음_예외() {
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(reviewLikeRepository.findByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> reviewService.unlikeReview(10L, 2L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REVIEW_LIKE_NOT_FOUND);
        }

        @Test
        @DisplayName("정상 취소 시 ReviewLike를 삭제하고 likeCount를 감소시킨다")
        void 정상_좋아요_취소_성공() {
            ReviewLike like = mock(ReviewLike.class);
            given(reviewRepository.findByReviewIdAndIsDeletedFalse(10L))
                    .willReturn(Optional.of(publicReview));
            given(reviewLikeRepository.findByReview_ReviewIdAndMember_MemberUserId(10L, 2L))
                    .willReturn(Optional.of(like));

            reviewService.unlikeReview(10L, 2L);

            verify(reviewLikeRepository).delete(like);
            verify(reviewRepository).decreaseLikeCount(10L);
        }
    }

    // ────────────────────────────────────────────────────────
    // 픽스처 헬퍼 메서드
    // ────────────────────────────────────────────────────────

    private ReviewCreateRequest createRequest(Long bookId, Long libraryBookId, byte rating,
                                              String content, String quote,
                                              ReviewVisibility visibility, ReviewStatus status,
                                              List<String> tags) {
        ReviewCreateRequest req = new ReviewCreateRequest();
        ReflectionTestUtils.setField(req, "bookId", bookId);
        ReflectionTestUtils.setField(req, "libraryBookId", libraryBookId);
        ReflectionTestUtils.setField(req, "rating", rating);
        ReflectionTestUtils.setField(req, "content", content);
        ReflectionTestUtils.setField(req, "quote", quote);
        ReflectionTestUtils.setField(req, "reviewVisibility", visibility);
        ReflectionTestUtils.setField(req, "reviewStatus", status);
        ReflectionTestUtils.setField(req, "tags", tags);
        ReflectionTestUtils.setField(req, "isSpoiler", false);
        return req;
    }

    private ReviewUpdateRequest updateRequest(byte rating, String content, String quote,
                                              ReviewVisibility visibility, ReviewStatus status,
                                              List<String> tags) {
        ReviewUpdateRequest req = new ReviewUpdateRequest();
        ReflectionTestUtils.setField(req, "rating", rating);
        ReflectionTestUtils.setField(req, "content", content);
        ReflectionTestUtils.setField(req, "quote", quote);
        ReflectionTestUtils.setField(req, "reviewVisibility", visibility);
        ReflectionTestUtils.setField(req, "reviewStatus", status);
        ReflectionTestUtils.setField(req, "tags", tags);
        ReflectionTestUtils.setField(req, "isSpoiler", false);
        return req;
    }
}
