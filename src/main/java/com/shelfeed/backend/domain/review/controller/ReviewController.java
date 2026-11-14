package com.shelfeed.backend.domain.review.controller;

import com.shelfeed.backend.domain.member.entity.Member;
import com.shelfeed.backend.domain.review.dto.request.ReviewCreateRequest;
import com.shelfeed.backend.domain.review.dto.request.ReviewUpdateRequest;
import com.shelfeed.backend.domain.review.dto.response.*;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.service.ReviewLikeService;
import com.shelfeed.backend.domain.review.service.ReviewService;
import com.shelfeed.backend.domain.review.viewcount.ViewCountService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
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
    private final ViewCountService viewCountService;

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
        // [실험 H] 인증 단계에서 이미 회원을 들고 있다. 다시 조회하면 그만큼 커넥션을 빌리므로 그대로 넘긴다.
        Member requester = userDetails != null ? userDetails.getMember() : null;//비회원 접근 허용
        return ApiResponse.success(200, reviewService.getReview(reviewId, requester));
    }
    // 2-1. 조회 기록  POST /api/v1/reviews/{reviewId}/view
    // 본문을 3초 이상 본 시점에 화면이 호출한다. 상세 조회(GET)는 순수 읽기로 두고
    // 조회수 집계는 이 요청 하나로 모은다. MySQL을 건드리지 않으므로 쥐는 락이 없다.
    @PostMapping("/reviews/{reviewId}/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordView(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestHeader(value = "X-Viewer-Key", required = false) String viewerKey,
            HttpServletRequest request){
        String viewer = userDetails != null
                ? String.valueOf(userDetails.getMember().getMemberUserId())
                : (viewerKey != null ? viewerKey : request.getRemoteAddr());//비회원은 IP로 식별
        viewCountService.record(reviewId, viewer);
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
