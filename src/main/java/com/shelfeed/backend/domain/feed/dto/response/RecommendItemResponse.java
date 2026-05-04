package com.shelfeed.backend.domain.feed.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shelfeed.backend.domain.review.entity.Review;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class RecommendItemResponse {

    private Long reviewId;
    private UserInfo user;
    private BookInfo book;
    private byte rating;
    private String content;
    private String quote;
    @JsonProperty("isSpoiler")
    private boolean isSpoiler;
    private int likeCount;
    private int commentCount;
    @JsonProperty("isLiked")
    private boolean isLiked;
    private List<String> tags;
    private LocalDateTime createdAt;

    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    @Getter
    @Builder
    public static class BookInfo {
        private Long bookId;
        private String title;
        private String author;
        private String coverImageUrl;
        private String category;
    }

    public static RecommendItemResponse of(Review review, boolean isLiked, List<String> tags) {
        return RecommendItemResponse.builder()
                .reviewId(review.getReviewId())
                .user(UserInfo.builder()
                        .userId(review.getMember().getMemberUserId())
                        .nickname(review.getMember().getNickname())
                        .profileImageUrl(review.getMember().getProfileImageUrl())
                        .build())
                .book(BookInfo.builder()
                        .bookId(review.getBook().getBookId())
                        .title(review.getBook().getTitle())
                        .author(review.getBook().getAuthor())
                        .coverImageUrl(review.getBook().getCoverImageUrl())
                        .category(review.getBook().getCategory())
                        .build())
                .rating(review.getRating())
                .content(review.getContent())
                .quote(review.getQuote())
                .isSpoiler(review.isSpoiler())
                .likeCount(review.getLikeCount())
                .commentCount(review.getCommentCount())
                .isLiked(isLiked)
                .tags(tags)
                .createdAt(review.getCreatedAt())
                .build();
    }
}
