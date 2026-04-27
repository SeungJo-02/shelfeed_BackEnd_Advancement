package com.shelfeed.backend.domain.notification;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.member.repository.MemberRepository;
import com.shelfeed.backend.domain.notification.dto.response.NotificationListResponse;
import com.shelfeed.backend.domain.notification.dto.response.UnreadCountResponse;
import com.shelfeed.backend.domain.notification.entity.Notification;
import com.shelfeed.backend.domain.notification.enums.NotificationType;
import com.shelfeed.backend.domain.notification.repository.NotificationRepository;
import com.shelfeed.backend.domain.notification.service.NotificationService;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock MemberRepository memberRepository;

    @InjectMocks NotificationService notificationService;

    private Member receiver;
    private Member actor;
    private Notification followNotification;

    @BeforeEach
    void setUp() {
        receiver = Member.createLocal(1L, "receiver@test.com", "encoded", "수신자", "bio");
        actor    = Member.createLocal(2L, "actor@test.com",   "encoded", "행위자", "bio");

        followNotification = Notification.createUserNotification(receiver, actor, NotificationType.FOLLOW, null);
        ReflectionTestUtils.setField(followNotification, "notificationId", 10L);
    }

    // ────────────────────────────────────────────────────────
    // getMyNotifications()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("알림 목록 조회")
    class GetMyNotifications {

        @Test
        @DisplayName("limit이 0 이하이면 INVALID_INPUT 예외가 발생한다")
        void limit_0이하_예외() {
            assertThatThrownBy(() -> notificationService.getMyNotifications(1L, null, 0))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원_없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.getMyNotifications(1L, null, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("cursor가 null이면 첫 페이지를 반환한다")
        void cursor_null_첫페이지_반환() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.findMyNotifications(eq(receiver), isNull(), any(Pageable.class)))
                    .willReturn(List.of(followNotification));

            NotificationListResponse response = notificationService.getMyNotifications(1L, null, 20);

            assertThat(response.getContent()).hasSize(1);
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("알림이 없으면 빈 목록과 hasNext=false를 반환한다")
        void 알림_없으면_빈목록() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.findMyNotifications(eq(receiver), isNull(), any(Pageable.class)))
                    .willReturn(List.of());

            NotificationListResponse response = notificationService.getMyNotifications(1L, null, 20);

            assertThat(response.getContent()).isEmpty();
            assertThat(response.isHasNext()).isFalse();
            assertThat(response.getSize()).isZero();
        }

        @Test
        @DisplayName("결과가 limit보다 많으면 hasNext=true이고 nextCursor가 설정된다")
        void 다음_페이지_존재시_hasNext_true() {
            // limit=2, 리포지토리는 limit+1=3개 반환
            Notification n1 = Notification.createUserNotification(receiver, actor, NotificationType.REVIEW_LIKE, null);
            Notification n2 = Notification.createUserNotification(receiver, actor, NotificationType.COMMENT, null);
            Notification n3 = Notification.createUserNotification(receiver, actor, NotificationType.FOLLOW, null);
            ReflectionTestUtils.setField(n1, "notificationId", 30L);
            ReflectionTestUtils.setField(n2, "notificationId", 29L);
            ReflectionTestUtils.setField(n3, "notificationId", 28L);

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.findMyNotifications(eq(receiver), isNull(), any(Pageable.class)))
                    .willReturn(List.of(n1, n2, n3));

            NotificationListResponse response = notificationService.getMyNotifications(1L, null, 2);

            assertThat(response.getContent()).hasSize(2);
            assertThat(response.isHasNext()).isTrue();
            assertThat(response.getNextCursor()).isNotNull();
        }

        @Test
        @DisplayName("유효한 cursor가 주어지면 해당 페이지를 반환한다")
        void 유효한_cursor_페이지_반환() {
            String validCursor = Base64.getEncoder()
                    .encodeToString("{\"id\":29}".getBytes(StandardCharsets.UTF_8));

            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.findMyNotifications(eq(receiver), eq(29L), any(Pageable.class)))
                    .willReturn(List.of(followNotification));

            NotificationListResponse response = notificationService.getMyNotifications(1L, validCursor, 20);

            assertThat(response.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("잘못된 형식의 cursor이면 INVALID_INPUT 예외가 발생한다")
        void 잘못된_cursor_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));

            assertThatThrownBy(() -> notificationService.getMyNotifications(1L, "invalid-cursor!!!", 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    // ────────────────────────────────────────────────────────
    // markAsRead()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("존재하지 않는 알림이면 NOTIFICATION_NOT_FOUND 예외가 발생한다")
        void 알림_없음_예외() {
            given(notificationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.markAsRead(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("본인 알림이 아니면 FORBIDDEN 예외가 발생한다")
        void 타인_알림_읽음처리_예외() {
            given(notificationRepository.findById(10L)).willReturn(Optional.of(followNotification));

            // actor(memberUserId=2L)가 receiver(memberUserId=1L)의 알림을 읽음 처리 시도
            assertThatThrownBy(() -> notificationService.markAsRead(2L, 10L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("본인 알림을 읽음 처리하면 isRead가 true로 변경된다")
        void 본인_알림_읽음처리_성공() {
            given(notificationRepository.findById(10L)).willReturn(Optional.of(followNotification));

            assertThatNoException().isThrownBy(() -> notificationService.markAsRead(1L, 10L));

            assertThat(followNotification.isRead()).isTrue();
        }

        @Test
        @DisplayName("이미 읽은 알림을 다시 읽음 처리해도 예외 없이 처리된다")
        void 이미_읽은_알림_재처리_허용() {
            followNotification.beReaded();
            given(notificationRepository.findById(10L)).willReturn(Optional.of(followNotification));

            assertThatNoException().isThrownBy(() -> notificationService.markAsRead(1L, 10L));

            assertThat(followNotification.isRead()).isTrue();
        }
    }

    // ────────────────────────────────────────────────────────
    // getUnreadCount()
    // ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("미읽음 알림 개수 조회")
    class GetUnreadCount {

        @Test
        @DisplayName("존재하지 않는 회원이면 MEMBER_NOT_FOUND 예외가 발생한다")
        void 회원_없음_예외() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> notificationService.getUnreadCount(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
        }

        @Test
        @DisplayName("미읽음 알림 개수를 반환한다")
        void 미읽음_개수_반환() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.countUnread(receiver)).willReturn(3L);

            UnreadCountResponse response = notificationService.getUnreadCount(1L);

            assertThat(response.getUnreadCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("미읽음 알림이 없으면 0을 반환한다")
        void 미읽음_없으면_0반환() {
            given(memberRepository.findByMemberUserId(1L)).willReturn(Optional.of(receiver));
            given(notificationRepository.countUnread(receiver)).willReturn(0L);

            UnreadCountResponse response = notificationService.getUnreadCount(1L);

            assertThat(response.getUnreadCount()).isZero();
        }
    }
}
