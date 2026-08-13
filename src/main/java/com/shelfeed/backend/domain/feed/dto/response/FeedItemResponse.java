package com.shelfeed.backend.domain.feed.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shelfeed.backend.domain.review.entity.Review;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 통합 피드의 감상 카드 한 장.
 *
 * <p>팔로잉 감상과 추천 감상이 한 피드로 합쳐졌으므로 출처와 무관하게 같은 모양이다.
 * 추천 감상에는 대응하는 Feed 행이 없어서 예전 응답에 있던 feedId 래퍼는 없앴고,
 * 커서도 feedId가 아니라 (createdAt, reviewId)를 쓴다.
 */
@Getter
@Builder
public class FeedItemResponse {

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

    public static FeedItemResponse of(Review review, boolean isLiked, List<String> tags) {
        return FeedItemResponse.builder()
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
