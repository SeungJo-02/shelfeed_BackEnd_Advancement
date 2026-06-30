package com.shelfeed.backend.domain.review.controller;

import com.shelfeed.backend.domain.review.dto.request.ReviewCreateRequest;
import com.shelfeed.backend.domain.review.dto.request.ReviewUpdateRequest;
import com.shelfeed.backend.domain.review.dto.response.*;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.service.ReviewLikeService;
import com.shelfeed.backend.domain.review.service.ReviewService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewLikeService reviewLikeService;

    // 1. 감상 작성  POST /api/v1/reviews
    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewCreateResponse> createReview(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ReviewCreateRequest request){
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(201,"감상이 작성되었습니다.", reviewService.createReview(memberUserId,request));
    }

    // 2. 감상 상세 조회  GET /api/v1/reviews/{reviewId}
    @GetMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewDetailResponse> getReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails){
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;//비회원 접근 허용
        return ApiResponse.success(200, reviewService.getReview(reviewId,memberUserId));
    }
    // 3. 감상 수정  PUT /api/v1/reviews/{reviewId}
    @PutMapping("/reviews/{reviewId}")
    public ApiResponse<ReviewUpdateResponse> updateReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ReviewUpdateRequest request) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, "감상이 수정되었습니다.", reviewService.updateReview(reviewId,memberUserId,request));
    }
    // 4. 감상 삭제  DELETE /api/v1/reviews/{reviewId}
    @DeleteMapping("/reviews/{reviewId}")
    public ApiResponse<Void> deleteReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        reviewService.deleteReview(reviewId,memberUserId);
        return ApiResponse.success(200, "감상이 삭제되었습니다.");
    }

    //5. 내 감상 목록
    @GetMapping("/reviews/me")
    public ApiResponse<List<ReviewSummaryResponse>> getMyReviews(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, reviewService.getMyReviews(memberUserId,status,cursor,limit));
    }

    //6. 타 유저 감상 목록
    @GetMapping("/members/{userId}/reviews")
    public ApiResponse<List<ReviewSummaryResponse>> getUserReviews(
            @PathVariable Long userId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit){
        Long requestingUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200, reviewService.getUserReviews(userId, requestingUserId, cursor, limit));
    }

    //7. 감상 좋아요
    @PostMapping("/reviews/{reviewId}/likes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReviewLikeResponse> likeReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(201, "좋아요를 눌렀습니다.",
                reviewLikeService.like(reviewId, memberUserId));
    }

    //8. 감상 좋아요 취소
    @DeleteMapping("/reviews/{reviewId}/likes")
    public ApiResponse<ReviewLikeResponse> unlikeReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, "좋아요가 취소되었습니다.",
                reviewLikeService.unlike(reviewId, memberUserId));
    }
}
