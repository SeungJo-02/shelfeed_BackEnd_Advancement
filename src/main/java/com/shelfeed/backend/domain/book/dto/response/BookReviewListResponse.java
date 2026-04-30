package com.shelfeed.backend.domain.book.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class BookReviewListResponse {
    private List<BookReviewResponse> content;
    private Long nextCursor;
    private boolean hasNext;
    private int size;


    public static BookReviewListResponse of(List<BookReviewResponse> content, int limit){
        boolean hasNext = content.size()>limit;
        List<BookReviewResponse> result = hasNext ? content.subList(0, limit) : content;
        Long nextCursor = hasNext ? result.get(result.size() -1).getReviewId() : null;

        return BookReviewListResponse.builder()
                .content(result)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(result.size())
                .build();
    }






}
