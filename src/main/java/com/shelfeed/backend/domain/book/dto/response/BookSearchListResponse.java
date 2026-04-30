package com.shelfeed.backend.domain.book.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class BookSearchListResponse {
    private List<BookSummaryResponse> content;
    private Long nextCursor;
    private boolean hasNext;
    private int size;

    public static BookSearchListResponse of(List<BookSummaryResponse>content, int limit){
        boolean hasNext = content.size()>limit;
        List<BookSummaryResponse> result = hasNext ? content.subList(0, limit) : content;
        Long nextCursor = hasNext ? result.get(result.size() -1).getBookId() : null;

    return BookSearchListResponse.builder()
            .content(result)
            .nextCursor(nextCursor)
            .hasNext(hasNext)
            .size(result.size())
            .build();
    }
}
