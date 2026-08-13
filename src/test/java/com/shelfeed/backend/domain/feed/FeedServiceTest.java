package com.shelfeed.backend.domain.feed;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.feed.dto.response.FeedItemResponse;
import com.shelfeed.backend.domain.feed.dto.response.FeedListResponse;
import com.shelfeed.backend.domain.feed.entity.Feed;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.feed.service.RecommendationService;
import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
import com.shelfeed.backend.domain.review.repository.ReviewRepository;
import com.shelfeed.backend.domain.review.repository.ReviewTagRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedService 단위 테스트")
class FeedServiceTest {

    @Mock MemberLoader memberLoader;
    @Mock FeedRepository feedRepository;
    @Mock FollowRepository followRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock ReviewLikeRepository reviewLikeRepository;
    @Mock ReviewTagRepository reviewTagRepository;
    @Mock RecommendationService recommendationService;

    @InjectMocks FeedService feedService;

    private Member reviewer;
    private Member follower;
    private Book book;
    private Review review;

    @BeforeEach
    void setUp() {
        reviewer = Member.createLocal(1L, "reviewer@test.com", "encoded", "작성자", "bio");
        follower = Member.createLocal(2L, "follower@test.com", "encoded", "팔로워", "bio");
        ReflectionTestUtils.setField(follower, "memberId", 102L);
        book = Book.create("9791234567890", "테스트 책", "작가", "출판사",
                null, null, null, null, null, null, null);
        review = Review.create(reviewer, book, null, (byte) 5, "내용", null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
    }

    /** 작성 시각과 ID가 지정된 감상. 두 값 모두 영속화 시점에 채워지므로 테스트에서 직접 넣는다. */
    private Review reviewAt(Member author, long reviewId, LocalDateTime createdAt) {
        Review r = Review.create(author, book, null, (byte) 5, "내용" + reviewId, null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
        ReflectionTestUtils.setField(r, "reviewId", reviewId);
        ReflectionTestUtils.setField(r, "createdAt", createdAt);
        return r;
    }

    private void givenFeedSources(List<Review> followingReviews, List<Review> recommendedReviews) {
        given(memberLoader.getOrThrow(2L)).willReturn(follower);
        given(feedRepository.findFeedWithDetails(eq(follower), any(), any(), any()))
                .willReturn(followingReviews.stream().map(r -> Feed.create(follower, r)).toList());
        given(recommendationService.findCandidates(eq(follower), any(), any(), anyInt()))
                .willReturn(recommendedReviews);
        given(reviewLikeRepository.findLikedReviewIds(any(), any())).willReturn(Set.of());
        given(reviewTagRepository.findByReviewIdIn(any())).willReturn(List.of());
    }

    private List<Long> reviewIdsOf(FeedListResponse response) {
        return response.getContent().stream().map(FeedItemResponse::getReviewId).toList();
    }

    @Nested
    @DisplayName("publishToFollowers (팔로워 피드 생성)")
    class PublishToFollowers {

        @Test
        @DisplayName("팔로워가 있으면 각 팔로워 피드를 일괄 저장한다")
        void 팔로워_있으면_피드_저장() {
            given(followRepository.findAllFollowersWithMember(reviewer))
                    .willReturn(List.of(Follow.create(follower, reviewer)));

            feedService.publishToFollowers(reviewer, review);

            verify(feedRepository).saveAll(any());
        }

        @Test
        @DisplayName("팔로워가 없으면 피드를 저장하지 않는다")
        void 팔로워_없으면_미저장() {
            given(followRepository.findAllFollowersWithMember(reviewer))
                    .willReturn(List.of());

            feedService.publishToFollowers(reviewer, review);

            verify(feedRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("removeByReview (피드 제거)")
    class RemoveByReview {

        @Test
        @DisplayName("해당 감상의 피드를 모두 삭제한다")
        void 감상_피드_삭제() {
            feedService.removeByReview(review);

            verify(feedRepository).deleteByReview(review);
        }
    }

    @Nested
    @DisplayName("backfillOnFollow (팔로우 시 소급 피드)")
    class BackfillOnFollow {

        @Test
        @DisplayName("대상의 최근 감상이 있으면 팔로워 피드에 일괄 생성한다")
        void 최근감상_있으면_생성() {
            given(reviewRepository.findUserReviews(eq(reviewer), any(), any()))
                    .willReturn(List.of(review));

            feedService.backfillOnFollow(follower, reviewer);

            verify(feedRepository).saveAll(any());
        }

        @Test
        @DisplayName("대상의 최근 감상이 없으면 피드를 생성하지 않는다")
        void 최근감상_없으면_미생성() {
            given(reviewRepository.findUserReviews(eq(reviewer), any(), any()))
                    .willReturn(List.of());

            feedService.backfillOnFollow(follower, reviewer);

            verify(feedRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("removeOnUnfollow (언팔로우 시 피드 제거)")
    class RemoveOnUnfollow {

        @Test
        @DisplayName("팔로워 피드에서 대상의 감상을 제거한다")
        void 언팔_시_피드제거() {
            feedService.removeOnUnfollow(follower, reviewer);

            verify(feedRepository).deleteByMemberAndReview_Member(follower, reviewer);
        }
    }

    @Nested
    @DisplayName("getFeed (통합 피드 조회)")
    class GetFeed {

        private final LocalDateTime 오래됨 = LocalDateTime.of(2026, 1, 1, 9, 0);
        private final LocalDateTime 보통 = LocalDateTime.of(2026, 7, 15, 9, 0);
        private final LocalDateTime 최신 = LocalDateTime.of(2026, 8, 4, 9, 0);

        @Test
        @DisplayName("팔로잉 감상과 추천 감상을 한 피드로 합쳐 최신순으로 돌려준다")
        void 두_출처_병합_최신순() {
            Review 팔로잉_오래된감상 = reviewAt(reviewer, 1L, 오래됨);
            Review 팔로잉_최신감상 = reviewAt(reviewer, 2L, 최신);
            Review 추천_중간감상 = reviewAt(reviewer, 3L, 보통);
            givenFeedSources(List.of(팔로잉_오래된감상, 팔로잉_최신감상), List.of(추천_중간감상));

            FeedListResponse response = feedService.getFeed(2L, null, null, 20);

            assertThat(reviewIdsOf(response))
                    .as("출처와 무관하게 작성 시각 내림차순이어야 한다")
                    .containsExactly(2L, 3L, 1L);
        }

        @Test
        @DisplayName("새로 팔로우한 사람의 오래된 감상은 최상단으로 올라오지 않는다")
        void 소급_피드가_최상단을_차지하지_않는다() {
            // 팔로우 시 backfill로 방금 피드에 들어온 과거 감상. 피드 행은 가장 최근에
            // 생겼지만 작성 시각은 오래됐으므로 맨 아래여야 한다.
            Review 방금_팔로우한_사람의_과거감상 = reviewAt(reviewer, 10L, 오래됨);
            Review 원래_보던_최신감상 = reviewAt(reviewer, 11L, 최신);
            givenFeedSources(List.of(방금_팔로우한_사람의_과거감상, 원래_보던_최신감상), List.of());

            FeedListResponse response = feedService.getFeed(2L, null, null, 20);

            assertThat(reviewIdsOf(response)).containsExactly(11L, 10L);
        }

        @Test
        @DisplayName("팔로잉과 추천 양쪽에 걸린 감상은 한 번만 노출한다")
        void 중복_감상_제거() {
            Review 겹치는감상 = reviewAt(reviewer, 7L, 최신);
            givenFeedSources(List.of(겹치는감상), List.of(겹치는감상));

            FeedListResponse response = feedService.getFeed(2L, null, null, 20);

            assertThat(reviewIdsOf(response)).containsExactly(7L);
            assertThat(response.getSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("다음 페이지가 있으면 마지막 항목의 작성 시각과 ID를 커서로 내려준다")
        void 다음페이지_커서() {
            givenFeedSources(
                    List.of(reviewAt(reviewer, 1L, 최신), reviewAt(reviewer, 2L, 보통)),
                    List.of(reviewAt(reviewer, 3L, 오래됨)));

            FeedListResponse response = feedService.getFeed(2L, null, null, 2);

            assertThat(response.isHasNext()).isTrue();
            assertThat(reviewIdsOf(response)).containsExactly(1L, 2L);
            assertThat(response.getNextCursorCreatedAt()).isEqualTo(보통);
            assertThat(response.getNextCursorId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("마지막 페이지면 커서를 내리지 않는다")
        void 마지막페이지_커서_없음() {
            givenFeedSources(List.of(reviewAt(reviewer, 1L, 최신)), List.of());

            FeedListResponse response = feedService.getFeed(2L, null, null, 20);

            assertThat(response.isHasNext()).isFalse();
            assertThat(response.getNextCursorCreatedAt()).isNull();
            assertThat(response.getNextCursorId()).isNull();
        }
    }
}
