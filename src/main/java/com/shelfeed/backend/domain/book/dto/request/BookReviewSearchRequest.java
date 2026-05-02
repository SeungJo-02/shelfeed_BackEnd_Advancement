package com.shelfeed.backend.domain.book.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BookReviewSearchRequest { //API 명세서 속 쿼리 파라미터 용
    @Schema(defaultValue = "latest", allowableValues = {"latest", "popular", "rating_high", "rating_low"})
    private String sort = "latest";  // latest, popular, rating_high, rating_low

    @Schema(description = "페이지네이션 커서 — latest/popular: 직전 마지막 reviewId, rating_*: 직전 마지막 reviewId", nullable = true)
    private Long cursor;

    @Schema(description = "평점순 전용 커서 — 직전 마지막 항목의 rating (rating_high / rating_low 에서만 사용)", nullable = true)
    private Integer cursorRating;

    @Schema(description = "인기순 전용 커서 — 직전 마지막 항목의 likeCount (popular 에서만 사용)", nullable = true)
    private Integer cursorLike;

    @Schema(defaultValue = "20", minimum = "1", maximum = "50")
    private int limit = 20; // 기본 20, 최대 50
}
