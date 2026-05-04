package com.shelfeed.backend.domain.feed.controller;

import com.shelfeed.backend.domain.feed.dto.response.FeedListResponse;
import com.shelfeed.backend.domain.feed.dto.response.RecommendFeedResponse;
import com.shelfeed.backend.domain.feed.service.FeedService;
import com.shelfeed.backend.domain.feed.service.RecommendationService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
@RequiredArgsConstructor
public class FeedController {
    private final FeedService feedService;
    private final RecommendationService recommendationService;

    // 9.1 팔로잉 피드  GET /api/v1/feed/following
    @GetMapping("/following")
    public ApiResponse<FeedListResponse> getFollowingFeed(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,
                feedService.getFollowingFeed(memberUserId, cursor, limit));
    }

    // 9.2 추천 피드  GET /api/v1/feed/recommend
    @GetMapping("/recommend")
    public ApiResponse<RecommendFeedResponse> getRecommendedFeed(
            @RequestParam(required = false) Integer cursorLike,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200,
                recommendationService.getRecommendedFeed(memberUserId, cursorLike, cursorId, limit));
    }
}
