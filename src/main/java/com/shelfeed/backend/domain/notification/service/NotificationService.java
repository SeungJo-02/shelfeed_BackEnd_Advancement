package com.shelfeed.backend.domain.notification.service;

import com.shelfeed.backend.domain.follow.entity.Follow;
import com.shelfeed.backend.domain.follow.repository.FollowRepository;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.notification.dto.response.NotificationItemResponse;
import com.shelfeed.backend.domain.notification.dto.response.NotificationListResponse;
import com.shelfeed.backend.domain.notification.dto.response.UnreadCountResponse;
import com.shelfeed.backend.domain.notification.entity.Notification;
import com.shelfeed.backend.domain.notification.enums.NotificationType;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.global.common.exception.BusinessException;
import com.shelfeed.backend.global.common.exception.ErrorCode;
import com.shelfeed.backend.global.common.util.CursorUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;
    private final FollowRepository followRepository;
    private final CursorUtils cursorUtils;

    /**
     * 새 공개 감상을 작성자의 팔로워들에게 알림한다.
     * (팔로워 중 followingReviewEnabled 설정이 켜진 사람만 대상)
     */
    @Transactional
    public void notifyFollowersOfNewReview(Member reviewer, Review review) {
        List<Notification> notifications = followRepository.findAllFollowersWithMember(reviewer).stream()
                .filter(follow -> follow.getFollower().getNotificationPreferences().isFollowingReviewEnabled())
                .map(follow -> Notification.createUserNotification(
                        follow.getFollower(), reviewer, NotificationType.FOLLOWING_REVIEW, review.getReviewId()))
                .toList();
        if (!notifications.isEmpty()) notificationRepository.saveAll(notifications);
    }

    /**
     * 감상 좋아요 알림을 작성자에게 보낸다. (작성자의 likeEnabled 설정이 켜진 경우만)
     */
    @Transactional
    public void notifyReviewLike(Member reviewOwner, Member liker, Long reviewId) {
        if (!reviewOwner.getNotificationPreferences().isLikeEnabled()) return;
        notificationRepository.save(Notification.createUserNotification(
                reviewOwner, liker, NotificationType.REVIEW_LIKE, reviewId));
    }

    /**
     * 팔로우 알림을 대상(followee)에게 보낸다. (followee의 followEnabled 설정이 켜진 경우만)
     */
    @Transactional
    public void notifyFollow(Member followee, Member follower, Long followId) {
        if (!followee.getNotificationPreferences().isFollowEnabled()) return;
        notificationRepository.save(Notification.createUserNotification(
                followee, follower, NotificationType.FOLLOW, followId));
    }

    /**
     * 댓글 알림을 대상에게 보낸다. (본인 알림 제외 + 대상의 commentEnabled 설정이 켜진 경우만)
     */
    @Transactional
    public void notifyComment(Member target, Member actor, Long reviewId, Long commentId) {
        if (target.getMemberUserId().equals(actor.getMemberUserId())) return;
        if (!target.getNotificationPreferences().isCommentEnabled()) return;
        notificationRepository.save(Notification.createCommentNotification(
                target, actor, NotificationType.COMMENT, reviewId, commentId));
    }

    /**
     * 댓글 좋아요 알림을 댓글 작성자에게 보낸다. (작성자의 likeEnabled 설정이 켜진 경우만)
     */
    @Transactional
    public void notifyCommentLike(Member commentOwner, Member liker, Long reviewId, Long commentId) {
        if (!commentOwner.getNotificationPreferences().isLikeEnabled()) return;
        notificationRepository.save(Notification.createCommentNotification(
                commentOwner, liker, NotificationType.COMMENT_LIKE, reviewId, commentId));
    }

    public NotificationListResponse getMyNotifications(Long memberUserId, String cursor, int limit) {
         if (limit <= 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        Member receiver = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        Long cursorId = cursorUtils.decode(cursor);
        List<Notification> notifications = notificationRepository.findMyNotifications(
                receiver,
                cursorId,
                PageRequest.of(0, limit + 1)
        );

        List<NotificationItemResponse> all = notifications.stream()
                .map(NotificationItemResponse::of)
                .toList();
        boolean hasNext = all.size() > limit;
        List<NotificationItemResponse> content = hasNext ? all.subList(0, limit) : all;
        String nextCursor = hasNext
                ? cursorUtils.encode(content.get(content.size() - 1).getNotificationId())
                : null;

        return NotificationListResponse.of(content, limit, nextCursor);
    }

    @Transactional
    public void markAsRead(Long memberUserId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.getReceiver().getMemberUserId().equals(memberUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        notification.beReaded();
    }

    // 전체 알림 읽음 처리 — 멱등 (이미 모두 읽음이면 0건 업데이트)
    @Transactional
    public void markAllAsRead(Long memberUserId) {
        Member receiver = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        notificationRepository.markAllAsRead(receiver);
    }

    public UnreadCountResponse getUnreadCount(Long memberUserId) {
        Member receiver = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        long unreadCount = notificationRepository.countUnread(receiver);
        return UnreadCountResponse.of(unreadCount);
    }

}

