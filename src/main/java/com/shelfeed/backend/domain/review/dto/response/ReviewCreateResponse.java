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
public class ReviewCreateResponse {
    private Long reviewId;
    private Long bookId;
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
    private List<String> tags;
    private LocalDateTime createdAt;

    // TODO: tags 파라미터 현재 미사용 — 태그 저장 로직 연동 후 응답에 포함
    public static ReviewCreateResponse of(Review review, List<String> tags) {
        return ReviewCreateResponse.builder()
                .reviewId(review.getReviewId())
                .bookId(review.getBook().getBookId())
                .rating(review.getRating())
                .content(review.getContent())
                .quote(review.getQuote())
                .readPages(review.getReadPages())
                .isSpoiler(review.isSpoiler())
                .reviewVisibility(review.getReviewVisibility())
                .reviewStatus(review.getReviewStatus())
                .likeCount(review.getLikeCount())
                .commentCount(review.getCommentCount())
                .tags(tags)
                .createdAt(review.getCreatedAt())
                .build();
    }

}
