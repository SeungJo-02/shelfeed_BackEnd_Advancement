package com.shelfeed.backend.domain.book.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class BookReviewListResponse {
    private List<BookReviewResponse> content;
    private Long nextCursor;
    private Integer nextCursorRating;
    private Integer nextCursorLike;
    private boolean hasNext;
    private int size;

    public static BookReviewListResponse of(List<BookReviewResponse> content, int limit) {
        return of(content, limit, false, false);
    }

    public static BookReviewListResponse of(List<BookReviewResponse> content, int limit, boolean ratingSort, boolean popularSort) {
        boolean hasNext = content.size() > limit;
        List<BookReviewResponse> result = hasNext ? content.subList(0, limit) : content;
        Long nextCursor = hasNext ? result.get(result.size() - 1).getReviewId() : null;
        Integer nextCursorRating = (hasNext && ratingSort) ? result.get(result.size() - 1).getRating() : null;
        Integer nextCursorLike = (hasNext && popularSort) ? result.get(result.size() - 1).getLikeCount() : null;

        return BookReviewListResponse.builder()
                .content(result)
                .nextCursor(nextCursor)
                .nextCursorRating(nextCursorRating)
                .nextCursorLike(nextCursorLike)
                .hasNext(hasNext)
                .size(result.size())
                .build();
    }






}
