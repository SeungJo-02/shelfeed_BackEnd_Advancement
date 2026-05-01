package com.shelfeed.backend.domain.notification.entity;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.notification.enums.NotificationType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    // 알림 받는 사람
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member receiver;

    // 알림 유발 행위자 (시스템 알림은 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_member_id")
    private Member actor;

    // 약한 결합을 통해 유연성 확보
    @Column(name = "review_id")
    private Long reviewId;

    @Column(name = "review_like_id")
    private Long reviewLikeId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "comment_like_id")
    private Long commentLikeId;

    @Column(name = "follow_id")
    private Long followId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType type;

    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private boolean isRead = false;

    @Column(nullable = false)
    private boolean isDeleted = false;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 1. 정적메서드 유저 간의 알림 (REVIEW_LIKE, FOLLOW)
    public static Notification createUserNotification(Member receiver, Member actor, NotificationType type, Long targetId) {
        if (actor == null) {
            throw new IllegalArgumentException("유저 알림은 반드시 유저가 필요합니다.");
        }
        Notification noti = new Notification();
        noti.receiver = receiver;
        noti.actor = actor;
        noti.type = type;
        switch (type) {
            case REVIEW_LIKE -> noti.reviewId = targetId;
            case FOLLOW      -> noti.followId = targetId;
            default          -> {}
        }
        return noti;
    }

    // 2. 댓글 관련 알림 (COMMENT, COMMENT_LIKE) — reviewId + commentId 둘 다 필요
    public static Notification createCommentNotification(Member receiver, Member actor, NotificationType type, Long reviewId, Long commentId) {
        if (actor == null) {
            throw new IllegalArgumentException("유저 알림은 반드시 유저가 필요합니다.");
        }
        Notification noti = new Notification();
        noti.receiver = receiver;
        noti.actor = actor;
        noti.type = type;
        noti.reviewId = reviewId;
        noti.commentId = commentId;
        return noti;
    }

    // 2. 정적메서스 시스템(푸시) 알림
    public static Notification createSystemNotification(Member receiver, NotificationType type, String message) {
        Notification noti = new Notification();
        noti.receiver = receiver;
        noti.type = type;
        noti.message = message;
        return noti;
    }

    //비즈니스 메서드
    //읽음 처리
    public void beReaded(){this.isRead = true;}
    //소프트 삭제
    public void delete (){this.isDeleted = true;}
}
