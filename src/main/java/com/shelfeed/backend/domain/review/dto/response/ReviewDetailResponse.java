package com.shelfeed.backend.domain.review.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shelfeed.backend.domain.review.entity.Review;
import com.shelfeed.backend.domain.review.enums.ReviewStatus;
import com.shelfeed.backend.domain.review.enums.ReviewVisibility;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ReviewDetailResponse {
    private Long reviewId;
    private UserInfo user;
    private BookInfo book;
    private byte rating;
    private String content;
    private String quote;
    private Integer readPages;
    @JsonProperty("isSpoiler")
    private boolean isSpoiler;
    private ReviewVisibility reviewVisibility;
    private ReviewStatus reviewStatus;
    private int likeCount;
    private int commentCount;
    @JsonProperty("isLiked")
    private boolean isLiked;    // Like 도메인 구현 후 연결
    @JsonProperty("isMine")
    private boolean isMine;
    private List<String> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Getter
    @Builder
    public static class UserInfo {
        private Long userId;
        private String nickname;
        private String profileImageUrl;
    }

    @Getter
    @Builder
    public static class BookInfo{
        private Long bookId;
        private String isbn13;
        private String title;
        private String author;
        private String coverImageUrl;
    }

    public static ReviewDetailResponse of(Review review, List<String> tags, boolean isMine, boolean isLiked){
        return ReviewDetailResponse.builder()
                .reviewId(review.getReviewId())
                .user(UserInfo.builder()
                        .userId(review.getMember().getMemberUserId())
                        .nickname(review.getMember().getNickname())
                        .profileImageUrl(review.getMember().getProfileImageUrl())
                        .build())
                .book(BookInfo.builder()
                        .bookId(review.getBook().getBookId())
                        .isbn13(review.getBook().getIsbn13())
                        .title(review.getBook().getTitle())
                        .author(review.getBook().getAuthor())
                        .coverImageUrl(review.getBook().getCoverImageUrl())
                        .build())
                .rating(review.getRating())
                .content(review.getContent())
                .quote(review.getQuote())
                .readPages(review.getReadPages())
                .isSpoiler(review.isSpoiler())
                .reviewVisibility(review.getReviewVisibility())
                .reviewStatus(review.getReviewStatus())
                .likeCount(review.getLikeCount())
                .commentCount(review.getCommentCount())
                .isLiked(isLiked)
                .isMine(isMine)
                .tags(tags)
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}
