package com.shelfeed.backend.domain.feed.controller;

import com.shelfeed.backend.domain.feed.dto.response.FeedListResponse;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    /**
     * 9. 통합 피드 GET /api/v1/feed
     *
     * <p>팔로우한 사람의 감상과 추천 감상을 한 피드로 합쳐 작성 시각 최신순으로 반환한다.
     * 커서는 직전 페이지 마지막 항목의 (createdAt, reviewId) 한 쌍이며 둘 다 넘기거나
     * 둘 다 생략해야 한다.
     */
    @GetMapping
    public ApiResponse<FeedListResponse> getFeed(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime cursorCreatedAt,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,
                feedService.getFeed(memberUserId, cursorCreatedAt, cursorId, limit));
    }
}
