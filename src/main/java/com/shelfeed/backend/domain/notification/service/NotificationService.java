package com.shelfeed.backend.domain.notification.service;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.notification.dto.response.NotificationItemResponse;
import com.shelfeed.backend.domain.notification.dto.response.NotificationListResponse;
import com.shelfeed.backend.domain.notification.dto.response.UnreadCountResponse;
import com.shelfeed.backend.domain.notification.entity.Notification;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
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
    private final CursorUtils cursorUtils;

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

    public UnreadCountResponse getUnreadCount(Long memberUserId) {
        Member receiver = memberRepository.findByMemberUserId(memberUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        long unreadCount = notificationRepository.countUnread(receiver);
        return UnreadCountResponse.of(unreadCount);
    }

}

