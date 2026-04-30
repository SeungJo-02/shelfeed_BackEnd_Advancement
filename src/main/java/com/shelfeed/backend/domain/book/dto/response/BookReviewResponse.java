package com.shelfeed.backend.domain.book.dto.response;

import com.shelfeed.backend.domain.review.entity.Review;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BookReviewResponse {
    private Long reviewId;
    private UserInfo user;
    private int rating;
    private String content;
    private String quote;
    private Boolean isSpoiler;
    private int likeCount;
    private int commentCount;
    private Boolean isLiked;        //ReviewLikeRepository 연결 후 적용
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    public static BookReviewResponse of(Review review, boolean isLiked) {
        return BookReviewResponse.builder()
                .reviewId(review.getReviewId())
                .user(UserInfo.builder()
                        .userId(review.getMember().getMemberUserId())
                        .nickname(review.getMember().getNickname())
                        .profileImageUrl(review.getMember().getProfileImageUrl())
                        .build())
                .rating(review.getRating())
                .content(review.getContent())
                .quote(review.getQuote())
                .isSpoiler(review.isSpoiler())
                .likeCount(review.getLikeCount())
                .commentCount(review.getCommentCount())
                .isLiked(isLiked)
                .createdAt(review.getCreatedAt())
                .build();
    }
}
