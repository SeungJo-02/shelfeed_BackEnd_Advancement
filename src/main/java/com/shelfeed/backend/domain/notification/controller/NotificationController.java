package com.shelfeed.backend.domain.notification.controller;

import com.shelfeed.backend.domain.notification.dto.response.NotificationListResponse;
import com.shelfeed.backend.domain.notification.dto.response.UnreadCountResponse;
import com.shelfeed.backend.domain.notification.service.NotificationService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {

    private final NotificationService notificationService;

    // 알림 목록 조회  GET /api/v1/notifications?cursor=...&limit=20
    @GetMapping
    public ApiResponse<NotificationListResponse> getMyNotifications(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,
                notificationService.getMyNotifications(memberUserId, cursor, limit));
    }

    // 알림 읽음 처리  PATCH /api/v1/notifications/{notificationId}/read
    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markAsRead(
            @PathVariable @Positive(message = "유효하지 않은 알림 ID입니다.") Long notificationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        notificationService.markAsRead(memberUserId, notificationId);
        return ApiResponse.success(200, "알림을 읽음 처리했습니다.");
    }

    // 전체 알림 읽음 처리  PATCH /api/v1/notifications/read-all
    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllAsRead(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        notificationService.markAllAsRead(memberUserId);
        return ApiResponse.success(200, "모든 알림을 읽음 처리했습니다.");
    }

    // 미읽음 알림 개수 조회  GET /api/v1/notifications/unread-count
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> getUnreadCount(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, notificationService.getUnreadCount(memberUserId));
    }
}

