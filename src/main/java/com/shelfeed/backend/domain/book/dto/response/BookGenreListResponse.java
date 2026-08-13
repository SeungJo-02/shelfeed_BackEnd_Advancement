package com.shelfeed.backend.domain.book.dto.response;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 장르별 도서 목록 응답.
 *
 * <p>검색과 달리 커서가 아니라 페이지 번호로 넘긴다. 이 목록을 쓰는 화면(빈 피드의
 * "다른 책")이 순서대로 넘기는 게 아니라 아무 페이지나 집어 보여주기 때문이다.
 * 그래서 {@code totalPages}를 함께 내려 클라이언트가 고를 범위를 알 수 있게 한다.
 */
@Getter
@Builder
public class BookGenreListResponse {

    private List<BookSummaryResponse> content;
    /** 1부터 시작하는 현재 페이지 번호. */
    private int page;
    private int totalPages;
    private long totalElements;
    private boolean hasNext;
    private int size;

    public static BookGenreListResponse of(Page<?> page, List<BookSummaryResponse> content) {
        return BookGenreListResponse.builder()
                .content(content)
                .page(page.getNumber() + 1) // Page는 0부터라 노출값만 1부터로 맞춘다
                .totalPages(page.getTotalPages())
                .totalElements(page.getTotalElements())
                .hasNext(page.hasNext())
                .size(content.size())
                .build();
    }
}
