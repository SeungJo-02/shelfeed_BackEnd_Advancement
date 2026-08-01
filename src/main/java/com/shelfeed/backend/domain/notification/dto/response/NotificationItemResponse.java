package com.shelfeed.backend.domain.notification.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.notification.entity.Notification;
import com.shelfeed.backend.domain.notification.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class NotificationItemResponse {
    private Long notificationId;
    private NotificationType type;
    private String message;

    @JsonProperty("isRead")
    private boolean isRead;

    private ActorResponse actor;
    private Long reviewId;
    private Long commentId;
    private Long followId;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class ActorResponse {
        private Long userId;
        private String nickname;
        private String profileImageUrl;

        public static ActorResponse of(Member actor) {
            if (actor == null) return null;
            return ActorResponse.builder()
                    .userId(actor.getMemberUserId())
                    .nickname(actor.getNickname())
                    .profileImageUrl(actor.getProfileImageUrl())
                    .build();
        }
    }

    /**
     * actor는 더 이상 Notification의 연관관계가 아니므로 호출측이 별도 조회해 넘긴다.
     * (시스템 알림이거나 행위자가 탈퇴한 경우 null)
     */
    public static NotificationItemResponse of(Notification n, Member actor) {
        return NotificationItemResponse.builder()
                .notificationId(n.getNotificationId())
                .type(n.getType())
                .message(n.getMessage())
                .isRead(n.isRead())
                .actor(ActorResponse.of(actor))
                .reviewId(n.getReviewId())
                .commentId(n.getCommentId())
                .followId(n.getFollowId())
                .createdAt(n.getCreatedAt())
                .build();
    }
}

