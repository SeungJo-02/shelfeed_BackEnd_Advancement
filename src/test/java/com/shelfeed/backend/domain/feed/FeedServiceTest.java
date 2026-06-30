package com.shelfeed.backend.domain.feed;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.feed.repository.FeedRepository;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.domain.review.repository.ReviewLikeRepository;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedService 단위 테스트")
class FeedServiceTest {

    @Mock MemberLoader memberLoader;
    @Mock FeedRepository feedRepository;
    @Mock FollowRepository followRepository;
    @Mock ReviewLikeRepository reviewLikeRepository;
    @Mock ReviewTagRepository reviewTagRepository;

    @InjectMocks FeedService feedService;

    private Member reviewer;
    private Member follower;
    private Review review;

    @BeforeEach
    void setUp() {
        reviewer = Member.createLocal(1L, "reviewer@test.com", "encoded", "작성자", "bio");
        follower = Member.createLocal(2L, "follower@test.com", "encoded", "팔로워", "bio");
        Book book = Book.create("9791234567890", "테스트 책", "작가", "출판사",
                null, null, null, null, null, null, null);
        review = Review.create(reviewer, book, null, (byte) 5, "내용", null,
                false, null, ReviewVisibility.PUBLIC, ReviewStatus.PUBLISHED);
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
}
