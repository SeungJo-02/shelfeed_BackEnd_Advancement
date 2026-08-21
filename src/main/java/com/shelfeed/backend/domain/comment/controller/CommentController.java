package com.shelfeed.backend.domain.comment.controller;

import com.shelfeed.backend.domain.comment.dto.request.CommentCreateRequest;
import com.shelfeed.backend.domain.comment.dto.request.CommentUpdateRequest;
import com.shelfeed.backend.domain.comment.dto.response.CommentCreateResponse;
import com.shelfeed.backend.domain.comment.dto.response.CommentLikeResponse;
import com.shelfeed.backend.domain.comment.dto.response.CommentListResponse;
import com.shelfeed.backend.domain.comment.dto.response.CommentUpdateResponse;
import com.shelfeed.backend.domain.comment.enums.CommentSort;
import com.shelfeed.backend.domain.comment.service.CommentService;
import com.shelfeed.backend.global.common.response.ApiResponse;
import com.shelfeed.backend.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    // 7.1 댓글 작성  POST /api/v1/reviews/{reviewId}/comments
    @PostMapping("/{reviewId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentCreateResponse> createComment(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CommentCreateRequest request) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(201, "댓글이 등록되었습니다.",
                commentService.createComment(reviewId, memberUserId, request));
    }
    /**
     * 7.2 댓글 목록 조회  GET /api/v1/reviews/{reviewId}/comments
     *
     * <p>{@code sort=top}이면 좋아요 → 대댓글 수 → 최신 순으로 정렬한다. 감상 상세가 댓글 몇 개를
     * 미리 펼쳐 보일 때 쓰는 값으로, 이 정렬은 커서를 받지 않고 {@code nextCursor}도 내려주지 않는다.
     * 기본값 {@code latest}는 기존과 같은 최신순 + 커서 페이지네이션이다.
     */
    @GetMapping("/{reviewId}/comments")
    public ApiResponse<CommentListResponse> getComments(
            @PathVariable Long reviewId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "latest") String sort,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails != null ? userDetails.getMember().getMemberUserId() : null;
        return ApiResponse.success(200,
                commentService.getComments(reviewId, cursor, limit, memberUserId, CommentSort.from(sort)));
    }
    // 7.3 댓글 수정  PUT /api/v1/reviews/{reviewId}/comments/{commentId}
    @PutMapping("/{reviewId}/comments/{commentId}")
    public ApiResponse<CommentUpdateResponse> updateComment(
            @PathVariable Long reviewId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CommentUpdateRequest request) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, "댓글이 수정되었습니다.",
                commentService.updateComment(reviewId, commentId, memberUserId, request));
    }
    // 7.4 댓글 삭제  DELETE /api/v1/reviews/{reviewId}/comments/{commentId}
    @DeleteMapping("/{reviewId}/comments/{commentId}")
    public ApiResponse<Void> deleteComment(
            @PathVariable Long reviewId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        commentService.deleteComment(reviewId, commentId, memberUserId);
        return ApiResponse.success(200, "댓글이 삭제되었습니다.");
    }

    // 7.5 댓글 좋아요  POST /api/v1/reviews/{reviewId}/comments/{commentId}/likes
    @PostMapping("/{reviewId}/comments/{commentId}/likes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CommentLikeResponse> likeComment(
            @PathVariable Long reviewId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(201, "좋아요를 눌렀습니다.",
                commentService.likeComment(reviewId, commentId, memberUserId));
    }

    // 7.6 댓글 좋아요 취소  DELETE /api/v1/reviews/{reviewId}/comments/{commentId}/likes
    @DeleteMapping("/{reviewId}/comments/{commentId}/likes")
    public ApiResponse<CommentLikeResponse> unlikeComment(
            @PathVariable Long reviewId,
            @PathVariable Long commentId,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long memberUserId = userDetails.getMember().getMemberUserId();
        return ApiResponse.success(200, "좋아요가 취소되었습니다.",
                commentService.unlikeComment(reviewId, commentId, memberUserId));
    }
}
