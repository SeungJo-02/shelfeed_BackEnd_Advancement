package com.shelfeed.backend.domain.library.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class LibraryListResponse {
    private List<LibraryBookSummaryResponse> content;
    private Long nextCursor;
    private boolean hasNext;
    private int size;

    public static LibraryListResponse of(List<LibraryBookSummaryResponse> content, int limit) {
        boolean hasNext = content.size() > limit; //limit 보다 크면 다음페이지 있는 거 안글면 없는 거
        List<LibraryBookSummaryResponse> result = hasNext ? content.subList(0, limit) : content;//다음 페이지 있으면 잘라서, 없으면 싹다 주기
        Long nextCursor = hasNext ? result.get(result.size() - 1).getLibraryBookId() : null;// 다음페이지의 기준점 조회

        return LibraryListResponse.builder()
                .content(result)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .size(result.size())
                .build();
    }
}
