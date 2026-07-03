package com.shelfeed.backend.domain.notification;

import com.shelfeed.backend.domain.book.entity.Book;
import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.member.vo.NotificationPreferences;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import com.shelfeed.backend.global.common.util.CursorUtils;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock MemberRepository memberRepository;
    @Mock FollowRepository followRepository;
    @Mock CursorUtils cursorUtils;

    @InjectMocks NotificationService notificationService;

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
        ReflectionTestUtils.setField(review, "reviewId", 10L);
    }

    @Nested
    @DisplayName("notifyFollowersOfNewReview (새 감상 팔로워 알림)")
    class NotifyFollowersOfNewReview {

        @Test
        @DisplayName("팔로잉 감상 알림이 켜진 팔로워에게 알림을 일괄 저장한다")
        void 설정_켜진_팔로워_알림_저장() {
            given(followRepository.findAllFollowersWithMember(reviewer))
                    .willReturn(List.of(Follow.create(follower, reviewer)));

            notificationService.notifyFollowersOfNewReview(reviewer, review);

            verify(notificationRepository).saveAll(any());
        }

        @Test
        @DisplayName("팔로잉 감상 알림이 꺼진 팔로워만 있으면 알림을 저장하지 않는다")
        void 설정_꺼진_팔로워_미저장() {
            follower.updateNotificationPreferences(
                    NotificationPreferences.builder().followingReviewEnabled(false).build());
            given(followRepository.findAllFollowersWithMember(reviewer))
                    .willReturn(List.of(Follow.create(follower, reviewer)));

            notificationService.notifyFollowersOfNewReview(reviewer, review);

            verify(notificationRepository, never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("notifyReviewLike (좋아요 알림)")
    class NotifyReviewLike {

        @Test
        @DisplayName("작성자의 좋아요 알림이 켜져 있으면 알림을 저장한다")
        void 설정_켜짐_알림_저장() {
            notificationService.notifyReviewLike(reviewer, follower, 10L);

            verify(notificationRepository).save(any());
        }

        @Test
        @DisplayName("작성자의 좋아요 알림이 꺼져 있으면 알림을 저장하지 않는다")
        void 설정_꺼짐_미저장() {
            reviewer.updateNotificationPreferences(
                    NotificationPreferences.builder().likeEnabled(false).build());

            notificationService.notifyReviewLike(reviewer, follower, 10L);

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("notifyFollow (팔로우 알림)")
    class NotifyFollow {

        @Test
        @DisplayName("팔로우 알림이 켜져 있으면 대상에게 알림을 저장한다")
        void 설정_켜짐_저장() {
            notificationService.notifyFollow(reviewer, follower, 5L);

            verify(notificationRepository).save(any());
        }

        @Test
        @DisplayName("팔로우 알림이 꺼져 있으면 저장하지 않는다")
        void 설정_꺼짐_미저장() {
            reviewer.updateNotificationPreferences(
                    NotificationPreferences.builder().followEnabled(false).build());

            notificationService.notifyFollow(reviewer, follower, 5L);

            verify(notificationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("notifyComment (댓글 알림)")
    class NotifyComment {

        @Test
        @DisplayName("본인에게 가는 알림이면 저장하지 않는다")
        void 본인_알림_미저장() {
            notificationService.notifyComment(reviewer, reviewer, 10L, 100L);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("대상의 댓글 알림이 꺼져 있으면 저장하지 않는다")
        void 설정_꺼짐_미저장() {
            follower.updateNotificationPreferences(
                    NotificationPreferences.builder().commentEnabled(false).build());

            notificationService.notifyComment(follower, reviewer, 10L, 100L);

            verify(notificationRepository, never()).save(any());
        }

        @Test
        @DisplayName("본인이 아니고 댓글 알림이 켜져 있으면 저장한다")
        void 정상_저장() {
            notificationService.notifyComment(follower, reviewer, 10L, 100L);

            verify(notificationRepository).save(any());
        }
    }

    @Nested
    @DisplayName("notifyCommentLike (댓글 좋아요 알림)")
    class NotifyCommentLike {

        @Test
        @DisplayName("댓글 작성자의 좋아요 알림이 켜져 있으면 저장한다")
        void 설정_켜짐_저장() {
            notificationService.notifyCommentLike(reviewer, follower, 10L, 100L);

            verify(notificationRepository).save(any());
        }

        @Test
        @DisplayName("댓글 작성자의 좋아요 알림이 꺼져 있으면 저장하지 않는다")
        void 설정_꺼짐_미저장() {
            reviewer.updateNotificationPreferences(
                    NotificationPreferences.builder().likeEnabled(false).build());

            notificationService.notifyCommentLike(reviewer, follower, 10L, 100L);

            verify(notificationRepository, never()).save(any());
        }
    }
}
